package com.example.zesto.target

import com.example.zesto.camera.CameraApiType
import com.example.zesto.camera.CameraVirtualizationStatus

/**
 * Compatibility profile describing a target application and its camera pipeline requirements.
 * Adheres strictly to Stage 3 specifications: Every target profile defaults to NOT_TESTED
 * until physical on-device runtime verification occurs.
 */
data class TargetProfile(
    val id: String,
    val appName: String,
    val packageName: String,
    val minAndroidVersion: Int = 24,
    val targetAndroidVersion: Int = 34,
    val cameraApi: CameraApiType,
    val expectedPreviewPath: String,
    val supportedBackend: String,
    val integrationMechanism: String = "LSPatch APK Instrumentation / LSPosed",
    val requiredPermissions: List<String> = listOf("android.permission.CAMERA"),
    val requiresInstrumentation: Boolean = true,
    val requiresRoot: Boolean = false,
    val requiresPrivilegedAccess: Boolean = false,
    val knownLimitations: String = "",
    val diagnosticInfo: String = "Profile registered. Awaiting physical runtime verification.",
    val testStatus: CameraVirtualizationStatus = CameraVirtualizationStatus.NOT_TESTED
)


