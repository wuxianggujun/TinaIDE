package com.wuxianggujun.tinaide.ui.compose.screens.main.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.config.NewProjectSourceLocation
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.git.GitService
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.extensions.handleErrorWithToast
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.extensions.toastSuccess
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.storage.PermissionStatus
import com.wuxianggujun.tinaide.storage.ProjectExporter
import com.wuxianggujun.tinaide.storage.ProjectImporter
import com.wuxianggujun.tinaide.storage.ProjectLocationManager
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.storage.StorageManager
import com.wuxianggujun.tinaide.storage.compose.rememberStoragePermissionRequester
import com.wuxianggujun.tinaide.tutorial.SpotlightUiState
import com.wuxianggujun.tinaide.tutorial.spotlight.SpotlightTargets
import com.wuxianggujun.tinaide.ui.ProjectManagerViewModel
import com.wuxianggujun.tinaide.ui.UiMessageException
import com.wuxianggujun.tinaide.ui.compose.components.ProjectCardSkeleton
import com.wuxianggujun.tinaide.ui.compose.components.TinaActionChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaExpandableFab
import com.wuxianggujun.tinaide.ui.compose.components.TinaPullToRefreshBox
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.projectlist.DeleteConfirmDialog
import com.wuxianggujun.tinaide.ui.projectlist.EmptyProjectsView
import com.wuxianggujun.tinaide.ui.projectlist.GitCloneDialog
import com.wuxianggujun.tinaide.ui.projectlist.ProjectAction
import com.wuxianggujun.tinaide.ui.projectlist.ProjectInfoDialog
import com.wuxianggujun.tinaide.ui.projectlist.ProjectListCard
import com.wuxianggujun.tinaide.ui.projectlist.RenameProjectDialog
import com.wuxianggujun.tinaide.ui.projectlist.SearchBox
import com.wuxianggujun.tinaide.ui.projectlist.SectionHeader
import com.wuxianggujun.tinaide.ui.wizard.NewProjectWizardActivity
import com.wuxianggujun.tinaide.update.AppUpdateInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import timber.log.Timber

private enum class ManagedProjectActionType {
    IMPORT_LOCAL,
    IMPORT_FROM_GIT,
}

private data class ManagedProjectAction(
    val type: ManagedProjectActionType,
    val sourceLocation: NewProjectSourceLocation
)

private sealed interface PendingProjectPermissionAction {
    data class Managed(val action: ManagedProjectAction) : PendingProjectPermissionAction
    data class OpenProject(val projectPath: String) : PendingProjectPermissionAction
    object RefreshProjectList : PendingProjectPermissionAction
}

