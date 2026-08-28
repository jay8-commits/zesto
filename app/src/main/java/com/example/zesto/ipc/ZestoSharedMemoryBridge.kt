package com.example.zesto.ipc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.zesto.hook.RemoteFrameResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Atomic Dual-Buffer Shared Memory / File Bridge for ultra-fast lock-free cross-process frame sharing.
 *
 * Uses two ping-pong slots with atomic sequence verification (seq_start == seq_end) to guarantee
 * tear-free, zero-copy/low-overhead frame reads between Zesto and hooked camera apps.
 */
object ZestoSharedMemoryBridge {
    private const val TAG = "ZestoSharedMemory"

    private const val SLOT_SIZE = 2 * 1024 * 1024 // 2 MB per slot
    private const val FILE_SIZE = 4 * 1024 * 1024L // 4 MB total
    private const val HEADER_MAGIC = 0x5A455354 // "ZEST"

    private val CANDIDATE_PATHS = listOf(
        "/data/local/tmp/zesto_frame.bin",
        "/sdcard/Android/data/com.example.zesto/files/zesto_frame.bin"
    )

    private var activeFilePath: String? = null
    private var writeChannel: FileChannel? = null
    private var writeBuffer: ByteBuffer? = null

    private var readChannel: FileChannel? = null
    private var readBuffer: ByteBuffer? = null
    private var activeSlotIndex = 0
    private var globalSeqCounter = 2L // Start at even number

    @Synchronized
    fun initServer(customPath: String? = null) {
        val path = customPath ?: selectBestPath()
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            if (raf.length() < FILE_SIZE) {
                raf.setLength(FILE_SIZE)
            }
            try {
                file.setReadable(true, false)
                file.setWritable(true, false)
            } catch (_: Exception) {}

            writeChannel = raf.channel
            writeBuffer = writeChannel?.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE)
            writeBuffer?.order(ByteOrder.LITTLE_ENDIAN)
            activeFilePath = path
            Log.i(TAG, "[SHARED_MEM_SERVER_INIT] Shared memory buffer initialized at '$path'")
        } catch (e: Exception) {
            Log.d(TAG, "Cannot init shared memory server at '$path': ${e.message}")
        }
    }

    private fun selectBestPath(): String {
        for (p in CANDIDATE_PATHS) {
            try {
                val f = File(p)
                f.parentFile?.mkdirs()
                if (f.parentFile?.canWrite() == true || f.canWrite()) {
                    return p
                }
            } catch (_: Exception) {}
        }
        return CANDIDATE_PATHS.first()
    }

    /**
     * Writes the latest decoded frame to the inactive slot and updates the active slot pointer.
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
        if (writeBuffer == null) {
            initServer()
        }
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
        val slotOffset = targetSlot * SLOT_SIZE

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

        // Atomically update header slot pointer
        buf.position(0)
        buf.putInt(targetSlot)

        activeSlotIndex = targetSlot
    }

    /**
     * Reads the latest complete frame from the shared memory buffer in the target process.
     */
    @Synchronized
    fun readLatestFrame(): RemoteFrameResult? {
        if (readBuffer == null) {
            for (p in CANDIDATE_PATHS) {
                try {
                    val f = File(p)
                    if (f.exists() && f.length() >= FILE_SIZE) {
                        val raf = RandomAccessFile(f, "r")
                        readChannel = raf.channel
                        readBuffer = readChannel?.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE)
                        readBuffer?.order(ByteOrder.LITTLE_ENDIAN)
                        activeFilePath = p
                        Log.i(TAG, "[SHARED_MEM_CLIENT_INIT] Attached to shared memory buffer at '$p'")
                        break
                    }
                } catch (_: Exception) {}
            }
        }

        val buf = readBuffer ?: return null

        try {
            buf.position(0)
            val slot = buf.getInt()
            if (slot != 0 && slot != 1) return null

            val slotOffset = slot * SLOT_SIZE
            buf.position(slotOffset)

            val magic = buf.getInt()
            if (magic != HEADER_MAGIC) return null

            val seqStart = buf.getLong()
            val frameId = buf.getLong()
            val timestampUs = buf.getLong()
            val width = buf.getInt()
            val height = buf.getInt()
            val isStreamingInt = buf.getInt()
            val payloadSize = buf.getInt()

            if (payloadSize <= 0 || payloadSize > (SLOT_SIZE - 256)) return null

            val payloadBytes = ByteArray(payloadSize)
            buf.get(payloadBytes)

            val seqEnd = buf.getLong()

            // Verify tear-free consistency: seqStart + 1 == seqEnd
            if (seqStart + 1L != seqEnd || (seqEnd % 2L != 0L)) {
                return null // Incomplete write detected, skip frame
            }

            val bitmap = BitmapFactory.decodeByteArray(payloadBytes, 0, payloadSize) ?: return null

            return RemoteFrameResult(
                frameId = frameId,
                bitmap = bitmap,
                width = if (width > 0) width else 1080,
                height = if (height > 0) height else 1920,
                healthState = "FRAME_ACTIVE",
                isStreaming = isStreamingInt == 1
            )
        } catch (_: Exception) {
            return null
        }
    }
}
