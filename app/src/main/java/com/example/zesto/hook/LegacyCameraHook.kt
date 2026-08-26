package com.example.zesto.hook

import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.example.zesto.frame.FrameCropMode
import com.example.zesto.frame.ZestoFrameTransformer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val substitutedFramesCount = AtomicLong(0L)

    val status: LegacyHookStatus get() = currentStatus

    fun attachHook(classLoader: ClassLoader, targetPackage: String = "unknown") {
        try {
            val cameraClass = Class.forName("android.hardware.Camera", false, classLoader)

            // Hook Camera.open(int)
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "open",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cameraId = param.args.getOrNull(0)?.toString() ?: "0"
                        val msg = "Target process ($targetPackage) requested Camera1 device: cameraId=$cameraId"
                        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
                        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
                    }
                }
            )

            // Hook Camera.open()
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "open",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val msg = "Target process ($targetPackage) requested default Camera1 device"
                        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
                        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
                    }
                }
            )

            // Hook setPreviewDisplay(SurfaceHolder)
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewDisplay",
                SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val holder = param.args.getOrNull(0) as? SurfaceHolder
                        if (holder != null) {
                            onPreviewDisplaySet(holder, targetPackage)
                        }
                    }
                }
            )

            // Hook setPreviewTexture(SurfaceTexture)
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewTexture",
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val texture = param.args.getOrNull(0) as? SurfaceTexture
                        if (texture != null) {
                            onPreviewTextureSet(texture, targetPackage)
                        }
                    }
                }
            )

            currentStatus = LegacyHookStatus.HOOK_REGISTERED
            Log.i(TAG, "[HOOK_REGISTERED] Legacy Camera1 hooks installed for package: $targetPackage")
        } catch (e: ClassNotFoundException) {
            currentStatus = LegacyHookStatus.NOT_PRESENT
            Log.d(TAG, "[NOT_PRESENT] android.hardware.Camera not loaded in classpath")
        } catch (e: Throwable) {
            Log.e(TAG, "Error installing legacy camera hooks: ${e.message}")
        }
    }

    fun onPreviewDisplaySet(holder: SurfaceHolder, targetPackage: String = "unknown") {
        currentStatus = LegacyHookStatus.PREVIEW_DISPLAY_INTERCEPTED
        Log.i(TAG, "[PREVIEW_DISPLAY_INTERCEPTED] Intercepted Camera1 SurfaceHolder preview display.")
        Log.i(TAG, "[SURFACE_ATTACHED] Camera1 SurfaceHolder surface attached.")
        startFramePump(holder.surface, targetPackage)
    }

    fun onPreviewTextureSet(texture: SurfaceTexture, targetPackage: String = "unknown") {
        currentStatus = LegacyHookStatus.PREVIEW_DISPLAY_INTERCEPTED
        Log.i(TAG, "[PREVIEW_DISPLAY_INTERCEPTED] Intercepted Camera1 SurfaceTexture preview.")
        val surface = Surface(texture)
        Log.i(TAG, "[SURFACE_ATTACHED] Camera1 SurfaceTexture surface attached.")
        startFramePump(surface, targetPackage)
    }

    private fun startFramePump(surface: Surface, targetPackage: String = "unknown") {
        stopFramePump()
        if (!surface.isValid) return
        isPumping.set(true)
        currentStatus = LegacyHookStatus.FRAME_PUMP_ACTIVE

        val activeMsg = "Active frame substitution pump rendering 9:16 portrait frames onto Camera1 surface"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        Log.i(TAG, "[FRAME_SOURCE_STARTED] Camera1 frame substitution pump started for $targetPackage")
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
                        Log.w(TAG, "Camera1 render canvas exception: ${e.message}")
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
                                Log.w(TAG, "Camera1 unlock canvas exception: ${e.message}")
                            }
                        }
                    }
                    Thread.sleep(33L)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Camera1 render cycle exception: ${e.message}")
                }
            }
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Camera1 frame pump loop exited")
        }
    }

    fun stopFramePump() {
        if (isPumping.getAndSet(false)) {
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Camera1 frame substitution pump stopped.")
        }
        pumpTask?.cancel(true)
        pumpTask = null
    }
}
