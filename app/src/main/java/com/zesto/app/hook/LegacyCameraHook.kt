package com.zesto.app.hook

import android.hardware.Camera
import com.zesto.app.bridge.ZestoFrameBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Legacy Camera1 API Hook intercepting setPreviewCallback / setOneShotPreviewCallback / setPreviewTexture
 */
class LegacyCameraHook {
    fun initHooks(classLoader: ClassLoader) {
        try {
            XposedBridge.hookAllMethods(
                Camera::class.java,
                "setPreviewCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callback = param.args[0] as? Camera.PreviewCallback
                        if (callback != null) {
                            // Intercept preview callback with synthetic NV21 frame
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("LegacyCameraHook error: ${e.message}")
        }
    }
}
