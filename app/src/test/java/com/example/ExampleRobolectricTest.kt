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
}
