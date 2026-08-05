package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorActionsState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitDialogState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitUiState
import java.io.File
import kotlinx.coroutines.CoroutineScope

@Stable
internal data class MainActivityDialogsUiState(
    val editorActionsState: EditorActionsState,
    val locationDialogState: MainActivityLocationDialogState,
    val dialogState: DialogState,
    val buildUiState: MainActivityBuildUiState,
    val gitUiState: GitUiState,
    val gitDialogState: GitDialogState,
    val projectName: String,
    val projectRoot: File?,
    val buildDir: File?,
    val showUnsavedExitDialog: Boolean,
)

@Stable
internal class MainActivityDialogsDependencies(
    val uiScope: CoroutineScope,
    val editorContainerState: EditorContainerState,
    val actionsViewModel: MainActivityActionsViewModel,
    val fileTreeState: FileTreeState,
    val gitViewModel: GitViewModel,
    val editorManager: IEditorManager,
    val saveScope: CoroutineScope,
)

internal class MainActivityDialogsCallbacks(
    val onCloseProject: (forgetSession: Boolean) -> Unit,
    val onPersistRunConfigManager: (RunConfigurationManager) -> Boolean,
    val onShowUnsavedExitDialogChange: (Boolean) -> Unit,
    val onFinish: () -> Unit,
)

@Composable
internal fun rememberMainActivityDialogsUiState(
    editorActionsState: EditorActionsState,
    locationDialogState: MainActivityLocationDialogState,
    dialogState: DialogState,
    buildUiState: MainActivityBuildUiState,
    gitUiState: GitUiState,
    gitDialogState: GitDialogState,
    projectName: String,
    projectRoot: File?,
    buildDir: File?,
    showUnsavedExitDialog: Boolean,
): MainActivityDialogsUiState = remember(
    editorActionsState,
    locationDialogState,
    dialogState,
    buildUiState,
    gitUiState,
    gitDialogState,
    projectName,
    projectRoot,
    buildDir,
    showUnsavedExitDialog,
) {
    MainActivityDialogsUiState(
        editorActionsState = editorActionsState,
        locationDialogState = locationDialogState,
        dialogState = dialogState,
        buildUiState = buildUiState,
        gitUiState = gitUiState,
        gitDialogState = gitDialogState,
        projectName = projectName,
        projectRoot = projectRoot,
        buildDir = buildDir,
        showUnsavedExitDialog = showUnsavedExitDialog,
    )
}

@Composable
internal fun rememberMainActivityDialogsDependencies(
    uiScope: CoroutineScope,
    editorContainerState: EditorContainerState,
    actionsViewModel: MainActivityActionsViewModel,
    fileTreeState: FileTreeState,
    gitViewModel: GitViewModel,
    editorManager: IEditorManager,
    saveScope: CoroutineScope,
): MainActivityDialogsDependencies = remember(
    uiScope,
    editorContainerState,
    actionsViewModel,
    fileTreeState,
    gitViewModel,
    editorManager,
    saveScope,
) {
    MainActivityDialogsDependencies(
        uiScope = uiScope,
        editorContainerState = editorContainerState,
        actionsViewModel = actionsViewModel,
        fileTreeState = fileTreeState,
        gitViewModel = gitViewModel,
        editorManager = editorManager,
        saveScope = saveScope,
    )
}

@Composable
internal fun rememberMainActivityDialogsCallbacks(
    onCloseProject: (forgetSession: Boolean) -> Unit,
    onPersistRunConfigManager: (RunConfigurationManager) -> Boolean,
    onShowUnsavedExitDialogChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
): MainActivityDialogsCallbacks = remember(
    onCloseProject,
    onPersistRunConfigManager,
    onShowUnsavedExitDialogChange,
    onFinish,
) {
    MainActivityDialogsCallbacks(
        onCloseProject = onCloseProject,
        onPersistRunConfigManager = onPersistRunConfigManager,
        onShowUnsavedExitDialogChange = onShowUnsavedExitDialogChange,
        onFinish = onFinish,
    )
}
