package com.example.zesto.frame

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Statistics for the frame delivery pipeline.
 */
data class FramePipelineStats(
    val receivedFrames: Long = 0L,
    val deliveredFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val queueDepth: Int = 0,
    val activeConsumers: Int = 0,
    val currentFps: Double = 0.0,
    val averagePipelineLatencyMs: Long = 0L,
    val isRunning: Boolean = false
)

/**
 * High-performance FramePipeline and FrameProvider implementation.
 * Manages bounded real-time frame delivery with drop-oldest strategy to prevent pipeline backpressure.
 */
class FramePipeline(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val maxQueueCapacity: Int = 2
) : FrameProvider {

    companion object {
        private const val TAG = "FramePipeline"
    }

    private val consumers = ConcurrentHashMap<String, FrameConsumer>()
    private val isRunning = AtomicBoolean(false)

    private val receivedCount = AtomicLong(0L)
    private val deliveredCount = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var framesSinceLastFpsCheck = 0L

    // Bounded drop-oldest queue for real-time camera frames
    private val frameQueue = ConcurrentLinkedDeque<VideoFrame>()

    private val _stats = MutableStateFlow(FramePipelineStats())
    val stats: StateFlow<FramePipelineStats> = _stats.asStateFlow()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            _stats.update { it.copy(isRunning = true) }
            Log.i(TAG, "[FRAME_PIPELINE] Pipeline started (bounded capacity=$maxQueueCapacity)")
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            frameQueue.clear()
            _stats.update { it.copy(isRunning = false, queueDepth = 0) }
            Log.i(TAG, "[FRAME_PIPELINE] Pipeline stopped")
        }
    }

    override fun registerConsumer(consumer: FrameConsumer): Boolean {
        consumers[consumer.consumerId] = consumer
        consumer.onConsumerAttached(this)
        updateConsumerCount()
        return true
    }

    override fun unregisterConsumer(consumerId: String): Boolean {
        val removed = consumers.remove(consumerId)
        removed?.onConsumerDetached()
        updateConsumerCount()
        return removed != null
    }

    override fun unregisterAll() {
        consumers.values.forEach { it.onConsumerDetached() }
        consumers.clear()
        frameQueue.clear()
        updateConsumerCount()
    }

    override fun getActiveConsumerCount(): Int = consumers.size

    override fun getRegisteredConsumerIds(): List<String> = consumers.keys.toList()

    override fun isRunning(): Boolean = isRunning.get()

    /**
     * Called by the VideoDecoder when a new frame is decoded.
     * Implements bounded/drop-oldest queue strategy so stale frames do not accumulate.
     */
    fun pushFrame(frame: VideoFrame) {
        val received = receivedCount.incrementAndGet()

        if (!isRunning.get() || consumers.isEmpty()) {
            val dropped = droppedCount.incrementAndGet()
            updateStats(frameDropped = true)
            if (received == 1L || received % 60L == 0L) {
                Log.i(TAG, "[FRAME_PIPELINE] frames received=$received, frames delivered=${deliveredCount.get()}, frames dropped=$dropped, queue depth=${frameQueue.size}")
            }
            return
        }

        // Bounded drop-oldest queue management
        while (frameQueue.size >= maxQueueCapacity) {
            frameQueue.pollFirst()
            val dropped = droppedCount.incrementAndGet()
            Log.d(TAG, "[FRAME_PIPELINE] Dropped oldest frame to prevent backpressure (queueDepth=${frameQueue.size}, totalDropped=$dropped)")
        }

        frameQueue.offerLast(frame)
        val activeFrame = frameQueue.pollFirst() ?: frame
        val startDeliveryTime = System.currentTimeMillis()

        // Deliver frame to all registered consumers
        for (consumer in consumers.values) {
            try {
                consumer.onFrameAvailable(activeFrame)
            } catch (e: Exception) {
                droppedCount.incrementAndGet()
            }
        }

        val delivered = deliveredCount.incrementAndGet()
        framesSinceLastFpsCheck++

        val deliveryLatency = System.currentTimeMillis() - startDeliveryTime
        val currentDepth = frameQueue.size
        updateStats(frameDropped = false, latencyMs = deliveryLatency)

        if (received == 1L || received % 60L == 0L) {
            Log.i(TAG, "[FRAME_PIPELINE] frames received=$received, frames delivered=$delivered, frames dropped=${droppedCount.get()}, queue depth=$currentDepth")
        }
    }

    private fun updateConsumerCount() {
        _stats.update { it.copy(activeConsumers = consumers.size) }
    }

    private fun updateStats(frameDropped: Boolean, latencyMs: Long = 0L) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsCalculationTime

        if (elapsed >= 1000L) {
            val fps = (framesSinceLastFpsCheck * 1000.0) / elapsed
            framesSinceLastFpsCheck = 0L
            lastFpsCalculationTime = now

            _stats.update { current ->
                current.copy(
                    receivedFrames = receivedCount.get(),
                    deliveredFrames = deliveredCount.get(),
                    droppedFrames = droppedCount.get(),
                    queueDepth = frameQueue.size,
                    activeConsumers = consumers.size,
                    currentFps = fps,
                    averagePipelineLatencyMs = latencyMs,
                    isRunning = isRunning.get()
                )
            }
        } else {
            _stats.update { current ->
                current.copy(
                    receivedFrames = receivedCount.get(),
                    deliveredFrames = deliveredCount.get(),
                    droppedFrames = droppedCount.get(),
                    queueDepth = frameQueue.size,
                    activeConsumers = consumers.size,
                    isRunning = isRunning.get()
                )
            }
        }
    }
}
