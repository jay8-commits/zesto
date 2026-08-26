package com.example.zesto.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Camera2 API bytecode/reflection hook adapter.
 *
 * Implements:
 * 1. Method Hooking on CameraManager.openCamera and CameraDevice.createCaptureSession
 * 2. In-process direct hook harness for ControlledCameraTestActivity
 * 3. Surface rendering pump injecting decoded frames from ZestoFrameBridge / ZestoFrameContentProvider into target Camera2 surfaces
 * 4. Explicit milestone diagnostic reporting
 */
object Camera2Hook {
    private const val TAG = "ZestoCamera2Hook"

    enum class HookStatus {
        HOOK_UNINITIALIZED,
        HOOK_REGISTERED,
        CAMERA2_DEVICE_OPEN_INTERCEPTED,
        CAMERA2_SESSION_INTERCEPTED,
        SURFACE_TARGET_ATTACHED,
        FRAME_PUMP_ACTIVE,
        HOOK_FAILED
    }

    private var currentStatus = HookStatus.HOOK_UNINITIALIZED
    private val isPumping = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var pumpTask: Future<*>? = null

    private val substitutedFramesCount = AtomicLong(0L)
    val totalSubstitutedFrames: Long get() = substitutedFramesCount.get()

    val status: HookStatus get() = currentStatus

    /**
     * Attaches bytecode / reflection hooks to Camera2 APIs.
     */
    fun attachHook(classLoader: ClassLoader, targetPackage: String = "unknown") {
        try {
            // 1. Hook CameraManager.openCamera
            hookCameraManager(classLoader, targetPackage)

            // 2. Hook CameraDevice.createCaptureSession
            hookCameraDevice(classLoader, targetPackage)

            currentStatus = HookStatus.HOOK_REGISTERED
            Log.i(TAG, "[CAMERA2_HOOK_INSTALLED] Camera2 virtualization hooks installed for package: $targetPackage")
        } catch (e: Throwable) {
            currentStatus = HookStatus.HOOK_FAILED
            Log.e(TAG, "[HOOK_FAILED] Unexpected error attaching Camera2 hook: ${e.message}", e)
        }
    }

    private fun hookCameraManager(classLoader: ClassLoader, targetPackage: String) {
        val managerClass = try {
            Class.forName("android.hardware.camera2.CameraManager", false, classLoader)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "CameraManager not found in classloader: ${e.message}")
            return
        }

