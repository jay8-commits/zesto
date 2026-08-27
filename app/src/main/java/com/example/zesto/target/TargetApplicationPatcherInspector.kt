package com.example.zesto.target

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Result of manifest and classloader inspection for a target package.
 */
data class ApplicationIntegrityResult(
    val packageName: String,
    val declaredApplicationClassName: String?,
    val originalLegitimateApplicationClassName: String?,
    val appComponentFactory: String?,
    val isClassResolvableInClassLoader: Boolean,
    val diagnosticMessage: String,
    val isLaunchSafe: Boolean
)

/**
 * Diagnostic inspector and verifier for target APK Application classes.
 *
 * Prevents and isolates crashes caused by patched APKs declaring nonexistent Application classes:
 * e.g., ClassNotFoundException: Didn't find class "com.ijoysoft.camera.app.App"
 * when target package is "photo.camera.beauty.hd.camera" or "net.sourceforge.opencamera".
 */
object TargetApplicationPatcherInspector {
    private const val TAG = "TargetAppPatcher"

    /**
     * Inspects the target application's manifest application name and verifies whether
     * the declared class can be instantiated by the target's ClassLoader.
     */
    fun inspectTargetApplicationClass(
        context: Context,
        packageName: String,
        targetClassLoader: ClassLoader = context.classLoader
    ): ApplicationIntegrityResult {
        var declaredName: String? = null
        var appComponentFactory: String? = null

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            declaredName = appInfo.className
            appComponentFactory = appInfo.appComponentFactory
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch PackageManager ApplicationInfo for $packageName: ${e.message}")
        }

        val originalLegitimate = resolveKnownLegitimateApplicationClass(packageName)
        val testClassName = declaredName ?: originalLegitimate

        var resolvable = false
        if (testClassName != null) {
            try {
                Class.forName(testClassName, false, targetClassLoader)
                resolvable = true
            } catch (_: ClassNotFoundException) {
                resolvable = false
            } catch (_: Throwable) {
                resolvable = false
            }
        } else {
            // Default android.app.Application is always resolvable
            resolvable = true
        }

        val logPrefix = "[PATCH_MANIFEST_APPLICATION]"
        val origStr = originalLegitimate ?: "android.app.Application"
        val patchStr = declaredName ?: "android.app.Application"
        val logMsg = "$logPrefix original=$origStr patched=$patchStr (resolvable=$resolvable, pkg=$packageName)"

        Log.i(TAG, logMsg)

        val isSafe = resolvable && (declaredName == null || declaredName == originalLegitimate || resolvable)

        return ApplicationIntegrityResult(
            packageName = packageName,
            declaredApplicationClassName = declaredName,
            originalLegitimateApplicationClassName = originalLegitimate,
            appComponentFactory = appComponentFactory,
            isClassResolvableInClassLoader = resolvable,
            diagnosticMessage = logMsg,
            isLaunchSafe = isSafe
        )
    }

    /**
     * Returns known legitimate application classes for standard target packages.
     */
    fun resolveKnownLegitimateApplicationClass(packageName: String): String? {
        return when (packageName) {
            "net.sourceforge.opencamera" -> "net.sourceforge.opencamera.MyApplication"
            "photo.camera.beauty.hd.camera" -> null // Default android.app.Application or vendor multi-dex stub if provided
            "com.discord" -> "com.discord.app.App"
            "com.whatsapp" -> "com.whatsapp.App"
            "org.telegram.messenger" -> "org.telegram.messenger.ApplicationLoader"
            "com.example.zesto.testtarget" -> null
            else -> null
        }
    }
}
