package com.example.zesto.ipc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import com.example.zesto.hook.RemoteFrameResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private class ZestoBinderServiceConnection : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.i("ZestoBinderClient", "[BINDER_CLIENT_CONNECTED] via=bindService name=$name uid=${Process.myUid()} pid=${Process.myPid()}")
        ZestoBinderClient.handleBinderConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.w("ZestoBinderClient", "Zesto Binder service disconnected: $name")
        ZestoBinderClient.handleBinderDisconnected()
    }
}

private class ZestoBinderBroadcastReceiver(
    private val latch: CountDownLatch,
    private val onBinderReceived: (IBinder?) -> Unit
) : BroadcastReceiver() {
    override fun onReceive(c: Context?, rIntent: Intent?) {
        val extras = getResultExtras(true)
        val binder = extras?.getBinder(ZestoFrameReceiver.EXTRA_BINDER_HANDLE)
        onBinderReceived(binder)
        latch.countDown()
    }
}

/**
 * Cross-Process Android Binder & SharedMemory IPC Client for hooked target processes (e.g. OpenCamera).
 *
 * Connects to Zesto host via Android Binder, maps the shared ashmem/memfd frame buffer,
 * and extracts frames in < 0.1ms without network permissions (INTERNET) or filesystem dependencies.
 */
object ZestoBinderClient {
    private const val TAG = "ZestoBinderClient"

    private const val HEADER_MAGIC = 0x5A455354 // "ZEST"
    private const val HEADER_RESERVED = 64
    private const val SLOT_SIZE = 4 * 1024 * 1024 - 128 // ~4 MB

    private val CANDIDATE_PACKAGES = listOf(
        "com.aistudio.zesto.vcam",
        "com.example.zesto"
    )

    private var zestoBinder: IBinder? = null
    private var sharedMemory: SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    private val isConnecting = AtomicBoolean(false)
    private var nextConnectAttemptMs = 0L
    private var lastSuccessLogMs = 0L
    private var cachedBitmap: Bitmap? = null
    private var cachedFrameId: Long = 0L

    private var clientLastConsumedFrameId: Long = 0L
    private var clientLastConsumedSeq: Long = 0L
    private var clientTotalReadsCount: Long = 0L
    private var clientStaleReadsCount: Long = 0L

    private val serviceConnection = ZestoBinderServiceConnection()

    fun handleBinderDisconnected() {
        zestoBinder = null
        readBuffer = null
    }

    fun isConnected(): Boolean = zestoBinder?.isBinderAlive == true && readBuffer != null

