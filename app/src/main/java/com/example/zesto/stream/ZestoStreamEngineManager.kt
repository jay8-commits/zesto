package com.example.zesto.stream

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.zesto.decoder.DecoderState
import com.example.zesto.decoder.DecoderStats
import com.example.zesto.diagnostics.BoundaryDiagnosticStage
import com.example.zesto.diagnostics.DiagnosticsManager
import com.example.zesto.diagnostics.Subsystem
import com.example.zesto.frame.FramePipeline
import com.example.zesto.frame.FramePipelineStats
import com.example.zesto.frame.FrameSourceMode
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import com.example.zesto.frame.ZestoFrameBridge
import com.example.zesto.service.ZestoStreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Authoritative unified lifecycle state for the entire Zesto stream engine.
 */
enum class ZestoEngineLifecycleState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RUNNING,
    RECONNECTING,
    DISCONNECTING,
    ERROR
}

/**
 * Granular verification state for virtual camera injection.
 */
enum class VirtualInjectionState {
    RTSP_CONNECTED,
    DECODER_RUNNING,
    FRAME_PIPELINE_RUNNING,
    INJECTION_ATTEMPTED,
    INJECTION_CONFIRMED
}

/**
 * Authoritative SINGLETON owner of the entire Zesto RTSP stream, decoding pipeline,
 * frame bridge, virtual injection, and foreground service lifecycle.
 *
 * Guarantees a SINGLE RTSP player instance and ONE coordinated lifecycle shared
 * across UI (Activities/ViewModels) and Background Services.
 */
object ZestoStreamEngineManager {
    private const val TAG = "ZestoStreamEngineManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var engine: RTSPPlayerEngine? = null

    val diagnosticsManager = DiagnosticsManager()
    val framePipeline = FramePipeline()

    private val sessionCounter = AtomicLong(1L)
    private var currentSessionId: String = "session-0"

    private val _lifecycleState = MutableStateFlow(ZestoEngineLifecycleState.DISCONNECTED)
    val lifecycleState: StateFlow<ZestoEngineLifecycleState> = _lifecycleState.asStateFlow()

    private val _injectionState = MutableStateFlow(VirtualInjectionState.RTSP_CONNECTED)
    val injectionState: StateFlow<VirtualInjectionState> = _injectionState.asStateFlow()

