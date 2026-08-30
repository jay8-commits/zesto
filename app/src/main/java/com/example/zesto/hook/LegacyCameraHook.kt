package com.example.zesto.hook

import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.hardware.Camera
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
 * Intercepts Camera.setPreviewDisplay(SurfaceHolder), Camera.setPreviewTexture(SurfaceTexture),
 * and isolates the target application's real preview Surface by redirecting hardware camera stream
 * to an offscreen dummy SurfaceTexture.
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
    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZestoCamera1RenderThread").apply { isDaemon = true }
    }
    private var pumpTask: Future<*>? = null
    private val substitutedFramesCount = AtomicLong(0L)

    // Call tracking counters
    private val openCallCount = AtomicLong(0L)
    private val setPreviewDisplayCount = AtomicLong(0L)
    private val setPreviewTextureCount = AtomicLong(0L)
    private val startPreviewCount = AtomicLong(0L)
    private val stopPreviewCount = AtomicLong(0L)
    private val releaseCount = AtomicLong(0L)

    val status: LegacyHookStatus get() = currentStatus

    private var dummySurfaceTexture: SurfaceTexture? = null

    @Synchronized
    private fun getOrCreateDummyTexture(): SurfaceTexture {
        val existing = dummySurfaceTexture
        if (existing != null) return existing
        val st = SurfaceTexture(1002).apply {
            setDefaultBufferSize(640, 480)
        }
        dummySurfaceTexture = st
        return st
    }

    fun attachHook(classLoader: ClassLoader, targetPackage: String = "unknown") {
        try {
            val cameraClass = Class.forName("android.hardware.Camera", false, classLoader)

            // 1. Hook Camera.open(int)
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "open",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = openCallCount.incrementAndGet()
                        val cameraId = param.args.getOrNull(0)?.toString() ?: "0"
                        Log.i(TAG, "[CAMERA_API_DETECTED] api=LEGACY_CAMERA")
                        Log.i(TAG, "[LEGACY_CAMERA_OPEN_INTERCEPTED] Camera.open(cameraId=$cameraId) call #$count in $targetPackage")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Camera.open(cameraId=$cameraId)")
                        val msg = "Target process ($targetPackage) requested Camera1 device: cameraId=$cameraId"
                        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
                        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
                    }
                }
            )

            // 2. Hook Camera.open()
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "open",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = openCallCount.incrementAndGet()
                        Log.i(TAG, "[CAMERA_API_DETECTED] api=LEGACY_CAMERA")
                        Log.i(TAG, "[LEGACY_CAMERA_OPEN_INTERCEPTED] Camera.open() default device call #$count in $targetPackage")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Camera.open()")
                        val msg = "Target process ($targetPackage) requested default Camera1 device"
                        Log.i(TAG, "[CAMERA2_DEVICE_OPEN_INTERCEPTED] $msg")
                        ZestoRemoteFrameSource.reportMilestone("CAMERA2_DEVICE_OPEN_INTERCEPTED", msg)
                    }
                }
            )

            // 3. Hook setPreviewDisplay(SurfaceHolder) with Hardware Camera Stream Redirection
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewDisplay",
                SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = setPreviewDisplayCount.incrementAndGet()
                        Log.i(TAG, "[CAMERA_API_DETECTED] api=LEGACY_CAMERA")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Camera.setPreviewDisplay(SurfaceHolder) call #$count")
                        val holder = param.args.getOrNull(0) as? SurfaceHolder
                        val camera = param.thisObject as? Camera
                        if (holder != null) {
                            onPreviewDisplaySet(holder, targetPackage)
                            // Redirect hardware camera to dummy texture so real SurfaceHolder is not overwritten by HAL
                            if (camera != null) {
                                try {
                                    val dummy = getOrCreateDummyTexture()
                                    camera.setPreviewTexture(dummy)
                                    param.result = null // Suppress native setPreviewDisplay to keep SurfaceHolder isolated
                                    Log.i(TAG, "[HARDWARE_STREAM_REDIRECTED] Camera1 preview display redirected to dummy SurfaceTexture. Real preview SurfaceHolder isolated.")
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Failed to redirect preview display to dummy texture: ${e.message}")
                                }
                            }
                        }
                    }
                }
            )

            // 4. Hook setPreviewTexture(SurfaceTexture) with Hardware Camera Stream Redirection
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewTexture",
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = setPreviewTextureCount.incrementAndGet()
                        Log.i(TAG, "[CAMERA_API_DETECTED] api=LEGACY_CAMERA")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Camera.setPreviewTexture(SurfaceTexture) call #$count")
                        val texture = param.args.getOrNull(0) as? SurfaceTexture
                        val dummy = getOrCreateDummyTexture()
                        if (texture != null && texture != dummy) {
                            onPreviewTextureSet(texture, targetPackage)
                            // Redirect hardware camera to dummy texture so preview texture is isolated
                            param.args[0] = dummy
                            Log.i(TAG, "[HARDWARE_STREAM_REDIRECTED] Camera1 preview texture redirected to dummy SurfaceTexture. Real preview SurfaceTexture isolated.")
                        }
                    }
                }
            )

            // 5. Hook startPreview()
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "startPreview",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = startPreviewCount.incrementAndGet()
                        Log.i(TAG, "[CAMERA_API_DETECTED] api=LEGACY_CAMERA")
                        Log.i(TAG, "[LEGACY_CAMERA_PREVIEW_STARTED] Camera.startPreview() call #$count in $targetPackage")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Camera.startPreview()")
                    }
                }
            )

            // 6. Hook stopPreview()
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "stopPreview",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = stopPreviewCount.incrementAndGet()
                        Log.i(TAG, "[LEGACY_CAMERA_STOP_PREVIEW] Camera.stopPreview() call #$count in $targetPackage")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Camera.stopPreview()")
                    }
                }
            )

            // 7. Hook release()
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "release",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = releaseCount.incrementAndGet()
                        Log.i(TAG, "[LEGACY_CAMERA_RELEASE] Camera.release() call #$count in $targetPackage")
                        Log.i(TAG, "[CAMERA_METHOD_INTERCEPTED]\nTARGET=$targetPackage\nMETHOD=Camera.release()")
                        stopFramePump()
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
        val surface = holder.surface
        val holderHash = System.identityHashCode(holder).toString(16)
        val surfaceHash = System.identityHashCode(surface).toString(16)
        Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=Camera1\nCLASS=${surface.javaClass.name}\nSURFACE_ID=@$surfaceHash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${surface.isValid}\nSURFACE_TEXTURE=null")
        Log.i(TAG, "[SURFACE_LIFECYCLE] SurfaceHolder=@$holderHash Surface=@$surfaceHash valid=${surface.isValid} in $targetPackage")
        Log.i(TAG, "[PREVIEW_DISPLAY_INTERCEPTED] Intercepted Camera1 SurfaceHolder preview display.")
        Log.i(TAG, "[SURFACE_ATTACHED] Camera1 SurfaceHolder surface attached.")
        TargetCameraLifecycleManager.onSurfaceAttached("LEGACY_CAMERA", listOf(surface), targetPackage)
        startFramePump(surface, targetPackage)
    }

    fun onPreviewTextureSet(texture: SurfaceTexture, targetPackage: String = "unknown") {
        currentStatus = LegacyHookStatus.PREVIEW_DISPLAY_INTERCEPTED
        val surface = Surface(texture)
        val surfaceHash = System.identityHashCode(surface).toString(16)
        val texHash = System.identityHashCode(texture).toString(16)
        Log.i(TAG, "[CAMERA_OUTPUT_DISCOVERED]\nTARGET=$targetPackage\nAPI=Camera1\nCLASS=Surface\nSURFACE_ID=@$surfaceHash\nWIDTH=1080\nHEIGHT=1920\nFORMAT=UNKNOWN\nVALID=${surface.isValid}\nSURFACE_TEXTURE=SurfaceTexture@$texHash")
        Log.i(TAG, "[SURFACE_LIFECYCLE] SurfaceTexture=@$texHash Surface=@$surfaceHash valid=${surface.isValid} in $targetPackage")
        Log.i(TAG, "[PREVIEW_DISPLAY_INTERCEPTED] Intercepted Camera1 SurfaceTexture preview.")
        Log.i(TAG, "[SURFACE_ATTACHED] Camera1 SurfaceTexture surface attached.")
        TargetCameraLifecycleManager.onSurfaceAttached("LEGACY_CAMERA", listOf(surface), targetPackage)
        startFramePump(surface, targetPackage)
    }

    private fun startFramePump(surface: Surface, targetPackage: String = "unknown") {
        stopFramePump()
        if (!surface.isValid) {
            val surfaceHash = System.identityHashCode(surface).toString(16)
            Log.w(TAG, "[SURFACE_INVALID] Cannot start frame pump on invalid Surface=@$surfaceHash")
            return
        }
        isPumping.set(true)
        currentStatus = LegacyHookStatus.FRAME_PUMP_ACTIVE

        val activeMsg = "Active frame substitution pump rendering 9:16 portrait frames onto Camera1 surface"
        Log.i(TAG, "[FRAME_SUBSTITUTION_ACTIVE] $activeMsg")
        Log.i(TAG, "[FRAME_SOURCE_STARTED] Camera1 frame substitution pump started for $targetPackage")
        ZestoRemoteFrameSource.reportMilestone("FRAME_SUBSTITUTION_ACTIVE", activeMsg)

        pumpTask = renderExecutor.submit {
            var cycleCount = 0L
            var lastRenderedFrameId = -1L
            var lastRenderedSeq = -1L
            var duplicateSkipCount = 0L
            val surfaceHash = System.identityHashCode(surface).toString(16)
            val threadName = Thread.currentThread().name

            while (isPumping.get() && surface.isValid) {
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

                    var canvas: Canvas? = null
                    try {
                        if (cycleCount == 1L || cycleCount % 60L == 0L) {
                            Log.i(TAG, "[FRAME_RENDER_STARTED] id=$frameId thread=$threadName hasBitmap=${bitmap != null} isRecycled=${bitmap?.isRecycled ?: true} bmpSize=${bitmap?.width}x${bitmap?.height} bmpId=@$bmpHash health=${frameResult.healthState}")
                            Log.i(TAG, "[SURFACE_ZESTO_RENDER_TARGET] Active render target surface=@$surfaceHash valid=${surface.isValid}")
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
                        } else {
                            if (cycleCount == 1L || cycleCount % 60L == 0L) {
                                Log.w(TAG, "[LOCK_CANVAS_NULL] surface.lockCanvas returned null for surface=@$surfaceHash")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Camera1 render canvas exception on surface=@$surfaceHash: ${e.message}")
                    } finally {
                        if (canvas != null) {
                            try {
                                surface.unlockCanvasAndPost(canvas)
                                lastRenderedFrameId = frameId
                                lastRenderedSeq = frameResult.sequence
                                val count = substitutedFramesCount.incrementAndGet()
                                TargetCameraLifecycleManager.onFrameRendered(
                                    apiName = "LEGACY_CAMERA",
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
                                Log.w(TAG, "Camera1 unlock canvas exception on surface=@$surfaceHash: ${e.message}")
                            }
                        }
                    }
                    val elapsed = System.currentTimeMillis() - cycleStartTime
                    val sleepMs = (33L - elapsed).coerceIn(2L, 33L)
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Camera1 render cycle exception: ${e.message}")
                }
            }
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Camera1 frame pump loop exited for surface=@$surfaceHash")
        }
    }

    fun stopFramePump() {
        if (isPumping.getAndSet(false)) {
            Log.i(TAG, "[FRAME_SOURCE_STOPPED] Camera1 frame substitution pump stopped.")
            TargetCameraLifecycleManager.onCameraClosing("LEGACY_CAMERA", "active.target")
        }
        pumpTask?.cancel(true)
        pumpTask = null
    }
}
