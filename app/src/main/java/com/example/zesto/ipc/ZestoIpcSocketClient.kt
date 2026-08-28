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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * High-performance cross-process IPC client for hooked target processes.
 *
 * Connects via Localhost TCP (127.0.0.1:28750) or Linux Abstract Unix Domain Socket,
 * bypassing Android 11-15 package visibility (<queries>) and SELinux domain restrictions.
 */
object ZestoIpcSocketClient {
    private const val TAG = "ZestoIpcClient"
    private const val SOCKET_NAME = "zesto_vcam_ipc"
    private const val TCP_PORT = 28750

    private var localSocket: LocalSocket? = null
    private var tcpSocket: Socket? = null
    private var dataInput: DataInputStream? = null
    private var dataOutput: DataOutputStream? = null

    private var consecutiveErrors = 0
    private var nextConnectAttemptMs = 0L
    private var lastSuccessLogMs = 0L

    @Synchronized
    private fun ensureConnected(): Boolean {
        val now = System.currentTimeMillis()
        val isConnected = (tcpSocket?.isConnected == true && !tcpSocket!!.isClosed) ||
                          (localSocket?.isConnected == true)
        if (isConnected && dataInput != null && dataOutput != null) {
            return true
        }

        if (now < nextConnectAttemptMs) {
            return false
        }

        closeConnection()

        // 1. Primary Transport: Localhost TCP (127.0.0.1:28750)
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()
        try {
            Log.i(TAG, "[TCP_CONNECT_ATTEMPT] address=127.0.0.1 port=$TCP_PORT uid=$myUid pid=$myPid")
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = 1000
            s.connect(InetSocketAddress("127.0.0.1", TCP_PORT), 400)

            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(s.getOutputStream())

            tcpSocket = s
            dataInput = input
            dataOutput = output
            consecutiveErrors = 0
            Log.i(TAG, "[TCP_CONNECT_SUCCESS] address=127.0.0.1 port=$TCP_PORT uid=$myUid pid=$myPid")
            Log.i(TAG, "[IPC_SOCKET_CONNECTED] Connected to Zesto TCP loopback on 127.0.0.1:$TCP_PORT (uid=$myUid, pid=$myPid)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "[TCP_CONNECT_FAILED] address=127.0.0.1 port=$TCP_PORT uid=$myUid pid=$myPid error=${e.javaClass.simpleName}: ${e.message}")
            // Fall through to Unix domain socket
        }

        // 2. Secondary Transport: Abstract Unix Domain Socket
        try {
            val s = LocalSocket()
            val address = LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
            s.connect(address)
            s.soTimeout = 1000

            val input = DataInputStream(s.inputStream)
            val output = DataOutputStream(s.outputStream)

            localSocket = s
            dataInput = input
            dataOutput = output
            consecutiveErrors = 0
            Log.i(TAG, "[IPC_SOCKET_CONNECTED] Connected to Zesto abstract socket '$SOCKET_NAME'")
            return true
        } catch (e: Exception) {
            consecutiveErrors++
            val backoffMs = (consecutiveErrors * 150L).coerceIn(100L, 1000L)
            nextConnectAttemptMs = now + backoffMs
            if (consecutiveErrors == 1 || consecutiveErrors % 15 == 0) {
                Log.d(TAG, "Cannot connect to Zesto IPC (retrying in ${backoffMs}ms): ${e.message}")
            }
            closeConnection()
            return false
        }
    }

    private fun closeConnection() {
        try {
            tcpSocket?.close()
        } catch (_: Exception) {}
        tcpSocket = null

        try {
            localSocket?.close()
        } catch (_: Exception) {}
        localSocket = null

        dataInput = null
        dataOutput = null
    }

    /**
     * Fetches the latest live frame from Zesto via IPC.
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
                if (frameId == 1L || frameId % 60L == 0L || (System.currentTimeMillis() - lastSuccessLogMs) > 2000L) {
                    Log.i(TAG, "[TCP_FRAME_RECEIVED] frameId=$frameId bytes=$payloadSize uid=$myUid pid=$myPid")
                }
                try {
                    decodedBitmap = BitmapFactory.decodeByteArray(payloadBytes, 0, payloadSize)
                    if (decodedBitmap != null && (frameId == 1L || frameId % 60L == 0L || (System.currentTimeMillis() - lastSuccessLogMs) > 2000L)) {
                        Log.i(TAG, "[TCP_DECODE_SUCCESS] frameId=$frameId res=${decodedBitmap.width}x${decodedBitmap.height} uid=$myUid pid=$myPid")
                    }
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
