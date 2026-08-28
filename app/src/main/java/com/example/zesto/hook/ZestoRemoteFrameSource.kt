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
import com.example.zesto.ipc.ZestoIpcSocketClient
import com.example.zesto.ipc.ZestoSharedMemoryBridge

/**
 * Data container for frames pulled from the local bridge or cross-process provider.
 */
data class RemoteFrameResult(
    val frameId: Long = 0L,
    val bitmap: Bitmap? = null,
    val width: Int = 1080,
    val height: Int = 1920,
    val healthState: String = "NO_FRAME",
    val isStreaming: Boolean = false
)

/**
 * Remote frame source and bi-directional diagnostics reporter for hooked target processes.
 *
 * Implements a 4-tier resilient IPC architecture:
 * 1. Direct in-memory bridge (intra-process)
 * 2. Linux Abstract Unix Domain Socket (high-performance, bypasses Android 11-15 package visibility)
 * 3. Atomic Dual-Buffer Shared Memory (lock-free tear-free shared buffer)
 * 4. ContentProvider ContentResolver IPC (standard Android IPC fallback)
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
     * Fetches the latest video frame through multi-tier IPC.
     */
    fun fetchLatestFrame(): RemoteFrameResult {
        val now = System.currentTimeMillis()

        // -------------------------------------------------------------
        // Tier 1: Direct in-process in-memory bridge check
        // -------------------------------------------------------------
        val localFrame = ZestoFrameBridge.consumeLatestFrame()
        if (localFrame.bitmap != null && !localFrame.bitmap.isRecycled) {
            isProviderAvailable = true
            consecutiveErrors = 0
            if (localFrame.frameId == 1L || localFrame.frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                lastIpcLogMs = now
                Log.i(TAG, "[IPC_TIER_LOCAL] Direct bridge frameId=${localFrame.frameId} res=${localFrame.width}x${localFrame.height}")
            }
            return RemoteFrameResult(
                frameId = localFrame.frameId,
                bitmap = localFrame.bitmap,
                width = localFrame.width,
                height = localFrame.height,
                healthState = ZestoFrameBridge.getFrameHealthState().name,
                isStreaming = true
            )
        }

        // -------------------------------------------------------------
        // Tier 2: Linux Abstract Unix Domain Socket (Primary cross-process transport)
        // -------------------------------------------------------------
        try {
            val socketResult = ZestoIpcSocketClient.fetchLatestFrame()
            if (socketResult != null && socketResult.bitmap != null && !socketResult.bitmap.isRecycled) {
                isProviderAvailable = true
                consecutiveErrors = 0
                if (socketResult.frameId == 1L || socketResult.frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                    lastIpcLogMs = now
                    Log.i(TAG, "[IPC_TIER_SOCKET] Received frameId=${socketResult.frameId} res=${socketResult.width}x${socketResult.height} hasBitmap=true target=$attachedPackageName")
                }
                return socketResult
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Socket IPC attempt note: ${e.message}")
        }

        // -------------------------------------------------------------
        // Tier 3: Atomic Dual-Buffer Shared Memory
        // -------------------------------------------------------------
        try {
            val shmResult = ZestoSharedMemoryBridge.readLatestFrame()
            if (shmResult != null && shmResult.bitmap != null && !shmResult.bitmap.isRecycled) {
                isProviderAvailable = true
                consecutiveErrors = 0
                if (shmResult.frameId == 1L || shmResult.frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                    lastIpcLogMs = now
                    Log.i(TAG, "[IPC_TIER_SHARED_MEM] Received frameId=${shmResult.frameId} res=${shmResult.width}x${shmResult.height} hasBitmap=true target=$attachedPackageName")
                }
                return shmResult
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Shared memory IPC attempt note: ${e.message}")
        }

        // -------------------------------------------------------------
        // Tier 4: ContentProvider ContentResolver IPC fallback
        // -------------------------------------------------------------
        val context = getTargetContext()
        if (context == null) {
            return RemoteFrameResult(
                frameId = 0L,
                bitmap = null,
                width = 1080,
                height = 1920,
                healthState = "AWAITING_ZESTO_PROVIDER",
                isStreaming = false
            )
        }

        if (!isProviderAvailable && now < nextProviderCheckMs) {
            return RemoteFrameResult(
                frameId = 0L,
                bitmap = null,
                width = 1080,
                height = 1920,
                healthState = "AWAITING_ZESTO_PROVIDER",
                isStreaming = false
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
                val width = bundle.getInt("width", 1080)
                val height = bundle.getInt("height", 1920)

                var finalBitmap: Bitmap? = null

                val jpegBytes = bundle.getByteArray("jpeg_buffer") ?: bundle.getByteArray("buffer")
                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    try {
                        val decoded = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (decoded != null && !decoded.isRecycled) {
                            finalBitmap = decoded
                        }
                    } catch (_: Throwable) {}
                }

                if (finalBitmap == null || finalBitmap.isRecycled) {
                    try {
                        val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            bundle.getParcelable("bitmap", Bitmap::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            bundle.getParcelable<Bitmap>("bitmap")
                        }
                        if (bmp != null && !bmp.isRecycled) {
                            finalBitmap = bmp
                        }
                    } catch (_: Throwable) {}
                }

                if (finalBitmap == null || finalBitmap.isRecycled) {
                    try {
                        context.contentResolver.openInputStream(PROVIDER_URI)?.use { inputStream ->
                            val streamed = android.graphics.BitmapFactory.decodeStream(inputStream)
                            if (streamed != null && !streamed.isRecycled) {
                                finalBitmap = streamed
                            }
                        }
                    } catch (_: Throwable) {}
                }

                isProviderAvailable = true
                consecutiveErrors = 0

                if (frameId == 1L || frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                    lastIpcLogMs = now
                    Log.i(TAG, "[IPC_TIER_CONTENT_PROVIDER] frameId=$frameId hasBitmap=${finalBitmap != null} target=$attachedPackageName")
                }

                RemoteFrameResult(
                    frameId = frameId,
                    bitmap = finalBitmap,
                    width = width,
                    height = height,
                    healthState = healthState ?: "NO_FRAME",
                    isStreaming = isStreaming || finalBitmap != null
                )
            } else {
                handleProviderUnavailable(now)
                RemoteFrameResult()
            }
        } catch (e: Exception) {
            handleProviderUnavailable(now, e.message)
            RemoteFrameResult()
        }
    }

    private fun handleProviderUnavailable(nowMs: Long, errorMsg: String? = null) {
        consecutiveErrors++
        isProviderAvailable = false
        val backoffMs = (consecutiveErrors * 250L).coerceIn(250L, 2000L)
        nextProviderCheckMs = nowMs + backoffMs

        if (consecutiveErrors == 1 || consecutiveErrors % 15 == 0) {
            Log.d(TAG, "[FRAME_PROVIDER] provider checking (errors=$consecutiveErrors, retryIn=${backoffMs}ms, error=${errorMsg ?: "null response"})")
        }
    }

    /**
     * Reports a diagnostic milestone from the target process back to Zesto across all available IPC tiers.
     */
    fun reportMilestone(stage: String, message: String) {
        val pkg = attachedPackageName
        Log.i(TAG, "[$stage] $message (target: $pkg)")

        // Tier 1: Local in-memory
        ZestoFrameBridge.reportExternalMilestone(stage, pkg, message)

        // Tier 2: Unix domain socket
        try {
            ZestoIpcSocketClient.reportMilestone(stage, pkg, message)
        } catch (_: Throwable) {}

        // Tier 4: ContentProvider
        val context = getTargetContext() ?: return
        try {
            val extras = Bundle().apply {
                putString("package_name", pkg)
                putString("message", message)
                putString("stage", stage)
            }
            context.contentResolver.call(PROVIDER_URI, "reportMilestone", stage, extras)
        } catch (_: Exception) {}
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