/**
 * 项目屏幕（MainPortal 底部导航 - 项目页）
 *
 * 复用 ProjectManager 的数据与项目卡片交互（字母头像、菜单操作等）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    modifier: Modifier = Modifier,
    onNewProjectClick: () -> Unit = {},
    onProjectClick: (String) -> Unit = {},
    spotlightUiState: SpotlightUiState? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val projectLocationManager: ProjectLocationManager = koinInject()
    val storageManager: StorageManager = koinInject()
    val gitService: GitService = koinInject()
    val viewModel: ProjectManagerViewModel = koinViewModel()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf<ProjectListItem?>(null) }
    var showInfoDialog by remember { mutableStateOf<ProjectListItem?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<ProjectListItem?>(null) }
    var localImportSourceLocation by remember { mutableStateOf<NewProjectSourceLocation?>(null) }
    var localImportPickerSourceLocation by remember { mutableStateOf<NewProjectSourceLocation?>(null) }
    var gitCloneSourceLocation by remember { mutableStateOf<NewProjectSourceLocation?>(null) }
    var sourceLocationSelectionAction by remember { mutableStateOf<ManagedProjectActionType?>(null) }
    var cloneProgressText by remember { mutableStateOf<String?>(null) }
    var pendingProjectPermissionAction by remember { mutableStateOf<PendingProjectPermissionAction?>(null) }

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val appUpdateInfo by viewModel.appUpdateInfo.collectAsStateWithLifecycle()
    val permissionStatus by storageManager.permissionStatus.collectAsStateWithLifecycle()
    val hasExternalStoragePermission = permissionStatus == PermissionStatus.GRANTED

    // 仅在首次进入时加载项目列表，避免切换页面时重复刷新
    var hasInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasInitialized) {
        if (!hasInitialized) {
            viewModel.reloadProjects()
            hasInitialized = true
        }
    }

    // 监听生命周期，当页面重新可见时刷新项目列表 + 权限状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // 用户可能去系统设置改过权限 —— 主动同步
                storageManager.refreshPermissionStatus()
                if (hasInitialized) {
                    // 页面恢复时刷新项目列表（例如从项目编辑器返回）
                    viewModel.reloadProjects()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val filteredProjects = remember(projects, searchQuery) {
        if (searchQuery.isBlank()) {
            projects
        } else {
            projects.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        }
    }
    val (recentProjects, otherProjects) = remember(filteredProjects) {
        val recent = filteredProjects
            .filter { (it.lastOpenedAt ?: 0L) > 0L }
            .sortedByDescending { it.lastOpenedAt ?: 0L }
            .take(3)

        val recentKeys = recent.asSequence().map { it.dir.absolutePath }.toSet()
        val other = filteredProjects
            .filterNot { it.dir.absolutePath in recentKeys }
            .sortedWith(compareBy<ProjectListItem>({ it.displayName.lowercase() }, { it.dir.name.lowercase() }))

        recent to other
    }

    // FAB 展开状态
    var isFabExpanded by remember { mutableStateOf(false) }
    val fabRotation by animateFloatAsState(
        targetValue = if (isFabExpanded) 45f else 0f,
        label = "fab_rotation"
    )

    // 当引导步骤需要显示 FAB 菜单项时，自动展开菜单以保证目标可被定位到
    LaunchedEffect(spotlightUiState?.isVisible, spotlightUiState?.currentStep?.targetId) {
        if (spotlightUiState?.isVisible != true) return@LaunchedEffect
        if (spotlightUiState.currentStep?.targetId == SpotlightTargets.FAB_MENU_NEW_PROJECT) {
            isFabExpanded = true
        }
    }

    // SAF 目录选择器（从本地导入）
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val activeSourceLocation = localImportPickerSourceLocation
        localImportPickerSourceLocation = null
        uri?.let {
            val projectsRoot = resolveManagedProjectsRoot(
                context = context,
                sourceLocation = activeSourceLocation ?: Prefs.projectDefaultSourceLocation
            )
            coroutineScope.launch {
                importDirectoryFromLocal(
                    context = context,
                    viewModel = viewModel,
                    uri = it,
                    projectsRoot = projectsRoot,
                    projectLocationManager = projectLocationManager,
                    storageManager = storageManager
                )
            }
        }
    }

    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val activeSourceLocation = localImportPickerSourceLocation
        localImportPickerSourceLocation = null
        uri?.let {
            val projectsRoot = resolveManagedProjectsRoot(
                context = context,
                sourceLocation = activeSourceLocation ?: Prefs.projectDefaultSourceLocation
            )
            coroutineScope.launch {
                importArchiveFromLocal(
                    context = context,
                    viewModel = viewModel,
                    uri = it,
                    projectsRoot = projectsRoot,
                    projectLocationManager = projectLocationManager
                )
            }
        }
    }

    // 新建项目向导启动器
    val newProjectWizardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // 刷新项目列表
            viewModel.reloadProjects()

            val projectPath = result.data?.getStringExtra(NewProjectWizardActivity.EXTRA_PROJECT_PATH)
            if (!projectPath.isNullOrBlank()) {
                onProjectClick(projectPath)
            }
        }
    }

    fun performManagedProjectAction(action: ManagedProjectAction) {
        when (action.type) {
            ManagedProjectActionType.IMPORT_LOCAL -> localImportSourceLocation = action.sourceLocation
            ManagedProjectActionType.IMPORT_FROM_GIT -> gitCloneSourceLocation = action.sourceLocation
        }
    }

    fun performPendingProjectPermissionAction(action: PendingProjectPermissionAction) {
        when (action) {
            is PendingProjectPermissionAction.Managed -> performManagedProjectAction(action.action)
            is PendingProjectPermissionAction.OpenProject -> onProjectClick(action.projectPath)
            PendingProjectPermissionAction.RefreshProjectList -> Unit
        }
    }

    val permissionRequester = rememberStoragePermissionRequester(storageManager = storageManager) { granted ->
        val action = pendingProjectPermissionAction
        pendingProjectPermissionAction = null
        if (action == null) return@rememberStoragePermissionRequester
        if (granted) {
            viewModel.reloadProjects()
            performPendingProjectPermissionAction(action)
        } else {
            context.toastError(Strings.permission_storage_settings.strOr(context))
        }
    }

    fun launchProjectPermissionRequest(action: PendingProjectPermissionAction) {
        pendingProjectPermissionAction = action
        permissionRequester.request()
    }

    fun requestOpenProject(project: ProjectListItem) {
        if (!ProjectPaths.isUnderPublicProjectsRoot(context, project.dir) || hasExternalStoragePermission) {
            onProjectClick(project.dir.absolutePath)
            return
        }
        launchProjectPermissionRequest(
            PendingProjectPermissionAction.OpenProject(project.dir.absolutePath)
        )
    }

    fun requestManagedProjectAction(action: ManagedProjectAction) {
        val projectRoot = resolveManagedProjectsRoot(context, action.sourceLocation)
        if (!ProjectPaths.isUnderPublicProjectsRoot(context, projectRoot)) {
            performManagedProjectAction(action)
            return
        }
        if (hasExternalStoragePermission) {
            performManagedProjectAction(action)
            return
        }
        launchProjectPermissionRequest(PendingProjectPermissionAction.Managed(action))
    }

    fun handleAction(project: ProjectListItem, action: ProjectAction) {
        when (action) {
            ProjectAction.OPEN -> requestOpenProject(project)
            ProjectAction.RENAME -> showRenameDialog = project
            ProjectAction.EXPORT -> {
                val projectName = project.dir.name
                context.toastSuccess(Strings.toast_exporting.strOr(context))
                coroutineScope.launch {
                    val result = ProjectExporter.exportProject(context = context, projectDir = project.dir)
                    when (result) {
                        is ProjectExporter.ExportResult.Success -> {
                            runCatching {
                                ProjectExporter.shareZipFile(
                                    context = context,
                                    zipFile = result.zipFile,
                                    projectName = projectName
                                )
                            }.onFailure { e ->
                                context.toastError(Strings.toast_share_failed.strOr(context, e.message ?: ""))
                            }
                        }

                        is ProjectExporter.ExportResult.Error -> {
                            context.toastError(Strings.toast_share_failed.strOr(context, result.message))
                        }
                    }
                }
            }

            ProjectAction.INFO -> showInfoDialog = project
            ProjectAction.SETTINGS -> SettingsActivity.startForProject(
                context = context,
                projectRootPath = project.dir.absolutePath
            )
            ProjectAction.DELETE -> showDeleteConfirmDialog = project
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TinaTopBar(
                    title = stringResource(Strings.nav_project)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 固定顶部区域（不滚动）：搜索框
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SearchBox(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(Strings.hint_search_project)
                    )

                    // Clone 进度指示
                    cloneProgressText?.let { progressText ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 可滚动项目列表 + 下拉刷新
                val pullToRefreshState = rememberPullToRefreshState()

                // 删除进行中的顶部进度条（与下拉刷新指示器区分开）
                if (isDeleting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // 首次加载显示骨架屏
                if (!hasInitialized || (isRefreshing && projects.isEmpty())) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(5) {
                            ProjectCardSkeleton()
                        }
                    }
                } else {
                    TinaPullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.reloadProjects() },
                        state = pullToRefreshState,
                        enableHapticFeedback = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!hasExternalStoragePermission) {
                                item {
                                    PublicProjectsPermissionCard(
                                        onGrantPermission = {
                                            launchProjectPermissionRequest(
                                                PendingProjectPermissionAction.RefreshProjectList
                                            )
                                        }
                                    )
                                }
                            }

                            if (recentProjects.isNotEmpty()) {
                                item {
                                    SectionHeader(title = stringResource(Strings.section_recent_projects))
                                }
                                items(recentProjects, key = { it.dir.absolutePath }) { project ->
                                    ProjectListCard(
                                        project = project,
                                        onClick = { requestOpenProject(project) },
                                        onAction = { action -> handleAction(project, action) }
                                    )
                                }
                            }

                            if (otherProjects.isNotEmpty()) {
                                item {
                                    SectionHeader(title = stringResource(Strings.section_other_projects))
                                }
                                items(otherProjects, key = { it.dir.absolutePath }) { project ->
                                    ProjectListCard(
                                        project = project,
                                        onClick = { requestOpenProject(project) },
                                        onAction = { action -> handleAction(project, action) }
                                    )
                                }
                            }

                            if (filteredProjects.isEmpty()) {
                                item {
                                    EmptyProjectsView(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 48.dp)
                                    )
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(100.dp)) // FAB space
                            }
                        }
                    }
                }
            }
        }

        // 点击背景关闭 FAB 菜单遮罩层
        if (isFabExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { isFabExpanded = false }
            )
        }

        TinaExpandableFab(
            isExpanded = isFabExpanded,
            onExpandedChange = { isFabExpanded = it },
            rotation = fabRotation,
            onNewProject = {
                isFabExpanded = false
                newProjectWizardLauncher.launch(NewProjectWizardActivity.createIntent(context))
            },
            onImportFromGit = {
                isFabExpanded = false
                sourceLocationSelectionAction = ManagedProjectActionType.IMPORT_FROM_GIT
            },
            onImportFromLocal = {
                isFabExpanded = false
                sourceLocationSelectionAction = ManagedProjectActionType.IMPORT_LOCAL
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .zIndex(2f)
                .padding(end = 16.dp, bottom = 16.dp)
        )
    }

    sourceLocationSelectionAction?.let { actionType ->
        TinaSingleChoiceDialog(
            title = stringResource(resolveManagedProjectActionTitleRes(actionType)),
            options = NewProjectSourceLocation.entries.map { location ->
                location.value to resolveProjectSourceLocationLabelRes(location).strOr(context)
            },
            selectedValue = Prefs.projectDefaultSourceLocation.value,
            onSelected = { value ->
                sourceLocationSelectionAction = null
                requestManagedProjectAction(
                    ManagedProjectAction(
                        type = actionType,
                        sourceLocation = NewProjectSourceLocation.fromValue(value)
                    )
                )
            },
            onDismiss = { sourceLocationSelectionAction = null }
        )
    }

    // Git 克隆对话框
    gitCloneSourceLocation?.let { activeSourceLocation ->
        GitCloneDialog(
            defaultPath = resolveManagedProjectsRoot(context, activeSourceLocation).absolutePath,
            onDismiss = { gitCloneSourceLocation = null },
            onClone = { url, projectName, branch ->
                gitCloneSourceLocation = null
                coroutineScope.launch {
                    cloneFromGit(
                        context = context,
                        viewModel = viewModel,
                        gitService = gitService,
                        storageManager = storageManager,
                        projectsRoot = resolveManagedProjectsRoot(context, activeSourceLocation),
                        url = url,
                        projectName = projectName,
                        branch = branch,
                        onProgressChange = { cloneProgressText = it }
                    )
                }
            }
        )
    }

    localImportSourceLocation?.let { activeSourceLocation ->
        TinaActionChoiceDialog(
            title = stringResource(Strings.dialog_title_local_import),
            message = stringResource(
                Strings.dialog_message_local_import,
                resolveManagedProjectsRoot(context, activeSourceLocation).absolutePath
            ),
            actions = listOf(
                stringResource(Strings.action_import_folder) to {
                    localImportPickerSourceLocation = activeSourceLocation
                    localImportSourceLocation = null
                    directoryPickerLauncher.launch(null)
                },
                stringResource(Strings.action_import_archive) to {
                    localImportPickerSourceLocation = activeSourceLocation
                    localImportSourceLocation = null
                    archivePickerLauncher.launch(arrayOf("*/*"))
                }
            ),
            onDismiss = { localImportSourceLocation = null }
        )
    }

    // 应用更新提示弹窗
    appUpdateInfo?.let { updateInfo ->
        AppUpdateDialog(
            updateInfo = updateInfo,
            onDismiss = { viewModel.dismissAppUpdate(updateInfo) },
            onDownload = {
                openAppUpdateUrl(context, updateInfo)
                viewModel.clearAppUpdatePrompt()
            },
        )
    }

    showRenameDialog?.let { project ->
        RenameProjectDialog(
            currentName = project.displayName,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                showRenameDialog = null
                viewModel.renameProject(project.dir, newName) { result ->
                    result
                        .onSuccess { context.toastSuccess(Strings.toast_project_renamed.strOr(context)) }
                        .onFailure { e ->
                            when (e) {
                                is UiMessageException -> context.toastError(e.messageResId.strOr(context))
                                else -> context.handleErrorWithToast(e, Strings.toast_rename_failed.strOr(context))
                            }
                        }
                }
            }
        )
    }

    showInfoDialog?.let { project ->
        ProjectInfoDialog(
            project = project,
            onDismiss = { showInfoDialog = null }
        )
    }

    showDeleteConfirmDialog?.let { project ->
        DeleteConfirmDialog(
            projectName = project.displayName,
            confirmMessage = stringResource(Strings.dialog_confirm_delete, project.displayName),
            warningMessage = stringResource(Strings.dialog_delete_warning),
            confirmButtonText = stringResource(Strings.btn_delete),
            onDismiss = { showDeleteConfirmDialog = null },
            onConfirm = { cancellationSignal, onProgress ->
                viewModel.deleteProject(project, cancellationSignal, onProgress)
            },
            onDeleted = {
                showDeleteConfirmDialog = null
                context.toastSuccess(Strings.toast_project_deleted.strOr(context))
            },
        )
    }
}

