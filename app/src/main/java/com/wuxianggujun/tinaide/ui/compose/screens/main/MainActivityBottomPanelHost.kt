package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.ui.BindMainActivityBottomPanelState
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.MainActivityBottomPanelActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityNavigationDelegate
import com.wuxianggujun.tinaide.ui.compose.components.BottomPanel
import com.wuxianggujun.tinaide.ui.compose.components.BottomPanelSecondaryBarHeight
import com.wuxianggujun.tinaide.ui.compose.components.EditorContainer
import com.wuxianggujun.tinaide.ui.compose.components.EditorStatusBarHeight
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.components.rememberBottomPanelDragState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitUiState
import org.koin.compose.koinInject
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveEditorCommandResult

@Composable
internal fun MainActivityBottomPanelHost(
    paddingValues: PaddingValues,
    editorContainerState: EditorContainerState,
    dialogState: DialogState,
    hostCommandExecutor: HostCommandExecutor?,
    drawerState: SwipeableDrawerState,
    gitUiState: GitUiState,
    cursorLine: Int,
    cursorColumn: Int,
    fileEncoding: String,
    editorManager: IEditorManager,
    projectSymbolIndexService: ProjectSymbolIndexService?,
    bottomPanelController: MainActivityBottomPanelActionBridge,
    bottomPanelViewModel: BottomPanelViewModel,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    actionsDelegate: MainActivityActionsDelegate,
    navigationDelegate: MainActivityNavigationDelegate,
    callbacks: MainActivityScreenCallbacks,
) {
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .consumeWindowInsets(paddingValues)
    ) {
        val maxPanelHeight = (maxHeight - EditorStatusBarHeight - BottomPanelSecondaryBarHeight)
            .coerceAtLeast(0.dp)

        val bottomPanelState = rememberBottomPanelDragState(
            initialExpanded = false,
            minHeight = 0.dp,
            maxHeight = maxPanelHeight
        )
        BindMainActivityBottomPanelState(
            bottomPanelState = bottomPanelState,
            bottomPanelController = bottomPanelController,
        )
        val bottomPanelImeModifier = if (drawerState.isClosed) {
            Modifier
                .windowInsetsPadding(WindowInsets.ime)
                .consumeWindowInsets(WindowInsets.ime)
        } else {
            Modifier
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                EditorContainer(
                    state = editorContainerState,
                    editorManager = editorManager,
                    pluginManager = koinInject(),
                    hostCommandExecutor = hostCommandExecutor,
                    onOpenFileTree = { drawerState.open() },
                    onEditorStateChanged = { hasFiles, undo, redo, dirty ->
                        editorStateViewModel.updateEditorState(hasFiles, undo, redo, dirty)
                    },
                    onCursorPositionChanged = { line, column ->
                        editorStateViewModel.updateCursorPosition(line, column)
                    },
                    onFileEncodingChanged = { encoding ->
                        editorStateViewModel.updateFileEncoding(encoding)
                    },
                    onSaveFile = callbacks.onSaveFile,
                    onOpenPluginLspDependencySettings = { pluginId ->
                        SettingsActivity.startPluginDetail(context, pluginId)
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(bottomPanelImeModifier)
            ) {
                BottomPanel(
                    editorContainerState = editorContainerState,
                    bottomPanelState = bottomPanelState,
                    bottomPanelViewModel = bottomPanelViewModel,
                    editorStateViewModel = editorStateViewModel,
                    debugViewModel = debugViewModel,
                    projectSymbolIndexService = projectSymbolIndexService,
                    modifier = Modifier.fillMaxWidth(),
                    onBookmarkNavigate = { filePath, line ->
                        actionsDelegate.navigateToBookmark(editorContainerState, filePath, line)
                    },
                    onDiagnosticClick = { diagnostic ->
                        navigationDelegate.navigateToDiagnostic(editorContainerState, diagnostic)
                    },
                    gitCurrentBranch = gitUiState.status.branch,
                    gitBranches = gitUiState.branches,
                    gitCommits = gitUiState.commitHistory,
                    gitIsLoadingHistory = gitUiState.isLoadingHistory,
                    onGitRefresh = callbacks.onGitRefresh,
                    onGitBranchSelect = callbacks.onGitBranchSelect,
                    onGitCommitClick = callbacks.onGitCommitClick,
                    cursorLine = cursorLine,
                    cursorColumn = cursorColumn,
                    fileEncoding = fileEncoding,
                    onCursorPositionClick = {
                        when (editorContainerState.getActiveEditableEditorCommandAvailability()) {
                            ActiveEditorCommandResult.SUCCESS -> {
                                dialogState.openGotoLineDialog()
                            }

                            ActiveEditorCommandResult.NO_OPEN_FILE -> {
                                callbacks.onNoOpenFile()
                            }

                            ActiveEditorCommandResult.UNSUPPORTED_EDITOR -> {
                                callbacks.onUnsupportedEditor()
                            }
                        }
                    }
                )
            }
        }
    }
}
