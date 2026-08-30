package com.example.zesto.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zesto.camera.Camera2Backend
import com.example.zesto.camera.CameraApiDetector
import com.example.zesto.camera.CameraVirtualizationBackend
import com.example.zesto.camera.CameraVirtualizationStatus
import com.example.zesto.data.ZestoPreferences
import com.example.zesto.decoder.HardwareVideoDecoder
import com.example.zesto.decoder.VideoDecoder
import com.example.zesto.diagnostics.DiagnosticsManager
import com.example.zesto.diagnostics.LogExporter
import com.example.zesto.diagnostics.Subsystem
import com.example.zesto.frame.FrameConsumer
import com.example.zesto.frame.FramePipeline
import com.example.zesto.frame.FrameProvider
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import com.example.zesto.frame.ZestoFrameBridge
import com.example.zesto.service.ZestoStreamingService
import com.example.zesto.stream.RTSPConnectionTester
import com.example.zesto.stream.RTSPPlayerEngine
import com.example.zesto.stream.RTSPTransport
import com.example.zesto.stream.StreamConfig
import com.example.zesto.stream.StreamReceiver
import com.example.zesto.stream.StreamState
import com.example.zesto.stream.TransportProtocol
import com.example.zesto.stream.ZestoStreamEngineManager
import com.example.zesto.target.CompatibilityManager
import com.example.zesto.target.TargetProfile
import com.example.zesto.testtarget.ControlledCameraTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ZestoViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = ZestoPreferences(application)

    val rtspPlayerEngine: RTSPPlayerEngine
        get() = ZestoStreamEngineManager.getEngine()

    val framePipeline: FramePipeline
        get() = ZestoStreamEngineManager.framePipeline

    private val cameraDetector =
        CameraApiDetector(application)

    val compatibilityManager =
        CompatibilityManager()

    val diagnosticsManager: DiagnosticsManager
        get() = ZestoStreamEngineManager.diagnosticsManager

    private var activeBackend:
        CameraVirtualizationBackend? = null

    private val _uiState =
        MutableStateFlow(ZestoUiState())

    val uiState: StateFlow<ZestoUiState> =
        _uiState.asStateFlow()

    private class BridgeFrameConsumer(private val diagnosticsManager: DiagnosticsManager) : FrameConsumer {
        override val consumerId: String = "bridge_consumer"
        override val preferredFormat: PixelFormat = PixelFormat.RGBA_8888

        override fun onConsumerAttached(provider: FrameProvider) {}

        override fun onFrameAvailable(frame: VideoFrame) {
            if (frame.sourceMode != com.example.zesto.frame.FrameSourceMode.RTSP) {
                ZestoFrameBridge.postFrame(
                    width = frame.width,
                    height = frame.height,
                    format = frame.pixelFormat,
                    buffer = frame.buffer?.array(),
                    bitmap = frame.bitmap,
                    timestampUs = frame.timestampUs,
                    sourceMode = frame.sourceMode,
                    externalFrameId = frame.frameNumber
                )
                diagnosticsManager.recordBoundaryStage(com.example.zesto.diagnostics.BoundaryDiagnosticStage.FRAME_BRIDGE_POSTED)
            }
        }

        override fun onConsumerDetached() {}
    }

    fun onFrameDecodedFromPreview(bitmap: android.graphics.Bitmap, width: Int, height: Int) {
        rtspPlayerEngine.deliverDecodedFrame(bitmap, width, height)
    }

    init {
        ZestoStreamEngineManager.initialize(application)

        val initialConfig =
            preferences.loadStreamConfig()

        val cameraCaps =
            cameraDetector.detectDeviceCapabilities()

        val allProfiles =
            compatibilityManager.getAllProfiles()

        val defaultProfile =
            allProfiles.firstOrNull()

        _uiState.update {
            it.copy(
                streamConfig = initialConfig,
                cameraCapabilities = cameraCaps,
                targetProfiles = allProfiles,
                selectedTargetProfile = defaultProfile,
                player = rtspPlayerEngine.player
            )
        }

        rtspPlayerEngine.diagnosticsManager = diagnosticsManager

        // Register default bridge frame consumer to guarantee frame delivery to ZestoFrameBridge
        val bridgeConsumer = BridgeFrameConsumer(diagnosticsManager)
        framePipeline.registerConsumer(bridgeConsumer)

        // Register the active camera virtualization backend for the selected profile
        val defaultBackend = defaultProfile?.let { compatibilityManager.createBackendForProfile(it) }
        activeBackend = defaultBackend
        defaultBackend?.let { framePipeline.registerConsumer(it) }

        diagnosticsManager.updateCameraDetection(
            cameraCaps.apiType,
            cameraCaps.hardwareLevel
        )

        diagnosticsManager.updateVirtualization(
            defaultProfile?.supportedBackend
                ?: "Camera2Backend",
            defaultProfile?.testStatus
                ?: CameraVirtualizationStatus.NOT_TESTED
        )

        diagnosticsManager.updateTarget(
            defaultProfile?.packageName
                ?: "com.example.zesto.testtarget",
            "INITIALIZED"
        )

        diagnosticsManager.logger.info(
            Subsystem.SYSTEM,
            "Zesto Authoritative RTSP Stream & Virtualization Pipeline Initialized"
        )

        observeSubsystems()
    }

    private fun observeSubsystems() {

        viewModelScope.launch {
            ZestoStreamEngineManager.lifecycleState.collect { lState ->
                val connecting = lState == com.example.zesto.stream.ZestoEngineLifecycleState.CONNECTING ||
                        lState == com.example.zesto.stream.ZestoEngineLifecycleState.RECONNECTING
                val connected = lState == com.example.zesto.stream.ZestoEngineLifecycleState.CONNECTED ||
                        lState == com.example.zesto.stream.ZestoEngineLifecycleState.RUNNING
                val decoding = lState == com.example.zesto.stream.ZestoEngineLifecycleState.RUNNING

                _uiState.update {
                    it.copy(
                        lifecycleState = lState,
                        isConnecting = connecting,
                        isConnected = connected,
                        isDecoding = decoding || it.isDecoding,
                        player = rtspPlayerEngine.player
                    )
                }
            }
        }

        viewModelScope.launch {
            ZestoStreamEngineManager.injectionState.collect { injState ->
                val virtualActive = injState == com.example.zesto.stream.VirtualInjectionState.INJECTION_CONFIRMED ||
                        injState == com.example.zesto.stream.VirtualInjectionState.INJECTION_ATTEMPTED
                _uiState.update {
                    it.copy(
                        injectionState = injState,
                        isVirtualFeedActive = virtualActive
                    )
                }
            }
        }

        viewModelScope.launch {

            rtspPlayerEngine.streamState.collect { state ->

                val connected =
                    state is StreamState.Connected

                val connecting =
                    state is StreamState.Connecting ||
                        state is StreamState.Reconnecting

                _uiState.update {

                    it.copy(
                        isConnected = connected || it.isConnected,
                        isConnecting = connecting,
                        isDecoding =
                            connected || it.isDecoding
                    )
                }

                diagnosticsManager.updateTransport(
                    state,
                    rtspPlayerEngine.streamStats.value,
                    _uiState.value.streamConfig.url
                )
            }
        }

        viewModelScope.launch {

            rtspPlayerEngine.streamStats.collect { stats ->

                diagnosticsManager.updateTransport(
                    rtspPlayerEngine.streamState.value,
                    stats,
                    _uiState.value.streamConfig.url
                )
            }
        }

        viewModelScope.launch {

            rtspPlayerEngine.decoderState.collect { decState ->

                val running =
                    decState is
                        com.example.zesto.decoder.DecoderState.Running

                _uiState.update {
                    it.copy(
                        isDecoding = running || it.isDecoding
                    )
                }

                diagnosticsManager.updateDecoder(
                    decState,
                    rtspPlayerEngine.decoderStats.value
                )
            }
        }

        viewModelScope.launch {

            rtspPlayerEngine.decoderStats.collect { decStats ->

                diagnosticsManager.updateDecoder(
                    rtspPlayerEngine.decoderState.value,
                    decStats
                )
            }
        }

        viewModelScope.launch {

            framePipeline.stats.collect { pipeStats ->

                diagnosticsManager.updatePipeline(
                    pipeStats
                )
            }
        }

        viewModelScope.launch {

            diagnosticsManager.snapshot.collect { snapshot ->

                _uiState.update {
                    it.copy(
                        diagnosticsSnapshot = snapshot
                    )
                }
            }
        }

        viewModelScope.launch {
            ZestoStreamingService.globalServiceState.collect { sState ->
                val running = sState != com.example.zesto.service.ServiceRuntimeState.SERVICE_STOPPED
                _uiState.update {
                    it.copy(
                        isServiceRunning = running
                    )
                }
            }
        }

        viewModelScope.launch {
            diagnosticsManager.logger.logs.collect { logs ->
                _uiState.update {
                    it.copy(
                        eventLogs = logs
                    )
                }
            }
        }

        viewModelScope.launch {
            ZestoFrameBridge.externalMilestones.collect { events ->
                events.lastOrNull()?.let { lastEvent ->
                    try {
                        val stage = com.example.zesto.diagnostics.BoundaryDiagnosticStage.valueOf(lastEvent.stage)
                        diagnosticsManager.recordBoundaryStage(stage)
                    } catch (_: IllegalArgumentException) {}

                    diagnosticsManager.updateTarget(lastEvent.packageName, "HOOK_ACTIVE")
                    diagnosticsManager.logger.info(
                        Subsystem.VIRTUALIZATION,
                        "[TARGET: ${lastEvent.packageName}] ${lastEvent.message}"
                    )
                }
            }
        }
    }

    fun selectTab(tab: ZestoTab) {

        _uiState.update {
            it.copy(
                selectedTab = tab
            )
        }
    }

    fun updateStreamUrl(url: String) {

        _uiState.update {

            val updated =
                it.streamConfig.copy(
                    url = url
                )

            preferences.saveStreamConfig(
                updated
            )

            it.copy(
                streamConfig = updated,
                connectionTestResult = null
            )
        }
    }

    fun updateTransportProtocol(
        protocol: TransportProtocol
    ) {

        _uiState.update {

            val updated =
                it.streamConfig.copy(
                    protocol = protocol
                )

            preferences.saveStreamConfig(
                updated
            )

            it.copy(
                streamConfig = updated
            )
        }
    }

    fun updateResolution(
        width: Int,
        height: Int
    ) {

        _uiState.update {

            val updated =
                it.streamConfig.copy(
                    targetWidth = width,
                    targetHeight = height
                )

            preferences.saveStreamConfig(
                updated
            )

            it.copy(
                streamConfig = updated
            )
        }
    }

    fun updateTargetFps(
        fps: Int
    ) {

        _uiState.update {

            val updated =
                it.streamConfig.copy(
                    targetFps = fps
                )

            preferences.saveStreamConfig(
                updated
            )

            it.copy(
                streamConfig = updated
            )
        }
    }

    fun testConnection() {

        val url =
            _uiState.value.streamConfig.url

        _uiState.update {
            it.copy(
                isTestingConnection = true,
                connectionTestResult = null
            )
        }

        viewModelScope.launch {

            val probeResult =
                RTSPConnectionTester.probe(
                    url,
                    _uiState.value
                        .streamConfig
                        .connectionTimeoutMs
                )

            val resultText =
                probeResult.toDisplayString()

            _uiState.update {

                it.copy(
                    isTestingConnection = false,
                    connectionTestResult = resultText
                )
            }

            if (probeResult.isConnected) {

                diagnosticsManager.logger.info(
                    Subsystem.TRANSPORT,
                    "Connection probe SUCCEEDED: $resultText"
                )

            } else {

                diagnosticsManager.logger.warn(
                    Subsystem.TRANSPORT,
                    "Connection probe issue: $resultText"
                )
            }
        }
    }

    /**
     * Master coordinated connect action triggered from UI.
     */
    fun connect() {
        val config = _uiState.value.streamConfig
        viewModelScope.launch {
            ZestoStreamEngineManager.connect(getApplication(), config)
            _uiState.update {
                it.copy(
                    player = rtspPlayerEngine.player
                )
            }
        }
    }

    /**
     * Master coordinated disconnect action triggered from UI.
     */
    fun disconnect() {
        viewModelScope.launch {
            ZestoStreamEngineManager.disconnect(getApplication())
        }
    }

    fun connectStream() {
        connect()
    }

    fun disconnectStream() {
        disconnect()
    }

    fun startDecoderAndPipeline() {
        connect()
    }

    fun stopDecoderAndPipeline() {
        disconnect()
    }

    fun startBackgroundService(
        context: Context
    ) {
        connect()
        _uiState.update {
            it.copy(
                userNoticeMessage = "Zesto Stream Pipeline & Service connected"
            )
        }
    }

    fun stopBackgroundService(
        context: Context
    ) {
        disconnect()
        _uiState.update {
            it.copy(
                userNoticeMessage = "Zesto Stream Pipeline & Service stopped"
            )
        }
    }

    fun startTestHarness() {
        connect()
    }

    fun startInjection() {
        connect()
    }

    fun stopInjection() {
        disconnect()
    }

    fun launchControlledTarget(
        context: Context
    ) {

        val intent =
            Intent(
                context,
                ControlledCameraTestActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK
            }

        context.startActivity(intent)
    }

    fun updateProfileSearchQuery(
        query: String
    ) {

        val filtered =
            compatibilityManager.filterProfiles(
                query
            )

        _uiState.update {

            it.copy(
                profileSearchQuery = query,
                targetProfiles = filtered
            )
        }
    }

    fun setShowModuleGuideDialog(
        show: Boolean
    ) {

        _uiState.update {
            it.copy(
                showModuleGuideDialog = show
            )
        }
    }

    fun selectTargetProfile(
        profile: TargetProfile
    ) {

        _uiState.update {
            it.copy(
                selectedTargetProfile = profile
            )
        }

        // Unregister previous backend consumer and register the new one
        activeBackend?.let { framePipeline.unregisterConsumer(it.consumerId) }

        val backend =
            compatibilityManager
                .createBackendForProfile(profile)

        activeBackend = backend
        backend?.let { framePipeline.registerConsumer(it) }

        diagnosticsManager.updateCameraDetection(
            profile.cameraApi,
            _uiState.value
                .cameraCapabilities
                .hardwareLevel
        )

        diagnosticsManager.updateVirtualization(
            profile.supportedBackend,
            profile.testStatus
        )

        diagnosticsManager.updateTarget(
            profile.packageName,
            "SELECTED"
        )

        diagnosticsManager.logger.info(
            Subsystem.TARGET_COMPATIBILITY,
            "Selected target profile: ${profile.appName} (${backend?.backendName ?: "No backend"})"
        )
    }

    fun exportDiagnosticsLog() {

        val snapshot =
            _uiState.value.diagnosticsSnapshot

        val logs =
            _uiState.value.eventLogs

        val formatted =
            LogExporter.exportAsMarkdown(
                snapshot,
                logs
            )

        _uiState.update {

            it.copy(
                exportedLogText = formatted,
                userNoticeMessage =
                    "Diagnostics log ready for export / debug inspection"
            )
        }
    }

    fun clearExportedLogDialog() {

        _uiState.update {
            it.copy(
                exportedLogText = null
            )
        }
    }

    fun dismissUserNotice() {

        _uiState.update {
            it.copy(
                userNoticeMessage = null
            )
        }
    }

    override fun onCleared() {
        // UI ViewModel cleared should NOT destroy background streaming service or manager
        // Preserves continuous stream for target injected apps
        super.onCleared()
    }
}
