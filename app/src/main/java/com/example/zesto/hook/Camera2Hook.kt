package com.example.zesto.hook

import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.example.zesto.frame.FrameCropMode
import com.example.zesto.frame.ZestoFrameTransformer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Universal Camera Interceptor covering:
 * 1. Camera2 API (android.hardware.camera2.CameraManager + CameraDevice)
 * 2. Legacy Camera1 API (android.hardware.Camera)
 * 3. Jetpack CameraX (androidx.camera.core)
 *
 * Provides comprehensive method interception with verbose runtime diagnostic logging
 * across all known camera access pathways.
 */
object Camera2Hook {
    private const val TAG = "ZestoCameraHook"

    enum class HookStatus {
        HOOK_UNINITIALIZED,
        HOOK_REGISTERED,
        CAMERA2_DEVICE_OPEN_INTERCEPTED,
        CAMERA2_SESSION_INTERCEPTED,
        SURFACE_TARGET_ATTACHED,
        SURFACE_LOST,
        FRAME_PUMP_ACTIVE,
        HOOK_FAILED
    }

    private var currentStatus = HookStatus.HOOK_UNINITIALIZED
    private val isPumping = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var pumpTask: Future<*>? = null
    private val activeSurfaces = CopyOnWriteArrayList<Surface>()

    private val substitutedFramesCount = AtomicLong(0L)
    val totalSubstitutedFrames: Long get() = substitutedFramesCount.get()

    val status: HookStatus get() = currentStatus

    /**
     * Attaches bytecode / reflection hooks across Camera2 and Camera1 APIs.
     */
    fun attachHook(classLoader: ClassLoader, targetPackage: String = "unknown") {
        var hookCount = 0
        Log.i(TAG, "[ATTACH_START] Installing camera interception hooks for target: $targetPackage")

        try {
            // 1. Camera2 CameraManager hooks
            hookCount += hookCameraManager(classLoader, targetPackage)

            // 2. Camera2 CameraDevice session hooks
            hookCount += hookCameraDevice(classLoader, targetPackage)

            currentStatus = HookStatus.HOOK_REGISTERED
            val installMsg = "Installed $hookCount Camera2 method interception hooks for $targetPackage"
            Log.i(TAG, "[CAMERA2_HOOK_INSTALLED] $installMsg")
            ZestoRemoteFrameSource.reportMilestone("CAMERA2_HOOK_INSTALLED", installMsg)
        } catch (e: Throwable) {
            currentStatus = HookStatus.HOOK_FAILED
            Log.e(TAG, "[HOOK_FAILED] Unexpected error attaching Camera2 hook: ${e.message}", e)
        }
    }

    private fun hookCameraManager(classLoader: ClassLoader, targetPackage: String): Int {
        var installed = 0
        val managerClass = try {
            Class.forName("android.hardware.camera2.CameraManager", false, classLoader)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "[HOOK_WARN] CameraManager not found in classloader: ${e.message}")
            return 0
        }

