package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zesto.camera.CameraApiDetector
import com.example.zesto.data.ZestoPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppNameResource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Zesto", appName)
    }

    @Test
    fun testPreferencesPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ZestoPreferences(context)
        val config = prefs.loadStreamConfig()
        assertNotNull(config.url)
    }

    @Test
    fun testCameraApiDetectorInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = CameraApiDetector(context)
        val caps = detector.detectDeviceCapabilities()
        assertNotNull(caps.apiType)
    }

    @Test
    fun testUnifiedLifecycleSingleConnectDisconnectFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = com.example.zesto.stream.ZestoStreamEngineManager
        manager.initialize(context)

        assertEquals(com.example.zesto.stream.ZestoEngineLifecycleState.DISCONNECTED, manager.lifecycleState.value)

        val config = com.example.zesto.stream.StreamConfig(url = "rtsp://127.0.0.1:8554/live")
        manager.connect(context, config)

        val activeState = manager.lifecycleState.value
        org.junit.Assert.assertTrue(
            activeState == com.example.zesto.stream.ZestoEngineLifecycleState.CONNECTING ||
            activeState == com.example.zesto.stream.ZestoEngineLifecycleState.CONNECTED ||
            activeState == com.example.zesto.stream.ZestoEngineLifecycleState.RUNNING
        )

        // Test duplicate connect is safely handled
        manager.connect(context, config)
        assertEquals(activeState, manager.lifecycleState.value)

        // Test disconnect
        manager.disconnect(context)
        assertEquals(com.example.zesto.stream.ZestoEngineLifecycleState.DISCONNECTED, manager.lifecycleState.value)

        // Test duplicate disconnect is safely handled
        manager.disconnect(context)
        assertEquals(com.example.zesto.stream.ZestoEngineLifecycleState.DISCONNECTED, manager.lifecycleState.value)
    }

    @Test
    fun testMasterConnectValidationRollback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = com.example.zesto.stream.ZestoStreamEngineManager
        manager.initialize(context)

        // Blank URL should fail validation and transition to ERROR
        val invalidConfig = com.example.zesto.stream.StreamConfig(url = "")
        manager.connect(context, invalidConfig)

        assertEquals(com.example.zesto.stream.ZestoEngineLifecycleState.ERROR, manager.lifecycleState.value)

        // Clean disconnect should restore DISCONNECTED
        manager.disconnect(context)
        assertEquals(com.example.zesto.stream.ZestoEngineLifecycleState.DISCONNECTED, manager.lifecycleState.value)
    }
}
