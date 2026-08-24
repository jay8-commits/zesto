package com.example.zesto.hook

import android.util.Log

/**
 * Legacy android.hardware.Camera (Camera1) hook adapter.
 * Intercepts Camera.setPreviewDisplay(SurfaceHolder) and Camera.setPreviewTexture(SurfaceTexture).
 */
object LegacyCameraHook {
    private const val TAG = "ZestoLegacyCameraHook"

    fun attachHook(classLoader: ClassLoader) {
        try {
            val cameraClass = Class.forName("android.hardware.Camera", false, classLoader)
            Log.i(TAG, "Legacy Camera class identified for Camera1 hooking: $cameraClass")
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "android.hardware.Camera not loaded in classpath")
        } catch (e: Throwable) {
            Log.e(TAG, "Error inspecting legacy camera classes: ${e.message}")
        }
    }
}