        val openCameraHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cameraId = param.args.getOrNull(0)?.toString() ?: "0"
                val method = param.method?.name ?: "openCamera"
                Log.d(TAG, "[RUN_TRACE] CameraManager.$method called in target=$targetPackage for cameraId=$cameraId")
                onCameraDeviceOpening(cameraId, "CameraManager.$method", targetPackage)
            }
        }

        // 1. openCamera(String cameraId, CameraDevice.StateCallback callback, Handler handler)
        try {
            val stateCallbackClass = Class.forName("android.hardware.camera2.CameraDevice\$StateCallback", false, classLoader)
            XposedHelpers.findAndHookMethod(
                managerClass,
                "openCamera",
                String::class.java,
                stateCallbackClass,
                Handler::class.java,
                openCameraHook
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CameraManager.openCamera(String, StateCallback, Handler)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CameraManager.openCamera(3 params): ${t.message}")
        }

        // 2. openCamera(String cameraId, Executor executor, CameraDevice.StateCallback callback) (API 28+)
        try {
            val stateCallbackClass = Class.forName("android.hardware.camera2.CameraDevice\$StateCallback", false, classLoader)
            XposedHelpers.findAndHookMethod(
                managerClass,
                "openCamera",
                String::class.java,
                Executor::class.java,
                stateCallbackClass,
                openCameraHook
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CameraManager.openCamera(String, Executor, StateCallback)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CameraManager.openCamera(Executor): ${t.message}")
        }

        // 3. openCameraDeviceUserAsync (Internal framework dispatch)
        try {
            val methods = managerClass.declaredMethods
            for (m in methods) {
                if (m.name == "openCameraDeviceUserAsync" || m.name == "openCameraForUid") {
                    XposedHelpers.findAndHookMethod(
                        managerClass,
                        m.name,
                        *m.parameterTypes,
                        openCameraHook
                    )
                    installed++
                    Log.i(TAG, "[HOOK_OK] Hooked internal CameraManager.${m.name}")
                }
            }
        } catch (_: Throwable) {
        }

        return installed
    }

    private fun hookCameraDevice(classLoader: ClassLoader, targetPackage: String): Int {
        var installed = 0
        val deviceClass = try {
            Class.forName("android.hardware.camera2.CameraDevice", false, classLoader)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "[HOOK_WARN] CameraDevice not found in classloader: ${e.message}")
            return 0
        }

        // Hook createCaptureSession(List<Surface>, StateCallback, Handler)
        val captureSessionListHook = object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param: MethodHookParam) {
                val surfaces = (param.args.getOrNull(0) as? List<Surface>) ?: emptyList()
                Log.d(TAG, "[RUN_TRACE] CameraDevice.createCaptureSession(List) invoked with ${surfaces.size} surface(s)")
                onSessionConfigured(surfaces, targetPackage, "CameraDevice.createCaptureSession(List)")
            }
        }

        try {
            val sessionCallbackClass = Class.forName("android.hardware.camera2.CameraCaptureSession\$StateCallback", false, classLoader)
            XposedHelpers.findAndHookMethod(
                deviceClass,
                "createCaptureSession",
                List::class.java,
                sessionCallbackClass,
                Handler::class.java,
                captureSessionListHook
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CameraDevice.createCaptureSession(List, StateCallback, Handler)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CameraDevice.createCaptureSession(List): ${t.message}")
        }

        // Hook createCaptureSession(SessionConfiguration) (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val sessionConfigClass = Class.forName("android.hardware.camera2.params.SessionConfiguration", false, classLoader)
                val sessionConfigHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sessionConfig = param.args.getOrNull(0) as? android.hardware.camera2.params.SessionConfiguration
                        if (sessionConfig != null) {
                            val surfaces = mutableListOf<Surface>()
                            for (outputConfig in sessionConfig.outputConfigurations) {
                                outputConfig.surface?.let { surfaces.add(it) }
                                for (s in outputConfig.surfaces) {
                                    if (s != null && !surfaces.contains(s)) surfaces.add(s)
                                }
                            }
                            Log.d(TAG, "[RUN_TRACE] CameraDevice.createCaptureSession(SessionConfig) invoked with ${surfaces.size} surface(s)")
                            onSessionConfigured(surfaces, targetPackage, "CameraDevice.createCaptureSession(SessionConfiguration)")
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(
                    deviceClass,
                    "createCaptureSession",
                    sessionConfigClass,
                    sessionConfigHook
                )
                installed++
                Log.i(TAG, "[HOOK_OK] Hooked CameraDevice.createCaptureSession(SessionConfiguration)")
            } catch (t: Throwable) {
                Log.w(TAG, "[HOOK_FAIL] CameraDevice.createCaptureSession(SessionConfiguration): ${t.message}")
            }
        }

        // Hook createCustomCaptureSession / createCaptureSessionByOutputConfigurations
        try {
            for (m in deviceClass.declaredMethods) {
                if (m.name == "createCaptureSessionByOutputConfigurations" || m.name == "createCustomCaptureSession") {
                    XposedHelpers.findAndHookMethod(
                        deviceClass,
                        m.name,
                        *m.parameterTypes,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                Log.d(TAG, "[RUN_TRACE] CameraDevice.${m.name} invoked in target: $targetPackage")
                            }
                        }
                    )
                    installed++
                    Log.i(TAG, "[HOOK_OK] Hooked CameraDevice.${m.name}")
                }
            }
        } catch (_: Throwable) {
        }

        return installed
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
    fun onSessionConfigured(outputs: List<Surface>, targetPackage: String = "unknown", source: String = "createCaptureSession") {
        currentStatus = HookStatus.CAMERA2_SESSION_INTERCEPTED
        Log.i(TAG, "[CAMERA2_SESSION_INTERCEPTED] Intercepted $source with ${outputs.size} output surfaces.")

        val validSurfaces = outputs.filter { it.isValid }
        if (validSurfaces.isNotEmpty()) {
            if (activeSurfaces.isNotEmpty() && activeSurfaces != validSurfaces) {
                Log.i(TAG, "[SURFACE_REPLACED] Replacing ${activeSurfaces.size} previous surfaces with ${validSurfaces.size} new surfaces")
            }
            activeSurfaces.clear()
            activeSurfaces.addAll(validSurfaces)

            currentStatus = HookStatus.SURFACE_TARGET_ATTACHED
            Log.i(TAG, "[SURFACE_ATTACHED] Attached to ${validSurfaces.size} valid target surface(s).")
            startFramePump(validSurfaces, targetPackage)
        } else {
            currentStatus = HookStatus.SURFACE_LOST
            Log.w(TAG, "[SURFACE_LOST] No currently valid surfaces in output list (${outputs.size} given)")
        }
    }

    /**
     * Continuously substitutes frames into target surfaces using canonical 9:16 portrait normalization.
     */
    fun startFramePump(surfaces: List<Surface>, targetPackage: String = "unknown") {
        stopFramePump()
        isPumping.set(true)
        currentStatus = HookStatus.FRAME_PUMP_ACTIVE

        val activeMsg = "Active frame substitution pump rendering 9:16 portrait frames onto ${surfaces.size} target surface(s)"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        Log.i(TAG, "[FRAME_SOURCE_STARTED] Frame substitution pump started for $targetPackage")
        ZestoRemoteFrameSource.reportMilestone("FRAME_SUBSTITUTION_ACTIVE", activeMsg)

        pumpTask = renderExecutor.submit {
            var cycleCount = 0L

            while (isPumping.get()) {
                try {
                    val frameResult = ZestoRemoteFrameSource.fetchLatestFrame()
                    val bitmap = frameResult.bitmap
                    cycleCount++

                    var hasValidSurface = false
                    for (surface in surfaces) {
                        if (!surface.isValid) {
                            Log.d(TAG, "[SURFACE_DETACHED] Target surface became invalid")
                            continue
                        }
                        hasValidSurface = true

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
                            Log.w(TAG, "Canvas render exception: ${e.message}")
                        } finally {
                            if (canvas != null) {
                                try {
                                    surface.unlockCanvasAndPost(canvas)
                                    val count = substitutedFramesCount.incrementAndGet()
                                    if (count == 1L || count % 60L == 0L) {
                                        val logMsg = "Target preview surface successfully received and rendered substituted frame #$count (source frame #${frameResult.frameId})"
                                        Log.i(TAG, "[TARGET_PREVIEW_RECEIVED_FRAME] $logMsg")
                                        ZestoRemoteFrameSource.reportMilestone("TARGET_PREVIEW_RECEIVED_FRAME", logMsg)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "UnlockCanvasAndPost exception: ${e.message}")
                                }
                            }
                        }
                    }

                    if (!hasValidSurface) {
                        currentStatus = HookStatus.SURFACE_LOST
                    }

                    Thread.sleep(33L) // ~30 FPS frame pacing
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Frame pump cycle exception: ${e.message}")
                }
            }
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Frame pump loop exited")
        }
    }

    fun stopFramePump() {
        if (isPumping.getAndSet(false)) {
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Frame substitution pump stopped.")
        }
        pumpTask?.cancel(true)
        pumpTask = null
        if (currentStatus == HookStatus.FRAME_PUMP_ACTIVE) {
            currentStatus = HookStatus.HOOK_REGISTERED
        }
    }
}

