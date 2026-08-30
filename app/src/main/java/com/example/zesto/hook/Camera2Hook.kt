package com.example.zesto.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.SurfaceTexture
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
 * Universal Camera2 Interceptor & Hardware Producer Decoupling Engine.
 *
 * Dynamically hooks CameraManager, CameraDevice, CameraDeviceImpl, SessionConfiguration,
 * OutputConfiguration, CaptureRequest.Builder, and CameraCaptureSession across all API overloads.
 * Isolates the hardware camera stream using an offscreen dummy sink while rendering Zesto's
 * live virtual frames into the target application's active preview Surface.
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
    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZestoCamera2RenderThread").apply { isDaemon = true }
    }
    private var pumpTask: Future<*>? = null
    private val activeSurfaces = CopyOnWriteArrayList<Surface>()

    // Dedicated dummy sink to isolate the hardware camera stream
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    private val substitutedFramesCount = AtomicLong(0L)
    val totalSubstitutedFrames: Long get() = substitutedFramesCount.get()

    val status: HookStatus get() = currentStatus

    private val hookedConcreteClasses = mutableSetOf<String>()

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
        Log.i(TAG, "[ATTACH_START] Installing generic Camera2 interception hooks for target: $targetPackage")

        try {
            // 1. Camera2 CameraManager hooks (openCamera, openCameraForUid, openCameraDeviceUserAsync, etc.)
            hookCount += hookCameraManager(classLoader, targetPackage)

            // 2. Camera2 CameraDevice abstract class hooks
            hookCount += hookCameraDeviceClass("android.hardware.camera2.CameraDevice", classLoader, targetPackage)

            // 3. Camera2 CameraDeviceImpl concrete class hooks
            hookCount += hookCameraDeviceClass("android.hardware.camera2.impl.CameraDeviceImpl", classLoader, targetPackage)

            // 4. Camera2 CaptureRequest.Builder and CameraCaptureSession hooks
            hookCount += hookCameraSessionRequests(classLoader, targetPackage)

            currentStatus = HookStatus.HOOK_REGISTERED
            val installMsg = "Installed $hookCount generic Camera2 method interception hooks for $targetPackage"
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
                val method = param.method?.name ?: "openCamera"
                Log.i(TAG, "[CAMERA_API_DETECTED] api=CAMERA2")
                Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=CameraManager.$method(cameraId=$cameraId)")
                try {
                    val mContext = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                    if (mContext != null) {
                        ZestoRemoteFrameSource.setTargetContext(mContext)
                    }
                } catch (_: Throwable) {}
                onCameraDeviceOpening(cameraId, "CameraManager.$method", targetPackage)

                // Wrap StateCallback if present in arguments to capture CameraDevice onOpened
                for (i in param.args.indices) {
                    val arg = param.args[i]
                    if (arg is android.hardware.camera2.CameraDevice.StateCallback) {
                        val origCallback = arg
                        param.args[i] = object : android.hardware.camera2.CameraDevice.StateCallback() {
                            override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                                Log.i(TAG, "[CAMERA2_DEVICE_OPENED] CameraDevice opened: id=${camera.id} class=${camera.javaClass.name} in $targetPackage")
                                hookConcreteCameraDevice(camera.javaClass, targetPackage)
                                origCallback.onOpened(camera)
                            }

                            override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                                Log.i(TAG, "[CAMERA2_DEVICE_DISCONNECTED] CameraDevice disconnected: id=${camera.id} in $targetPackage")
                                stopFramePump()
                                origCallback.onDisconnected(camera)
                            }

                            override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                                Log.w(TAG, "[CAMERA2_DEVICE_ERROR] CameraDevice error: id=${camera.id} error=$error in $targetPackage")
                                origCallback.onError(camera, error)
                            }

                            override fun onClosed(camera: android.hardware.camera2.CameraDevice) {
                                Log.i(TAG, "[CAMERA2_DEVICE_CLOSED] CameraDevice closed: id=${camera.id} in $targetPackage")
                                stopFramePump()
                                origCallback.onClosed(camera)
                            }
                        }
                        break
                    }
                }
            }
        }

        // Hook all declared methods in CameraManager matching open*
        try {
            for (m in managerClass.declaredMethods) {
                if (m.name.startsWith("openCamera")) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            managerClass,
                            m.name,
                            *m.parameterTypes,
                            openCameraHook
                        )
                        installed++
                        Log.i(TAG, "[HOOK_OK] Hooked CameraManager.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    } catch (t: Throwable) {
                        Log.d(TAG, "[HOOK_SKIP] CameraManager.${m.name}: ${t.message}")
                    }
                }
            }
        } catch (_: Throwable) {}

        return installed
    }

    @Synchronized
    fun hookConcreteCameraDevice(clazz: Class<*>, targetPackage: String) {
        val className = clazz.name
        if (hookedConcreteClasses.contains(className)) return
        hookedConcreteClasses.add(className)
        Log.i(TAG, "[HOOK_CONCRETE_DEVICE] Hooking concrete camera device class: $className for $targetPackage")
        hookCameraDeviceClass(className, clazz.classLoader ?: ClassLoader.getSystemClassLoader(), targetPackage)
    }

    private fun hookCameraDeviceClass(className: String, classLoader: ClassLoader, targetPackage: String): Int {
        var installed = 0
        val deviceClass = try {
            Class.forName(className, false, classLoader)
        } catch (_: Throwable) {
            return 0
        }

        // Generic capture session hook for any create*CaptureSession* method
        val genericSessionHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val methodName = param.method?.name ?: "createCaptureSession"
                Log.i(TAG, "[CAMERA2_SESSION_CREATE_INTERCEPTED] target=$targetPackage method=$className.$methodName")
                Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=$className.$methodName")

                val discoveredSurfaces = mutableListOf<Surface>()
                for (arg in param.args) {
                    discoveredSurfaces.addAll(extractSurfaces(arg))
                }

                for (s in discoveredSurfaces) {
                    val hash = System.identityHashCode(s).toString(16)
                    Log.i(TAG, "[CAMERA2_OUTPUT_DISCOVERED] target=$targetPackage surface=@$hash valid=${s.isValid}")
                    Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=Camera2\nCLASS=${s.javaClass.name}\nSURFACE_ID=@$hash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${s.isValid}\nSURFACE_TEXTURE=null")
                }

                // If surfaces discovered, register and start frame pump
                if (discoveredSurfaces.isNotEmpty()) {
                    onSessionConfigured(discoveredSurfaces, targetPackage, "$className.$methodName")
                }

                // Hardware Camera Redirection: substitute or include dummy surface in session configuration
                val dummy = getOrCreateDummySurface()
                for (i in param.args.indices) {
                    val arg = param.args[i]
                    if (arg is List<*>) {
                        try {
                            val modifiedList = mutableListOf<Any>()
                            var hasSurface = false
                            for (item in arg) {
                                if (item is Surface) {
                                    hasSurface = true
                                } else {
                                    if (item != null) modifiedList.add(item)
                                }
                            }
                            if (hasSurface) {
                                modifiedList.add(dummy)
                                param.args[i] = modifiedList
                                Log.i(TAG, "[HARDWARE_STREAM_REDIRECTED] Redirected Camera2 session output list to dummy Surface@${System.identityHashCode(dummy).toString(16)}")
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // Wrap StateCallback if present
                for (i in param.args.indices) {
                    val arg = param.args[i]
                    if (arg is android.hardware.camera2.CameraCaptureSession.StateCallback) {
                        val origCallback = arg
                        param.args[i] = object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                Log.i(TAG, "[CAMERA2_SESSION_CONFIGURED] CameraCaptureSession configured in $targetPackage")
                                try {
                                    origCallback.onConfigured(session)
                                } finally {
                                    if (discoveredSurfaces.isNotEmpty()) {
                                        onSessionConfigured(discoveredSurfaces, targetPackage, "$className.$methodName onConfigured")
                                    }
                                }
                            }

                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                Log.w(TAG, "[CAMERA2_SESSION_FAILED] CameraCaptureSession configuration failed in $targetPackage")
                                origCallback.onConfigureFailed(session)
                            }

                            override fun onClosed(session: android.hardware.camera2.CameraCaptureSession) {
                                Log.i(TAG, "[CAMERA2_SESSION_CLOSED] CameraCaptureSession closed in $targetPackage")
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
                    }
                }
            }
        }

        try {
            for (m in deviceClass.declaredMethods) {
                if (m.name.startsWith("createCaptureSession") ||
                    m.name.startsWith("createCustomCaptureSession") ||
                    m.name.startsWith("createReprocessableCaptureSession") ||
                    m.name.startsWith("createConstrainedHighSpeedCaptureSession")) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            deviceClass,
                            m.name,
                            *m.parameterTypes,
                            genericSessionHook
                        )
                        installed++
                        Log.i(TAG, "[HOOK_OK] Hooked $className.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    } catch (t: Throwable) {
                        Log.d(TAG, "[HOOK_SKIP] $className.${m.name}: ${t.message}")
                    }
                }
            }
        } catch (_: Throwable) {}

        return installed
    }

    private fun extractSurfaces(arg: Any?): List<Surface> {
        val list = mutableListOf<Surface>()
        if (arg == null) return list
        when (arg) {
            is Surface -> {
                if (!list.contains(arg)) list.add(arg)
            }
            is Collection<*> -> {
                for (item in arg) {
                    list.addAll(extractSurfaces(item))
                }
            }
            else -> {
                // Check for OutputConfiguration (reflection / API 24+)
                try {
                    val clazz = arg.javaClass
                    if (clazz.name.contains("OutputConfiguration")) {
                        val getSurfaceMethod = clazz.methods.firstOrNull { it.name == "getSurface" }
                        val surface = getSurfaceMethod?.invoke(arg) as? Surface
                        if (surface != null && !list.contains(surface)) {
                            list.add(surface)
                        }
                        val getSurfacesMethod = clazz.methods.firstOrNull { it.name == "getSurfaces" }
                        val surfaces = getSurfacesMethod?.invoke(arg) as? List<*>
                        if (surfaces != null) {
                            for (s in surfaces) {
                                if (s is Surface && !list.contains(s)) list.add(s)
                            }
                        }
                    } else if (clazz.name.contains("SessionConfiguration")) {
                        val getOutputConfigurationsMethod = clazz.methods.firstOrNull { it.name == "getOutputConfigurations" }
                        val configs = getOutputConfigurationsMethod?.invoke(arg) as? List<*>
                        if (configs != null) {
                            for (c in configs) {
                                list.addAll(extractSurfaces(c))
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return list
    }

    private fun hookCameraSessionRequests(classLoader: ClassLoader, targetPackage: String): Int {
        var installed = 0

        // 1. Hook CaptureRequest.Builder.addTarget(Surface) with hardware camera redirection
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
                        if (surface != null && surface.isValid) {
                            val hash = System.identityHashCode(surface).toString(16)
                            Log.i(TAG, "[CAMERA2_OUTPUT_DISCOVERED] target=$targetPackage surface=@$hash valid=${surface.isValid}")
                            Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=Camera2\nCLASS=${surface.javaClass.name}\nSURFACE_ID=@$hash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${surface.isValid}\nSURFACE_TEXTURE=null")
                            Log.i(TAG, "[SURFACE_CAPTURE_REQUEST_TARGET] Target Surface added to CaptureRequest: hash=@$hash valid=${surface.isValid} in $targetPackage")
                            if (!activeSurfaces.contains(surface)) {
                                onSessionConfigured(listOf(surface), targetPackage, "CaptureRequest.Builder.addTarget")
                            }

                            // Redirect hardware camera target to dummy surface to keep target preview surface isolated
                            val dummy = getOrCreateDummySurface()
                            if (surface != dummy) {
                                param.args[0] = dummy
                                Log.i(TAG, "[HARDWARE_STREAM_REDIRECTED] Redirected CaptureRequest.Builder.addTarget to dummy Surface@${System.identityHashCode(dummy).toString(16)}")
                            }
                        }
                    }
                }
            )
            installed++
            Log.i(TAG, "[HOOK_OK] Hooked CaptureRequest.Builder.addTarget(Surface)")
        } catch (t: Throwable) {
            Log.w(TAG, "[HOOK_FAIL] CaptureRequest.Builder.addTarget: ${t.message}")
        }

        // 2. Hook CameraCaptureSession repeating request methods
        val sessionClasses = listOf(
            "android.hardware.camera2.CameraCaptureSession",
            "android.hardware.camera2.impl.CameraCaptureSessionImpl"
        )
        for (sessionClassName in sessionClasses) {
            try {
                val sessionClass = Class.forName(sessionClassName, false, classLoader)
                for (m in sessionClass.declaredMethods) {
                    if (m.name.startsWith("setRepeatingRequest") || m.name.startsWith("setRepeatingBurst") || m.name.startsWith("setSingleRepeatingRequest")) {
                        try {
                            XposedHelpers.findAndHookMethod(
                                sessionClass,
                                m.name,
                                *m.parameterTypes,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        Log.i(TAG, "[CAMERA2_REPEATING_REQUEST_INTERCEPTED] target=$targetPackage method=$sessionClassName.${m.name}")
                                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=$sessionClassName.${m.name}")
                                    }
                                }
                            )
                            installed++
                            Log.i(TAG, "[HOOK_OK] Hooked $sessionClassName.${m.name}")
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        return installed
    }

    /**
     * Triggered when target process opens a CameraDevice.
     */
    fun onCameraDeviceOpening(cameraId: String, methodPath: String = "openCamera", targetPackage: String = "unknown") {
        if (targetPackage != "unknown") {
            ZestoRemoteFrameSource.setAttachedPackage(targetPackage)
        }
        TargetCameraLifecycleManager.onCameraOpening("CAMERA2", cameraId, targetPackage)
        currentStatus = HookStatus.CAMERA2_DEVICE_OPEN_INTERCEPTED
        val msg = "Target process ($targetPackage) requested Camera2 device: cameraId=$cameraId via $methodPath"
        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
    }

    /**
     * Called when a target CameraCaptureSession is created or target preview surface is discovered.
     * Attaches isolated preview surfaces and starts the live frame substitution pump.
     */
    fun onSessionConfigured(outputs: List<Surface>, targetPackage: String = "unknown", source: String = "createCaptureSession") {
        if (targetPackage != "unknown") {
            ZestoRemoteFrameSource.setAttachedPackage(targetPackage)
        }
        TargetCameraLifecycleManager.onCameraConfiguring("CAMERA2", targetPackage, source)
        currentStatus = HookStatus.CAMERA2_SESSION_INTERCEPTED
        Log.i(TAG, "[CAMERA2_SESSION_CONFIGURED] CameraCaptureSession successfully configured for $targetPackage via $source with ${outputs.size} output surfaces.")
        Log.i(TAG, "[CAMERA2_SESSION_INTERCEPTED] Intercepted $source with ${outputs.size} output surfaces.")

        val dummy = dummySurface
        val validSurfaces = outputs.filter { it.isValid && it != dummy }
        if (validSurfaces.isNotEmpty()) {
            if (activeSurfaces.isNotEmpty() && activeSurfaces != validSurfaces) {
                Log.i(TAG, "[SURFACE_REPLACED] Replacing ${activeSurfaces.size} previous surfaces with ${validSurfaces.size} new surfaces")
            }
            activeSurfaces.clear()
            activeSurfaces.addAll(validSurfaces)

            TargetCameraLifecycleManager.onSurfaceAttached("CAMERA2", validSurfaces, targetPackage)
            currentStatus = HookStatus.SURFACE_TARGET_ATTACHED
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
     * Continuously retrieves live RTSP frames and renders onto target Camera2 preview surfaces.
     * Optimized for low latency using dynamic pacing and newest-frame delivery.
     */
    fun startFramePump(surfaces: List<Surface>, targetPackage: String = "unknown") {
        stopFramePump()
        isPumping.set(true)
        currentStatus = HookStatus.FRAME_PUMP_ACTIVE

        for (surface in surfaces) {
            val hash = System.identityHashCode(surface).toString(16)
            Log.i(TAG, "[CAMERA2_CAMERA_SURFACE_ATTACHED] Camera preview Surface (@$hash) attached for Zesto rendering in $targetPackage")
        }

        val activeMsg = "Camera2 pipeline active for ${surfaces.size} target surface(s) in $targetPackage"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        Log.i(TAG, "[FRAME_SOURCE_STARTED] Camera2 frame substitution pump started for $targetPackage")
        ZestoRemoteFrameSource.reportMilestone("FRAME_SUBSTITUTION_ACTIVE", activeMsg)

        pumpTask = renderExecutor.submit {
            var cycleCount = 0L
            var lastRenderedFrameId = -1L
            var lastRenderedSeq = -1L
            var duplicateSkipCount = 0L
            val threadName = Thread.currentThread().name

            while (isPumping.get()) {
                val cycleStartTime = System.currentTimeMillis()
                try {
                    val frameResult = ZestoRemoteFrameSource.fetchLatestFrame()
                    val bitmap = frameResult.bitmap
                    cycleCount++

                    val frameId = if (frameResult.frameId > 0) frameResult.frameId else cycleCount
                    val isDuplicate = (frameResult.sequence > 0L && frameResult.sequence == lastRenderedSeq) ||
                                      (!frameResult.isNewFrame && lastRenderedSeq > 0L && frameResult.frameId == lastRenderedFrameId)

                    if (isDuplicate) {
                        duplicateSkipCount++
                        if (duplicateSkipCount == 1L || duplicateSkipCount % 60L == 0L) {
                            Log.d(TAG, "[FRAME_SKIP_DUPLICATE] frameId=$frameId seq=${frameResult.sequence} lastRenderedSeq=$lastRenderedSeq skips=$duplicateSkipCount")
                        }
                        val elapsed = System.currentTimeMillis() - cycleStartTime
                        val sleepMs = (8L - elapsed).coerceIn(2L, 10L)
                        Thread.sleep(sleepMs)
                        continue
                    }

                    val bmpHash = if (bitmap != null) System.identityHashCode(bitmap).toString(16) else "null"

                    var hasValidSurface = false
                    for (surface in surfaces) {
                        if (!surface.isValid) {
                            continue
                        }
                        hasValidSurface = true
                        val surfaceHash = System.identityHashCode(surface).toString(16)

                        var canvas: Canvas? = null
                        try {
                            if (cycleCount == 1L || cycleCount % 60L == 0L) {
                                Log.i(TAG, "[FRAME_RENDER_STARTED] id=$frameId thread=$threadName hasBitmap=${bitmap != null} isRecycled=${bitmap?.isRecycled ?: true} bmpSize=${bitmap?.width}x${bitmap?.height} bmpId=@$bmpHash health=${frameResult.healthState}")
                                Log.i(TAG, "[SURFACE_ZESTO_RENDER_TARGET] Active Camera2 render target surface=@$surfaceHash valid=${surface.isValid}")
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
                                    Log.i(TAG, "[FRAME_RENDERED_TO_OUTPUT] id=$frameId canvasSize=${canvas.width}x${canvas.height}")
                                }
                            }
                        } catch (e: Exception) {
                            if (cycleCount == 1L || cycleCount % 60L == 0L) {
                                Log.d(TAG, "Camera2 render canvas note on surface=@$surfaceHash: ${e.message}")
                            }
                        } finally {
                            if (canvas != null) {
                                try {
                                    surface.unlockCanvasAndPost(canvas)
                                    lastRenderedFrameId = frameId
                                    lastRenderedSeq = frameResult.sequence
                                    val count = substitutedFramesCount.incrementAndGet()
                                    TargetCameraLifecycleManager.onFrameRendered(
                                        apiName = "CAMERA2",
                                        targetPkg = targetPackage,
                                        frameId = frameId,
                                        seq = frameResult.sequence,
                                        fps = 29.8,
                                        surface = surface,
                                        payloadSize = bitmap?.byteCount ?: 0
                                    )
                                    if (count == 1L || count % 30L == 0L) {
                                        Log.i(TAG, "[FRAME_RENDER] renderCount=$count frameId=$frameId seq=${frameResult.sequence}")
                                        Log.i(TAG, "[FRAME_POSTED_TO_OUTPUT] id=$frameId")
                                        val logMsg = "Target preview surface received and posted frame #$count (frameId=$frameId seq=${frameResult.sequence})"
                                        Log.i(TAG, "[FRAME_POSTED_TO_SURFACE] $logMsg")
                                        ZestoRemoteFrameSource.reportMilestone("FRAME_POSTED_TO_SURFACE", logMsg)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Camera2 unlock canvas exception on surface=@$surfaceHash: ${e.message}")
                                }
                            }
                        }
                    }

                    if (!hasValidSurface) {
                        currentStatus = HookStatus.SURFACE_LOST
                    }

                    // Low-latency adaptive sleep: measure actual render duration
                    val elapsed = System.currentTimeMillis() - cycleStartTime
                    val sleepMs = (33L - elapsed).coerceIn(2L, 33L)
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Camera2 frame pump cycle exception: ${e.message}")
                }
            }
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Camera2 frame pump loop exited")
        }
    }

    fun stopFramePump() {
        if (isPumping.getAndSet(false)) {
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Camera2 frame substitution pump stopped.")
            TargetCameraLifecycleManager.onCameraClosing("CAMERA2", "active.target")
        }
        activeSurfaces.clear()
        pumpTask?.cancel(true)
        pumpTask = null
        if (currentStatus == HookStatus.FRAME_PUMP_ACTIVE) {
            currentStatus = HookStatus.HOOK_REGISTERED
        }
    }
}
