package com.wuxianggujun.tinaide.ui.compose.editor

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.TutorialViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial.TutorialScreen
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorialCourseInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun courseLoadsOfflineAndNextLessonStaysInsideTutorials() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var model: TutorialViewModel
        instrumentation.runOnMainSync { model = TutorialViewModel(context.applicationContext as Application) }
        composeRule.setContent { TinaIDETheme { TutorialScreen(viewModel = model) } }
        composeRule.runOnIdle { model.openTutorialByLinkTarget("ide-workflow.md") }
        val complete = context.getString(Strings.tutorial_complete)
        composeRule.waitUntil(15_000) { composeRule.onAllNodes(hasText(complete)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(complete).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(Strings.course_cpp_basics_title)).performScrollTo().performClick()
        composeRule.waitUntil(15_000) { model.uiState.value.selectedTutorial?.id == "cpp_basics" }
        composeRule.onNodeWithText(complete).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("cpp_basics", model.uiState.value.selectedTutorial?.id) }
        for (lesson in listOf("cpp-functions", "cpp-collections", "cpp-raii", "cmake-practice", "vcpkg-guide", "graphics-sdl3", "ai-practice")) {
            composeRule.runOnIdle { model.openTutorialByLinkTarget("$lesson.md") }
            composeRule.waitUntil(15_000) { composeRule.onAllNodes(hasText(complete)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(complete).assertIsDisplayed()
        }
    }
}
