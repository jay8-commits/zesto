package com.example.zesto.stream

import android.content.Context
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
 * - Real rendered-frame and dropped-frame telemetry
 * - Real Media3 playback error reporting
 * - Automatic reconnect with exponential backoff
 *
 * Note:
 * Media3/ExoPlayer does not expose a simple "RTP packets received"
 * counter through Player.Listener. Therefore this class does NOT
 * fabricate packet counts. Transport packet telemetry should come
 * from the RTSP transport layer if packet-level statistics are needed.
 */
@OptIn(UnstableApi::class)
class RTSPPlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.Main.immediate)
) {

    private var exoPlayer: ExoPlayer? = null

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

            playerInstance.addListener(
                object : Player.Listener {

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
                            }

                            Player.STATE_BUFFERING -> {
                                if (
                                    _streamState.value
                                        !is StreamState.Reconnecting
                                ) {
                                    _streamState.value =
                                        StreamState.Connecting
                                }

                                _decoderState.value =
                                    DecoderState.Configured(
                                        "video/avc",
                                        currentVideoWidth,
                                        currentVideoHeight
                                    )
                            }

                            Player.STATE_READY -> {
                                val url =
                                    activeConfig?.url.orEmpty()

                                _streamState.value =
                                    StreamState.Connected(url)

                                reconnectAttempts = 0
                            }

                            Player.STATE_ENDED -> {
                                _streamState.value =
                                    StreamState.Disconnected

                                _decoderState.value =
                                    DecoderState.Stopped
                            }
                        }
                    }

                    override fun onIsPlayingChanged(
                        isPlaying: Boolean
                    ) {
                        if (isPlaying) {
                            _decoderState.value =
                                DecoderState.Running
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

                            _decoderStats.update {
                                it.copy(
                                    width =
                                        videoSize.width,
                                    height =
                                        videoSize.height
                                )
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        _decoderState.value =
                            DecoderState.Running
                    }

                    override fun onPlayerError(
                        error: PlaybackException
                    ) {
                        decodeErrorCount.incrementAndGet()

                        val message =
                            buildPlaybackErrorMessage(error)

                        _decoderStats.update {
                            it.copy(
                                decodeErrors =
                                    decodeErrorCount.get()
                            )
                        }

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
                    }

                    override fun onVideoFrameProcessingOffset(
                        eventTime: AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int
                    ) {
                        if (frameCount <= 0) {
                            return
                        }

                        renderedFramesCount.addAndGet(
                            frameCount.toLong()
                        )

                        framesSinceLastFps +=
                            frameCount
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

        while (current != null) {

            causes.add(
                "${current::class.java.simpleName}: ${current.message}"
            )

            current =
                current.cause
        }

        return buildString {

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

        _streamState.value =
            StreamState.Disconnected

        _decoderState.value =
            DecoderState.Stopped
    }

    private fun startMetricsMonitor() {
        statsMonitorJob?.cancel()

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
}
