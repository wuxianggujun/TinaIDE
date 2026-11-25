package com.wuxianggujun.tinaide.tutorial

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
import com.wuxianggujun.tinaide.tutorial.data.TutorialCourseStep
import com.wuxianggujun.tinaide.tutorial.data.TutorialStep
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import com.wuxianggujun.tinaide.tutorial.data.TutorialWithProgress
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.tutorial.repository.TutorialRepository
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectBlueprints
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectPreparer
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 教程 ViewModel
 */
class TutorialViewModel internal constructor(
    application: Application,
    private val progressStore: TutorialProgressStore,
    private val projectPreparer: TutorialProjectPreparer = TutorialProjectRepository(application),
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, TutorialProgressStore(application))

    private val repository = TutorialRepository(application, progressStore)
    private val progressOperations = Mutex()
    private val pendingProgressOperations = ArrayDeque<suspend () -> Unit>()
    private var progressOperationsPaused = false
    private val catalogReload = MutableStateFlow(0L)
    private var tutorialSessionId = 0L
    private var courseProgressRevision = 0L
    private var completingSessionId: Long? = null
    private var onboardingPromptHandled = false
    private var courseProjectJob: Job? = null
    private var courseProjectRequestId = 0L

    // UI 状态
    private val _uiState = MutableStateFlow(TutorialUiState())
    val uiState: StateFlow<TutorialUiState> = _uiState.asStateFlow()

    /**
     * 教程目录加载状态。
     *
     * 使用显式 Loading 状态，避免 StateFlow 的空初值在首帧被 UI 误判为“没有教程”。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tutorialCatalogState: StateFlow<TutorialCatalogUiState> =
        catalogReload.flatMapLatest {
            repository.getAllTutorialsWithProgress()
                .map(::buildTutorialCatalogUiState)
                .onStart { emit(TutorialCatalogUiState()) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    Log.w("TutorialViewModel", "Failed to read tutorial catalogue progress", error)
                    emit(TutorialCatalogUiState(isLoading = false, hasLoadError = true))
                }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = TutorialCatalogUiState()
            )

    // Spotlight 引导状态
    private val _spotlightState = MutableStateFlow(SpotlightUiState())
    val spotlightState: StateFlow<SpotlightUiState> = _spotlightState.asStateFlow()

    init {
        checkOnboardingStatus()
    }

    /**
     * 检查新手引导状态
     */
    private fun checkOnboardingStatus() {
        launchProgressOperation {
            val shouldShow = progressStore.shouldShowOnboarding()
            if (shouldShow && !onboardingPromptHandled) {
                _uiState.update { it.copy(showOnboardingPrompt = true) }
            }
        }
    }

    /**
     * 开始新手引导
     */
    fun startOnboarding() {
        startTutorial(repository.getOnboardingTutorial())
    }

    /**
     * 跳过新手引导
     */
    fun skipOnboarding() {
        dismissOnboardingPrompt()
        dismissSpotlight()
        launchProgressOperation {
            progressStore.setOnboardingSkipped(true)
        }
    }

    /**
     * 稍后再说（关闭提示但不标记为跳过）
     */
    fun dismissOnboardingPrompt() {
        onboardingPromptHandled = true
        _uiState.update { it.copy(showOnboardingPrompt = false) }
    }

    /**
     * 开始教程
     */
    fun startTutorial(tutorial: Tutorial) {
        val sessionId = resetTutorialSession()
        launchProgressOperation {
            if (sessionId != tutorialSessionId) return@launchProgressOperation
            progressStore.startTutorial(tutorial.id)
            restoreTutorial(tutorial, sessionId, requestedStepIndex = 0)
        }
    }

    /**
     * 继续教程
     */
    fun reloadSelectedCourseProgress() {
        val state = _uiState.value
        val selected = state.selectedTutorial ?: return
        if (selected.type != TutorialType.GUIDED || state.hasProgressError) return
        val sessionId = tutorialSessionId
        val revision = courseProgressRevision
        // Resuming the same reader (including rotation) must preserve its pending project request.
        launchProgressOperation { restoreTutorial(selected, sessionId, expectedProgressRevision = revision) }
    }

    fun continueTutorial(tutorial: Tutorial) {
        val sessionId = resetTutorialSession()
        launchProgressOperation {
            restoreTutorial(tutorial, sessionId)
        }
    }

    /**
     * 根据 Markdown 链接目标打开教程。
     *
     * @return `true` 表示已在教程体系内处理。
     */
    fun openTutorialByLinkTarget(linkTarget: String): Boolean {
        val tutorial = repository.resolveTutorialByLinkTarget(linkTarget) ?: return false
        val targetStepIndex = repository.resolveCourseStepIndexByLinkTarget(linkTarget) ?: return false

        val sessionId = resetTutorialSession()
        launchProgressOperation {
            if (sessionId != tutorialSessionId) return@launchProgressOperation
            progressStore.startTutorial(tutorial.id)
            restoreTutorial(tutorial, sessionId, requestedStepIndex = targetStepIndex)
        }

        return true
    }

    /** 在当前完整课程中打开 Markdown 指向的下一章，并保存章节进度。 */
    fun openCourseStepByLinkTarget(tutorial: Tutorial, linkTarget: String): Boolean {
        if (tutorial.id != TutorialRepository.CPP_PRACTICE_COURSE_ID || !isSelectedGuidedTutorial(tutorial.id)) {
            return false
        }
        val stepIndex = repository.resolveCourseStepIndexByLinkTarget(linkTarget) ?: return false
        val unlockedIndex = firstUnlockedCourseStepIndex(tutorial.courseSteps, _uiState.value.completedCourseStepIds)
        if (stepIndex > unlockedIndex) return true
        updateCourseStep(tutorial.id, stepIndex)
        return true
    }

    fun setCourseStepCompleted(tutorialId: String, stepId: String, completed: Boolean) {
        if (!isSelectedGuidedTutorial(tutorialId)) return
        val selectedTutorial = _uiState.value.selectedTutorial ?: return
        val progress = updateCourseStepCompletion(
            courseSteps = selectedTutorial.courseSteps,
            completedStepIds = _uiState.value.completedCourseStepIds,
            currentStepIndex = _uiState.value.currentCourseStepIndex,
            stepId = stepId,
            completed = completed,
        )
        if (progress.completedStepIds == _uiState.value.completedCourseStepIds &&
            progress.currentStepIndex == _uiState.value.currentCourseStepIndex
        ) return
        if (progress.currentStepIndex != _uiState.value.currentCourseStepIndex) invalidateCourseProjectRequest()
        courseProgressRevision += 1
        _uiState.update {
            it.copy(
                completedCourseStepIds = progress.completedStepIds,
                currentCourseStepIndex = progress.currentStepIndex,
            )
        }
        launchProgressOperation {
            progressStore.updateCourseProgress(
                tutorialId = tutorialId,
                completedStepIds = progress.completedStepIds,
                currentStepIndex = progress.currentStepIndex,
            )
        }
    }

    /**
     * Spotlight: 下一步
     */
    fun nextStep() {
        val current = _spotlightState.value
        if (!current.isVisible || completingSessionId == tutorialSessionId) return
        if (current.currentStepIndex < current.steps.size - 1) {
            val newIndex = current.currentStepIndex + 1
            _spotlightState.update { it.copy(currentStepIndex = newIndex) }

            // 保存进度
            launchProgressOperation {
                current.currentTutorial?.let { tutorial ->
                    progressStore.updateStepProgress(tutorial.id, newIndex)
                }
            }
        }
    }

    /**
     * Spotlight: 上一步
     */
    fun previousStep() {
        val current = _spotlightState.value
        if (!current.isVisible || completingSessionId == tutorialSessionId) return
        if (current.currentStepIndex > 0) {
            val newIndex = current.currentStepIndex - 1
            _spotlightState.update { it.copy(currentStepIndex = newIndex) }

            launchProgressOperation {
                current.currentTutorial?.let { tutorial ->
                    progressStore.updateStepProgress(tutorial.id, newIndex)
                }
            }
        }
    }

    /**
     * Spotlight: 完成教程
     */
    fun completeSpotlight() {
        val current = _spotlightState.value
        if (!current.isVisible || completingSessionId == tutorialSessionId) return
        val tutorial = current.currentTutorial?.takeIf { current.isLastStep } ?: return
        val sessionId = tutorialSessionId
        completingSessionId = sessionId
        launchProgressOperation {
            try {
                val isOnboarding = tutorial.id == TutorialRepository.ONBOARDING_TUTORIAL_ID
                if (isOnboarding) {
                    progressStore.completeOnboardingTutorial(tutorial.id)
                } else {
                    progressStore.completeTutorial(tutorial.id)
                }
                if (sessionId == tutorialSessionId) {
                    dismissSpotlight()
                    _uiState.update { it.copy(showOnboardingCompleteDialog = isOnboarding) }
                }
            } finally {
                if (completingSessionId == sessionId) completingSessionId = null
            }
        }
    }

    /**
     * Spotlight: 跳过
     */
    fun skipSpotlight() {
        val current = _spotlightState.value
        if (!current.isVisible || completingSessionId == tutorialSessionId) return
        dismissSpotlight()
        if (current.currentTutorial?.id == TutorialRepository.ONBOARDING_TUTORIAL_ID) {
            launchProgressOperation {
                progressStore.setOnboardingSkipped(true)
            }
        }
    }

    /**
     * 关闭 Spotlight
     */
    fun dismissSpotlight() {
        resetTutorialSession()
    }

    /**
     * 关闭教程内容页面
     */
    fun closeTutorialContent() {
        resetTutorialSession()
    }

    /**
     * 完成当前实操课程
     */
    fun completeTutorial(tutorialId: String) {
        val current = _uiState.value
        if (completingSessionId == tutorialSessionId) return
        val tutorial = current.selectedTutorial
            ?.takeIf { current.showTutorialContent && it.id == tutorialId }
            ?: return
        if (tutorial.type == TutorialType.GUIDED &&
            tutorial.courseSteps.any { it.id !in current.completedCourseStepIds }
        ) return

        // Validate the clicked session before suspending; a later page has its own completion set.
        val sessionId = tutorialSessionId
        completingSessionId = sessionId
        launchProgressOperation {
            try {
                progressStore.completeTutorial(tutorialId)
                if (sessionId == tutorialSessionId) closeTutorialContent()
            } finally {
                if (completingSessionId == sessionId) completingSessionId = null
            }
        }
    }

    /** 前进到下一阶段并持久化进度，方便离开后继续。 */
    fun nextCourseStep(tutorial: Tutorial) {
        if (!isSelectedGuidedTutorial(tutorial.id)) return
        val currentIndex = _uiState.value.currentCourseStepIndex
        val currentStep = tutorial.courseSteps.getOrNull(currentIndex) ?: return
        if (currentStep.id !in _uiState.value.completedCourseStepIds) return
        val nextIndex = (currentIndex + 1).coerceAtMost(tutorial.courseSteps.lastIndex)
        if (nextIndex <= currentIndex) return
        updateCourseStep(tutorial.id, nextIndex)
    }

    /** 返回上一阶段。 */
    fun previousCourseStep(tutorial: Tutorial) {
        if (!isSelectedGuidedTutorial(tutorial.id)) return
        val currentIndex = _uiState.value.currentCourseStepIndex
        val previousIndex = (currentIndex - 1).coerceAtLeast(0)
        if (previousIndex >= currentIndex) return
        updateCourseStep(tutorial.id, previousIndex)
    }

    /** 打开目录中的已解锁章节；课程进度仍按现有章节确认状态保存。 */
    fun selectCourseStep(tutorial: Tutorial, stepIndex: Int) {
        if (tutorial.type != TutorialType.GUIDED || !isSelectedGuidedTutorial(tutorial.id)) return
        if (stepIndex !in tutorial.courseSteps.indices) return

        val unlockedIndex = firstUnlockedCourseStepIndex(
            tutorial.courseSteps,
            _uiState.value.completedCourseStepIds,
        )
        if (stepIndex > unlockedIndex) return

        updateCourseStep(tutorial.id, stepIndex)
    }

    private fun updateCourseStep(tutorialId: String, stepIndex: Int) {
        if (_uiState.value.currentCourseStepIndex == stepIndex) return
        courseProgressRevision += 1
        invalidateCourseProjectRequest()
        _uiState.update { it.copy(currentCourseStepIndex = stepIndex) }
        launchProgressOperation {
            progressStore.updateStepProgress(tutorialId, stepIndex)
        }
    }

    private fun isSelectedGuidedTutorial(tutorialId: String): Boolean =
        _uiState.value.let { state ->
            completingSessionId != tutorialSessionId && state.showTutorialContent &&
                state.selectedTutorial?.let { it.id == tutorialId && it.type == TutorialType.GUIDED } == true
        }

    /** Preparing starter files is independent of confirming or verifying the lesson. */
    fun prepareCourseProject(stepId: String, language: String) {
        val courseId = TutorialRepository.CPP_PRACTICE_COURSE_ID
        if (!isCurrentCourseProjectStep(stepId) || _uiState.value.hasProgressError ||
            !TutorialProjectBlueprints.supports(stepId) || courseProjectJob?.isActive == true ||
            _uiState.value.courseProject is TutorialProjectUiState.Ready
        ) return
        val sessionId = tutorialSessionId
        val requestId = ++courseProjectRequestId
        val preparing = TutorialProjectUiState.Preparing(requestId, stepId)
        _uiState.update { it.copy(courseProject = preparing) }
        courseProjectJob = viewModelScope.launch {
            try {
                val project = projectPreparer.prepare(stepId, language)
                if (sessionId == tutorialSessionId && isSelectedGuidedTutorial(courseId) &&
                    isCurrentCourseProjectStep(stepId) && _uiState.value.courseProject == preparing
                ) {
                    _uiState.update {
                        it.copy(courseProject = TutorialProjectUiState.Ready(requestId, stepId, project.absolutePath))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("TutorialViewModel", "Failed to prepare course project: $stepId", error)
                if (sessionId == tutorialSessionId && _uiState.value.courseProject == preparing) {
                    _uiState.update { it.copy(courseProject = TutorialProjectUiState.Error(stepId)) }
                }
            } finally {
                if (_uiState.value.courseProject == preparing) {
                    _uiState.update { it.copy(courseProject = TutorialProjectUiState.Idle) }
                }
            }
        }
    }

    /** Consume only the active reader's request, immediately before the host opens the editor. */
    fun consumeCourseProjectRequest(request: TutorialProjectUiState.Ready): String? {
        if (_uiState.value.courseProject != request || !isCurrentCourseProjectStep(request.stepId)) return null
        _uiState.update { it.copy(courseProject = TutorialProjectUiState.Idle) }
        return request.projectPath
    }

    fun courseProjectOpenFailed(request: TutorialProjectUiState.Ready) {
        val projectState = _uiState.value.courseProject
        if (request.requestId == courseProjectRequestId &&
            (projectState == request || projectState == TutorialProjectUiState.Idle) &&
            isCurrentCourseProjectStep(request.stepId)
        ) {
            _uiState.update { it.copy(courseProject = TutorialProjectUiState.Error(request.stepId)) }
        }
    }

    private fun isCurrentCourseProjectStep(stepId: String): Boolean =
        isSelectedGuidedTutorial(TutorialRepository.CPP_PRACTICE_COURSE_ID) &&
            _uiState.value.let { it.selectedTutorial?.courseSteps?.getOrNull(it.currentCourseStepIndex)?.id == stepId }

    private fun invalidateCourseProjectRequest() {
        courseProjectRequestId += 1
        courseProjectJob?.cancel()
        courseProjectJob = null
        _uiState.update { it.copy(courseProject = TutorialProjectUiState.Idle) }
    }

    /**
     * 展开/收起分类
     */
    fun toggleCategory(category: TutorialCategory) {
        _uiState.update { state ->
            val expanded = state.expandedCategories.toMutableSet()
            if (expanded.contains(category)) {
                expanded.remove(category)
            } else {
                expanded.add(category)
            }
            state.copy(expandedCategories = expanded)
        }
    }

    /**
     * 关闭新手引导完成对话框
     */
    fun dismissOnboardingCompleteDialog() {
        _uiState.update { it.copy(showOnboardingCompleteDialog = false) }
    }

    /**
     * 跳转到教程页面（从完成对话框）
     */
    fun navigateToTutorialTab(): Int {
        _uiState.update { it.copy(showOnboardingCompleteDialog = false) }
        // 返回教程 Tab 的索引（第 3 个，索引为 2）
        return 2
    }

    /** Register operations in click order so a reopen reads all preceding progress writes. */
    private fun launchProgressOperation(operation: suspend () -> Unit) {
        pendingProgressOperations.addLast(operation)
        drainProgressOperations()
    }

    /** Keep the failed operation ahead of later writes; retry must never reorder user intent. */
    private fun drainProgressOperations() {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            progressOperations.withLock {
                if (progressOperationsPaused) return@withLock
                try {
                    while (pendingProgressOperations.isNotEmpty()) {
                        try {
                            pendingProgressOperations.first().invoke()
                            pendingProgressOperations.removeFirst()
                        } catch (cancelled: CancellationException) {
                            pendingProgressOperations.removeFirst()
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("TutorialViewModel", "Failed to persist or restore tutorial progress", error)
                            progressOperationsPaused = true
                            _uiState.update { it.copy(hasProgressError = true) }
                            return@withLock
                        }
                    }
                    _uiState.update { it.copy(hasProgressError = false) }
                } finally {
                    _uiState.update { it.copy(isRetryingProgress = false) }
                }
            }
        }
    }

    fun retryProgressOperation() {
        if (!progressOperationsPaused) return
        progressOperationsPaused = false
        _uiState.update { it.copy(isRetryingProgress = true) }
        drainProgressOperations()
        retryCatalogLoading()
    }

    fun retryCatalogLoading() {
        catalogReload.update { it + 1 }
    }

    /** Opening and closing both invalidate pending UI callbacks, even for the same tutorial ID. */
    private fun resetTutorialSession(): Long {
        tutorialSessionId += 1
        invalidateCourseProjectRequest()
        completingSessionId = null
        onboardingPromptHandled = true
        _spotlightState.value = SpotlightUiState()
        _uiState.update {
            it.copy(
                showOnboardingPrompt = false,
                showOnboardingCompleteDialog = false,
                selectedTutorial = null,
                showTutorialContent = false,
                currentCourseStepIndex = 0,
                completedCourseStepIds = emptySet(),
            )
        }
        return tutorialSessionId
    }

    private suspend fun restoreTutorial(
        tutorial: Tutorial,
        sessionId: Long,
        requestedStepIndex: Int? = null,
        expectedProgressRevision: Long? = null,
    ) {
        fun isCurrentRequest() = sessionId == tutorialSessionId &&
            (expectedProgressRevision == null || expectedProgressRevision == courseProgressRevision)
        if (!isCurrentRequest()) return
        val progress = progressStore.getProgress(tutorial.id)
        if (!isCurrentRequest()) return
        val completedStepIds = if (tutorial.type == TutorialType.GUIDED) {
            normalizeCompletedCourseStepIds(tutorial.courseSteps, progress?.completedStepIds.orEmpty())
        } else {
            progress?.completedStepIds.orEmpty()
        }
        val requestedIndex = requestedStepIndex ?: progress?.currentStepIndex ?: 0
        val stepIndex = when (tutorial.type) {
            TutorialType.GUIDED -> normalizeTutorialStepIndex(requestedIndex, tutorial.courseSteps.size)
                .coerceAtMost(firstUnlockedCourseStepIndex(tutorial.courseSteps, completedStepIds))
            TutorialType.INTERACTIVE -> normalizeTutorialStepIndex(requestedIndex, tutorial.steps.size)
        }
        if (progress == null || progress.status == ProgressStatus.NOT_STARTED) {
            progressStore.startTutorial(tutorial.id)
        }
        if (tutorial.type == TutorialType.GUIDED) {
            if (progress?.currentStepIndex != stepIndex || progress?.completedStepIds != completedStepIds) {
                progressStore.updateCourseProgress(tutorial.id, completedStepIds, stepIndex)
            }
        } else if (progress?.currentStepIndex != stepIndex) {
            progressStore.updateStepProgress(tutorial.id, stepIndex)
        }
        if (isCurrentRequest()) {
            if (_uiState.value.currentCourseStepIndex != stepIndex) invalidateCourseProjectRequest()
            openTutorial(tutorial, stepIndex, completedStepIds)
        }
    }

    private fun openTutorial(
        tutorial: Tutorial,
        stepIndex: Int,
        completedStepIds: Set<String>,
    ) {
        when (tutorial.type) {
            TutorialType.INTERACTIVE -> {
                _uiState.update {
                    it.copy(
                        selectedTutorial = null,
                        showTutorialContent = false
                    )
                }
                _spotlightState.update {
                    SpotlightUiState(
                        isVisible = true,
                        currentTutorial = tutorial,
                        steps = tutorial.steps,
                        currentStepIndex = stepIndex
                    )
                }
            }

            TutorialType.GUIDED -> {
                _spotlightState.value = SpotlightUiState()
                _uiState.update {
                    it.copy(
                        selectedTutorial = tutorial,
                        showTutorialContent = true,
                        currentCourseStepIndex = stepIndex,
                        completedCourseStepIds = completedStepIds,
                    )
                }
            }

        }
    }
}

