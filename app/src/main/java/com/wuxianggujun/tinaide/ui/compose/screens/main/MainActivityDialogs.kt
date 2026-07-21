package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.apkbuilder.ApkTemplateType
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.packages.PackageManagerImpl
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.extensions.toastSuccess
import com.wuxianggujun.tinaide.file.IFileDeletionOperations
import com.wuxianggujun.tinaide.file.IFileOperations
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.ResolvedPluginApkExport
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.MainActivityNavigationHelper
import com.wuxianggujun.tinaide.ui.apk.ApkExportRuntimeLibrariesResolver
import com.wuxianggujun.tinaide.ui.apk.ApkExportTemplateOption
import com.wuxianggujun.tinaide.ui.apk.TerminalApkExportResolver
import com.wuxianggujun.tinaide.ui.compose.components.ApkPackageDialog
import com.wuxianggujun.tinaide.ui.compose.components.CodeActionsMenu
import com.wuxianggujun.tinaide.ui.compose.components.CreateFolderDialog
import com.wuxianggujun.tinaide.ui.compose.components.DeleteConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.components.GoToLineDialog
import com.wuxianggujun.tinaide.ui.compose.components.LocationListDialog
import com.wuxianggujun.tinaide.ui.compose.components.LspRenameDialog
import com.wuxianggujun.tinaide.ui.compose.components.NewFileDialog
import com.wuxianggujun.tinaide.ui.compose.components.RenameDialog
import com.wuxianggujun.tinaide.ui.compose.components.ReplaceDialog
import com.wuxianggujun.tinaide.ui.compose.components.RunConfigDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDangerOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.UnsavedChangesOnExitDialog
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorActionsState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitDialogState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitUiState
import com.wuxianggujun.tinaide.ui.runtime.NativeLibraryDependencyHints
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import timber.log.Timber

private const val BUILTIN_APK_TEMPLATE_NATIVE = "builtin:native_activity"
private const val BUILTIN_APK_TEMPLATE_SDL3 = "builtin:sdl3"
private const val APK_DIALOG_TAG = "MainActivityApkDialog"

@Stable
internal class MainActivityLocationDialogState {
    var showDialog by mutableStateOf(false)
        private set

    var title by mutableStateOf("")
        private set

    var locations by mutableStateOf<List<LocationItem>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    fun showLoading() {
        title = ""
        locations = emptyList()
        isLoading = true
        showDialog = true
    }

    fun showResults(title: String, results: List<LocationItem>) {
        this.title = title
        locations = results
        isLoading = false
        showDialog = true
    }

    fun dismiss() {
        showDialog = false
        title = ""
        locations = emptyList()
        isLoading = false
    }
}

@Composable
internal fun rememberMainActivityLocationDialogState(): MainActivityLocationDialogState = remember { MainActivityLocationDialogState() }

@Composable
internal fun MainActivityDialogsHost(
    uiState: MainActivityDialogsUiState,
    dependencies: MainActivityDialogsDependencies,
    callbacks: MainActivityDialogsCallbacks,
) {
    val openConflictFile: (String) -> Unit = { path ->
        if (uiState.projectRoot != null) {
            val file = File(uiState.projectRoot, path)
            if (file.exists()) {
                dependencies.editorContainerState.openFile(file)
            }
        }
    }

    MainActivityEditorDialogs(
        state = uiState.editorActionsState,
        uiScope = dependencies.uiScope,
        editorContainerState = dependencies.editorContainerState,
        actionsViewModel = dependencies.actionsViewModel,
    )

    MainActivityLocationDialog(
        state = uiState.locationDialogState,
        editorContainerState = dependencies.editorContainerState,
    )

    MainActivityFileDialogs(
        dialogState = uiState.dialogState,
        fileTreeState = dependencies.fileTreeState,
        uiScope = dependencies.uiScope,
        editorContainerState = dependencies.editorContainerState,
    )

    MainActivityCloseProjectDialog(
        dialogState = uiState.dialogState,
        onCloseProject = callbacks.onCloseProject,
    )

    MainActivityRunConfigDialog(
        state = uiState.buildUiState,
        onPersistRunConfigManager = callbacks.onPersistRunConfigManager,
    )

    MainActivityGitDialogs(
        gitUiState = uiState.gitUiState,
        gitViewModel = dependencies.gitViewModel,
        dialogState = uiState.gitDialogState,
        onOpenConflictFile = openConflictFile,
    )

    MainActivityApkPackageDialog(
        state = uiState.buildUiState,
        projectName = uiState.projectName,
        projectRoot = uiState.projectRoot,
        buildDir = uiState.buildDir,
    )

    MainActivityUnsavedExitDialog(
        showUnsavedExitDialog = uiState.showUnsavedExitDialog,
        onShowUnsavedExitDialogChange = callbacks.onShowUnsavedExitDialogChange,
        unsavedCount = dependencies.editorContainerState.getUnsavedCount(),
        editorManager = dependencies.editorManager,
        saveScope = dependencies.saveScope,
        onFinish = callbacks.onFinish,
    )
}

