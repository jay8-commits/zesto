package com.example.zesto.diagnostics

import com.example.zesto.camera.CameraApiType
import com.example.zesto.camera.CameraVirtualizationStatus
import com.example.zesto.decoder.DecoderState
import com.example.zesto.decoder.DecoderStats
import com.example.zesto.frame.FramePipelineStats
import com.example.zesto.stream.StreamState
import com.example.zesto.stream.StreamStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * DiagnosticsManager centralizes metric collection and fault isolation across all 6 layers.
 */
class DiagnosticsManager(
    val logger: DiagnosticsLogger = DiagnosticsLogger()
) {
    private val _snapshot = MutableStateFlow(DiagnosticsSnapshot())
    val snapshot: StateFlow<DiagnosticsSnapshot> = _snapshot.asStateFlow()

    fun updateTransport(state: StreamState, stats: StreamStats, url: String) {
        val statusStr = when (state) {
            is StreamState.Connected -> "CONNECTED"
            is StreamState.Connecting -> "CONNECTING"
            is StreamState.Reconnecting -> "RECONNECTING (${state.attempt}/${state.maxAttempts})"
            is StreamState.Disconnected -> "DISCONNECTED"
            is StreamState.Error -> "ERROR: ${state.message}"
        }

        val fault = if (state is StreamState.Error) Subsystem.TRANSPORT else null
        val errMsg = if (state is StreamState.Error) state.message else null

        _snapshot.update { current ->
            current.copy(
                transportStatus = statusStr,
                rtspUrl = url,
                reconnectCount = stats.reconnectCount,
                streamStats = stats,
                faultSubsystem = fault ?: current.faultSubsystem,
                lastErrorMessage = errMsg ?: current.lastErrorMessage
            )
        }

        if (state is StreamState.Error) {
            logger.error(Subsystem.TRANSPORT, "Transport error: ${state.message}", state.cause?.stackTraceToString())
        }
    }

    fun updateDecoder(state: DecoderState, stats: DecoderStats) {
        val statusStr = when (state) {
            is DecoderState.Running -> "RUNNING"
            is DecoderState.Configured -> "CONFIGURED"
            is DecoderState.Paused -> "PAUSED"
            is DecoderState.Stopped -> "STOPPED"
            is DecoderState.Uninitialized -> "UNINITIALIZED"
            is DecoderState.Error -> "ERROR: ${state.message}"
        }

        val fault = if (state is DecoderState.Error) Subsystem.DECODER else null

        _snapshot.update { current ->
            current.copy(
                decoderStatus = statusStr,
                decoderResolution = "${stats.width}x${stats.height}",
                decoderFps = stats.fps,
                decodedFrames = stats.decodedFrameCount,
                decoderDroppedFrames = stats.droppedFrameCount,
                decodeErrors = stats.decodeErrors,
                decoderStats = stats,
                faultSubsystem = fault ?: current.faultSubsystem
            )
        }
    }

    fun updatePipeline(stats: FramePipelineStats) {
        val statusStr = if (stats.isRunning) "ACTIVE" else "IDLE"

        _snapshot.update { current ->
            current.copy(
                pipelineStatus = statusStr,
                deliveredFrames = stats.deliveredFrames,
                pipelineDroppedFrames = stats.droppedFrames,
                pipelineLatencyMs = stats.averagePipelineLatencyMs,
                activeConsumers = stats.activeConsumers,
                pipelineStats = stats
            )
        }
    }

    fun updateFrameHealth(health: com.example.zesto.frame.FrameHealthState, msAgo: Long) {
        _snapshot.update { current ->
            current.copy(
                frameHealthState = health,
                msSinceLastFrame = msAgo
            )
        }
    }

    fun updateServiceState(serviceState: com.example.zesto.service.ServiceRuntimeState) {
        _snapshot.update { current ->
            current.copy(
                serviceRuntimeState = serviceState
            )
        }
    }

    fun updateCameraDetection(apiType: CameraApiType, hwLevel: String) {
        _snapshot.update {
            it.copy(
                detectedCameraApi = apiType,
                cameraHardwareLevel = hwLevel
            )
        }
    }

    fun updateVirtualization(backendName: String, status: CameraVirtualizationStatus) {
        _snapshot.update {
            it.copy(
                activeBackend = backendName,
                virtualizationStatus = status
            )
        }
    }

    fun updateTarget(packageName: String, status: String) {
        _snapshot.update {
            it.copy(
                targetPackage = packageName,
                targetStatus = status
            )
        }
    }

    fun clearFault() {
        _snapshot.update {
            it.copy(faultSubsystem = null, lastErrorMessage = null)
        }
    }
}
