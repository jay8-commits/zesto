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
    val sourceResolution: String = "1080x1920",
    val outputResolution: String = "1080x1920",
    val sourceAspectRatio: String = "9:16",
    val outputAspectRatio: String = "9:16",
    val rotationDegrees: Int = 0,
    val cropMode: FrameCropMode = FrameCropMode.CENTER_CROP_9_16,
    val activeSurfaceCount: Int = 0,
    val frameCounter: Long = 0L,
    val lastFrameTimestampEpochMs: Long = 0L,
    val reconnectCount: Int = 0
)

/**
 * Result details from calculating frame scaling and center-crop rectangles.
 */
data class FrameTransformMetrics(
    val srcRect: Rect,
    val dstRect: Rect,
    val scale: Float,
    val cropX: Int,
    val cropY: Int,
    val srcWidth: Int,
    val srcHeight: Int,
    val dstWidth: Int,
    val dstHeight: Int,
    val sourceAspectStr: String,
    val targetAspectStr: String,
    val rotationApplied: Int = 0
)

/**
 * Canonical Frame Transformation and Rendering Engine.
 *
 * Guarantees that both the Zesto Test Harness and hooked camera target applications
 * (e.g. Open Camera, Camera2, Camera1, CameraX) receive identical frame transformations,
 * orientation normalization, aspect-ratio cropping, and true 9:16 portrait rendering
 * without distortion, stretching, or unwanted black gaps at top or bottom.
 */
object ZestoFrameTransformer {
    private const val TAG = "ZestoFrameTransformer"

    const val CANONICAL_OUTPUT_WIDTH = 1080
    const val CANONICAL_OUTPUT_HEIGHT = 1920
    const val CANONICAL_ASPECT_RATIO = 9f / 16f

