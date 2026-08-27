package com.example.zesto.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.example.zesto.decoder.DecoderState
import com.example.zesto.decoder.DecoderStats
import com.example.zesto.frame.FrameSourceMode
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Zesto RTSP playback engine.
 *
 * Responsibilities:
 * - RTSP playback through Media3 ExoPlayer
 * - RTP-over-TCP / RTP-over-UDP selection
 * - Hardware decoder selection with fallback
 * - Real rendered-frame and dropped-frame telemetry via SurfaceTexture frame listeners
 * - Real Media3 playback error reporting
 * - Automatic reconnect with exponential backoff
 * - Real-time decoded VideoFrame delivery to downstream FramePipeline
 */
@OptIn(UnstableApi::class)
class RTSPPlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.Main.immediate)
) {
    var diagnosticsManager: com.example.zesto.diagnostics.DiagnosticsManager? = null

    private var exoPlayer: ExoPlayer? = null
    private var offscreenFrameExtractor: OffscreenFrameExtractor? = null
    private var internalSurface: Surface? = null
    private var frameListener: ((VideoFrame) -> Unit)? = null

    val player: ExoPlayer?
        get() = exoPlayer

    private val _streamState =
        MutableStateFlow<StreamState>(
            StreamState.Disconnected
        )

    val streamState: StateFlow<StreamState> =
        _streamState.asStateFlow()

    private val _streamStats =
        MutableStateFlow(StreamStats())

    val streamStats: StateFlow<StreamStats> =
        _streamStats.asStateFlow()

    private val _decoderState =
        MutableStateFlow<DecoderState>(
            DecoderState.Uninitialized
        )

    val decoderState: StateFlow<DecoderState> =
        _decoderState.asStateFlow()

    private val _decoderStats =
        MutableStateFlow(DecoderStats())

    val decoderStats: StateFlow<DecoderStats> =
        _decoderStats.asStateFlow()

    private var activeConfig: StreamConfig? = null

    private var reconnectJob: Job? = null
    private var statsMonitorJob: Job? = null

    private var reconnectAttempts = 0

    private val renderedFramesCount =
        AtomicLong(0L)

    private val droppedFramesCount =
        AtomicLong(0L)

    private val decodeErrorCount =
        AtomicLong(0L)

    private var lastFpsTimestamp =
        System.currentTimeMillis()

    private var framesSinceLastFps =
        0L

    private var currentVideoWidth =
        0

    private var currentVideoHeight =
        0

    private var decoderName =
        "Unknown"

    init {
        initializePlayer()
    }

    fun setFrameListener(listener: ((VideoFrame) -> Unit)?) {
        this.frameListener = listener
    }

    /**
     * Authoritative frame delivery from hardware/software decoder into the frame pipeline.
     */
    fun deliverDecodedFrame(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        timestampUs: Long = System.nanoTime() / 1000L
    ) {
        val isValid = !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
        if (!isValid) {
            Log.w(TAG, "[ZESTO_FRAME_INVALID] Frame contains invalid bitmap (recycled=${bitmap.isRecycled}, ${bitmap.width}x${bitmap.height})")
            return
        }

        val count = renderedFramesCount.incrementAndGet()
        framesSinceLastFps++
        if (width > 0) currentVideoWidth = width
        if (height > 0) currentVideoHeight = height

        if (count == 1L || count % 60L == 0L) {
            Log.i(TAG, "[RTSP_FRAME_DECODED] id=$count width=$width height=$height timestamp=$timestampUs BITMAP_VALID=true")
            Log.i(TAG, "[RTSP_FRAME_PIXELS_READY] id=$count width=$width height=$height BITMAP_WIDTH=$width BITMAP_HEIGHT=$height FRAME_ID=$count BITMAP_VALID=true")
            diagnosticsManager?.recordBoundaryStage(com.example.zesto.diagnostics.BoundaryDiagnosticStage.VIDEO_FRAME_DECODED)
        }

        val frame = VideoFrame(
            frameNumber = count,
            timestampUs = timestampUs,
            width = width,
            height = height,
            pixelFormat = PixelFormat.RGBA_8888,
            bitmap = bitmap,
            sourceMode = FrameSourceMode.RTSP
        )

        if (count == 1L || count % 60L == 0L) {
            Log.i(TAG, "[FRAME_PIPELINE_DISPATCH] id=$count width=$width height=$height timestamp=$timestampUs")
        }

        frameListener?.invoke(frame)
    }

    private fun initializePlayer() {
        if (exoPlayer != null) {
            return
        }

        try {
            val renderersFactory =
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(
                        DefaultRenderersFactory
                            .EXTENSION_RENDERER_MODE_PREFER
                    )

            val playerInstance =
                ExoPlayer.Builder(
                    context,
                    renderersFactory
                ).build()

            // Attach offscreen GL Frame Extractor to receive and convert hardware-decoded frames directly
            try {
                val initialW = if (currentVideoWidth > 0) currentVideoWidth else 1280
                val initialH = if (currentVideoHeight > 0) currentVideoHeight else 720
                val extractor = OffscreenFrameExtractor(initialW, initialH) { bmp, w, h, ts ->
                    deliverDecodedFrame(bmp, w, h, ts)
                }
                offscreenFrameExtractor = extractor
                val surf = extractor.surface
                internalSurface = surf
                if (surf != null) {
                    playerInstance.setVideoSurface(surf)
                    Log.i(TAG, "[DECODER_SURFACE_ATTACHED] Offscreen hardware decoder surface attached (${initialW}x${initialH})")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "OffscreenFrameExtractor init fallback: ${e.message}")
            }

            playerInstance.addListener(
                object : Player.Listener {

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        for (group in tracks.groups) {
                            if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                                for (i in 0 until group.length) {
                                    if (group.isTrackSelected(i)) {
                                        val format = group.getTrackFormat(i)
                                        val mime = format.sampleMimeType ?: "video/avc"
                                        val codec = format.codecs ?: "unknown"
                                        val w = format.width
                                        val h = format.height
                                        val fps = format.frameRate
                                        val bitrate = format.bitrate

                                        if (w > 0) currentVideoWidth = w
                                        if (h > 0) currentVideoHeight = h
                                        if (w > 0 && h > 0) {
                                            offscreenFrameExtractor?.updateDimensions(w, h)
                                        }

                                        diagnosticsManager?.logger?.info(
                                            com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                                            "[VIDEO_TRACK_DETECTED] SDP Video Track Detected: MIME=$mime, Codec=$codec, Resolution=${w}x${h}@${fps}fps, Bitrate=$bitrate bps"
                                        )
                                        diagnosticsManager?.recordBoundaryStage(
                                            com.example.zesto.diagnostics.BoundaryDiagnosticStage.VIDEO_TRACK_DETECTED
                                        )

                                        _decoderState.value = DecoderState.Configured(
                                            mimeType = mime,
                                            width = if (w > 0) w else 1280,
                                            height = if (h > 0) h else 720
                                        )
                                        _decoderStats.update {
                                            it.copy(
                                                width = if (w > 0) w else it.width,
                                                height = if (h > 0) h else it.height,
                                                pixelFormat = mime
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(
                        playbackState: Int
                    ) {
                        when (playbackState) {

                            Player.STATE_IDLE -> {
                                if (
                                    _streamState.value
                                        is StreamState.Connected
                                ) {
                                    _streamState.value =
                                        StreamState.Disconnected
                                }
                                diagnosticsManager?.removeBoundaryStage(
                                    com.example.zesto.diagnostics.BoundaryDiagnosticStage.RTSP_CONNECTED
                                )
                            }

                            Player.STATE_BUFFERING -> {
                                if (
                                    _streamState.value
                                        !is StreamState.Reconnecting
                                ) {
                                    _streamState.value =
                                        StreamState.Connecting
                                }

                                diagnosticsManager?.logger?.info(
                                    com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                                    "RTSP DESCRIBE & SETUP complete. Buffering RTP packet stream..."
                                )

                                _decoderState.value =
                                    DecoderState.Configured(
                                        "video/avc",
                                        if (currentVideoWidth > 0) currentVideoWidth else 1280,
                                        if (currentVideoHeight > 0) currentVideoHeight else 720
                                    )
                            }

                            Player.STATE_READY -> {
                                val isPlaying = exoPlayer?.isPlaying == true
                                val url = activeConfig?.url.orEmpty()

                                if (isPlaying) {
                                    _streamState.value = StreamState.Connected(url)
                                    _decoderState.value = DecoderState.Running
                                    reconnectAttempts = 0

                                    diagnosticsManager?.logger?.info(
                                        com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                                        "[RTSP_CONNECTED] RTSP connection established and RTP media stream active: $url"
                                    )
                                    diagnosticsManager?.recordBoundaryStage(
                                        com.example.zesto.diagnostics.BoundaryDiagnosticStage.RTSP_CONNECTED
                                    )
                                } else {
                                    _streamState.value = StreamState.Connecting
                                    diagnosticsManager?.logger?.info(
                                        com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                                        "RTSP player ready, awaiting media flow..."
                                    )
                                }
                            }

                            Player.STATE_ENDED -> {
                                _streamState.value =
                                    StreamState.Disconnected

                                _decoderState.value =
                                    DecoderState.Stopped

                                diagnosticsManager?.removeBoundaryStage(
                                    com.example.zesto.diagnostics.BoundaryDiagnosticStage.RTSP_CONNECTED
                                )

                                diagnosticsManager?.logger?.info(
                                    com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                                    "RTSP stream ended by remote source"
                                )
                            }
                        }
                    }

                    override fun onIsPlayingChanged(
                        isPlaying: Boolean
                    ) {
                        if (isPlaying) {
                            val url = activeConfig?.url.orEmpty()
                            _streamState.value = StreamState.Connected(url)
                            _decoderState.value = DecoderState.Running
                            reconnectAttempts = 0

                            diagnosticsManager?.logger?.info(
                                com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                                "[RTSP_CONNECTED] RTSP connection established and active RTP media playback underway: $url"
                            )
                            diagnosticsManager?.recordBoundaryStage(
                                com.example.zesto.diagnostics.BoundaryDiagnosticStage.RTSP_CONNECTED
                            )
                        } else if (exoPlayer?.playbackState != Player.STATE_BUFFERING && exoPlayer?.playbackState != Player.STATE_READY) {
                            diagnosticsManager?.removeBoundaryStage(
                                com.example.zesto.diagnostics.BoundaryDiagnosticStage.RTSP_CONNECTED
                            )
                        }
                    }

                    override fun onVideoSizeChanged(
                        videoSize: VideoSize
                    ) {
                        if (
                            videoSize.width > 0 &&
                            videoSize.height > 0
                        ) {
                            currentVideoWidth =
                                videoSize.width

                            currentVideoHeight =
                                videoSize.height

                            try {
                                offscreenFrameExtractor?.updateDimensions(videoSize.width, videoSize.height)
                            } catch (_: Exception) {}

                            _decoderStats.update {
                                it.copy(
                                    width =
                                        videoSize.width,
                                    height =
                                        videoSize.height
                                )
                            }

                            diagnosticsManager?.logger?.info(
                                com.example.zesto.diagnostics.Subsystem.DECODER,
                                "Video dimensions updated from stream: ${videoSize.width}x${videoSize.height}"
                            )
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        _decoderState.value =
                            DecoderState.Running

                        val width = if (currentVideoWidth > 0) currentVideoWidth else 1280
                        val height = if (currentVideoHeight > 0) currentVideoHeight else 720
                        val timestampUs = System.nanoTime() / 1000L

                        diagnosticsManager?.logger?.info(
                            com.example.zesto.diagnostics.Subsystem.DECODER,
                            "[VIDEO_FRAME_DECODED] First video frame decoded to hardware surface: codec=$decoderName, resolution=${width}x${height}, timestampUs=$timestampUs"
                        )
                        diagnosticsManager?.recordBoundaryStage(
                            com.example.zesto.diagnostics.BoundaryDiagnosticStage.VIDEO_FRAME_DECODED
                        )
                    }

                    override fun onPlayerError(
                        error: PlaybackException
                    ) {
                        decodeErrorCount.incrementAndGet()
                        diagnosticsManager?.removeBoundaryStage(
                            com.example.zesto.diagnostics.BoundaryDiagnosticStage.RTSP_CONNECTED
                        )

                        val message =
                            buildPlaybackErrorMessage(error)

                        _decoderStats.update {
                            it.copy(
                                decodeErrors =
                                    decodeErrorCount.get()
                            )
                        }

                        diagnosticsManager?.logger?.error(
                            com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                            "RTSP Playback Error [${error.errorCodeName} / ${error.errorCode}]: $message",
                            error.stackTraceToString()
                        )

                        handlePlaybackFailure(
                            message,
                            error
                        )
                    }
                }
            )

            playerInstance.addAnalyticsListener(
                object : AnalyticsListener {

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        if (droppedFrames <= 0) {
                            return
                        }

                        droppedFramesCount.addAndGet(
                            droppedFrames.toLong()
                        )

                        _decoderStats.update {
                            it.copy(
                                droppedFrameCount =
                                    droppedFramesCount.get()
                            )
                        }
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderNameParam: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        decoderName =
                            decoderNameParam

                        _decoderStats.update {
                            it.copy(
                                pixelFormat =
                                    decoderName
                            )
                        }

                        diagnosticsManager?.logger?.info(
                            com.example.zesto.diagnostics.Subsystem.DECODER,
                            "[DECODER_INITIALIZED] Video decoder initialized: $decoderNameParam (took ${initializationDurationMs}ms)"
                        )
                        diagnosticsManager?.recordBoundaryStage(
                            com.example.zesto.diagnostics.BoundaryDiagnosticStage.DECODER_INITIALIZED
                        )
                    }

                    override fun onVideoFrameProcessingOffset(
                        eventTime: AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int
                    ) {
                        // Diagnostic telemetry only; do not fabricate frames here
                    }
                }
            )

            exoPlayer =
                playerInstance

        } catch (e: Exception) {

            _decoderState.value =
                DecoderState.Error(
                    "Player initialization error: ${e.message}",
                    e
                )
        }
    }

    fun startStream(
        config: StreamConfig
    ) {
        activeConfig = config

        diagnosticsManager?.resetPipelineBoundaries()

        reconnectJob?.cancel()
        reconnectJob = null

        reconnectAttempts = 0

        renderedFramesCount.set(0L)
        droppedFramesCount.set(0L)
        decodeErrorCount.set(0L)

        framesSinceLastFps = 0L
        lastFpsTimestamp =
            System.currentTimeMillis()

        currentVideoWidth = 0
        currentVideoHeight = 0

        _decoderStats.value =
            DecoderStats()

        initializePlayer()

        connectInternal(config)

        startMetricsMonitor()
    }

    private fun connectInternal(
        config: StreamConfig
    ) {
        val playerInstance =
            exoPlayer ?: run {
                handlePlaybackFailure(
                    "ExoPlayer is not initialized",
                    null
                )
                return
            }

        _streamState.value =
            StreamState.Connecting

        try {
            playerInstance.stop()
            playerInstance.clearMediaItems()

            val forceTcp =
                config.protocol ==
                    TransportProtocol.RTSP_TCP

            val transportModeStr =
                if (forceTcp) "RTP/AVP/TCP (Interleaved)" else "RTP/AVP/UDP (Unicast)"

            diagnosticsManager?.logger?.info(
                com.example.zesto.diagnostics.Subsystem.TRANSPORT,
                "Connecting to RTSP URL: ${config.url} [Transport: $transportModeStr, Timeout: ${config.connectionTimeoutMs}ms]"
            )

            val mediaItem =
                MediaItem.fromUri(config.url)

            val mediaSource =
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceTcp)
                    .setTimeoutMs(
                        config.connectionTimeoutMs
                    )
                    .setUserAgent(
                        "Zesto/1.0 (Android Systems Pipeline)"
                    )
                    .createMediaSource(mediaItem)

            playerInstance.setMediaSource(
                mediaSource
            )

            playerInstance.prepare()

            playerInstance.playWhenReady =
                true

        } catch (e: Exception) {

            handlePlaybackFailure(
                "Failed to connect to RTSP source: ${e.message}",
                e
            )
        }
    }

    private fun handlePlaybackFailure(
        reason: String,
        cause: Throwable?
    ) {
        val config =
            activeConfig

        if (
            config != null &&
            config.autoReconnect &&
            reconnectAttempts <
                config.maxReconnectAttempts
        ) {

            reconnectAttempts++

            _streamState.value =
                StreamState.Reconnecting(
                    attempt =
                        reconnectAttempts,
                    maxAttempts =
                        config.maxReconnectAttempts,
                    reason =
                        reason
                )

            val exponent =
                (reconnectAttempts - 1)
                    .coerceIn(0, 5)

            val backoffMultiplier =
                1L shl exponent

            val delayDuration =
                (
                    config.reconnectDelayMs *
                        backoffMultiplier
                    ).coerceAtMost(15_000L)

            reconnectJob?.cancel()

            reconnectJob =
                scope.launch {

                    delay(
                        delayDuration
                    )

                    if (
                        activeConfig === config
                    ) {
                        connectInternal(
                            config
                        )
                    }
                }

        } else {

            _streamState.value =
                StreamState.Error(
                    message = reason,
                    cause = cause,
                    recoverable = true
                )

            _decoderState.value =
                DecoderState.Error(
                    reason,
                    cause
                )
        }
    }

    private fun buildPlaybackErrorMessage(
        error: PlaybackException
    ): String {

        val causes =
            mutableListOf<String>()

        var current: Throwable? =
            error

        var isAuthError = false
        var isHostError = false

        while (current != null) {
            val msg = current.message.orEmpty()
            val clsName = current::class.java.simpleName
            if (msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true) || clsName.contains("RtspPlaybackException")) {
                if (msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true)) {
                    isAuthError = true
                }
            }
            if (clsName.contains("UnknownHostException") || msg.contains("UnknownHost", ignoreCase = true)) {
                isHostError = true
            }

            causes.add(
                "${current::class.java.simpleName}: ${current.message}"
            )

            current =
                current.cause
        }

        return buildString {
            if (isAuthError) {
                append("RTSP 401 Unauthorized during SETUP/DESCRIBE. Source requires credentials (format: rtsp://username:password@host:port/path) | ")
            } else if (isHostError) {
                append("RTSP Host resolution failed (UnknownHostException). Check IP/domain and network connection | ")
            }

            append(
                error.message
                    ?: "RTSP playback error"
            )

            if (causes.isNotEmpty()) {
                append(" | Cause chain: ")
                append(
                    causes.joinToString(" → ")
                )
            }
        }
    }

    fun stopStream() {
        reconnectJob?.cancel()
        reconnectJob = null

        statsMonitorJob?.cancel()
        statsMonitorJob = null

        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        } catch (_: Exception) {
            // Player may already have been released.
        }

        diagnosticsManager?.removeBoundaryStage(
            com.example.zesto.diagnostics.BoundaryDiagnosticStage.RTSP_CONNECTED
        )

        _streamState.value =
            StreamState.Disconnected

        _decoderState.value =
            DecoderState.Stopped
    }

    private fun startMetricsMonitor() {

        statsMonitorJob =
            scope.launch {

                while (true) {

                    delay(500L)

                    updateMetrics()
                }
            }
    }

    private fun updateMetrics() {

        val now =
            System.currentTimeMillis()

        val elapsed =
            now - lastFpsTimestamp

        if (elapsed < 1_000L) {
            return
        }

        val fps =
            if (elapsed > 0) {
                (
                    framesSinceLastFps *
                        1_000.0
                    ) / elapsed
            } else {
                0.0
            }

        framesSinceLastFps = 0L
        lastFpsTimestamp = now

        val rendered =
            renderedFramesCount.get()

        val dropped =
            droppedFramesCount.get()

        val errors =
            decodeErrorCount.get()

        val playerInstance =
            exoPlayer

        val isActuallyPlaying =
            playerInstance?.isPlaying == true

        _decoderStats.update { current ->

            current.copy(
                width =
                    currentVideoWidth,

                height =
                    currentVideoHeight,

                fps =
                    if (
                        isActuallyPlaying &&
                        rendered > 0
                    ) {
                        fps
                    } else {
                        0.0
                    },

                decodedFrameCount =
                    rendered,

                droppedFrameCount =
                    dropped,

                decodeErrors =
                    errors,

                averageDecodeLatencyMs =
                    0L,

                lastFrameTimestampUs =
                    if (rendered > 0) {
                        System.nanoTime() / 1_000L
                    } else {
                        0L
                    }
            )
        }

        _streamStats.update { current ->

            current.copy(
                framesReceived =
                    rendered,

                reconnectCount =
                    reconnectAttempts,

                networkLatencyMs =
                    0L,

                lastPacketTimestamp =
                    if (isActuallyPlaying) {
                        now
                    } else {
                        0L
                    }
            )
        }
    }

    /**
     * Fully releases the ExoPlayer and cancels all internal jobs.
     *
     * This method is required by ZestoStreamingService and
     * ZestoViewModel during lifecycle cleanup.
     */
    fun release() {

        reconnectJob?.cancel()
        reconnectJob = null

        statsMonitorJob?.cancel()
        statsMonitorJob = null

        try {
            exoPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            exoPlayer?.clearMediaItems()
        } catch (_: Exception) {
        }

        try {
            exoPlayer?.release()
        } catch (_: Exception) {
        }

        exoPlayer = null
        activeConfig = null
        frameListener = null

        try {
            offscreenFrameExtractor?.release()
        } catch (_: Exception) {}
        offscreenFrameExtractor = null
        internalSurface = null

        renderedFramesCount.set(0L)
        droppedFramesCount.set(0L)
        decodeErrorCount.set(0L)

        framesSinceLastFps = 0L
        currentVideoWidth = 0
        currentVideoHeight = 0
        decoderName = "Unknown"

        _streamState.value =
            StreamState.Disconnected

        _decoderState.value =
            DecoderState.Uninitialized

        _decoderStats.value =
            DecoderStats()

        _streamStats.value =
            StreamStats()
    }

    companion object {
        private const val TAG = "RTSPPlayerEngine"
    }
}