@Composable
internal fun MainActivityDialogsSection(
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
    uiScope: CoroutineScope,
    editorContainerState: EditorContainerState,
    actionsViewModel: MainActivityActionsViewModel,
    fileTreeState: FileTreeState,
    gitViewModel: GitViewModel,
    editorManager: IEditorManager,
    saveScope: CoroutineScope,
    onCloseProject: (forgetSession: Boolean) -> Unit,
    onPersistRunConfigManager: (RunConfigurationManager) -> Unit,
    onShowUnsavedExitDialogChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    val uiState = rememberMainActivityDialogsUiState(
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
    val dependencies = rememberMainActivityDialogsDependencies(
        uiScope = uiScope,
        editorContainerState = editorContainerState,
        actionsViewModel = actionsViewModel,
        fileTreeState = fileTreeState,
        gitViewModel = gitViewModel,
        editorManager = editorManager,
        saveScope = saveScope,
    )
    val callbacks = rememberMainActivityDialogsCallbacks(
        onCloseProject = onCloseProject,
        onPersistRunConfigManager = onPersistRunConfigManager,
        onShowUnsavedExitDialogChange = onShowUnsavedExitDialogChange,
        onFinish = onFinish,
    )

    MainActivityDialogsHost(
        uiState = uiState,
        dependencies = dependencies,
        callbacks = callbacks,
    )
}

@Composable
internal fun MainActivityEditorDialogs(
    state: EditorActionsState,
    uiScope: CoroutineScope,
    editorContainerState: EditorContainerState,
    actionsViewModel: MainActivityActionsViewModel,
) {
    val context = LocalContext.current

    if (state.showCodeActionsMenu) {
        CodeActionsMenu(
            actions = state.codeActions,
            isLoading = state.codeActionsLoading,
            onActionClick = { action ->
                val tabId = state.codeActionsTabId ?: return@CodeActionsMenu
                state.codeActionsLoading = true
                uiScope.launch {
                    val success = runCatching {
                        editorContainerState.executeCodeAction(
                            tabId = tabId,
                            action = action,
                            onApplyEdit = { edit -> actionsViewModel.applyWorkspaceEdit(editorContainerState, edit) }
                        )
                    }.getOrDefault(false)

                    state.codeActionsLoading = false
                    state.showCodeActionsMenu = false

                    if (success) {
                        context.toastSuccess(Strings.code_action_executed.strOr(context))
                    } else {
                        context.toastError(Strings.code_action_failed.strOr(context))
                    }
                }
            },
            onDismiss = { state.dismissCodeActions() }
        )
    }

    if (state.showLspRenameDialog) {
        LspRenameDialog(
            currentName = state.renameCurrentName,
            isLoading = state.renameIsLoading,
            error = state.renameError,
            onRename = { newName ->
                val tabId = state.renameTabId ?: return@LspRenameDialog
                state.renameIsLoading = true
                state.renameError = null

                uiScope.launch {
                    val result = runCatching {
                        editorContainerState.rename(
                            tabId = tabId,
                            line = state.renameLine,
                            column = state.renameColumn,
                            newName = newName,
                            onApplyEdit = { edit -> actionsViewModel.applyWorkspaceEdit(editorContainerState, edit) }
                        )
                    }.getOrNull()

                    if (result?.success == true) {
                        state.renameIsLoading = false
                        state.showLspRenameDialog = false
                        context.toastSuccess(
                            Strings.rename_success_with_files.strOr(context, result.changedFiles.size)
                        )
                    } else {
                        state.renameIsLoading = false
                        state.renameError = result?.error ?: Strings.rename_failed.strOr(context)
                    }
                }
            },
            onDismiss = { state.dismissRename() }
        )
    }
}

@Composable
internal fun MainActivityFileDialogs(
    dialogState: DialogState,
    fileTreeState: FileTreeState,
    uiScope: CoroutineScope,
    editorContainerState: EditorContainerState,
) {
    val context = LocalContext.current
    val fileOperations: IFileOperations = koinInject()
    val fileDeletionOperations: IFileDeletionOperations = koinInject()

    // 新建文件对话框
    if (dialogState.showNewFileDialog && dialogState.newFileTargetDir != null) {
        NewFileDialog(
            targetDir = dialogState.newFileTargetDir!!,
            onDismiss = { dialogState.closeNewFileDialog() },
            onConfirm = { result ->
                dialogState.closeNewFileDialog()

                // 显示成功消息
                val fileNames = result.files.joinToString(", ") { it.name }
                context.toastSuccess(Strings.files_created.strOr(context, fileNames))

                // 打开建议的文件
                result.openFile?.let { file ->
                    editorContainerState.openFile(file)
                    uiScope.launch { fileTreeState.reveal(file) }
                }
            }
        )
    }

    // 新建文件夹对话框
    if (dialogState.showCreateFolderDialog && dialogState.createFolderParentDir != null) {
        CreateFolderDialog(
            parentDir = dialogState.createFolderParentDir!!,
            onDismiss = { dialogState.closeCreateFolderDialog() }
        )
    }

    // 重命名对话框
    if (dialogState.showRenameDialog && dialogState.renameFile != null) {
        RenameDialog(
            file = dialogState.renameFile!!,
            onDismiss = { dialogState.closeRenameDialog() },
            onRename = fileOperations::renameFile
        )
    }

    // 删除确认对话框
    if (dialogState.showDeleteDialog && dialogState.deleteFile != null) {
        DeleteConfirmDialog(
            file = dialogState.deleteFile!!,
            onDismiss = { dialogState.closeDeleteDialog() },
            onDelete = fileDeletionOperations::deleteFile,
        )
    }

    // 跳转到行
    if (dialogState.showGotoLineDialog) {
        GoToLineDialog(
            onDismiss = { dialogState.closeGotoLineDialog() },
            onGoToLine = { lineNumber ->
                dialogState.closeGotoLineDialog()
                when (editorContainerState.requestGoToPositionInActiveEditableEditor(lineNumber - 1, 0)) {
                    EditorContainerState.ActiveEditorCommandResult.SUCCESS -> Unit
                    EditorContainerState.ActiveEditorCommandResult.NO_OPEN_FILE -> {
                        context.toastInfo(Strings.toast_no_open_file.strOr(context))
                    }

                    EditorContainerState.ActiveEditorCommandResult.UNSUPPORTED_EDITOR -> {
                        context.toastInfo(Strings.toast_file_not_support_format.strOr(context))
                    }
                }
            }
        )
    }

    // 替换（全文件 Replace All）
    if (dialogState.showReplaceDialog) {
        ReplaceDialog(
            initialFind = editorContainerState.currentSearchState.query,
            onDismiss = { dialogState.closeReplaceDialog() },
            onReplaceAll = { findText, replaceText ->
                dialogState.closeReplaceDialog()
                if (findText.isEmpty()) return@ReplaceDialog
                when (val result = editorContainerState.requestReplaceAllInActiveEditor(findText, replaceText)) {
                    EditorContainerState.ReplaceAllInActiveEditorResult.NoOpenFile -> {
                        context.toastInfo(Strings.toast_no_open_file.strOr(context))
                    }

                    EditorContainerState.ReplaceAllInActiveEditorResult.UnsupportedEditor -> {
                        context.toastInfo(Strings.toast_file_not_support_format.strOr(context))
                    }

                    EditorContainerState.ReplaceAllInActiveEditorResult.NoMatches -> {
                        context.toastInfo(Strings.toast_no_matches.strOr(context))
                    }

                    is EditorContainerState.ReplaceAllInActiveEditorResult.Success -> {
                        context.toastSuccess(Strings.toast_replaced.strOr(context, result.count))
                    }
                }
            }
        )
    }
}

@Composable
internal fun MainActivityCloseProjectDialog(
    dialogState: DialogState,
    onCloseProject: (forgetSession: Boolean) -> Unit,
) {
    if (!dialogState.showCloseProjectDialog) return

    TinaAlertDialog(
        onDismissRequest = { dialogState.closeCloseProjectDialog() },
        title = { TinaDialogTitleText(stringResource(Strings.dialog_close_project_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(Strings.dialog_close_project_message)
                )
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_close_project),
                onClick = {
                    dialogState.closeCloseProjectDialog()
                    onCloseProject(false)
                },
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinaDangerOutlinedButton(
                    text = stringResource(Strings.btn_close_and_forget),
                    onClick = {
                        dialogState.closeCloseProjectDialog()
                        onCloseProject(true)
                    }
                )
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = { dialogState.closeCloseProjectDialog() }
                )
            }
        }
    )
}

