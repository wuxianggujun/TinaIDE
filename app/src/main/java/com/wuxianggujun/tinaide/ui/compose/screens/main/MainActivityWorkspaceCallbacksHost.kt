package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.runtime.Composable
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.ui.MainActivityDialogCoordinator
import com.wuxianggujun.tinaide.ui.MainActivityWorkspaceActionsDelegate
import java.io.File

@Composable
internal fun rememberMainActivityWorkspaceCallbacksHost(
    mainScreenState: MainActivityMainScreenState,
    workspaceActions: MainActivityWorkspaceActionsDelegate,
    dialogCoordinator: MainActivityDialogCoordinator,
    toastInfo: (String) -> Unit,
    toastError: (String) -> Unit,
    onOpenWithExternalApp: (File) -> Unit,
    onShareFileOrDirectory: (File) -> Unit,
    onPersistRunConfigManager: (RunConfigurationManager) -> Boolean,
    onGitRefresh: () -> Unit,
): MainActivityWorkspaceCallbacks = rememberMainActivityWorkspaceCallbacks(
    onOpenSettings = workspaceActions::openSettings,
    toastInfo = toastInfo,
    toastError = toastError,
    onDuplicateFileOrDirectory = workspaceActions::duplicateFileOrDirectory,
    onDuplicateSuccess = workspaceActions::onDuplicateSuccess,
    onDuplicateFailure = workspaceActions::onDuplicateFailure,
    onOpenWithExternalApp = onOpenWithExternalApp,
    onShareFileOrDirectory = onShareFileOrDirectory,
    onProjectNotOpen = workspaceActions::onProjectNotOpen,
    onGitCommitSuccess = workspaceActions::onGitCommitSuccess,
    onGitInitSuccess = workspaceActions::onGitInitSuccess,
    onPersistRunConfigManager = onPersistRunConfigManager,
    onNoOpenFile = workspaceActions::onNoOpenFile,
    onUnsupportedEditor = workspaceActions::onUnsupportedEditor,
    onOpenBookmarks = workspaceActions::openBookmarksPanel,
    onOpenTerminal = workspaceActions::openProjectTerminal,
    onSaveFile = workspaceActions::saveFileForClose,
    onGitRefresh = onGitRefresh,
    onGitBranchSelect = workspaceActions::checkoutGitBranch,
    onGitCommitClick = mainScreenState.gitDialogState::openCommitDetail,
    onRequestUnsavedExitConfirm = dialogCoordinator::requestUnsavedExitConfirm,
)
