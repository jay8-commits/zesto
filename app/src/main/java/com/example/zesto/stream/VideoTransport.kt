package com.example.zesto.stream

import kotlinx.coroutines.flow.StateFlow

/**
 * Low-level video transport listener interface.
 */
interface VideoTransportListener {
    fun onConnected()
    fun onDisconnected(reason: String)
    fun onError(error: String, throwable: Throwable?)
    fun onDataReceived(data: ByteArray, offset: Int, length: Int, timestampUs: Long, isKeyFrame: Boolean)
}

/**
 * Transport abstraction layer for video streaming.
 * Keeps the core video pipeline independent from underlying network transport (RTSP, WebRTC, USB, etc.).
 */
interface VideoTransport {
    val transportType: TransportProtocol
    val state: StateFlow<StreamState>
    val stats: StateFlow<StreamStats>

    suspend fun connect(config: StreamConfig): Result<Unit>
    suspend fun disconnect()
    fun setListener(listener: VideoTransportListener?)
    fun isConnected(): Boolean
}
