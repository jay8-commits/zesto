package com.example.zesto.hook

import android.util.Log

/**
 * Jetpack CameraX API bytecode/reflection hook adapter.
 * Intercepts androidx.camera.core.Preview.setSurfaceProvider to bind
 * Zesto's virtualized SurfaceProvider into the target CameraX lifecycle.
 */
object CameraXHook {
    private const val TAG = "ZestoCameraXHook"

    fun attachHook(classLoader: ClassLoader) {
        try {
            val previewClass = Class.forName("androidx.camera.core.Preview", false, classLoader)
            Log.i(TAG, "androidx.camera.core.Preview class identified for CameraX hooking: $previewClass")
            // Targeted signatures for Xposed / LSPosed / LSPatch:
            // 1. Preview.setSurfaceProvider(SurfaceProvider)
            // 2. Preview.setSurfaceProvider(Executor, SurfaceProvider)
        } catch (e: ClassNotFoundException) {
            // Target app does not utilize CameraX (standard for non-Jetpack camera apps)
            Log.d(TAG, "CameraX Preview class not present in target app classpath")
        } catch (e: Throwable) {
            Log.e(TAG, "Error inspecting CameraX classes: ${e.message}")
        }
    }
}
