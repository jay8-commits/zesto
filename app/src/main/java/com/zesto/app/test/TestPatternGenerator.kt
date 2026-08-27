package com.zesto.app.test

import android.graphics.Bitmap
import android.graphics.Canvas
import com.zesto.app.model.PixelFormat
import com.zesto.app.model.VideoFrame
import com.zesto.app.util.FrameRenderUtils
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic Test Pattern Generator producing Stage 2 test frames with:
 * - Frame ID
 * - Presentation Timestamp (PTS)
 * - 4 High-contrast corner markers (Red TL, Green TR, Blue BR, Yellow BL)
 */
class TestPatternGenerator(
    private val width: Int = 1280,
    private val height: Int = 720
) {
    private val frameCounter = AtomicLong(0L)
    private var baseTimestampNs = System.nanoTime()
    private val reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(reusableBitmap)
    private val byteBuffer = ByteBuffer.allocateDirect(width * height * 4)

    /**
     * Generates the next canonical VideoFrame test pattern.
     */
    fun nextFrame(rotationDegrees: Int = 0, isMirrored: Boolean = false): VideoFrame {
        val frameId = frameCounter.incrementAndGet()
        // Compute synthetic 30 FPS PTS increments in nanoseconds
        val ptsNs = (frameId - 1) * 33_333_333L

        // Draw Stage 2 Corner Markers and Diagnostic text
        FrameRenderUtils.drawTestPatternToCanvas(canvas, frameId, ptsNs, width, height)

        byteBuffer.rewind()
        reusableBitmap.copyPixelsToBuffer(byteBuffer)
        byteBuffer.rewind()

        // Create independent direct buffer copy for the canonical frame
        val directBuffer = ByteBuffer.allocateDirect(byteBuffer.capacity())
        directBuffer.put(byteBuffer)
        directBuffer.flip()
        byteBuffer.rewind()

        return VideoFrame(
            frameId = frameId,
            timestampPts = ptsNs,
            width = width,
            height = height,
            stride = width * 4,
            pixelFormat = PixelFormat.TEST_PATTERN,
            buffer = directBuffer,
            rotationDegrees = rotationDegrees,
            isMirrored = isMirrored
        )
    }
}
