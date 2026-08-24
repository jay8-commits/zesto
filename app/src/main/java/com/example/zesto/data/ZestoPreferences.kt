package com.example.zesto.data

import android.content.Context
import android.content.SharedPreferences
import com.example.zesto.stream.StreamConfig
import com.example.zesto.stream.TransportProtocol

/**
 * Manages persistent user preferences and connection configurations.
 */
class ZestoPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("zesto_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RTSP_URL = "key_rtsp_url"
        private const val KEY_PROTOCOL = "key_protocol"
        private const val KEY_TARGET_WIDTH = "key_target_width"
        private const val KEY_TARGET_HEIGHT = "key_target_height"
        private const val KEY_TARGET_FPS = "key_target_fps"
        private const val KEY_AUTO_RECONNECT = "key_auto_reconnect"
        private const val DEFAULT_RTSP_URL = "rtsp://192.168.1.100:8554/live"
    }

    fun loadStreamConfig(): StreamConfig {
        val url = prefs.getString(KEY_RTSP_URL, DEFAULT_RTSP_URL) ?: DEFAULT_RTSP_URL
        val protocolName = prefs.getString(KEY_PROTOCOL, TransportProtocol.RTSP_TCP.name)
        val protocol = try {
            TransportProtocol.valueOf(protocolName ?: TransportProtocol.RTSP_TCP.name)
        } catch (e: Exception) {
            TransportProtocol.RTSP_TCP
        }
        val width = prefs.getInt(KEY_TARGET_WIDTH, 1280)
        val height = prefs.getInt(KEY_TARGET_HEIGHT, 720)
        val fps = prefs.getInt(KEY_TARGET_FPS, 30)
        val autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true)

        return StreamConfig(
            url = url,
            protocol = protocol,
            targetWidth = width,
            targetHeight = height,
            targetFps = fps,
            autoReconnect = autoReconnect
        )
    }

    fun saveStreamConfig(config: StreamConfig) {
        prefs.edit()
            .putString(KEY_RTSP_URL, config.url)
            .putString(KEY_PROTOCOL, config.protocol.name)
            .putInt(KEY_TARGET_WIDTH, config.targetWidth)
            .putInt(KEY_TARGET_HEIGHT, config.targetHeight)
            .putInt(KEY_TARGET_FPS, config.targetFps)
            .putBoolean(KEY_AUTO_RECONNECT, config.autoReconnect)
            .apply()
    }
}
