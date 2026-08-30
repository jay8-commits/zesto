package com.example.zesto.frame

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Health state classification for real-time video stream frames.
 */
enum class FrameHealthState {
    NO_FRAME,
    FRAME_ACTIVE,
    FRAME_STALLED
}

/**
 * Deterministic pipeline lifecycle states.
 */
enum class PipelineLifecycleState {
    IDLE,
    CONNECTING,
    CONNECTED,
    STREAMING,
    SURFACE_LOST,
    RECONNECTING,
    ERROR
}

/**
 * Record of external milestone reported by hooked target processes.
 */
data class ExternalMilestoneEvent(
    val stage: String,
    val packageName: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Shared in-memory frame bridge for Zesto.
 *
 * RTSP frames posted here are the authoritative decoded-frame source.
 *
 * Pipeline:
 *
 * RTSP
 *   -> ExoPlayer / MediaCodec
 *   -> OffscreenFrameExtractor
 *   -> RTSPPlayerEngine
 *   -> ZestoFrameBridge
 *   -> Camera/injection provider
 */
object ZestoFrameBridge {

    private const val TAG = "ZestoFrameBridge"

    data class FrameData(
        val frameId: Long = 0L,
        val sequence: Long = 0L,
        val timestampUs: Long = 0L,
        val timestampEpochMs: Long = 0L,
        val width: Int = 1280,
        val height: Int = 720,
        val format: PixelFormat = PixelFormat.RGBA_8888,
        val buffer: ByteArray? = null,
        val bitmap: Bitmap? = null,
        val jpegBuffer: ByteArray? = null,
        val sourceMode: FrameSourceMode = FrameSourceMode.RTSP
    )

    private val _latestFrame =
        MutableStateFlow(FrameData())

    val latestFrame: StateFlow<FrameData> =
        _latestFrame.asStateFlow()

    private val _sourceMode =
        MutableStateFlow(FrameSourceMode.RTSP)

    val sourceMode: StateFlow<FrameSourceMode> =
        _sourceMode.asStateFlow()

    private val _pipelineState =
        MutableStateFlow(PipelineLifecycleState.IDLE)

    val pipelineState: StateFlow<PipelineLifecycleState> =
        _pipelineState.asStateFlow()

    private val _externalMilestones =
        MutableStateFlow<List<ExternalMilestoneEvent>>(emptyList())

    val externalMilestones: StateFlow<List<ExternalMilestoneEvent>> =
        _externalMilestones.asStateFlow()

    private val frameCounter =
        AtomicLong(0L)

    private val deliveredCounter =
        AtomicLong(0L)

    private val droppedCounter =
        AtomicLong(0L)

    private val lastFrameTimeMs =
        AtomicLong(0L)

    private val reconnectCounter =
        AtomicInteger(0)

    private val providerRunning =
        AtomicBoolean(false)

    private val bridgeReady =
        AtomicBoolean(false)

    /**
     * Used only for telemetry.
     *
     * We keep the most recent ~2 seconds of frame arrival timestamps
     * so the bridge FPS can be calculated without creating another
     * frame queue.
     */
    private val recentFrameTimestamps =
        ConcurrentLinkedQueue<Long>()

    private val localTestPatternActive =
        AtomicBoolean(false)

    val totalFramesReceived: Long
        get() = frameCounter.get()

    val totalFramesDelivered: Long
        get() = deliveredCounter.get()

    val totalFramesDropped: Long
        get() = droppedCounter.get()

    val lastFrameArrivalEpochMs: Long
        get() = lastFrameTimeMs.get()

    val reconnectCount: Int
        get() = reconnectCounter.get()

    val isProviderRunning: Boolean
        get() = providerRunning.get()

    val isBridgeReady: Boolean
        get() = bridgeReady.get()

    val isTestPatternMode: Boolean
        get() =
            _sourceMode.value == FrameSourceMode.TEST_PATTERN ||
                localTestPatternActive.get()

    /**
     * Changes the authoritative frame source.
     *
     * RTSP mode is the normal production mode.
     * TEST_PATTERN should only be explicitly enabled for diagnostics.
     */
    fun setSourceMode(mode: FrameSourceMode) {

        _sourceMode.value = mode

        localTestPatternActive.set(
            mode == FrameSourceMode.TEST_PATTERN
        )

        Log.i(
            TAG,
            "[SOURCE_MODE_CHANGED] Frame source mode explicitly set to: $mode"
        )
    }

    /**
     * Explicitly enables/disables test-pattern mode.
     *
     * IMPORTANT:
     * This method is the only intended switch for test-pattern mode.
     * Normal RTSP playback must use RTSP mode.
     */
    fun setTestPatternMode(enabled: Boolean) {

        val mode =
            if (enabled) {
                FrameSourceMode.TEST_PATTERN
            } else {
                FrameSourceMode.RTSP
            }

        setSourceMode(mode)

        Log.i(
            TAG,
            "[TEST_PATTERN_MODE] enabled=$enabled"
        )
    }

    /**
     * Calculates approximate bridge FPS over the last two seconds.
     */
    fun calculateBridgeFps(): Double {

        val now =
            System.currentTimeMillis()

        while (
            recentFrameTimestamps.isNotEmpty() &&
            (
                now -
                    (recentFrameTimestamps.peek() ?: 0L)
                ) > 2_000L
        ) {
            recentFrameTimestamps.poll()
        }

        val count =
            recentFrameTimestamps.size

        if (count < 2) {
            return 0.0
        }

        val oldest =
            recentFrameTimestamps.peek()
                ?: return 0.0

        val span =
            (
                now -
                    oldest
                ).coerceAtLeast(100L)

        return (
            count.toDouble() *
                1_000.0
            ) /
            span.toDouble()
    }

    /**
     * Marks the bridge as ready/not ready.
     */
    fun setBridgeReady(ready: Boolean) {

        val changed =
            if (ready) {
                bridgeReady.compareAndSet(
                    false,
                    true
                )
            } else {
                bridgeReady.compareAndSet(
                    true,
                    false
                )
            }

        if (changed) {

            if (ready) {

                Log.i(
                    TAG,
                    "[FRAME_BRIDGE] bridge ready"
                )

                Log.i(
                    TAG,
                    "[FRAME_BRIDGE] bridge connected"
                )

            } else {

                Log.i(
                    TAG,
                    "[FRAME_BRIDGE] bridge disconnected"
                )
            }
        }
    }

    /**
     * Marks the camera/frame provider as running.
     */
    fun setProviderRunning(running: Boolean) {

        val previous =
            providerRunning.getAndSet(running)

        if (previous != running) {

            Log.i(
                TAG,
                "[FRAME_PROVIDER] provider running=$running"
            )

            setBridgeReady(running)
        }
    }

    /**
     * Updates the global pipeline lifecycle state.
     */
    fun updatePipelineState(
        state: PipelineLifecycleState
    ) {

        _pipelineState.value = state

        Log.i(
            TAG,
            "[PIPELINE_STATE] Pipeline transitioned to: $state"
        )
    }

    /**
     * Increments reconnect telemetry.
     */
    fun incrementReconnectCount() {

        reconnectCounter.incrementAndGet()
    }

    /**
     * Posts a newly decoded frame into the shared bridge.
     *
     * This is the important RTSP handoff point.
     *
     * RTSPPlayerEngine should call:
     *
     * ZestoFrameBridge.postFrame(
     *     width = width,
     *     height = height,
     *     format = PixelFormat.RGBA_8888,
     *     bitmap = bitmap,
     *     timestampUs = timestampUs,
     *     sourceMode = FrameSourceMode.RTSP,
     *     externalFrameId = frameId
     * )
     *
     * The latest decoded RTSP frame then becomes available through
     * latestFrame and consumeLatestFrame().
     */
    fun postFrame(
        width: Int,
        height: Int,
        format: PixelFormat = PixelFormat.RGBA_8888,
        buffer: ByteArray? = null,
        bitmap: Bitmap? = null,
        timestampUs: Long = System.nanoTime() / 1_000,
        sourceMode: FrameSourceMode = _sourceMode.value,
        externalFrameId: Long? = null
    ) {

        /*
         * Reject invalid frame dimensions.
         */
        if (
            width <= 0 ||
            height <= 0
        ) {

            Log.w(
                TAG,
                "[FRAME_REJECTED] Invalid dimensions ${width}x${height}"
            )

            recordDroppedFrame()

            return
        }

        /*
         * A frame should normally contain either a Bitmap or raw buffer.
         *
         * We do not reject empty frames completely because some existing
         * diagnostic paths may use buffer-only delivery.
         */
        if (
            bitmap == null &&
            buffer == null
        ) {

            Log.w(
                TAG,
                "[FRAME_REJECTED] No bitmap or buffer supplied for ${width}x${height}"
            )

            recordDroppedFrame()

            return
        }

        /*
         * Reject recycled Bitmaps.
         */
        if (
            bitmap != null &&
            bitmap.isRecycled
        ) {

            Log.w(
                TAG,
                "[FRAME_REJECTED] Bitmap is already recycled"
            )

            recordDroppedFrame()

            return
        }

        val nowMs =
            System.currentTimeMillis()

        lastFrameTimeMs.set(nowMs)

        recentFrameTimestamps.offer(
            nowMs
        )

        /*
         * Keep telemetry queue bounded.
         */
        while (
            recentFrameTimestamps.size > 240
        ) {
            recentFrameTimestamps.poll()
        }

        /*
         * Use the decoder's frame ID when supplied.
         *
         * Otherwise generate a bridge-local ID.
         */
        val id =
            externalFrameId
                ?: frameCounter.incrementAndGet()

        /*
         * Keep the counter synchronized with externally supplied IDs.
         */
        if (
            externalFrameId != null
        ) {

            while (true) {

                val current =
                    frameCounter.get()

                if (id <= current) {
                    break
                }

                if (
                    frameCounter.compareAndSet(
                        current,
                        id
                    )
                ) {
                    break
                }
            }
        }

        if (id == 1L || id % 30L == 0L) {
            Log.i(TAG, "[FRAME_BRIDGE_RECEIVED] decoderFrameNumber=${externalFrameId ?: id} frameId=$id width=$width height=$height sourceMode=$sourceMode")
        }

        /*
         * IMPORTANT:
         *
         * The actual decoded frame is stored here.
         *
         * This replaces the previous frame atomically from the
         * StateFlow consumer's point of view.
         */
        var precompressedJpeg: ByteArray? = null
        if (bitmap != null && !bitmap.isRecycled) {
            try {
                val baos = java.io.ByteArrayOutputStream(65536)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                precompressedJpeg = baos.toByteArray()
            } catch (t: Throwable) {
                Log.w(TAG, "Frame precompression warning: ${t.message}")
            }
        }

        val frame =
            FrameData(
                frameId = id,
                sequence = id,
                timestampUs = timestampUs,
                timestampEpochMs = nowMs,
                width = width,
                height = height,
                format = format,
                buffer = buffer,
                bitmap = bitmap,
                jpegBuffer = precompressedJpeg,
                sourceMode = sourceMode
            )

        _latestFrame.value =
            frame

        // Broadcast to high-performance Android Binder/SharedMemory, Unix Domain Socket Server, and Shared Memory Bridge
        try {
            var precompressedJpeg: ByteArray? = buffer
            if (precompressedJpeg == null && bitmap != null && !bitmap.isRecycled) {
                try {
                    val baos = ByteArrayOutputStream(width * height / 4)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    precompressedJpeg = baos.toByteArray()
                } catch (_: Throwable) {}
            }

            com.example.zesto.ipc.ZestoFrameBinder.writeFrame(
                frameId = id,
                timestampUs = timestampUs,
                width = width,
                height = height,
                bitmap = bitmap,
                rawBytes = precompressedJpeg,
                isStreaming = sourceMode == FrameSourceMode.RTSP || bitmap != null,
                healthState = getFrameHealthState().name
            )
            com.example.zesto.ipc.ZestoIpcSocketServer.updateFrame(
                frameId = id,
                timestampUs = timestampUs,
                width = width,
                height = height,
                bitmap = bitmap,
                rawBuffer = precompressedJpeg,
                sourceMode = sourceMode,
                healthState = getFrameHealthState(),
                isStreaming = sourceMode == FrameSourceMode.RTSP || bitmap != null
            )
            com.example.zesto.ipc.ZestoSharedMemoryBridge.writeFrame(
                frameId = id,
                timestampUs = timestampUs,
                width = width,
                height = height,
                bitmap = bitmap,
                rawBytes = precompressedJpeg,
                isStreaming = sourceMode == FrameSourceMode.RTSP || bitmap != null,
                healthState = getFrameHealthState().name
            )
        } catch (_: Throwable) {}

        /*
         * A real frame means the bridge is alive.
         */
        if (!bridgeReady.get()) {
            setBridgeReady(true)
        }

        /*
         * A posted RTSP frame means the pipeline is actively streaming.
         */
        if (
            sourceMode == FrameSourceMode.RTSP
        ) {

            if (
                _pipelineState.value !=
                    PipelineLifecycleState.STREAMING
            ) {

                _pipelineState.value =
                    PipelineLifecycleState.STREAMING
            }
        }

        /*
         * If this is a real RTSP frame, make that explicit in logs.
         */
        if (
            sourceMode == FrameSourceMode.RTSP
        ) {

            if (
                id == 1L ||
                id % 60L == 0L
            ) {

                Log.i(
                    TAG,
                    "[RTSP_FRAME_BRIDGE] id=$id " +
                        "resolution=${width}x${height} " +
                        "timestampUs=$timestampUs " +
                        "hasBitmap=${bitmap != null} " +
                        "hasBuffer=${buffer != null}"
                )

                Log.i(
                    TAG,
                    "[RTSP_FRAME_AUTHORITATIVE] " +
                        "RTSP decoded frame is now the authoritative bridge frame"
                )
            }

        } else {

            if (
                id == 1L ||
                id % 60L == 0L
            ) {

                Log.i(
                    TAG,
                    "[FRAME_BRIDGE_POSTED] id=$id " +
                        "source=$sourceMode " +
                        "${width}x${height} " +
                        "hasBitmap=${bitmap != null}"
                )
            }
        }

        /*
         * General telemetry.
         */
        if (
            id == 1L ||
            id % 60L == 0L
        ) {

            Log.i(
                TAG,
                "[FRAME_GENERATED] id=$id"
            )

            Log.i(
                TAG,
                "[FRAME_INJECTED] Frame #$id " +
                    "(${width}x${height}, format=$format) " +
                    "available in bridge"
            )

            Log.i(
                TAG,
                "[FRAME_PIPELINE] " +
                    "frames received=${frameCounter.get()}, " +
                    "frames delivered=${deliveredCounter.get()}, " +
                    "frames dropped=${droppedCounter.get()}, " +
                    "queue depth=1"
            )
        }
    }

    /**
     * Convenience method specifically for decoded RTSP Bitmap frames.
     *
     * Use this from RTSPPlayerEngine after OffscreenFrameExtractor
     * produces a valid Bitmap.
     */
    fun postRtspFrame(
        bitmap: Bitmap,
        width: Int = bitmap.width,
        height: Int = bitmap.height,
        timestampUs: Long = System.nanoTime() / 1_000,
        frameId: Long? = null
    ) {

        if (bitmap.isRecycled) {

            Log.w(
                TAG,
                "[RTSP_FRAME_REJECTED] Bitmap is recycled"
            )

            recordDroppedFrame()

            return
        }

        postFrame(
            width = width,
            height = height,
            format = PixelFormat.RGBA_8888,
            buffer = null,
            bitmap = bitmap,
            timestampUs = timestampUs,
            sourceMode = FrameSourceMode.RTSP,
            externalFrameId = frameId
        )
    }

    /**
     * Returns the most recently decoded frame.
     *
     * This does not remove the frame because the bridge is intentionally
     * latest-frame based rather than queue based.
     */
    fun consumeLatestFrame(): FrameData {

        val frame =
            _latestFrame.value

        if (
            frame.frameId > 0L
        ) {

            deliveredCounter.incrementAndGet()
        }

        return frame
    }

    /**
     * Returns the latest frame without incrementing delivery telemetry.
     *
     * Useful when multiple consumers inspect the same frame.
     */
    fun peekLatestFrame(): FrameData {
        return _latestFrame.value
    }

    /**
     * Records an external milestone reported by a target-process hook.
     */
    fun reportExternalMilestone(
        stage: String,
        packageName: String,
        message: String
    ) {

        Log.i(
            TAG,
            "[EXTERNAL_MILESTONE] " +
                "[$stage] from $packageName: $message"
        )

        _externalMilestones.update { current ->

            /*
             * Keep only the most recent 100 events.
             */
            val updated =
                current +
                    ExternalMilestoneEvent(
                        stage = stage,
                        packageName = packageName,
                        message = message
                    )

            if (updated.size > 100) {
                updated.takeLast(100)
            } else {
                updated
            }
        }
    }

    /**
     * Records a dropped frame.
     */
    fun recordDroppedFrame() {

        droppedCounter.incrementAndGet()
    }

    /**
     * Returns milliseconds since the last received frame.
     */
    fun getMillisecondsSinceLastFrame(): Long {

        val last =
            lastFrameTimeMs.get()

        return if (
            last == 0L
        ) {
            -1L
        } else {
            (
                System.currentTimeMillis() -
                    last
                ).coerceAtLeast(0L)
        }
    }

    /**
     * Returns current frame health.
     */
    fun getFrameHealthState(
        stalledTimeoutMs: Long = 5_000L
    ): FrameHealthState {

        val last =
            lastFrameTimeMs.get()

        if (
            last == 0L ||
            frameCounter.get() == 0L
        ) {

            return FrameHealthState.NO_FRAME
        }

        val elapsed =
            System.currentTimeMillis() -
                last

        return if (
            elapsed > stalledTimeoutMs
        ) {
            FrameHealthState.FRAME_STALLED
        } else {
            FrameHealthState.FRAME_ACTIVE
        }
    }

    /**
     * Returns true when a valid RTSP frame is currently available.
     */
    fun hasRtspFrame(): Boolean {

        val frame =
            _latestFrame.value

        return (
            frame.frameId > 0L &&
                frame.sourceMode == FrameSourceMode.RTSP &&
                (
                    frame.bitmap != null ||
                        frame.buffer != null
                    )
            )
    }

    /**
     * Returns the latest frame only if it is an RTSP frame.
     *
     * This is useful for the injection/provider side because it prevents
     * an old test-pattern frame from being mistaken for an RTSP frame.
     */
    fun consumeLatestRtspFrame(): FrameData? {

        val frame =
            _latestFrame.value

        if (
            frame.frameId <= 0L
        ) {
            return null
        }

        if (
            frame.sourceMode !=
                FrameSourceMode.RTSP
        ) {

            return null
        }

        if (
            frame.bitmap == null &&
            frame.buffer == null
        ) {

            return null
        }

        deliveredCounter.incrementAndGet()

        return frame
    }

    /**
     * Resets all bridge state.
     *
     * This intentionally returns the source mode to RTSP rather than
     * leaving the bridge stuck in test-pattern mode after a previous
     * diagnostic session.
     */
    fun reset() {

        frameCounter.set(0L)
        deliveredCounter.set(0L)
        droppedCounter.set(0L)
        lastFrameTimeMs.set(0L)
        reconnectCounter.set(0)

        providerRunning.set(false)
        bridgeReady.set(false)

        recentFrameTimestamps.clear()

        localTestPatternActive.set(false)

        _sourceMode.value =
            FrameSourceMode.RTSP

        _latestFrame.value =
            FrameData(
                frameId = 0L,
                timestampUs = 0L,
                timestampEpochMs = 0L,
                width = 1280,
                height = 720,
                format = PixelFormat.RGBA_8888,
                buffer = null,
                bitmap = null,
                sourceMode = FrameSourceMode.RTSP
            )

        _externalMilestones.value =
            emptyList()

        _pipelineState.value =
            PipelineLifecycleState.IDLE

        Log.i(
            TAG,
            "[FRAME_BRIDGE_RESET] Bridge reset; source mode restored to RTSP"
        )
    }
}
