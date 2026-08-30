package com.example.zesto.ipc

import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.example.zesto.frame.FrameHealthState
import com.example.zesto.frame.FrameSourceMode
import com.example.zesto.frame.ZestoFrameBridge
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance cross-process IPC server supporting both Linux Abstract Unix Domain Sockets
 * and Localhost TCP Loopback (127.0.0.1:28750) for zero-latency frame streaming.
 *
 * Localhost TCP bypasses all SELinux cross-UID restrictions, Scoped Storage, and Android 11-15
 * Package Visibility (<queries>) constraints completely.
 */
object ZestoIpcSocketServer {
    private const val TAG = "ZestoIpcServer"
    const val SOCKET_NAME = "zesto_vcam_ipc"
    const val TCP_PORT = 28750

    const val CMD_GET_LATEST_FRAME: Byte = 0x01
    const val CMD_REPORT_MILESTONE: Byte = 0x02
    const val CMD_PING: Byte = 0x03

    const val MAGIC: Int = 0x5A455354 // "ZEST"
    const val PROTOCOL_VERSION: Byte = 0x01

    private val isRunning = AtomicBoolean(false)
    private var localServerSocket: LocalServerSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private val clientExecutor = Executors.newCachedThreadPool()

    private val latestFrameLock = Any()
    @Volatile private var cachedFrameId: Long = 0L
    @Volatile private var cachedTimestampUs: Long = 0L
    @Volatile private var cachedWidth: Int = 1080
    @Volatile private var cachedHeight: Int = 1920
    @Volatile private var cachedFormat: Byte = 0 // 0 = JPEG
    @Volatile private var cachedHealthByte: Byte = 0
    @Volatile private var cachedIsStreaming: Byte = 0
    @Volatile private var cachedSourceModeByte: Byte = 0
    @Volatile private var cachedPayload: ByteArray? = null

    private val serverLogCount = AtomicLong(0)

