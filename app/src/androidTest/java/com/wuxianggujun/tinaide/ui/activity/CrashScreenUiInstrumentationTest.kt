package com.wuxianggujun.tinaide.ui.activity

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.ui.MainPortalActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render on the actual Android runtime, without desktop native-graphics/classloader substitutions. */
@RunWith(AndroidJUnit4::class)
class CrashScreenUiInstrumentationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainPortalActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun userProgram_hasNoHostActionsAndKeepsTheRawReportCollapsed() {
        show(CrashScreenOrigin.USER_PROGRAM)
        node(Strings.crash_btn_return_editor).assertExists()
        node(Strings.crash_btn_restart).assertDoesNotExist()
        node(Strings.crash_btn_exit).assertDoesNotExist()
        composeRule.onNodeWithText(REPORT).assertDoesNotExist()
        node(Strings.crash_btn_show_details).performClick()
        composeRule.onNodeWithText(REPORT).assertExists()
        node(Strings.crash_btn_hide_details).performClick()
        composeRule.onNodeWithText(REPORT).assertDoesNotExist()
    }

    @Test
    fun hostCrash_hasExplicitIdeActions() {
        show(CrashScreenOrigin.HOST_APP)
        node(Strings.crash_btn_restart).assertExists()
        node(Strings.crash_btn_exit).assertExists()
        node(Strings.crash_btn_return_editor).assertDoesNotExist()
    }

    @Test
    fun unknownComponent_neverOffersHostRestartOrExit() {
        show(CrashScreenOrigin.COMPONENT)
        node(Strings.crash_btn_restart).assertDoesNotExist()
        node(Strings.crash_btn_exit).assertDoesNotExist()
        node(Strings.crash_btn_close).assertExists()
    }

    @Test
    fun repeatedIdenticalCrash_resetsDetailsForTheNewEvent() {
        var eventId by mutableIntStateOf(1)
        show(CrashScreenOrigin.USER_PROGRAM) { eventId }
        node(Strings.crash_btn_show_details).performClick()
        composeRule.onNodeWithText(REPORT).assertExists()
        composeRule.runOnIdle { eventId++ }
        composeRule.onNodeWithText(REPORT).assertDoesNotExist()
    }

    private fun node(resource: Int) = composeRule.onNodeWithText(
        resource.strOr(InstrumentationRegistry.getInstrumentation().targetContext)
    )

    private fun show(origin: CrashScreenOrigin, eventId: () -> Int = { 0 }) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch<MainPortalActivity>(
            Intent(context, MainPortalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).also { activityScenario ->
            activityScenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        CrashScreen(REPORT, origin, true, null, {}, {}, {}, {}, eventId())
                    }
                }
            }
        }
    }

    private companion object {
        const val REPORT = "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0\nbacktrace: acceptance fixture"
    }
}
