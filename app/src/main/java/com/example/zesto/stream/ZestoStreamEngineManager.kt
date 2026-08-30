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
 * Authoritative singleton owner of the Zesto RTSP stream and decoding pipeline.
 *
 * Guarantees a SINGLE RTSP player instance and a single connection lifecycle
 * shared across the UI (Activities/ViewModels) and Background Services.
 */
object ZestoStreamEngineManager {
    private const val TAG = "ZestoStreamEngineManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var engine: RTSPPlayerEngine? = null

    val diagnosticsManager = DiagnosticsManager()
    val framePipeline = FramePipeline()

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
                    if (state is StreamState.Connected) {
                        diagnosticsManager.recordBoundaryStage(BoundaryDiagnosticStage.RTSP_CONNECTED)
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
     * Authoritative single connect action.
     * Starts foreground service to keep decoder alive and initiates RTSP playback.
     */
    fun connectStream(context: Context, config: StreamConfig = _streamConfig.value) {
        initialize(context)
        _streamConfig.value = config
        val current = _streamState.value
        if (current is StreamState.Connected || current is StreamState.Connecting) {
            Log.i(TAG, "[CONNECT_IGNORED] Already connecting/connected: $current")
            return
        }

        diagnosticsManager.logger.info(Subsystem.TRANSPORT, "Authoritative connect requested for ${config.url}")
        ZestoFrameBridge.setSourceMode(com.example.zesto.frame.FrameSourceMode.RTSP)
        ZestoFrameBridge.setProviderRunning(true)

        // Ensure background foreground service is running so OS doesn't kill decoder
        try {
            ZestoStreamingService.startStreaming(context, config)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed starting ZestoStreamingService foreground: ${t.message}")
        }

        getEngine().startStream(config)
    }

    /**
     * Authoritative single disconnect action.
     */
    fun disconnectStream(context: Context) {
        initialize(context)
        diagnosticsManager.logger.info(Subsystem.TRANSPORT, "Authoritative disconnect requested")

        getEngine().stopStream()
        ZestoFrameBridge.setProviderRunning(false)

        try {
            ZestoStreamingService.stopStreaming(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed stopping ZestoStreamingService: ${t.message}")
        }

        diagnosticsManager.removeBoundaryStage(BoundaryDiagnosticStage.RTSP_CONNECTED)
        diagnosticsManager.removeBoundaryStage(BoundaryDiagnosticStage.VIDEO_FRAME_DECODED)
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
                        "[ZESTO_PIPELINE_HEALTH] decoderFrame=$decoderFrames bridgeFrame=$bridgeFrameId publishedSeq=$publishedSeq " +
                                "fps=${String.format("%.1f", decStats.fps)} decodeFps=${String.format("%.1f", decStats.fps)} " +
                                "connectionState=${state.javaClass.simpleName} protocol=${_streamConfig.value.protocol} bitrate=${streamStats.estimatedBitrateKbps}kbps"
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
