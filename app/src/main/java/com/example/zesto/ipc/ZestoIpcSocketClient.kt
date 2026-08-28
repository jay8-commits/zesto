package com.example.zesto.ipc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.example.zesto.hook.RemoteFrameResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * High-performance Linux Abstract Unix Domain Socket IPC client for hooked target processes.
 *
 * Connects directly to the Zesto daemon in the abstract Linux socket namespace, bypassing
 * Android 11-15 package visibility and Binder limitations.
 */
object ZestoIpcSocketClient {
    private const val TAG = "ZestoIpcClient"
    private const val SOCKET_NAME = "zesto_vcam_ipc"

    private var socket: LocalSocket? = null
    private var dataInput: DataInputStream? = null
    private var dataOutput: DataOutputStream? = null

    private var consecutiveErrors = 0
    private var nextConnectAttemptMs = 0L
    private var lastSuccessLogMs = 0L

    @Synchronized
    private fun ensureConnected(): Boolean {
        val now = System.currentTimeMillis()
        if (socket != null && socket?.isConnected == true && dataInput != null && dataOutput != null) {
            return true
        }

        if (now < nextConnectAttemptMs) {
            return false
        }

        closeConnection()

        try {
            val s = LocalSocket()
            val address = LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
            s.connect(address)
            s.soTimeout = 1000

            val input = DataInputStream(s.inputStream)
            val output = DataOutputStream(s.outputStream)

            socket = s
            dataInput = input
            dataOutput = output
            consecutiveErrors = 0
            Log.i(TAG, "[IPC_SOCKET_CONNECTED] Connected to Zesto abstract socket '$SOCKET_NAME'")
            return true
        } catch (e: Exception) {
            consecutiveErrors++
            val backoffMs = (consecutiveErrors * 200L).coerceIn(200L, 2000L)
            nextConnectAttemptMs = now + backoffMs
            if (consecutiveErrors == 1 || consecutiveErrors % 15 == 0) {
                Log.d(TAG, "Cannot connect to Zesto socket (retrying in ${backoffMs}ms): ${e.message}")
            }
            closeConnection()
            return false
        }
    }

    private fun closeConnection() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        dataInput = null
        dataOutput = null
    }

    /**
     * Fetches the latest live frame from Zesto via abstract domain socket.
     * Returns null if socket is not connected or frame is not yet available.
     */
    @Synchronized
    fun fetchLatestFrame(): RemoteFrameResult? {
        if (!ensureConnected()) {
            return null
        }

        val input = dataInput ?: return null
        val output = dataOutput ?: return null

        try {
            output.writeByte(ZestoIpcSocketServer.CMD_GET_LATEST_FRAME.toInt())
            output.flush()

            val magic = input.readInt()
            if (magic != ZestoIpcSocketServer.MAGIC) {
                Log.w(TAG, "Invalid IPC frame magic: 0x${Integer.toHexString(magic)}")
                closeConnection()
                return null
            }

            val version = input.readByte()
            val frameId = input.readLong()
            val timestampUs = input.readLong()
            val width = input.readInt()
            val height = input.readInt()
            val formatByte = input.readByte()
            val healthByte = input.readByte()
            val isStreamingByte = input.readByte()
            val sourceModeByte = input.readByte()
            val payloadSize = input.readInt()

            var decodedBitmap: Bitmap? = null
            if (payloadSize > 0) {
                val payloadBytes = ByteArray(payloadSize)
                input.readFully(payloadBytes)
                try {
                    decodedBitmap = BitmapFactory.decodeByteArray(payloadBytes, 0, payloadSize)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error decoding IPC payload into Bitmap: ${e.message}")
                }
            }

            val healthState = when (healthByte.toInt()) {
                1 -> "FRAME_ACTIVE"
                2 -> "FRAME_STALLED"
                else -> if (decodedBitmap != null) "FRAME_ACTIVE" else "NO_FRAME"
            }

            val isStreaming = isStreamingByte.toInt() == 1 || decodedBitmap != null

            val now = System.currentTimeMillis()
            if (frameId == 1L || frameId % 60L == 0L || (now - lastSuccessLogMs) > 2000L) {
                lastSuccessLogMs = now
                Log.i(TAG, "[IPC_SOCKET_FRAME_RECEIVED] frameId=$frameId mode=$sourceModeByte res=${width}x${height} hasBitmap=${decodedBitmap != null} payload=${payloadSize}B")
            }

            return RemoteFrameResult(
                frameId = frameId,
                bitmap = decodedBitmap,
                width = if (width > 0) width else 1080,
                height = if (height > 0) height else 1920,
                healthState = healthState,
                isStreaming = isStreaming
            )
        } catch (e: Exception) {
            Log.d(TAG, "Socket IPC read error: ${e.message}")
            closeConnection()
            return null
        }
    }

    /**
     * Reports a diagnostic milestone from the target app to Zesto.
     */
    @Synchronized
    fun reportMilestone(stage: String, targetPackage: String, message: String): Boolean {
        if (!ensureConnected()) {
            return false
        }

        val input = dataInput ?: return false
        val output = dataOutput ?: return false

        try {
            output.writeByte(ZestoIpcSocketServer.CMD_REPORT_MILESTONE.toInt())

            val stageBytes = stage.toByteArray(Charsets.UTF_8)
            output.writeShort(stageBytes.size)
            output.write(stageBytes)

            val pkgBytes = targetPackage.toByteArray(Charsets.UTF_8)
            output.writeShort(pkgBytes.size)
            output.write(pkgBytes)

            val msgBytes = message.toByteArray(Charsets.UTF_8)
            output.writeShort(msgBytes.size)
            output.write(msgBytes)
            output.flush()

            val ack = input.readByte()
            return ack.toInt() == 0
        } catch (e: Exception) {
            closeConnection()
            return false
        }
    }
}
