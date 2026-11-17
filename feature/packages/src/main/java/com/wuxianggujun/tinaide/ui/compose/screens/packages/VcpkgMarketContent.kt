package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.VcpkgPlatformSupport
import com.wuxianggujun.tinaide.core.packages.VcpkgPort
import com.wuxianggujun.tinaide.core.packages.VcpkgProjectCatalog
import com.wuxianggujun.tinaide.core.packages.VcpkgSearchResult
import com.wuxianggujun.tinaide.core.packages.VcpkgSharedLibrary
import com.wuxianggujun.tinaide.core.packages.VcpkgVerifiedPorts
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.ui.compose.components.PluginCardSkeleton
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaEmptyState
import com.wuxianggujun.tinaide.ui.compose.components.TinaErrorState
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPullToRefreshBox
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import org.koin.androidx.compose.koinViewModel

private val CompactVcpkgMaxHeight = 360.dp

/**
 * vcpkg market: a fixed toolbar (catalog status, project selector, refresh/update) over a scrolling
 * catalog, plus a footer that owns the project-level install actions. The search text comes from the
 * shared market top bar; browsing the catalog never requires a selected project.
 */
@Composable
fun VcpkgMarketContent(projects: List<ProjectListItem>, onOpenGuide: () -> Unit, query: String) {
    val viewModel: VcpkgMarketViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 关键词由市场顶栏统一持有；这里只把最新文本交给带防抖的 ViewModel。
    LaunchedEffect(query) { viewModel.search(query) }
    val context = LocalContext.current
    var chooseProject by rememberSaveable { mutableStateOf(false) }
    var pendingDependency by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmManaged by rememberSaveable(state.projectPath) { mutableStateOf(false) }
    var pendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    var detailPortName by rememberSaveable { mutableStateOf<String?>(null) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var confirmShared by rememberSaveable(state.projectPath) { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        owner.lifecycle.addObserver(observer)
        viewModel.onResumed()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    state.notice?.let { notice ->
        val text = stringResource(notice)
        LaunchedEffect(notice) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            viewModel.clearNotice()
        }
    }
    LaunchedEffect(state.installing) { if (state.installing) showLog = true }

    val catalog = state.catalog
    val android = state.androidCatalog
    // 只有「浏览可安装的库」这一个视图：已声明但被兼容性过滤掉的条目（上游删除 / 不适配当前
    // triplet）仍然并入列表，保证它们还有卡片的移除入口。
    val result = remember(android, catalog, state.appliedQuery) {
        val searched = android?.search(state.appliedQuery) ?: VcpkgSearchResult(emptyList())
        val orphans = android?.orphanDependencies(catalog, state.appliedQuery).orEmpty()
        if (orphans.isEmpty()) searched else searched.copy(ports = (searched.ports + orphans).sortedBy { it.name })
    }
    val currentProject = projects.firstOrNull { it.dir.absolutePath == state.projectPath }
    val canModify = projects.isNotEmpty() && catalog?.settings?.mode != VcpkgProjectSettings.SHARED
    val sharedLibrary = catalog?.settings?.let { VcpkgSharedLibrary.usesSharedLibrary(context, it) } == true
    val copier = rememberVcpkgNameCopier()
    val detailPort = detailPortName?.let { name ->
        result.ports.firstOrNull { it.name == name } ?: catalog?.ports?.firstOrNull { it.name == name }
    }

    fun toggleDependency(port: VcpkgPort) {
        when {
            state.projectPath.isBlank() -> {
                pendingDependency = port.name
                chooseProject = true
            }
            port.name in catalog?.declared.orEmpty() -> pendingRemoval = port.name
            else -> viewModel.toggleDependency(port.name, true)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < CompactVcpkgMaxHeight
        Column(modifier = Modifier.fillMaxSize()) {
            VcpkgMarketHeader(
                compact = compact,
                state = state,
                catalog = catalog,
                projectName = currentProject?.displayName,
                projectCount = projects.size,
                onOpenGuide = onOpenGuide,
                onChooseProject = { chooseProject = true },
                onRefreshCatalog = viewModel::refreshCatalog,
                onUpdateCatalog = viewModel::updateCatalog,
                onCancelOperation = viewModel::cancelOperation,
            )
            VcpkgMarketBody(
                state = state,
                catalog = catalog,
                result = result,
                sharedLibrary = sharedLibrary,
                canModify = canModify,
                onRefresh = viewModel::refresh,
                onRefreshCatalog = viewModel::refreshCatalog,
                onClearError = viewModel::clearError,
                onToggleDependency = ::toggleDependency,
                onOpenDetail = { detailPortName = it.name },
                onCopy = { copier(it.name) },
                modifier = Modifier.weight(1f),
            )
            VcpkgMarketFooter(
                compact = compact,
                state = state,
                catalog = catalog,
                onInstall = viewModel::install,
                onInstallToShared = { confirmShared = true },
                onCancelOperation = viewModel::cancelOperation,
                onUseManaged = {
                    if (catalog?.settings?.mode == VcpkgProjectSettings.SHARED) confirmManaged = true
                    else viewModel.useManagedInstallation()
                },
                onOpenLog = { showLog = true },
            )
        }
    }

    if (chooseProject) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.vcpkg_select_project),
            options = projects.map { it.dir.absolutePath to it.displayName },
            selectedValue = state.projectPath,
            onSelected = { path ->
                viewModel.selectProject(path, pendingDependency)
                pendingDependency = null
                chooseProject = false
            },
            onDismiss = { pendingDependency = null; chooseProject = false },
        )
    }
    if (confirmManaged) {
        TinaAlertDialog(
            onDismissRequest = { confirmManaged = false },
            title = { TinaDialogTitleText(stringResource(Strings.vcpkg_use_managed)) },
            text = { Text(stringResource(Strings.vcpkg_shared_leave_message)) },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.vcpkg_use_managed),
                    enabled = !state.busy,
                    onClick = { confirmManaged = false; viewModel.useManagedInstallation() },
                )
            },
            dismissButton = { TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = { confirmManaged = false }) },
        )
    }
    if (confirmShared) {
        TinaAlertDialog(
            onDismissRequest = { confirmShared = false },
            title = { TinaDialogTitleText(stringResource(Strings.vcpkg_shared_confirm_title)) },
            text = { Text(stringResource(Strings.vcpkg_shared_confirm_message)) },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.vcpkg_shared_confirm_action),
                    enabled = !state.busy,
                    onClick = {
                        confirmShared = false
                        viewModel.installToShared()
                    },
                )
            },
            dismissButton = { TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = { confirmShared = false }) },
        )
    }
    pendingRemoval?.let { name ->
        TinaAlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { TinaDialogTitleText(stringResource(Strings.vcpkg_remove_confirm_title)) },
            text = { Text(stringResource(Strings.vcpkg_remove_confirm_message, name)) },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.vcpkg_remove_confirm_action),
                    enabled = !state.busy,
                    onClick = {
                        pendingRemoval = null
                        viewModel.toggleDependency(name, false)
                    },
                )
            },
            dismissButton = { TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = { pendingRemoval = null }) },
        )
    }
    detailPort?.let { port ->
        VcpkgPortDetailDialog(
            port = port,
            declared = port.name in catalog?.declared.orEmpty(),
            installedVersion = catalog?.installed?.get(port.name),
            support = android?.support(port.name) ?: VcpkgPlatformSupport.UNKNOWN,
            verified = VcpkgVerifiedPorts.isVerified(port.name),
            androidAbi = android?.androidAbi.orEmpty(),
            canModify = canModify,
            busy = state.busy,
            onToggleDependency = {
                detailPortName = null
                toggleDependency(port)
            },
            onCopy = { copier(port.name) },
            onDismiss = { detailPortName = null },
        )
    }
    if (showLog) {
        VcpkgInstallLogDialog(output = state.output, running = state.installing, onDismiss = { showLog = false })
    }
}

