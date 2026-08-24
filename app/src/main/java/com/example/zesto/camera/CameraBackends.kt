package com.example.zesto.camera

import com.example.zesto.frame.FrameProvider
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import com.example.zesto.frame.ZestoFrameBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Camera2 Virtualization Backend.
 * Targets modern Android Camera2 (android.hardware.camera2) capture sessions.
 * In non-root environments, camera virtualization on third-party targets requires explicit bytecode instrumentation
 * or controlled test app integration.
 */
class Camera2Backend : CameraVirtualizationBackend {

    override val backendName: String = "Camera2Backend"
    override val supportedApi: CameraApiType = CameraApiType.CAMERA2
    override val consumerId: String = "backend_camera2"
    override val preferredFormat: PixelFormat = PixelFormat.SURFACE_TEXTURE

    private val _status = MutableStateFlow<CameraVirtualizationStatus>(CameraVirtualizationStatus.NOT_TESTED)
    override val status: StateFlow<CameraVirtualizationStatus> = _status.asStateFlow()

    override val technicalNotes: String = """
        Camera2 Pipeline:
        - Target uses CameraDevice, CameraCaptureSession, and Surface targets (ImageReader/SurfaceView).
        - Non-Root Isolation: Standard Android application sandboxing isolates CameraService.
        - Integration Path: Controlled test application surface redirection or LSPosed/LSPatch hooking.
    """.trimIndent()

    private var frameProvider: FrameProvider? = null

    override fun initialize(): Result<Unit> {
        _status.value = CameraVirtualizationStatus.TESTING
        return Result.success(Unit)
    }

    override fun startVirtualFeed(): Result<Unit> {
        _status.value = CameraVirtualizationStatus.ACTIVE
        return Result.success(Unit)
    }

    override fun stopVirtualFeed() {
        if (_status.value == CameraVirtualizationStatus.ACTIVE) {
            _status.value = CameraVirtualizationStatus.NOT_TESTED
        }
    }

    override fun release() {
        stopVirtualFeed()
        frameProvider = null
    }

    override fun onConsumerAttached(provider: FrameProvider) {
        this.frameProvider = provider
    }

    override fun onFrameAvailable(frame: VideoFrame) {
        // Publish incoming decoded frame to the cross-process ZestoFrameBridge
        ZestoFrameBridge.postFrame(
            width = frame.width,
            height = frame.height,
            format = frame.pixelFormat,
            timestampUs = frame.timestampUs
        )
    }

    override fun onConsumerDetached() {
        this.frameProvider = null
    }
}

/**
 * Legacy Camera Backend for deprecated android.hardware.Camera.
 */
class LegacyCameraBackend : CameraVirtualizationBackend {
    override val backendName: String = "LegacyCameraBackend"
    override val supportedApi: CameraApiType = CameraApiType.CAMERA1_LEGACY
    override val consumerId: String = "backend_legacy_camera"
    override val preferredFormat: PixelFormat = PixelFormat.NV21

    private val _status = MutableStateFlow(CameraVirtualizationStatus.REQUIRES_INSTRUMENTATION)
    override val status: StateFlow<CameraVirtualizationStatus> = _status.asStateFlow()

    override val technicalNotes: String = """
        Legacy Camera API:
        - Deprecated in Android 5.0 (API 21).
        - Relies on Camera.open(), setPreviewDisplay(), or setPreviewTexture().
        - Non-Root: Interception requires target app bytecode instrumentation or LSPatch hook.
    """.trimIndent()

    override fun initialize(): Result<Unit> = Result.success(Unit)
    override fun startVirtualFeed(): Result<Unit> {
        _status.value = CameraVirtualizationStatus.ACTIVE
        return Result.success(Unit)
    }
    override fun stopVirtualFeed() {
        _status.value = CameraVirtualizationStatus.REQUIRES_INSTRUMENTATION
    }
    override fun release() {
        stopVirtualFeed()
    }
    override fun onConsumerAttached(provider: FrameProvider) {}
    override fun onFrameAvailable(frame: VideoFrame) {
        ZestoFrameBridge.postFrame(
            width = frame.width,
            height = frame.height,
            format = PixelFormat.NV21,
            timestampUs = frame.timestampUs
        )
    }
    override fun onConsumerDetached() {}
}

/**
 * CameraX Integration Backend for Jetpack CameraX targets.
 */
class CameraXIntegration : CameraVirtualizationBackend {
    override val backendName: String = "CameraXIntegration"
    override val supportedApi: CameraApiType = CameraApiType.CAMERAX
    override val consumerId: String = "backend_camerax"
    override val preferredFormat: PixelFormat = PixelFormat.SURFACE_TEXTURE

    private val _status = MutableStateFlow(CameraVirtualizationStatus.REQUIRES_INSTRUMENTATION)
    override val status: StateFlow<CameraVirtualizationStatus> = _status.asStateFlow()

    override val technicalNotes: String = """
        CameraX Pipeline:
        - High-level Jetpack library built atop Camera2.
        - Provides Preview, ImageCapture, and ImageAnalysis use cases.
        - Integration Path: Custom CameraX CameraProvider / SurfaceProvider override or LSPosed module.
    """.trimIndent()

    override fun initialize(): Result<Unit> = Result.success(Unit)
    override fun startVirtualFeed(): Result<Unit> {
        _status.value = CameraVirtualizationStatus.ACTIVE
        return Result.success(Unit)
    }
    override fun stopVirtualFeed() {
        _status.value = CameraVirtualizationStatus.REQUIRES_INSTRUMENTATION
    }
    override fun release() {
        stopVirtualFeed()
    }
    override fun onConsumerAttached(provider: FrameProvider) {}
    override fun onFrameAvailable(frame: VideoFrame) {
        ZestoFrameBridge.postFrame(
            width = frame.width,
            height = frame.height,
            format = PixelFormat.SURFACE_TEXTURE,
            timestampUs = frame.timestampUs
        )
    }
    override fun onConsumerDetached() {}
}