private fun openAppUpdateUrl(
    context: Context,
    updateInfo: AppUpdateInfo,
) {
    val url = updateInfo.downloadUrl.takeIf(String::isNotBlank) ?: updateInfo.releasePageUrl
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        context.toastError(Strings.app_update_open_failed.strOr(context))
    }
}

@Composable
private fun PublicProjectsPermissionCard(
    onGrantPermission: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(Strings.permission_storage_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(Strings.project_public_projects_permission_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onGrantPermission,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(text = stringResource(Strings.permission_btn_grant))
            }
        }
    }
}

private suspend fun importDirectoryFromLocal(
    context: Context,
    viewModel: ProjectManagerViewModel,
    uri: Uri,
    projectsRoot: File,
    projectLocationManager: ProjectLocationManager,
    storageManager: StorageManager
) {
    context.toastInfo(Strings.toast_importing.strOr(context))
    ProjectImporter.importDirectory(
        context = context,
        uri = uri,
        projectsRoot = projectsRoot,
        projectLocationManager = projectLocationManager,
        storageManager = storageManager
    )
        .onSuccess {
            context.toastSuccess(Strings.toast_import_success.strOr(context))
            viewModel.reloadProjects()
        }
        .onFailure { e ->
            context.toastError(e.message ?: Strings.toast_import_failed.strOr(context))
        }
}

