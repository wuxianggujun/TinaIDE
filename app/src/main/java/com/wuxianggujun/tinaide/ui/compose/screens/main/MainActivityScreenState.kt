package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.plugin.PluginHostLogSources
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.project.ProjectApkExportSupportResolver
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainViewModel
import com.wuxianggujun.tinaide.ui.compose.components.DebugStatus
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.components.rememberFileTreeState
import com.wuxianggujun.tinaide.ui.compose.components.rememberSwipeableDrawerState
import com.wuxianggujun.tinaide.ui.compose.state.DialogState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorActionsState
import com.wuxianggujun.tinaide.ui.compose.state.editor.rememberEditorActionsState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitDialogState
import com.wuxianggujun.tinaide.ui.compose.state.git.GitUiState
import com.wuxianggujun.tinaide.ui.compose.state.git.rememberGitDialogState
import com.wuxianggujun.tinaide.ui.compose.state.git.rememberGitUiState
import com.wuxianggujun.tinaide.ui.compose.state.rememberDialogState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Stable
internal data class MainActivityScreenUiState(
    val projectName: String,
    val isCompiling: Boolean,
    val isDirty: Boolean,
    val cursorLine: Int,
    val cursorColumn: Int,
    val fileEncoding: String,
    val isDebugActive: Boolean,
    val debugStatus: DebugStatus,
    val gitUiState: GitUiState,
)

@Stable
internal data class MainActivityProjectSnapshot(
    val rootPath: String?,
    val buildDirPath: String?,
    val projectRoot: File?,
    val buildDir: File?,
)

@Stable
internal data class MainActivityMainScreenState(
    val drawerState: SwipeableDrawerState,
    val scope: CoroutineScope,
    val projectSnapshot: MainActivityProjectSnapshot,
    val gitDialogState: GitDialogState,
    val editorActionsState: EditorActionsState,
    val locationDialogState: MainActivityLocationDialogState,
    val buildUiState: MainActivityBuildUiState,
    val screenUiState: MainActivityScreenUiState,
    val fileTreeState: FileTreeState,
    val dialogState: DialogState,
)

@Stable
internal class MainActivityBuildUiState(
    initialRunConfigManager: RunConfigurationManager,
) {
    var showRunConfigDialog by mutableStateOf(false)
        private set

    var showApkPackageDialog by mutableStateOf(false)
        private set

    var apkExportType by mutableStateOf<ProjectApkExportType?>(null)
        internal set

    var hasTerminalApkExportOptions by mutableStateOf(false)
        internal set

    var runConfigManager by mutableStateOf(initialRunConfigManager)
        private set

    var editingConfig by mutableStateOf<RunConfiguration?>(null)
        private set

    var currentBuildSystem by mutableStateOf(BuildSystem.UNKNOWN)
        internal set

    var availableTargets by mutableStateOf<List<TargetInfo>>(emptyList())
        internal set

    fun openRunConfigDialog(config: RunConfiguration? = editingConfig ?: runConfigManager.selectedConfig) {
        editingConfig = config
        showRunConfigDialog = true
    }

    fun closeRunConfigDialog() {
        showRunConfigDialog = false
        editingConfig = null
    }

    fun startEditingConfig(config: RunConfiguration?) {
        editingConfig = config
    }

    fun commitRunConfigManager(
        updated: RunConfigurationManager,
        persist: (RunConfigurationManager) -> Boolean,
    ): Boolean {
        if (!persist(updated)) return false
        runConfigManager = updated
        return true
    }

    fun openApkPackageDialog() {
        showApkPackageDialog = true
    }

    fun closeApkPackageDialog() {
        showApkPackageDialog = false
    }
}

@Composable
internal fun rememberMainActivityProjectSnapshot(
    projectContext: IProjectContext,
): MainActivityProjectSnapshot {
    val currentProject = projectContext.getCurrentProject()
    val rootPath = currentProject?.rootPath
    val buildDirPath = currentProject?.buildDirPath

    return remember(rootPath, buildDirPath) {
        MainActivityProjectSnapshot(
            rootPath = rootPath,
            buildDirPath = buildDirPath,
            projectRoot = rootPath?.let(::File),
            buildDir = buildDirPath?.let(::File),
        )
    }
}

@Composable
internal fun rememberMainActivityScreenUiState(
    mainViewModel: MainViewModel,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    gitViewModel: GitViewModel,
): MainActivityScreenUiState {
    val projectName by mainViewModel.projectName.collectAsStateWithLifecycle()
    val isCompiling by mainViewModel.isCompiling.collectAsStateWithLifecycle()
    val isDirty by editorStateViewModel.isDirty.collectAsStateWithLifecycle()
    val cursorLine by editorStateViewModel.cursorLine.collectAsStateWithLifecycle()
    val cursorColumn by editorStateViewModel.cursorColumn.collectAsStateWithLifecycle()
    val fileEncoding by editorStateViewModel.fileEncoding.collectAsStateWithLifecycle()
    val isDebugActive by debugViewModel.isActive.collectAsStateWithLifecycle()
    val debugStatus by debugViewModel.debugStatus.collectAsStateWithLifecycle()
    val gitUiState = rememberGitUiState(gitViewModel)

    return remember(
        projectName,
        isCompiling,
        isDirty,
        cursorLine,
        cursorColumn,
        fileEncoding,
        isDebugActive,
        debugStatus,
        gitUiState,
    ) {
        MainActivityScreenUiState(
            projectName = projectName,
            isCompiling = isCompiling,
            isDirty = isDirty,
            cursorLine = cursorLine,
            cursorColumn = cursorColumn,
            fileEncoding = fileEncoding,
            isDebugActive = isDebugActive,
            debugStatus = debugStatus,
            gitUiState = gitUiState,
        )
    }
}

