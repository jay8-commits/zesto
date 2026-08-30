package com.example.zesto.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.example.zesto.frame.FrameCropMode
import com.example.zesto.frame.ZestoFrameTransformer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Universal Camera Interceptor & Hardware Producer Decoupling Engine.
 *
 * Prevents Camera HAL buffer collisions by redirecting the hardware camera stream
 * to a dummy sink surface while exclusively rendering Zesto's 30 FPS virtual frames
 * into the target application's visible preview surface.
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

    enum class TargetCameraLifecycle {
        TARGET_CAMERA_DISCONNECTED,
        TARGET_CAMERA_OPENING,
        TARGET_CAMERA_CONFIGURING,
        TARGET_SURFACE_ATTACHED,
        TARGET_INJECTION_SYNCHRONIZED,
        TARGET_INJECTION_STREAMING,
        TARGET_CAMERA_CLOSING
    }

    private var currentStatus = HookStatus.HOOK_UNINITIALIZED
    private var targetLifecycleState = TargetCameraLifecycle.TARGET_CAMERA_DISCONNECTED
    private val isPumping = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var pumpTask: Future<*>? = null
    private val activeSurfaces = CopyOnWriteArrayList<Surface>()

    fun setTargetLifecycleState(state: TargetCameraLifecycle, targetPackage: String = "unknown") {
        targetLifecycleState = state
        Log.i(TAG, "[TARGET_LIFECYCLE_STATE] state=$state package=$targetPackage")
    }

    // Dedicated dummy sink to isolate the hardware camera stream
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    private var activeCameraId: String = "0"
    private var isPhysicalCameraBypassed: Boolean = false
    private var isInjectionConfirmed: Boolean = false

    private val substitutedFramesCount = AtomicLong(0L)
    val totalSubstitutedFrames: Long get() = substitutedFramesCount.get()

    val status: HookStatus get() = currentStatus

    @Synchronized
    fun getOrCreateDummySurface(): Surface {
        val existing = dummySurface
        if (existing != null && existing.isValid) {
            return existing
        }
        val st = SurfaceTexture(1001).apply {
            setDefaultBufferSize(640, 480)
        }
        dummySurfaceTexture = st
        val s = Surface(st)
        dummySurface = s
        Log.i(TAG, "[DUMMY_SINK_INITIALIZED] Created hardware camera isolation sink Surface@${System.identityHashCode(s).toString(16)}")
        return s
    }

    /**
     * Attaches bytecode / reflection hooks across Camera2 APIs.
     */
    fun attachHook(classLoader: ClassLoader, targetPackage: String = "unknown") {
        var hookCount = 0
        Log.i(TAG, "[HOOK_INVOKED]\npackage=$targetPackage\napi=Camera2")
        Log.i(TAG, "[ATTACH_START] Installing camera interception & substitution hooks for target: $targetPackage")

        try {
            // 1. Camera2 CameraManager hooks
            hookCount += hookCameraManager(classLoader, targetPackage)

            // 2. Camera2 CameraDevice session hooks (with hardware redirection)
            hookCount += hookCameraDevice(classLoader, targetPackage)

            // 3. Camera2 CaptureRequest and CaptureSession repeating request hooks
            hookCount += hookCameraSessionRequests(classLoader, targetPackage)

            currentStatus = HookStatus.HOOK_REGISTERED
            val installMsg = "Installed $hookCount Camera2 method interception & substitution hooks for $targetPackage"
            Log.i(TAG, "[CAMERA2_HOOK_INSTALLED] package=$targetPackage $installMsg")
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
                activeCameraId = cameraId
                val method = param.method?.name ?: "openCamera"
                Log.i(TAG, "[HOOK_INVOKED]\npackage=$targetPackage\napi=Camera2")
                Log.i(TAG, "[CAMERA_DEVICE_OPEN]\npackage=$targetPackage\ncameraId=$cameraId")
                Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CameraManager.$method(cameraId=$cameraId)")
                try {
                    val mContext = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                    if (mContext != null) {
                        ZestoRemoteFrameSource.setTargetContext(mContext)
                    }
                } catch (_: Throwable) {}
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
                val dummy = getOrCreateDummySurface()
                val dummyHash = System.identityHashCode(dummy).toString(16)
                isPhysicalCameraBypassed = true

                Log.i(TAG, "[HOOK_INVOKED]\npackage=$targetPackage\napi=Camera2")
                Log.i(TAG, "[CAPTURE_SESSION_CREATE]\npackage=$targetPackage\nsurfaceCount=${surfaces.size}\nsurface=${surfaces.joinToString { "Surface@" + System.identityHashCode(it).toString(16) }}")
                Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CameraDevice.createCaptureSession(List<Surface>)")

                for (s in surfaces) {
                    val hash = System.identityHashCode(s).toString(16)
                    Log.i(TAG, "[TARGET_PREVIEW_SURFACE]\nidentity=Surface@$hash\nwidth=1080\nheight=1920\nformat=PRIVATE")
                    Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=Camera2\nCLASS=${s.javaClass.name}\nSURFACE_ID=@$hash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${s.isValid}\nSURFACE_TEXTURE=null")
                    Log.i(TAG, "[SURFACE_SESSION_OUTPUT] hash=@$hash valid=${s.isValid} in $targetPackage")
                }

                Log.i(TAG, "[PHYSICAL_CAMERA_OUTPUT]\ncameraId=$activeCameraId\nsurface=Surface@$dummyHash (REDIRECTED_TO_DUMMY)")

                // Redirect hardware camera session to dummy sink so physical camera HAL never touches Instagram preview surfaces
                param.args[0] = listOf(dummy)

                // Wrap the StateCallback to trace configuration success/failure and start frame delivery onConfigured
                val originalCallback = param.args.getOrNull(1) as? android.hardware.camera2.CameraCaptureSession.StateCallback
                if (originalCallback != null) {
                    param.args[1] = object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            Log.i(TAG, "[PREVIEW_SESSION_CONFIGURED] CameraCaptureSession configured successfully for $targetPackage (physical HAL redirected to dummy sink)")
                            try {
                                originalCallback.onConfigured(session)
                            } finally {
                                onSessionConfigured(surfaces, targetPackage, "CameraDevice.createCaptureSession(List)")
                            }
                        }

                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            Log.e(TAG, "[PREVIEW_SESSION_FAILED] CameraCaptureSession configuration failed for $targetPackage")
                            originalCallback.onConfigureFailed(session)
                        }

                        override fun onClosed(session: android.hardware.camera2.CameraCaptureSession) {
                            stopFramePump()
                            originalCallback.onClosed(session)
                        }

                        override fun onReady(session: android.hardware.camera2.CameraCaptureSession) {
                            originalCallback.onReady(session)
                        }

                        override fun onActive(session: android.hardware.camera2.CameraCaptureSession) {
                            originalCallback.onActive(session)
                        }

                        override fun onCaptureQueueEmpty(session: android.hardware.camera2.CameraCaptureSession) {
                            originalCallback.onCaptureQueueEmpty(session)
                        }

                        override fun onSurfacePrepared(session: android.hardware.camera2.CameraCaptureSession, surface: Surface) {
                            originalCallback.onSurfacePrepared(session, surface)
                        }
                    }
                } else {
                    onSessionConfigured(surfaces, targetPackage, "CameraDevice.createCaptureSession(List)")
                }
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
                            val dummy = getOrCreateDummySurface()
                            val dummyHash = System.identityHashCode(dummy).toString(16)
                            isPhysicalCameraBypassed = true

                            Log.i(TAG, "[HOOK_INVOKED]\npackage=$targetPackage\napi=Camera2")
                            Log.i(TAG, "[CAPTURE_SESSION_CREATE]\npackage=$targetPackage\nsurfaceCount=${surfaces.size}\nsurface=${surfaces.joinToString { "Surface@" + System.identityHashCode(it).toString(16) }}")
                            Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CameraDevice.createCaptureSession(SessionConfiguration)")

                            for (s in surfaces) {
                                val hash = System.identityHashCode(s).toString(16)
                                Log.i(TAG, "[TARGET_PREVIEW_SURFACE]\nidentity=Surface@$hash\nwidth=1080\nheight=1920\nformat=PRIVATE")
                                Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=Camera2\nCLASS=${s.javaClass.name}\nSURFACE_ID=@$hash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${s.isValid}\nSURFACE_TEXTURE=null")
                                Log.i(TAG, "[SURFACE_SESSION_OUTPUT] hash=@$hash valid=${s.isValid} in $targetPackage")
                            }

                            Log.i(TAG, "[PHYSICAL_CAMERA_OUTPUT]\ncameraId=$activeCameraId\nsurface=Surface@$dummyHash (REDIRECTED_TO_DUMMY)")

                            // Wrap StateCallback and replace output configuration with dummy sink
                            val dummyOutputConfig = android.hardware.camera2.params.OutputConfiguration(dummy)
                            val origCallback = sessionConfig.stateCallback
                            val wrappedCallback = object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                    Log.i(TAG, "[PREVIEW_SESSION_CONFIGURED] CameraCaptureSession (SessionConfiguration) configured with dummy sink for $targetPackage")
                                    try {
                                        origCallback.onConfigured(session)
                                    } finally {
                                        onSessionConfigured(surfaces, targetPackage, "CameraDevice.createCaptureSession(SessionConfiguration)")
                                    }
                                }

                                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                    Log.e(TAG, "[PREVIEW_SESSION_FAILED] CameraCaptureSession (SessionConfiguration) configuration failed for $targetPackage")
                                    origCallback.onConfigureFailed(session)
                                }

                                override fun onClosed(session: android.hardware.camera2.CameraCaptureSession) {
                                    stopFramePump()
                                    origCallback.onClosed(session)
                                }

                                override fun onReady(session: android.hardware.camera2.CameraCaptureSession) {
                                    origCallback.onReady(session)
                                }

                                override fun onActive(session: android.hardware.camera2.CameraCaptureSession) {
                                    origCallback.onActive(session)
                                }

                                override fun onCaptureQueueEmpty(session: android.hardware.camera2.CameraCaptureSession) {
                                    origCallback.onCaptureQueueEmpty(session)
                                }

                                override fun onSurfacePrepared(session: android.hardware.camera2.CameraCaptureSession, surface: Surface) {
                                    origCallback.onSurfacePrepared(session, surface)
                                }
                            }

                            try {
                                val newSessionConfig = android.hardware.camera2.params.SessionConfiguration(
                                    sessionConfig.sessionType,
                                    listOf(dummyOutputConfig),
                                    sessionConfig.executor,
                                    wrappedCallback
                                )
                                param.args[0] = newSessionConfig
                            } catch (e: Throwable) {
                                Log.w(TAG, "[SESSION_CONFIG_WRAP_WARN] Could not wrap SessionConfiguration callback: ${e.message}")
                                onSessionConfigured(surfaces, targetPackage, "CameraDevice.createCaptureSession(SessionConfiguration)")
                            }
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

        // Hook CameraDevice.close()
        try {
            XposedHelpers.findAndHookMethod(
                deviceClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CameraDevice.close()")
                        setTargetLifecycleState(TargetCameraLifecycle.TARGET_CAMERA_CLOSING, targetPackage)
                        stopFramePump()
                        setTargetLifecycleState(TargetCameraLifecycle.TARGET_CAMERA_DISCONNECTED, targetPackage)
                    }
                }
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CameraDevice.close()")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CameraDevice.close(): ${t.message}")
        }

        return installed
    }

    private fun hookCameraSessionRequests(classLoader: ClassLoader, targetPackage: String): Int {
        var installed = 0

        // 1. Hook CameraDevice.createCaptureRequest(int templateType)
        try {
            val deviceClass = Class.forName("android.hardware.camera2.CameraDevice", false, classLoader)
            XposedHelpers.findAndHookMethod(
                deviceClass,
                "createCaptureRequest",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val template = param.args.getOrNull(0) as? Int ?: -1
                        Log.d(TAG, "[CAPTURE_REQUEST_CREATED] CameraDevice.createCaptureRequest(template=$template) in $targetPackage")
                    }
                }
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CameraDevice.createCaptureRequest(int)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CameraDevice.createCaptureRequest: ${t.message}")
        }

        // 2. Hook CaptureRequest.Builder.addTarget(Surface)
        try {
            val builderClass = Class.forName("android.hardware.camera2.CaptureRequest\$Builder", false, classLoader)
            XposedHelpers.findAndHookMethod(
                builderClass,
                "addTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val surface = param.args.getOrNull(0) as? Surface
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CaptureRequest.Builder.addTarget(Surface)")
                        if (surface != null) {
                            val hash = System.identityHashCode(surface).toString(16)
                            Log.i(TAG, "[CAPTURE_REQUEST]\ncameraId=$activeCameraId\ntargetSurfaceCount=1")
                            Log.i(TAG, "[TARGET_PREVIEW_SURFACE]\nidentity=Surface@$hash\nwidth=1080\nheight=1920\nformat=PRIVATE")
                            Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=Camera2\nCLASS=${surface.javaClass.name}\nSURFACE_ID=@$hash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${surface.isValid}\nSURFACE_TEXTURE=null")
                            Log.i(TAG, "[SURFACE_CAPTURE_REQUEST_TARGET] Target Surface added to CaptureRequest: hash=@$hash valid=${surface.isValid} in $targetPackage")

                            // Redirect repeating capture target to dummy sink so Camera HAL writes to dummy sink
                            val dummy = getOrCreateDummySurface()
                            val dummyHash = System.identityHashCode(dummy).toString(16)
                            Log.i(TAG, "[PHYSICAL_CAMERA_OUTPUT]\ncameraId=$activeCameraId\nsurface=Surface@$dummyHash (REDIRECTED_TO_DUMMY)")
                            param.args[0] = dummy
                        }
                    }
                }
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CaptureRequest.Builder.addTarget(Surface)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CaptureRequest.Builder.addTarget: ${t.message}")
        }

        // 3. Hook CameraCaptureSession.setRepeatingRequest
        try {
            val sessionClass = Class.forName("android.hardware.camera2.CameraCaptureSession", false, classLoader)
            val captureCallbackClass = Class.forName("android.hardware.camera2.CameraCaptureSession\$CaptureCallback", false, classLoader)

            XposedHelpers.findAndHookMethod(
                sessionClass,
                "setRepeatingRequest",
                Class.forName("android.hardware.camera2.CaptureRequest", false, classLoader),
                captureCallbackClass,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CameraCaptureSession.setRepeatingRequest")
                        Log.i(TAG, "[REPEATING_REQUEST_STARTED] CameraCaptureSession.setRepeatingRequest invoked in $targetPackage")
                        if (!isPumping.get() && activeSurfaces.isNotEmpty()) {
                            startFramePump(activeSurfaces, targetPackage)
                        }
                    }
                }
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CameraCaptureSession.setRepeatingRequest(Request, Callback, Handler)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CameraCaptureSession.setRepeatingRequest: ${t.message}")
        }

        // 4. Hook CameraCaptureSession.capture
        try {
            val sessionClass = Class.forName("android.hardware.camera2.CameraCaptureSession", false, classLoader)
            val captureCallbackClass = Class.forName("android.hardware.camera2.CameraCaptureSession\$CaptureCallback", false, classLoader)

            XposedHelpers.findAndHookMethod(
                sessionClass,
                "capture",
                Class.forName("android.hardware.camera2.CaptureRequest", false, classLoader),
                captureCallbackClass,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CameraCaptureSession.capture")
                        Log.i(TAG, "[CAPTURE_REQUEST_INVOKED] CameraCaptureSession.capture invoked in $targetPackage")
                    }
                }
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CameraCaptureSession.capture(Request, Callback, Handler)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CameraCaptureSession.capture: ${t.message}")
        }

        return installed
    }

    /**
     * Triggered when target process opens a CameraDevice.
     */
    fun onCameraDeviceOpening(cameraId: String, methodPath: String = "openCamera", targetPackage: String = "unknown") {
        activeCameraId = cameraId
        if (targetPackage != "unknown") {
            ZestoRemoteFrameSource.setAttachedPackage(targetPackage)
        }
        setTargetLifecycleState(TargetCameraLifecycle.TARGET_CAMERA_OPENING, targetPackage)
        currentStatus = HookStatus.CAMERA2_DEVICE_OPEN_INTERCEPTED
        val msg = "Target process ($targetPackage) requested Camera2 device: cameraId=$cameraId via $methodPath"
        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
    }

    /**
     * Called when a target CameraCaptureSession is created.
     * Attaches isolated preview surfaces and starts the live frame substitution pump.
     */
    fun onSessionConfigured(outputs: List<Surface>, targetPackage: String = "unknown", source: String = "createCaptureSession") {
        if (targetPackage != "unknown") {
            ZestoRemoteFrameSource.setAttachedPackage(targetPackage)
        }
        setTargetLifecycleState(TargetCameraLifecycle.TARGET_CAMERA_CONFIGURING, targetPackage)
        currentStatus = HookStatus.CAMERA2_SESSION_INTERCEPTED
        Log.i(TAG, "[CAMERA2_SESSION_INTERCEPTED] Intercepted $source with ${outputs.size} output surfaces.")

        val validSurfaces = outputs.filter { it.isValid }
        if (validSurfaces.isNotEmpty()) {
            if (activeSurfaces.isNotEmpty() && activeSurfaces != validSurfaces) {
                Log.i(TAG, "[SURFACE_REPLACED] Replacing ${activeSurfaces.size} previous surfaces with ${validSurfaces.size} new surfaces")
            }
            activeSurfaces.clear()
            activeSurfaces.addAll(validSurfaces)

            setTargetLifecycleState(TargetCameraLifecycle.TARGET_SURFACE_ATTACHED, targetPackage)
            currentStatus = HookStatus.SURFACE_TARGET_ATTACHED
            for (s in validSurfaces) {
                val hash = System.identityHashCode(s).toString(16)
                Log.i(TAG, "[CAM2_HOOK_SURFACE] surface=@$hash valid=${s.isValid} state=$targetLifecycleState")
            }
            Log.i(TAG, "[SURFACE_ATTACHED] Attached to ${validSurfaces.size} isolated target surface(s).")
            startFramePump(validSurfaces, targetPackage)
        } else if (outputs.isNotEmpty()) {
            currentStatus = HookStatus.SURFACE_LOST
            Log.w(TAG, "[SURFACE_LOST] No currently valid surfaces in output list (${outputs.size} given)")
        } else {
            currentStatus = HookStatus.CAMERA2_SESSION_INTERCEPTED
            Log.i(TAG, "[CAMERA2_SESSION_INTERCEPTED] Session created with 0 initial surfaces.")
        }
    }

    /**
     * Continuously renders 30 FPS virtual frames onto the isolated preview surface.
     */
    fun startFramePump(surfaces: List<Surface>, targetPackage: String = "unknown") {
        stopFramePump()
        isPumping.set(true)
        setTargetLifecycleState(TargetCameraLifecycle.TARGET_INJECTION_SYNCHRONIZED, targetPackage)
        currentStatus = HookStatus.FRAME_PUMP_ACTIVE

        val activeMsg = "Active frame substitution pump rendering 30 FPS frames onto ${surfaces.size} isolated target surface(s)"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        Log.i(TAG, "[FRAME_SOURCE_STARTED] Frame substitution pump started for $targetPackage")
        ZestoRemoteFrameSource.reportMilestone("FRAME_SUBSTITUTION_ACTIVE", activeMsg)

        pumpTask = renderExecutor.submit {
            var cycleCount = 0L
            var lastFpsCalcMs = System.currentTimeMillis()
            var framesInSecond = 0L
            var currentTargetFps = 29.8
            var lastRenderedFrameId = 0L
            var lastRenderedSeq = 0L
            var lastStandbyRenderMs = 0L

            while (isPumping.get()) {
                try {
                    val frameResult = ZestoRemoteFrameSource.fetchLatestFrame()
                    val bitmap = frameResult.bitmap
                    cycleCount++

                    val now = System.currentTimeMillis()
                    val hasNewFrame = frameResult.isNewFrame && frameResult.frameId > 0L &&
                            (frameResult.frameId > lastRenderedFrameId || frameResult.sequence > lastRenderedSeq) &&
                            bitmap != null && !bitmap.isRecycled

                    val needsStandbyRender = (bitmap == null || frameResult.healthState == "AWAITING_ZESTO_PROVIDER") &&
                            (now - lastStandbyRenderMs > 1000L)

                    if (!hasNewFrame && !needsStandbyRender) {
                        // Stale frame or no change - sleep briefly to prevent busy thrashing and avoid duplicate rendering
                        Thread.sleep(10L)
                        continue
                    }

                    if (hasNewFrame) {
                        lastRenderedFrameId = frameResult.frameId
                        lastRenderedSeq = frameResult.sequence
                        framesInSecond++
                    } else if (needsStandbyRender) {
                        lastStandbyRenderMs = now
                    }

                    if (now - lastFpsCalcMs >= 1000L) {
                        val elapsed = now - lastFpsCalcMs
                        currentTargetFps = (framesInSecond * 1000.0) / elapsed.toDouble()
                        framesInSecond = 0L
                        lastFpsCalcMs = now
                    }

                    var hasValidSurface = false
                    for (surface in surfaces) {
                        if (!surface.isValid) {
                            Log.d(TAG, "[SURFACE_DETACHED] Target surface became invalid")
                            continue
                        }
                        hasValidSurface = true

                        var canvas: Canvas? = null
                        val hash = System.identityHashCode(surface).toString(16)
                        val frameId = if (frameResult.frameId > 0) frameResult.frameId else cycleCount
                        try {
                            if (hasNewFrame && (lastRenderedFrameId == 1L || lastRenderedFrameId % 60L == 0L)) {
                                Log.i(TAG, "[FRAME_RENDER] frameId=$frameId seq=${frameResult.sequence}")
                                Log.i(TAG, "[FRAME_RENDER_STARTED] id=$frameId hasBitmap=${bitmap != null} isRecycled=${bitmap?.isRecycled ?: true} bmpSize=${bitmap?.width}x${bitmap?.height} health=${frameResult.healthState}")
                                Log.i(TAG, "[FRAME_RENDER_STARTED] Rendering cycle #$cycleCount onto Surface hash=@$hash in $targetPackage")
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
                                    fps = currentTargetFps
                                )
                                if (hasNewFrame && (lastRenderedFrameId == 1L || lastRenderedFrameId % 60L == 0L)) {
                                    Log.i(TAG, "[FRAME_RENDERED_TO_OUTPUT] id=$frameId")
                                    Log.i(TAG, "[FRAME_RENDERED_TO_SURFACE] Render completed on canvas ${canvas.width}x${canvas.height} for target=$targetPackage")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Canvas render exception on surface=@$hash: ${e.message}")
                        } finally {
                            if (canvas != null) {
                                try {
                                    surface.unlockCanvasAndPost(canvas)
                                    if (hasNewFrame) {
                                        val count = substitutedFramesCount.incrementAndGet()

                                        Log.i(TAG, "[TARGET_FRAME_RENDER] seq=${frameResult.sequence} frameId=$frameId surface=@$hash count=$count ts=$now")
                                        Log.i(TAG, "[ZESTO_SUBSTITUTION_OUTPUT]\nsurface=Surface@$hash\nframeId=$frameId\nseq=${frameResult.sequence}")

                                        if (isPhysicalCameraBypassed && surface.isValid) {
                                            if (!isInjectionConfirmed) {
                                                isInjectionConfirmed = true
                                                setTargetLifecycleState(TargetCameraLifecycle.TARGET_INJECTION_STREAMING, targetPackage)
                                                val confMsg = "INJECTION_CONFIRMED: Target preview Surface@$hash receiving Zesto frame #$count (source #$frameId) with physical HAL bypassed"
                                                Log.i(TAG, "[INJECTION_STATE] $confMsg")
                                                ZestoRemoteFrameSource.reportMilestone("INJECTION_CONFIRMED", confMsg)
                                            }
                                        } else {
                                            Log.w(TAG, "[INJECTION_STATE] INJECTION_NOT_CONFIRMED: physicalBypassed=$isPhysicalCameraBypassed surfaceValid=${surface.isValid}")
                                        }

                                        if (count == 1L || count % 60L == 0L) {
                                            Log.i(TAG, "[FRAME_POSTED_TO_SURFACE] frameId=$frameId seq=${frameResult.sequence}")
                                            Log.i(TAG, "[FRAME_POSTED_TO_OUTPUT] id=$frameId")
                                            val logMsg = "Target preview surface successfully received and rendered substituted frame #$count (source frame #${frameResult.frameId}) @ ${String.format(Locale.US, "%.1f", currentTargetFps)} FPS"
                                            Log.i(TAG, "[FRAME_POSTED_TO_SURFACE] $logMsg")
                                            Log.i(TAG, "[FRAME_CONSUMED] $logMsg")
                                            Log.i(TAG, "[TARGET_PREVIEW_RECEIVED_FRAME] $logMsg")
                                            ZestoRemoteFrameSource.reportMilestone("FRAME_CONSUMED", logMsg)
                                            ZestoRemoteFrameSource.reportMilestone("TARGET_PREVIEW_RECEIVED_FRAME", logMsg)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "UnlockCanvasAndPost exception: ${e.message}")
                                }
                            }
                        }
                    }

                    if (!hasValidSurface) {
                        currentStatus = HookStatus.SURFACE_LOST
                        if (isInjectionConfirmed) {
                            isInjectionConfirmed = false
                            Log.w(TAG, "[INJECTION_STATE] INJECTION_NOT_CONFIRMED: Target surface lost or invalidated")
                        }
                    }

                    Thread.sleep(12L) // Smooth frame pacing
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
        isInjectionConfirmed = false
        pumpTask?.cancel(true)
        pumpTask = null
        if (currentStatus == HookStatus.FRAME_PUMP_ACTIVE) {
            currentStatus = HookStatus.HOOK_REGISTERED
        }
    }
}


