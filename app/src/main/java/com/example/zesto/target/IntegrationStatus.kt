package com.example.zesto.target

/**
 * Module and Stage 2 Hook/IPC integration states.
 * Reflects actual runtime evidence rather than static presence.
 */
enum class IntegrationStatus(val displayName: String, val description: String) {
    MODULE_NOT_INSTALLED("Module Not Installed", "Zesto Xposed/LSPosed hook module is not installed or enabled in the framework scope"),
    MODULE_INSTALLED("Module Installed", "Module APK is recognized by LSPosed/LSPatch framework"),
    HOOK_NOT_ACTIVE("Hook Inactive", "Hook is installed but target process has not triggered camera entry hooks"),
    HOOK_ACTIVE("Hook Active", "Camera interception hooks successfully engaged in target process"),
    FRAME_BRIDGE_READY("Frame Bridge Ready", "ZestoFrameBridge / ContentProvider IPC channel is initialized and waiting"),
    FRAME_BRIDGE_RECEIVING("Frame Bridge Receiving", "Target process is actively pulling decoded video frames from Zesto"),
    CAMERA_BACKEND_READY("Camera Backend Ready", "Target Camera2/CameraX/Legacy backend adapter is initialized"),
    CAMERA_BACKEND_ACTIVE("Camera Backend Active", "Target camera output surface is receiving injected OBS video feed"),
    FAILED("Integration Failed", "Hook initialization or IPC surface binding encountered a fatal error")
}
