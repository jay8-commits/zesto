package com.example.zesto.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.example.zesto.frame.ZestoFrameBridge
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Legacy android.hardware.Camera (Camera1) hook adapter.
 * Intercepts Camera.setPreviewDisplay(SurfaceHolder) and Camera.setPreviewTexture(SurfaceTexture).
 */
object LegacyCameraHook {
    private const val TAG = "ZestoLegacyCameraHook"

    enum class LegacyHookStatus {
        UNINITIALIZED,
        HOOK_REGISTERED,
        PREVIEW_DISPLAY_INTERCEPTED,
        FRAME_PUMP_ACTIVE,
        NOT_PRESENT
    }

    private var currentStatus = LegacyHookStatus.UNINITIALIZED
    private val isPumping = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var pumpTask: Future<*>? = null

    val status: LegacyHookStatus get() = currentStatus

    fun attachHook(classLoader: ClassLoader) {
        try {
            val cameraClass = Class.forName("android.hardware.Camera", false, classLoader)
            currentStatus = LegacyHookStatus.HOOK_REGISTERED
            Log.i(TAG, "[HOOK_REGISTERED] Legacy Camera class identified for Camera1 hooking: $cameraClass")
        } catch (e: ClassNotFoundException) {
            currentStatus = LegacyHookStatus.NOT_PRESENT
            Log.d(TAG, "[NOT_PRESENT] android.hardware.Camera not loaded in classpath")
        } catch (e: Throwable) {
            Log.e(TAG, "Error inspecting legacy camera classes: ${e.message}")
        }
    }

    fun onPreviewDisplaySet(holder: SurfaceHolder) {
        currentStatus = LegacyHookStatus.PREVIEW_DISPLAY_INTERCEPTED
        Log.i(TAG, "[PREVIEW_DISPLAY_INTERCEPTED] Intercepted Camera1 SurfaceHolder preview display.")
        startFramePump(holder.surface)
    }

    fun onPreviewTextureSet(texture: SurfaceTexture) {
        currentStatus = LegacyHookStatus.PREVIEW_DISPLAY_INTERCEPTED
        Log.i(TAG, "[PREVIEW_DISPLAY_INTERCEPTED] Intercepted Camera1 SurfaceTexture preview.")
        val surface = Surface(texture)
        startFramePump(surface)
    }

    private fun startFramePump(surface: Surface) {
        stopFramePump()
        if (!surface.isValid) return
        isPumping.set(true)
        currentStatus = LegacyHookStatus.FRAME_PUMP_ACTIVE

        pumpTask = renderExecutor.submit {
            val paint = Paint().apply { isFilterBitmap = true }
            while (isPumping.get() && surface.isValid) {
                try {
                    val frame = ZestoFrameBridge.consumeLatestFrame()
                    val bitmap = frame.bitmap
                    val canvas: Canvas? = surface.lockCanvas(null)
                    if (canvas != null) {
                        try {
                            if (bitmap != null) {
                                val src = Rect(0, 0, bitmap.width, bitmap.height)
                                val dst = Rect(0, 0, canvas.width, canvas.height)
                                canvas.drawBitmap(bitmap, src, dst, paint)
                            }
                        } finally {
                            surface.unlockCanvasAndPost(canvas)
                        }
                    }
                    Thread.sleep(33L)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Camera1 render cycle exception: ${e.message}")
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

