package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.MainActivityCompileDelegate
import com.wuxianggujun.tinaide.ui.MainActivityNavigationDelegate
import com.wuxianggujun.tinaide.ui.compose.components.DebugStatus
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivityTopBarHost(
    isCompiling: Boolean,
    isDirty: Boolean,
    isDebugActive: Boolean,
    debugStatus: DebugStatus,
    buildUiState: MainActivityBuildUiState,
    drawerState: SwipeableDrawerState,
    editorContainerState: EditorContainerState,
    dialogState: DialogState,
    compileDelegate: MainActivityCompileDelegate,
    actionsDelegate: MainActivityActionsDelegate,
    navigationDelegate: MainActivityNavigationDelegate,
    debugViewModel: DebugViewModel,
    callbacks: MainActivityScreenCallbacks,
) {
    val canPackageApk = when (buildUiState.apkExportType) {
        null,
        ProjectApkExportType.DISABLED -> false

        ProjectApkExportType.TERMINAL -> buildUiState.hasTerminalApkExportOptions
        else -> true
    }
    val activeFile = editorContainerState.getActiveFileOrNull()
    val isBasicLspNavigationAvailable = activeFile?.let(editorContainerState::supportsBasicLspNavigation) == true
    val isAdvancedLspNavigationAvailable = activeFile?.let(editorContainerState::supportsAdvancedLspNavigation) == true
    val isCallHierarchyIncomingAvailable = editorContainerState.supportsActiveCallHierarchyIncoming()
    val isLspRefactorAvailable = activeFile?.let(editorContainerState::supportsLspRefactorActions) == true
    val isHeaderSourceSwitchAvailable = activeFile?.let(editorContainerState::supportsHeaderSourceSwitch) == true
    val canNavigateBack = editorContainerState.canNavigateBack()
    val canNavigateForward = editorContainerState.canNavigateForward()
    val canMoveTabToSecondaryPane = editorContainerState.canMoveActiveTabToSecondaryPane()

    val topBarCallbacks = rememberMainActivityTopBarCallbacks(
        drawerState = drawerState,
        editorContainerState = editorContainerState,
        dialogState = dialogState,
        compileDelegate = compileDelegate,
        actionsDelegate = actionsDelegate,
        navigationDelegate = navigationDelegate,
        screenCallbacks = callbacks,
        onPackageApk = buildUiState::openApkPackageDialog,
    )

    MainActivityTopBar(
        isCompiling = isCompiling,
        isDirty = isDirty,
        isDebugActive = isDebugActive,
        debugStatus = debugStatus,
        canPackageApk = canPackageApk,
        isBasicLspNavigationAvailable = isBasicLspNavigationAvailable,
        isAdvancedLspNavigationAvailable = isAdvancedLspNavigationAvailable,
        isCallHierarchyIncomingAvailable = isCallHierarchyIncomingAvailable,
        isLspRefactorAvailable = isLspRefactorAvailable,
        isHeaderSourceSwitchAvailable = isHeaderSourceSwitchAvailable,
        canNavigateBack = canNavigateBack,
        canNavigateForward = canNavigateForward,
        isSplitEditorEnabled = editorContainerState.isSplitEditorEnabled,
        canMoveTabToSecondaryPane = canMoveTabToSecondaryPane,
        currentBuildSystem = buildUiState.currentBuildSystem,
        availableTargets = buildUiState.availableTargets,
        runConfigManager = buildUiState.runConfigManager,
        onRunConfigManagerChange = { updated ->
            buildUiState.updateRunConfigManager(updated)
            callbacks.onPersistRunConfigManager(updated)
        },
        onEditConfig = buildUiState::startEditingConfig,
        onShowRunConfigDialog = buildUiState::openRunConfigDialog,
        callbacks = topBarCallbacks,
        debugViewModel = debugViewModel,
    )
}

