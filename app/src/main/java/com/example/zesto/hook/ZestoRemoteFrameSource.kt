package com.example.zesto.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.example.zesto.frame.FrameHealthState
import com.example.zesto.frame.ZestoFrameBridge
import com.example.zesto.frame.ZestoFrameTransformer

/**
 * Data container for frames pulled from the local bridge or cross-process provider.
 */
data class RemoteFrameResult(
    val frameId: Long = 0L,
    val bitmap: Bitmap? = null,
    val width: Int = 1280,
    val height: Int = 720,
    val healthState: String = "NO_FRAME",
    val isStreaming: Boolean = false
)

/**
 * Remote frame source and bi-directional diagnostics reporter for hooked target processes.
 */
object ZestoRemoteFrameSource {
    private const val TAG = "ZestoRemoteFrameSource"
    const val AUTHORITY = "com.example.zesto.frameprovider"
    private val PROVIDER_URI: Uri = Uri.parse("content://$AUTHORITY/frame")

    private var targetAppContext: Context? = null
    private var attachedPackageName: String = "unknown.target"
    private var lastIpcLogMs: Long = 0L

    fun setAttachedPackage(packageName: String) {
        this.attachedPackageName = packageName
    }

    fun getAttachedPackage(): String = attachedPackageName

    fun getTargetContext(): Context? {
        if (targetAppContext != null) return targetAppContext
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null) as? Context
            if (app != null) {
                targetAppContext = app
                return app
            }
        } catch (_: Throwable) {
        }
        return null
    }

    fun setTargetContext(context: Context) {
        this.targetAppContext = context
    }

    /**
     * Fetches the latest video frame either from in-memory bridge or cross-process IPC.
     */
    fun fetchLatestFrame(): RemoteFrameResult {
        // 1. In-process direct bridge check (Fast-path when running in the same process)
        val localFrame = ZestoFrameBridge.consumeLatestFrame()
        if (localFrame.bitmap != null && !localFrame.bitmap.isRecycled) {
            return RemoteFrameResult(
                frameId = localFrame.frameId,
                bitmap = localFrame.bitmap,
                width = localFrame.width,
                height = localFrame.height,
                healthState = ZestoFrameBridge.getFrameHealthState().name,
                isStreaming = true
            )
        }

        // 2. Cross-process IPC fetch from ZestoFrameContentProvider
        val context = getTargetContext() ?: return RemoteFrameResult(
            frameId = localFrame.frameId,
            bitmap = null,
            width = localFrame.width,
            height = localFrame.height,
            healthState = ZestoFrameBridge.getFrameHealthState().name,
            isStreaming = false
        )

        return try {
            val bundle = context.contentResolver.call(PROVIDER_URI, "getLatestFrame", null, null)
            if (bundle != null) {
                val bitmap = bundle.getParcelable<Bitmap>("bitmap")
                val frameId = bundle.getLong("frame_id", 0L)
                val width = bundle.getInt("width", 1280)
                val height = bundle.getInt("height", 720)
                val healthState = bundle.getString("health_state", "NO_FRAME")
                val isStreaming = bundle.getBoolean("is_streaming", false)
                RemoteFrameResult(
                    frameId = frameId,
                    bitmap = bitmap,
                    width = width,
                    height = height,
                    healthState = healthState ?: "NO_FRAME",
                    isStreaming = isStreaming
                )
            } else {
                RemoteFrameResult()
            }
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastIpcLogMs > 5000L) {
                lastIpcLogMs = now
                Log.w(TAG, "IPC query to ZestoFrameContentProvider: ${e.message}")
            }
            RemoteFrameResult()
        }
    }

    /**
     * Reports a diagnostic milestone from the target process back to Zesto.
     */
    fun reportMilestone(stage: String, message: String) {
        val pkg = attachedPackageName
        Log.i(TAG, "[$stage] $message (target: $pkg)")

        // Local in-memory update
        ZestoFrameBridge.reportExternalMilestone(stage, pkg, message)

        // Cross-process notification via ContentProvider
        val context = getTargetContext() ?: return
        try {
            val extras = Bundle().apply {
                putString("package_name", pkg)
                putString("message", message)
                putString("stage", stage)
            }
            context.contentResolver.call(PROVIDER_URI, "reportMilestone", stage, extras)
        } catch (_: Exception) {
        }
    }

    /**
     * Renders a crisp virtual camera test card in true 9:16 portrait format.
     */
    fun renderStandbyTestPattern(canvas: Canvas, frameId: Long, health: String) {
        ZestoFrameTransformer.renderPortraitStandbyPattern(
            canvas = canvas,
            targetPackage = attachedPackageName,
            frameId = frameId,
            healthState = health
        )
    }
}
