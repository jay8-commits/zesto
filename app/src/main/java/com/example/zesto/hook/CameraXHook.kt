package com.example.zesto.hook

import android.graphics.Canvas
import android.util.Log
import android.view.Surface
import com.example.zesto.frame.FrameCropMode
import com.example.zesto.frame.ZestoFrameTransformer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val substitutedFramesCount = AtomicLong(0L)

    val status: CameraXStatus get() = currentStatus

    fun attachHook(classLoader: ClassLoader, targetPackage: String = "unknown") {
        try {
            val previewClass = Class.forName("androidx.camera.core.Preview", false, classLoader)

            XposedHelpers.findAndHookMethod(
                previewClass,
                "setSurfaceProvider",
                Class.forName("androidx.camera.core.Preview\$SurfaceProvider", false, classLoader),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val msg = "Target process ($targetPackage) configured CameraX Preview SurfaceProvider"
                        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
                        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
                    }
                }
            )

            currentStatus = CameraXStatus.HOOK_REGISTERED
            Log.i(TAG, "[HOOK_REGISTERED] androidx.camera.core.Preview class hooked for package: $targetPackage")
        } catch (e: ClassNotFoundException) {
            currentStatus = CameraXStatus.NOT_PRESENT
            Log.d(TAG, "[NOT_PRESENT] CameraX Preview class not present in target app classpath")
        } catch (e: Throwable) {
            Log.e(TAG, "Error inspecting CameraX classes: ${e.message}")
        }
    }

    fun onSurfaceProvided(surface: Surface, targetPackage: String = "unknown") {
        currentStatus = CameraXStatus.SURFACE_PROVIDER_INTERCEPTED
        Log.i(TAG, "[SURFACE_PROVIDER_INTERCEPTED] CameraX provided target preview surface.")
        Log.i(TAG, "[SURFACE_ATTACHED] CameraX target surface attached.")
        startFramePump(surface, targetPackage)
    }

    private fun startFramePump(surface: Surface, targetPackage: String = "unknown") {
        stopFramePump()
        if (!surface.isValid) return
        isPumping.set(true)
        currentStatus = CameraXStatus.FRAME_PUMP_ACTIVE

        val activeMsg = "Active frame substitution pump rendering 9:16 portrait frames onto CameraX surface"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        Log.i(TAG, "[FRAME_SOURCE_STARTED] CameraX frame substitution pump started for $targetPackage")
        ZestoRemoteFrameSource.reportMilestone("FRAME_SUBSTITUTION_ACTIVE", activeMsg)

        pumpTask = renderExecutor.submit {
            var cycleCount = 0L

            while (isPumping.get() && surface.isValid) {
                try {
                    val frameResult = ZestoRemoteFrameSource.fetchLatestFrame()
                    val bitmap = frameResult.bitmap
                    cycleCount++

                    var canvas: Canvas? = null
                    try {
                        canvas = surface.lockCanvas(null)
                        if (canvas != null) {
                            ZestoFrameTransformer.renderToCanvas(
                                canvas = canvas,
                                bitmap = bitmap,
                                targetPackage = targetPackage,
                                frameId = if (frameResult.frameId > 0) frameResult.frameId else cycleCount,
                                healthState = frameResult.healthState,
                                cropMode = FrameCropMode.CENTER_CROP_9_16
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CameraX render canvas exception: ${e.message}")
                    } finally {
                        if (canvas != null) {
                            try {
                                surface.unlockCanvasAndPost(canvas)
                                val count = substitutedFramesCount.incrementAndGet()
                                if (count == 1L || count % 60L == 0L) {
                                    val logMsg = "Target preview surface received and displayed frame #$count"
                                    Log.i(TAG, "[TARGET_PREVIEW_RECEIVED_FRAME] $logMsg")
                                    ZestoRemoteFrameSource.reportMilestone("TARGET_PREVIEW_RECEIVED_FRAME", logMsg)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "CameraX unlock canvas exception: ${e.message}")
                            }
                        }
                    }
                    Thread.sleep(33L)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "CameraX render cycle error: ${e.message}")
                }
            }
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] CameraX frame pump loop exited")
        }
    }

    fun stopFramePump() {
        if (isPumping.getAndSet(false)) {
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] CameraX frame substitution pump stopped.")
        }
        pumpTask?.cancel(true)
        pumpTask = null
    }
}
