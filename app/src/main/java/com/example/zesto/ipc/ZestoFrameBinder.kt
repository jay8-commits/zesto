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
    const val SHM_SIZE = 4 * 1024 * 1024 // 4 MB
    const val SLOT_SIZE = 2 * 1024 * 1024 // 2 MB per slot

    private var sharedMemory: SharedMemory? = null
    private var writeBuffer: ByteBuffer? = null
    private var activeSlotIndex = 0
    private var globalSeqCounter = 2L

    private val binderInstance = object : Binder() {
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
                        Log.i(TAG, "[FRAME_HANDLE_PUBLISHED] callerUid=$callerUid callerPid=$callerPid size=$SHM_SIZE")
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

    fun getBinder(): IBinder {
        ensureSharedMemoryInitialized()
        return binderInstance
    }

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

        var jpegBytes: ByteArray? = null
        if (bitmap != null && !bitmap.isRecycled) {
            try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                jpegBytes = baos.toByteArray()
            } catch (_: Throwable) {}
        } else if (rawBytes != null) {
            jpegBytes = rawBytes
        }

        if (jpegBytes == null || jpegBytes.isEmpty() || jpegBytes.size > (SLOT_SIZE - 256)) {
            return
        }

        val targetSlot = 1 - activeSlotIndex
        val slotOffset = 4 + (targetSlot * SLOT_SIZE)

        // Increment sequence to ODD number to indicate write-in-progress
        val seqStart = ++globalSeqCounter
        val payloadSize = jpegBytes.size

        buf.position(slotOffset)
        buf.putInt(HEADER_MAGIC)
        buf.putLong(seqStart)
        buf.putLong(frameId)
        buf.putLong(timestampUs)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(if (isStreaming) 1 else 0)
        buf.putInt(payloadSize)
        buf.put(jpegBytes)

        // Write closing sequence number (EVEN number = write complete)
        val seqEnd = ++globalSeqCounter
        buf.putLong(seqEnd)

        // Atomically update header slot pointer (at offset 0)
        buf.position(0)
        buf.putInt(targetSlot)

        activeSlotIndex = targetSlot

        if (frameId == 1L || frameId % 60L == 0L) {
            Log.i(TAG, "[FRAME_HANDLE_PUBLISHED] frameId=$frameId bytes=$payloadSize res=${width}x${height} slot=$targetSlot")
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
