package com.wuxianggujun.tinaide.tutorial

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
import com.wuxianggujun.tinaide.tutorial.data.TutorialStep
import com.wuxianggujun.tinaide.tutorial.data.TutorialWithProgress
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.tutorial.repository.TutorialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 教程 ViewModel
 */
class TutorialViewModel(application: Application) : AndroidViewModel(application) {

    private val progressStore = TutorialProgressStore(application)
    private val repository = TutorialRepository(application, progressStore)

    // UI 状态
    private val _uiState = MutableStateFlow(TutorialUiState())
    val uiState: StateFlow<TutorialUiState> = _uiState.asStateFlow()

    /**
     * 教程目录加载状态。
     *
     * 使用显式 Loading 状态，避免 StateFlow 的空初值在首帧被 UI 误判为“没有教程”。
     */
    val tutorialCatalogState: StateFlow<TutorialCatalogUiState> =
        repository.getAllTutorialsWithProgress()
            .map(::buildTutorialCatalogUiState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = TutorialCatalogUiState()
            )

    // 所有教程（带进度），保留现有消费入口。
    val tutorialsWithProgress: StateFlow<List<TutorialWithProgress>> =
        tutorialCatalogState
            .map { state -> state.tutorials }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    // 按分类分组的教程
    val tutorialsByCategory: StateFlow<Map<TutorialCategory, List<TutorialWithProgress>>> =
        tutorialCatalogState
            .map { state -> state.tutorialsByCategory }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap()
            )

    // 是否应该显示新手引导
    val shouldShowOnboarding: StateFlow<Boolean> =
        progressStore.hasCompletedOnboardingFlow
            .combine(progressStore.hasSkippedOnboardingFlow) { completed, skipped ->
                !completed && !skipped
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
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
        viewModelScope.launch {
            val shouldShow = progressStore.shouldShowOnboarding()
            if (shouldShow) {
                _uiState.update { it.copy(showOnboardingPrompt = true) }
            }
        }
    }

    /**
     * 开始新手引导
     */
    fun startOnboarding() {
        viewModelScope.launch {
            val tutorial = repository.getOnboardingTutorial()
            progressStore.startTutorial(tutorial.id)
            _spotlightState.update {
                SpotlightUiState(
                    isVisible = true,
                    currentTutorial = tutorial,
                    steps = tutorial.steps,
                    currentStepIndex = 0
                )
            }
            _uiState.update { it.copy(showOnboardingPrompt = false) }
        }
    }

    /**
     * 跳过新手引导
     */
    fun skipOnboarding() {
        viewModelScope.launch {
            progressStore.setOnboardingSkipped(true)
            _uiState.update { it.copy(showOnboardingPrompt = false) }
            dismissSpotlight()
        }
    }

    /**
     * 稍后再说（关闭提示但不标记为跳过）
     */
    fun dismissOnboardingPrompt() {
        _uiState.update { it.copy(showOnboardingPrompt = false) }
    }

    /**
     * 开始教程
     */
    fun startTutorial(tutorial: Tutorial) {
        viewModelScope.launch {
            progressStore.startTutorial(tutorial.id)
            openTutorial(tutorial)
        }
    }

    /**
     * 继续教程
     */
    fun continueTutorial(tutorial: Tutorial) {
        viewModelScope.launch {
            val progress = progressStore.getProgress(tutorial.id)
            val stepIndex = progress?.currentStepIndex ?: 0
            openTutorial(tutorial, stepIndex)
        }
    }

    /**
     * 根据 Markdown 链接目标打开教程。
     *
     * @return `true` 表示已在教程体系内处理。
     */
    fun openTutorialByLinkTarget(linkTarget: String): Boolean {
        val tutorial = repository.resolveTutorialByLinkTarget(linkTarget) ?: return false

        viewModelScope.launch {
            val progress = progressStore.getProgress(tutorial.id)
            if (progress?.status == ProgressStatus.IN_PROGRESS) {
                openTutorial(tutorial, progress.currentStepIndex)
            } else {
                progressStore.startTutorial(tutorial.id)
                openTutorial(tutorial)
            }
        }

        return true
    }

    fun resolveTutorialByLinkTarget(linkTarget: String): Tutorial? = repository.resolveTutorialByLinkTarget(linkTarget)

    /**
     * Spotlight: 下一步
     */
    fun nextStep() {
        val current = _spotlightState.value
        if (current.currentStepIndex < current.steps.size - 1) {
            val newIndex = current.currentStepIndex + 1
            _spotlightState.update { it.copy(currentStepIndex = newIndex) }

            // 保存进度
            viewModelScope.launch {
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
        if (current.currentStepIndex > 0) {
            val newIndex = current.currentStepIndex - 1
            _spotlightState.update { it.copy(currentStepIndex = newIndex) }

            viewModelScope.launch {
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
        viewModelScope.launch {
            current.currentTutorial?.let { tutorial ->
                progressStore.completeTutorial(tutorial.id)

                // 如果是新手引导，标记为已完成并显示完成对话框
                if (tutorial.id == TutorialRepository.ONBOARDING_TUTORIAL_ID) {
                    progressStore.setOnboardingCompleted(true)
                    _uiState.update { it.copy(showOnboardingCompleteDialog = true) }
                }
            }
            dismissSpotlight()
        }
    }

    /**
     * Spotlight: 跳过
     */
    fun skipSpotlight() {
        val current = _spotlightState.value
        viewModelScope.launch {
            // 如果是新手引导，标记为已跳过
            if (current.currentTutorial?.id == TutorialRepository.ONBOARDING_TUTORIAL_ID) {
                progressStore.setOnboardingSkipped(true)
            }
            dismissSpotlight()
        }
    }

    /**
     * 关闭 Spotlight
     */
    fun dismissSpotlight() {
        _spotlightState.update { SpotlightUiState() }
    }

    /**
     * 关闭教程内容页面
     */
    fun closeTutorialContent() {
        _uiState.update {
            it.copy(
                selectedTutorial = null,
                showTutorialContent = false
            )
        }
    }

    /**
     * 完成图文教程
     */
    fun completeTutorial(tutorialId: String) {
        viewModelScope.launch {
            progressStore.completeTutorial(tutorialId)
            closeTutorialContent()
        }
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

    private fun openTutorial(tutorial: Tutorial, stepIndex: Int = 0) {
        when (tutorial.type) {
            com.wuxianggujun.tinaide.tutorial.data.TutorialType.INTERACTIVE -> {
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

            com.wuxianggujun.tinaide.tutorial.data.TutorialType.ARTICLE,
            com.wuxianggujun.tinaide.tutorial.data.TutorialType.VIDEO -> {
                dismissSpotlight()
                _uiState.update {
                    it.copy(
                        selectedTutorial = tutorial,
                        showTutorialContent = true
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
    val showOnboardingPrompt: Boolean = false,
    val showOnboardingCompleteDialog: Boolean = false,
    val selectedTutorial: Tutorial? = null,
    val showTutorialContent: Boolean = false,
    val expandedCategories: Set<TutorialCategory> = TutorialCategory.entries.toSet(),
    val error: String? = null
)

/**
 * 教程目录的显式加载状态。
 */
data class TutorialCatalogUiState(
    val isLoading: Boolean = true,
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