    /**
     * Connects to Zesto host via Ordered Broadcast or Service Binding.
     */
    fun ensureConnected(context: Context?): Boolean {
        if (isConnected()) return true

        val now = System.currentTimeMillis()
        if (now < nextConnectAttemptMs) return false
        if (context == null) return false

        if (!isConnecting.compareAndSet(false, true)) return false

        val myUid = Process.myUid()
        val myPid = Process.myPid()
        Log.i(TAG, "[BINDER_CLIENT_CONNECT_ATTEMPT] targetPackage=${context.packageName} uid=$myUid pid=$myPid")

        Thread(Runnable {
            try {
                // Strategy 1: Ordered Broadcast (Bypasses Android 11-15 Package Visibility filters)
                for (pkg in CANDIDATE_PACKAGES) {
                    val intent = Intent(ZestoFrameReceiver.ACTION_GET_BINDER)
                    intent.component = ComponentName(pkg, "com.example.zesto.ipc.ZestoFrameReceiver")
                    intent.putExtra("caller_package", context.packageName)
                    intent.putExtra("caller_uid", myUid)
                    intent.putExtra("caller_pid", myPid)

                    val latch = CountDownLatch(1)
                    var receivedBinder: IBinder? = null

                    context.sendOrderedBroadcast(
                        intent,
                        null,
                        ZestoBinderBroadcastReceiver(latch) { binder ->
                            receivedBinder = binder
                        },
                        null,
                        Activity.RESULT_OK,
                        null,
                        null
                    )

                    try {
                        latch.await(400, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {}

                    if (receivedBinder != null && receivedBinder?.isBinderAlive == true) {
                        Log.i(TAG, "[BINDER_CLIENT_CONNECTED] via=orderedBroadcast pkg=$pkg uid=$myUid pid=$myPid")
                        handleBinderConnected(receivedBinder)
                        isConnecting.set(false)
                        return@Runnable
                    }
                }

                // Strategy 2: Explicit Service Binding
                for (pkg in CANDIDATE_PACKAGES) {
                    try {
                        val bindIntent = Intent()
                        bindIntent.component = ComponentName(pkg, "com.example.zesto.ipc.ZestoFrameBinderService")
                        val bound = context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                        if (bound) {
                            Log.d(TAG, "bindService initiated for $pkg")
                            break
                        }
                    } catch (e: Throwable) {
                        Log.d(TAG, "bindService attempt error for $pkg: ${e.message}")
                    }
                }

                nextConnectAttemptMs = System.currentTimeMillis() + 1000L
            } catch (e: Throwable) {
                Log.w(TAG, "[BINDER_CONNECT_ERROR] ${e.message}")
                nextConnectAttemptMs = System.currentTimeMillis() + 1500L
            } finally {
                isConnecting.set(false)
            }
        }, "zesto-binder-connector").start()

        return isConnected()
    }

    private fun handleBinderConnected(binder: IBinder?) {
        if (binder == null || !binder.isBinderAlive) return
        zestoBinder = binder

        val myUid = Process.myUid()
        val myPid = Process.myPid()

        try {
            // Obtain SharedMemory handle via Binder transaction
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.example.zesto.ipc.IZestoFrameService")
                binder.transact(ZestoFrameBinder.TRANSACTION_GET_SHM_HANDLE, data, reply, 0)
                reply.readException()
                val hasShm = reply.readInt()
                if (hasShm != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val shm = SharedMemory.CREATOR.createFromParcel(reply)
                    if (shm != null) {
                        val buf = shm.mapReadOnly()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        sharedMemory = shm
                        readBuffer = buf
                        Log.i(TAG, "[FRAME_HANDLE_RECEIVED] shmSize=${shm.size} uid=$myUid pid=$myPid transport=BINDER_SHARED_MEMORY")
                    }
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to map SharedMemory from Binder: ${e.message}", e)
        }
    }

    /**
     * Reads the latest video frame directly from the mapped SharedMemory buffer.
     */
    @Synchronized
    fun fetchLatestFrame(context: Context?): RemoteFrameResult? {
        if (!isConnected()) {
            ensureConnected(context)
            if (!isConnected()) return null
        }

        val buf = readBuffer ?: return null

        try {
            // Read active slot index and newest available sequence from offset 0
            buf.position(0)
            val activeSlot = buf.getInt()
            val globalSeq = buf.getLong()
            if (activeSlot != 0 && activeSlot != 1) {
                return null
            }

            val slotOffset = HEADER_RESERVED + (activeSlot * SLOT_SIZE)
            buf.position(slotOffset)

            val magic = buf.getInt()
            if (magic != HEADER_MAGIC) {
                return null
            }

            val headerSeq = buf.getLong()
            val frameId = buf.getLong()
            val timestampUs = buf.getLong()
            val width = buf.getInt()
            val height = buf.getInt()
            val isStreamingInt = buf.getInt()
            val payloadSize = buf.getInt()

            if (payloadSize <= 0 || payloadSize > (SLOT_SIZE - 256)) {
                return null
            }

            clientTotalReadsCount++
            val isNewFrame = (frameId != clientLastConsumedFrameId || headerSeq != clientLastConsumedSeq)

            var bitmap = cachedBitmap
            if (!isNewFrame && bitmap != null && !bitmap.isRecycled) {
                clientStaleReadsCount++
                val now = System.currentTimeMillis()
                if (now - lastSuccessLogMs > 2000L || clientTotalReadsCount % 60L == 0L) {
                    lastSuccessLogMs = now
                    Log.i(TAG, "[FRAME_HANDLE_READ] readCount=$clientTotalReadsCount frameId=$frameId seq=$headerSeq isNewFrame=false (slot=$activeSlot bytes=$payloadSize res=${width}x${height} newestSeq=$globalSeq staleReads=$clientStaleReadsCount)")
                }
                return RemoteFrameResult(
                    frameId = frameId,
                    sequence = headerSeq,
                    isNewFrame = false,
                    bitmap = bitmap,
                    width = width,
                    height = height,
                    healthState = "FRAME_INJECTION_ACTIVE",
                    isStreaming = isStreamingInt == 1
                )
            }

            val payload = ByteArray(payloadSize)
            buf.get(payload)

            val trailerSeq = buf.getLong()

            // Verify atomic read: headerSeq must match trailerSeq and be valid
            if (headerSeq != trailerSeq || headerSeq <= 0L) {
                return null
            }

            if (bitmap == null || bitmap.isRecycled || frameId != cachedFrameId) {
                try {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = 1
                    }
                    val decoded = BitmapFactory.decodeByteArray(payload, 0, payloadSize, opts)
                    if (decoded != null) {
                        bitmap = decoded
                        cachedBitmap = decoded
                        cachedFrameId = frameId
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error decoding frame $frameId: ${e.message}")
                }
            }

            if (bitmap == null || bitmap.isRecycled) {
                return null
            }

            clientLastConsumedFrameId = frameId
            clientLastConsumedSeq = headerSeq

            val now = System.currentTimeMillis()
            if (frameId == 1L || frameId % 30L == 0L || isNewFrame || (now - lastSuccessLogMs) > 2000L) {
                lastSuccessLogMs = now
                Log.i(TAG, "[FRAME_HANDLE_READ] readCount=$clientTotalReadsCount frameId=$frameId seq=$headerSeq isNewFrame=true (slot=$activeSlot bytes=$payloadSize res=${width}x${height} newestSeq=$globalSeq staleReads=$clientStaleReadsCount)")
            }

            return RemoteFrameResult(
                frameId = frameId,
                sequence = headerSeq,
                isNewFrame = true,
                bitmap = bitmap,
                width = width,
                height = height,
                healthState = "FRAME_INJECTION_ACTIVE",
                isStreaming = isStreamingInt == 1
            )
        } catch (e: Throwable) {
            Log.d(TAG, "Error reading from SharedMemory: ${e.message}")
            return null
        }
    }
}
