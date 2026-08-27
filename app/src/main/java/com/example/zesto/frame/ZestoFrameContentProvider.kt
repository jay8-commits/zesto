package com.example.zesto.frame

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * IPC ContentProvider for zero-latency cross-process frame streaming.
 * Allows target applications and Xposed/LSPatch hooks running in other processes
 * to fetch live decoded video frames, health diagnostics, and metadata from Zesto.
 */
class ZestoFrameContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "ZestoFrameProvider"
        const val AUTHORITY = "com.example.zesto.frameprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/frame")

        const val METHOD_GET_LATEST_FRAME = "getLatestFrame"
        const val METHOD_GET_FRAME_META = "getFrameMeta"
        const val METHOD_IS_STREAMING = "isStreaming"
        const val METHOD_IS_PROVIDER_RUNNING = "isProviderRunning"
        const val METHOD_GET_HEALTH_STATE = "getHealthState"
        const val METHOD_REPORT_MILESTONE = "reportMilestone"

        const val KEY_FRAME_ID = "frame_id"
        const val KEY_BITMAP = "bitmap"
        const val KEY_BUFFER = "buffer"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_TIMESTAMP_US = "timestamp_us"
        const val KEY_FORMAT = "format"
        const val KEY_IS_STREAMING = "is_streaming"
        const val KEY_PROVIDER_RUNNING = "provider_running"
        const val KEY_BRIDGE_READY = "bridge_ready"
        const val KEY_BUFFER_SIZE = "buffer_size"
        const val KEY_HEALTH_STATE = "health_state"
        const val KEY_MS_SINCE_LAST_FRAME = "ms_since_last_frame"

        private val pipeExecutor = Executors.newCachedThreadPool()
    }

    override fun onCreate(): Boolean {
        ZestoFrameBridge.setProviderRunning(true)
        ZestoFrameBridge.setBridgeReady(true)
        Log.i(TAG, "[FRAME_PROVIDER] provider created")
        Log.i(TAG, "[FRAME_PROVIDER] provider registered/available (providerRunning=true)")
        Log.i(TAG, "[ZESTO_PROCESS_INIT] ZestoFrameContentProvider initialized.")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(
            KEY_FRAME_ID,
            KEY_WIDTH,
            KEY_HEIGHT,
            KEY_TIMESTAMP_US,
            KEY_FORMAT,
            KEY_HEALTH_STATE,
            KEY_MS_SINCE_LAST_FRAME
        ))
        val frame = ZestoFrameBridge.consumeLatestFrame()
        val health = ZestoFrameBridge.getFrameHealthState()
        val msAgo = ZestoFrameBridge.getMillisecondsSinceLastFrame()
        cursor.addRow(arrayOf<Any>(
            frame.frameId,
            frame.width,
            frame.height,
            frame.timestampUs,
            frame.format.name,
            health.name,
            msAgo
        ))
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        val frame = ZestoFrameBridge.consumeLatestFrame()
        val health = ZestoFrameBridge.getFrameHealthState()
        val msAgo = ZestoFrameBridge.getMillisecondsSinceLastFrame()
        val buffer = frame.buffer
        val isProvRunning = ZestoFrameBridge.isProviderRunning
        val isBrdgReady = ZestoFrameBridge.isBridgeReady

        result.putBoolean(KEY_PROVIDER_RUNNING, isProvRunning)
        result.putBoolean(KEY_BRIDGE_READY, isBrdgReady)

        when (method) {
            METHOD_GET_LATEST_FRAME, "getFrameBitmap" -> {
                val now = System.currentTimeMillis()
                val lastTimestamp = ZestoFrameBridge.lastFrameArrivalEpochMs
                val bridgeFps = ZestoFrameBridge.calculateBridgeFps()
                result.putLong(KEY_FRAME_ID, frame.frameId)
                result.putInt(KEY_WIDTH, frame.width)
                result.putInt(KEY_HEIGHT, frame.height)
                result.putLong(KEY_TIMESTAMP_US, frame.timestampUs)
                result.putString(KEY_FORMAT, frame.format.name)
                result.putString(KEY_HEALTH_STATE, health.name)
                result.putLong(KEY_MS_SINCE_LAST_FRAME, msAgo)
                result.putLong("current_time_ms", now)
                result.putLong("last_frame_timestamp_ms", lastTimestamp)
                result.putDouble("bridge_fps", bridgeFps)
                result.putBoolean("test_pattern_mode", ZestoFrameBridge.isTestPatternMode)
                result.putBoolean(KEY_IS_STREAMING, health == FrameHealthState.FRAME_ACTIVE && isProvRunning)
                if (frame.bitmap != null) {
                    result.putParcelable(KEY_BITMAP, frame.bitmap)
                }
                if (buffer != null) {
                    result.putByteArray(KEY_BUFFER, buffer)
                }

                if (frame.frameId == 1L || frame.frameId % 60L == 0L) {
                    Log.i(TAG, "[IPC_FRAME_RETURNED] frameId=${frame.frameId} size=${buffer?.size ?: 0}B timestampUs=${frame.timestampUs} health=$health elapsed=${msAgo}ms")
                }
            }
            METHOD_GET_FRAME_META -> {
                result.putLong(KEY_FRAME_ID, frame.frameId)
                result.putInt(KEY_WIDTH, frame.width)
                result.putInt(KEY_HEIGHT, frame.height)
                result.putLong(KEY_TIMESTAMP_US, frame.timestampUs)
                result.putString(KEY_FORMAT, frame.format.name)
                result.putInt(KEY_BUFFER_SIZE, buffer?.size ?: 0)
                result.putString(KEY_HEALTH_STATE, health.name)
                result.putLong(KEY_MS_SINCE_LAST_FRAME, msAgo)
                result.putBoolean(KEY_IS_STREAMING, health == FrameHealthState.FRAME_ACTIVE && isProvRunning)
            }
            METHOD_IS_STREAMING -> {
                result.putBoolean(KEY_IS_STREAMING, health == FrameHealthState.FRAME_ACTIVE && isProvRunning)
            }
            METHOD_IS_PROVIDER_RUNNING -> {
                result.putBoolean(KEY_PROVIDER_RUNNING, isProvRunning)
                result.putBoolean(KEY_BRIDGE_READY, isBrdgReady)
            }
            METHOD_GET_HEALTH_STATE -> {
                result.putString(KEY_HEALTH_STATE, health.name)
                result.putLong(KEY_MS_SINCE_LAST_FRAME, msAgo)
            }
            METHOD_REPORT_MILESTONE -> {
                val stage = arg ?: extras?.getString("stage") ?: "UNKNOWN"
                val pkg = extras?.getString("package_name") ?: "UNKNOWN"
                val msg = extras?.getString("message") ?: ""
                ZestoFrameBridge.reportExternalMilestone(stage, pkg, msg)
                result.putBoolean("success", true)
            }
            else -> {
                Log.w(TAG, "Unknown method called: $method")
            }
        }
        return result
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val frame = ZestoFrameBridge.consumeLatestFrame()
        val buffer = frame.buffer ?: return null

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        pipeExecutor.execute {
            try {
                FileOutputStream(writeSide.fileDescriptor).use { output ->
                    output.write(buffer)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                try {
                    writeSide.close()
                } catch (_: Exception) {}
            }
        }

        return readSide
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
