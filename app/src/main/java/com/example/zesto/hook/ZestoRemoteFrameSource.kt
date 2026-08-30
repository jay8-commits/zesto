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
    val sequence: Long = 0L,
    val bitmap: Bitmap? = null,
    val width: Int = 1080,
    val height: Int = 1920,
    val healthState: String = "NO_FRAME",
    val isStreaming: Boolean = false,
    val isNewFrame: Boolean = false
)

/**
 * Remote frame source and bi-directional diagnostics reporter for hooked target processes.
 */
object ZestoRemoteFrameSource {
    private const val TAG = "ZestoRemoteFrameSource"
    const val AUTHORITY = "com.example.zesto.frameprovider"
    private val PROVIDER_URI: Uri by lazy {
        try {
            Uri.parse("content://$AUTHORITY/frame")
        } catch (_: Throwable) {
            Uri.EMPTY
        }
    }

    private var targetAppContext: Context? = null
    private var attachedPackageName: String = "unknown.target"
    private var lastIpcLogMs: Long = 0L

    private var lastFetchedFrameId: Long = 0L
    private var lastFetchedSeq: Long = 0L
    private var cachedDecodedBitmap: Bitmap? = null
    private val clientReadCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val staleReadCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val newFrameCount = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var isProviderAvailable: Boolean = false
    private var consecutiveErrors: Int = 0
    private var nextProviderCheckMs: Long = 0L

    fun setAttachedPackage(packageName: String) {
        this.attachedPackageName = packageName
    }

    fun getAttachedPackage(): String = attachedPackageName

    fun isProviderReachable(): Boolean = isProviderAvailable

