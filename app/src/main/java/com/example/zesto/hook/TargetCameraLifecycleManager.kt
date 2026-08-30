package com.example.zesto.hook

import android.util.Log
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Authoritative target camera connection & injection lifecycle states.
 */
enum class TargetCameraState {
    TARGET_CAMERA_DISCONNECTED,
    TARGET_CAMERA_OPENING,
    TARGET_CAMERA_CONFIGURING,
    TARGET_SURFACE_ATTACHED,
    TARGET_INJECTION_SYNCHRONIZED,
    TARGET_INJECTION_STREAMING,
    TARGET_CAMERA_CLOSING
}

/**
 * Authoritative Target Camera Connection & Injection Lifecycle Manager.
 *
 * Coordinates Camera1 (Legacy), Camera2, and CameraX hooks to ensure the virtual
 * injected feed is strictly synchronized with the physical target application's
 * camera session and preview surface.
 *
 * Enforces:
 * 1. Target camera opening -> configuring -> surface attached -> synchronized -> streaming -> closing lifecycle.
 * 2. Instant invalidation of stale/destroyed preview surfaces when camera is closed or reconfigured.
 * 3. Strict verification of INJECTION_CONFIRMED: only asserted when real frames are actively delivering to target surfaces.
 * 4. Boundary logging across all hook injection points.
 */
object TargetCameraLifecycleManager {
    private const val TAG = "ZestoTargetLifecycle"

    private val currentState = AtomicReference(TargetCameraState.TARGET_CAMERA_DISCONNECTED)
    private val activeTargetPackage = AtomicReference("unknown.target")
    private val activeApi = AtomicReference("NONE")
    private val activeSurfaces = CopyOnWriteArrayList<Surface>()

    private val monotonicSeqCounter = AtomicLong(0L)
    private val totalRenderedFrames = AtomicLong(0L)
    private val lastRenderEpochMs = AtomicLong(0L)
    private val lastFps = AtomicReference(0.0)

    val state: TargetCameraState get() = currentState.get()
    val targetPackage: String get() = activeTargetPackage.get()
    val api: String get() = activeApi.get()
    val renderedCount: Long get() = totalRenderedFrames.get()

