package com.example.zesto.frame

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * IPC ContentProvider for cross-process frame streaming.
 * Allows target applications and Xposed/LSPatch hooks running in other processes
 * to fetch live decoded video frames and metadata from Zesto.
 */
class ZestoFrameContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.zesto.frameprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/frame")

        const val METHOD_GET_FRAME_META = "getFrameMeta"
        const val METHOD_IS_STREAMING = "isStreaming"

        const val KEY_FRAME_ID = "frame_id"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_TIMESTAMP_US = "timestamp_us"
        const val KEY_FORMAT = "format"
        const val KEY_IS_STREAMING = "is_streaming"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(KEY_FRAME_ID, KEY_WIDTH, KEY_HEIGHT, KEY_TIMESTAMP_US, KEY_FORMAT))
        val frame = ZestoFrameBridge.consumeLatestFrame()
        cursor.addRow(arrayOf(frame.frameId, frame.width, frame.height, frame.timestampUs, frame.format.name))
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = Bundle()
        when (method) {
            METHOD_GET_FRAME_META -> {
                val frame = ZestoFrameBridge.consumeLatestFrame()
                result.putLong(KEY_FRAME_ID, frame.frameId)
                result.putInt(KEY_WIDTH, frame.width)
                result.putInt(KEY_HEIGHT, frame.height)
                result.putLong(KEY_TIMESTAMP_US, frame.timestampUs)
                result.putString(KEY_FORMAT, frame.format.name)
            }
            METHOD_IS_STREAMING -> {
                val frame = ZestoFrameBridge.latestFrame.value
                val isRecent = (System.nanoTime() / 1000 - frame.timestampUs) < 1_000_000 // Within 1s
                result.putBoolean(KEY_IS_STREAMING, frame.frameId > 0 && isRecent)
            }
        }
        return result
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val frame = ZestoFrameBridge.consumeLatestFrame()
        val buffer = frame.buffer ?: return null

        val cacheFile = File(context.cacheDir, "latest_frame.bin")
        FileOutputStream(cacheFile).use { it.write(buffer) }
        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
