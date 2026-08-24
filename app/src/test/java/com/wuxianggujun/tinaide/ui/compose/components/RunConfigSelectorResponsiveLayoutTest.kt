package com.wuxianggujun.tinaide.ui.compose.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
        val context = RuntimeEnvironment.getApplication<Application>()
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
                    )
                }
            }
        }

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

    private companion object {
        const val TITLE_SLOT_TAG = "run-config-title-slot"
    }
}
