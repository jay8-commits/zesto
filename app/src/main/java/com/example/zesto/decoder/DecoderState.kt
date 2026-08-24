package com.example.zesto.decoder

/**
 * State representation for the video decoder lifecycle.
 */
sealed class DecoderState {
    object Uninitialized : DecoderState()
    data class Configured(val mimeType: String, val width: Int, val height: Int) : DecoderState()
    object Running : DecoderState()
    object Paused : DecoderState()
    object Stopped : DecoderState()
    data class Error(val message: String, val cause: Throwable? = null) : DecoderState()
}

/**
 * Real-time statistics tracked by the video decoder.
 */
data class DecoderStats(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Double = 0.0,
    val pixelFormat: String = "COLOR_FormatYUV420Flexible",
    val decodedFrameCount: Long = 0L,
    val droppedFrameCount: Long = 0L,
    val decodeErrors: Long = 0L,
    val averageDecodeLatencyMs: Long = 0L,
    val lastFrameTimestampUs: Long = 0L
)
