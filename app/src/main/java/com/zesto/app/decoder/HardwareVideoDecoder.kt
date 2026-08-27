package com.zesto.app.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.zesto.app.model.PixelFormat
import com.zesto.app.model.VideoFrame
import com.zesto.app.pipeline.FramePipeline
import java.nio.ByteBuffer

/**
 * MediaCodec hardware decoder for H.264/AVC stream ingestion.
 * Produces canonical VideoFrame instances with preserved MediaCodec PTS.
 */
class HardwareVideoDecoder(private val framePipeline: FramePipeline) {
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private var frameSeq = 0L

    fun initialize(width: Int, height: Int, mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC) {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        }

        mediaCodec = MediaCodec.createDecoderByType(mimeType).apply {
            configure(format, null, null, 0)
            start()
        }
        isRunning = true
    }

    fun processOutputBuffer(bufferIndex: Int, info: MediaCodec.BufferInfo) {
        val codec = mediaCodec ?: return
        val outputBuffer = codec.getOutputBuffer(bufferIndex) ?: return
        val outputFormat = codec.getOutputFormat(bufferIndex)
        val width = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val stride = if (outputFormat.containsKey(MediaFormat.KEY_STRIDE)) {
            outputFormat.getInteger(MediaFormat.KEY_STRIDE)
        } else {
            width
        }

        // Copy raw YUV buffer from decoder for canonical storage
        val directBuffer = ByteBuffer.allocateDirect(info.size)
        directBuffer.put(outputBuffer)
        directBuffer.flip()

        // Presentation timestamp converted directly from MediaCodec presentationTimeUs (microseconds -> nanoseconds)
        val sourcePtsNs = info.presentationTimeUs * 1000L

        val frame = VideoFrame(
            frameId = ++frameSeq,
            timestampPts = sourcePtsNs,
            width = width,
            height = height,
            stride = stride,
            pixelFormat = PixelFormat.YUV_420_888,
            buffer = directBuffer,
            rotationDegrees = 0,
            isMirrored = false
        )

        framePipeline.onDecodedFrame(frame)
        codec.releaseOutputBuffer(bufferIndex, false)
    }

    fun release() {
        isRunning = false
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
    }
}
