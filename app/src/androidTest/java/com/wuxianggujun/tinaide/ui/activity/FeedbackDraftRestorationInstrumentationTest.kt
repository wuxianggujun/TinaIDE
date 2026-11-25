package com.wuxianggujun.tinaide.ui.activity

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.settings.SettingsActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackDraftRestorationInstrumentationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun dialogAndEnteredFieldsSurviveActivityRecreationWithoutSubmitting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_INITIAL_ROUTE, "about")
        ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
            composeRule.onNodeWithText(context.getString(Strings.settings_feedback)).performScrollTo().performClick()
            val message = hasSetTextAction() and hasText(context.getString(Strings.feedback_message_label))
            val contact = hasSetTextAction() and hasText(context.getString(Strings.feedback_contact_label))
            composeRule.onNode(message).performTextInput("rotation-draft")
            composeRule.onNode(contact).performTextInput("qa@example.invalid")
            scenario.recreate()
            composeRule.onNodeWithText(context.getString(Strings.feedback_dialog_title)).assertIsDisplayed()
            composeRule.onNode(message).assertTextContains("rotation-draft")
            composeRule.onNode(contact).assertTextContains("qa@example.invalid")
        }
    }
}
