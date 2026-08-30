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

    val rtspPlayerEngine =
        com.example.zesto.stream.ZestoStreamEngineManager.getEngine(application)

    private val rtspTransport = RTSPTransport()

    val streamReceiver = StreamReceiver(
        transport = rtspTransport
    )

    val videoDecoder: VideoDecoder =
        HardwareVideoDecoder()

    val framePipeline =
        FramePipeline()

    private val cameraDetector =
        CameraApiDetector(application)

    val compatibilityManager =
        CompatibilityManager()

    val diagnosticsManager =
        DiagnosticsManager()

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

        /*
         * Wire RTSPPlayerEngine real hardware frames -> FramePipeline -> ZestoFrameBridge / Backends
         */
        var frameHandoffCount = 0L
        rtspPlayerEngine.setFrameListener { frame ->
            framePipeline.pushFrame(frame)
            frameHandoffCount++
            if (frameHandoffCount == 1L || frameHandoffCount % 150L == 0L) {
                diagnosticsManager.logger.debug(
                    Subsystem.FRAME_PIPELINE,
                    "Frame #${frame.frameNumber} (${frame.width}x${frame.height}) delivered to ${framePipeline.getActiveConsumerCount()} active consumers"
                )
            }
        }

        observeSubsystems()
    }

    private fun observeSubsystems() {

        viewModelScope.launch {

            rtspPlayerEngine.streamState.collect { state ->

                val connected =
                    state is StreamState.Connected

                val connecting =
                    state is StreamState.Connecting ||
                        state is StreamState.Reconnecting

                _uiState.update {

                    it.copy(
                        isConnected = connected,
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
                        isDecoding = running
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

    fun connectStream() {

        val config =
            _uiState.value.streamConfig

        viewModelScope.launch {

            diagnosticsManager.logger.info(
                Subsystem.TRANSPORT,
                "Initiating real RTSP stream from ${config.url}"
            )

            framePipeline.start()

            // Start the authoritative Foreground Streaming Service
            com.example.zesto.service.ZestoStreamingService.startStreaming(
                getApplication(),
                config
            )

            _uiState.update {
                it.copy(
                    player =
                        rtspPlayerEngine.player
                )
            }

            diagnosticsManager.logger.info(
                Subsystem.FRAME_PIPELINE,
                "Frame delivery pipeline active with ${framePipeline.getActiveConsumerCount()} registered consumers"
            )
        }
    }

    fun disconnectStream() {

        viewModelScope.launch {

            diagnosticsManager.logger.info(
                Subsystem.TRANSPORT,
                "Disconnecting RTSP stream"
            )

            // Stop the authoritative Foreground Streaming Service
            com.example.zesto.service.ZestoStreamingService.stopStreaming(
                getApplication()
            )

            streamReceiver.stop()

            stopDecoderAndPipeline()
        }
    }

    fun startDecoderAndPipeline() {

        viewModelScope.launch {

            val config =
                _uiState.value.streamConfig

            diagnosticsManager.logger.info(
                Subsystem.DECODER,
                "Starting hardware decoder and stream ingestion at " +
                    "${config.targetWidth}x${config.targetHeight}"
            )

            framePipeline.start()

            rtspPlayerEngine.startStream(
                config
            )

            _uiState.update {
                it.copy(
                    player =
                        rtspPlayerEngine.player
                )
            }

            diagnosticsManager.logger.info(
                Subsystem.FRAME_PIPELINE,
                "Frame delivery pipeline active with ${framePipeline.getActiveConsumerCount()} registered consumers"
            )
        }
    }

    fun stopDecoderAndPipeline() {

        viewModelScope.launch {

            rtspPlayerEngine.stopStream()

            framePipeline.stop()

            diagnosticsManager.logger.info(
                Subsystem.DECODER,
                "Decoder and pipeline stopped"
            )
        }
    }

    fun startBackgroundService(
        context: Context
    ) {

        val config =
            _uiState.value.streamConfig

        ZestoStreamingService.startStreaming(
            context,
            config
        )

        _uiState.update {
            it.copy(
                isServiceRunning = true,
                userNoticeMessage =
                    "Background streaming service started"
            )
        }

        diagnosticsManager.logger.info(
            Subsystem.SYSTEM,
            "Foreground streaming service started"
        )
    }

    fun stopBackgroundService(
        context: Context
    ) {

        ZestoStreamingService.stopStreaming(
            context
        )

        _uiState.update {
            it.copy(
                isServiceRunning = false,
                userNoticeMessage =
                    "Background streaming service stopped"
            )
        }

        diagnosticsManager.logger.info(
            Subsystem.SYSTEM,
            "Foreground streaming service stopped"
        )
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

        /*
         * Note: rtspPlayerEngine is managed authoritatively by ZestoStreamEngineManager
         * and ZestoStreamingService so background streaming continues when UI is backgrounded.
         */

        /*
         * VideoDecoder cleanup.
         */
        videoDecoder.stop()

        framePipeline.unregisterAll()
        framePipeline.stop()

        activeBackend?.release()

        super.onCleared()
    }
}
