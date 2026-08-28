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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Linux Abstract Unix Domain Socket Server for zero-latency cross-process frame streaming.
 *
 * Abstract namespace domain sockets on Android Linux kernel are accessible by any app in
 * the same Android user sandbox (User 0), bypassing Android 11-15 package visibility (<queries>)
 * and Binder transaction size limits.
 */
object ZestoIpcSocketServer {
    private const val TAG = "ZestoIpcServer"
    const val SOCKET_NAME = "zesto_vcam_ipc"

    const val CMD_GET_LATEST_FRAME: Byte = 0x01
    const val CMD_REPORT_MILESTONE: Byte = 0x02
    const val CMD_PING: Byte = 0x03

    const val MAGIC: Int = 0x5A455354 // "ZEST"
    const val PROTOCOL_VERSION: Byte = 0x01

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
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

        Thread({
            try {
                // Try abstract namespace first
                serverSocket = LocalServerSocket(SOCKET_NAME)
                Log.i(TAG, "[IPC_SERVER_STARTED] Abstract Unix domain socket server listening on '$SOCKET_NAME'")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind abstract local socket '$SOCKET_NAME': ${e.message}")
                isRunning.set(false)
                return@Thread
            }

            while (isRunning.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    clientExecutor.execute {
                        handleClient(client)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.d(TAG, "LocalServerSocket accept error: ${e.message}")
                    }
                    break
                }
            }
        }, "zesto-ipc-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stopServer() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
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

        var jpegBytes: ByteArray? = null
        if (bitmap != null && !bitmap.isRecycled) {
            try {
                val baos = ByteArrayOutputStream(width * height / 4)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                jpegBytes = baos.toByteArray()
            } catch (e: Throwable) {
                Log.w(TAG, "Error compressing IPC frame: ${e.message}")
            }
        } else if (rawBuffer != null && rawBuffer.isNotEmpty()) {
            jpegBytes = rawBuffer
        }

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

    private fun handleClient(client: LocalSocket) {
        try {
            client.soTimeout = 3000
            val input = DataInputStream(client.inputStream)
            val output = DataOutputStream(client.outputStream)

            while (isRunning.get() && client.isConnected) {
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
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }
}