/**
 * 教程页面 UI 状态
 */
data class TutorialUiState(
    val hasProgressError: Boolean = false,
    val isRetryingProgress: Boolean = false,
    val showOnboardingPrompt: Boolean = false,
    val showOnboardingCompleteDialog: Boolean = false,
    val selectedTutorial: Tutorial? = null,
    val showTutorialContent: Boolean = false,
    val currentCourseStepIndex: Int = 0,
    val completedCourseStepIds: Set<String> = emptySet(),
    val expandedCategories: Set<TutorialCategory> = TutorialCategory.entries.toSet(),
    val courseProject: TutorialProjectUiState = TutorialProjectUiState.Idle,
)

sealed interface TutorialProjectUiState {
    data object Idle : TutorialProjectUiState
    data class Preparing(val requestId: Long, val stepId: String) : TutorialProjectUiState
    data class Ready(val requestId: Long, val stepId: String, val projectPath: String) : TutorialProjectUiState
    data class Error(val stepId: String) : TutorialProjectUiState
}

/**
 * 教程目录的显式加载状态。
 */
data class TutorialCatalogUiState(
    val isLoading: Boolean = true,
    val hasLoadError: Boolean = false,
    val tutorials: List<TutorialWithProgress> = emptyList(),
    val tutorialsByCategory: Map<TutorialCategory, List<TutorialWithProgress>> = emptyMap(),
)

