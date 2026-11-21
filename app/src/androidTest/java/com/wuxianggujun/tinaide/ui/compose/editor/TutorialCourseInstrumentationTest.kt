package com.wuxianggujun.tinaide.ui.compose.editor

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.TutorialViewModel
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.tutorial.repository.TutorialRepository
import com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial.TutorialScreen
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorialCourseInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun learnerCanCompleteTheOfflineCourseAndResumeProgressIsPersisted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
        val progressStore = TutorialProgressStore(application)
        val course = runBlocking {
            progressStore.clearAllProgress()
            TutorialRepository(application, progressStore)
                .getAllTutorialsWithProgress()
                .first()
                .single { it.tutorial.type == TutorialType.GUIDED }
                .tutorial
        }
        lateinit var viewModel: TutorialViewModel
        instrumentation.runOnMainSync { viewModel = TutorialViewModel(application) }

        composeRule.setContent { TinaIDETheme { TutorialScreen(viewModel = viewModel) } }
        composeRule.runOnIdle { viewModel.openTutorialByLinkTarget("ide-workflow.md") }
        composeRule.waitUntil(15_000) { viewModel.uiState.value.selectedTutorial != null }

        composeRule.onNodeWithTag("tutorial-course-outline").performClick()
        composeRule.onNodeWithTag("tutorial-course-outline-step-cpp_functions").assertIsNotEnabled()
        composeRule.onNodeWithTag("tutorial-course-outline").performClick()

        course.courseSteps.dropLast(1).forEachIndexed { index, step ->
            val confirmationTag = "tutorial-course-confirm-${step.id}"
            composeRule.waitUntil(15_000) {
                viewModel.uiState.value.currentCourseStepIndex == index &&
                    composeRule.onAllNodes(hasTestTag(confirmationTag)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("tutorial-course-hint-${step.id}-0").assertDoesNotExist()
            if (index == 0) {
                repeat(3) {
                    composeRule.onNodeWithTag("tutorial-course-hint-next-${step.id}")
                        .performScrollTo().performClick()
                }
                composeRule.onNodeWithTag("tutorial-course-hint-${step.id}-2").assertExists()
                composeRule.onNodeWithTag("tutorial-course-continue").assertIsNotEnabled()
                composeRule.onAllNodesWithText(context.getString(Strings.action_copy)).onFirst()
                    .performScrollTo().performClick()
                composeRule.runOnIdle {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(clipboard.primaryClip?.getItemAt(0)?.text.toString().contains("First change"))
                    assertTrue(viewModel.uiState.value.completedCourseStepIds.isEmpty())
                }
                composeRule.onNodeWithTag("tutorial-course-hints-hide-${step.id}")
                    .performScrollTo().performClick()
                composeRule.onNodeWithTag("tutorial-course-hint-${step.id}-0").assertDoesNotExist()
            }
            composeRule.onNodeWithTag(confirmationTag).performScrollTo().performClick()
            composeRule.waitUntil(5_000) { step.id in viewModel.uiState.value.completedCourseStepIds }

            if (index == 0) {
                composeRule.onNodeWithTag("tutorial-course-outline").performClick()
                composeRule.onNodeWithTag("tutorial-course-outline-step-cpp_basics").assertIsEnabled()
                composeRule.onNodeWithText(context.getString(Strings.tutorial_course_chapter_available))
                composeRule.onNodeWithTag("tutorial-course-outline-step-cpp_basics").performClick()
                composeRule.waitUntil(5_000) {
                    viewModel.uiState.value.currentCourseStepIndex == index + 1
                }
                composeRule.runOnIdle { viewModel.closeTutorialContent() }
                composeRule.waitUntil(5_000) { viewModel.uiState.value.selectedTutorial == null }
                composeRule.runOnIdle { viewModel.continueTutorial(course) }
                composeRule.waitUntil(5_000) {
                    viewModel.uiState.value.selectedTutorial?.id == course.id &&
                        viewModel.uiState.value.currentCourseStepIndex == index + 1 &&
                        step.id in viewModel.uiState.value.completedCourseStepIds
                }
            } else {
                composeRule.onNodeWithTag("tutorial-course-continue").performClick()
            }
            composeRule.waitUntil(5_000) { viewModel.uiState.value.currentCourseStepIndex == index + 1 }
        }

        val finalStep = course.courseSteps.last()
        repeat(3) {
            composeRule.onNodeWithTag("tutorial-course-hint-next-${finalStep.id}")
                .performScrollTo().performClick()
        }
        composeRule.onNodeWithTag("tutorial-course-continue").assertIsNotEnabled()
        composeRule.onNodeWithTag("tutorial-course-quiz-answer-0").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { finalStep.id in viewModel.uiState.value.completedCourseStepIds }
        composeRule.onNodeWithText(context.getString(Strings.tutorial_course_finish))
            .performClick()
        composeRule.waitUntil(10_000) { viewModel.uiState.value.selectedTutorial == null }

        val savedProgress = runBlocking { progressStore.getProgress(course.id) }
        assertEquals(ProgressStatus.COMPLETED, savedProgress?.status)
        assertEquals(course.courseSteps.map { it.id }.toSet(), savedProgress?.completedStepIds)
        assertTrue(savedProgress?.completedAt != null)
    }
}
