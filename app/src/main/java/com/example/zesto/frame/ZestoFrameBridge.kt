package com.example.zesto.frame

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared in-memory frame bridge for zero-latency frame sharing across Zesto subsystems,
 * background service, IPC content provider, and camera virtualization hooks.
 */
object ZestoFrameBridge {

    data class FrameData(
        val frameId: Long = 0L,
        val timestampUs: Long = 0L,
        val width: Int = 1280,
        val height: Int = 720,
        val format: PixelFormat = PixelFormat.RGBA_8888,
        val buffer: ByteArray? = null,
        val bitmap: Bitmap? = null
    )

    private val _latestFrame = MutableStateFlow(FrameData())
    val latestFrame: StateFlow<FrameData> = _latestFrame.asStateFlow()

    private val frameCounter = AtomicLong(0L)
    private val deliveredCounter = AtomicLong(0L)
    private val droppedCounter = AtomicLong(0L)

    val totalFramesReceived: Long get() = frameCounter.get()
    val totalFramesDelivered: Long get() = deliveredCounter.get()
    val totalFramesDropped: Long get() = droppedCounter.get()

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
        val id = frameCounter.incrementAndGet()
        _latestFrame.value = FrameData(
            frameId = id,
            timestampUs = timestampUs,
            width = width,
            height = height,
            format = format,
            buffer = buffer,
            bitmap = bitmap
        )
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

    fun recordDroppedFrame() {
        droppedCounter.incrementAndGet()
    }

    fun reset() {
        frameCounter.set(0L)
        deliveredCounter.set(0L)
        droppedCounter.set(0L)
        _latestFrame.value = FrameData()
    }
}
