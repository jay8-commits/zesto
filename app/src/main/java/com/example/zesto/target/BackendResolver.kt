package com.example.zesto.target

import com.example.zesto.camera.Camera2Backend
import com.example.zesto.camera.CameraApiType
import com.example.zesto.camera.CameraVirtualizationBackend
import com.example.zesto.camera.CameraVirtualizationStatus
import com.example.zesto.camera.CameraXIntegration
import com.example.zesto.camera.LegacyCameraBackend

/**
 * Deployment path for target application integration.
 */
enum class DeploymentPath(val title: String, val description: String) {
    SELF_CONTAINED_HARNESS(
        "Self-Contained In-App Harness",
        "Direct internal Camera2 pipeline binding. Requires no external patching or root access."
    ),
    NON_ROOT_LSPATCH(
        "Non-Root LSPatch / NPatch APK Patching",
        "Target APK is repacked with embedded Zesto module DEX. Intercepts Camera2/CameraX surfaces via sandbox-internal hooks."
    ),
    ROOTED_LSPOSED(
        "Rooted LSPosed System Hook",
        "Module is loaded system-wide via Zygote injection. Required for apps protected by Play Integrity, attestation, or DRM."
    )
}

/**
 * Resolves optimal camera virtualization backend, deployment paths, and integration states
 * based on target package, API characteristics, and device execution environment.
 */
object BackendResolver {

    fun resolveBackend(apiType: CameraApiType): CameraVirtualizationBackend {
        return when (apiType) {
            CameraApiType.CAMERA2 -> Camera2Backend()
            CameraApiType.CAMERAX -> CameraXIntegration()
            CameraApiType.CAMERA1_LEGACY -> LegacyCameraBackend()
            CameraApiType.NATIVE_NDK -> Camera2Backend()
            CameraApiType.UNKNOWN -> Camera2Backend()
        }
    }

    fun resolveDeploymentPath(profile: TargetProfile): DeploymentPath {
        return when {
            profile.packageName == "com.example.zesto.testtarget" -> DeploymentPath.SELF_CONTAINED_HARNESS
            profile.requiresRoot -> DeploymentPath.ROOTED_LSPOSED
            else -> DeploymentPath.NON_ROOT_LSPATCH
        }
    }

    fun resolveIntegrationStatus(
        profile: TargetProfile,
        isRooted: Boolean = false,
        isModuleInstalled: Boolean = false,
        isHookActive: Boolean = false,
        isFrameBridgeReceiving: Boolean = false
    ): IntegrationStatus {
        return when {
            profile.packageName == "com.example.zesto.testtarget" -> {
                if (isFrameBridgeReceiving) IntegrationStatus.CAMERA_BACKEND_ACTIVE
                else IntegrationStatus.FRAME_BRIDGE_READY
            }
            !isModuleInstalled -> IntegrationStatus.MODULE_NOT_INSTALLED
            !isHookActive -> IntegrationStatus.HOOK_NOT_ACTIVE
            !isFrameBridgeReceiving -> IntegrationStatus.FRAME_BRIDGE_READY
            else -> IntegrationStatus.CAMERA_BACKEND_ACTIVE
        }
    }
}

