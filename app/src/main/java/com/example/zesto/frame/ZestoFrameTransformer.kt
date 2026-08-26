package com.example.zesto.frame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crop and aspect transformation modes for camera frame normalization.
 */
enum class FrameCropMode {
    CENTER_CROP_9_16,
    FIT_CENTER,
    STRETCH_FULL
}

/**
 * Diagnostic snapshot of the active frame transformation and delivery pipeline.
 */
data class FramePipelineDiagnostics(
    val targetPackage: String = "unknown",
    val moduleState: String = "INITIALIZED",
    val hookState: String = "HOOK_REGISTERED",
    val sourceState: String = "IDLE",
    val sourceResolution: String = "1280x720",
    val outputResolution: String = "1080x1920",
    val sourceAspectRatio: String = "16:9",
    val outputAspectRatio: String = "9:16",
    val rotationDegrees: Int = 0,
    val cropMode: FrameCropMode = FrameCropMode.CENTER_CROP_9_16,
    val activeSurfaceCount: Int = 0,
    val frameCounter: Long = 0L,
    val lastFrameTimestampEpochMs: Long = 0L,
    val reconnectCount: Int = 0
)

/**
 * Canonical Frame Transformation and Rendering Engine.
 *
 * Guarantees that both the Zesto Test Harness and hooked camera target applications
 * (e.g. Open Camera, Camera2, Camera1, CameraX) receive identical frame transformations,
 * orientation normalization, aspect-ratio cropping, and true 9:16 portrait rendering
 * without distortion or stretching.
 */
object ZestoFrameTransformer {
    private const val TAG = "ZestoFrameTransformer"

    const val CANONICAL_OUTPUT_WIDTH = 1080
    const val CANONICAL_OUTPUT_HEIGHT = 1920
    const val CANONICAL_ASPECT_RATIO = 9f / 16f

