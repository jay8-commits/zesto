package com.zesto.app.bridge

import android.util.Log
import com.zesto.app.model.VideoFrame
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Interface for components that subscribe directly to canonical VideoFrames.
 */
fun interface VideoFrameListener {
    fun onFrameAvailable(frame: VideoFrame)
}

/**
 * Shared bridge between Zesto frame generator/decoder and camera injection hooks.
 *
 * Implements direct push-driven dispatching without decoupled polling timers or
 * intermediate authoritative bitmap caches.
 */
object ZestoFrameBridge {
    private const val TAG = "ZestoFrameBridge"
    private val latestFrameRef = AtomicReference<VideoFrame?>(null)
    private val frameListeners = CopyOnWriteArrayList<VideoFrameListener>()

    /**
     * Posts a new canonical VideoFrame from the pipeline and dispatches it
     * immediately to all registered consumers.
     */
    fun postFrame(frame: VideoFrame) {
        latestFrameRef.set(frame)

        for (listener in frameListeners) {
            try {
                listener.onFrameAvailable(frame)
            } catch (t: Throwable) {
                Log.e(TAG, "Error dispatching frameId=${frame.frameId} to listener: ${t.message}")
            }
        }
    }

    fun getLatestFrame(): VideoFrame? = latestFrameRef.get()

    fun addFrameListener(listener: VideoFrameListener) {
        frameListeners.addIfAbsent(listener)
    }

    fun removeFrameListener(listener: VideoFrameListener) {
        frameListeners.remove(listener)
    }

    fun clear() {
        latestFrameRef.set(null)
    }
}
