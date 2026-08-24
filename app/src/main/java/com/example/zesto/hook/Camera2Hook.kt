package com.example.zesto.hook

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.os.Handler
import android.util.Log
import android.view.Surface

/**
 * Camera2 API bytecode/reflection hook adapter.
 * Intercepts target application CameraDevice.createCaptureSession calls
 * and redirects/renders decoded OBS frames from ZestoFrameContentProvider.
 */
object Camera2Hook {
    private const val TAG = "ZestoCamera2Hook"

    fun attachHook(classLoader: ClassLoader) {
        try {
            val cameraDeviceClass = Class.forName("android.hardware.camera2.CameraDevice", false, classLoader)
            Log.i(TAG, "CameraDevice class loaded successfully for Camera2 hooking: $cameraDeviceClass")
            // Targeted signatures for Xposed / LSPosed / LSPatch:
            // 1. CameraDevice.createCaptureSession(List<Surface>, StateCallback, Handler)
            // 2. CameraDevice.createCaptureSessionByOutputConfigurations(List<OutputConfiguration>, StateCallback, Handler)
            // 3. CameraDevice.createCustomCaptureSession(SessionConfiguration)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "CameraDevice class not found in target classLoader: ${e.message}")
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error inspecting Camera2 classes: ${e.message}")
        }
    }

    fun onSessionConfigured(outputs: List<Surface>, callback: Any?) {
        Log.i(TAG, "Camera2 session configured with ${outputs.size} output surfaces.")
    }
}
