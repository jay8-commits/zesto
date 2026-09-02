package com.example.zesto.ipc

import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cross-Process Android Binder & SharedMemory frame provider for Zesto.
 *
 * Provides high-speed, zero-copy frame sharing across Android 11-15 application UIDs.
 * Avoids Binder transaction limits by using SharedMemory (ashmem/memfd) file descriptors
 * while using Binder for secure handle exchange.
 */
object ZestoFrameBinder {
    private const val TAG = "ZestoFrameBinder"

    const val TRANSACTION_GET_SHM_HANDLE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_GET_METADATA = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 3

    const val HEADER_MAGIC = 0x5A455354 // "ZEST"
    const val SHM_SIZE = 8 * 1024 * 1024 // 8 MB
    const val HEADER_RESERVED = 64
    const val SLOT_SIZE = 4 * 1024 * 1024 - 128 // ~4 MB per slot

    private var sharedMemory: SharedMemory? = null
    private var writeBuffer: ByteBuffer? = null
    private var activeSlotIndex = 0
    private val globalSeqCounter = java.util.concurrent.atomic.AtomicLong(10L)

    private val publishCounter = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile private var latestPublishedFrameId: Long = 0L
    @Volatile private var latestPublishedSeq: Long = 0L

    private class ZestoFrameBinderImpl : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val callerUid = Binder.getCallingUid()
            val callerPid = Binder.getCallingPid()

            when (code) {
                TRANSACTION_GET_SHM_HANDLE -> {
                    data.enforceInterface("com.example.zesto.ipc.IZestoFrameService")
                    reply?.writeNoException()

                    ensureSharedMemoryInitialized()
                    val shm = sharedMemory
                    if (shm != null && reply != null) {
                        reply.writeInt(1) // SHM present
                        shm.writeToParcel(reply, 0)
                        Log.i(TAG, "[FRAME_HANDLE_PUBLISHED] callerUid=$callerUid callerPid=$callerPid size=$SHM_SIZE activeSlot=$activeSlotIndex latestSeq=${globalSeqCounter.get()} latestFrameId=$latestPublishedFrameId")
                    } else {
                        reply?.writeInt(0)
                        Log.w(TAG, "SharedMemory not available to publish to callerUid=$callerUid")
                    }
                    return true
                }

                TRANSACTION_GET_METADATA -> {
                    data.enforceInterface("com.example.zesto.ipc.IZestoFrameService")
                    reply?.writeNoException()
                    val bundle = Bundle()
                    bundle.putInt("shm_size", SHM_SIZE)
                    bundle.putInt("slot_size", SLOT_SIZE)
                    bundle.putInt("header_magic", HEADER_MAGIC)
                    bundle.putInt("server_uid", Process.myUid())
                    bundle.putInt("server_pid", Process.myPid())
                    bundle.putLong("latest_frame_id", latestPublishedFrameId)
                    bundle.putLong("latest_seq", latestPublishedSeq)
                    reply?.writeBundle(bundle)
                    return true
                }

                TRANSACTION_PING -> {
                    data.enforceInterface("com.example.zesto.ipc.IZestoFrameService")
                    reply?.writeNoException()
                    reply?.writeInt(0) // OK
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val binderInstance = ZestoFrameBinderImpl()

    fun getBinder(): IBinder {
        ensureSharedMemoryInitialized()
        return binderInstance
    }

    fun getLatestPublishedFrameId(): Long = latestPublishedFrameId
    fun getLatestPublishedSeq(): Long = latestPublishedSeq

    @Synchronized
    fun ensureSharedMemoryInitialized() {
        if (sharedMemory != null && writeBuffer != null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val shm = SharedMemory.create("zesto_frame_shm", SHM_SIZE)
                val buf = shm.mapReadWrite()
                buf.order(ByteOrder.LITTLE_ENDIAN)

                // Initialize header slot pointer to 0
                buf.position(0)
                buf.putInt(0)
                buf.putLong(globalSeqCounter.get())

                sharedMemory = shm
                writeBuffer = buf

                val uid = Process.myUid()
                val pid = Process.myPid()
                Log.i(TAG, "[BINDER_SERVER_STARTED] uid=$uid pid=$pid shmSize=$SHM_SIZE transport=BINDER_SHARED_MEMORY")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize SharedMemory for Binder: ${e.message}", e)
        }
    }

    /**
     * Writes the latest decoded RTSP frame into the ping-pong slot buffer.
     */
    @Synchronized
    fun writeFrame(
        frameId: Long,
        timestampUs: Long,
        width: Int,
        height: Int,
        bitmap: Bitmap?,
        rawBytes: ByteArray?,
        isStreaming: Boolean,
        healthState: String
    ) {
        ensureSharedMemoryInitialized()
        val buf = writeBuffer ?: return

        val jpegBytes = rawBytes ?: if (bitmap != null && !bitmap.isRecycled) {
            try {
                val baos = ByteArrayOutputStream(width * height / 4)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                baos.toByteArray()
            } catch (_: Throwable) { null }
        } else null

        if (jpegBytes == null || jpegBytes.isEmpty() || jpegBytes.size > (SLOT_SIZE - 256)) {
            if (jpegBytes != null && jpegBytes.size > (SLOT_SIZE - 256)) {
                Log.w(TAG, "[SHM_FRAME_DROPPED_OVERSIZED] frameId=$frameId bytes=${jpegBytes.size} max=${SLOT_SIZE - 256}")
            }
            return
        }

        val targetSlot = 1 - activeSlotIndex
        val slotOffset = HEADER_RESERVED + (targetSlot * SLOT_SIZE)
        val currentSeq = globalSeqCounter.incrementAndGet()
        val payloadSize = jpegBytes.size

        buf.position(slotOffset)
        buf.putInt(HEADER_MAGIC)
        buf.putLong(currentSeq)
        buf.putLong(frameId)
        buf.putLong(timestampUs)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(if (isStreaming) 1 else 0)
        buf.putInt(payloadSize)
        buf.put(jpegBytes)
        buf.putLong(currentSeq) // trailer sequence matches header sequence

        // Atomically publish active slot pointer and global sequence at offset 0
        buf.position(0)
        buf.putInt(targetSlot)
        buf.putLong(currentSeq)

        activeSlotIndex = targetSlot
        latestPublishedFrameId = frameId
        latestPublishedSeq = currentSeq
        val pubCount = publishCounter.incrementAndGet()

        Log.i(TAG, "[IPC_PUBLISH] seq=$currentSeq shmIndex=$targetSlot size=$payloadSize")
        if (pubCount == 1L || pubCount % 30L == 0L || frameId == 1L || frameId % 30L == 0L) {
            Log.i(TAG, "[FRAME_HANDLE_PUBLISHED] publishCount=$pubCount frameId=$frameId seq=$currentSeq activeSlot=$targetSlot bytes=$payloadSize res=${width}x${height} latestPublishedFrameId=$latestPublishedFrameId latestPublishedSeq=$latestPublishedSeq")
        }
    }

    fun release() {
        try {
            sharedMemory?.close()
        } catch (_: Throwable) {}
        sharedMemory = null
        writeBuffer = null
    }
}
