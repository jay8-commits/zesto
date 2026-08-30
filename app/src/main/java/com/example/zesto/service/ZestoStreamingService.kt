package com.example.zesto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.zesto.decoder.FrameDecodeListener
import com.example.zesto.decoder.HardwareVideoDecoder
import com.example.zesto.decoder.VideoDecoder
import com.example.zesto.frame.FrameConsumer
import com.example.zesto.frame.FramePipeline
import com.example.zesto.frame.FrameProvider
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import com.example.zesto.frame.ZestoFrameBridge
import com.example.zesto.stream.RTSPPlayerEngine
import com.example.zesto.stream.StreamConfig
import com.example.zesto.stream.StreamState
import com.example.zesto.stream.ZestoStreamEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Explicit service runtime diagnostics state.
 */
enum class ServiceRuntimeState {
    SERVICE_STARTED,
    SERVICE_RUNNING,
    SERVICE_STOPPED,
    STREAM_ACTIVE_IN_BACKGROUND,
    STREAM_INTERRUPTED,
    STREAM_RECONNECTED
}

/**
 * Background Foreground Service managing the RTSP ingestion,
 * hardware decoding, and frame delivery pipeline independently
 * of the UI activity lifecycle.
 */
class ZestoStreamingService : Service() {

    companion object {

        const val CHANNEL_ID =
            "zesto_streaming_channel"

        const val NOTIFICATION_ID =
            1001

        const val ACTION_START =
            "com.example.zesto.action.START_STREAM"

        const val ACTION_STOP =
            "com.example.zesto.action.STOP_STREAM"

        const val EXTRA_CONFIG_URL =
            "extra_config_url"

        const val EXTRA_CONFIG_PORT =
            "extra_config_port"

        const val EXTRA_CONFIG_WIDTH =
            "extra_config_width"

        const val EXTRA_CONFIG_HEIGHT =
            "extra_config_height"

        private val _globalServiceState =
            MutableStateFlow(
                ServiceRuntimeState.SERVICE_STOPPED
            )

        val globalServiceState:
            StateFlow<ServiceRuntimeState> =
            _globalServiceState.asStateFlow()

        fun startStreaming(
            context: Context,
            config: StreamConfig
        ) {
            val intent =
                Intent(
                    context,
                    ZestoStreamingService::class.java
                ).apply {

                    action =
                        ACTION_START

                    putExtra(
                        EXTRA_CONFIG_URL,
                        config.url
                    )

                    putExtra(
                        EXTRA_CONFIG_WIDTH,
                        config.targetWidth
                    )

                    putExtra(
                        EXTRA_CONFIG_HEIGHT,
                        config.targetHeight
                    )
                }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                context.startForegroundService(
                    intent
                )
            } else {
                context.startService(
                    intent
                )
            }
        }

        fun stopStreaming(
            context: Context
        ) {
            val intent =
                Intent(
                    context,
                    ZestoStreamingService::class.java
                ).apply {
                    action = ACTION_STOP
                }

            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {

        val service:
            ZestoStreamingService
            get() = this@ZestoStreamingService
    }

    private val binder =
        LocalBinder()

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main
        )

    private val playerEngine: RTSPPlayerEngine
        get() = ZestoStreamEngineManager.getEngine()

    private val _isRunning =
        MutableStateFlow(false)

    val isRunning:
        StateFlow<Boolean> =
        _isRunning.asStateFlow()

    private val _runtimeState =
        MutableStateFlow(
            ServiceRuntimeState.SERVICE_STARTED
        )

    val runtimeState:
        StateFlow<ServiceRuntimeState> =
        _runtimeState.asStateFlow()

    private var activeConfig:
        StreamConfig =
        StreamConfig()

    private var playerStateJob:
        Job? = null

    override fun onCreate() {
        super.onCreate()

        _runtimeState.value =
            ServiceRuntimeState.SERVICE_STARTED

        _globalServiceState.value =
            ServiceRuntimeState.SERVICE_STARTED

        createNotificationChannel()

        ZestoStreamEngineManager.initialize(applicationContext)

        ZestoFrameBridge.setProviderRunning(true)
        ZestoFrameBridge.setBridgeReady(true)
        try {
            com.example.zesto.ipc.ZestoFrameBinder.ensureSharedMemoryInitialized()
            com.example.zesto.ipc.ZestoIpcSocketServer.startServer()
            com.example.zesto.ipc.ZestoSharedMemoryBridge.initServer()
        } catch (_: Throwable) {}

        observePlayerState()
    }

