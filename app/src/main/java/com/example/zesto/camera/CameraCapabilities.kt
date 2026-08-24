package com.example.zesto.camera

/**
 * Camera API types recognized by Zesto detector.
 */
enum class CameraApiType(val displayName: String, val level: String) {
    CAMERA1_LEGACY("Legacy Camera API (android.hardware.Camera)", "Deprecated"),
    CAMERA2("Camera2 API (android.hardware.camera2)", "Modern"),
    CAMERAX("Jetpack CameraX (androidx.camera)", "High-level Jetpack"),
    NATIVE_NDK("Native NDK Camera (ACameraManager)", "C/C++ NDK"),
    UNKNOWN("Unknown / Undetected", "N/A")
}

/**
 * Physical/Virtual camera capabilities and hardware levels.
 */
data class CameraCapabilities(
    val apiType: CameraApiType = CameraApiType.UNKNOWN,
    val hardwareLevel: String = "LIMITED",
    val supportedResolutions: List<String> = listOf("1280x720", "1920x1080", "640x480"),
    val supportedFpsRanges: List<String> = listOf("[15, 30]", "[30, 30]"),
    val supportsSurfaceTexture: Boolean = true,
    val supportsImageReader: Boolean = true,
    val requiresInstrumentation: Boolean = false,
    val cameraCount: Int = 0
)

/**
 * Camera Virtualization Backend & Target status report.
 * Strictly adheres to truth-in-engineering (never claims supported without actual validation).
 */
enum class CameraVirtualizationStatus(val displayName: String, val description: String) {
    NOT_TESTED("Not Tested", "Implementation completed but not yet physically verified on target device"),
    DETECTED("Target Detected", "Target camera pipeline identified by analyzer"),
    TESTING("Testing in Progress", "Currently executing active validation loop"),
    SUPPORTED("Supported", "Target architecture matches verified injection pipeline"),
    ACTIVE("Active / Injected", "Virtual camera feed actively delivering frames into target surface"),
    FAILED("Failed", "Integration attempt encountered runtime error or surface rejection"),
    UNSUPPORTED("Unsupported", "Unsupported by target security architecture or hardware DRM"),
    REQUIRES_ROOT("Requires Root (LSPosed)", "System-level hooking required due to app attestation or sandboxing"),
    REQUIRES_INSTRUMENTATION("Requires Instrumentation", "Target requires bytecode or framework instrumentation"),
    REQUIRES_PATCHING("Requires Patching (LSPatch)", "Non-root target requires APK patching with embedded module")
}