    private val renderPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Calculates the exact source crop rectangle and scale factor to fill the target canvas
     * using an aspect-ratio-preserving Center-Crop strategy (no top/bottom gaps, no distortion).
     */
    fun calculateCropRect(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        mode: FrameCropMode = FrameCropMode.CENTER_CROP_9_16
    ): FrameTransformMetrics {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            val sRect = Rect(0, 0, srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1))
            val dRect = Rect(0, 0, dstWidth.coerceAtLeast(1), dstHeight.coerceAtLeast(1))
            return FrameTransformMetrics(
                srcRect = sRect,
                dstRect = dRect,
                scale = 1.0f,
                cropX = 0,
                cropY = 0,
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = dstWidth,
                dstHeight = dstHeight,
                sourceAspectStr = "UNKNOWN",
                targetAspectStr = "UNKNOWN"
            )
        }

        val dstRect = Rect(0, 0, dstWidth, dstHeight)
        val sourceAspectStr = getAspectRatioString(srcWidth, srcHeight)
        val targetAspectStr = getAspectRatioString(dstWidth, dstHeight)

        if (mode == FrameCropMode.STRETCH_FULL) {
            val scaleX = dstWidth.toFloat() / srcWidth.toFloat()
            return FrameTransformMetrics(
                srcRect = Rect(0, 0, srcWidth, srcHeight),
                dstRect = dstRect,
                scale = scaleX,
                cropX = 0,
                cropY = 0,
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = dstWidth,
                dstHeight = dstHeight,
                sourceAspectStr = sourceAspectStr,
                targetAspectStr = targetAspectStr
            )
        }

        val targetRatio = dstWidth.toFloat() / dstHeight.toFloat()
        val sourceRatio = srcWidth.toFloat() / srcHeight.toFloat()

        val srcRect: Rect
        val cropX: Int
        val cropY: Int
        val scale: Float

        if (sourceRatio > targetRatio) {
            // Source is wider than target relative to height (e.g. 9:16 source into 9:20 tall phone screen)
            // Scale to match target height, and crop horizontal sides to eliminate vertical black gaps
            scale = dstHeight.toFloat() / srcHeight.toFloat()
            val cropWidth = (srcHeight * targetRatio).toInt().coerceIn(1, srcWidth)
            cropX = ((srcWidth - cropWidth) / 2).coerceAtLeast(0)
            cropY = 0
            srcRect = Rect(cropX, 0, (cropX + cropWidth).coerceAtMost(srcWidth), srcHeight)
        } else {
            // Source is taller than target relative to width (e.g. 9:16 source into 3:4 or wider target)
            // Scale to match target width, and crop vertical ends to eliminate horizontal black gaps
            scale = dstWidth.toFloat() / srcWidth.toFloat()
            val cropHeight = (srcWidth / targetRatio).toInt().coerceIn(1, srcHeight)
            cropX = 0
            cropY = ((srcHeight - cropHeight) / 2).coerceAtLeast(0)
            srcRect = Rect(0, cropY, srcWidth, (cropY + cropHeight).coerceAtMost(srcHeight))
        }

        return FrameTransformMetrics(
            srcRect = srcRect,
            dstRect = dstRect,
            scale = scale,
            cropX = cropX,
            cropY = cropY,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            sourceAspectStr = sourceAspectStr,
            targetAspectStr = targetAspectStr
        )
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
        cropMode: FrameCropMode = FrameCropMode.CENTER_CROP_9_16,
        screenWidth: Int = 1080,
        screenHeight: Int = 1920
    ) {
        val dstW = canvas.width
        val dstH = canvas.height
        if (dstW <= 0 || dstH <= 0) return

        if (bitmap != null && !bitmap.isRecycled) {
            val srcW = bitmap.width
            val srcH = bitmap.height

            // Handle Camera HAL landscape buffers (e.g. 1920x1080) when rendering 9:16 portrait video
            if (dstW > dstH && srcW < srcH) {
                // Buffer is landscape (1920x1080) but camera app displays in portrait with 90° rotation
                canvas.save()
                canvas.translate(dstW / 2f, dstH / 2f)
                canvas.rotate(90f)
                canvas.translate(-dstH / 2f, -dstW / 2f)

                // Render with effective destination dimensions (dstH x dstW)
                val metrics = calculateCropRect(srcW, srcH, dstH, dstW, cropMode)
                canvas.drawBitmap(bitmap, metrics.srcRect, metrics.dstRect, renderPaint)
                canvas.restore()

                if (frameId == 1L || frameId % 60L == 0L) {
                    logTransformDiagnostics(
                        metrics = metrics.copy(dstWidth = dstW, dstHeight = dstH, rotationApplied = 90),
                        targetPackage = targetPackage,
                        frameId = frameId,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight
                    )
                }
            } else {
                // Standard portrait rendering onto target surface
                val metrics = calculateCropRect(srcW, srcH, dstW, dstH, cropMode)
                canvas.drawBitmap(bitmap, metrics.srcRect, metrics.dstRect, renderPaint)

                if (frameId == 1L || frameId % 60L == 0L) {
                    logTransformDiagnostics(
                        metrics = metrics,
                        targetPackage = targetPackage,
                        frameId = frameId,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight
                    )
                }
            }
        } else {
            renderPortraitStandbyPattern(canvas, targetPackage, frameId, healthState)
        }
    }

    /**
     * Emits the required dimension and aspect-ratio transform diagnostics to logcat.
     */
    private fun logTransformDiagnostics(
        metrics: FrameTransformMetrics,
        targetPackage: String,
        frameId: Long,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val logMsg = buildString {
            append("SOURCE_SIZE=${metrics.srcWidth}x${metrics.srcHeight} ")
            append("DECODE_SIZE=${metrics.srcWidth}x${metrics.srcHeight} ")
            append("BRIDGE_SIZE=${metrics.srcWidth}x${metrics.srcHeight} ")
            append("PIPELINE_SIZE=${metrics.srcWidth}x${metrics.srcHeight} ")
            append("TARGET_SURFACE_SIZE=${metrics.dstWidth}x${metrics.dstHeight} ")
            append("TARGET_PREVIEW_SIZE=${metrics.dstWidth}x${metrics.dstHeight} ")
            append("SCREEN_SIZE=${screenWidth}x${screenHeight} ")
            append("SOURCE_ASPECT=${metrics.sourceAspectStr} ")
            append("TARGET_ASPECT=${metrics.targetAspectStr} ")
            append("SCALE=${String.format(Locale.US, "%.3f", metrics.scale)} ")
            append("CROP_X=${metrics.cropX} ")
            append("CROP_Y=${metrics.cropY} ")
            if (metrics.rotationApplied != 0) {
                append("ROTATION=${metrics.rotationApplied}deg ")
            }
            append("(frame #$frameId, target=$targetPackage)")
        }
        Log.i(TAG, "[FRAME_TRANSFORM] $logMsg")
    }

    /**
     * Renders an elegant, full-bleed 9:16 portrait standby test pattern card without empty edge gaps.
     */
    fun renderPortraitStandbyPattern(
        canvas: Canvas,
        targetPackage: String,
        frameId: Long,
        healthState: String
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // 1. Full-Bleed Dark Slate Canvas (Fills entire screen 0 to w, 0 to h)
        val bgPaint = Paint().apply { color = Color.rgb(11, 17, 33) }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Full-Width Top Accent Strip (Edge-to-Edge)
        val emeraldPaint = Paint().apply {
            color = Color.rgb(16, 185, 129)
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, w, (h * 0.015f).coerceAtLeast(8f), emeraldPaint)

        // 3. Full-Width Bottom Accent Strip (Edge-to-Edge)
        canvas.drawRect(0f, h - (h * 0.015f).coerceAtLeast(8f), w, h, emeraldPaint)

        // 4. Center High-Tech Container Card
        val cardPaint = Paint().apply {
            color = Color.rgb(20, 29, 47)
            isAntiAlias = true
        }
        val cardMarginX = w * 0.04f
        val cardMarginY = h * 0.06f
        canvas.drawRoundRect(
            cardMarginX,
            cardMarginY,
            w - cardMarginX,
            h - cardMarginY,
            24f,
            24f,
            cardPaint
        )

        // Top Card Accent Bar
        canvas.drawRoundRect(
            cardMarginX,
            cardMarginY,
            w - cardMarginX,
            cardMarginY + (h * 0.012f),
            12f,
            12f,
            emeraldPaint
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

        val subHeaderPaint = Paint().apply {
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
        canvas.drawText("CANONICAL 9:16 PORTRAIT PIPELINE", startX, currentY, subHeaderPaint)

        currentY += lineSpacing * 1.3f
        canvas.drawText("TARGET: $targetPackage", startX, currentY, titlePaint)
        currentY += lineSpacing
        canvas.drawText("OUTPUT: ${canvas.width}x${canvas.height} (9:16 Full)", startX, currentY, bodyPaint)
        currentY += lineSpacing
        canvas.drawText("STATUS: $healthState", startX, currentY, if (healthState == "FRAME_ACTIVE") subHeaderPaint else bodyPaint)
        currentY += lineSpacing
        canvas.drawText("PIPELINE: RTSP -> Decoder -> Camera Hook", startX, currentY, bodyPaint)

        currentY += lineSpacing * 1.3f
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        canvas.drawText("CYCLE: #$frameId", startX, currentY, subHeaderPaint)
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

