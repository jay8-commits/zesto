package com.example.zesto.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
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
        startFramePump(holder.surface, targetPackage)
    }

    fun onPreviewTextureSet(texture: SurfaceTexture, targetPackage: String = "unknown") {
        currentStatus = LegacyHookStatus.PREVIEW_DISPLAY_INTERCEPTED
        Log.i(TAG, "[PREVIEW_DISPLAY_INTERCEPTED] Intercepted Camera1 SurfaceTexture preview.")
        val surface = Surface(texture)
        startFramePump(surface, targetPackage)
    }

    private fun startFramePump(surface: Surface, targetPackage: String = "unknown") {
        stopFramePump()
        if (!surface.isValid) return
        isPumping.set(true)
        currentStatus = LegacyHookStatus.FRAME_PUMP_ACTIVE

        val activeMsg = "Active frame substitution pump rendering OBS frames onto Camera1 surface"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        ZestoRemoteFrameSource.reportMilestone("FRAME_SUBSTITUTION_ACTIVE", activeMsg)

        pumpTask = renderExecutor.submit {
            val paint = Paint().apply { isFilterBitmap = true }
            var cycleCount = 0L

            while (isPumping.get() && surface.isValid) {
                try {
                    val frameResult = ZestoRemoteFrameSource.fetchLatestFrame()
                    val bitmap = frameResult.bitmap
                    cycleCount++

                    val canvas: Canvas? = surface.lockCanvas(null)
                    if (canvas != null) {
                        try {
                            if (bitmap != null && !bitmap.isRecycled) {
                                val src = Rect(0, 0, bitmap.width, bitmap.height)
                                val dst = Rect(0, 0, canvas.width, canvas.height)
                                canvas.drawBitmap(bitmap, src, dst, paint)
                            } else {
                                ZestoRemoteFrameSource.renderStandbyTestPattern(canvas, cycleCount, frameResult.healthState)
                            }
                        } finally {
                            surface.unlockCanvasAndPost(canvas)
                            val count = substitutedFramesCount.incrementAndGet()
                            if (count == 1L || count % 60L == 0L) {
                                val logMsg = "Target preview surface received and displayed frame #$count"
                                Log.i(TAG, "[TARGET_PREVIEW_RECEIVED_FRAME] $logMsg")
                                ZestoRemoteFrameSource.reportMilestone("TARGET_PREVIEW_RECEIVED_FRAME", logMsg)
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
        }
    }

    fun stopFramePump() {
        isPumping.set(false)
        pumpTask?.cancel(true)
        pumpTask = null
    }
}
