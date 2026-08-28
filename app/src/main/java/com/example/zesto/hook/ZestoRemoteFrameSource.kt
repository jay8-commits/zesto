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
        // Tier 1: Direct in-process in-memory bridge check (Host process only)
        // -------------------------------------------------------------
        val isHostProcess = attachedPackageName == "com.aistudio.zesto.vcam" || 
                            attachedPackageName == "com.example.zesto"
        if (isHostProcess) {
            val localFrame = ZestoFrameBridge.consumeLatestFrame()
            if (localFrame.bitmap != null && !localFrame.bitmap.isRecycled && localFrame.sourceMode == com.example.zesto.frame.FrameSourceMode.RTSP) {
                isProviderAvailable = true
                consecutiveErrors = 0
                if (localFrame.frameId == 1L || localFrame.frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                    lastIpcLogMs = now
                    Log.i(TAG, "[REMOTE_FRAME_RECEIVED] package=$attachedPackageName frameId=${localFrame.frameId} res=${localFrame.width}x${localFrame.height} sourceMode=${localFrame.sourceMode} health=FRAME_INJECTION_ACTIVE transport=IN_PROCESS_BRIDGE")
                    Log.i(TAG, "[IPC_TIER_LOCAL] Direct bridge frameId=${localFrame.frameId} res=${localFrame.width}x${localFrame.height}")
                }
                return RemoteFrameResult(
                    frameId = localFrame.frameId,
                    bitmap = localFrame.bitmap,
                    width = localFrame.width,
                    height = localFrame.height,
                    healthState = "FRAME_INJECTION_ACTIVE",
                    isStreaming = true
                )
            }
        }

        var candidateHealthState = if (com.example.zesto.ipc.ZestoBinderClient.isConnected()) "BINDER_CONNECTED_WAITING_FRAME" else "BINDER_CONNECTING"
        var candidateIsStreaming = false
        val context = getTargetContext()

        // -------------------------------------------------------------
        // Tier 2: Android Binder & SharedMemory IPC (Primary cross-UID transport)
        // -------------------------------------------------------------
        try {
            val binderResult = com.example.zesto.ipc.ZestoBinderClient.fetchLatestFrame(context)
            if (binderResult != null) {
                isProviderAvailable = true
                consecutiveErrors = 0
                candidateHealthState = if (binderResult.healthState.isNotEmpty() && binderResult.healthState != "NO_FRAME") binderResult.healthState else "BRIDGE_NO_FRAME"
                candidateIsStreaming = binderResult.isStreaming

                if (binderResult.bitmap != null && !binderResult.bitmap.isRecycled) {
                    if (binderResult.frameId == 1L || binderResult.frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                        lastIpcLogMs = now
                        Log.i(TAG, "[REMOTE_FRAME_RECEIVED] package=$attachedPackageName frameId=${binderResult.frameId} res=${binderResult.width}x${binderResult.height} sourceMode=RTSP health=FRAME_INJECTION_ACTIVE transport=BINDER_SHARED_MEMORY")
                        Log.i(TAG, "[IPC_TIER_BINDER_SHM] Received frameId=${binderResult.frameId} res=${binderResult.width}x${binderResult.height} hasBitmap=true target=$attachedPackageName")
                    }
                    return binderResult.copy(healthState = "FRAME_INJECTION_ACTIVE", isStreaming = true)
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Binder IPC attempt note: ${e.message}")
        }

        // -------------------------------------------------------------
        // Tier 3: Linux Abstract Unix Domain Socket / TCP
        // -------------------------------------------------------------
        try {
            val socketResult = ZestoIpcSocketClient.fetchLatestFrame()
            if (socketResult != null) {
                isProviderAvailable = true
                consecutiveErrors = 0
                candidateHealthState = if (socketResult.healthState.isNotEmpty() && socketResult.healthState != "NO_FRAME") socketResult.healthState else "BRIDGE_NO_FRAME"
                candidateIsStreaming = socketResult.isStreaming

                if (socketResult.bitmap != null && !socketResult.bitmap.isRecycled) {
                    if (socketResult.frameId == 1L || socketResult.frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                        lastIpcLogMs = now
                        Log.i(TAG, "[REMOTE_FRAME_RECEIVED] package=$attachedPackageName frameId=${socketResult.frameId} res=${socketResult.width}x${socketResult.height} sourceMode=RTSP health=FRAME_INJECTION_ACTIVE transport=UNIX_DOMAIN_SOCKET")
                        Log.i(TAG, "[IPC_TIER_SOCKET] Received frameId=${socketResult.frameId} res=${socketResult.width}x${socketResult.height} hasBitmap=true target=$attachedPackageName")
                    }
                    return socketResult.copy(healthState = "FRAME_INJECTION_ACTIVE", isStreaming = true)
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Socket IPC attempt note: ${e.message}")
        }

        // -------------------------------------------------------------
        // Tier 4: Atomic Dual-Buffer Shared Memory / File Bridge
        // -------------------------------------------------------------
        try {
            val shmResult = ZestoSharedMemoryBridge.readLatestFrame()
            if (shmResult != null) {
                isProviderAvailable = true
                consecutiveErrors = 0
                if (candidateHealthState == "BINDER_CONNECTING" || candidateHealthState == "SOCKET_UNREACHABLE") {
                    candidateHealthState = if (shmResult.healthState.isNotEmpty() && shmResult.healthState != "NO_FRAME") shmResult.healthState else "BRIDGE_NO_FRAME"
                }

                if (shmResult.bitmap != null && !shmResult.bitmap.isRecycled) {
                    if (shmResult.frameId == 1L || shmResult.frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                        lastIpcLogMs = now
                        Log.i(TAG, "[REMOTE_FRAME_RECEIVED] package=$attachedPackageName frameId=${shmResult.frameId} res=${shmResult.width}x${shmResult.height} sourceMode=RTSP health=FRAME_INJECTION_ACTIVE transport=SHARED_MEMORY")
                        Log.i(TAG, "[IPC_TIER_SHARED_MEM] Received frameId=${shmResult.frameId} res=${shmResult.width}x${shmResult.height} hasBitmap=true target=$attachedPackageName")
                    }
                    return shmResult.copy(healthState = "FRAME_INJECTION_ACTIVE", isStreaming = true)
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Shared memory IPC attempt note: ${e.message}")
        }

        // -------------------------------------------------------------
        // Tier 5: ContentProvider ContentResolver IPC fallback
        // -------------------------------------------------------------
        if (context == null) {
            val reportedHealth = if (candidateHealthState != "BINDER_CONNECTING") candidateHealthState else "PROVIDER_NOT_STARTED"
            return RemoteFrameResult(
                frameId = 0L,
                bitmap = null,
                width = 1080,
                height = 1920,
                healthState = reportedHealth,
                isStreaming = candidateIsStreaming
            )
        }

        if (!isProviderAvailable && now < nextProviderCheckMs) {
            val reportedHealth = if (candidateHealthState != "SOCKET_UNREACHABLE") candidateHealthState else "PROVIDER_UNREACHABLE"
            return RemoteFrameResult(
                frameId = 0L,
                bitmap = null,
                width = 1080,
                height = 1920,
                healthState = reportedHealth,
                isStreaming = candidateIsStreaming
            )
        }

        return try {
            if (now - lastIpcLogMs > 4000L) {
                Log.i(TAG, "[PROVIDER_CONNECT_ATTEMPT] package=$attachedPackageName target=$attachedPackageName transport=CONTENT_PROVIDER uri=$PROVIDER_URI")
            }

            var bundle: Bundle? = null
            try {
                bundle = context.contentResolver.call(PROVIDER_URI, "getLatestFrame", null, null)
            } catch (e: Throwable) {
                if (now - lastIpcLogMs > 4000L) {
                    Log.w(TAG, "[PROVIDER_CONNECT_FAILED] package=$attachedPackageName transport=CONTENT_PROVIDER error=${e.message}")
                }
            }

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

                val effectiveHealth = if (finalBitmap != null) {
                    "FRAME_INJECTION_ACTIVE"
                } else if (healthState == "FRAME_ACTIVE") {
                    "FRAME_ACTIVE"
                } else if (isStreaming) {
                    "RTSP_CONNECTED_NO_FRAMES"
                } else {
                    "BRIDGE_NO_FRAME"
                }

                if (frameId == 1L || frameId % 60L == 0L || (now - lastIpcLogMs) > 2000L) {
                    lastIpcLogMs = now
                    Log.i(TAG, "[PROVIDER_CONNECT_SUCCESS] package=$attachedPackageName frameId=$frameId res=${width}x${height} sourceMode=RTSP health=$effectiveHealth hasBitmap=${finalBitmap != null} transport=CONTENT_PROVIDER")
                    if (finalBitmap != null) {
                        Log.i(TAG, "[REMOTE_FRAME_RECEIVED] package=$attachedPackageName frameId=$frameId res=${width}x${height} sourceMode=RTSP health=$effectiveHealth transport=CONTENT_PROVIDER")
                    }
                    Log.i(TAG, "[IPC_TIER_CONTENT_PROVIDER] frameId=$frameId hasBitmap=${finalBitmap != null} target=$attachedPackageName")
                }

                RemoteFrameResult(
                    frameId = frameId,
                    bitmap = finalBitmap,
                    width = width,
                    height = height,
                    healthState = effectiveHealth,
                    isStreaming = isStreaming || finalBitmap != null
                )
            } else {
                handleProviderUnavailable(now)
                val fallbackState = if (candidateHealthState != "SOCKET_UNREACHABLE") candidateHealthState else "PROVIDER_UNREACHABLE"
                RemoteFrameResult(
                    frameId = 0L,
                    bitmap = null,
                    width = 1080,
                    height = 1920,
                    healthState = fallbackState,
                    isStreaming = candidateIsStreaming
                )
            }
        } catch (e: Exception) {
            handleProviderUnavailable(now, e.message)
            val fallbackState = if (candidateHealthState != "SOCKET_UNREACHABLE") candidateHealthState else "PROVIDER_UNREACHABLE"
            RemoteFrameResult(
                frameId = 0L,
                bitmap = null,
                width = 1080,
                height = 1920,
                healthState = fallbackState,
                isStreaming = candidateIsStreaming
            )
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
