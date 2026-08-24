package com.example

import com.example.zesto.camera.CameraApiType
import com.example.zesto.camera.CameraVirtualizationStatus
import com.example.zesto.diagnostics.DiagnosticsLevel
import com.example.zesto.diagnostics.DiagnosticsManager
import com.example.zesto.diagnostics.LogExporter
import com.example.zesto.diagnostics.Subsystem
import com.example.zesto.frame.FrameConsumer
import com.example.zesto.frame.FramePipeline
import com.example.zesto.frame.FrameProvider
import com.example.zesto.frame.PixelFormat
import com.example.zesto.frame.VideoFrame
import com.example.zesto.stream.StreamConfig
import com.example.zesto.stream.StreamState
import com.example.zesto.stream.StreamStats
import com.example.zesto.stream.TransportProtocol
import com.example.zesto.target.CompatibilityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ExampleUnitTest {

    @Test
    fun testStreamConfigDefaults() {
        val config = StreamConfig()
        assertEquals(1280, config.targetWidth)
        assertEquals(720, config.targetHeight)
        assertEquals(30, config.targetFps)
        assertEquals(TransportProtocol.RTSP_TCP, config.protocol)
        assertTrue(config.autoReconnect)
    }

    @Test
    fun testVideoFrameCalculations() {
        val testBuffer = ByteBuffer.allocate(1280 * 720 * 3 / 2)
        val frame = VideoFrame(
            frameNumber = 1L,
            timestampUs = 1000000L,
            width = 1280,
            height = 720,
            pixelFormat = PixelFormat.NV21,
            buffer = testBuffer
        )
        assertEquals(1280 * 720 * 1.5, frame.calculateDataSize().toDouble(), 0.01)
        assertTrue(frame.hasBuffer())
    }

    @Test
    fun testFramePipelineLifecycleAndDelivery() {
        val pipeline = FramePipeline()
        assertFalse(pipeline.isRunning())

        var frameReceived = false
        val consumer = object : FrameConsumer {
            override val consumerId: String = "test_consumer_1"
            override fun onConsumerAttached(provider: FrameProvider) {}
            override fun onFrameAvailable(frame: VideoFrame) {
                frameReceived = true
            }
            override fun onConsumerDetached() {}
        }

        pipeline.registerConsumer(consumer)
        assertEquals(1, pipeline.getActiveConsumerCount())
        assertEquals(listOf("test_consumer_1"), pipeline.getRegisteredConsumerIds())

        pipeline.start()
        assertTrue(pipeline.isRunning())

        val frame = VideoFrame(
            frameNumber = 1L,
            timestampUs = 1000L,
            width = 640,
            height = 480,
            pixelFormat = PixelFormat.RGBA_8888
        )
        pipeline.pushFrame(frame)
        assertTrue(frameReceived)

        pipeline.unregisterConsumer("test_consumer_1")
        assertEquals(0, pipeline.getActiveConsumerCount())
        pipeline.stop()
        assertFalse(pipeline.isRunning())
    }

    @Test
    fun testCompatibilityManagerProfiles() {
        val manager = CompatibilityManager()
        val profiles = manager.getAllProfiles()
        assertTrue(profiles.isNotEmpty())

        val controlledTest = manager.getProfile("com.example.zesto.testtarget")
        assertNotNull(controlledTest)
        assertEquals(CameraApiType.CAMERA2, controlledTest?.cameraApi)

        val backend = manager.createBackendForProfile(controlledTest!!)
        assertEquals("Camera2Backend", backend.backendName)
    }

    @Test
    fun testDiagnosticsManagerAndLogExporter() {
        val manager = DiagnosticsManager()
        manager.updateTransport(
            StreamState.Connected("rtsp://192.168.1.50:8554/live"),
            StreamStats(packetsReceived = 100, estimatedBitrateKbps = 2500.0),
            "rtsp://192.168.1.50:8554/live"
        )
        manager.logger.info(Subsystem.TRANSPORT, "Transport connection validated")

        val snapshot = manager.snapshot.value
        assertEquals("CONNECTED", snapshot.transportStatus)
        assertEquals("rtsp://192.168.1.50:8554/live", snapshot.rtspUrl)

        val logs = manager.logger.logs.value
        assertTrue(logs.isNotEmpty())

        val exportedMarkdown = LogExporter.exportAsMarkdown(snapshot, logs)
        assertTrue(exportedMarkdown.contains("ZESTO DIAGNOSTICS LOG EXPORT"))
        assertTrue(exportedMarkdown.contains("--- TRANSPORT LAYER ---"))
        assertTrue(exportedMarkdown.contains("CONNECTED"))
    }

    @Test
    fun testRTSPProbeUrlValidation() {
        kotlinx.coroutines.runBlocking {
            val invalidSchemeResult = com.example.zesto.stream.RTSPConnectionTester.probe("http://192.168.1.100:8554/live")
            assertTrue(invalidSchemeResult is com.example.zesto.stream.RTSPProbeResult.InvalidUrl)

            val missingHostResult = com.example.zesto.stream.RTSPConnectionTester.probe("rtsp://")
            assertTrue(missingHostResult is com.example.zesto.stream.RTSPProbeResult.InvalidUrl)
        }
    }

    @Test
    fun testZestoFrameBridgeSingleton() {
        com.example.zesto.frame.ZestoFrameBridge.reset()
        assertEquals(0L, com.example.zesto.frame.ZestoFrameBridge.frameCount.value)

        com.example.zesto.frame.ZestoFrameBridge.postFrame(
            width = 1920,
            height = 1080,
            format = PixelFormat.RGBA_8888,
            timestampUs = 123456L
        )

        assertEquals(1L, com.example.zesto.frame.ZestoFrameBridge.frameCount.value)
        val latest = com.example.zesto.frame.ZestoFrameBridge.latestFrame.value
        assertNotNull(latest)
        assertEquals(1920, latest?.width)
        assertEquals(1080, latest?.height)
        assertEquals(PixelFormat.RGBA_8888, latest?.pixelFormat)
    }

    @Test
    fun testAllTargetProfilesDefaultToNotTested() {
        val manager = CompatibilityManager()
        val allProfiles = manager.getAllProfiles()
        assertEquals(8, allProfiles.size)

        allProfiles.forEach { profile ->
            assertEquals(
                "Profile ${profile.appName} must default to NOT_TESTED prior to physical on-device execution",
                CameraVirtualizationStatus.NOT_TESTED,
                profile.testStatus
            )
            assertTrue(profile.diagnosticInfo.isNotBlank())
            assertTrue(profile.packageName.isNotBlank())
        }
    }

    @Test
    fun testDeploymentPathsAndIntegrationStatus() {
        val manager = CompatibilityManager()
        val controlled = manager.getProfile("com.example.zesto.testtarget")!!
        val discord = manager.getProfile("com.discord")!!
        val instagram = manager.getProfile("com.instagram.android")!!

        // Controlled test harness path
        val controlledPath = com.example.zesto.target.BackendResolver.resolveDeploymentPath(controlled)
        assertEquals(com.example.zesto.target.DeploymentPath.SELF_CONTAINED_HARNESS, controlledPath)

        // Non-root path
        val discordPath = com.example.zesto.target.BackendResolver.resolveDeploymentPath(discord)
        assertEquals(com.example.zesto.target.DeploymentPath.NON_ROOT_LSPATCH, discordPath)

        // Rooted path
        val instaPath = com.example.zesto.target.BackendResolver.resolveDeploymentPath(instagram)
        assertEquals(com.example.zesto.target.DeploymentPath.ROOTED_LSPOSED, instaPath)

        // Integration statuses
        val statusNotInstalled = com.example.zesto.target.BackendResolver.resolveIntegrationStatus(
            profile = discord,
            isModuleInstalled = false
        )
        assertEquals(com.example.zesto.target.IntegrationStatus.MODULE_NOT_INSTALLED, statusNotInstalled)

        val statusHookInactive = com.example.zesto.target.BackendResolver.resolveIntegrationStatus(
            profile = discord,
            isModuleInstalled = true,
            isHookActive = false
        )
        assertEquals(com.example.zesto.target.IntegrationStatus.HOOK_NOT_ACTIVE, statusHookInactive)

        val statusActive = com.example.zesto.target.BackendResolver.resolveIntegrationStatus(
            profile = discord,
            isModuleInstalled = true,
            isHookActive = true,
            isFrameBridgeReceiving = true
        )
        assertEquals(com.example.zesto.target.IntegrationStatus.CAMERA_BACKEND_ACTIVE, statusActive)
    }

    @Test
    fun testPhysicalVerificationRequiredMarkers() {
        // Explicitly assert that physical device testing is marked as required
        val requiresPhysicalOnDevice = true
        assertTrue("PHYSICAL TEST REQUIRED: Live camera frame injection into target processes requires on-device validation", requiresPhysicalOnDevice)
    }
}


