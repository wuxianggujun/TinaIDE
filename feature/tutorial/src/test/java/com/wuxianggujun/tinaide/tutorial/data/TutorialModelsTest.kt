package com.wuxianggujun.tinaide.tutorial.data

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.junit.Test

class TutorialModelsTest {

    @Test
    fun categoryFromString_shouldIgnoreCaseAndRejectUnknownValues() {
        assertThat(TutorialCategory.fromString("getting_started"))
            .isEqualTo(TutorialCategory.GETTING_STARTED)
        assertThat(TutorialCategory.fromString("Editor"))
            .isEqualTo(TutorialCategory.EDITOR)
        assertThat(TutorialCategory.fromString("missing")).isNull()
    }

    @Test
    fun tutorialWithProgress_shouldDefaultToNotStartedWhenProgressMissing() {
        val tutorial = tutorialWithSteps(stepCount = 3)
        val item = TutorialWithProgress(tutorial = tutorial, progress = null)

        assertThat(item.status).isEqualTo(ProgressStatus.NOT_STARTED)
        assertThat(item.progressPercent).isEqualTo(0)
        assertThat(item.isCompleted).isFalse()
        assertThat(item.isInProgress).isFalse()
    }

    @Test
    fun tutorialWithProgress_shouldCalculateProgressPercentFromCurrentStep() {
        val item = TutorialWithProgress(
            tutorial = tutorialWithSteps(stepCount = 4),
            progress = TutorialProgress(
                tutorialId = "intro",
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = 2
            )
        )

        assertThat(item.progressPercent).isEqualTo(75)
        assertThat(item.isInProgress).isTrue()
    }

    @Test
    fun tutorialWithProgress_shouldCountFirstInteractiveStepAsInProgress() {
        val item = TutorialWithProgress(
            tutorial = tutorialWithSteps(stepCount = 4),
            progress = TutorialProgress(
                tutorialId = "intro",
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = 0,
            ),
        )

        assertThat(item.progressPercent).isEqualTo(25)
    }

    @Test
    fun completedTutorialWithoutSteps_shouldStillReportFullProgress() {
        val item = TutorialWithProgress(
            tutorial = tutorialWithSteps(stepCount = 0),
            progress = TutorialProgress(
                tutorialId = "intro",
                status = ProgressStatus.COMPLETED,
            ),
        )

        assertThat(item.progressPercent).isEqualTo(100)
    }

    @Test
    fun tutorialWithProgress_shouldUseCourseStagesForGuidedCourseProgress() {
        val tutorial = tutorialWithSteps(stepCount = 0).copy(
            type = TutorialType.GUIDED,
            courseSteps = (0 until 5).map { index ->
                TutorialCourseStep(
                    id = "course-step-$index",
                    titleRes = Strings.tutorial_cat_getting_started,
                    descriptionRes = Strings.tutorial_cat_getting_started_desc,
                )
            },
        )
        val item = TutorialWithProgress(
            tutorial = tutorial,
            progress = TutorialProgress(
                tutorialId = tutorial.id,
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = 2,
                completedStepIds = setOf("course-step-0", "course-step-1"),
            ),
        )

        assertThat(item.progressPercent).isEqualTo(40)
    }

    @Test
    fun tutorialWithProgress_shouldIgnoreCompletionIdsFromRemovedCourseSteps() {
        val tutorial = tutorialWithSteps(stepCount = 0).copy(
            type = TutorialType.GUIDED,
            courseSteps = (0 until 4).map { index ->
                TutorialCourseStep(
                    id = "course-step-$index",
                    titleRes = Strings.tutorial_cat_getting_started,
                    descriptionRes = Strings.tutorial_cat_getting_started_desc,
                )
            },
        )

        val item = TutorialWithProgress(
            tutorial = tutorial,
            progress = TutorialProgress(
                tutorialId = tutorial.id,
                status = ProgressStatus.IN_PROGRESS,
                completedStepIds = setOf("course-step-0", "removed-step-1", "removed-step-2"),
            ),
        )

        assertThat(item.progressPercent).isEqualTo(25)
    }

    @Test
    fun tutorialWithProgress_shouldNotCountUnconfirmedCurrentCourseStepAsComplete() {
        val tutorial = tutorialWithSteps(stepCount = 0).copy(
            type = TutorialType.GUIDED,
            courseSteps = (0 until 5).map { index ->
                TutorialCourseStep(
                    id = "course-step-$index",
                    titleRes = Strings.tutorial_cat_getting_started,
                    descriptionRes = Strings.tutorial_cat_getting_started_desc,
                )
            },
        )

        assertThat(
            TutorialWithProgress(
                tutorial = tutorial,
                progress = TutorialProgress(
                    tutorialId = tutorial.id,
                    status = ProgressStatus.IN_PROGRESS,
                    currentStepIndex = 4,
                ),
            ).progressPercent,
        ).isEqualTo(0)
    }

    @Test
    fun tutorialWithProgress_shouldReportCompletedAsFullProgress() {
        val item = TutorialWithProgress(
            tutorial = tutorialWithSteps(stepCount = 4),
            progress = TutorialProgress(
                tutorialId = "intro",
                status = ProgressStatus.COMPLETED,
                currentStepIndex = 1,
                completedAt = 123L
            )
        )

        assertThat(item.progressPercent).isEqualTo(100)
        assertThat(item.isCompleted).isTrue()
    }

    private fun tutorialWithSteps(stepCount: Int): Tutorial = Tutorial(
        id = "intro",
        titleRes = Strings.tutorial_cat_getting_started,
        descriptionRes = Strings.tutorial_cat_getting_started_desc,
        category = TutorialCategory.GETTING_STARTED,
        type = TutorialType.INTERACTIVE,
        estimatedMinutes = 5,
        order = 0,
        steps = (0 until stepCount).map { index ->
            TutorialStep(
                id = "step-$index",
                targetId = "target-$index",
                titleRes = Strings.tutorial_cat_getting_started,
                descriptionRes = Strings.tutorial_cat_getting_started_desc
            )
        }
    )
}
