package com.wuxianggujun.tinaide.ui.compose.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class RunConfigSelectorResponsiveLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrowTitleSlot_keepsRunActionVisible() {
        assertRunActionFits(titleSlotWidth = 60.dp)
    }

    @Test
    fun compactTitleSlot_keepsRunActionVisibleBesideConfig() {
        assertRunActionFits(titleSlotWidth = 80.dp)
    }

    private fun assertRunActionFits(titleSlotWidth: Dp) {
        val context = RuntimeEnvironment.getApplication()
        showSelector(titleSlotWidth)

        val runAction = composeRule
            .onNodeWithContentDescription(context.getString(Strings.action_run))
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val titleSlot = composeRule
            .onNodeWithTag(TITLE_SLOT_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        assertThat(runAction.width).isGreaterThan(0f)
        assertThat(runAction.left).isAtLeast(titleSlot.left)
        assertThat(runAction.right).isAtMost(titleSlot.right)
    }

    @Test
    fun runAndStopUseTheSameButtonBoundsAndInvokeTheCurrentAction() {
        val context = RuntimeEnvironment.getApplication()
        val running = mutableStateOf(false)
        var runs = 0
        var stops = 0
        showSelector(80.dp, isRunning = { running.value },
            onRun = { runs++; running.value = true }, onStop = { stops++; running.value = false })
        val run = composeRule.onNodeWithContentDescription(context.getString(Strings.action_run))
        val originalBounds = run.fetchSemanticsNode().boundsInRoot
        run.performClick()

        val stop = composeRule.onNodeWithContentDescription(context.getString(Strings.content_desc_stop))
            .assertIsDisplayed().assertIsEnabled()
        assertThat(stop.fetchSemanticsNode().boundsInRoot).isEqualTo(originalBounds)
        composeRule.onAllNodesWithContentDescription(context.getString(Strings.action_run)).assertCountEquals(0)
        stop.performClick()
        composeRule.onNodeWithContentDescription(context.getString(Strings.action_run)).assertIsDisplayed().assertIsEnabled()
        composeRule.runOnIdle {
            assertThat(runs).isEqualTo(1)
            assertThat(stops).isEqualTo(1)
        }
    }

    @Test
    fun narrowSlotKeepsStopClickableWhenRunIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        var stops = 0
        showSelector(60.dp, isRunning = { true }, onStop = { stops++ })
        composeRule.onNodeWithContentDescription(context.getString(Strings.content_desc_stop))
            .assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertThat(stops).isEqualTo(1) }
    }

    private fun showSelector(
        titleSlotWidth: Dp,
        isRunning: () -> Boolean = { false },
        onRun: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(titleSlotWidth)
                        .height(48.dp)
                        .testTag(TITLE_SLOT_TAG),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    RunConfigSelector(
                        configManager = RunConfigurationManager(),
                        onSelectConfig = {},
                        onAddConfig = {},
                        onEditConfig = {},
                        onDuplicateConfig = {},
                        onDeleteConfig = {},
                        configSegmentMaxWidth = 72.dp,
                        isRunEnabled = !isRunning(),
                        isRunning = isRunning(),
                        onRun = onRun,
                        onStop = onStop,
                    )
                }
            }
        }

    }

    private companion object {
        const val TITLE_SLOT_TAG = "run-config-title-slot"
    }
}