    fun startServer() {
        if (isRunning.getAndSet(true)) {
            return
        }

        // 1. Start Abstract Unix Domain Socket Server Thread
        Thread({
            try {
                localServerSocket = LocalServerSocket(SOCKET_NAME)
                Log.i(TAG, "[SOCKET_SERVER_STARTED] package=com.example.zesto socket=$SOCKET_NAME transport=UNIX_DOMAIN_SOCKET")
                Log.i(TAG, "[IPC_SERVER_STARTED] Abstract Unix domain socket server listening on '$SOCKET_NAME'")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind abstract local socket '$SOCKET_NAME': ${e.message}")
            }

            while (isRunning.get()) {
                try {
                    val client = localServerSocket?.accept() ?: break
                    clientExecutor.execute {
                        handleLocalClient(client)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.d(TAG, "LocalServerSocket accept error: ${e.message}")
                    }
                    break
                }
            }
        }, "zesto-unix-ipc-server").apply {
            isDaemon = true
            start()
        }

        // 2. Start Localhost TCP Server Thread (127.0.0.1:28750)
        Thread({
            try {
                val server = ServerSocket(TCP_PORT, 50, InetAddress.getByName("127.0.0.1"))
                server.reuseAddress = true
                tcpServerSocket = server
                val myUid = android.os.Process.myUid()
                val myPid = android.os.Process.myPid()
                Log.i(TAG, "[TCP_SERVER_STARTED] address=127.0.0.1 port=$TCP_PORT package=com.example.zesto uid=$myUid pid=$myPid transport=TCP_LOOPBACK bound=${server.isBound}")
                Log.i(TAG, "[IPC_SERVER_STARTED] Localhost TCP IPC server listening on 127.0.0.1:$TCP_PORT (uid=$myUid, pid=$myPid)")
            } catch (e: Exception) {
                Log.w(TAG, "[TCP_SERVER_ERROR] Failed to bind TCP server on 127.0.0.1:$TCP_PORT: ${e.message}")
            }

            while (isRunning.get()) {
                try {
                    val client = tcpServerSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    val clientAddr = client.inetAddress?.hostAddress ?: "127.0.0.1"
                    val clientPort = client.port
                    Log.i(TAG, "[TCP_CLIENT_ACCEPTED] remote=$clientAddr:$clientPort local=127.0.0.1:$TCP_PORT")
                    clientExecutor.execute {
                        handleTcpClient(client)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.d(TAG, "TcpServerSocket accept error: ${e.message}")
                    }
                    break
                }
            }
        }, "zesto-tcp-ipc-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stopServer() {
        isRunning.set(false)
        try {
            localServerSocket?.close()
        } catch (_: Exception) {}
        localServerSocket = null

        try {
            tcpServerSocket?.close()
        } catch (_: Exception) {}
        tcpServerSocket = null
    }

    /**
     * Updates the active cached frame payload from a decoded Bitmap.
     * Called whenever a new frame arrives in ZestoFrameBridge.
     */
    fun updateFrame(
        frameId: Long,
        timestampUs: Long,
        width: Int,
        height: Int,
        bitmap: Bitmap?,
        rawBuffer: ByteArray?,
        sourceMode: FrameSourceMode,
        healthState: FrameHealthState,
        isStreaming: Boolean
    ) {
        // Ensure server is listening
        if (!isRunning.get()) {
            startServer()
        }

        var jpegBytes: ByteArray? = if (rawBuffer != null && rawBuffer.isNotEmpty()) {
            rawBuffer
        } else if (bitmap != null && !bitmap.isRecycled) {
            try {
                val baos = ByteArrayOutputStream(width * height / 4)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                baos.toByteArray()
            } catch (e: Throwable) {
                Log.w(TAG, "Error compressing IPC frame: ${e.message}")
                null
            }
        } else null

        val healthByte: Byte = when (healthState) {
            FrameHealthState.NO_FRAME -> 0
            FrameHealthState.FRAME_ACTIVE -> 1
            FrameHealthState.FRAME_STALLED -> 2
        }

        val sourceModeByte: Byte = when (sourceMode) {
            FrameSourceMode.RTSP -> 0
            FrameSourceMode.TEST_PATTERN -> 1
        }

        synchronized(latestFrameLock) {
            cachedFrameId = frameId
            cachedTimestampUs = timestampUs
            cachedWidth = width
            cachedHeight = height
            cachedFormat = 0 // JPEG
            cachedHealthByte = healthByte
            cachedIsStreaming = if (isStreaming) 1 else 0
            cachedSourceModeByte = sourceModeByte
            if (jpegBytes != null) {
                cachedPayload = jpegBytes
            }
        }

        val count = serverLogCount.incrementAndGet()
        if (count == 1L || count % 60L == 0L) {
            Log.i(TAG, "[IPC_SERVER_FRAME_UPDATED] frameId=$frameId mode=$sourceMode payloadSize=${jpegBytes?.size ?: 0}B res=${width}x${height}")
        }
    }

    private fun handleLocalClient(client: LocalSocket) {
        try {
            client.soTimeout = 3000
            val input = DataInputStream(client.inputStream)
            val output = DataOutputStream(client.outputStream)
            processClientStream(input, output)
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun handleTcpClient(client: Socket) {
        try {
            client.soTimeout = 3000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            processClientStream(input, output)
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun processClientStream(input: DataInputStream, output: DataOutputStream) {
        while (isRunning.get()) {
            val cmd = try {
                input.readByte()
            } catch (_: IOException) {
                break
            }

            when (cmd) {
                CMD_GET_LATEST_FRAME -> {
                    var frameId: Long
                    var tsUs: Long
                    var w: Int
                    var h: Int
                    var fmt: Byte
                    var health: Byte
                    var streaming: Byte
                    var mode: Byte
                    var payload: ByteArray?

                    synchronized(latestFrameLock) {
                        frameId = cachedFrameId
                        tsUs = cachedTimestampUs
                        w = cachedWidth
                        h = cachedHeight
                        fmt = cachedFormat
                        health = cachedHealthByte
                        streaming = cachedIsStreaming
                        mode = cachedSourceModeByte
                        payload = cachedPayload
                    }

                    val payloadSize = payload?.size ?: 0

                    output.writeInt(MAGIC)
                    output.writeByte(PROTOCOL_VERSION.toInt())
                    output.writeLong(frameId)
                    output.writeLong(tsUs)
                    output.writeInt(w)
                    output.writeInt(h)
                    output.writeByte(fmt.toInt())
                    output.writeByte(health.toInt())
                    output.writeByte(streaming.toInt())
                    output.writeByte(mode.toInt())
                    output.writeInt(payloadSize)
                    if (payload != null && payloadSize > 0) {
                        output.write(payload, 0, payloadSize)
                    }
                    output.flush()

                    if (frameId == 1L || frameId % 60L == 0L) {
                        Log.i(TAG, "[TCP_FRAME_SENT] frameId=$frameId bytes=$payloadSize res=${w}x${h} mode=$mode health=$health")
                    }
                }

                CMD_REPORT_MILESTONE -> {
                    val stageLen = input.readShort().toInt()
                    val stageBytes = ByteArray(stageLen)
                    input.readFully(stageBytes)
                    val stage = String(stageBytes, Charsets.UTF_8)

                    val pkgLen = input.readShort().toInt()
                    val pkgBytes = ByteArray(pkgLen)
                    input.readFully(pkgBytes)
                    val pkg = String(pkgBytes, Charsets.UTF_8)

                    val msgLen = input.readShort().toInt()
                    val msgBytes = ByteArray(msgLen)
                    input.readFully(msgBytes)
                    val msg = String(msgBytes, Charsets.UTF_8)

                    ZestoFrameBridge.reportExternalMilestone(stage, pkg, msg)
                    output.writeByte(0x00)
                    output.flush()
                }

                CMD_PING -> {
                    output.writeByte(0x00)
                    output.flush()
                }

                else -> {
                    Log.w(TAG, "Unknown client command: $cmd")
                    break
                }
            }
        }
    }
}