internal fun buildTutorialCatalogUiState(
    tutorials: List<TutorialWithProgress>,
): TutorialCatalogUiState = TutorialCatalogUiState(
    isLoading = false,
    tutorials = tutorials,
    tutorialsByCategory = tutorials
        .groupBy { it.tutorial.category }
        .mapValues { (_, list) -> list.sortedBy { it.tutorial.order } }
        .toSortedMap(compareBy { it.order }),
)

internal fun normalizeTutorialStepIndex(requestedIndex: Int, stepCount: Int): Int =
    if (stepCount <= 0) 0 else requestedIndex.coerceIn(0, stepCount - 1)

internal fun normalizeCompletedCourseStepIds(
    courseSteps: List<TutorialCourseStep>,
    completedStepIds: Set<String>,
): Set<String> = courseSteps
    .asSequence()
    .takeWhile { it.id in completedStepIds }
    .map { it.id }
    .toSet()

/** Future stages stay locked until every earlier stage has been confirmed. */
fun firstUnlockedCourseStepIndex(
    courseSteps: List<TutorialCourseStep>,
    completedStepIds: Set<String>,
): Int {
    if (courseSteps.isEmpty()) return 0
    return courseSteps.indexOfFirst { it.id !in completedStepIds }
        .takeIf { it >= 0 }
        ?: courseSteps.lastIndex
}

