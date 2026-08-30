package com.example.zesto.stream

import android.content.Context
import android.util.Log
import com.example.zesto.service.ZestoStreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Authoritative lifecycle manager for the single RTSP stream engine.
 * Ensures the connection state, configuration, and RTSP playback engine remain persistent
 * and unified across Activity, Compose UI, and Foreground Service lifecycles.
 */
object ZestoStreamEngineManager {
    private const val TAG = "ZestoStreamEngineManager"

    typealias EngineConnectionState = ZestoEngineLifecycleState

    @Volatile
    private var instance: RTSPPlayerEngine? = null
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _lifecycleState = MutableStateFlow(ZestoEngineLifecycleState.DISCONNECTED)
    val lifecycleState: StateFlow<ZestoEngineLifecycleState> = _lifecycleState.asStateFlow()
    val connectionState: StateFlow<ZestoEngineLifecycleState> = _lifecycleState.asStateFlow()

    private val _currentConfig = MutableStateFlow(StreamConfig())
    val currentConfig: StateFlow<StreamConfig> = _currentConfig.asStateFlow()

    fun initialize(context: Context) {
        getEngine(context)
    }

    fun getEngine(context: Context): RTSPPlayerEngine {
        val current = instance
        if (current != null) return current
        return synchronized(lock) {
            val secondCheck = instance
            if (secondCheck != null) {
                secondCheck
            } else {
                val newEngine = RTSPPlayerEngine(context.applicationContext)
                instance = newEngine
                observeEngineState(newEngine)
                newEngine
            }
        }
    }

    private fun observeEngineState(engine: RTSPPlayerEngine) {
        scope.launch {
            engine.streamState.collect { state ->
                val mapped = when (state) {
                    is StreamState.Connected -> ZestoEngineLifecycleState.CONNECTED
                    is StreamState.Streaming -> ZestoEngineLifecycleState.RUNNING
                    is StreamState.Stalling, is StreamState.Stalled -> ZestoEngineLifecycleState.CONNECTED
                    is StreamState.Connecting -> ZestoEngineLifecycleState.CONNECTING
                    is StreamState.Recovering, is StreamState.Reconnecting -> ZestoEngineLifecycleState.RECONNECTING
                    is StreamState.Disconnected -> ZestoEngineLifecycleState.DISCONNECTED
                    is StreamState.Error -> ZestoEngineLifecycleState.ERROR
                }
                _lifecycleState.value = mapped
                Log.i(TAG, "[ENGINE_STATE_CHANGED] state=$mapped url=${_currentConfig.value.url}")
            }
        }
    }

    fun connect(context: Context, config: StreamConfig) {
        if (config.url.isBlank()) {
            _lifecycleState.value = ZestoEngineLifecycleState.ERROR
            Log.e(TAG, "[ENGINE_CONNECT_INVALID] URL is blank, cannot connect")
            return
        }
        val current = _lifecycleState.value
        if (current == ZestoEngineLifecycleState.CONNECTING ||
            current == ZestoEngineLifecycleState.CONNECTED ||
            current == ZestoEngineLifecycleState.RUNNING) {
            if (_currentConfig.value.url == config.url) {
                Log.d(TAG, "[ENGINE_CONNECT_SKIPPED] Already active with matching URL")
                return
            }
        }
        startStream(context, config)
    }

    fun disconnect(context: Context) {
        if (_lifecycleState.value == ZestoEngineLifecycleState.DISCONNECTED) {
            return
        }
        stopStream(context)
        _lifecycleState.value = ZestoEngineLifecycleState.DISCONNECTED
    }

    fun startStream(context: Context, config: StreamConfig) {
        _currentConfig.value = config
        _lifecycleState.value = ZestoEngineLifecycleState.CONNECTING
        Log.i(TAG, "[ENGINE_START_STREAM] Starting single authoritative stream via foreground service: ${config.url}")
        ZestoStreamingService.startStreaming(context, config)
    }

    fun stopStream(context: Context) {
        _lifecycleState.value = ZestoEngineLifecycleState.DISCONNECTING
        Log.i(TAG, "[ENGINE_STOP_STREAM] Stopping single authoritative stream via foreground service")
        ZestoStreamingService.stopStreaming(context)
    }
}
