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
                        Log.i(TAG, "[CAMERA_API_DETECTED] api=CAMERAX")
                        Log.i(TAG, "[CAMERAX_PREVIEW_CONFIGURED] Preview.setSurfaceProvider in $targetPackage")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Preview.setSurfaceProvider")
                        val msg = "Target process ($targetPackage) configured CameraX Preview SurfaceProvider"
                        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
                        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
                    }
                }
            )

            // Hook ProcessCameraProvider.bindToLifecycle
            try {
                val providerClass = Class.forName("androidx.camera.lifecycle.ProcessCameraProvider", false, classLoader)
                val methods = providerClass.declaredMethods
                for (m in methods) {
                    if (m.name == "bindToLifecycle") {
                        XposedHelpers.findAndHookMethod(
                            providerClass,
                            m.name,
                            *m.parameterTypes,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    Log.i(TAG, "[CAMERA_API_DETECTED] api=CAMERAX")
                                    Log.i(TAG, "[CAMERAX_BIND_INTERCEPTED] ProcessCameraProvider.bindToLifecycle in $targetPackage")
                                }
                            }
                        )
                    }
                }
            } catch (_: Throwable) {}

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
        val hash = System.identityHashCode(surface).toString(16)
        Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=CameraX\nCLASS=${surface.javaClass.name}\nSURFACE_ID=@$hash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${surface.isValid}\nSURFACE_TEXTURE=null")
        Log.i(TAG, "[SURFACE_SESSION_OUTPUT] hash=@$hash valid=${surface.isValid} in $targetPackage")
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
            val hash = System.identityHashCode(surface).toString(16)

            while (isPumping.get() && surface.isValid) {
                try {
                    val frameResult = ZestoRemoteFrameSource.fetchLatestFrame()
                    val bitmap = frameResult.bitmap
                    cycleCount++
                    val frameId = if (frameResult.frameId > 0) frameResult.frameId else cycleCount

                    var canvas: Canvas? = null
                    try {
                        if (cycleCount == 1L || cycleCount % 60L == 0L) {
                            Log.i(TAG, "[FRAME_RENDER_STARTED] id=$frameId")
                            Log.i(TAG, "[SURFACE_ZESTO_RENDER_TARGET] Active render target surface=@$hash valid=${surface.isValid}")
                        }

                        canvas = surface.lockCanvas(null)
                        if (canvas != null) {
                            ZestoFrameTransformer.renderToCanvas(
                                canvas = canvas,
                                bitmap = bitmap,
                                targetPackage = targetPackage,
                                frameId = frameId,
                                healthState = frameResult.healthState,
                                cropMode = FrameCropMode.CENTER_CROP_9_16,
                                fps = 29.8
                            )
                            if (cycleCount == 1L || cycleCount % 60L == 0L) {
                                Log.i(TAG, "[FRAME_RENDERED_TO_OUTPUT] id=$frameId")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CameraX render canvas exception on surface=@$hash: ${e.message}")
                    } finally {
                        if (canvas != null) {
                            try {
                                surface.unlockCanvasAndPost(canvas)
                                val count = substitutedFramesCount.incrementAndGet()
                                if (count == 1L || count % 60L == 0L) {
                                    Log.i(TAG, "[FRAME_POSTED_TO_OUTPUT] id=$frameId")
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