/**
 * 计算章节确认变化后的有效课程进度。
 * 撤销某一章会让该章之后的完成状态失效，并将位置回退到首个未完成章节。
 */
fun updateCourseStepCompletion(
    courseSteps: List<TutorialCourseStep>,
    completedStepIds: Set<String>,
    currentStepIndex: Int,
    stepId: String,
    completed: Boolean,
): CourseStepCompletionUpdate {
    val validCompletedIds = normalizeCompletedCourseStepIds(courseSteps, completedStepIds)
    val currentlyUnlockedIndex = firstUnlockedCourseStepIndex(courseSteps, validCompletedIds)
    val safeCurrentIndex = normalizeTutorialStepIndex(currentStepIndex, courseSteps.size)
        .coerceAtMost(currentlyUnlockedIndex)
    val unchangedProgress = CourseStepCompletionUpdate(validCompletedIds, safeCurrentIndex)
    val targetIndex = courseSteps.indexOfFirst { it.id == stepId }
    if (targetIndex < 0) return unchangedProgress

    if (completed && targetIndex > currentlyUnlockedIndex) {
        return unchangedProgress
    }

    val updatedCompletedIds = validCompletedIds.toMutableSet()
    if (completed) {
        updatedCompletedIds.add(stepId)
    } else {
        courseSteps.drop(targetIndex).forEach { step -> updatedCompletedIds.remove(step.id) }
    }

    val nextStepIndex = if (completed) {
        safeCurrentIndex
    } else {
        safeCurrentIndex.coerceAtMost(firstUnlockedCourseStepIndex(courseSteps, updatedCompletedIds))
    }
    return CourseStepCompletionUpdate(updatedCompletedIds, nextStepIndex)
}

data class CourseStepCompletionUpdate(
    val completedStepIds: Set<String>,
    val currentStepIndex: Int,
)

/**
 * Spotlight 引导 UI 状态
 */
data class SpotlightUiState(
    val isVisible: Boolean = false,
    val currentTutorial: Tutorial? = null,
    val steps: List<TutorialStep> = emptyList(),
    val currentStepIndex: Int = 0
) {
    val currentStep: TutorialStep?
        get() = steps.getOrNull(currentStepIndex)

    val isFirstStep: Boolean
        get() = currentStepIndex == 0

    val isLastStep: Boolean
        get() = currentStepIndex == steps.size - 1

    val progress: Float
        get() = if (steps.isEmpty()) 0f else (currentStepIndex + 1).toFloat() / steps.size
}
