package com.wuxianggujun.tinaide.tutorial.repository

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.TutorialProgress
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
class TutorialProgressStoreTest {
    @Test fun verificationWritesOnlyToTheUnchangedChapter() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val store = TutorialProgressStore(application)
        store.clearAllProgress()
        val course = "cpp_practice_course"
        assertThat(store.recordVerifiedStep(course, "ide_workflow", 0, emptySet(), null)).isTrue()
        val baseline = store.getProgress(course)
        store.updateStepProgress(course, 1)
        assertThat(store.recordVerifiedStep(course, "ide_workflow", 0, emptySet(), baseline)).isFalse()
        val current = store.getProgress(course)
        assertThat(store.recordVerifiedStep(course, "cpp_basics", 1, setOf("ide_workflow"), current)).isTrue()
        assertThat(store.getProgress(course)?.completedStepIds).containsExactly("ide_workflow", "cpp_basics")
        store.clearAllProgress()
        assertThat(store.recordVerifiedStep(course, "cpp_basics", 1, setOf("ide_workflow"), current)).isFalse()
        assertThat(store.getProgress(course)).isNull()
    }

    @Test
    fun courseStepCompletion_shouldPersistAndCanBeUnchecked() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val store = TutorialProgressStore(application)
        store.clearAllProgress()

        store.startTutorial("cpp_practice_course")
        val progressUpdate = launch {
            store.updateStepProgress("cpp_practice_course", stepIndex = 2)
        }
        val completionUpdate = launch {
            store.setStepCompleted("cpp_practice_course", "cpp-basics", completed = true)
        }
        progressUpdate.join()
        completionUpdate.join()

        val completedProgress = store.getProgress("cpp_practice_course")
        assertThat(completedProgress?.completedStepIds).containsExactly("cpp-basics")
        assertThat(completedProgress?.currentStepIndex).isEqualTo(2)

        store.setStepCompleted("cpp_practice_course", "cpp-basics", completed = false)

        assertThat(store.getProgress("cpp_practice_course")?.completedStepIds).isEmpty()
        assertThat(store.getProgress("cpp_practice_course")?.status)
            .isEqualTo(ProgressStatus.IN_PROGRESS)
        store.clearAllProgress()
    }

    @Test
    fun updateCourseProgress_shouldPersistCompletionSetAndCurrentIndexTogether() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val store = TutorialProgressStore(application)
        store.clearAllProgress()

        store.saveProgress(
            TutorialProgress(
                tutorialId = "cpp_practice_course",
                status = ProgressStatus.COMPLETED,
                currentStepIndex = 9,
                completedAt = 123L,
                completedStepIds = setOf("ide_workflow", "cpp_basics", "cpp_functions"),
            ),
        )
        store.updateCourseProgress(
            tutorialId = "cpp_practice_course",
            completedStepIds = setOf("ide_workflow"),
            currentStepIndex = 1,
        )

        val progress = store.getProgress("cpp_practice_course")
        assertThat(progress?.completedStepIds).containsExactly("ide_workflow")
        assertThat(progress?.currentStepIndex).isEqualTo(1)
        assertThat(progress?.status).isEqualTo(ProgressStatus.IN_PROGRESS)
        assertThat(progress?.completedAt).isNull()

        store.clearAllProgress()
    }

    @Test
    fun corruptProgress_shouldBeBackedUpBeforeAWriteRepairsTheActiveMap() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val dataStoreFile = File(application.cacheDir, "tutorial-progress-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { dataStoreFile },
        )
        val progressKey = stringPreferencesKey("all_tutorial_progress")
        val backupKey = stringPreferencesKey("all_tutorial_progress_corrupt_backup")
        val corruptPayload = "{not valid tutorial progress"
        dataStore.edit { preferences -> preferences[progressKey] = corruptPayload }
        val store = TutorialProgressStore(dataStore)

        assertThat(store.getAllProgress()).isEmpty()
        store.startTutorial("new-course")

        val repairedPreferences = dataStore.data.first()
        assertThat(repairedPreferences[backupKey]).isEqualTo(corruptPayload)
        assertThat(store.getProgress("new-course")?.status).isEqualTo(ProgressStatus.IN_PROGRESS)
    }

    @Test
    fun clearAllProgress_shouldAlsoResetOnboardingState() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val store = TutorialProgressStore(application)
        store.clearAllProgress()
        store.setOnboardingCompleted(true)
        store.setOnboardingSkipped(true)
        store.startTutorial("onboarding")

        store.clearAllProgress()

        assertThat(store.getAllProgress()).isEmpty()
        assertThat(store.hasCompletedOnboarding()).isFalse()
        assertThat(store.shouldShowOnboarding()).isTrue()
    }

    @Test
    fun completeOnboardingTutorial_shouldUpdateProgressAndPromptFlagsAtomically() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val store = TutorialProgressStore(application)
        store.clearAllProgress()
        store.setOnboardingSkipped(true)

        store.completeOnboardingTutorial("onboarding")

        val progress = store.getProgress("onboarding")
        assertThat(progress?.status).isEqualTo(ProgressStatus.COMPLETED)
        assertThat(progress?.completedAt).isNotNull()
        assertThat(store.hasCompletedOnboarding()).isTrue()
        assertThat(store.shouldShowOnboarding()).isFalse()
        assertThat(store.hasSkippedOnboardingFlow.first()).isFalse()
        store.clearAllProgress()
    }

    @Test
    fun updateStepProgress_shouldClearCompletionTimeWhenLearningResumes() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(application.cacheDir, "tutorial-resume-${System.nanoTime()}.preferences_pb") },
        )
        val store = TutorialProgressStore(dataStore)
        store.completeTutorial("course")

        store.updateStepProgress("course", stepIndex = 1)

        val progress = store.getProgress("course")
        assertThat(progress?.status).isEqualTo(ProgressStatus.IN_PROGRESS)
        assertThat(progress?.currentStepIndex).isEqualTo(1)
        assertThat(progress?.completedAt).isNull()
    }

    @Test
    fun startTutorial_shouldClearStaleCompletionTimeWithoutLosingInProgressChapters() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(application.cacheDir, "tutorial-start-${System.nanoTime()}.preferences_pb") },
        )
        val store = TutorialProgressStore(dataStore)
        store.saveProgress(
            TutorialProgress(
                tutorialId = "course",
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = 1,
                completedStepIds = setOf("first-step"),
                completedAt = 123L,
            ),
        )

        store.startTutorial("course")

        val progress = store.getProgress("course")
        assertThat(progress?.currentStepIndex).isEqualTo(1)
        assertThat(progress?.completedStepIds).containsExactly("first-step")
        assertThat(progress?.completedAt).isNull()
    }
}
