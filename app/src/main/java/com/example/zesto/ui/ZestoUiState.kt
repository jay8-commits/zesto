package com.example.zesto.ui

import androidx.media3.exoplayer.ExoPlayer
import com.example.zesto.camera.CameraCapabilities
import com.example.zesto.diagnostics.DiagnosticsEvent
import com.example.zesto.diagnostics.DiagnosticsSnapshot
import com.example.zesto.stream.StreamConfig
import com.example.zesto.stream.VirtualInjectionState
import com.example.zesto.stream.ZestoEngineLifecycleState
import com.example.zesto.target.TargetProfile

enum class ZestoTab(val title: String) {
    STREAM_CONFIG("Config"),
    STREAM_PREVIEW("Preview"),
    DIAGNOSTICS("Diagnostics"),
    TARGET_COMPAT("Target & API")
}

data class ZestoUiState(
    val selectedTab: ZestoTab = ZestoTab.STREAM_CONFIG,
    val streamConfig: StreamConfig = StreamConfig(),
    val lifecycleState: ZestoEngineLifecycleState = ZestoEngineLifecycleState.DISCONNECTED,
    val injectionState: VirtualInjectionState = VirtualInjectionState.RTSP_CONNECTED,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isDecoding: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isVirtualFeedActive: Boolean = false,
    val connectionTestResult: String? = null,
    val isTestingConnection: Boolean = false,
    val player: ExoPlayer? = null,
    val diagnosticsSnapshot: DiagnosticsSnapshot = DiagnosticsSnapshot(),
    val eventLogs: List<DiagnosticsEvent> = emptyList(),
    val cameraCapabilities: CameraCapabilities = CameraCapabilities(),
    val targetProfiles: List<TargetProfile> = emptyList(),
    val selectedTargetProfile: TargetProfile? = null,
    val profileSearchQuery: String = "",
    val showModuleGuideDialog: Boolean = false,
    val exportedLogText: String? = null,
    val userNoticeMessage: String? = null
)

