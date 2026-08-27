package com.zesto.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.Surface
import com.zesto.app.model.VideoFrame
import com.zesto.app.util.FrameRenderUtils
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Camera virtualization backend interface defining contract for frame delivery.
 */
interface CameraVirtualizationBackend {
    fun initialize()
    fun attachTargetSurfaces(surfaces: List<Surface>)
    fun deliverFrame(frame: VideoFrame)
    fun detachTargetSurfaces()
    fun release()
}

/**
 * Camera2 implementation of virtualization backend.
 */
class Camera2Backend : CameraVirtualizationBackend {
    private val targetSurfaces = CopyOnWriteArrayList<Surface>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var reusableBitmap: Bitmap? = null

    companion object {
        private const val TAG = "Camera2Backend"
    }

    override fun initialize() {}

    override fun attachTargetSurfaces(surfaces: List<Surface>) {
        targetSurfaces.clear()
        for (surface in surfaces) {
            val surfaceString = surface.toString()
            val isValid = surface.isValid
            val isImageReaderSurface = surfaceString.contains("ImageReader") || surfaceString.contains("Surface(name=null)")
            val surfaceType = if (isImageReaderSurface) "ImageReader" else "Window/SurfaceView/TextureView"
            val isSupported = isValid && !isImageReaderSurface

            Log.i(
                TAG,
                String.format(
                    "[INJECT_SURFACE] surface=%s type=%s supported=%b",
                    surfaceString,
                    surfaceType,
                    isSupported
                )
            )

            if (isSupported) {
                targetSurfaces.add(surface)
            }
        }
    }

    override fun deliverFrame(frame: VideoFrame) {
        if (targetSurfaces.isEmpty()) return

        Log.i(TAG, frame.toLogString("INJECT_FRAME"))

        val bmp = FrameRenderUtils.createDeterministicBitmap(frame, reusableBitmap)
        if (bmp != null) {
            reusableBitmap = bmp
            for (surface in targetSurfaces) {
                if (surface.isValid) {
                    renderToSurface(surface, frame, bmp)
                }
            }
        }
    }

    private fun renderToSurface(surface: Surface, frame: VideoFrame, bitmap: Bitmap) {
        var canvas: Canvas? = null
        try {
            canvas = surface.lockHardwareCanvas()
        } catch (_: Exception) {
            try {
                canvas = surface.lockCanvas(null)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unsupported surface lock in Camera2Backend: ${e.message}")
                return
            }
        }

        if (canvas != null) {
            try {
                canvas.drawColor(Color.BLACK)
                val matrix = FrameRenderUtils.computeTransformMatrix(frame, canvas.width, canvas.height)
                canvas.drawBitmap(bitmap, matrix, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }
    }

    override fun detachTargetSurfaces() {
        targetSurfaces.clear()
    }

    override fun release() {
        detachTargetSurfaces()
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
