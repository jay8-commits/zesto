package com.example.zesto.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Listener for decoded raw video frames when operating in buffer mode.
 */
interface FrameDecodeListener {
    fun onFrameDecoded(frame: VideoFrame)
    fun onDecodeError(error: String, cause: Throwable?)
}

/**
 * Video decoder interface.
 * Hardware-accelerated decoding abstraction layer supporting Surface and ByteBuffer rendering.
 */
interface VideoDecoder {
    val state: StateFlow<DecoderState>
    val stats: StateFlow<DecoderStats>

    fun configure(
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        width: Int = 1280,
        height: Int = 720,
        surface: Surface? = null
    ): Result<Unit>

    fun start(): Result<Unit>
    fun pause()
    fun resume()
    fun stop()
    fun release()

    fun decodePacket(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestampUs: Long,
        isKeyFrame: Boolean
    ): Boolean

    fun setDecodeListener(listener: FrameDecodeListener?)
    fun setOutputSurface(surface: Surface?)
}