    private fun observePlayerState() {

        playerStateJob?.cancel()

        playerStateJob =
            serviceScope.launch {

                launch {
                    ZestoStreamEngineManager.lifecycleState.collect { lState ->
                        when (lState) {
                            com.example.zesto.stream.ZestoEngineLifecycleState.RUNNING -> {
                                _runtimeState.value = ServiceRuntimeState.STREAM_ACTIVE_IN_BACKGROUND
                                _globalServiceState.value = ServiceRuntimeState.STREAM_ACTIVE_IN_BACKGROUND
                                _isRunning.value = true
                            }
                            com.example.zesto.stream.ZestoEngineLifecycleState.CONNECTED -> {
                                _runtimeState.value = ServiceRuntimeState.SERVICE_RUNNING
                                _globalServiceState.value = ServiceRuntimeState.SERVICE_RUNNING
                                _isRunning.value = true
                            }
                            com.example.zesto.stream.ZestoEngineLifecycleState.CONNECTING -> {
                                _runtimeState.value = ServiceRuntimeState.SERVICE_RUNNING
                                _globalServiceState.value = ServiceRuntimeState.SERVICE_RUNNING
                                _isRunning.value = true
                            }
                            com.example.zesto.stream.ZestoEngineLifecycleState.RECONNECTING -> {
                                _runtimeState.value = ServiceRuntimeState.STREAM_INTERRUPTED
                                _globalServiceState.value = ServiceRuntimeState.STREAM_INTERRUPTED
                            }
                            com.example.zesto.stream.ZestoEngineLifecycleState.ERROR -> {
                                _runtimeState.value = ServiceRuntimeState.STREAM_INTERRUPTED
                                _globalServiceState.value = ServiceRuntimeState.STREAM_INTERRUPTED
                            }
                            com.example.zesto.stream.ZestoEngineLifecycleState.DISCONNECTED,
                            com.example.zesto.stream.ZestoEngineLifecycleState.DISCONNECTING -> {
                                _runtimeState.value = ServiceRuntimeState.SERVICE_STOPPED
                                _globalServiceState.value = ServiceRuntimeState.SERVICE_STOPPED
                                _isRunning.value = false
                            }
                        }
                    }
                }

                launch {
                    ZestoStreamEngineManager.streamState.collect { state ->
                        updateNotification(state)
                    }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                val url =
                    intent.getStringExtra(
                        EXTRA_CONFIG_URL
                    )
                        ?: activeConfig.url

                val width =
                    intent.getIntExtra(
                        EXTRA_CONFIG_WIDTH,
                        activeConfig.targetWidth
                    )

                val height =
                    intent.getIntExtra(
                        EXTRA_CONFIG_HEIGHT,
                        activeConfig.targetHeight
                    )

                activeConfig =
                    activeConfig.copy(
                        url = url,
                        targetWidth = width,
                        targetHeight = height
                    )

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        "Connecting to RTSP source..."
                    )
                )

                _isRunning.value = true
                _runtimeState.value = ServiceRuntimeState.SERVICE_RUNNING
                _globalServiceState.value = ServiceRuntimeState.SERVICE_RUNNING
            }

            ACTION_STOP -> {

                _isRunning.value = false

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.N
                ) {
                    stopForeground(
                        STOP_FOREGROUND_REMOVE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }

                _runtimeState.value =
                    ServiceRuntimeState.SERVICE_STOPPED

                _globalServiceState.value =
                    ServiceRuntimeState.SERVICE_STOPPED

                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Zesto Video Streaming Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "Coordinates RTSP stream ingestion, decoding, and frame delivery"

                    setShowBadge(false)
                }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager?.createNotificationChannel(
                channel
            )
        }
    }

    private fun buildNotification(
        statusText: String
    ): Notification {

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                "Zesto Live Camera Service"
            )
            .setContentText(
                statusText
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_camera
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(
        state: StreamState
    ) {

        val text =
            when (state) {

                is StreamState.Connected ->
                    "Streaming active: ${state.url}"

                is StreamState.Connecting ->
                    "Connecting to RTSP stream..."

                is StreamState.Reconnecting ->
                    "Reconnecting (${state.attempt}/${state.maxAttempts})..."

                is StreamState.Error ->
                    "Stream error: ${state.message}"

                is StreamState.Disconnected ->
                    "Stream idle"
            }

        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as? NotificationManager

        manager?.notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )
    }

    override fun onBind(
        intent: Intent?
    ): IBinder {
        return binder
    }

    override fun onDestroy() {
        playerStateJob?.cancel()
        playerStateJob = null

        _runtimeState.value =
            ServiceRuntimeState.SERVICE_STOPPED

        _globalServiceState.value =
            ServiceRuntimeState.SERVICE_STOPPED

        serviceScope.cancel()

        super.onDestroy()
    }
}
