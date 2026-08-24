package com.example.zesto.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import com.example.zesto.frame.ZestoFrameBridge
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jetpack CameraX API bytecode/reflection hook adapter.
 * Intercepts androidx.camera.core.Preview.setSurfaceProvider to bind
 * Zesto's virtualized SurfaceProvider into the target CameraX lifecycle.
 */
object CameraXHook {
    private const val TAG = "ZestoCameraXHook"

    enum class CameraXStatus {
        UNINITIALIZED,
        HOOK_REGISTERED,
        SURFACE_PROVIDER_INTERCEPTED,
        FRAME_PUMP_ACTIVE,
        NOT_PRESENT
    }

    private var currentStatus = CameraXStatus.UNINITIALIZED
    private val isPumping = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var pumpTask: Future<*>? = null

    val status: CameraXStatus get() = currentStatus

    fun attachHook(classLoader: ClassLoader) {
        try {
            val previewClass = Class.forName("androidx.camera.core.Preview", false, classLoader)
            currentStatus = CameraXStatus.HOOK_REGISTERED
            Log.i(TAG, "[HOOK_REGISTERED] androidx.camera.core.Preview class identified for CameraX hooking: $previewClass")
        } catch (e: ClassNotFoundException) {
            currentStatus = CameraXStatus.NOT_PRESENT
            Log.d(TAG, "[NOT_PRESENT] CameraX Preview class not present in target app classpath")
        } catch (e: Throwable) {
            Log.e(TAG, "Error inspecting CameraX classes: ${e.message}")
        }
    }

    fun onSurfaceProvided(surface: Surface) {
        currentStatus = CameraXStatus.SURFACE_PROVIDER_INTERCEPTED
        Log.i(TAG, "[SURFACE_PROVIDER_INTERCEPTED] CameraX provided target preview surface.")
        startFramePump(surface)
    }

    private fun startFramePump(surface: Surface) {
        stopFramePump()
        if (!surface.isValid) return
        isPumping.set(true)
        currentStatus = CameraXStatus.FRAME_PUMP_ACTIVE

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
                    Log.w(TAG, "CameraX render cycle error: ${e.message}")
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

