package com.example.zesto.frame

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Statistics for the frame delivery pipeline.
 */
data class FramePipelineStats(
    val deliveredFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val activeConsumers: Int = 0,
    val currentFps: Double = 0.0,
    val averagePipelineLatencyMs: Long = 0L,
    val isRunning: Boolean = false
)

/**
 * High-performance FramePipeline and FrameProvider implementation.
 * Manages frame delivery to UI, virtual camera backends, and diagnostics without blocking the decoder thread.
 */
class FramePipeline(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : FrameProvider {

    private val consumers = ConcurrentHashMap<String, FrameConsumer>()
    private val isRunning = AtomicBoolean(false)

    private val deliveredCount = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var framesSinceLastFpsCheck = 0L

    private val _stats = MutableStateFlow(FramePipelineStats())
    val stats: StateFlow<FramePipelineStats> = _stats.asStateFlow()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            _stats.update { it.copy(isRunning = true) }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            _stats.update { it.copy(isRunning = false) }
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
        updateConsumerCount()
    }

    override fun getActiveConsumerCount(): Int = consumers.size

    override fun getRegisteredConsumerIds(): List<String> = consumers.keys.toList()

    override fun isRunning(): Boolean = isRunning.get()

    /**
     * Called by the VideoDecoder when a new frame is decoded.
     */
    fun pushFrame(frame: VideoFrame) {
        if (!isRunning.get() || consumers.isEmpty()) {
            droppedCount.incrementAndGet()
            updateStats(frameDropped = true)
            return
        }

        val startDeliveryTime = System.currentTimeMillis()

        // Deliver frame to all registered consumers asynchronously or directly
        for (consumer in consumers.values) {
            try {
                consumer.onFrameAvailable(frame)
            } catch (e: Exception) {
                // Prevent rogue consumer from crashing pipeline
                droppedCount.incrementAndGet()
            }
        }

        deliveredCount.incrementAndGet()
        framesSinceLastFpsCheck++

        val deliveryLatency = System.currentTimeMillis() - startDeliveryTime
        updateStats(frameDropped = false, latencyMs = deliveryLatency)
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
                    deliveredFrames = deliveredCount.get(),
                    droppedFrames = droppedCount.get(),
                    activeConsumers = consumers.size,
                    currentFps = fps,
                    averagePipelineLatencyMs = latencyMs,
                    isRunning = isRunning.get()
                )
            }
        } else {
            _stats.update { current ->
                current.copy(
                    deliveredFrames = deliveredCount.get(),
                    droppedFrames = droppedCount.get(),
                    activeConsumers = consumers.size,
                    isRunning = isRunning.get()
                )
            }
        }
    }
}
