package com.example.zesto.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Detects the camera API environment and device camera capabilities.
 * Determines whether Camera2, Legacy Camera, or CameraX pipelines are present.
 */
class CameraApiDetector(private val context: Context) {

    fun detectDeviceCapabilities(): CameraCapabilities {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                return CameraCapabilities(apiType = CameraApiType.UNKNOWN)
            }

            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                return CameraCapabilities(apiType = CameraApiType.CAMERA2, cameraCount = 0)
            }

            val firstCameraId = cameraIds[0]
            val characteristics = cameraManager.getCameraCharacteristics(firstCameraId)
            val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

            val hwLevelStr = when (hwLevel) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "EXTERNAL"
            }

            val apiType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CameraApiType.CAMERA2
            } else {
                CameraApiType.CAMERA1_LEGACY
            }

            CameraCapabilities(
                apiType = apiType,
                hardwareLevel = hwLevelStr,
                supportedResolutions = listOf("1920x1080", "1280x720", "640x480"),
                supportedFpsRanges = listOf("[15, 30]", "[30, 30]"),
                supportsSurfaceTexture = true,
                supportsImageReader = true,
                requiresInstrumentation = true, // Virtualizing camera in 3rd party apps without root requires instrumentation
                cameraCount = cameraIds.size
            )
        } catch (e: Exception) {
            CameraCapabilities(
                apiType = CameraApiType.UNKNOWN,
                hardwareLevel = "UNKNOWN"
            )
        }
    }

    /**
     * Inspects a target package name to identify likely camera API architecture.
     */
    fun analyzeTargetPackage(packageName: String): CameraApiType {
        return when {
            packageName.isEmpty() -> CameraApiType.UNKNOWN
            packageName.contains("test", ignoreCase = true) -> CameraApiType.CAMERA2
            packageName.contains("camerax", ignoreCase = true) -> CameraApiType.CAMERAX
            packageName.contains("legacy", ignoreCase = true) -> CameraApiType.CAMERA1_LEGACY
            else -> CameraApiType.CAMERA2
        }
    }
}
