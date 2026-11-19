package com.wuxianggujun.tinaide.tutorial

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
import com.wuxianggujun.tinaide.tutorial.data.TutorialCourseStep
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import com.wuxianggujun.tinaide.tutorial.data.TutorialWithProgress
import org.junit.Test

class TutorialCatalogUiStateTest {

    @Test
    fun firstUnlockedCourseStep_shouldRequireEarlierStepsToBeCompleted() {
        val steps = (0..3).map { index ->
            TutorialCourseStep(
                id = "step-$index",
                titleRes = Strings.tutorial_title,
                descriptionRes = Strings.tutorial_empty,
            )
        }

        assertThat(firstUnlockedCourseStepIndex(steps, emptySet())).isEqualTo(0)
        assertThat(firstUnlockedCourseStepIndex(steps, setOf("step-0", "step-1"))).isEqualTo(2)
        assertThat(firstUnlockedCourseStepIndex(steps, steps.map { it.id }.toSet())).isEqualTo(steps.lastIndex)
        assertThat(firstUnlockedCourseStepIndex(emptyList(), emptySet())).isEqualTo(0)
    }

    @Test
    fun normalizeTutorialStepIndex_shouldClampCorruptIndicesToValidRange() {
        assertThat(normalizeTutorialStepIndex(requestedIndex = -3, stepCount = 4)).isEqualTo(0)
        assertThat(normalizeTutorialStepIndex(requestedIndex = 99, stepCount = 4)).isEqualTo(3)
        assertThat(normalizeTutorialStepIndex(requestedIndex = 99, stepCount = 0)).isEqualTo(0)
    }

    @Test
    fun normalizeCompletedCourseStepIds_shouldDropStaleIdsAndCompletionsAfterGaps() {
        val steps = (0..3).map { index ->
            TutorialCourseStep(
                id = "step-$index",
                titleRes = Strings.tutorial_title,
                descriptionRes = Strings.tutorial_empty,
            )
        }

        assertThat(
            normalizeCompletedCourseStepIds(
                courseSteps = steps,
                completedStepIds = setOf("step-0", "step-2", "removed-step"),
            ),
        ).containsExactly("step-0")
    }

    @Test
    fun uncheckingCourseStep_shouldInvalidateLaterStepsAndRewindCurrentPosition() {
        val steps = (0..3).map { index ->
            TutorialCourseStep(
                id = "step-$index",
                titleRes = Strings.tutorial_title,
                descriptionRes = Strings.tutorial_empty,
            )
        }

        val update = updateCourseStepCompletion(
            courseSteps = steps,
            completedStepIds = steps.take(3).map { it.id }.toSet(),
            currentStepIndex = 3,
            stepId = "step-1",
            completed = false,
        )

        assertThat(update.completedStepIds).containsExactly("step-0")
        assertThat(update.currentStepIndex).isEqualTo(1)
        assertThat(firstUnlockedCourseStepIndex(steps, update.completedStepIds)).isEqualTo(1)
    }

    @Test
    fun completingCourseStep_shouldNotRestoreCompletionsAfterAnExistingGap() {
        val steps = (0..2).map { index ->
            TutorialCourseStep(
                id = "step-$index",
                titleRes = Strings.tutorial_title,
                descriptionRes = Strings.tutorial_empty,
            )
        }

        val update = updateCourseStepCompletion(
            courseSteps = steps,
            completedStepIds = setOf("step-0", "step-2"),
            currentStepIndex = 1,
            stepId = "step-1",
            completed = true,
        )

        assertThat(update.completedStepIds).containsExactly("step-0", "step-1")
        assertThat(update.currentStepIndex).isEqualTo(1)
    }

    @Test
    fun completingCourseStep_shouldPreserveValidLaterProgressAndCurrentPosition() {
        val steps = (0..2).map { index ->
            TutorialCourseStep(
                id = "step-$index",
                titleRes = Strings.tutorial_title,
                descriptionRes = Strings.tutorial_empty,
            )
        }

        val update = updateCourseStepCompletion(
            courseSteps = steps,
            completedStepIds = steps.map { it.id }.toSet(),
            currentStepIndex = 1,
            stepId = "step-1",
            completed = true,
        )

        assertThat(update.completedStepIds).containsExactly("step-0", "step-1", "step-2")
        assertThat(update.currentStepIndex).isEqualTo(1)
    }

    @Test
    fun completingLockedCourseStep_shouldNotGrantCompletionOrUnlockLaterSteps() {
        val steps = (0..3).map { index ->
            TutorialCourseStep(
                id = "step-$index",
                titleRes = Strings.tutorial_title,
                descriptionRes = Strings.tutorial_empty,
            )
        }

        val update = updateCourseStepCompletion(
            courseSteps = steps,
            completedStepIds = setOf("step-0"),
            currentStepIndex = 1,
            stepId = "step-3",
            completed = true,
        )

        assertThat(update.completedStepIds).containsExactly("step-0")
        assertThat(update.currentStepIndex).isEqualTo(1)
        assertThat(firstUnlockedCourseStepIndex(steps, update.completedStepIds)).isEqualTo(1)
    }

    @Test
    fun defaultState_shouldRepresentLoadingInsteadOfEmptyCatalog() {
        val state = TutorialCatalogUiState()

        assertThat(state.isLoading).isTrue()
        assertThat(state.tutorials).isEmpty()
        assertThat(state.tutorialsByCategory).isEmpty()
    }

    @Test
    fun loadedState_shouldSortCategoriesAndTutorials() {
        val state = buildTutorialCatalogUiState(
            listOf(
                tutorial(id = "advanced-2", category = TutorialCategory.ADVANCED, order = 2),
                tutorial(id = "start-1", category = TutorialCategory.GETTING_STARTED, order = 1),
                tutorial(id = "advanced-1", category = TutorialCategory.ADVANCED, order = 1),
            )
        )

        assertThat(state.isLoading).isFalse()
        assertThat(state.tutorials).hasSize(3)
        assertThat(state.tutorialsByCategory.keys).containsExactly(
            TutorialCategory.GETTING_STARTED,
            TutorialCategory.ADVANCED,
        ).inOrder()
        val advancedTutorialIds = state.tutorialsByCategory
            .getValue(TutorialCategory.ADVANCED)
            .map { it.tutorial.id }
        assertThat(advancedTutorialIds)
            .containsExactly("advanced-1", "advanced-2")
            .inOrder()
    }

    private fun tutorial(
        id: String,
        category: TutorialCategory,
        order: Int,
    ): TutorialWithProgress = TutorialWithProgress(
        tutorial = Tutorial(
            id = id,
            titleRes = Strings.tutorial_title,
            descriptionRes = Strings.tutorial_empty,
            category = category,
            type = TutorialType.GUIDED,
            estimatedMinutes = 1,
            order = order,
        ),
        progress = null,
    )
}
