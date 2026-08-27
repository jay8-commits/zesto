package com.zesto.app.ui.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.SurfaceHolder
import com.zesto.app.model.VideoFrame
import com.zesto.app.util.FrameRenderUtils

/**
 * Preview renderer for the local Zesto application UI.
 * Consumes canonical VideoFrame and renders onto the preview SurfaceHolder.
 */
class ZestoPreviewRenderer(private val surfaceHolder: SurfaceHolder) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var reusableBitmap: Bitmap? = null

    companion object {
        private const val TAG = "ZestoPreviewRenderer"
    }

    fun renderFrame(frame: VideoFrame) {
        // Stage 2 Diagnostic Telemetry: Log preview frame receipt
        Log.i(TAG, frame.toLogString("PREVIEW_FRAME"))

        val surface = surfaceHolder.surface
        if (surface == null || !surface.isValid) return

        val canvas: Canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: Exception) {
            try {
                surface.lockCanvas(null)
            } catch (_: Exception) {
                return
            }
        } ?: return

        try {
            // Clear background with letterbox color
            canvas.drawColor(Color.BLACK)

            val bmp = FrameRenderUtils.createDeterministicBitmap(frame, reusableBitmap)
            if (bmp != null) {
                reusableBitmap = bmp
                val matrix = FrameRenderUtils.computeTransformMatrix(frame, canvas.width, canvas.height)
                canvas.drawBitmap(bmp, matrix, paint)
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
