package com.example.zesto.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Diagnostic stages for the LSPosed / LSPatch Xposed injection pipeline.
 */
enum class XposedHookLifecycle {
    MODULE_LOADED,
    TARGET_PROCESS_ATTACHED,
    CAMERA2_HOOK_INSTALLED,
    CAMERA_API_DETECTED,
    FRAME_SOURCE_CONNECTED,
    VIRTUAL_CAMERA_ACTIVE,
    HOOK_FAILED
}

/**
 * Zesto Camera Virtualization Hook Entry Point.
 * Compatible with LSPosed (Root) and LSPatch / NPatch (Non-Root APK patching).
 *
 * Implements the standard Xposed Framework hook contract to intercept target camera sessions
 * and inject the real-time OBS stream from ZestoFrameContentProvider / ZestoFrameBridge.
 */
class ZestoXposedInit {

    companion object {
        const val MODULE_TAG = "ZestoXposedHook"
        const val PROVIDER_URI = "content://com.example.zesto.frameprovider/frame"

        private var currentLifecycle = XposedHookLifecycle.MODULE_LOADED
        val lifecycle: XposedHookLifecycle get() = currentLifecycle
    }

    init {
        currentLifecycle = XposedHookLifecycle.MODULE_LOADED
        Log.i(MODULE_TAG, "[MODULE_LOADED] Zesto Xposed/LSPatch module initialized.")
    }

    /**
     * Called by LSPosed / LSPatch loader upon target package initialization.
     */
    fun handleLoadPackage(lpparam: Any) {
        try {
            val packageNameField = lpparam.javaClass.getField("packageName")
            val packageName = packageNameField.get(lpparam) as? String ?: return
            val classLoaderField = lpparam.javaClass.getField("classLoader")
            val classLoader = classLoaderField.get(lpparam) as? ClassLoader ?: return

            if (shouldHookPackage(packageName)) {
                currentLifecycle = XposedHookLifecycle.TARGET_PROCESS_ATTACHED
                Log.i(MODULE_TAG, "[TARGET_PROCESS_ATTACHED] Target process identified and attached: $packageName")

                hookCamera2Pipeline(classLoader)
                hookLegacyCameraPipeline(classLoader)
                hookCameraXPipeline(classLoader)

                currentLifecycle = XposedHookLifecycle.CAMERA2_HOOK_INSTALLED
                Log.i(MODULE_TAG, "[CAMERA2_HOOK_INSTALLED] Virtualization hooks registered for package: $packageName")
            }
        } catch (e: Throwable) {
            currentLifecycle = XposedHookLifecycle.HOOK_FAILED
            Log.e(MODULE_TAG, "[HOOK_FAILED] Error during load package handling: ${e.message}")
        }
    }

    private fun shouldHookPackage(packageName: String): Boolean {
        // Skip system packages and Zesto itself
        if (packageName == "android" || packageName == "com.android.systemui" || packageName == "com.example.zesto" || packageName == "com.example") {
            return false
        }
        return true
    }

    private fun hookCamera2Pipeline(classLoader: ClassLoader) {
        Camera2Hook.attachHook(classLoader)
    }

    private fun hookLegacyCameraPipeline(classLoader: ClassLoader) {
        LegacyCameraHook.attachHook(classLoader)
    }

    private fun hookCameraXPipeline(classLoader: ClassLoader) {
        CameraXHook.attachHook(classLoader)
    }
}

/**
 * Adapter providing real-time frame pulling across processes via ZestoFrameContentProvider.
 */
object ZestoRemoteFrameReceiver {

    fun queryLatestFrameMeta(context: Context): Bundle? {
        return try {
            val uri = Uri.parse(ZestoXposedInit.PROVIDER_URI)
            context.contentResolver.call(uri, "getFrameMeta", null, null)
        } catch (e: Exception) {
            null
        }
    }

    fun isZestoStreamingActive(context: Context): Boolean {
        return try {
            val uri = Uri.parse(ZestoXposedInit.PROVIDER_URI)
            val bundle = context.contentResolver.call(uri, "isStreaming", null, null)
            bundle?.getBoolean("is_streaming", false) ?: false
        } catch (e: Exception) {
            false
        }
    }
}
