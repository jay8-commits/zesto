package com.example.zesto.camera

import com.example.zesto.frame.FrameConsumer
import com.example.zesto.frame.FrameProvider
import com.example.zesto.frame.VideoFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Camera Virtualization Backend abstraction.
 * Represents an integration point where decoded frames from FrameProvider can be injected
 * into target camera pipelines.
 */
interface CameraVirtualizationBackend : FrameConsumer {
    val backendName: String
    val supportedApi: CameraApiType
    val status: StateFlow<CameraVirtualizationStatus>
    val technicalNotes: String

    fun initialize(): Result<Unit>
    fun startVirtualFeed(): Result<Unit>
    fun stopVirtualFeed()
    fun release()
}
