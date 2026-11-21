package com.wuxianggujun.tinaide.tutorial

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.TutorialProgress
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.tutorial.repository.TutorialRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.IOException
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TutorialViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val viewModelStore = ViewModelStore()
    private lateinit var application: Application
    private lateinit var preferences: PausingPreferencesDataStore
    private lateinit var progressStore: TutorialProgressStore
    private lateinit var repository: TutorialRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        application = RuntimeEnvironment.getApplication()
        preferences = PausingPreferencesDataStore()
        progressStore = TutorialProgressStore(preferences)
        repository = TutorialRepository(application, progressStore)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun returningFromEditorReloadsVerifiedCourseProgress() = runTest {
        val viewModel = createViewModel()
        val course = requireNotNull(repository.getTutorial(TutorialRepository.CPP_PRACTICE_COURSE_ID))
        viewModel.startTutorial(course)
        runCurrent()
        progressStore.setStepCompleted(course.id, course.courseSteps.first().id, true)
        viewModel.reloadSelectedCourseProgress()
        runCurrent()
        assertThat(viewModel.uiState.value.completedCourseStepIds).contains(course.courseSteps.first().id)
    }

    @Test
    fun closingCourse_shouldInvalidateAnOpeningThatIsStillSaving() = runTest {
        val viewModel = createViewModel()
        runCurrent()
        val gate = preferences.pauseNextWrite()

        viewModel.startTutorial(course())
        runCurrent()
        assertThat(gate.entered.isCompleted).isTrue()
        viewModel.closeTutorialContent()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(viewModel.uiState.value.showTutorialContent).isFalse()
        assertThat(viewModel.uiState.value.selectedTutorial).isNull()
        assertThat(viewModel.spotlightState.value.isVisible).isFalse()
    }

    @Test
    fun closingCourse_shouldInvalidateAContinueThatIsStillReading() = runTest {
        val viewModel = createViewModel()
        runCurrent()
        val gate = preferences.pauseNextRead()

        viewModel.continueTutorial(course())
        runCurrent()
        assertThat(gate.entered.isCompleted).isTrue()
        viewModel.closeTutorialContent()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(viewModel.uiState.value.showTutorialContent).isFalse()
        assertThat(progressStore.getProgress(TutorialRepository.CPP_PRACTICE_COURSE_ID)).isNull()
    }

    @Test
    fun newerOpen_shouldWinOverPendingCourseAndLinkRequests() = runTest {
        val viewModel = createViewModel()
        runCurrent()
        val course = course()
        val gate = preferences.pauseNextWrite()

        assertThat(viewModel.openTutorialByLinkTarget("cpp-basics.md")).isTrue()
        viewModel.startOnboarding()
        viewModel.continueTutorial(course)
        runCurrent()
        assertThat(gate.entered.isCompleted).isTrue()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(viewModel.uiState.value.selectedTutorial?.id).isEqualTo(course.id)
        assertThat(viewModel.uiState.value.showTutorialContent).isTrue()
        assertThat(viewModel.spotlightState.value.isVisible).isFalse()
        assertThat(progressStore.getProgress(TutorialRepository.ONBOARDING_TUTORIAL_ID)).isNull()
    }

    @Test
    fun immediateContinue_shouldWaitForConfirmationAndChapterPositionToBeSaved() = runTest {
        val viewModel = createViewModel()
        val course = course()
        viewModel.startTutorial(course)
        runCurrent()
        val gate = preferences.pauseNextWrite()

        viewModel.setCourseStepCompleted(course.id, course.courseSteps.first().id, true)
        viewModel.nextCourseStep(course)
        viewModel.closeTutorialContent()
        viewModel.continueTutorial(course)
        runCurrent()

        assertThat(gate.entered.isCompleted).isTrue()
        assertThat(viewModel.uiState.value.showTutorialContent).isFalse()
        gate.release.complete(Unit)
        runCurrent()

        val progress = progressStore.getProgress(course.id)
        assertThat(viewModel.uiState.value.showTutorialContent).isTrue()
        assertThat(viewModel.uiState.value.currentCourseStepIndex).isEqualTo(1)
        assertThat(viewModel.uiState.value.completedCourseStepIds).containsExactly(course.courseSteps.first().id)
        assertThat(progress?.currentStepIndex).isEqualTo(1)
        assertThat(progress?.completedStepIds).isEqualTo(viewModel.uiState.value.completedCourseStepIds)
    }

    @Test
    fun finishingCourse_shouldUseClickedProgressAndKeepAReopenedSessionVisible() = runTest {
        val viewModel = createViewModel()
        val course = course()
        val completedIds = course.courseSteps.map { it.id }.toSet()
        progressStore.updateCourseProgress(course.id, completedIds, course.courseSteps.lastIndex)
        viewModel.continueTutorial(course)
        runCurrent()
        val gate = preferences.pauseNextWrite()

        viewModel.completeTutorial(course.id)
        viewModel.closeTutorialContent()
        viewModel.continueTutorial(course)
        runCurrent()
        assertThat(gate.entered.isCompleted).isTrue()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(progressStore.getProgress(course.id)?.status).isEqualTo(ProgressStatus.COMPLETED)
        assertThat(viewModel.uiState.value.showTutorialContent).isTrue()
        assertThat(viewModel.uiState.value.selectedTutorial?.id).isEqualTo(course.id)
        assertThat(viewModel.uiState.value.completedCourseStepIds).isEqualTo(completedIds)
    }

    @Test
    fun finishingCourse_shouldIgnoreRepeatedFinishAndLateChapterEdits() = runTest {
        val viewModel = createViewModel()
        val course = course()
        val completedIds = course.courseSteps.map { it.id }.toSet()
        progressStore.updateCourseProgress(course.id, completedIds, course.courseSteps.lastIndex)
        viewModel.continueTutorial(course)
        runCurrent()
        val writesBeforeCompletion = preferences.writeCount
        val gate = preferences.pauseNextWrite()

        viewModel.completeTutorial(course.id)
        viewModel.completeTutorial(course.id)
        viewModel.setCourseStepCompleted(course.id, course.courseSteps.first().id, false)
        viewModel.previousCourseStep(course)
        gate.release.complete(Unit)
        runCurrent()

        val progress = progressStore.getProgress(course.id)
        assertThat(preferences.writeCount).isEqualTo(writesBeforeCompletion + 1)
        assertThat(progress?.status).isEqualTo(ProgressStatus.COMPLETED)
        assertThat(progress?.completedStepIds).isEqualTo(completedIds)
        assertThat(progress?.currentStepIndex).isEqualTo(course.courseSteps.lastIndex)
        assertThat(viewModel.uiState.value.showTutorialContent).isFalse()
    }

    @Test
    fun incompleteCourse_shouldRejectFinishWithoutWritingProgress() = runTest {
        val viewModel = createViewModel()
        val course = course()
        viewModel.startTutorial(course)
        runCurrent()
        val writesBeforeCompletion = preferences.writeCount

        viewModel.completeTutorial(course.id)
        runCurrent()

        assertThat(preferences.writeCount).isEqualTo(writesBeforeCompletion)
        assertThat(progressStore.getProgress(course.id)?.status).isEqualTo(ProgressStatus.IN_PROGRESS)
        assertThat(viewModel.uiState.value.showTutorialContent).isTrue()
    }

    @Test
    fun completedOnboarding_shouldNotDismissOrShowADialogOverANewerSession() = runTest {
        val viewModel = createViewModel()
        val onboarding = repository.getOnboardingTutorial()
        viewModel.startOnboarding()
        runCurrent()
        repeat(onboarding.steps.lastIndex) { viewModel.nextStep() }
        runCurrent()
        val gate = preferences.pauseNextWrite()

        viewModel.completeSpotlight()
        viewModel.startOnboarding()
        runCurrent()
        assertThat(gate.entered.isCompleted).isTrue()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(progressStore.hasCompletedOnboarding()).isTrue()
        assertThat(viewModel.spotlightState.value.isVisible).isTrue()
        assertThat(viewModel.spotlightState.value.currentStepIndex).isEqualTo(0)
        assertThat(viewModel.uiState.value.showOnboardingCompleteDialog).isFalse()
    }

    @Test
    fun skippedOnboarding_shouldNotDismissANewerSpotlight() = runTest {
        val viewModel = createViewModel()
        viewModel.startOnboarding()
        runCurrent()
        val gate = preferences.pauseNextWrite()

        viewModel.skipSpotlight()
        assertThat(viewModel.spotlightState.value.isVisible).isFalse()
        viewModel.startOnboarding()
        runCurrent()
        assertThat(gate.entered.isCompleted).isTrue()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(viewModel.spotlightState.value.isVisible).isTrue()
        assertThat(viewModel.spotlightState.value.currentStepIndex).isEqualTo(0)
    }

    @Test
    fun finishingCurrentOnboarding_shouldPersistAndShowItsCompletionDialogOnce() = runTest {
        val viewModel = createViewModel()
        viewModel.startOnboarding()
        runCurrent()
        repeat(repository.getOnboardingTutorial().steps.lastIndex) { viewModel.nextStep() }
        runCurrent()
        val writesBeforeCompletion = preferences.writeCount
        val gate = preferences.pauseNextWrite()

        viewModel.completeSpotlight()
        viewModel.completeSpotlight()
        viewModel.previousStep()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(preferences.writeCount).isEqualTo(writesBeforeCompletion + 1)
        assertThat(progressStore.hasCompletedOnboarding()).isTrue()
        assertThat(viewModel.spotlightState.value.isVisible).isFalse()
        assertThat(viewModel.uiState.value.showOnboardingCompleteDialog).isTrue()
        viewModel.dismissOnboardingCompleteDialog()
        assertThat(viewModel.uiState.value.showOnboardingCompleteDialog).isFalse()
    }

    @Test
    fun dismissedOnboardingPrompt_shouldNotReappearAfterItsInitialReadFinishes() = runTest {
        val gate = preferences.pauseNextRead()
        val viewModel = createViewModel()
        runCurrent()
        assertThat(gate.entered.isCompleted).isTrue()

        viewModel.dismissOnboardingPrompt()
        gate.release.complete(Unit)
        runCurrent()

        assertThat(viewModel.uiState.value.showOnboardingPrompt).isFalse()
    }

    @Test
    fun continueCourse_shouldPersistNormalizedCompletionsEvenWhenPositionHasNotChanged() = runTest {
        val course = course()
        progressStore.saveProgress(
            TutorialProgress(
                tutorialId = course.id,
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = 0,
                completedStepIds = setOf(course.courseSteps[0].id, course.courseSteps[2].id, "removed-step"),
            ),
        )
        val viewModel = createViewModel()

        viewModel.continueTutorial(course)
        runCurrent()

        assertThat(viewModel.uiState.value.completedCourseStepIds).containsExactly(course.courseSteps[0].id)
        assertThat(progressStore.getProgress(course.id)?.completedStepIds).containsExactly(course.courseSteps[0].id)
        assertThat(progressStore.getProgress(course.id)?.currentStepIndex).isEqualTo(0)
    }

    @Test
    fun linkedChapter_shouldRespectUnlockingAndPersistTheActualOpenedPosition() = runTest {
        val course = course()
        progressStore.updateCourseProgress(course.id, setOf(course.courseSteps.first().id), 0)
        val viewModel = createViewModel()

        assertThat(viewModel.openTutorialByLinkTarget("graphics-sdl3.md")).isTrue()
        runCurrent()

        assertThat(viewModel.uiState.value.currentCourseStepIndex).isEqualTo(1)
        assertThat(progressStore.getProgress(course.id)?.currentStepIndex).isEqualTo(1)
        assertThat(progressStore.getProgress(course.id)?.completedStepIds).containsExactly(course.courseSteps.first().id)
    }

    @Test
    fun continueCourse_shouldPersistAClampedPositionWithoutUnlockingChapters() = runTest {
        val course = course()
        progressStore.updateCourseProgress(course.id, setOf(course.courseSteps.first().id), 99)
        val viewModel = createViewModel()

        viewModel.continueTutorial(course)
        runCurrent()

        assertThat(viewModel.uiState.value.currentCourseStepIndex).isEqualTo(1)
        assertThat(progressStore.getProgress(course.id)?.currentStepIndex).isEqualTo(1)
        assertThat(viewModel.uiState.value.completedCourseStepIds).containsExactly(course.courseSteps.first().id)
    }

    @Test
    fun failedWrite_isRetainedAndRetriedBeforeLaterProgressChanges() = runTest {
        val viewModel = createViewModel()
        val course = course()
        viewModel.startTutorial(course)
        runCurrent()
        preferences.failNextWrite = true
        viewModel.setCourseStepCompleted(course.id, course.courseSteps.first().id, true)
        runCurrent()
        assertThat(viewModel.uiState.value.hasProgressError).isTrue()
        assertThat(progressStore.getProgress(course.id)?.completedStepIds).isEmpty()

        viewModel.nextCourseStep(course)
        runCurrent()
        assertThat(progressStore.getProgress(course.id)?.currentStepIndex).isEqualTo(0)
        viewModel.retryProgressOperation()
        runCurrent()

        assertThat(viewModel.uiState.value.hasProgressError).isFalse()
        assertThat(progressStore.getProgress(course.id)?.completedStepIds).containsExactly(course.courseSteps.first().id)
        assertThat(progressStore.getProgress(course.id)?.currentStepIndex).isEqualTo(1)
    }

    @Test
    fun failedOnboardingRead_canRetryWithoutCrashingOrBlockingCourseOpen() = runTest {
        preferences.failNextRead = true
        val viewModel = createViewModel()
        runCurrent()
        assertThat(viewModel.uiState.value.hasProgressError).isTrue()
        viewModel.continueTutorial(course())
        runCurrent()
        assertThat(viewModel.uiState.value.showTutorialContent).isFalse()
        viewModel.retryProgressOperation()
        runCurrent()
        assertThat(viewModel.uiState.value.hasProgressError).isFalse()
        assertThat(viewModel.uiState.value.showTutorialContent).isTrue()
    }

    @Test
    fun failedCatalogRead_exposesErrorAndCanReload() = runTest {
        val viewModel = createViewModel()
        runCurrent()
        preferences.failNextRead = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.tutorialCatalogState.collect()
        }
        runCurrent()
        assertThat(viewModel.tutorialCatalogState.value.hasLoadError).isTrue()
        assertThat(viewModel.tutorialCatalogState.value.isLoading).isFalse()
        viewModel.retryCatalogLoading()
        runCurrent()
        assertThat(viewModel.tutorialCatalogState.value.hasLoadError).isFalse()
        assertThat(viewModel.tutorialCatalogState.value.tutorials).isNotEmpty()
    }

    @Test
    fun repeatedWriteFailure_keepsPendingChangesUntilSuccessfulRetry() = runTest {
        val viewModel = createViewModel()
        val course = course()
        runCurrent()
        preferences.failNextWrite = true
        viewModel.startTutorial(course)
        runCurrent()
        preferences.failNextWrite = true
        viewModel.retryProgressOperation()
        runCurrent()
        assertThat(viewModel.uiState.value.hasProgressError).isTrue()
        assertThat(viewModel.uiState.value.isRetryingProgress).isFalse()
        viewModel.retryProgressOperation()
        runCurrent()
        assertThat(viewModel.uiState.value.showTutorialContent).isTrue()
        assertThat(viewModel.uiState.value.hasProgressError).isFalse()
    }

    private suspend fun course() = requireNotNull(repository.getTutorial(TutorialRepository.CPP_PRACTICE_COURSE_ID))

    private fun createViewModel() = TutorialViewModel(application, progressStore).also {
        viewModelStore.put("tutorial", it)
    }
}