@Composable
private fun VcpkgMarketHeader(
    compact: Boolean,
    state: VcpkgMarketState,
    catalog: VcpkgProjectCatalog?,
    projectName: String?,
    projectCount: Int,
    onOpenGuide: () -> Unit,
    onChooseProject: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onUpdateCatalog: () -> Unit,
    onCancelOperation: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                VcpkgProjectChooser(
                    projectName = projectName,
                    enabled = projectCount > 0 && !state.busy,
                    onChooseProject = onChooseProject,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.androidCatalog?.let { android ->
                        Text(
                            text = stringResource(Strings.vcpkg_catalog_count, android.ports.size, android.androidAbi),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val hidden = android.totalCount - android.ports.size
                        if (hidden > 0) {
                            Text(
                                text = stringResource(Strings.vcpkg_filter_hidden_hint, android.androidAbi, hidden),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onRefreshCatalog, enabled = !state.busy) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(Strings.vcpkg_refresh),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onUpdateCatalog, enabled = !state.busy) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = stringResource(Strings.vcpkg_update_catalog),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (compact) {
                IconButton(onClick = onOpenGuide) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = stringResource(Strings.vcpkg_open_guide))
                }
            } else {
                TinaTextButton(text = stringResource(Strings.vcpkg_open_guide), onClick = onOpenGuide)
            }
        }
        if (!compact) {
            VcpkgProjectChooser(projectName, projectCount > 0 && !state.busy, onChooseProject, Modifier.fillMaxWidth())
        }
        if (state.projectMissing) {
            Text(
                text = stringResource(Strings.vcpkg_project_missing),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.updating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val progress = state.downloadProgress
                    if (progress != null) {
                        Text(
                            text = stringResource(Strings.vcpkg_preparing_download, (progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = onCancelOperation)
            }
        }
    }
}

@Composable
private fun VcpkgProjectChooser(projectName: String?, enabled: Boolean, onChooseProject: () -> Unit, modifier: Modifier) {
    TinaOutlinedButton(
        text = projectName ?: stringResource(Strings.vcpkg_select_project),
        onClick = onChooseProject,
        enabled = enabled,
        modifier = modifier,
        leadingIcon = Icons.Default.FolderOpen,
    )
}

@Composable
private fun VcpkgMarketBody(
    state: VcpkgMarketState,
    catalog: VcpkgProjectCatalog?,
    result: VcpkgSearchResult,
    sharedLibrary: Boolean,
    canModify: Boolean,
    onRefresh: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onClearError: () -> Unit,
    onToggleDependency: (VcpkgPort) -> Unit,
    onOpenDetail: (VcpkgPort) -> Unit,
    onCopy: (VcpkgPort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().testTag("vcpkg-market-list")) {
        when {
            state.androidCatalog != null -> TinaPullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (sharedLibrary) {
                        item(key = "shared-library") {
                            Text(
                                text = stringResource(Strings.vcpkg_shared_mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.error?.let { error ->
                        item(key = "error") {
                            TinaCard(
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TinaTextButton(text = stringResource(Strings.market_error_retry), onClick = onRefresh)
                                        TinaTextButton(text = stringResource(Strings.btn_close), onClick = onClearError)
                                    }
                                }
                            }
                        }
                    }
                    if (result.ports.isEmpty()) {
                        // Full-height item: the empty state centres inside the visible list area and can adapt
                        // its size instead of pushing the message below the fold on short screens.
                        item(key = "empty") {
                            Box(modifier = Modifier.fillParentMaxHeight()) {
                                VcpkgEmptyContent(
                                    state = state,
                                    result = result,
                                )
                            }
                        }
                    }
                    items(result.ports, key = { it.name }) { port ->
                        VcpkgPortCard(
                            port = port,
                            declared = port.name in catalog?.declared.orEmpty(),
                            installedVersion = catalog?.installed?.get(port.name),
                            support = state.androidCatalog?.support(port.name) ?: VcpkgPlatformSupport.UNKNOWN,
                            verified = VcpkgVerifiedPorts.isVerified(port.name),
                            canModify = canModify,
                            busy = state.busy,
                            onClick = { onOpenDetail(port) },
                            onToggleDependency = { onToggleDependency(port) },
                            onCopy = { onCopy(port) },
                        )
                    }
                }
            }
            state.loading -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { items(5) { PluginCardSkeleton() } }
            else -> TinaErrorState(
                icon = Icons.Default.Inventory,
                errorMessage = state.error ?: stringResource(Strings.vcpkg_operation_failed),
                onRetry = onRefreshCatalog,
            )
        }
    }
}

@Composable
private fun VcpkgEmptyContent(
    state: VcpkgMarketState,
    result: VcpkgSearchResult,
) {
    val hasQuery = state.appliedQuery.isNotBlank()
    when {
        hasQuery && result.incompatible.isNotEmpty() -> {
            val names = result.incompatible.map { it.name }
            val primary = stringResource(
                Strings.vcpkg_empty_incompatible_desc,
                names.take(3).joinToString(", "),
                state.androidCatalog?.androidAbi.orEmpty(),
            )
            val description = if (names.size > 3) {
                primary + "\n" + stringResource(Strings.vcpkg_other_incompatible, names.size - 3)
            } else {
                primary
            }
            TinaEmptyState(
                icon = Icons.Default.Warning,
                title = stringResource(Strings.vcpkg_empty_incompatible_title),
                description = description,
            )
        }
        else -> TinaEmptyState(
            icon = Icons.Default.Search,
            title = stringResource(Strings.market_empty_search_title),
            description = stringResource(Strings.market_empty_search_desc),
        )
    }
}

@Composable
private fun VcpkgMarketFooter(
    compact: Boolean,
    state: VcpkgMarketState,
    catalog: VcpkgProjectCatalog?,
    onInstall: () -> Unit,
    onInstallToShared: () -> Unit,
    onCancelOperation: () -> Unit,
    onUseManaged: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val mode = catalog?.settings?.mode
    val declaredOrInstalled = catalog != null && (catalog.declared.isNotEmpty() || catalog.installed.isNotEmpty())
    val hasActions = state.installing || mode == VcpkgProjectSettings.SHARED || declaredOrInstalled || state.output.isNotEmpty()
    if (compact && !hasActions) return
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.output.isNotEmpty() && !compact) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TinaTextButton(text = stringResource(Strings.vcpkg_install_log_open), onClick = onOpenLog)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.output.isNotEmpty() && compact) {
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Default.Terminal, contentDescription = stringResource(Strings.vcpkg_install_log_open))
                    }
                }
                when {
                    state.installing -> {
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                        TinaOutlinedButton(text = stringResource(Strings.btn_cancel_install), onClick = onCancelOperation)
                    }
                    mode == VcpkgProjectSettings.MANIFEST && declaredOrInstalled -> {
                        TinaOutlinedButton(
                            text = stringResource(Strings.vcpkg_install_shared),
                            onClick = onInstallToShared,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                        TinaPrimaryButton(
                            text = stringResource(Strings.vcpkg_install_project),
                            onClick = onInstall,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    mode == VcpkgProjectSettings.SHARED -> TinaPrimaryButton(
                        text = stringResource(Strings.vcpkg_use_managed),
                        enabled = !state.busy,
                        onClick = onUseManaged,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    mode == VcpkgProjectSettings.DISABLED && declaredOrInstalled -> TinaPrimaryButton(
                        text = stringResource(Strings.vcpkg_use_managed),
                        enabled = !state.busy,
                        onClick = onUseManaged,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    catalog == null -> Text(
                        text = stringResource(Strings.vcpkg_no_project_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        text = stringResource(Strings.vcpkg_add_dependency_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
