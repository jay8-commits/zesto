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

    enum class EngineConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        RECONNECTING,
        ERROR
    }

    @Volatile
    private var instance: RTSPPlayerEngine? = null
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connectionState = MutableStateFlow(EngineConnectionState.DISCONNECTED)
    val connectionState: StateFlow<EngineConnectionState> = _connectionState.asStateFlow()

    private val _currentConfig = MutableStateFlow(StreamConfig())
    val currentConfig: StateFlow<StreamConfig> = _currentConfig.asStateFlow()

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
                    is StreamState.Connected -> EngineConnectionState.CONNECTED
                    is StreamState.Connecting -> EngineConnectionState.CONNECTING
                    is StreamState.Reconnecting -> EngineConnectionState.RECONNECTING
                    is StreamState.Disconnected -> EngineConnectionState.DISCONNECTED
                    is StreamState.Error -> EngineConnectionState.ERROR
                }
                _connectionState.value = mapped
                Log.i(TAG, "[ENGINE_STATE_CHANGED] state=$mapped url=${_currentConfig.value.url}")
            }
        }
    }

    fun startStream(context: Context, config: StreamConfig) {
        _currentConfig.value = config
        _connectionState.value = EngineConnectionState.CONNECTING
        Log.i(TAG, "[ENGINE_START_STREAM] Starting single authoritative stream via foreground service: ${config.url}")
        ZestoStreamingService.startStreaming(context, config)
    }

    fun stopStream(context: Context) {
        _connectionState.value = EngineConnectionState.DISCONNECTING
        Log.i(TAG, "[ENGINE_STOP_STREAM] Stopping single authoritative stream via foreground service")
        ZestoStreamingService.stopStreaming(context)
    }
}
