package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.ui.GitStatusHelper
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.compose.components.DrawerContent
import com.wuxianggujun.tinaide.ui.compose.components.DrawerFileCallbacks
import com.wuxianggujun.tinaide.ui.compose.components.DrawerGitCallbacks
import com.wuxianggujun.tinaide.ui.compose.components.FileContextAction
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitDialogState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitUiState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun MainActivityDrawerContentHost(
    projectName: String,
    fileTreeState: FileTreeState,
    editorContainerState: EditorContainerState,
    dialogState: DialogState,
    actionsDelegate: MainActivityActionsDelegate,
    gitUiState: GitUiState,
    gitDialogState: GitDialogState,
    hostCommandExecutor: HostCommandExecutor?,
    drawerState: SwipeableDrawerState,
    uiScope: CoroutineScope,
    projectContext: IProjectContext,
    gitViewModel: GitViewModel,
    callbacks: MainActivityScreenCallbacks,
) {
    val gitStatusMap = remember(gitUiState.status) {
        GitStatusHelper.buildGitStatusMap(gitUiState.status)
    }

    DrawerContent(
        projectName = projectName,
        fileTreeState = fileTreeState,
        pluginManager = koinInject(),
        hostCommandExecutor = hostCommandExecutor,
        drawerOpen = drawerState.isOpen,
        editorContainerState = editorContainerState,
        selectedDrawerTab = drawerState.selectedTab,
        onDrawerTabSelected = { tab -> drawerState.selectedTab = tab },
        fileCallbacks = DrawerFileCallbacks(
            onFileClick = { file ->
                editorContainerState.openFile(file)
                drawerState.close()
            },
            onContextAction = { action ->
                when (action) {
                    is FileContextAction.NewFile -> {
                        dialogState.openNewFileDialog(action.parentDir)
                    }

                    is FileContextAction.NewFolder -> {
                        dialogState.openCreateFolderDialog(action.parentDir)
                    }

                    is FileContextAction.Rename -> {
                        dialogState.openRenameDialog(action.file)
                    }

                    is FileContextAction.Delete -> {
                        dialogState.openDeleteDialog(action.file)
                    }

                    is FileContextAction.CopyPath -> {
                        actionsDelegate.copyPathToClipboard(action.file)
                    }

                    is FileContextAction.CopyName -> {
                        actionsDelegate.copyNameToClipboard(action.file)
                    }

                    is FileContextAction.CopyRelativePath -> {
                        actionsDelegate.copyRelativePathToClipboard(action.file)
                    }

                    is FileContextAction.Duplicate -> {
                        uiScope.launch {
                            val duplicated = callbacks.onDuplicateFileOrDirectory(action.file)
                            if (duplicated != null) {
                                callbacks.onDuplicateSuccess(duplicated)
                                if (duplicated.isFile) {
                                    editorContainerState.openFile(duplicated)
                                }
                                fileTreeState.reveal(duplicated)
                            } else {
                                callbacks.onDuplicateFailure()
                            }
                        }
                    }

                    is FileContextAction.OpenWith -> {
                        callbacks.onOpenWithExternalApp(action.file)
                    }

                    is FileContextAction.Share -> {
                        callbacks.onShareFileOrDirectory(action.file)
                    }

                    is FileContextAction.RevealInFileManager -> {
                        drawerState.open()
                        uiScope.launch { fileTreeState.reveal(action.file) }
                    }
                }
            },
            onAddFileClick = { targetDir ->
                val project = projectContext.getCurrentProject()
                val dir = targetDir ?: project?.rootPath?.let(::File)
                if (dir != null) {
                    dialogState.openNewFileDialog(dir)
                } else {
                    callbacks.onProjectNotOpen()
                }
            },
        ),
        gitStatus = gitUiState.status,
        gitIsLoading = gitUiState.isLoading,
        gitStatusMap = gitStatusMap,
        gitCallbacks = DrawerGitCallbacks(
            onRefresh = { gitViewModel.refresh() },
            onStageAll = { gitViewModel.stageAll() },
            onUnstageAll = { gitViewModel.unstageAll() },
            onCommitWithMessage = { message ->
                gitViewModel.commit(message) {
                    callbacks.onGitCommitSuccess()
                }
            },
            onStageFile = { gitViewModel.stageFile(it) },
            onUnstageFile = { gitViewModel.unstageFile(it) },
            onDiscardChanges = { path ->
                uiScope.launch {
                    gitViewModel.discardChanges(path)
                }
            },
            onFileClick = { path ->
                val project = projectContext.getCurrentProject()
                if (project != null) {
                    val file = File(project.rootPath, path)
                    if (file.exists()) {
                        editorContainerState.openFile(file)
                        drawerState.close()
                    }
                }
            },
            onShowDiff = { path, isStaged ->
                gitViewModel.loadDiff(path, isStaged)
            },
            onInitRepository = {
                gitViewModel.initRepository {
                    callbacks.onGitInitSuccess()
                }
            },
            onOpenSyncDialog = {
                gitDialogState.showSyncDialog = true
            },
            onOpenRemoteDialog = {
                gitDialogState.showRemoteDialog = true
            },
            recentCommitMessages = gitUiState.recentCommitMessages,
            onClearCommitMessageHistory = gitViewModel::clearRecentCommitMessages,
        ),
    )
}
