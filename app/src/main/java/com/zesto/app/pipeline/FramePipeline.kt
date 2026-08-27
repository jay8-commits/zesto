package com.zesto.app.pipeline

import com.zesto.app.bridge.ZestoFrameBridge
import com.zesto.app.model.VideoFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central pipeline router receiving decoded frames from RTSP / Test Source
 * and dispatching the EXACT same canonical VideoFrame to both the In-App Preview
 * consumer and the Camera Injection consumer.
 */
class FramePipeline {
    // Non-blocking SharedFlow for UI/Preview subscription (buffer latest 1 frame)
    private val _previewFrameFlow = MutableSharedFlow<VideoFrame>(replay = 1, extraBufferCapacity = 2)
    val previewFrameFlow: SharedFlow<VideoFrame> = _previewFrameFlow.asSharedFlow()

    /**
     * Entry point for decoded frames from MediaCodec or Test Pattern source.
     *
     * @param frame The canonical VideoFrame containing authoritative frameId, PTS, and buffers.
     */
    fun onDecodedFrame(frame: VideoFrame) {
        // 1. Dispatch to Preview Flow (UI thread / Compose Preview surface)
        _previewFrameFlow.tryEmit(frame)

        // 2. Dispatch to Camera Injection Bridge (Target Camera Hook)
        ZestoFrameBridge.postFrame(frame)
    }
}