@Composable
internal fun MainActivityRunConfigDialog(
    state: MainActivityBuildUiState,
    onPersistRunConfigManager: (RunConfigurationManager) -> Unit,
) {
    val context = LocalContext.current
    val currentConfig = state.editingConfig ?: return
    if (!state.showRunConfigDialog) return

    RunConfigDialog(
        config = currentConfig,
        buildSystem = state.currentBuildSystem,
        availableTargets = state.availableTargets,
        onSave = { newConfig ->
            val isNew = state.runConfigManager.configurations.none { it.id == newConfig.id }
            val updated = if (isNew) {
                state.runConfigManager.addConfig(newConfig)
            } else {
                state.runConfigManager.updateConfig(newConfig)
            }
            state.updateRunConfigManager(updated)
            onPersistRunConfigManager(updated)
            state.closeRunConfigDialog()
            context.toastSuccess(Strings.toast_run_config_saved.strOr(context))
        },
        onDismiss = state::closeRunConfigDialog
    )
}

@Composable
internal fun MainActivityLocationDialog(
    state: MainActivityLocationDialogState,
    editorContainerState: EditorContainerState,
) {
    if (!state.showDialog) return

    LocationListDialog(
        title = state.title,
        locations = state.locations,
        isLoading = state.isLoading,
        onLocationClick = { location ->
            state.dismiss()
            MainActivityNavigationHelper.navigateToLocation(location, editorContainerState)
        },
        onDismiss = state::dismiss
    )
}

