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
import com.example.zesto.decoder.HardwareVideoDecoder
import com.example.zesto.decoder.VideoDecoder
import com.example.zesto.frame.FramePipeline
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import com.example.zesto.frame.ZestoFrameBridge
import com.example.zesto.stream.RTSPPlayerEngine
import com.example.zesto.stream.StreamConfig
import com.example.zesto.stream.StreamState
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
 * Background Foreground Service managing the RTSP ingestion, hardware decoding,
 * and frame delivery pipeline independently of the UI activity lifecycle.
 */
class ZestoStreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "zesto_streaming_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.zesto.action.START_STREAM"
        const val ACTION_STOP = "com.example.zesto.action.STOP_STREAM"
        const val EXTRA_CONFIG_URL = "extra_config_url"
        const val EXTRA_CONFIG_PORT = "extra_config_port"
        const val EXTRA_CONFIG_WIDTH = "extra_config_width"
        const val EXTRA_CONFIG_HEIGHT = "extra_config_height"

        fun startStreaming(context: Context, config: StreamConfig) {
            val intent = Intent(context, ZestoStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG_URL, config.url)
                putExtra(EXTRA_CONFIG_WIDTH, config.targetWidth)
                putExtra(EXTRA_CONFIG_HEIGHT, config.targetHeight)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }


        fun stopStreaming(context: Context) {
            val intent = Intent(context, ZestoStreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        val service: ZestoStreamingService get() = this@ZestoStreamingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var playerEngine: RTSPPlayerEngine
    private val videoDecoder: VideoDecoder = HardwareVideoDecoder()
    private val framePipeline = FramePipeline()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var activeConfig: StreamConfig = StreamConfig()
    private var framePostingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        playerEngine = RTSPPlayerEngine(applicationContext, serviceScope)

        videoDecoder.configure(width = 1280, height = 720)
        videoDecoder.start()
        framePipeline.start()

        observePlayerState()
    }

    private fun observePlayerState() {
        serviceScope.launch {
            playerEngine.streamState.collect { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_CONFIG_URL) ?: activeConfig.url
                val width = intent.getIntExtra(EXTRA_CONFIG_WIDTH, activeConfig.targetWidth)
                val height = intent.getIntExtra(EXTRA_CONFIG_HEIGHT, activeConfig.targetHeight)

                activeConfig = activeConfig.copy(url = url, targetWidth = width, targetHeight = height)
                startForeground(NOTIFICATION_ID, buildNotification("Connecting to RTSP source..."))
                startPipelineInternal(activeConfig)
            }
            ACTION_STOP -> {
                stopPipelineInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startPipelineInternal(config: StreamConfig) {
        _isRunning.value = true
        playerEngine.startStream(config)

        framePostingJob?.cancel()
        framePostingJob = serviceScope.launch {
            while (_isRunning.value) {
                kotlinx.coroutines.delay(33L) // ~30 FPS frame dispatch into IPC bridge
                if (playerEngine.streamState.value is StreamState.Connected) {
                    ZestoFrameBridge.postFrame(
                        width = config.targetWidth,
                        height = config.targetHeight,
                        format = PixelFormat.RGBA_8888
                    )
                }
            }
        }
    }

    private fun stopPipelineInternal() {
        _isRunning.value = false
        framePostingJob?.cancel()
        framePostingJob = null
        playerEngine.stopStream()
        ZestoFrameBridge.reset()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zesto Video Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Coordinates RTSP stream ingestion, decoding, and frame delivery"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zesto Live Camera Service")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: StreamState) {
        val text = when (state) {
            is StreamState.Connected -> "Streaming active: ${state.url}"
            is StreamState.Connecting -> "Connecting to RTSP stream..."
            is StreamState.Reconnecting -> "Reconnecting (${state.attempt}/${state.maxAttempts})..."
            is StreamState.Error -> "Stream error: ${state.message}"
            is StreamState.Disconnected -> "Stream idle"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopPipelineInternal()
        playerEngine.release()
        videoDecoder.release()
        framePipeline.stop()
        serviceScope.cancel()
    }
}