    fun getTargetContext(): Context? {
        if (targetAppContext != null) return targetAppContext
        try {
            val appGlobalsClass = Class.forName("android.app.AppGlobals")
            val getInitialAppMethod = appGlobalsClass.getMethod("getInitialApplication")
            val app = getInitialAppMethod.invoke(null) as? Context
            if (app != null) {
                targetAppContext = app
                return app
            }
        } catch (_: Throwable) {}
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null) as? Context
            if (app != null) {
                targetAppContext = app
                return app
            }
        } catch (_: Throwable) {}
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null)
            if (activityThread != null) {
                val getAppMethod = activityThreadClass.getMethod("getApplication")
                val app = getAppMethod.invoke(activityThread) as? Context
                if (app != null) {
                    targetAppContext = app
                    return app
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    fun setTargetContext(context: Context) {
        this.targetAppContext = context
    }

    /**
     * Fetches the latest video frame either from in-memory bridge or cross-process IPC.
     * Uses backoff when FrameProvider is unavailable to eliminate busy retry thrashing.
     */
    fun fetchLatestFrame(): RemoteFrameResult {
        val reads = clientReadCount.incrementAndGet()

        // 1. In-process direct bridge check (Fast-path when running in the same process)
        val localFrame = ZestoFrameBridge.consumeLatestFrame()
        if (localFrame.bitmap != null && !localFrame.bitmap.isRecycled) {
            isProviderAvailable = true
            consecutiveErrors = 0
            val isNew = localFrame.frameId > 0L && (localFrame.frameId > lastFetchedFrameId || localFrame.sequence > lastFetchedSeq)
            if (isNew) {
                lastFetchedFrameId = localFrame.frameId
                lastFetchedSeq = localFrame.sequence
                newFrameCount.incrementAndGet()
            } else {
                staleReadCount.incrementAndGet()
            }

            if (reads == 1L || reads % 60L == 0L) {
                Log.i(TAG, "[FRAME_HANDLE_READ] frameId=${localFrame.frameId} seq=${localFrame.sequence} isNewFrame=$isNew")
            }

            return RemoteFrameResult(
                frameId = localFrame.frameId,
                sequence = localFrame.sequence,
                bitmap = localFrame.bitmap,
                width = localFrame.width,
                height = localFrame.height,
                healthState = ZestoFrameBridge.getFrameHealthState().name,
                isStreaming = true,
                isNewFrame = isNew
            )
        }

        // 2. Cross-process IPC fetch from ZestoFrameContentProvider
        val context = getTargetContext() ?: return RemoteFrameResult(
            frameId = localFrame.frameId,
            sequence = localFrame.sequence,
            bitmap = null,
            width = localFrame.width,
            height = localFrame.height,
            healthState = ZestoFrameBridge.getFrameHealthState().name,
            isStreaming = false,
            isNewFrame = false
        )

        val now = System.currentTimeMillis()
        // If provider was unavailable, enforce backoff interval instead of 30 FPS hammering
        if (!isProviderAvailable && now < nextProviderCheckMs) {
            return RemoteFrameResult(
                frameId = 0L,
                sequence = 0L,
                bitmap = null,
                width = 1080,
                height = 1920,
                healthState = "AWAITING_ZESTO_PROVIDER",
                isStreaming = false,
                isNewFrame = false
            )
        }

        return try {
            var bundle: Bundle? = null
            try {
                bundle = context.contentResolver.call(PROVIDER_URI, "getLatestFrame", null, null)
            } catch (_: Throwable) {}
            if (bundle == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    bundle = context.contentResolver.call(AUTHORITY, "getLatestFrame", null, null)
                } catch (_: Throwable) {}
            }
            if (bundle != null) {
                try {
                    val cl = context.classLoader ?: ZestoRemoteFrameSource::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
                    bundle.classLoader = cl
                } catch (_: Throwable) {}

                val providerRunning = bundle.getBoolean("provider_running", true)
                val isStreaming = bundle.getBoolean("is_streaming", false)
                val healthState = bundle.getString("health_state", "NO_FRAME")
                val frameId = bundle.getLong("frame_id", 0L)
                val sequence = bundle.getLong("sequence", frameId)
                val width = bundle.getInt("width", 1080)
                val height = bundle.getInt("height", 1920)
                val timestampUs = bundle.getLong("timestamp_us", 0L)

                val isNew = frameId > 0L && (frameId > lastFetchedFrameId || sequence > lastFetchedSeq)

                if (!isProviderAvailable) {
                    isProviderAvailable = true
                    consecutiveErrors = 0
                    Log.i(TAG, "[FRAME_BRIDGE] bridge connected (provider available=$providerRunning, streaming=$isStreaming)")
                }

                if (!isNew) {
                    staleReadCount.incrementAndGet()
                    if (reads == 1L || reads % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                        lastIpcLogMs = now
                        Log.i(TAG, "[FRAME_HANDLE_READ] frameId=$frameId seq=$sequence isNewFrame=false")
                    }
                    return RemoteFrameResult(
                        frameId = frameId,
                        sequence = sequence,
                        bitmap = cachedDecodedBitmap,
                        width = width,
                        height = height,
                        healthState = healthState ?: "NO_FRAME",
                        isStreaming = isStreaming,
                        isNewFrame = false
                    )
                }

                // NEW FRAME ARRIVED! Decode JPEG only when a new frame is detected!
                newFrameCount.incrementAndGet()
                lastFetchedFrameId = frameId
                lastFetchedSeq = sequence

                var transportUsed = "NONE"
                var finalBitmap: Bitmap? = null

                val jpegBytes = bundle.getByteArray("jpeg_buffer") ?: bundle.getByteArray("buffer")
                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    try {
                        val decoded = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (decoded != null && !decoded.isRecycled) {
                            cachedDecodedBitmap?.recycle()
                            cachedDecodedBitmap = decoded
                            finalBitmap = decoded
                            transportUsed = "JPEG_BUFFER"
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error decoding jpeg_buffer: ${e.message}")
                    }
                }

                // Layer 2: Direct Parcelable Bitmap fallback
                if (finalBitmap == null || finalBitmap.isRecycled) {
                    try {
                        val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            bundle.getParcelable("bitmap", Bitmap::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            bundle.getParcelable<Bitmap>("bitmap")
                        }
                        if (bmp != null && !bmp.isRecycled) {
                            transportUsed = "DIRECT_BITMAP"
                            finalBitmap = bmp
                        }
                    } catch (e: Throwable) {
                        Log.d(TAG, "Direct Parcelable Bitmap fallback note: ${e.message}")
                    }
                }

                val bufferSize = if (finalBitmap != null) finalBitmap.byteCount else (jpegBytes?.size ?: 0)

                if (reads == 1L || reads % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                    lastIpcLogMs = now
                    Log.i(TAG, "[FRAME_HANDLE_READ] frameId=$frameId seq=$sequence isNewFrame=true")
                    Log.i(TAG, "[IPC_FRAME_TRANSPORT] transport=$transportUsed frameId=$frameId seq=$sequence dimensions=${width}x${height} bufferSize=${bufferSize}B")
                }

                RemoteFrameResult(
                    frameId = frameId,
                    sequence = sequence,
                    bitmap = finalBitmap,
                    width = width,
                    height = height,
                    healthState = healthState ?: "NO_FRAME",
                    isStreaming = isStreaming,
                    isNewFrame = true
                )
            } else {
                handleProviderUnavailable(now)
                RemoteFrameResult(isNewFrame = false)
            }
        } catch (e: Exception) {
            handleProviderUnavailable(now, e.message)
            RemoteFrameResult(isNewFrame = false)
        }
    }

    private fun handleProviderUnavailable(nowMs: Long, errorMsg: String? = null) {
        consecutiveErrors++
        isProviderAvailable = false
        val backoffMs = (consecutiveErrors * 250L).coerceIn(250L, 2000L)
        nextProviderCheckMs = nowMs + backoffMs

        if (consecutiveErrors == 1 || consecutiveErrors % 15 == 0) {
            Log.i(TAG, "[FRAME_PROVIDER] provider running=false (consecutiveErrors=$consecutiveErrors, retryIn=${backoffMs}ms, error=${errorMsg ?: "null response"})")
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
