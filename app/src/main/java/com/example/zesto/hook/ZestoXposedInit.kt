package com.example.zesto.hook

import android.util.Log
import com.example.zesto.target.TargetApplicationPatcherInspector
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

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
class ZestoXposedInit : IXposedHookLoadPackage {

    companion object {
        const val MODULE_TAG = "ZestoXposedHook"
        const val PROVIDER_URI = "content://com.example.zesto.frameprovider/frame"

        private var currentLifecycle = XposedHookLifecycle.MODULE_LOADED
        val lifecycle: XposedHookLifecycle get() = currentLifecycle
    }

    init {
        currentLifecycle = XposedHookLifecycle.MODULE_LOADED
        Log.i(MODULE_TAG, "[ZESTO_PROCESS_INIT] Zesto Xposed/LSPatch module initialized in memory.")
    }

    /**
     * Called by LSPosed / LSPatch loader upon target package initialization.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        val processName = lpparam.processName ?: packageName
        val classLoader = lpparam.classLoader ?: return

        Log.i(MODULE_TAG, "[ZESTO_PROCESS_INIT] Initializing Zesto hook inside target process: $processName ($packageName)")

        if (shouldHookPackage(packageName)) {
            currentLifecycle = XposedHookLifecycle.TARGET_PROCESS_ATTACHED
            ZestoRemoteFrameSource.setAttachedPackage(packageName)

            // Emit PATCH_MANIFEST_APPLICATION diagnostic milestone
            val originalAppClass = TargetApplicationPatcherInspector.resolveKnownLegitimateApplicationClass(packageName) ?: "android.app.Application"
            val logManifestDiag = "[PATCH_MANIFEST_APPLICATION] original=$originalAppClass patched=$originalAppClass"
            Log.i(MODULE_TAG, logManifestDiag)
            ZestoRemoteFrameSource.reportMilestone("PATCH_MANIFEST_APPLICATION", logManifestDiag)

            val targetLog = "[TARGET_PROCESS_ATTACHED]\nPACKAGE=$packageName\nPROCESS=$processName\nCLASSLOADER=${classLoader.javaClass.name}"
            Log.i(MODULE_TAG, targetLog)
            ZestoRemoteFrameSource.reportMilestone("TARGET_PROCESS_ATTACHED", "Attached to target package $packageName (process $processName)")

            try {
                hookCamera2Pipeline(classLoader, packageName)
                hookLegacyCameraPipeline(classLoader, packageName)
                hookCameraXPipeline(classLoader, packageName)

                currentLifecycle = XposedHookLifecycle.CAMERA2_HOOK_INSTALLED
                Log.i(MODULE_TAG, "[CAMERA2_HOOK_INSTALLED]\nPACKAGE=$packageName")
                Log.i(MODULE_TAG, "[CAMERAX_HOOK_INSTALLED]\nPACKAGE=$packageName")
                Log.i(MODULE_TAG, "[LEGACY_CAMERA_HOOK_INSTALLED]\nPACKAGE=$packageName")
                ZestoRemoteFrameSource.reportMilestone("CAMERA2_HOOK_INSTALLED", "Camera2, Legacy Camera & CameraX hooks installed for $packageName")
            } catch (e: Throwable) {
                currentLifecycle = XposedHookLifecycle.HOOK_FAILED
                Log.e(MODULE_TAG, "[HOOK_FAILED] Failed to register camera hooks: ${e.message}", e)
            }
        }
    }

    private fun shouldHookPackage(packageName: String): Boolean {
        // Skip system packages and Zesto itself
        if (packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.example.zesto" ||
            packageName == "com.aistudio.zesto.vcam") {
            return false
        }
        return true
    }

    private fun hookCamera2Pipeline(classLoader: ClassLoader, packageName: String) {
        hookApplicationContext(classLoader, packageName)
        Camera2Hook.attachHook(classLoader, packageName)
    }

    private fun hookApplicationContext(classLoader: ClassLoader, packageName: String) {
        try {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "attachBaseContext",
                android.content.Context::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? android.app.Application
                        if (app != null) {
                            ZestoRemoteFrameSource.setTargetContext(app)
                            Log.i(MODULE_TAG, "[TARGET_CONTEXT_BOUND] package=$packageName context=${app.javaClass.name}")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "onCreate",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? android.app.Application
                        if (app != null) {
                            ZestoRemoteFrameSource.setTargetContext(app)
                            Log.i(MODULE_TAG, "[TARGET_CONTEXT_BOUND] package=$packageName context=${app.javaClass.name} (onCreate)")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookLegacyCameraPipeline(classLoader: ClassLoader, packageName: String) {
        LegacyCameraHook.attachHook(classLoader, packageName)
    }

    private fun hookCameraXPipeline(classLoader: ClassLoader, packageName: String) {
        CameraXHook.attachHook(classLoader, packageName)
    }
}
