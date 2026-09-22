package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.MainActivityBottomPanelActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityCompileDelegate
import com.wuxianggujun.tinaide.ui.MainActivityNavigationDelegate
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitDialogState
import kotlinx.coroutines.CoroutineScope

@Stable
internal class MainActivityDrawerDependencies(
    val fileTreeState: FileTreeState,
    val editorContainerState: EditorContainerState,
    val dialogState: DialogState,
    val gitDialogState: GitDialogState,
    val drawerState: SwipeableDrawerState,
    val buildUiState: MainActivityBuildUiState,
    val hostCommandExecutor: HostCommandExecutor?,
    val uiScope: CoroutineScope,
    val projectContext: IProjectContext,
    val editorManager: IEditorManager,
    val projectSymbolIndexService: ProjectSymbolIndexService?,
    val gitViewModel: GitViewModel,
    val bottomPanelController: MainActivityBottomPanelActionBridge,
    val bottomPanelViewModel: BottomPanelViewModel,
    val editorStateViewModel: EditorStateViewModel,
    val debugViewModel: DebugViewModel,
    val actionsDelegate: MainActivityActionsDelegate,
    val compileDelegate: MainActivityCompileDelegate,
    val navigationDelegate: MainActivityNavigationDelegate,
    val onReconfigureCMake: () -> Unit,
)

@Composable
internal fun rememberMainActivityDrawerDependencies(
    fileTreeState: FileTreeState,
    editorContainerState: EditorContainerState,
    dialogState: DialogState,
    gitDialogState: GitDialogState,
    drawerState: SwipeableDrawerState,
    buildUiState: MainActivityBuildUiState,
    hostCommandExecutor: HostCommandExecutor?,
    uiScope: CoroutineScope,
    projectContext: IProjectContext,
    editorManager: IEditorManager,
    projectSymbolIndexService: ProjectSymbolIndexService?,
    gitViewModel: GitViewModel,
    bottomPanelController: MainActivityBottomPanelActionBridge,
    bottomPanelViewModel: BottomPanelViewModel,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    actionsDelegate: MainActivityActionsDelegate,
    compileDelegate: MainActivityCompileDelegate,
    navigationDelegate: MainActivityNavigationDelegate,
    onReconfigureCMake: () -> Unit,
): MainActivityDrawerDependencies = remember(
    fileTreeState,
    editorContainerState,
    dialogState,
    gitDialogState,
    drawerState,
    buildUiState,
    hostCommandExecutor,
    uiScope,
    projectContext,
    editorManager,
    projectSymbolIndexService,
    gitViewModel,
    bottomPanelController,
    bottomPanelViewModel,
    editorStateViewModel,
    debugViewModel,
    actionsDelegate,
    compileDelegate,
    navigationDelegate,
    onReconfigureCMake,
) {
    MainActivityDrawerDependencies(
        fileTreeState = fileTreeState,
        editorContainerState = editorContainerState,
        dialogState = dialogState,
        gitDialogState = gitDialogState,
        drawerState = drawerState,
        buildUiState = buildUiState,
        hostCommandExecutor = hostCommandExecutor,
        uiScope = uiScope,
        projectContext = projectContext,
        editorManager = editorManager,
        projectSymbolIndexService = projectSymbolIndexService,
        gitViewModel = gitViewModel,
        bottomPanelController = bottomPanelController,
        bottomPanelViewModel = bottomPanelViewModel,
        editorStateViewModel = editorStateViewModel,
        debugViewModel = debugViewModel,
        actionsDelegate = actionsDelegate,
        compileDelegate = compileDelegate,
        navigationDelegate = navigationDelegate,
        onReconfigureCMake = onReconfigureCMake,
    )
}