@Composable
private fun rememberMainActivityTopBarCallbacks(
    drawerState: SwipeableDrawerState,
    editorContainerState: EditorContainerState,
    dialogState: DialogState,
    compileDelegate: MainActivityCompileDelegate,
    actionsDelegate: MainActivityActionsDelegate,
    navigationDelegate: MainActivityNavigationDelegate,
    screenCallbacks: MainActivityScreenCallbacks,
    onPackageApk: () -> Unit,
): TopBarCallbacks = remember(
    drawerState,
    editorContainerState,
    dialogState,
    compileDelegate,
    actionsDelegate,
    navigationDelegate,
    screenCallbacks,
    onPackageApk,
) {
    TopBarCallbacks(
        onOpenDrawer = { drawerState.open() },
        onBuild = { compileDelegate.onBuildProject() },
        onCompile = { compileDelegate.onCompileProject() },
        onRebuildAndRun = { compileDelegate.onRebuildAndRunProject() },
        onCompileInTerminal = { compileDelegate.onCompileInTerminal() },
        onDebug = { compileDelegate.onDebugProject() },
        onSave = { actionsDelegate.saveCurrentFile(editorContainerState) },
        onSaveAll = { actionsDelegate.saveAllFiles(editorContainerState) },
        onFormatCode = { actionsDelegate.formatCode(editorContainerState) },
        onNavigateBack = { editorContainerState.navigateBack() },
        onNavigateForward = { editorContainerState.navigateForward() },
        onPeekDefinition = { editorContainerState.requestActiveLspNavigation("peekDefinition") },
        onGotoDefinition = { editorContainerState.requestActiveLspNavigation("definition") },
        onFindReferences = { editorContainerState.requestActiveLspNavigation("references") },
        onGotoTypeDefinition = { editorContainerState.requestActiveLspNavigation("typeDefinition") },
        onGotoImplementation = { editorContainerState.requestActiveLspNavigation("implementation") },
        onCallHierarchyIncoming = { editorContainerState.requestActiveLspNavigation("callHierarchyIncoming") },
        onCodeActions = { editorContainerState.requestActiveLspCodeActions() },
        onRenameSymbol = { editorContainerState.requestActiveLspRename() },
        onSwitchHeaderSource = { editorContainerState.requestActiveLspNavigation("switchHeaderSource") },
        onToggleSplitEditor = { editorContainerState.toggleSplitEditor() },
        onMoveTabToSecondaryPane = { editorContainerState.moveActiveTabToSecondaryPane() },
        onGotoLine = {
            when (editorContainerState.getActiveEditableEditorCommandAvailability()) {
                EditorContainerState.ActiveEditorCommandResult.SUCCESS -> {
                    dialogState.openGotoLineDialog()
                }

                EditorContainerState.ActiveEditorCommandResult.NO_OPEN_FILE -> {
                    screenCallbacks.onNoOpenFile()
                }

                EditorContainerState.ActiveEditorCommandResult.UNSUPPORTED_EDITOR -> {
                    screenCallbacks.onUnsupportedEditor()
                }
            }
        },
        onOpenExplorer = { drawerState.open() },
        onOpenGlobalSearch = { navigationDelegate.openGlobalSearch() },
        onOpenBookmarks = screenCallbacks.onOpenBookmarks,
        onToggleBookmark = { actionsDelegate.toggleBookmark(editorContainerState) },
        onPrevBookmark = { actionsDelegate.goToPreviousBookmark(editorContainerState) },
        onNextBookmark = { actionsDelegate.goToNextBookmark(editorContainerState) },
        onOpenTerminal = screenCallbacks.onOpenTerminal,
        onOpenSettings = screenCallbacks.onOpenSettings,
        onExitWorkspace = { dialogState.openCloseProjectDialog() },
        onPackageApk = onPackageApk,
        onCmakeOpenArtifactsDir = { compileDelegate.onCmakeOpenArtifactsDir() },
        onCmakeReconfigure = { compileDelegate.onCmakeReconfigure() },
        onCmakeCleanAndReconfigure = { compileDelegate.onCmakeCleanAndReconfigure() },
        onCmakeClearBuildDir = { compileDelegate.onCmakeClearBuildDir() },
    )
}
