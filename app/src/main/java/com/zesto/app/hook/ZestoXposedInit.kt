package com.zesto.app.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Main Xposed / LSPosed module entry point.
 */
class ZestoXposedInit : IXposedHookLoadPackage {
    private val camera2Hook = Camera2Hook()
    private val legacyCameraHook = LegacyCameraHook()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.zesto.app") {
            // Self package ignore or testing
            return
        }

        // Initialize Camera2 hooks
        camera2Hook.initHooks(lpparam.classLoader)

        // Initialize Legacy Camera1 hooks
        legacyCameraHook.initHooks(lpparam.classLoader)
    }
}