@Composable
internal fun MainActivityApkPackageDialog(
    state: MainActivityBuildUiState,
    projectName: String,
    projectRoot: File?,
    buildDir: File?,
) {
    if (!state.showApkPackageDialog) return

    val context = LocalContext.current
    val pluginLogManager = remember(context) {
        com.wuxianggujun.tinaide.plugin.PluginLogManager.getInstance(context.applicationContext)
    }
    val pluginManager = remember(context) { PluginManager.getInstance(context.applicationContext) }
    val packageManager = remember(context) {
        val appContext = context.applicationContext
        val configManager = org.koin.core.context.GlobalContext.get().get<com.wuxianggujun.tinaide.core.config.IConfigManager>()
        PackageManagerImpl(
            context = appContext,
            apiClient = PackageApiClient.getInstance(appContext),
            installStateStore = LocalInstallStateStore(appContext),
            prootEnv = PRootEnvironment(appContext, configManager),
        )
    }
    var availablePackages by remember { mutableStateOf<List<GUIPackage>>(emptyList()) }
    val enabledPlugins by pluginManager.enabledPluginsFlow.collectAsStateWithLifecycle()
    val exportPayload by produceState<MainActivityApkExportPayload?>(
        null,
        state.showApkPackageDialog,
        state.apkExportType,
        projectRoot?.absolutePath,
        buildDir?.absolutePath
    ) {
        value = withContext(Dispatchers.IO) {
            when (state.apkExportType) {
                ProjectApkExportType.TERMINAL -> {
                    val resolution = TerminalApkExportResolver.resolve(
                        context = context.applicationContext,
                        projectRoot = projectRoot,
                        buildDir = buildDir
                    )
                    MainActivityApkExportPayload(
                        soFiles = resolution.packagedLibraries,
                        executableFile = resolution.executableFile,
                        missingLibraries = resolution.missingLibraries,
                        installedLibraryPackageIndex = resolveInstalledLibraryPackageIndex(
                            context = context.applicationContext,
                            missingLibraries = resolution.missingLibraries,
                        ),
                    )
                }

                ProjectApkExportType.SDL3,
                ProjectApkExportType.NATIVE_ACTIVITY,
                ProjectApkExportType.DISABLED,
                null -> {
                    val resolution = ApkExportRuntimeLibrariesResolver.resolve(
                        context = context.applicationContext,
                        projectRoot = projectRoot,
                        buildDir = buildDir
                    )
                    MainActivityApkExportPayload(
                        soFiles = resolution.packagedLibraries,
                        executableFile = null,
                        missingLibraries = resolution.missingLibraries,
                        installedLibraryPackageIndex = resolveInstalledLibraryPackageIndex(
                            context = context.applicationContext,
                            missingLibraries = resolution.missingLibraries,
                        ),
                    )
                }
            }
        }
    }
    val resolvedExportPayload = exportPayload
    if (resolvedExportPayload == null) {
        TinaAlertDialog(
            onDismissRequest = state::closeApkPackageDialog,
            title = { TinaDialogTitleText(stringResource(Strings.apk_builder_title)) },
            text = {
                TinaDialogContentColumn {
                    TinaDialogMessageCard(message = stringResource(Strings.loading))
                }
            },
            confirmButton = {
                TinaTextButton(
                    text = stringResource(Strings.action_close),
                    onClick = state::closeApkPackageDialog,
                )
            },
        )
        return
    }
    val outputDir = remember(
        state.showApkPackageDialog,
        projectRoot?.absolutePath,
        buildDir?.absolutePath
    ) {
        if (buildDir != null) {
            File(buildDir, "apk")
        } else {
            File(projectRoot ?: context.filesDir, "build/apk")
        }
    }
    val templateOptions = remember(
        state.showApkPackageDialog,
        state.apkExportType,
        enabledPlugins
    ) {
        when (state.apkExportType) {
            ProjectApkExportType.TERMINAL -> {
                pluginManager.listApkExportOptions(ProjectApkExportType.TERMINAL)
                    .mapNotNull(::toApkExportTemplateOption)
            }

            ProjectApkExportType.SDL3,
            ProjectApkExportType.NATIVE_ACTIVITY,
            ProjectApkExportType.DISABLED,
            null -> {
                listOf(
                    ApkExportTemplateOption(
                        id = BUILTIN_APK_TEMPLATE_NATIVE,
                        label = Strings.apk_builder_template_native.strOr(context),
                        templateType = ApkTemplateType.NATIVE_ACTIVITY
                    ),
                    ApkExportTemplateOption(
                        id = BUILTIN_APK_TEMPLATE_SDL3,
                        label = Strings.apk_builder_template_sdl3.strOr(context),
                        templateType = ApkTemplateType.SDL3
                    )
                )
            }
        }
    }
    val initialTemplateOptionId = when (state.apkExportType) {
        ProjectApkExportType.SDL3 -> BUILTIN_APK_TEMPLATE_SDL3
        ProjectApkExportType.TERMINAL -> templateOptions.firstOrNull()?.id
        ProjectApkExportType.NATIVE_ACTIVITY,
        ProjectApkExportType.DISABLED,
        null -> BUILTIN_APK_TEMPLATE_NATIVE
    }

    LaunchedEffect(state.showApkPackageDialog) {
        if (!state.showApkPackageDialog) return@LaunchedEffect
        packageManager.getAvailablePackages(pageSize = 200)
            .onSuccess { availablePackages = it }
            .onFailure { availablePackages = emptyList() }
    }

    LaunchedEffect(
        state.showApkPackageDialog,
        state.apkExportType,
        templateOptions,
        enabledPlugins
    ) {
        if (!state.showApkPackageDialog) return@LaunchedEffect
        Timber.tag(APK_DIALOG_TAG).i(
            "Showing APK package dialog pluginManager=%s apkExportType=%s templateOptions=%s enabledPlugins=%s",
            pluginManager.instanceId,
            state.apkExportType,
            templateOptions.joinToString(",") { it.id },
            enabledPlugins.joinToString(",") { it.manifest.id }
        )
        pluginLogManager.info(
            com.wuxianggujun.tinaide.plugin.PluginHostLogSources.MainUi,
            "Showing APK package dialog manager=${pluginManager.instanceId} apkExportType=${state.apkExportType} templateOptions=${templateOptions.joinToString(",") { it.id }} enabledPlugins=${enabledPlugins.joinToString(",") { it.manifest.id }}"
        )
    }

    ApkPackageDialog(
        soFiles = resolvedExportPayload.soFiles,
        executableFile = resolvedExportPayload.executableFile,
        projectName = projectName,
        outputDir = outputDir,
        templateOptions = templateOptions,
        initialTemplateOptionId = initialTemplateOptionId,
        missingLibraries = resolvedExportPayload.missingLibraries,
        availablePackages = availablePackages,
        installedLibraryPackageIndex = resolvedExportPayload.installedLibraryPackageIndex,
        onOpenPackageManager = { searchQuery ->
            state.closeApkPackageDialog()
            SettingsActivity.startPackages(context, searchQuery)
        },
        onDismiss = state::closeApkPackageDialog
    )
}

