package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.git.GitCommit
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.ui.BottomPanelController
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.CompileActionsHelper
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.commands.rememberMainActivityHostCommandExecutor
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import kotlinx.coroutines.CoroutineScope

internal class MainActivityScreenCallbacks(
    val onDuplicateFileOrDirectory: suspend (File) -> File?,
    val onDuplicateSuccess: (File) -> Unit,
    val onDuplicateFailure: () -> Unit,
    val onOpenWithExternalApp: (File) -> Unit,
    val onShareFileOrDirectory: (File) -> Unit,
    val onProjectNotOpen: () -> Unit,
    val onGitCommitSuccess: () -> Unit,
    val onGitInitSuccess: () -> Unit,
    val onPersistRunConfigManager: (RunConfigurationManager) -> Boolean,
    val onNoOpenFile: () -> Unit,
    val onUnsupportedEditor: () -> Unit,
    val onOpenBookmarks: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onSaveFile: (tabId: String, onComplete: () -> Unit) -> Unit,
    val onGitRefresh: () -> Unit,
    val onGitBranchSelect: (String) -> Unit,
    val onGitCommitClick: (GitCommit) -> Unit,
    val onRequestUnsavedExitConfirm: () -> Unit,
)

@Stable
internal class MainActivityWorkspaceCallbacks(
    val onOpenSettings: () -> Unit,
    val toastInfo: (String) -> Unit,
    val toastError: (String) -> Unit,
    val screenCallbacks: MainActivityScreenCallbacks,
)

@Composable
internal fun rememberMainActivityScreenCallbacks(
    onDuplicateFileOrDirectory: suspend (File) -> File?,
    onDuplicateSuccess: (File) -> Unit,
    onDuplicateFailure: () -> Unit,
    onOpenWithExternalApp: (File) -> Unit,
    onShareFileOrDirectory: (File) -> Unit,
    onProjectNotOpen: () -> Unit,
    onGitCommitSuccess: () -> Unit,
    onGitInitSuccess: () -> Unit,
    onPersistRunConfigManager: (RunConfigurationManager) -> Boolean,
    onNoOpenFile: () -> Unit,
    onUnsupportedEditor: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveFile: (tabId: String, onComplete: () -> Unit) -> Unit,
    onGitRefresh: () -> Unit,
    onGitBranchSelect: (String) -> Unit,
    onGitCommitClick: (GitCommit) -> Unit,
    onRequestUnsavedExitConfirm: () -> Unit,
): MainActivityScreenCallbacks = remember(
    onDuplicateFileOrDirectory,
    onDuplicateSuccess,
    onDuplicateFailure,
    onOpenWithExternalApp,
    onShareFileOrDirectory,
    onProjectNotOpen,
    onGitCommitSuccess,
    onGitInitSuccess,
    onPersistRunConfigManager,
    onNoOpenFile,
    onUnsupportedEditor,
    onOpenBookmarks,
    onOpenTerminal,
    onOpenSettings,
    onSaveFile,
    onGitRefresh,
    onGitBranchSelect,
    onGitCommitClick,
    onRequestUnsavedExitConfirm,
) {
    MainActivityScreenCallbacks(
        onDuplicateFileOrDirectory = onDuplicateFileOrDirectory,
        onDuplicateSuccess = onDuplicateSuccess,
        onDuplicateFailure = onDuplicateFailure,
        onOpenWithExternalApp = onOpenWithExternalApp,
        onShareFileOrDirectory = onShareFileOrDirectory,
        onProjectNotOpen = onProjectNotOpen,
        onGitCommitSuccess = onGitCommitSuccess,
        onGitInitSuccess = onGitInitSuccess,
        onPersistRunConfigManager = onPersistRunConfigManager,
        onNoOpenFile = onNoOpenFile,
        onUnsupportedEditor = onUnsupportedEditor,
        onOpenBookmarks = onOpenBookmarks,
        onOpenTerminal = onOpenTerminal,
        onOpenSettings = onOpenSettings,
        onSaveFile = onSaveFile,
        onGitRefresh = onGitRefresh,
        onGitBranchSelect = onGitBranchSelect,
        onGitCommitClick = onGitCommitClick,
        onRequestUnsavedExitConfirm = onRequestUnsavedExitConfirm,
    )
}

