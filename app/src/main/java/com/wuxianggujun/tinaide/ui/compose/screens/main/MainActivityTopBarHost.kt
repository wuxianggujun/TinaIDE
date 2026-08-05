package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.MainActivityCompileDelegate
import com.wuxianggujun.tinaide.ui.MainActivityNavigationDelegate
import com.wuxianggujun.tinaide.ui.compose.components.DebugStatus
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveEditorCommandResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.SplitEditorLayout

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
    hostCommandExecutor: HostCommandExecutor?,
    debugViewModel: DebugViewModel,
    showCommandPalette: Boolean,
    onOpenCommandPalette: () -> Unit,
    onDismissCommandPalette: () -> Unit,
    callbacks: MainActivityScreenCallbacks,
) {
    val context = LocalContext.current
    val pluginManager = remember(context) {
        PluginManager.getInstance(context.applicationContext)
    }
    val enabledPlugins by pluginManager.enabledPluginsFlow.collectAsStateWithLifecycle()
    val enabledPluginIds = remember(enabledPlugins) {
        enabledPlugins.mapTo(linkedSetOf()) { plugin -> plugin.manifest.id }
    }
    val commandStore = rememberMainActivityCommandPreferenceStore()
    val pinnedCommandIds by commandStore.pinnedCommandIdsFlow.collectAsStateWithLifecycle()
    val recentCommandIds by commandStore.recentCommandIdsFlow.collectAsStateWithLifecycle()
    val canPackageApk = resolveCanPackageApk(buildUiState)
    val activeFile = editorContainerState.getActiveFileOrNull()
    val isBasicLspNavigationAvailable = activeFile?.let(editorContainerState::supportsBasicLspNavigation) == true
    val isAdvancedLspNavigationAvailable = activeFile?.let(editorContainerState::supportsAdvancedLspNavigation) == true
    val isCallHierarchyIncomingAvailable = editorContainerState.supportsActiveCallHierarchyIncoming()
    val isLspRefactorAvailable = activeFile?.let(editorContainerState::supportsLspRefactorActions) == true
    val isHeaderSourceSwitchAvailable = activeFile?.let(editorContainerState::supportsHeaderSourceSwitch) == true
    val canNavigateBack = editorContainerState.canNavigateBack()
    val canNavigateForward = editorContainerState.canNavigateForward()
    val canMoveTabToSecondaryPane = editorContainerState.canMoveActiveTabToSecondaryPane()
    val canCopyTabToSecondaryPane = editorContainerState.canCopyActiveTabToSecondaryPane()
    val editorToolBarState = editorContainerState.getActiveEditorToolBarState()
    val canUndo = editorToolBarState.canUndo
    val canRedo = editorToolBarState.canRedo

    val topBarCallbacks = rememberMainActivityTopBarCallbacks(
        drawerState = drawerState,
        editorContainerState = editorContainerState,
        dialogState = dialogState,
        compileDelegate = compileDelegate,
        actionsDelegate = actionsDelegate,
        navigationDelegate = navigationDelegate,
        screenCallbacks = callbacks,
        onPackageApk = buildUiState::openApkPackageDialog,
        onOpenCommandPalette = onOpenCommandPalette,
    )
    val commandPaletteCommands = rememberMainActivityCommands(
        availability = MainActivityCommandAvailability(
            hasActiveFile = activeFile != null,
            isCompiling = isCompiling,
            isDirty = isDirty,
            canPackageApk = canPackageApk,
            isBasicLspNavigationAvailable = isBasicLspNavigationAvailable,
            isAdvancedLspNavigationAvailable = isAdvancedLspNavigationAvailable,
            isCallHierarchyIncomingAvailable = isCallHierarchyIncomingAvailable,
            isLspRefactorAvailable = isLspRefactorAvailable,
            isHeaderSourceSwitchAvailable = isHeaderSourceSwitchAvailable,
            canNavigateBack = canNavigateBack,
            canNavigateForward = canNavigateForward,
            isSplitEditorEnabled = editorContainerState.isSplitEditorEnabled,
            splitEditorLayout = editorContainerState.splitEditorLayout,
            canMoveTabToSecondaryPane = canMoveTabToSecondaryPane,
            canCopyTabToSecondaryPane = canCopyTabToSecondaryPane,
            currentBuildSystem = buildUiState.currentBuildSystem,
        ),
        activeFile = activeFile,
        isActiveTabDirty = editorContainerState.isActiveTabDirty(),
        callbacks = topBarCallbacks,
        hostCommandExecutor = hostCommandExecutor,
    )
    LaunchedEffect(commandStore, enabledPluginIds) {
        commandStore.pruneUnavailablePluginCommands(enabledPluginIds)
    }
    val overflowCommands = rememberMainActivityOverflowCommands(commandPaletteCommands)
    val executeCommand: (MainActivityCommand) -> Unit = remember(commandStore) {
        { command ->
            if (command.enabled) {
                commandStore.recordExecuted(command.id)
                command.execute()
            }
        }
    }

    MainActivityTopBar(
        isCompiling = isCompiling,
        isDirty = isDirty,
        canUndo = canUndo,
        canRedo = canRedo,
        isDebugActive = isDebugActive,
        debugStatus = debugStatus,
        runConfigManager = buildUiState.runConfigManager,
        onRunConfigManagerChange = { updated ->
            if (!buildUiState.commitRunConfigManager(updated, callbacks.onPersistRunConfigManager)) {
                context.toastError(Strings.toast_run_config_save_failed.strOr(context))
            }
        },
        onEditConfig = buildUiState::startEditingConfig,
        onShowRunConfigDialog = buildUiState::openRunConfigDialog,
        callbacks = topBarCallbacks,
        debugViewModel = debugViewModel,
        overflowCommands = overflowCommands,
        onExecuteCommand = executeCommand,
    )

    if (showCommandPalette) {
        MainActivityCommandPalette(
            commands = commandPaletteCommands,
            pinnedCommandIds = pinnedCommandIds,
            recentCommandIds = recentCommandIds,
            onTogglePinned = { command -> commandStore.togglePinned(command.id) },
            onMovePinned = { command, direction -> commandStore.movePinned(command.id, direction) },
            onExecuteCommand = executeCommand,
            onDismissRequest = onDismissCommandPalette
        )
    }
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
    onOpenCommandPalette: () -> Unit,
): TopBarCallbacks = remember(
    drawerState,
    editorContainerState,
    dialogState,
    compileDelegate,
    actionsDelegate,
    navigationDelegate,
    screenCallbacks,
    onPackageApk,
    onOpenCommandPalette,
) {
    TopBarCallbacks(
        onOpenDrawer = { drawerState.open() },
        onOpenCommandPalette = onOpenCommandPalette,
        onBuild = { compileDelegate.onBuildProject() },
        onCompile = { compileDelegate.onCompileProject() },
        onRebuildAndRun = { compileDelegate.onRebuildAndRunProject() },
        onCompileInTerminal = { compileDelegate.onCompileInTerminal() },
        onDebug = { compileDelegate.onDebugProject() },
        onSave = { actionsDelegate.saveCurrentFile(editorContainerState) },
        onSaveAll = { actionsDelegate.saveAllFiles(editorContainerState) },
        onUndo = { editorContainerState.undoInActiveTab() },
        onRedo = { editorContainerState.redoInActiveTab() },
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
        onSetSplitEditorLayout = { layout -> editorContainerState.updateSplitEditorLayout(layout) },
        onMoveTabToSecondaryPane = { editorContainerState.moveActiveTabToSecondaryPane() },
        onCopyTabToSecondaryPane = { editorContainerState.copyActiveTabToSecondaryPane() },
        onGotoLine = {
            when (editorContainerState.getActiveEditableEditorCommandAvailability()) {
                ActiveEditorCommandResult.SUCCESS -> {
                    dialogState.openGotoLineDialog()
                }

                ActiveEditorCommandResult.NO_OPEN_FILE -> {
                    screenCallbacks.onNoOpenFile()
                }

                ActiveEditorCommandResult.UNSUPPORTED_EDITOR -> {
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
