package com.example.zesto.frame

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Explicit frame source modes for authoritative pipeline selection.
 */
enum class FrameSourceMode {
    RTSP,
    TEST_PATTERN
}

/**
 * Pixel format types for video frames.
 */
enum class PixelFormat(val description: String, val bytesPerPixel: Float) {
    YUV420P("YUV 4:2:0 Planar (I420)", 1.5f),
    NV21("YUV 4:2:0 Semi-Planar (NV21 - Android Camera standard)", 1.5f),
    NV12("YUV 4:2:0 Semi-Planar (NV12)", 1.5f),
    RGBA_8888("32-bit RGBA", 4.0f),
    HARDWARE_BUFFER("Android AHardwareBuffer (Zero-copy GPU)", 0.0f),
    SURFACE_TEXTURE("Direct SurfaceTexture / OpenGL OES Texture", 0.0f)
}

/**
 * Clean abstraction for video frames across the entire Zesto pipeline.
 * Avoids CPU copies wherever possible while supporting format conversion for downstream consumers.
 */
data class VideoFrame(
    val frameNumber: Long,
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val rotationDegrees: Int = 0,
    val buffer: ByteBuffer? = null,
    val stride: Int = width,
    val bitmap: Bitmap? = null,
    val sourceMode: FrameSourceMode = FrameSourceMode.RTSP
) {
    fun hasBuffer(): Boolean = buffer != null && buffer.remaining() > 0

    fun calculateDataSize(): Int {
        return (width * height * pixelFormat.bytesPerPixel).toInt()
    }
}
