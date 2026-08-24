package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.theme.ZestoTheme
import com.example.zesto.ui.StreamConfigScreen
import com.example.zesto.ui.ZestoUiState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun zesto_config_screenshot() {
        composeTestRule.setContent {
            ZestoTheme {
                StreamConfigScreen(
                    uiState = ZestoUiState(),
                    onUrlChange = {},
                    onProtocolChange = {},
                    onResolutionChange = { _, _ -> },
                    onFpsChange = {},
                    onTestConnection = {},
                    onConnect = {},
                    onDisconnect = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
    }
}