        val openCameraHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cameraId = param.args.getOrNull(0)?.toString() ?: "0"
                val method = param.method?.name ?: "openCamera"
                onCameraDeviceOpening(cameraId, method, targetPackage)
            }
        }

        // Hook openCamera(String, CameraDevice.StateCallback, Handler)
        try {
            XposedHelpers.findAndHookMethod(
                managerClass,
                "openCamera",
                String::class.java,
                CameraDevice.StateCallback::class.java,
                Handler::class.java,
                openCameraHook
            )
            Log.d(TAG, "Hooked CameraManager.openCamera(String, StateCallback, Handler)")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hook CameraManager.openCamera(3 params): ${t.message}")
        }

        // Hook openCamera(String, Executor, CameraDevice.StateCallback) (API 28+)
        try {
            XposedHelpers.findAndHookMethod(
                managerClass,
                "openCamera",
                String::class.java,
                Executor::class.java,
                CameraDevice.StateCallback::class.java,
                openCameraHook
            )
            Log.d(TAG, "Hooked CameraManager.openCamera(String, Executor, StateCallback)")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hook CameraManager.openCamera(Executor): ${t.message}")
        }

        // Hook internal openCameraForUid if present
        try {
            XposedHelpers.findAndHookMethod(
                managerClass,
                "openCameraForUid",
                String::class.java,
                CameraDevice.StateCallback::class.java,
                Executor::class.java,
                Int::class.javaPrimitiveType,
                openCameraHook
            )
            Log.d(TAG, "Hooked CameraManager.openCameraForUid")
        } catch (_: Throwable) {
        }
    }

    private fun hookCameraDevice(classLoader: ClassLoader, targetPackage: String) {
        val deviceClass = try {
            Class.forName("android.hardware.camera2.CameraDevice", false, classLoader)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "CameraDevice not found in classloader: ${e.message}")
            return
        }

        // Hook createCaptureSession(List<Surface>, StateCallback, Handler)
        val captureSessionListHook = object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param: MethodHookParam) {
                val surfaces = (param.args.getOrNull(0) as? List<Surface>) ?: emptyList()
                onSessionConfigured(surfaces, targetPackage)
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                deviceClass,
                "createCaptureSession",
                List::class.java,
                Class.forName("android.hardware.camera2.CameraCaptureSession\$StateCallback", false, classLoader),
                Handler::class.java,
                captureSessionListHook
            )
            Log.d(TAG, "Hooked CameraDevice.createCaptureSession(List, StateCallback, Handler)")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hook CameraDevice.createCaptureSession(List): ${t.message}")
        }

        // Hook createCaptureSession(SessionConfiguration) (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val sessionConfigClass = Class.forName("android.hardware.camera2.params.SessionConfiguration", false, classLoader)
                val sessionConfigHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sessionConfig = param.args.getOrNull(0) as? SessionConfiguration
                        if (sessionConfig != null) {
                            val surfaces = mutableListOf<Surface>()
                            for (outputConfig in sessionConfig.outputConfigurations) {
                                outputConfig.surface?.let { surfaces.add(it) }
                                for (s in outputConfig.surfaces) {
                                    if (s != null && !surfaces.contains(s)) surfaces.add(s)
                                }
                            }
                            onSessionConfigured(surfaces, targetPackage)
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(
                    deviceClass,
                    "createCaptureSession",
                    sessionConfigClass,
                    sessionConfigHook
                )
                Log.d(TAG, "Hooked CameraDevice.createCaptureSession(SessionConfiguration)")
            } catch (t: Throwable) {
                Log.w(TAG, "Could not hook CameraDevice.createCaptureSession(SessionConfiguration): ${t.message}")
            }
        }
    }

    /**
     * Triggered when target process opens a CameraDevice.
     */
    fun onCameraDeviceOpening(cameraId: String, methodPath: String = "openCamera", targetPackage: String = "unknown") {
        currentStatus = HookStatus.CAMERA2_DEVICE_OPEN_INTERCEPTED
        val msg = "Target process ($targetPackage) requested Camera2 device: cameraId=$cameraId via $methodPath"
        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
    }

    /**
     * Called when a target CameraCaptureSession is created.
     * Hooks target surfaces and starts the live frame substitution pump.
     */
    fun onSessionConfigured(outputs: List<Surface>, targetPackage: String = "unknown") {
        currentStatus = HookStatus.CAMERA2_SESSION_INTERCEPTED
        Log.i(TAG, "[CAMERA2_SESSION_INTERCEPTED] Intercepted CameraCaptureSession with ${outputs.size} output surfaces.")

        val validSurfaces = outputs.filter { it.isValid }
        if (validSurfaces.isNotEmpty()) {
            currentStatus = HookStatus.SURFACE_TARGET_ATTACHED
            Log.i(TAG, "[SURFACE_TARGET_ATTACHED] Attached to ${validSurfaces.size} valid target surfaces.")
            startFramePump(validSurfaces, targetPackage)
        }
    }

    /**
     * Continuously substitutes frames into target surfaces.
     */
    fun startFramePump(surfaces: List<Surface>, targetPackage: String = "unknown") {
        stopFramePump()
        isPumping.set(true)
        currentStatus = HookStatus.FRAME_PUMP_ACTIVE

        val activeMsg = "Active frame substitution pump rendering OBS frames onto ${surfaces.size} target surface(s)"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        ZestoRemoteFrameSource.reportMilestone("FRAME_SUBSTITUTION_ACTIVE", activeMsg)

        pumpTask = renderExecutor.submit {
            val paint = Paint().apply { isFilterBitmap = true }
            var cycleCount = 0L

            while (isPumping.get()) {
                try {
                    val frameResult = ZestoRemoteFrameSource.fetchLatestFrame()
                    val bitmap = frameResult.bitmap
                    cycleCount++

                    for (surface in surfaces) {
                        if (!surface.isValid) continue
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
                                    val logMsg = "Target preview surface successfully received and rendered substituted frame #$count (source frame #${frameResult.frameId})"
                                    Log.i(TAG, "[TARGET_PREVIEW_RECEIVED_FRAME] $logMsg")
                                    ZestoRemoteFrameSource.reportMilestone("TARGET_PREVIEW_RECEIVED_FRAME", logMsg)
                                }
                            }
                        }
                    }
                    Thread.sleep(33L) // ~30 FPS frame pacing
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Frame pump cycle exception: ${e.message}")
                }
            }
        }
    }

    fun stopFramePump() {
        if (isPumping.getAndSet(false)) {
            Log.i(TAG, "Frame substitution pump stopped.")
        }
        pumpTask?.cancel(true)
        pumpTask = null
        if (currentStatus == HookStatus.FRAME_PUMP_ACTIVE) {
            currentStatus = HookStatus.HOOK_REGISTERED
        }
    }
}
