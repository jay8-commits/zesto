package com.example.zesto.diagnostics

import com.example.zesto.camera.CameraApiType
import com.example.zesto.camera.CameraVirtualizationStatus
import com.example.zesto.decoder.DecoderStats
import com.example.zesto.frame.FrameHealthState
import com.example.zesto.frame.FramePipelineStats
import com.example.zesto.service.ServiceRuntimeState
import com.example.zesto.stream.StreamStats

/**
 * Diagnostics event levels and subsystem tags.
 */
enum class DiagnosticsLevel {
    DEBUG, INFO, WARNING, ERROR
}

enum class Subsystem {
    TRANSPORT,
    DECODER,
    FRAME_PIPELINE,
    CAMERA_DETECTION,
    VIRTUALIZATION,
    TARGET_COMPATIBILITY,
    SYSTEM
}

data class DiagnosticsEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val subsystem: Subsystem,
    val level: DiagnosticsLevel,
    val message: String,
    val errorDetails: String? = null
)

/**
 * Complete system-wide diagnostics snapshot covering all six architectural layers.
 */
data class DiagnosticsSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    // Transport Layer
    val transportStatus: String = "DISCONNECTED",
    val transportType: String = "RTSP",
    val rtspUrl: String = "",
    val reconnectCount: Int = 0,
    val streamStats: StreamStats = StreamStats(),

    // Decoder Layer
    val decoderStatus: String = "UNINITIALIZED",
    val decoderResolution: String = "1280x720",
    val decoderFps: Double = 0.0,
    val decodedFrames: Long = 0L,
    val decoderDroppedFrames: Long = 0L,
    val decodeErrors: Long = 0L,
    val decoderStats: DecoderStats = DecoderStats(),

    // Frame Pipeline Layer
    val pipelineStatus: String = "IDLE",
    val frameHealthState: FrameHealthState = FrameHealthState.NO_FRAME,
    val msSinceLastFrame: Long = -1L,
    val deliveredFrames: Long = 0L,
    val pipelineDroppedFrames: Long = 0L,
    val pipelineLatencyMs: Long = 0L,
    val activeConsumers: Int = 0,
    val pipelineStats: FramePipelineStats = FramePipelineStats(),

    // Service & IPC Layer
    val serviceRuntimeState: ServiceRuntimeState = ServiceRuntimeState.SERVICE_STOPPED,
    val ipcProviderStatus: String = "ONLINE (AUTHORITY: com.example.zesto.frameprovider)",

    // Camera API Layer
    val detectedCameraApi: CameraApiType = CameraApiType.CAMERA2,
    val cameraHardwareLevel: String = "LIMITED",

    // Virtualization Backend Layer
    val activeBackend: String = "Camera2Backend",
    val virtualizationStatus: CameraVirtualizationStatus = CameraVirtualizationStatus.NOT_TESTED,

    // Target Layer
    val targetPackage: String = "com.example.zesto.testtarget",
    val targetStatus: String = "CONFIGURED",

    // Active Fault Identification
    val faultSubsystem: Subsystem? = null,
    val lastErrorMessage: String? = null
)

