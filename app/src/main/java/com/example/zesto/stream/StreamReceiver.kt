package com.example.zesto.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * StreamReceiver coordinates the video transport, tracks network states,
 * handles automatic reconnection, and feeds raw encoded stream packets to downstream decoders.
 */
class StreamReceiver(
    val transport: VideoTransport = RTSPTransport(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    val state: StateFlow<StreamState> = transport.state
    val stats: StateFlow<StreamStats> = transport.stats

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var packetCallback: ((data: ByteArray, offset: Int, length: Int, timestampUs: Long, isKeyFrame: Boolean) -> Unit)? = null

    init {
        transport.setListener(object : VideoTransportListener {
            override fun onConnected() {
                _lastError.value = null
            }

            override fun onDisconnected(reason: String) {
                // Handled in state
            }

            override fun onError(error: String, throwable: Throwable?) {
                _lastError.value = error
            }

            override fun onDataReceived(
                data: ByteArray,
                offset: Int,
                length: Int,
                timestampUs: Long,
                isKeyFrame: Boolean
            ) {
                packetCallback?.invoke(data, offset, length, timestampUs, isKeyFrame)
            }
        })
    }

    fun setPacketCallback(callback: (data: ByteArray, offset: Int, length: Int, timestampUs: Long, isKeyFrame: Boolean) -> Unit) {
        this.packetCallback = callback
    }

    suspend fun start(config: StreamConfig): Result<Unit> {
        _lastError.value = null
        return transport.connect(config)
    }

    suspend fun stop() {
        transport.disconnect()
    }

    fun isReceiving(): Boolean {
        return transport.isConnected()
    }
}
