package com.example.zesto.hook

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import java.lang.reflect.Method

/**
 * Zesto Camera Virtualization Hook Entry Point.
 * Compatible with LSPosed (Root) and LSPatch / NPatch (Non-Root APK patching).
 *
 * Implements the standard Xposed Framework hook contract to intercept target camera sessions
 * and inject the real-time OBS stream from ZestoFrameContentProvider.
 */
class ZestoXposedInit {

    companion object {
        const val MODULE_TAG = "ZestoXposedHook"
        const val PROVIDER_URI = "content://com.example.zesto.frameprovider/frame"
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
                hookCamera2Pipeline(classLoader)
                hookLegacyCameraPipeline(classLoader)
                hookCameraXPipeline(classLoader)
            }
        } catch (_: Exception) {}
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
