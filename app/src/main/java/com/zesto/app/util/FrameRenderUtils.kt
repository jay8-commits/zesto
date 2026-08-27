package com.zesto.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.zesto.app.model.PixelFormat
import com.zesto.app.model.VideoFrame

/**
 * Shared deterministic frame rendering and transformation utilities for
 * Preview and Camera Injection paths.
 */
object FrameRenderUtils {

    /**
     * Deterministically creates or fills a Bitmap from the canonical VideoFrame.
     * Both preview and injection consumers MUST use this identical routine if Bitmap
     * conversion is required.
     */
    fun createDeterministicBitmap(frame: VideoFrame, reusableBitmap: Bitmap? = null): Bitmap? {
        if (frame.buffer == null) return null

        val targetWidth = frame.width
        val targetHeight = frame.height
        if (targetWidth <= 0 || targetHeight <= 0) return null

        val bmp = if (reusableBitmap != null &&
            reusableBitmap.width == targetWidth &&
            reusableBitmap.height == targetHeight &&
            reusableBitmap.config == Bitmap.Config.ARGB_8888
        ) {
            reusableBitmap
        } else {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }

        when (frame.pixelFormat) {
            PixelFormat.RGBA_8888, PixelFormat.TEST_PATTERN -> {
                val currentPos = frame.buffer.position()
                frame.buffer.rewind()
                bmp.copyPixelsFromBuffer(frame.buffer)
                frame.buffer.position(currentPos)
            }
            PixelFormat.NV21, PixelFormat.YUV_420_888 -> {
                // Deterministic software YUV->RGB fallback for Canvas blitting
                decodeYuv420ToBitmap(frame, bmp)
            }
            else -> {
                // Unsupported formats return null safely
                return null
            }
        }

        return bmp
    }

    /**
     * Computes the uniform transformation matrix (aspect-fit letterboxing, rotation, and mirroring)
     * from source VideoFrame dimensions to the target Canvas/Surface viewport.
     */
    fun computeTransformMatrix(
        frame: VideoFrame,
        destWidth: Int,
        destHeight: Int
    ): Matrix {
        val matrix = Matrix()
        if (destWidth <= 0 || destHeight <= 0 || frame.width <= 0 || frame.height <= 0) {
            return matrix
        }

        // Account for 90 or 270 degree rotation swapping effective frame aspect ratio
        val isRotated = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val effectiveSourceWidth = if (isRotated) frame.height.toFloat() else frame.width.toFloat()
        val effectiveSourceHeight = if (isRotated) frame.width.toFloat() else frame.height.toFloat()

        // Calculate aspect-fit scale
        val scaleX = destWidth.toFloat() / effectiveSourceWidth
        val scaleY = destHeight.toFloat() / effectiveSourceHeight
        val scale = minOf(scaleX, scaleY)

        val scaledWidth = effectiveSourceWidth * scale
        val scaledHeight = effectiveSourceHeight * scale

        val dx = (destWidth - scaledWidth) / 2f
        val dy = (destHeight - scaledHeight) / 2f

        // Center origin for rotation and mirroring
        matrix.postTranslate(-frame.width / 2f, -frame.height / 2f)

        if (frame.isMirrored) {
            matrix.postScale(-1f, 1f)
        }

        if (frame.rotationDegrees != 0) {
            matrix.postRotate(frame.rotationDegrees.toFloat())
        }

        // Scale and translate to destination letterbox/pillarbox viewport
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx + scaledWidth / 2f, dy + scaledHeight / 2f)

        return matrix
    }

    /**
     * Renders a standardized Stage 2 test pattern containing:
     * - Frame ID
     * - Source Presentation Timestamp (PTS)
     * - Top-Left: Red (0xFFFF0000)
     * - Top-Right: Green (0xFF00FF00)
     * - Bottom-Right: Blue (0xFF0000FF)
     * - Bottom-Left: Yellow (0xFFFFFF00)
     */
    fun drawTestPatternToCanvas(
        canvas: Canvas,
        frameId: Long,
        timestampPts: Long,
        width: Int,
        height: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Dark background
        paint.color = 0xFF121212.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val cornerSize = minOf(width, height) * 0.15f

        // Top-Left: RED
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, cornerSize, cornerSize, paint)

        // Top-Right: GREEN
        paint.color = Color.GREEN
        canvas.drawRect(width - cornerSize, 0f, width.toFloat(), cornerSize, paint)

        // Bottom-Right: BLUE
        paint.color = Color.BLUE
        canvas.drawRect(width - cornerSize, height - cornerSize, width.toFloat(), height.toFloat(), paint)

        // Bottom-Left: YELLOW
        paint.color = Color.YELLOW
        canvas.drawRect(0f, height - cornerSize, cornerSize, height.toFloat(), paint)

        // Center diagnostic text
        paint.color = Color.WHITE
        paint.textSize = minOf(width, height) * 0.05f
        paint.textAlign = Paint.Align.CENTER

        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText("ZESTO STAGE 2 TEST PATTERN", cx, cy - 40f, paint)
        canvas.drawText(String.format("FRAME_ID: %06d", frameId), cx, cy + 10f, paint)
        canvas.drawText(String.format("PTS: %d ns", timestampPts), cx, cy + 60f, paint)
    }

    private fun decodeYuv420ToBitmap(frame: VideoFrame, outBitmap: Bitmap) {
        val buffer = frame.buffer ?: return
        val width = frame.width
        val height = frame.height
        val stride = frame.stride

        val pixels = IntArray(width * height)
        val yuvBytes = ByteArray(buffer.remaining())
        val currentPos = buffer.position()
        buffer.get(yuvBytes)
        buffer.position(currentPos)

        val ySize = stride * height
        var outIndex = 0

        for (j in 0 until height) {
            val yOffset = j * stride
            val uvOffset = ySize + (j shr 1) * stride
            for (i in 0 until width) {
                val y = (yuvBytes[yOffset + i].toInt() and 0xFF)
                val uvIndex = uvOffset + (i and 1.inv())
                val u = if (uvIndex + 1 < yuvBytes.size) (yuvBytes[uvIndex + 1].toInt() and 0xFF) - 128 else 0
                val v = if (uvIndex < yuvBytes.size) (yuvBytes[uvIndex].toInt() and 0xFF) - 128 else 0

                var r = (y + (1.370705f * v)).toInt()
                var g = (y - (0.337633f * u) - (0.698001f * v)).toInt()
                var b = (y + (1.732446f * u)).toInt()

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                pixels[outIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        outBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