    private val renderPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Calculates the exact source crop rectangle to match the target canvas aspect ratio
     * without distortion or stretching.
     */
    fun calculateCropRect(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        mode: FrameCropMode = FrameCropMode.CENTER_CROP_9_16
    ): Pair<Rect, Rect> {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            val sRect = Rect(0, 0, srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1))
            val dRect = Rect(0, 0, dstWidth.coerceAtLeast(1), dstHeight.coerceAtLeast(1))
            return Pair(sRect, dRect)
        }

        val dstRect = Rect(0, 0, dstWidth, dstHeight)

        if (mode == FrameCropMode.STRETCH_FULL) {
            return Pair(Rect(0, 0, srcWidth, srcHeight), dstRect)
        }

        val targetRatio = dstWidth.toFloat() / dstHeight.toFloat()
        val sourceRatio = srcWidth.toFloat() / srcHeight.toFloat()

        val srcRect = if (sourceRatio > targetRatio) {
            // Source is wider than destination (e.g. 16:9 source into 9:16 destination)
            // Crop width to match destination aspect ratio
            val cropWidth = (srcHeight * targetRatio).toInt()
            val left = ((srcWidth - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + cropWidth).coerceAtMost(srcWidth), srcHeight)
        } else {
            // Source is taller than destination
            // Crop height to match destination aspect ratio
            val cropHeight = (srcWidth / targetRatio).toInt()
            val top = ((srcHeight - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, srcWidth, (top + cropHeight).coerceAtMost(srcHeight))
        }

        return Pair(srcRect, dstRect)
    }

    /**
     * Renders a transformed video frame onto any target Canvas with true 9:16 portrait cropping.
     */
    fun renderToCanvas(
        canvas: Canvas,
        bitmap: Bitmap?,
        targetPackage: String,
        frameId: Long,
        healthState: String,
        cropMode: FrameCropMode = FrameCropMode.CENTER_CROP_9_16
    ) {
        val dstW = canvas.width
        val dstH = canvas.height
        if (dstW <= 0 || dstH <= 0) return

        if (bitmap != null && !bitmap.isRecycled) {
            val (srcRect, dstRect) = calculateCropRect(bitmap.width, bitmap.height, dstW, dstH, cropMode)
            canvas.drawBitmap(bitmap, srcRect, dstRect, renderPaint)
        } else {
            renderPortraitStandbyPattern(canvas, targetPackage, frameId, healthState)
        }
    }

    /**
     * Renders an elegant, high-contrast 9:16 portrait standby test pattern card.
     */
    fun renderPortraitStandbyPattern(
        canvas: Canvas,
        targetPackage: String,
        frameId: Long,
        healthState: String
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // Background Dark Canvas
        val bgPaint = Paint().apply { color = Color.rgb(11, 17, 33) }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Center Card Container
        val cardPaint = Paint().apply { color = Color.rgb(20, 29, 47) }
        val cardMarginX = w * 0.06f
        val cardMarginY = h * 0.12f
        canvas.drawRoundRect(
            cardMarginX,
            cardMarginY,
            w - cardMarginX,
            h - cardMarginY,
            24f,
            24f,
            cardPaint
        )

        // Emerald Accent Bar
        val accentPaint = Paint().apply { color = Color.rgb(16, 185, 129) }
        canvas.drawRoundRect(
            cardMarginX,
            cardMarginY,
            w - cardMarginX,
            cardMarginY + (h * 0.015f),
            12f,
            12f,
            accentPaint
        )

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = (h * 0.026f).coerceIn(28f, 52f)
            isAntiAlias = true
            isFakeBoldText = true
        }

        val bodyPaint = Paint().apply {
            color = Color.rgb(148, 163, 184)
            textSize = (h * 0.018f).coerceIn(20f, 36f)
            isAntiAlias = true
        }

        val emeraldPaint = Paint().apply {
            color = Color.rgb(16, 185, 129)
            textSize = (h * 0.019f).coerceIn(22f, 38f)
            isAntiAlias = true
            isFakeBoldText = true
        }

        val startX = cardMarginX + (w * 0.05f)
        var currentY = cardMarginY + (h * 0.06f)
        val lineSpacing = h * 0.045f

        canvas.drawText("ZESTO VIRTUAL CAMERA", startX, currentY, titlePaint)
        currentY += lineSpacing * 0.9f
        canvas.drawText("CANONICAL 9:16 PORTRAIT PIPELINE", startX, currentY, emeraldPaint)

        currentY += lineSpacing * 1.3f
        canvas.drawText("TARGET: $targetPackage", startX, currentY, titlePaint)
        currentY += lineSpacing
        canvas.drawText("OUTPUT: 1080x1920 (9:16)", startX, currentY, bodyPaint)
        currentY += lineSpacing
        canvas.drawText("STATUS: $healthState", startX, currentY, if (healthState == "FRAME_ACTIVE") emeraldPaint else bodyPaint)
        currentY += lineSpacing
        canvas.drawText("PIPELINE: RTSP -> Decoder -> Camera Hook", startX, currentY, bodyPaint)

        currentY += lineSpacing * 1.3f
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        canvas.drawText("CYCLE: #$frameId", startX, currentY, emeraldPaint)
        currentY += lineSpacing
        canvas.drawText("TIME: $timeStr", startX, currentY, bodyPaint)
    }

    /**
     * Computes the simplified aspect ratio string from dimensions (e.g. "16:9", "9:16", "4:3").
     */
    fun getAspectRatioString(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "UNKNOWN"
        val ratio = width.toFloat() / height.toFloat()
        return when {
            kotlin.math.abs(ratio - (16f / 9f)) < 0.05f -> "16:9"
            kotlin.math.abs(ratio - (9f / 16f)) < 0.05f -> "9:16"
            kotlin.math.abs(ratio - (4f / 3f)) < 0.05f -> "4:3"
            kotlin.math.abs(ratio - (3f / 4f)) < 0.05f -> "3:4"
            kotlin.math.abs(ratio - 1f) < 0.05f -> "1:1"
            else -> String.format(Locale.US, "%.2f:1", ratio)
        }
    }
}
