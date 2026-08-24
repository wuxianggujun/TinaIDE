package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.wuxianggujun.tinaide.ui.TinaEmbeddedProjectBridge
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawer
import me.rerere.rikkahub.data.ai.embedded.EmbeddedProjectBridgeRegistry

@Composable
internal fun MainActivityDrawerHost(
    uiState: MainActivityScreenUiState,
    dependencies: MainActivityDrawerDependencies,
    showCommandPalette: Boolean,
    onOpenCommandPalette: () -> Unit,
    onDismissCommandPalette: () -> Unit,
    callbacks: MainActivityScreenCallbacks,
) {
    val embeddedProjectBridge = remember(dependencies.projectContext, dependencies.editorContainerState) {
        TinaEmbeddedProjectBridge(
            projectContext = dependencies.projectContext,
            editorState = dependencies.editorContainerState,
        )
    }
    DisposableEffect(embeddedProjectBridge) {
        EmbeddedProjectBridgeRegistry.register(embeddedProjectBridge)
        onDispose {
            EmbeddedProjectBridgeRegistry.unregister(embeddedProjectBridge)
        }
    }

    BackHandler(enabled = dependencies.drawerState.isOpen || dependencies.editorContainerState.hasUnsavedChanges()) {
        when {
            dependencies.drawerState.isOpen -> dependencies.drawerState.close()
            dependencies.editorContainerState.hasUnsavedChanges() -> callbacks.onRequestUnsavedExitConfirm()
        }
    }

    SwipeableDrawer(
        state = dependencies.drawerState,
        drawerContent = {
            MainActivityDrawerContentHost(
                projectName = uiState.projectName,
                fileTreeState = dependencies.fileTreeState,
                editorContainerState = dependencies.editorContainerState,
                dialogState = dependencies.dialogState,
                actionsDelegate = dependencies.actionsDelegate,
                gitUiState = uiState.gitUiState,
                gitDialogState = dependencies.gitDialogState,
                hostCommandExecutor = dependencies.hostCommandExecutor,
                drawerState = dependencies.drawerState,
                uiScope = dependencies.uiScope,
                projectContext = dependencies.projectContext,
                gitViewModel = dependencies.gitViewModel,
                callbacks = callbacks,
            )
        }
    ) {
        Scaffold(
            modifier = Modifier,
            topBar = {
                MainActivityTopBarHost(
                    isCompiling = uiState.isCompiling,
                    isDirty = uiState.isDirty,
                    isDebugActive = uiState.isDebugActive,
                    debugStatus = uiState.debugStatus,
                    buildUiState = dependencies.buildUiState,
                    drawerState = dependencies.drawerState,
                    editorContainerState = dependencies.editorContainerState,
                    dialogState = dependencies.dialogState,
                    compileDelegate = dependencies.compileDelegate,
                    actionsDelegate = dependencies.actionsDelegate,
                    navigationDelegate = dependencies.navigationDelegate,
                    hostCommandExecutor = dependencies.hostCommandExecutor,
                    debugViewModel = dependencies.debugViewModel,
                    showCommandPalette = showCommandPalette,
                    onOpenCommandPalette = onOpenCommandPalette,
                    onDismissCommandPalette = onDismissCommandPalette,
                    callbacks = callbacks,
                )
            }
        ) { paddingValues ->
            MainActivityBottomPanelHost(
                paddingValues = paddingValues,
                editorContainerState = dependencies.editorContainerState,
                dialogState = dependencies.dialogState,
                hostCommandExecutor = dependencies.hostCommandExecutor,
                drawerState = dependencies.drawerState,
                gitUiState = uiState.gitUiState,
                cursorLine = uiState.cursorLine,
                cursorColumn = uiState.cursorColumn,
                fileEncoding = uiState.fileEncoding,
                editorManager = dependencies.editorManager,
                projectSymbolIndexService = dependencies.projectSymbolIndexService,
                bottomPanelController = dependencies.bottomPanelController,
                bottomPanelViewModel = dependencies.bottomPanelViewModel,
                editorStateViewModel = dependencies.editorStateViewModel,
                debugViewModel = dependencies.debugViewModel,
                actionsDelegate = dependencies.actionsDelegate,
                navigationDelegate = dependencies.navigationDelegate,
                callbacks = callbacks,
            )
        }
    }
}