private suspend fun importArchiveFromLocal(
    context: Context,
    viewModel: ProjectManagerViewModel,
    uri: Uri,
    projectsRoot: File,
    projectLocationManager: ProjectLocationManager
) {
    context.toastInfo(Strings.toast_importing.strOr(context))
    ProjectImporter.importArchive(
        context = context,
        uri = uri,
        projectsRoot = projectsRoot,
        projectLocationManager = projectLocationManager
    ).onSuccess {
        context.toastSuccess(Strings.toast_import_success.strOr(context))
        viewModel.reloadProjects()
    }.onFailure { e ->
        context.toastError(e.message ?: Strings.toast_import_failed.strOr(context))
    }
}

private suspend fun cloneFromGit(
    context: Context,
    viewModel: ProjectManagerViewModel,
    gitService: GitService,
    storageManager: StorageManager,
    projectsRoot: File,
    url: String,
    projectName: String,
    branch: String,
    onProgressChange: (String?) -> Unit
) {
    val targetDir = File(projectsRoot, projectName)
    if (!hasPublicSourceAccess(context, storageManager, targetDir)) {
        context.toastError(Strings.permission_storage_settings.strOr(context))
        return
    }
    if (targetDir.exists()) {
        context.toastError(Strings.error_project_name_exists.strOr(context))
        return
    }

    onProgressChange(Strings.toast_cloning.strOr(context))

    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    val result = try {
        withContext(Dispatchers.IO) {
            gitService.cloneRepository(
                url = url,
                destinationPath = targetDir.absolutePath,
                branch = branch.takeIf { it.isNotBlank() },
                onProgress = { progressText ->
                    mainHandler.post { onProgressChange(progressText) }
                }
            )
        }
    } catch (t: Throwable) {
        if (t is CancellationException) {
            cleanupFailedCloneTarget(targetDir)
            throw t
        }
        com.wuxianggujun.tinaide.core.git.GitResult.Error(
            t.message?.trim()?.takeIf { it.isNotEmpty() }
                ?: t.cause?.message?.trim()?.takeIf { it.isNotEmpty() }
                ?: Strings.git_error_clone_failed.strOr(context)
        )
    } finally {
        onProgressChange(null)
    }

    when (result) {
        is com.wuxianggujun.tinaide.core.git.GitResult.Success -> {
            context.toastSuccess(Strings.toast_clone_success.strOr(context))
            viewModel.reloadProjects()
        }
        is com.wuxianggujun.tinaide.core.git.GitResult.Error -> {
            cleanupFailedCloneTarget(targetDir)
            context.toastError(result.message)
        }
    }
}

