package com.example.zesto.frame

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * Shared in-memory frame bridge for zero-latency frame sharing across Zesto subsystems,
 * background service, IPC content provider, and camera virtualization hooks.
 */
object ZestoFrameBridge {
    private const val TAG = "ZestoFrameBridge"

    data class FrameData(
        val frameId: Long = 0L,
        val timestampUs: Long = 0L,
        val timestampEpochMs: Long = 0L,
        val width: Int = 1080,
        val height: Int = 1920,
        val format: PixelFormat = PixelFormat.RGBA_8888,
        val buffer: ByteArray? = null,
        val bitmap: Bitmap? = null
    )

    private val _latestFrame = MutableStateFlow(FrameData())
    val latestFrame: StateFlow<FrameData> = _latestFrame.asStateFlow()

    private val _pipelineState = MutableStateFlow(PipelineLifecycleState.IDLE)
    val pipelineState: StateFlow<PipelineLifecycleState> = _pipelineState.asStateFlow()

    private val _externalMilestones = MutableStateFlow<List<ExternalMilestoneEvent>>(emptyList())
    val externalMilestones: StateFlow<List<ExternalMilestoneEvent>> = _externalMilestones.asStateFlow()

    private val frameCounter = AtomicLong(0L)
    private val deliveredCounter = AtomicLong(0L)
    private val droppedCounter = AtomicLong(0L)
    private val lastFrameTimeMs = AtomicLong(0L)
    private val reconnectCounter = AtomicInteger(0)
    private val providerRunning = AtomicBoolean(false)
    private val bridgeReady = AtomicBoolean(false)

    // Telemetry FPS calculation
    private val recentFrameTimestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val localTestPatternActive = AtomicBoolean(false)

    val totalFramesReceived: Long get() = frameCounter.get()
    val totalFramesDelivered: Long get() = deliveredCounter.get()
    val totalFramesDropped: Long get() = droppedCounter.get()
    val lastFrameArrivalEpochMs: Long get() = lastFrameTimeMs.get()
    val reconnectCount: Int get() = reconnectCounter.get()
    val isProviderRunning: Boolean get() = providerRunning.get()
    val isBridgeReady: Boolean get() = bridgeReady.get()
    val isTestPatternMode: Boolean get() = localTestPatternActive.get()

    fun setTestPatternMode(enabled: Boolean) {
        localTestPatternActive.set(enabled)
        Log.i(TAG, "[TEST_PATTERN_MODE] Universal 30 FPS Local Test Pattern Generator set to: $enabled")
    }

    fun calculateBridgeFps(): Double {
        val now = System.currentTimeMillis()
        while (recentFrameTimestamps.isNotEmpty() && (now - (recentFrameTimestamps.peek() ?: 0L)) > 2000L) {
            recentFrameTimestamps.poll()
        }
        val count = recentFrameTimestamps.size
        return if (count >= 2) {
            val oldest = recentFrameTimestamps.peek() ?: now
            val span = (now - oldest).coerceAtLeast(100L)
            (count.toDouble() * 1000.0) / span.toDouble()
        } else {
            0.0
        }
    }

    fun setBridgeReady(ready: Boolean) {
        if (bridgeReady.compareAndSet(!ready, ready)) {
            if (ready) {
                Log.i(TAG, "[FRAME_BRIDGE] bridge ready")
                Log.i(TAG, "[FRAME_BRIDGE] bridge connected")
            } else {
                Log.i(TAG, "[FRAME_BRIDGE] bridge disconnected")
            }
        }
    }

    fun setProviderRunning(running: Boolean) {
        val prev = providerRunning.getAndSet(running)
        if (prev != running) {
            Log.i(TAG, "[FRAME_PROVIDER] provider running=$running")
            setBridgeReady(running)
        }
    }

    fun updatePipelineState(state: PipelineLifecycleState) {
        _pipelineState.value = state
        Log.i(TAG, "[PIPELINE_STATE] Pipeline transitioned to: $state")
    }

    fun incrementReconnectCount() {
        reconnectCounter.incrementAndGet()
    }

    /**
     * Updates the shared bridge with a newly decoded frame.
     */
    fun postFrame(
        width: Int,
        height: Int,
        format: PixelFormat = PixelFormat.RGBA_8888,
        buffer: ByteArray? = null,
        bitmap: Bitmap? = null,
        timestampUs: Long = System.nanoTime() / 1000
    ) {
        val nowMs = System.currentTimeMillis()
        lastFrameTimeMs.set(nowMs)
        recentFrameTimestamps.offer(nowMs)
        val id = frameCounter.incrementAndGet()
        _latestFrame.value = FrameData(
            frameId = id,
            timestampUs = timestampUs,
            timestampEpochMs = nowMs,
            width = width,
            height = height,
            format = format,
            buffer = buffer,
            bitmap = bitmap
        )
        if (_pipelineState.value != PipelineLifecycleState.STREAMING) {
            _pipelineState.value = PipelineLifecycleState.STREAMING
        }
        if (!bridgeReady.get()) {
            setBridgeReady(true)
        }
        if (id == 1L || id % 60L == 0L) {
            Log.i(TAG, "[FRAME_GENERATED] id=$id")
            Log.i(TAG, "[FRAME_BRIDGE_POSTED] id=$id")
            Log.i(TAG, "[FRAME_INJECTED] Frame #$id (${width}x${height}, format=$format) injected into bridge")
            Log.i(TAG, "[FRAME_PIPELINE] frames received=$id, frames delivered=${deliveredCounter.get()}, frames dropped=${droppedCounter.get()}, queue depth=1")
        }
    }

    /**
     * Consumes the latest frame.
     */
    fun consumeLatestFrame(): FrameData {
        val frame = _latestFrame.value
        if (frame.frameId > 0) {
            deliveredCounter.incrementAndGet()
        }
        return frame
    }

    /**
     * Records an external milestone reported from a target process hook (e.g. Open Camera).
     */
    fun reportExternalMilestone(stage: String, packageName: String, message: String) {
        Log.i(TAG, "[EXTERNAL_MILESTONE] [$stage] from $packageName: $message")
        _externalMilestones.update { current ->
            current + ExternalMilestoneEvent(stage, packageName, message)
        }
    }

    fun recordDroppedFrame() {
        droppedCounter.incrementAndGet()
    }

    fun getMillisecondsSinceLastFrame(): Long {
        val last = lastFrameTimeMs.get()
        return if (last == 0L) -1L else (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    fun getFrameHealthState(stalledTimeoutMs: Long = 5000L): FrameHealthState {
        val last = lastFrameTimeMs.get()
        if (last == 0L || frameCounter.get() == 0L) {
            return FrameHealthState.NO_FRAME
        }
        val elapsed = System.currentTimeMillis() - last
        return if (elapsed > stalledTimeoutMs) {
            FrameHealthState.FRAME_STALLED
        } else {
            FrameHealthState.FRAME_ACTIVE
        }
    }

    fun reset() {
        frameCounter.set(0L)
        deliveredCounter.set(0L)
        droppedCounter.set(0L)
        lastFrameTimeMs.set(0L)
        providerRunning.set(false)
        bridgeReady.set(false)
        _latestFrame.value = FrameData()
        _externalMilestones.value = emptyList()
        _pipelineState.value = PipelineLifecycleState.IDLE
    }
}