/** Real serialization and transaction transforms, with deterministic pauses at the storage boundary. */
private class PausingPreferencesDataStore : DataStore<Preferences> {
    private val preferences = MutableStateFlow(emptyPreferences())
    private val writes = Mutex()
    private var nextReadGate: StorageGate? = null
    private var nextWriteGate: StorageGate? = null
    var failNextRead = false
    var failNextWrite = false
    var writeCount: Int = 0
        private set

    override val data: Flow<Preferences> = flow {
        if (failNextRead) {
            failNextRead = false
            throw IOException("Simulated progress read failure")
        }
        val gate = nextReadGate
        nextReadGate = null
        gate?.awaitRelease()
        emitAll(preferences)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = writes.withLock {
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("Simulated progress write failure")
        }
        val gate = nextWriteGate
        nextWriteGate = null
        gate?.awaitRelease()
        transform(preferences.value).also {
            preferences.value = it
            writeCount += 1
        }
    }

    fun pauseNextRead() = StorageGate().also {
        check(nextReadGate == null)
        nextReadGate = it
    }

    fun pauseNextWrite() = StorageGate().also {
        check(nextWriteGate == null)
        nextWriteGate = it
    }
}

private class StorageGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun awaitRelease() {
        entered.complete(Unit)
        release.await()
    }
}
