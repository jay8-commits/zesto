package com.example.zesto.target

import com.example.zesto.camera.Camera2Backend
import com.example.zesto.camera.CameraApiType
import com.example.zesto.camera.CameraVirtualizationBackend
import com.example.zesto.camera.CameraVirtualizationStatus
import com.example.zesto.camera.CameraXIntegration
import com.example.zesto.camera.LegacyCameraBackend

/**
 * Manages target compatibility profiles, verifies system sandbox limitations,
 * and determines the appropriate virtualization backend for target applications.
 */
class CompatibilityManager {

    private val profiles = mutableListOf<TargetProfile>()

    init {
        // 1. Zesto Controlled Test Target (Self-contained test harness)
        profiles.add(
            TargetProfile(
                id = "zesto_controlled_test_app",
                appName = "Zesto Controlled Camera Test",
                packageName = "com.example.zesto.testtarget",
                cameraApi = CameraApiType.CAMERA2,
                expectedPreviewPath = "TextureView / SurfaceDirect IPC Bridge",
                supportedBackend = "Camera2Backend",
                integrationMechanism = "Direct In-App Harness (Self-contained)",
                requiredPermissions = listOf("android.permission.CAMERA", "android.permission.INTERNET"),
                requiresInstrumentation = false,
                requiresRoot = false,
                requiresPrivilegedAccess = false,
                knownLimitations = "None (Fully instrumented reference test harness)",
                diagnosticInfo = "Reference camera pipeline for validating OBS RTSP -> Decoder -> Surface injection without third-party sandboxing.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )

        // 2. Discord (WebRTC / Camera2)
        profiles.add(
            TargetProfile(
                id = "discord",
                appName = "Discord",
                packageName = "com.discord",
                cameraApi = CameraApiType.CAMERA2,
                expectedPreviewPath = "org.webrtc.Camera2Enumerator -> SurfaceTextureHelper",
                supportedBackend = "Camera2Backend",
                integrationMechanism = "LSPatch APK Patching (Portable Mode) / LSPosed",
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiresInstrumentation = true,
                requiresRoot = false,
                requiresPrivilegedAccess = false,
                knownLimitations = "WebRTC SurfaceTextureHelper requires surface interception via LSPatch or LSPosed.",
                diagnosticInfo = "Target hooks org.webrtc.Camera2Session and android.hardware.camera2.CameraDevice. Physical target-app verification required.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )

        // 3. WhatsApp (Camera2 Custom Pipeline)
        profiles.add(
            TargetProfile(
                id = "whatsapp",
                appName = "WhatsApp",
                packageName = "com.whatsapp",
                cameraApi = CameraApiType.CAMERA2,
                expectedPreviewPath = "CameraDevice.createCaptureSession -> GlSurfaceView",
                supportedBackend = "Camera2Backend",
                integrationMechanism = "LSPatch APK Patching / LSPosed Module",
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiresInstrumentation = true,
                requiresRoot = false,
                requiresPrivilegedAccess = false,
                knownLimitations = "Custom EGL renderer; requires session Surface hooking.",
                diagnosticInfo = "Hooks CameraDevice.createCaptureSession output targets. Physical target-app verification required.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )

        // 4. Zoom Workplace (NDK / Camera2)
        profiles.add(
            TargetProfile(
                id = "zoom",
                appName = "Zoom Workplace",
                packageName = "us.zoom.videomeetings",
                cameraApi = CameraApiType.CAMERA2,
                expectedPreviewPath = "Custom C++ VideoCapturer -> SurfaceView",
                supportedBackend = "Camera2Backend",
                integrationMechanism = "LSPatch APK Patching / LSPosed Module",
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiresInstrumentation = true,
                requiresRoot = false,
                requiresPrivilegedAccess = false,
                knownLimitations = "Native capture hooks CameraDevice at Java layer before NDK ingestion.",
                diagnosticInfo = "Hooks Java Camera2 layer prior to JNI video buffer ingestion. Physical target-app verification required.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )

        // 5. Google Meet (WebRTC / Camera2)
        profiles.add(
            TargetProfile(
                id = "google_meet",
                appName = "Google Meet",
                packageName = "com.google.android.apps.meetings",
                cameraApi = CameraApiType.CAMERA2,
                expectedPreviewPath = "WebRTC Camera2Session -> SurfaceTexture",
                supportedBackend = "Camera2Backend",
                integrationMechanism = "LSPatch APK Patching / LSPosed Module",
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiresInstrumentation = true,
                requiresRoot = false,
                requiresPrivilegedAccess = false,
                knownLimitations = "Package signature check requires signature spoofing in LSPatch.",
                diagnosticInfo = "Hooks WebRTC Camera2Session. Requires signature verification bypass in non-root mode. Physical verification required.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )

        // 6. Telegram (Camera1 & Camera2 dual mode)
        profiles.add(
            TargetProfile(
                id = "telegram",
                appName = "Telegram",
                packageName = "org.telegram.messenger",
                cameraApi = CameraApiType.CAMERA2,
                expectedPreviewPath = "org.telegram.messenger.camera.CameraController",
                supportedBackend = "Camera2Backend",
                integrationMechanism = "LSPatch APK Patching / LSPosed Module",
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiresInstrumentation = true,
                requiresRoot = false,
                requiresPrivilegedAccess = false,
                knownLimitations = "Open-source client can also be directly compiled with Zesto IPC FrameProvider.",
                diagnosticInfo = "Dual Camera1/Camera2 capture pipeline. Physical target-app verification required.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )

        // 7. Instagram (CameraX / Camera2 Proprietary)
        profiles.add(
            TargetProfile(
                id = "instagram",
                appName = "Instagram",
                packageName = "com.instagram.android",
                cameraApi = CameraApiType.CAMERAX,
                expectedPreviewPath = "IGCameraService -> TextureView / HardwareBuffer",
                supportedBackend = "CameraXIntegration",
                integrationMechanism = "LSPosed System Hook (Root Required)",
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiresInstrumentation = true,
                requiresRoot = true,
                requiresPrivilegedAccess = true,
                knownLimitations = "Heavy anti-tampering, Play Integrity & native attestation require root LSPosed hook.",
                diagnosticInfo = "Proprietary camera stack with hardware buffer verification. Non-root LSPatch blocked by integrity checks.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )

        // 8. Snapchat (Hardware Buffer Protected)
        profiles.add(
            TargetProfile(
                id = "snapchat",
                appName = "Snapchat",
                packageName = "com.snapchat.android",
                cameraApi = CameraApiType.CAMERA2,
                expectedPreviewPath = "SCNDKRenderer -> HardwareBuffer Surface",
                supportedBackend = "Camera2Backend",
                integrationMechanism = "LSPosed System Hook (Root Required)",
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiresInstrumentation = true,
                requiresRoot = true,
                requiresPrivilegedAccess = true,
                knownLimitations = "Play Integrity & native attestation block non-root APK patching.",
                diagnosticInfo = "NDK HardwareBuffer rendering pipeline. Requires system-level LSPosed hook. Physical verification required.",
                testStatus = CameraVirtualizationStatus.NOT_TESTED
            )
        )
    }

    fun getAllProfiles(): List<TargetProfile> = profiles.toList()

    fun getProfile(packageName: String): TargetProfile? {
        return profiles.find { it.packageName.equals(packageName, ignoreCase = true) }
    }

    fun filterProfiles(query: String): List<TargetProfile> {
        if (query.isBlank()) return getAllProfiles()
        return profiles.filter {
            it.appName.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true) ||
            it.supportedBackend.contains(query, ignoreCase = true) ||
            it.testStatus.name.contains(query, ignoreCase = true)
        }
    }

    fun updateProfileStatus(packageName: String, status: CameraVirtualizationStatus, diagnosticNote: String? = null) {
        val index = profiles.indexOfFirst { it.packageName.equals(packageName, ignoreCase = true) }
        if (index >= 0) {
            val current = profiles[index]
            profiles[index] = current.copy(
                testStatus = status,
                diagnosticInfo = diagnosticNote ?: current.diagnosticInfo
            )
        }
    }

    fun createBackendForProfile(profile: TargetProfile): CameraVirtualizationBackend {
        return BackendResolver.resolveBackend(profile.cameraApi)
    }
}