    private fun transitionTo(newState: TargetCameraState) {
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            Log.i(TAG, "[TARGET_LIFECYCLE_STATE] state=$newState")
            ZestoRemoteFrameSource.reportMilestone("TARGET_LIFECYCLE_STATE", "Target state transitioned: $newState (package=${activeTargetPackage.get()})")
        }
    }

    fun onCameraOpening(apiName: String, cameraId: String, targetPkg: String) {
        activeApi.set(apiName)
        if (targetPkg != "unknown" && targetPkg.isNotEmpty()) {
            activeTargetPackage.set(targetPkg)
            ZestoRemoteFrameSource.setAttachedPackage(targetPkg)
        }
        transitionTo(TargetCameraState.TARGET_CAMERA_OPENING)
        Log.i(TAG, "[TARGET_CAMERA_OPENING] API=$apiName cameraId=$cameraId package=$targetPkg")
    }

    fun onCameraConfiguring(apiName: String, targetPkg: String, sessionInfo: String = "") {
        activeApi.set(apiName)
        if (targetPkg != "unknown" && targetPkg.isNotEmpty()) {
            activeTargetPackage.set(targetPkg)
            ZestoRemoteFrameSource.setAttachedPackage(targetPkg)
        }
        transitionTo(TargetCameraState.TARGET_CAMERA_CONFIGURING)
        Log.i(TAG, "[TARGET_CAMERA_CONFIGURING] API=$apiName package=$targetPkg info=$sessionInfo")
    }

    fun onSurfaceAttached(apiName: String, surfaces: List<Surface>, targetPkg: String, width: Int = 1080, height: Int = 1920) {
        activeApi.set(apiName)
        if (targetPkg != "unknown" && targetPkg.isNotEmpty()) {
            activeTargetPackage.set(targetPkg)
            ZestoRemoteFrameSource.setAttachedPackage(targetPkg)
        }

        // Filter valid surfaces and replace active surfaces
        val valid = surfaces.filter { it.isValid }
        activeSurfaces.clear()
        activeSurfaces.addAll(valid)

        if (valid.isNotEmpty()) {
            transitionTo(TargetCameraState.TARGET_SURFACE_ATTACHED)
            for (surface in valid) {
                val surfId = System.identityHashCode(surface)
                val seq = monotonicSeqCounter.incrementAndGet()
                Log.i(TAG, "[CAM2_HOOK_SURFACE] seq=$seq surfId=$surfId w=$width h=$height")
                Log.i(TAG, "[TARGET_SURFACE_ATTACHED] API=$apiName surfId=@${Integer.toHexString(surfId)} valid=${surface.isValid} totalSurfaces=${valid.size} package=$targetPkg")
            }
        } else {
            Log.w(TAG, "[TARGET_SURFACE_EMPTY] No valid surfaces supplied for $targetPkg")
            transitionTo(TargetCameraState.TARGET_CAMERA_CONFIGURING)
        }
    }

    fun onSurfaceInvalidated(apiName: String, surface: Surface, targetPkg: String) {
        activeSurfaces.remove(surface)
        val surfId = System.identityHashCode(surface)
        Log.i(TAG, "[TARGET_SURFACE_INVALIDATED] API=$apiName surfId=@${Integer.toHexString(surfId)} remainingSurfaces=${activeSurfaces.size} package=$targetPkg")
        if (activeSurfaces.isEmpty() || activeSurfaces.none { it.isValid }) {
            transitionTo(TargetCameraState.TARGET_CAMERA_CONFIGURING)
        }
    }

    fun onInjectionSynchronized(apiName: String, targetPkg: String, frameId: Long, seq: Long) {
        if (currentState.get() == TargetCameraState.TARGET_SURFACE_ATTACHED) {
            transitionTo(TargetCameraState.TARGET_INJECTION_SYNCHRONIZED)
            Log.i(TAG, "[TARGET_INJECTION_SYNCHRONIZED] API=$apiName package=$targetPkg frameId=$frameId seq=$seq")
        }
    }

    fun onFrameRendered(
        apiName: String,
        targetPkg: String,
        frameId: Long,
        seq: Long,
        fps: Double,
        surface: Surface?,
        payloadSize: Int = 0
    ) {
        val now = System.currentTimeMillis()
        lastRenderEpochMs.set(now)
        lastFps.set(fps)
        val count = totalRenderedFrames.incrementAndGet()

        if (currentState.get() != TargetCameraState.TARGET_INJECTION_STREAMING) {
            transitionTo(TargetCameraState.TARGET_INJECTION_STREAMING)
        }

        val effectiveSeq = if (seq > 0L) seq else count

        if (apiName == "LEGACY_CAMERA" || apiName == "CAMERA1") {
            Log.i(TAG, "[CAM1_HOOK_DELIVER] seq=$effectiveSeq pkg=$targetPkg size=$payloadSize")
        }

        if (count == 1L || count % 30L == 0L) {
            Log.i(TAG, String.format(java.util.Locale.US, "[TARGET_FRAME_RENDER] seq=%d pkg=%s fps=%.1f", effectiveSeq, targetPkg, fps))
        }
    }

    fun onCameraClosing(apiName: String, targetPkg: String) {
        activeSurfaces.clear()
        transitionTo(TargetCameraState.TARGET_CAMERA_CLOSING)
        Log.i(TAG, "[TARGET_CAMERA_CLOSING] API=$apiName package=$targetPkg")
    }

    fun onCameraClosed(apiName: String, targetPkg: String) {
        activeSurfaces.clear()
        transitionTo(TargetCameraState.TARGET_CAMERA_DISCONNECTED)
        Log.i(TAG, "[TARGET_CAMERA_DISCONNECTED] API=$apiName package=$targetPkg")
    }

    /**
     * Verifies whether injection is genuinely confirmed:
     * - Target camera is open and active
     * - Target surfaces exist and are valid
     * - Target injection is in STREAMING state
     * - Real frames were rendered within the last 2000ms
     */
    fun isInjectionConfirmed(): Boolean {
        if (currentState.get() != TargetCameraState.TARGET_INJECTION_STREAMING) {
            return false
        }
        val validSurfacesExist = activeSurfaces.any { it.isValid }
        if (!validSurfacesExist) {
            return false
        }
        val now = System.currentTimeMillis()
        val lastRender = lastRenderEpochMs.get()
        return (now - lastRender) < 2000L && totalRenderedFrames.get() > 0L
    }

    fun reset() {
        activeSurfaces.clear()
        totalRenderedFrames.set(0L)
        lastRenderEpochMs.set(0L)
        transitionTo(TargetCameraState.TARGET_CAMERA_DISCONNECTED)
    }
}
