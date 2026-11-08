package com.wuxianggujun.tinaide.ui.compose.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.IntSize
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class GraphicalRuntimeOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun enabledLogIsVisibleImmediatelyAndCanBeClosedAndReopened() {
        val title = RuntimeEnvironment.getApplication().getString(Strings.floating_overlay_log_title)
        compose.setContent {
            MaterialTheme {
                GraphicalRuntimeOverlay(true, onExit = {}, windowBounds = IntSize(1080, 1920), onDragWindow = {})
            }
        }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithContentDescription(title).performTouchInput { click() }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithContentDescription(title).performTouchInput { click() }
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun disabledLogKeepsReturnControlWithoutLogPanel() {
        val app = RuntimeEnvironment.getApplication()
        compose.setContent {
            MaterialTheme {
                GraphicalRuntimeOverlay(false, onExit = {}, windowBounds = IntSize(1080, 1920), onDragWindow = {})
            }
        }
        compose.onNodeWithText(app.getString(Strings.floating_overlay_log_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(Strings.floating_overlay_ball_desc)).assertIsDisplayed()
    }
}
