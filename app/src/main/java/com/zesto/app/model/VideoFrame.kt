package com.zesto.app.model

import android.graphics.Rect
import java.nio.ByteBuffer

/**
 * Pixel format enum for frames flowing through Zesto.
 */
enum class PixelFormat {
    RGBA_8888,
    YUV_420_888,
    NV21,
    TEST_PATTERN,
    SURFACE_TEXTURE,
    HARDWARE_BUFFER
}

/**
 * Canonical VideoFrame representation in Zesto frame pipeline.
 *
 * All pipeline stages (decoder, test generator, preview renderer, and camera injection)
 * MUST share this exact canonical structure.
 *
 * @param frameId Monotonically increasing 64-bit frame identifier assigned at source/decoder.
 * @param timestampPts Presentation timestamp (PTS) in nanoseconds derived directly from
 *                     the source/decoder (e.g. MediaCodec BufferInfo.presentationTimeUs * 1000L).
 * @param width Visible active frame width in pixels.
 * @param height Visible active frame height in pixels.
 * @param stride Row stride in bytes/pixels.
 * @param pixelFormat Canonical pixel format.
 * @param buffer Read-only direct ByteBuffer holding raw pixel payload.
 * @param rawBytes Optional raw byte array representation.
 * @param cropRect Optional crop rectangle within the frame.
 * @param rotationDegrees Clockwise rotation metadata (0, 90, 180, 270).
 * @param isMirrored Sensor horizontal mirroring flag (e.g. front camera).
 */
data class VideoFrame(
    val frameId: Long,
    val timestampPts: Long,
    val width: Int,
    val height: Int,
    val stride: Int = width,
    val pixelFormat: PixelFormat,
    val buffer: ByteBuffer? = null,
    val rawBytes: ByteArray? = null,
    val cropRect: Rect? = null,
    val rotationDegrees: Int = 0,
    val isMirrored: Boolean = false
) {
    fun toLogString(tag: String): String {
        return String.format(
            "[%s] frameId=%d pts=%d dim=%dx%d stride=%d format=%s rotation=%d mirrored=%b",
            tag,
            frameId,
            timestampPts,
            width,
            height,
            stride,
            pixelFormat.name,
            rotationDegrees,
            isMirrored
        )
    }
}