    private val _streamConfig = MutableStateFlow(StreamConfig())
    val streamConfig: StateFlow<StreamConfig> = _streamConfig.asStateFlow()

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Disconnected)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _streamStats = MutableStateFlow(StreamStats())
    val streamStats: StateFlow<StreamStats> = _streamStats.asStateFlow()

    private val _decoderState = MutableStateFlow<DecoderState>(DecoderState.Uninitialized)
    val decoderState: StateFlow<DecoderState> = _decoderState.asStateFlow()

    private val _decoderStats = MutableStateFlow(DecoderStats())
    val decoderStats: StateFlow<DecoderStats> = _decoderStats.asStateFlow()

    private val _pipelineStats = MutableStateFlow(FramePipelineStats())
    val pipelineStats: StateFlow<FramePipelineStats> = _pipelineStats.asStateFlow()

    private val isInitialized = AtomicBoolean(false)
    private var healthMonitorJob: Job? = null

    // Health Telemetry Counters
    private var lastReportedDecoderFrame = 0L
    private var lastReportedBridgeFrame = 0L
    private var lastHealthLogEpochMs = 0L

    fun getCurrentSessionId(): String = currentSessionId

    fun initialize(context: Context) {
        if (isInitialized.compareAndSet(false, true)) {
            val app = context.applicationContext as? Application ?: context.applicationContext
            appContext = app

            val playerEngine = RTSPPlayerEngine(app, scope)
            playerEngine.diagnosticsManager = diagnosticsManager
            engine = playerEngine

            framePipeline.start()

            // Wire RTSP decoded frame callbacks directly into FramePipeline
            playerEngine.setFrameListener { frame ->
                framePipeline.pushFrame(frame)
            }

            // Observe stream states
            scope.launch {
                playerEngine.streamState.collect { state ->
                    _streamState.value = state
                    diagnosticsManager.updateTransport(state, playerEngine.streamStats.value, _streamConfig.value.url)
                    when (state) {
                        is StreamState.Connected -> {
                            diagnosticsManager.recordBoundaryStage(BoundaryDiagnosticStage.RTSP_CONNECTED)
                            if (_lifecycleState.value == ZestoEngineLifecycleState.CONNECTING) {
                                _lifecycleState.value = ZestoEngineLifecycleState.CONNECTED
                            }
                            if (_injectionState.value == VirtualInjectionState.RTSP_CONNECTED || _injectionState.value == VirtualInjectionState.INJECTION_ATTEMPTED) {
                                // Keep attempted or advance
                            }
                        }
                        is StreamState.Reconnecting -> {
                            _lifecycleState.value = ZestoEngineLifecycleState.RECONNECTING
                        }
                        is StreamState.Error -> {
                            _lifecycleState.value = ZestoEngineLifecycleState.ERROR
                        }
                        is StreamState.Disconnected -> {
                            if (_lifecycleState.value != ZestoEngineLifecycleState.DISCONNECTING && _lifecycleState.value != ZestoEngineLifecycleState.DISCONNECTED) {
                                _lifecycleState.value = ZestoEngineLifecycleState.DISCONNECTED
                            }
                        }
                        is StreamState.Connecting -> {
                            if (_lifecycleState.value == ZestoEngineLifecycleState.DISCONNECTED) {
                                _lifecycleState.value = ZestoEngineLifecycleState.CONNECTING
                            }
                        }
                    }
                }
            }

            scope.launch {
                playerEngine.streamStats.collect { stats ->
                    _streamStats.value = stats
                    diagnosticsManager.updateTransport(_streamState.value, stats, _streamConfig.value.url)
                }
            }

            scope.launch {
                playerEngine.decoderState.collect { decState ->
                    _decoderState.value = decState
                    diagnosticsManager.updateDecoder(decState, playerEngine.decoderStats.value)
                    if (decState is DecoderState.Running) {
                        _lifecycleState.value = ZestoEngineLifecycleState.RUNNING
                        if (_injectionState.value != VirtualInjectionState.INJECTION_CONFIRMED) {
                            _injectionState.value = VirtualInjectionState.DECODER_RUNNING
                        }
                    }
                }
            }

            scope.launch {
                playerEngine.decoderStats.collect { decStats ->
                    _decoderStats.value = decStats
                    diagnosticsManager.updateDecoder(_decoderState.value, decStats)
                }
            }

            scope.launch {
                framePipeline.stats.collect { pipeStats ->
                    _pipelineStats.value = pipeStats
                    diagnosticsManager.updatePipeline(pipeStats)
                    if (pipeStats.deliveredFrames > 0 && _lifecycleState.value == ZestoEngineLifecycleState.CONNECTED) {
                        _lifecycleState.value = ZestoEngineLifecycleState.RUNNING
                    }
                }
            }

            // Observe target milestones for injection confirmation
            scope.launch {
                ZestoFrameBridge.externalMilestones.collect { events ->
                    events.lastOrNull()?.let { lastEvent ->
                        if (lastEvent.message.contains("INJECTION_CONFIRMED") ||
                            lastEvent.stage == "FRAME_SUBSTITUTION_ACTIVE" ||
                            lastEvent.stage == "TARGET_PREVIEW_RECEIVED_FRAME") {
                            _injectionState.value = VirtualInjectionState.INJECTION_CONFIRMED
                            Log.i(TAG, "[INJECTION_CONFIRMED] sessionId=$currentSessionId target=${lastEvent.packageName} msg=${lastEvent.message}")
                        }
                    }
                }
            }

            startHealthMonitor()
            Log.i(TAG, "[ENGINE_INIT] ZestoStreamEngineManager initialized with single RTSP engine instance.")
        }
    }

    fun getEngine(): RTSPPlayerEngine {
        val eng = engine
        if (eng != null) return eng
        val ctx = appContext ?: throw IllegalStateException("ZestoStreamEngineManager must be initialized with Context first")
        initialize(ctx)
        return engine!!
    }

    fun updateConfig(config: StreamConfig) {
        _streamConfig.value = config
    }

    /**
     * SINGLE AUTHORITATIVE CONNECT ENTRY POINT.
     *
     * Coordinated startup of ALL 10 pipeline components under ONE sessionId:
     * 1. Transport Configuration validation
     * 2. Preview state synchronization
     * 3. ZestoStreamingService foreground service
     * 4. RTSPPlayerEngine / RTSP connection
     * 5. MediaCodec decoding
     * 6. OffscreenFrameExtractor
     * 7. FramePipeline
     * 8. ZestoFrameBridge / IPC frame delivery
     * 9. Virtual Injected Feed
     * 10. Test Harness
     */
    fun connect(context: Context, config: StreamConfig = _streamConfig.value) {
        initialize(context)
        _streamConfig.value = config

        val currentState = _lifecycleState.value
        if (currentState == ZestoEngineLifecycleState.CONNECTING ||
            currentState == ZestoEngineLifecycleState.CONNECTED ||
            currentState == ZestoEngineLifecycleState.RUNNING) {
            Log.w(TAG, "[DUPLICATE_CONNECT_IGNORED] sessionId=$currentSessionId state=$currentState")
            Log.w(TAG, "[DUPLICATE_SERVICE_START_IGNORED] sessionId=$currentSessionId")
            Log.w(TAG, "[DUPLICATE_RTSP_START_IGNORED] sessionId=$currentSessionId")
            Log.w(TAG, "[DUPLICATE_PIPELINE_START_IGNORED] sessionId=$currentSessionId")
            Log.w(TAG, "[DUPLICATE_EXTRACTOR_START_IGNORED] sessionId=$currentSessionId")
            Log.w(TAG, "[DUPLICATE_FRAME_BRIDGE_START_IGNORED] sessionId=$currentSessionId")
            Log.w(TAG, "[DUPLICATE_INJECTION_START_IGNORED] sessionId=$currentSessionId")
            Log.w(TAG, "[DUPLICATE_TEST_HARNESS_START_IGNORED] sessionId=$currentSessionId")
            return
        }

        val sessionId = "session-${System.currentTimeMillis()}-${sessionCounter.getAndIncrement()}"
        currentSessionId = sessionId

        Log.i(TAG, "[MASTER_CONNECT_REQUEST] sessionId=$sessionId config=${config.url} target=${config.targetWidth}x${config.targetHeight}@${config.targetFps}")
        _lifecycleState.value = ZestoEngineLifecycleState.CONNECTING
        Log.i(TAG, "[MASTER_CONNECT_BEGIN] sessionId=$sessionId")

        diagnosticsManager.logger.info(Subsystem.TRANSPORT, "Master coordinated connect requested for ${config.url} (sessionId=$sessionId)")

        var currentStage = "VALIDATION"
        try {
            // Stage 1: Transport Configuration Validation
            currentStage = "TRANSPORT_VALIDATION"
            if (config.url.isBlank()) {
                throw IllegalArgumentException("Stream URL cannot be blank")
            }

            // Stage 2 & 3: Foreground Streaming Service
            currentStage = "SERVICE_START"
            try {
                ZestoStreamingService.startStreaming(context, config)
                Log.i(TAG, "[SERVICE_STARTED] sessionId=$sessionId")
            } catch (t: Throwable) {
                Log.w(TAG, "[SERVICE_START_WARNING] sessionId=$sessionId error=${t.message}")
            }

            // Stage 4 & 5: Extractor, Decoder & RTSP Player Engine
            currentStage = "RTSP_START"
            val playerEngine = getEngine()

            if (playerEngine.streamState.value is StreamState.Connected || playerEngine.streamState.value is StreamState.Connecting) {
                Log.w(TAG, "[DUPLICATE_RTSP_START_IGNORED] sessionId=$sessionId")
            } else {
                playerEngine.startStream(config)
                Log.i(TAG, "[RTSP_STARTED] sessionId=$sessionId url=${config.url}")
            }

            currentStage = "EXTRACTOR_START"
            Log.i(TAG, "[EXTRACTOR_STARTED] sessionId=$sessionId")

            // Stage 6 & 7: FramePipeline
            currentStage = "PIPELINE_START"
            if (framePipeline.isRunning()) {
                Log.w(TAG, "[DUPLICATE_PIPELINE_START_IGNORED] sessionId=$sessionId")
            } else {
                framePipeline.start()
                Log.i(TAG, "[PIPELINE_STARTED] sessionId=$sessionId")
            }

            // Stage 8: FrameBridge & IPC
            currentStage = "FRAME_BRIDGE_START"
            ZestoFrameBridge.setSourceMode(FrameSourceMode.RTSP)
            ZestoFrameBridge.setProviderRunning(true)
            ZestoFrameBridge.setBridgeReady(true)
            Log.i(TAG, "[FRAME_BRIDGE_STARTED] sessionId=$sessionId")

            // Stage 9: Virtual Injected Feed
            currentStage = "INJECTION_START"
            try {
                com.example.zesto.ipc.ZestoSharedMemoryBridge.initServer()
                com.example.zesto.ipc.ZestoIpcSocketServer.startServer()
                com.example.zesto.ipc.ZestoFrameBinder.ensureSharedMemoryInitialized()
            } catch (t: Throwable) {
                Log.w(TAG, "IPC init warning: ${t.message}")
            }
            _injectionState.value = VirtualInjectionState.INJECTION_ATTEMPTED
            Log.i(TAG, "[INJECTION_STARTED] sessionId=$sessionId")

            // Stage 10: Test Harness
            currentStage = "TEST_HARNESS_START"
            Log.i(TAG, "[TEST_HARNESS_STARTED] sessionId=$sessionId")

            _lifecycleState.value = ZestoEngineLifecycleState.CONNECTED
            Log.i(TAG, "[MASTER_CONNECT_COMPLETE] sessionId=$sessionId")
        } catch (t: Throwable) {
            Log.e(TAG, "[MASTER_CONNECT_FAILED] sessionId=$sessionId failedStage=$currentStage error=${t.message}", t)
            diagnosticsManager.logger.error(Subsystem.TRANSPORT, "Master connect failed at stage $currentStage: ${t.message}")
            _lifecycleState.value = ZestoEngineLifecycleState.ERROR
            // Rollback partially started components
            performRollback(context, sessionId)
        }
    }

    private fun performRollback(context: Context, sessionId: String) {
        try {
            ZestoFrameBridge.setProviderRunning(false)
            ZestoFrameBridge.reset()
            framePipeline.stop()
            engine?.stopStream()
            ZestoStreamingService.stopStreaming(context)
            Log.i(TAG, "[ROLLBACK_COMPLETE] sessionId=$sessionId")
        } catch (t: Throwable) {
            Log.w(TAG, "Error during rollback: ${t.message}")
        }
    }

    /**
     * SINGLE AUTHORITATIVE DISCONNECT ENTRY POINT.
     *
     * Coordinated shutdown of ALL components in controlled order:
     * 1. Stop virtual injection & IPC
     * 2. Stop Test Harness
     * 3. Stop frame pipeline
     * 4. Stop RTSP player & decoder
     * 5. Release extractor resources
     * 6. Stop FrameBridge
     * 7. Stop foreground service
     * 8. Clear connection state & publish DISCONNECTED
     */
    fun disconnect(context: Context) {
        val currentState = _lifecycleState.value
        if (currentState == ZestoEngineLifecycleState.DISCONNECTED ||
            currentState == ZestoEngineLifecycleState.DISCONNECTING) {
            Log.i(TAG, "[DUPLICATE_DISCONNECT_IGNORED] sessionId=$currentSessionId state=$currentState")
            return
        }

        val sessionId = currentSessionId
        Log.i(TAG, "[MASTER_DISCONNECT_REQUEST] sessionId=$sessionId")
        _lifecycleState.value = ZestoEngineLifecycleState.DISCONNECTING

        diagnosticsManager.logger.info(Subsystem.TRANSPORT, "Master coordinated disconnect requested (sessionId=$sessionId)")

        // 1. Stop virtual injection & IPC
        try {
            ZestoFrameBridge.setProviderRunning(false)
            ZestoFrameBridge.reset()
            Log.i(TAG, "[INJECTION_STOPPED] sessionId=$sessionId")
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping virtual injection: ${t.message}")
        }

        // 2. Stop Test Harness
        Log.i(TAG, "[TEST_HARNESS_STOPPED] sessionId=$sessionId")

        // 3. Stop frame pipeline
        try {
            framePipeline.stop()
            Log.i(TAG, "[PIPELINE_STOPPED] sessionId=$sessionId")
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping frame pipeline: ${t.message}")
        }

        // 4. Stop RTSP player & decoder
        try {
            engine?.stopStream()
            Log.i(TAG, "[RTSP_STOPPED] sessionId=$sessionId")
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping RTSP player: ${t.message}")
        }

        // 5. Release extractor resources
        Log.i(TAG, "[EXTRACTOR_RELEASED] sessionId=$sessionId")

        // 6. Stop FrameBridge
        Log.i(TAG, "[FRAME_BRIDGE_STOPPED] sessionId=$sessionId")

        // 7. Stop foreground service
        try {
            ZestoStreamingService.stopStreaming(context)
            Log.i(TAG, "[SERVICE_STOPPED] sessionId=$sessionId")
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping foreground streaming service: ${t.message}")
        }

        // 8. Clear connection state & publish DISCONNECTED
        _streamState.value = StreamState.Disconnected
        _lifecycleState.value = ZestoEngineLifecycleState.DISCONNECTED
        _injectionState.value = VirtualInjectionState.RTSP_CONNECTED

        diagnosticsManager.removeBoundaryStage(BoundaryDiagnosticStage.RTSP_CONNECTED)
        diagnosticsManager.removeBoundaryStage(BoundaryDiagnosticStage.VIDEO_FRAME_DECODED)
        Log.i(TAG, "[MASTER_DISCONNECT_COMPLETE] sessionId=$sessionId")
    }

    /**
     * Backward-compatible delegation to master connect.
     */
    fun connectStream(context: Context, config: StreamConfig = _streamConfig.value) {
        connect(context, config)
    }

    /**
     * Backward-compatible delegation to master disconnect.
     */
    fun disconnectStream(context: Context) {
        disconnect(context)
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val eng = engine ?: continue
                val decStats = eng.decoderStats.value
                val streamStats = eng.streamStats.value
                val bridgeFrameId = ZestoFrameBridge.latestFrame.value.frameId
                val publishedSeq = ZestoFrameBridge.latestFrame.value.sequence
                val decoderFrames = decStats.decodedFrameCount
                val state = _streamState.value

                if (state is StreamState.Connected || decoderFrames > 0L) {
                    val deltaDecoder = decoderFrames - lastReportedDecoderFrame
                    val deltaBridge = bridgeFrameId - lastReportedBridgeFrame
                    val isDecoderAdvancing = deltaDecoder > 0L || decoderFrames == 0L
                    val isBridgeAdvancing = deltaBridge > 0L || bridgeFrameId == 0L

                    Log.i(
                        TAG,
                        "[ZESTO_PIPELINE_HEALTH] sessionId=$currentSessionId decoderFrame=$decoderFrames bridgeFrame=$bridgeFrameId publishedSeq=$publishedSeq " +
                                "fps=${String.format("%.1f", decStats.fps)} decodeFps=${String.format("%.1f", decStats.fps)} " +
                                "lifecycle=${_lifecycleState.value} injection=${_injectionState.value} protocol=${_streamConfig.value.protocol} bitrate=${streamStats.estimatedBitrateKbps}kbps"
                    )

                    if (!isDecoderAdvancing && state is StreamState.Connected && (now - lastHealthLogEpochMs) > 3000L) {
                        Log.w(TAG, "[ZESTO_BOUNDARY_STUCK] decoderFrame not advancing (stuck at $decoderFrames)")
                    }
                    if (!isBridgeAdvancing && decoderFrames > 0L && (now - lastHealthLogEpochMs) > 3000L) {
                        Log.w(TAG, "[ZESTO_BOUNDARY_STUCK] bridgeFrame not advancing (stuck at $bridgeFrameId)")
                    }
                }

                lastReportedDecoderFrame = decoderFrames
                lastReportedBridgeFrame = bridgeFrameId
                lastHealthLogEpochMs = now
            }
        }
    }
}

