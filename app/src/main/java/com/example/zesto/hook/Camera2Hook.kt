package com.example.zesto.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.ZestoFrameBridge
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 API bytecode/reflection hook adapter.
 * Intercepts target application CameraDevice.createCaptureSession calls
 * and injects decoded OBS frames from Zesto into the target application surfaces.
 */
object Camera2Hook {
    private const val TAG = "ZestoCamera2Hook"

    enum class HookStatus {
        HOOK_UNINITIALIZED,
        HOOK_REGISTERED,
        CAMERA2_SESSION_INTERCEPTED,
        SURFACE_TARGET_ATTACHED,
        FRAME_PUMP_ACTIVE,
        HOOK_FAILED
    }

    private var currentStatus = HookStatus.HOOK_UNINITIALIZED
    private val isPumping = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var pumpTask: Future<*>? = null

    val status: HookStatus get() = currentStatus

    fun attachHook(classLoader: ClassLoader) {
        try {
            val cameraDeviceClass = Class.forName("android.hardware.camera2.CameraDevice", false, classLoader)
            currentStatus = HookStatus.HOOK_REGISTERED
            Log.i(TAG, "[HOOK_REGISTERED] CameraDevice class loaded for Camera2 interception: $cameraDeviceClass")
        } catch (e: ClassNotFoundException) {
            currentStatus = HookStatus.HOOK_FAILED
            Log.w(TAG, "[HOOK_FAILED] CameraDevice class not found in target classLoader: ${e.message}")
        } catch (e: Throwable) {
            currentStatus = HookStatus.HOOK_FAILED
            Log.e(TAG, "[HOOK_FAILED] Unexpected error inspecting Camera2 classes: ${e.message}")
        }
    }

    /**
     * Called when a target CameraCaptureSession is created.
     * Hooks target surfaces and starts the live frame pump.
     */
    fun onSessionConfigured(outputs: List<Surface>, callback: Any? = null) {
        currentStatus = HookStatus.CAMERA2_SESSION_INTERCEPTED
        Log.i(TAG, "[CAMERA2_SESSION_INTERCEPTED] Intercepted session with ${outputs.size} output surfaces.")

        val validSurfaces = outputs.filter { it.isValid }
        if (validSurfaces.isNotEmpty()) {
            currentStatus = HookStatus.SURFACE_TARGET_ATTACHED
            Log.i(TAG, "[SURFACE_TARGET_ATTACHED] Attached to ${validSurfaces.size} valid target surfaces.")
            startFramePump(validSurfaces)
        }
    }

    private fun startFramePump(surfaces: List<Surface>) {
        stopFramePump()
        isPumping.set(true)
        currentStatus = HookStatus.FRAME_PUMP_ACTIVE
        Log.i(TAG, "[FRAME_PUMP_ACTIVE] Virtual frame pump launched.")

        pumpTask = renderExecutor.submit {
            val paint = Paint().apply { isFilterBitmap = true }
            val fallbackPaint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                isAntiAlias = true
            }

            while (isPumping.get()) {
                try {
                    val frame = ZestoFrameBridge.consumeLatestFrame()
                    val bitmap = frame.bitmap

                    for (surface in surfaces) {
                        if (!surface.isValid) continue
                        val canvas: Canvas? = surface.lockCanvas(null)
                        if (canvas != null) {
                            try {
                                if (bitmap != null) {
                                    val src = Rect(0, 0, bitmap.width, bitmap.height)
                                    val dst = Rect(0, 0, canvas.width, canvas.height)
                                    canvas.drawBitmap(bitmap, src, dst, paint)
                                } else {
                                    canvas.drawColor(Color.rgb(15, 23, 42))
                                    canvas.drawText("ZESTO CAMERA2 VIRTUAL INJECTION", 50f, 100f, fallbackPaint)
                                    canvas.drawText("Frame ID: ${frame.frameId}", 50f, 160f, fallbackPaint)
                                }
                            } finally {
                                surface.unlockCanvasAndPost(canvas)
                            }
                        }
                    }
                    Thread.sleep(33L) // ~30 FPS frame rate
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Frame pump render cycle exception: ${e.message}")
                }
            }
        }
    }

    fun stopFramePump() {
        isPumping.set(false)
        pumpTask?.cancel(true)
        pumpTask = null
    }
}