internal suspend fun cleanupFailedCloneTarget(
    targetDir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean = withContext(NonCancellable + ioDispatcher) {
    runCatching {
        !targetDir.exists() || targetDir.deleteRecursively()
    }.onFailure { error ->
        Timber.tag("ProjectGitClone").w(
            error,
            "Failed to clean partial clone directory: %s",
            targetDir.absolutePath,
        )
    }.getOrDefault(false)
}

private fun resolveManagedProjectsRoot(
    context: Context,
    sourceLocation: NewProjectSourceLocation
): File = when (sourceLocation) {
    NewProjectSourceLocation.PUBLIC -> ProjectPaths.getPublicProjectsRoot(context)
    NewProjectSourceLocation.PRIVATE -> ProjectPaths.getPrivateProjectsRoot(context)
}

private fun resolveManagedProjectActionTitleRes(actionType: ManagedProjectActionType): Int = when (actionType) {
    ManagedProjectActionType.IMPORT_LOCAL -> Strings.dialog_title_select_import_location
    ManagedProjectActionType.IMPORT_FROM_GIT -> Strings.dialog_title_select_clone_location
}

private fun resolveProjectSourceLocationLabelRes(location: NewProjectSourceLocation): Int = when (location) {
    NewProjectSourceLocation.PUBLIC -> Strings.project_source_location_public
    NewProjectSourceLocation.PRIVATE -> Strings.project_source_location_private
}

private fun hasPublicSourceAccess(
    context: Context,
    storageManager: StorageManager,
    targetDir: File
): Boolean {
    if (!ProjectPaths.isUnderPublicProjectsRoot(context, targetDir)) {
        return true
    }
    return storageManager.hasExternalStoragePermission()
}