@Composable
internal fun rememberMainActivityBuildUiState(
    initialRunConfigManager: RunConfigurationManager,
    currentProjectRootPath: String?,
    currentProjectBuildDirPath: String?,
    detectBuildSystem: () -> BuildSystem,
    loadAvailableTargets: suspend () -> List<TargetInfo>,
): MainActivityBuildUiState {
    val logTag = "MainActivityBuildUiState"
    val state = remember { MainActivityBuildUiState(initialRunConfigManager) }
    val context = LocalContext.current
    val pluginLogManager = remember(context) { PluginLogManager.getInstance(context.applicationContext) }
    val pluginManager = remember(context) { PluginManager.getInstance(context.applicationContext) }
    val enabledPlugins by pluginManager.enabledPluginsFlow.collectAsStateWithLifecycle()
    val latestDetectBuildSystem by rememberUpdatedState(detectBuildSystem)
    val latestLoadAvailableTargets by rememberUpdatedState(loadAvailableTargets)

    LaunchedEffect(currentProjectRootPath, state.showRunConfigDialog) {
        if (currentProjectRootPath != null) {
            state.currentBuildSystem = latestDetectBuildSystem()
            state.availableTargets = if (state.currentBuildSystem == BuildSystem.CMAKE) {
                latestLoadAvailableTargets()
            } else {
                emptyList()
            }
        } else {
            state.currentBuildSystem = BuildSystem.UNKNOWN
            state.availableTargets = emptyList()
        }
    }

    LaunchedEffect(currentProjectRootPath, currentProjectBuildDirPath, enabledPlugins) {
        if (currentProjectRootPath == null) {
            state.apkExportType = null
            state.hasTerminalApkExportOptions = false
            Timber.tag(logTag).i(
                "Cleared APK export state pluginManager=%s because no project is open",
                pluginManager.instanceId
            )
            pluginLogManager.debug(
                PluginHostLogSources.MainUi,
                "Cleared APK export state because no project is open manager=${pluginManager.instanceId}"
            )
        } else {
            val (apkExportType, hasTerminalApkExportOptions) = withContext(Dispatchers.IO) {
                val detectedExportType = ProjectApkExportSupportResolver.ensureDetected(
                    projectRoot = File(currentProjectRootPath),
                    buildDir = currentProjectBuildDirPath?.let(::File)
                )
                val terminalOptionsAvailable =
                    pluginManager.listApkExportOptions(ProjectApkExportType.TERMINAL).isNotEmpty()
                detectedExportType to terminalOptionsAvailable
            }
            state.apkExportType = apkExportType
            state.hasTerminalApkExportOptions = hasTerminalApkExportOptions
            Timber.tag(logTag).i(
                "Recomputed APK export state pluginManager=%s project=%s apkExportType=%s terminalOptions=%s enabledPlugins=%s",
                pluginManager.instanceId,
                currentProjectRootPath,
                apkExportType,
                hasTerminalApkExportOptions,
                enabledPlugins.joinToString(",") { it.manifest.id }
            )
            pluginLogManager.info(
                PluginHostLogSources.MainUi,
                "Recomputed APK export state manager=${pluginManager.instanceId} project=$currentProjectRootPath apkExportType=$apkExportType terminalOptions=$hasTerminalApkExportOptions enabledPlugins=${enabledPlugins.joinToString(",") { it.manifest.id }}"
            )
        }
    }

    return state
}

@Composable
internal fun rememberMainActivityMainScreenState(
    drawerWidth: Dp,
    projectContext: IProjectContext,
    initialRunConfigManager: RunConfigurationManager,
    detectBuildSystem: () -> BuildSystem,
    loadAvailableTargets: suspend () -> List<TargetInfo>,
    mainViewModel: MainViewModel,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    gitViewModel: GitViewModel,
): MainActivityMainScreenState {
    val drawerState = rememberSwipeableDrawerState(drawerWidth = drawerWidth)
    val scope = rememberCoroutineScope()
    val projectSnapshot = rememberMainActivityProjectSnapshot(projectContext)
    val gitDialogState = rememberGitDialogState()
    val editorActionsState = rememberEditorActionsState()
    val locationDialogState = rememberMainActivityLocationDialogState()
    val buildUiState = rememberMainActivityBuildUiState(
        initialRunConfigManager = initialRunConfigManager,
        currentProjectRootPath = projectSnapshot.rootPath,
        currentProjectBuildDirPath = projectSnapshot.buildDirPath,
        detectBuildSystem = detectBuildSystem,
        loadAvailableTargets = loadAvailableTargets,
    )
    val screenUiState = rememberMainActivityScreenUiState(
        mainViewModel = mainViewModel,
        editorStateViewModel = editorStateViewModel,
        debugViewModel = debugViewModel,
        gitViewModel = gitViewModel,
    )
    val fileTreeState = rememberFileTreeState()
    val dialogState = rememberDialogState()

    return remember(
        drawerState,
        scope,
        projectSnapshot,
        gitDialogState,
        editorActionsState,
        locationDialogState,
        buildUiState,
        screenUiState,
        fileTreeState,
        dialogState,
    ) {
        MainActivityMainScreenState(
            drawerState = drawerState,
            scope = scope,
            projectSnapshot = projectSnapshot,
            gitDialogState = gitDialogState,
            editorActionsState = editorActionsState,
            locationDialogState = locationDialogState,
            buildUiState = buildUiState,
            screenUiState = screenUiState,
            fileTreeState = fileTreeState,
            dialogState = dialogState,
        )
    }
}
