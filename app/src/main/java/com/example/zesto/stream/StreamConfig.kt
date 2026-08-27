package com.example.zesto.stream

/**
 * Configuration options for video stream connections.
 */
data class StreamConfig(
    val url: String = "rtsp://192.168.1.100:8554/live",
    val protocol: TransportProtocol = TransportProtocol.RTSP_TCP,
    val targetWidth: Int = 1080,
    val targetHeight: Int = 1920,
    val targetFps: Int = 30,
    val connectionTimeoutMs: Long = 5000L,
    val readTimeoutMs: Long = 5000L,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val reconnectDelayMs: Long = 2000L,
    val bufferDurationMs: Long = 200L
)

enum class TransportProtocol {
    RTSP_TCP,
    RTSP_UDP,
    FUTURE_WEBRTC,
    FUTURE_USB
}
