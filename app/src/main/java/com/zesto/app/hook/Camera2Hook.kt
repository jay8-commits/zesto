package com.zesto.app.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.Surface
import com.zesto.app.bridge.VideoFrameListener
import com.zesto.app.bridge.ZestoFrameBridge
import com.zesto.app.model.VideoFrame
import com.zesto.app.util.FrameRenderUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Camera2 API hook intercepting capture sessions and delivering canonical
 * VideoFrames push-driven to validated target Surfaces.
 */
class Camera2Hook : VideoFrameListener {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val activeSurfaces = CopyOnWriteArraySet<Surface>()
    private var reusableBitmap: Bitmap? = null

    companion object {
        private const val TAG = "Camera2Hook"
    }

    fun initHooks(classLoader: ClassLoader) {
        try {
            // Subscribe this hook directly to canonical frame arrival
            ZestoFrameBridge.addFrameListener(this)

            // Hook CameraDevice.createCaptureSession(List<Surface>, ...)
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", classLoader),
                "createCaptureSession",
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val surfaces = param.args[0] as? List<Surface>
                        surfaces?.let {
                            activeSurfaces.clear()
                            for (surface in it) {
                                validateAndRegisterSurface(surface)
                            }
                        }
                    }
                }
            )

            // Hook CameraCaptureSession.setRepeatingRequest
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.hardware.camera2.impl.CameraCaptureSessionImpl", classLoader),
                "setRepeatingRequest",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Repeating request hook for future Stage 3 metadata injection
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Zesto Camera2Hook initialization error: ${e.message}")
        }
    }

    /**
     * Surface classification and safety check.
     */
    private fun validateAndRegisterSurface(surface: Surface) {
        val surfaceString = surface.toString()
        val isValid = surface.isValid

        // Classify surface type safely
        val isImageReaderSurface = surfaceString.contains("ImageReader") || surfaceString.contains("Surface(name=null)")
        val surfaceType = if (isImageReaderSurface) "ImageReader" else "Window/SurfaceView/TextureView"
        val isSupportedForCanvas = isValid && !isImageReaderSurface

        Log.i(
            TAG,
            String.format(
                "[INJECT_SURFACE] surface=%s type=%s supported=%b",
                surfaceString,
                surfaceType,
                isSupportedForCanvas
            )
        )

        if (isSupportedForCanvas) {
            activeSurfaces.add(surface)
        }
    }

    /**
     * Push-driven frame delivery callback triggered whenever FramePipeline produces a frame.
     */
    override fun onFrameAvailable(frame: VideoFrame) {
        if (activeSurfaces.isEmpty()) return

        // Stage 2 Diagnostic Telemetry: Log injection frame delivery attempt
        Log.i(TAG, frame.toLogString("INJECT_FRAME"))

        val bmp = FrameRenderUtils.createDeterministicBitmap(frame, reusableBitmap)
        if (bmp != null) {
            reusableBitmap = bmp
            for (surface in activeSurfaces) {
                if (surface.isValid) {
                    injectFrameToSurface(surface, frame, bmp)
                }
            }
        }
    }

    private fun injectFrameToSurface(surface: Surface, frame: VideoFrame, bitmap: Bitmap) {
        var canvas: Canvas? = null
        try {
            canvas = surface.lockHardwareCanvas()
        } catch (e: Exception) {
            try {
                canvas = surface.lockCanvas(null)
            } catch (e2: Exception) {
                // Skip safely and log without crashing the target app
                Log.w(TAG, "Skipping unsupported surface lock: ${e2.message}")
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

    fun release() {
        ZestoFrameBridge.removeFrameListener(this)
        activeSurfaces.clear()
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
