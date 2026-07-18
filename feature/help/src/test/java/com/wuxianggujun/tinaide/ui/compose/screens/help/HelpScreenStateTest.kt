package com.wuxianggujun.tinaide.ui.compose.screens.help

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.help.HelpCategory
import com.wuxianggujun.tinaide.core.help.HelpDocument
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
class HelpScreenStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun documentErrorState_shouldExposeRetryAction() {
        var retryCount = 0
        val context = RuntimeEnvironment.getApplication()

        composeRule.setContent {
            MaterialTheme {
                HelpDocumentContent(
                    document = testDocument(),
                    content = null,
                    isLoading = false,
                    onCreatePluginProject = {},
                    onOpenPluginSettings = {},
                    onRetry = { retryCount += 1 },
                    onLinkClick = {},
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(Strings.help_load_failed))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(Strings.action_retry))
            .assertIsDisplayed()
            .performClick()

        assertThat(retryCount).isEqualTo(1)
    }

    private fun testDocument() = HelpDocument(
        id = "help-screen-test",
        title = "Help screen test",
        category = HelpCategory.GETTING_STARTED,
        fileName = "help-screen-test.md",
    )
}
