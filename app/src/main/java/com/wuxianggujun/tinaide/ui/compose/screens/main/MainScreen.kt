package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.tutorial.TutorialViewModel
import com.wuxianggujun.tinaide.tutorial.spotlight.LocalSpotlightRegistry
import com.wuxianggujun.tinaide.tutorial.spotlight.SpotlightOverlay
import com.wuxianggujun.tinaide.tutorial.spotlight.SpotlightTargets
import com.wuxianggujun.tinaide.tutorial.spotlight.rememberSpotlightRegistry
import com.wuxianggujun.tinaide.ui.compose.components.MainBottomNavItems
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBottomBar
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.screens.main.market.MarketScreen
import com.wuxianggujun.tinaide.ui.compose.screens.main.profile.ProfileScreen
import com.wuxianggujun.tinaide.ui.compose.screens.main.project.ProjectScreen
import com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial.TutorialScreen

/**
 * TinaIDE 主屏幕容器
 *
 * 负责管理底部导航和切换各个主要模块
 *
 * 底部导航：
 * - 项目：项目管理、项目列表
 * - 市场：插件市场、包管理、代码片段
 * - 教程：教程列表、学习进度
 * - 我的：用户信息、设置、我的内容
 */
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onOpenProject: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToPackages: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToDownloadHistory: () -> Unit = {},
) {
    var selectedNavIndex by rememberSaveable { mutableIntStateOf(0) }
    val saveableStateHolder = rememberSaveableStateHolder()

    // Spotlight 注册表
    val spotlightRegistry = rememberSpotlightRegistry()

    // Tutorial ViewModel
    val tutorialViewModel: TutorialViewModel = viewModel()
    val tutorialUiState by tutorialViewModel.uiState.collectAsState()
    val spotlightState by tutorialViewModel.spotlightState.collectAsState()

    fun navigateToSpotlightTarget(targetId: String) {
        selectedNavIndex = when (targetId) {
            SpotlightTargets.BOTTOM_NAV_PROJECT,
            SpotlightTargets.FAB_PROJECT_ACTIONS,
            SpotlightTargets.FAB_MENU_NEW_PROJECT -> 0
            SpotlightTargets.BOTTOM_NAV_MARKET -> 1
            SpotlightTargets.BOTTOM_NAV_TUTORIAL -> 2
            SpotlightTargets.BOTTOM_NAV_PROFILE -> 3
            else -> selectedNavIndex
        }
    }

    // 引导过程中保证底栏可见、并把页面切到“目标所属 Tab”，避免目标不可见导致的引导失败
    LaunchedEffect(
        spotlightState.isVisible,
        spotlightState.currentStep?.targetId
    ) {
        if (!spotlightState.isVisible) return@LaunchedEffect
        val targetId = spotlightState.currentStep?.targetId ?: return@LaunchedEffect
        navigateToSpotlightTarget(targetId)
    }

    // 提供 Spotlight 注册表给子组件
    CompositionLocalProvider(LocalSpotlightRegistry provides spotlightRegistry) {
        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                // 主内容区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    // 让各个 Tab 的 rememberSaveable 状态在切换时也能被保留，避免回到「项目」时重复触发刷新
                    val tabKey = MainBottomNavItems.getOrNull(selectedNavIndex)?.route ?: "tab_$selectedNavIndex"
                    saveableStateHolder.SaveableStateProvider(key = tabKey) {
                        when (selectedNavIndex) {
                            0 -> ProjectScreen(
                                onNewProjectClick = { onOpenProject("") },
                                onProjectClick = onOpenProject,
                                spotlightUiState = spotlightState
                            )
                            1 -> MarketScreen()
                            2 -> TutorialScreen(viewModel = tutorialViewModel)
                            3 -> ProfileScreen(
                                onNavigateToSettings = onNavigateToSettings,
                                onNavigateToAbout = onNavigateToAbout,
                                onNavigateToPlugins = onNavigateToPlugins,
                                onNavigateToPackages = onNavigateToPackages,
                                onNavigateToFavorites = onNavigateToFavorites,
                                onNavigateToDownloadHistory = onNavigateToDownloadHistory,
                            )
                        }
                    }
                }

                // 底部导航栏
                TinaBottomBar(
                    items = MainBottomNavItems,
                    selectedIndex = selectedNavIndex,
                    onItemSelected = { selectedNavIndex = it }
                )
            }

            // Spotlight 遮罩引导
            SpotlightOverlay(
                visible = spotlightState.isVisible,
                steps = spotlightState.steps,
                currentStepIndex = spotlightState.currentStepIndex,
                onNext = { tutorialViewModel.nextStep() },
                onPrevious = { tutorialViewModel.previousStep() },
                onSkip = { tutorialViewModel.skipSpotlight() },
                onComplete = { tutorialViewModel.completeSpotlight() },
                onRequestNavigateToTarget = { navigateToSpotlightTarget(it) }
            )
        }

        // 新手引导提示对话框
        if (tutorialUiState.showOnboardingPrompt) {
            OnboardingPromptDialog(
                onStartOnboarding = {
                    navigateToSpotlightTarget(SpotlightTargets.BOTTOM_NAV_PROJECT)
                    tutorialViewModel.startOnboarding()
                },
                onLater = { tutorialViewModel.dismissOnboardingPrompt() },
                onSkip = { tutorialViewModel.skipOnboarding() }
            )
        }

        // 新手引导完成对话框
        if (tutorialUiState.showOnboardingCompleteDialog) {
            OnboardingCompleteDialog(
                onGoToTutorial = {
                    selectedNavIndex = tutorialViewModel.navigateToTutorialTab()
                },
                onDismiss = { tutorialViewModel.dismissOnboardingCompleteDialog() }
            )
        }
    }
}

/**
 * 新手引导提示对话框
 */
@Composable
private fun OnboardingPromptDialog(
    onStartOnboarding: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onLater,
        title = { TinaDialogTitleText(stringResource(R.string.onboarding_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(R.string.onboarding_dialog_message)
                )
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(R.string.onboarding_dialog_start),
                onClick = onStartOnboarding
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinaTextButton(
                    text = stringResource(R.string.onboarding_dialog_skip),
                    onClick = onSkip
                )
                TinaTextButton(
                    text = stringResource(R.string.onboarding_dialog_later),
                    onClick = onLater
                )
            }
        }
    )
}

/**
 * 新手引导完成对话框
 */
@Composable
private fun OnboardingCompleteDialog(
    onGoToTutorial: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(R.string.onboarding_complete_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(R.string.onboarding_complete_message)
                )
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(R.string.onboarding_complete_go_tutorial),
                onClick = onGoToTutorial
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(R.string.onboarding_complete_dismiss),
                onClick = onDismiss
            )
        }
    )
}
