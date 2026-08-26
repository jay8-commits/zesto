package com.example.zesto.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.example.zesto.frame.FrameHealthState
import com.example.zesto.frame.ZestoFrameBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // 1. In-process direct bridge check
        val localFrame = ZestoFrameBridge.consumeLatestFrame()
        if (localFrame.bitmap != null) {
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
            Log.w(TAG, "Failed to query ZestoFrameContentProvider: ${e.message}")
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
     * Renders a crisp virtual camera test card when RTSP video stream is in standby.
     */
    fun renderStandbyTestPattern(canvas: Canvas, frameId: Long, health: String) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // Background
        val bgPaint = Paint().apply { color = Color.rgb(15, 23, 42) }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Center card
        val cardPaint = Paint().apply { color = Color.rgb(30, 41, 59) }
        val cardRect = Rect(
            (w * 0.1f).toInt(),
            (h * 0.2f).toInt(),
            (w * 0.9f).toInt(),
            (h * 0.8f).toInt()
        )
        canvas.drawRect(cardRect, cardPaint)

        // Header accent line
        val accentPaint = Paint().apply { color = Color.rgb(16, 185, 129) }
        canvas.drawRect(
            w * 0.1f,
            h * 0.2f,
            w * 0.9f,
            h * 0.22f,
            accentPaint
        )

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = (h * 0.045f).coerceIn(24f, 48f)
            isAntiAlias = true
        }

        val secondaryPaint = Paint().apply {
            color = Color.rgb(148, 163, 184)
            textSize = (h * 0.035f).coerceIn(18f, 36f)
            isAntiAlias = true
        }

        val emeraldPaint = Paint().apply {
            color = Color.rgb(16, 185, 129)
            textSize = (h * 0.035f).coerceIn(18f, 36f)
            isAntiAlias = true
        }

        val startX = w * 0.14f
        var currentY = h * 0.30f
        val lineSpacing = h * 0.065f

        canvas.drawText("ZESTO VIRTUAL CAMERA ACTIVE", startX, currentY, textPaint)
        currentY += lineSpacing
        canvas.drawText("TARGET: $attachedPackageName", startX, currentY, emeraldPaint)
        currentY += lineSpacing
        canvas.drawText("PIPELINE: RTSP -> Decoder -> Virtual Camera2", startX, currentY, secondaryPaint)
        currentY += lineSpacing
        canvas.drawText("STATUS: Intercepted | Health: $health", startX, currentY, secondaryPaint)
        currentY += lineSpacing
        canvas.drawText("SOURCE: rtsp://192.168.1.49:8554/live/obs", startX, currentY, secondaryPaint)
        currentY += lineSpacing

        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        canvas.drawText("CYCLE: #$frameId | TIME: $timeStr", startX, currentY, emeraldPaint)
    }
}
