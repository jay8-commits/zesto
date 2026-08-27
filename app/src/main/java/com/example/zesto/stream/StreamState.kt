package com.example.zesto.stream

/**
 * State representation for streaming transport and receiver.
 */
sealed class StreamState {
    object Disconnected : StreamState()
    object Connecting : StreamState()
    data class Connected(
        val url: String,
        val connectedTimestamp: Long = System.currentTimeMillis()
    ) : StreamState()
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val reason: String
    ) : StreamState()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val recoverable: Boolean = true
    ) : StreamState()
}

/**
 * Real-time statistics tracked by the streaming receiver.
 */
data class StreamStats(
    val bytesReceived: Long = 0L,
    val packetsReceived: Long = 0L,
    val packetsLost: Long = 0L,
    val framesReceived: Long = 0L,
    val estimatedBitrateKbps: Double = 0.0,
    val networkLatencyMs: Long = 0L,
    val reconnectCount: Int = 0,
    val lastPacketTimestamp: Long = 0L
)