@Composable
internal fun rememberMainActivityWorkspaceCallbacks(
    onOpenSettings: () -> Unit,
    toastInfo: (String) -> Unit,
    toastError: (String) -> Unit,
    onDuplicateFileOrDirectory: suspend (File) -> File?,
    onDuplicateSuccess: (File) -> Unit,
    onDuplicateFailure: () -> Unit,
    onOpenWithExternalApp: (File) -> Unit,
    onShareFileOrDirectory: (File) -> Unit,
    onProjectNotOpen: () -> Unit,
    onGitCommitSuccess: () -> Unit,
    onGitInitSuccess: () -> Unit,
    onPersistRunConfigManager: (RunConfigurationManager) -> Boolean,
    onNoOpenFile: () -> Unit,
    onUnsupportedEditor: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSaveFile: (tabId: String, onComplete: () -> Unit) -> Unit,
    onGitRefresh: () -> Unit,
    onGitBranchSelect: (String) -> Unit,
    onGitCommitClick: (GitCommit) -> Unit,
    onRequestUnsavedExitConfirm: () -> Unit,
): MainActivityWorkspaceCallbacks {
    val screenCallbacks = rememberMainActivityScreenCallbacks(
        onDuplicateFileOrDirectory = onDuplicateFileOrDirectory,
        onDuplicateSuccess = onDuplicateSuccess,
        onDuplicateFailure = onDuplicateFailure,
        onOpenWithExternalApp = onOpenWithExternalApp,
        onShareFileOrDirectory = onShareFileOrDirectory,
        onProjectNotOpen = onProjectNotOpen,
        onGitCommitSuccess = onGitCommitSuccess,
        onGitInitSuccess = onGitInitSuccess,
        onPersistRunConfigManager = onPersistRunConfigManager,
        onNoOpenFile = onNoOpenFile,
        onUnsupportedEditor = onUnsupportedEditor,
        onOpenBookmarks = onOpenBookmarks,
        onOpenTerminal = onOpenTerminal,
        onOpenSettings = onOpenSettings,
        onSaveFile = onSaveFile,
        onGitRefresh = onGitRefresh,
        onGitBranchSelect = onGitBranchSelect,
        onGitCommitClick = onGitCommitClick,
        onRequestUnsavedExitConfirm = onRequestUnsavedExitConfirm,
    )

    return remember(
        onOpenSettings,
        toastInfo,
        toastError,
        screenCallbacks,
    ) {
        MainActivityWorkspaceCallbacks(
            onOpenSettings = onOpenSettings,
            toastInfo = toastInfo,
            toastError = toastError,
            screenCallbacks = screenCallbacks,
        )
    }
}

@Stable
internal class MainActivityWorkspaceUi(
    val hostCommandExecutor: HostCommandExecutor,
    val screenCallbacks: MainActivityScreenCallbacks,
)

@Composable
internal fun rememberMainActivityWorkspaceUi(
    activity: Activity,
    editorContainerState: EditorContainerState,
    fileTreeState: FileTreeState,
    projectContext: IProjectContext,
    drawerState: SwipeableDrawerState,
    dialogState: DialogState,
    bottomPanelViewModel: BottomPanelViewModel,
    bottomPanelController: BottomPanelController,
    actionsViewModel: MainActivityActionsViewModel,
    compileActionsHelper: CompileActionsHelper,
    hostScope: CoroutineScope,
    onOpenRunConfig: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    callbacks: MainActivityWorkspaceCallbacks,
): MainActivityWorkspaceUi {
    val hostCommandExecutor = rememberMainActivityHostCommandExecutor(
        activity = activity,
        editorContainerState = editorContainerState,
        fileTreeState = fileTreeState,
        projectContext = projectContext,
        drawerState = drawerState,
        dialogState = dialogState,
        bottomPanelViewModel = bottomPanelViewModel,
        bottomPanelController = bottomPanelController,
        actionsViewModel = actionsViewModel,
        compileActionsHelper = compileActionsHelper,
        scope = hostScope,
        openSettings = callbacks.onOpenSettings,
        openRunConfig = onOpenRunConfig,
        openCommandPalette = onOpenCommandPalette,
        toastInfo = callbacks.toastInfo,
    )

    return remember(hostCommandExecutor, callbacks.screenCallbacks) {
        MainActivityWorkspaceUi(
            hostCommandExecutor = hostCommandExecutor,
            screenCallbacks = callbacks.screenCallbacks,
        )
    }
}