private data class MainActivityApkExportPayload(
    val soFiles: List<File>,
    val executableFile: File?,
    val missingLibraries: List<String>,
    val installedLibraryPackageIndex: Map<String, String>,
)

private fun resolveInstalledLibraryPackageIndex(
    context: android.content.Context,
    missingLibraries: List<String>,
): Map<String, String> = if (missingLibraries.isEmpty()) {
    emptyMap()
} else {
    NativeLibraryDependencyHints.buildInstalledLibraryPackageIndex(context)
}

private fun toApkExportTemplateOption(
    export: ResolvedPluginApkExport
): ApkExportTemplateOption? {
    val templateType = when (export.templateType) {
        "native_activity" -> ApkTemplateType.NATIVE_ACTIVITY
        "sdl3" -> ApkTemplateType.SDL3
        "terminal" -> ApkTemplateType.TERMINAL
        else -> return null
    }
    return ApkExportTemplateOption(
        id = export.optionId,
        label = export.displayName,
        templateType = templateType,
        templateFile = export.templateFile
    )
}

@Composable
internal fun MainActivityUnsavedExitDialog(
    showUnsavedExitDialog: Boolean,
    onShowUnsavedExitDialogChange: (Boolean) -> Unit,
    unsavedCount: Int,
    editorManager: IEditorManager,
    saveScope: CoroutineScope,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    if (!showUnsavedExitDialog) return

    UnsavedChangesOnExitDialog(
        unsavedCount = unsavedCount,
        onSaveAllAndExit = {
            onShowUnsavedExitDialogChange(false)
            saveScope.launch {
                val results = editorManager.saveAll(SaveReason.MANUAL)
                val failures = results.filterIsInstance<SaveResult.Failure>()
                if (failures.isEmpty()) {
                    if (results.isNotEmpty()) {
                        context.toastSuccess(Strings.toast_files_saved.strOr(context, results.size))
                    }
                    onFinish()
                } else {
                    context.toastError(Strings.toast_some_files_save_failed.strOr(context))
                }
            }
        },
        onDiscardAndExit = {
            onShowUnsavedExitDialogChange(false)
            onFinish()
        },
        onDismiss = { onShowUnsavedExitDialogChange(false) }
    )
}
