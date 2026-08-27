package com.example.zesto.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Concrete implementation of VideoTransport for RTSP streams.
 * Handles RTSP socket connection validation, lifecycle state transitions,
 * error recovery, and packet statistics.
 */
class RTSPTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : VideoTransport {

    override val transportType: TransportProtocol = TransportProtocol.RTSP_TCP

    private val _state = MutableStateFlow<StreamState>(StreamState.Disconnected)
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private var listener: VideoTransportListener? = null
    private var activeConfig: StreamConfig? = null
    private var connectionJob: Job? = null
    private var reconnectCount = 0

    override fun setListener(listener: VideoTransportListener?) {
        this.listener = listener
    }

    override fun isConnected(): Boolean {
        return _state.value is StreamState.Connected
    }

    override suspend fun connect(config: StreamConfig): Result<Unit> {
        if (_state.value is StreamState.Connected || _state.value is StreamState.Connecting) {
            disconnect()
        }

        activeConfig = config
        _state.value = StreamState.Connecting

        return try {
            val uri = URI(config.url)
            val host = uri.host ?: throw IllegalArgumentException("Invalid RTSP host in URL: ${config.url}")
            val port = if (uri.port > 0) uri.port else 554

            startConnectionLoop(host, port, config)
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = StreamState.Error("Invalid RTSP URL: ${e.message}", e, recoverable = false)
            listener?.onError("Failed to parse RTSP URL", e)
            Result.failure(e)
        }
    }

    private fun startConnectionLoop(host: String, port: Int, config: StreamConfig) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                // Test reachability / RTSP handshake endpoint via socket
                val probeResult = RTSPConnectionTester.probe(config.url, config.connectionTimeoutMs)
                if (probeResult !is RTSPProbeResult.Success) {
                    throw IOException(probeResult.toDisplayString())
                }

                _state.value = StreamState.Connected(config.url)
                listener?.onConnected()

                // Active connection monitoring loop
                var lastBytes = 0L
                var lastCheckTime = System.currentTimeMillis()

                while (isActive) {
                    delay(1000L)
                    val now = System.currentTimeMillis()
                    val durationSec = (now - lastCheckTime).coerceAtLeast(1) / 1000.0
                    val currentBytes = _stats.value.bytesReceived
                    val bitrate = ((currentBytes - lastBytes) * 8 / 1000.0) / durationSec
                    lastBytes = currentBytes
                    lastCheckTime = now

                    _stats.update { current ->
                        current.copy(
                            estimatedBitrateKbps = bitrate,
                            reconnectCount = reconnectCount
                        )
                    }
                }
            } catch (e: Exception) {
                if (!isActive) return@launch

                if (config.autoReconnect && reconnectCount < config.maxReconnectAttempts) {
                    reconnectCount++
                    _state.value = StreamState.Reconnecting(
                        attempt = reconnectCount,
                        maxAttempts = config.maxReconnectAttempts,
                        reason = e.message ?: "Connection dropped"
                    )
                    delay(config.reconnectDelayMs)
                    startConnectionLoop(host, port, config)
                } else {
                    _state.value = StreamState.Error(
                        message = "RTSP Connection Failed: ${e.message}",
                        cause = e,
                        recoverable = true
                    )
                    listener?.onError("Connection terminated: ${e.message}", e)
                }
            }
        }
    }

    override suspend fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        reconnectCount = 0
        val wasConnected = _state.value is StreamState.Connected
        _state.value = StreamState.Disconnected
        if (wasConnected) {
            listener?.onDisconnected("User disconnected")
        }
    }

    fun injectTestPacket(data: ByteArray, isKeyFrame: Boolean, timestampUs: Long = System.nanoTime() / 1000) {
        _stats.update { current ->
            current.copy(
                bytesReceived = current.bytesReceived + data.size,
                packetsReceived = current.packetsReceived + 1,
                framesReceived = current.framesReceived + 1,
                lastPacketTimestamp = System.currentTimeMillis()
            )
        }
        listener?.onDataReceived(data, 0, data.size, timestampUs, isKeyFrame)
    }
}
