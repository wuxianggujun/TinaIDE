

package com.wuxianggujun.tinaide.ui.compose.screens.main.market

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.plugin.marketplace.PluginDetail
import com.wuxianggujun.tinaide.plugin.marketplace.PluginMarketplaceSelectionSupport
import com.wuxianggujun.tinaide.plugin.marketplace.PluginSummary
import com.wuxianggujun.tinaide.plugin.marketplace.PluginVersion
import com.wuxianggujun.tinaide.ui.compose.components.BadgeStatus
import com.wuxianggujun.tinaide.ui.compose.components.PluginCardSkeleton
import com.wuxianggujun.tinaide.ui.compose.components.PluginPermissionDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaEmptyState
import com.wuxianggujun.tinaide.ui.compose.components.TinaErrorState
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaInlineSearchField
import com.wuxianggujun.tinaide.ui.compose.components.TinaPullToRefreshBox
import com.wuxianggujun.tinaide.ui.compose.components.TinaStatusBadge
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.GitHubRegistryProxyDialog
import org.koin.androidx.compose.koinViewModel

private val LeadingMarketTabsMinWidth = 480.dp

/**
 * 市场主屏幕
 *
 * 市场主屏幕：插件市场、包管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    modifier: Modifier = Modifier,
    viewModel: MarketScreenViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val permissionDeniedText = stringResource(Strings.toast_plugins_permission_denied)
    val proxySavedText = stringResource(Strings.github_registry_proxy_save_ok)
    var showProxyDialog by rememberSaveable { mutableStateOf(false) }
    // 默认「插件」；包管理为第二 Tab，不抢首屏
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    // 搜索由顶栏统一持有：三个 Tab 共用一份关键词，切换 Tab 不丢
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchActive = searchExpanded || searchQuery.isNotBlank()
    var searchFocusPending by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val collapseSearch: () -> Unit = {
        searchQuery = ""
        searchExpanded = false
        keyboardController?.hide()
    }

    val pluginState by viewModel.pluginState.collectAsStateWithLifecycle()
    val packageState by viewModel.packageState.collectAsStateWithLifecycle()
    val closePluginDetails = viewModel::closePluginDetails
    val selectedPlugin = PluginMarketplaceSelectionSupport.resolveSelectedPlugin(
        selectedPluginId = pluginState.selectedPluginId,
        plugins = pluginState.plugins,
    )

    // 显示安装结果提示
    LaunchedEffect(pluginState.message) {
        pluginState.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearPluginMessage()
        }
    }

    LaunchedEffect(packageState.message) {
        packageState.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearPackageMessage()
        }
    }

    TinaBackHandlers(
        tinaBackAction(enabled = searchActive && selectedPlugin == null, onBack = collapseSearch),
        tinaBackAction(
            enabled = pluginState.selectedPluginId != null,
            onBack = closePluginDetails
        )
    )

    LaunchedEffect(pluginState.selectedPluginId, selectedPlugin) {
        if (PluginMarketplaceSelectionSupport.shouldClosePluginDetails(pluginState.selectedPluginId, selectedPlugin)) {
            viewModel.closePluginDetails()
        }
    }

    pluginState.pendingInstall?.let { pending ->
        PluginPermissionDialog(
            pluginName = pending.manifest.name,
            permissions = pending.permissions,
            onConfirm = viewModel::confirmPendingPluginInstall,
            onDeny = {
                viewModel.dismissPendingPluginInstall()
                Toast.makeText(
                    context,
                    permissionDeniedText,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onDismiss = viewModel::dismissPendingPluginInstall,
        )
    }

    if (showProxyDialog) {
        GitHubRegistryProxyDialog(
            onDismiss = { showProxyDialog = false },
            onSaved = { Toast.makeText(context, proxySavedText, Toast.LENGTH_SHORT).show() },
        )
    }

    // 如果选中了插件，显示详情页面；拦截返回手势，防止直接退出 APP
    selectedPlugin?.let { plugin ->
        val detail = pluginState.selectedPluginDetail ?: plugin.toPluginDetailFallback()
        PluginDetailScreen(
            plugin = detail,
            isPluginDetailLoading = pluginState.isPluginDetailLoading,
            isInstalled = plugin.pluginId in pluginState.installedPlugins,
            isUpdatable = plugin.pluginId in pluginState.updatablePlugins,
            isFavorited = plugin.pluginId in pluginState.favoritedPlugins,
            downloadProgress = pluginState.downloadingPlugins[plugin.pluginId],
            onInstall = { viewModel.installPlugin(plugin) },
            onCancel = { viewModel.cancelPluginInstall(plugin.pluginId) },
            onToggleFavorite = { viewModel.togglePluginFavorite(plugin.pluginId) },
            onNavigateBack = closePluginDetails
        )
        return
    }

    // 只在用户主动点开搜索时请求焦点；从插件详情返回时不弹键盘。
    LaunchedEffect(searchActive, searchFocusPending) {
        if (searchFocusPending && searchActive) {
            searchFocusPending = false
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Scaffold(
        topBar = {
            // 搜索态与标题态同高（64dp）：展开搜索不额外占用列表空间
            if (searchActive) {
                MarketSearchTopBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onCollapse = collapseSearch,
                    focusRequester = searchFocusRequester,
                )
            } else {
                TinaTopBar(
                    title = stringResource(Strings.market_title),
                    actions = {
                        IconButton(onClick = {
                            searchFocusPending = true
                            searchExpanded = true
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(Strings.market_search_action)
                            )
                        }
                        IconButton(onClick = { showProxyDialog = true }) {
                            Icon(Icons.Default.Language, contentDescription = stringResource(Strings.market_github_proxy))
                        }
                    },
                )
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val leadingIcons = maxWidth >= LeadingMarketTabsMinWidth
                val destinations = listOf(
                    Strings.market_tab_plugins to Icons.Default.Extension,
                    Strings.market_tab_packages to Icons.Default.Inventory,
                    Strings.market_tab_vcpkg to Icons.Default.Build,
                )
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    destinations.forEachIndexed { index, (label, imageVector) ->
                        val text: @Composable () -> Unit = {
                            Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val icon: @Composable () -> Unit = { Icon(imageVector, contentDescription = null) }
                        if (leadingIcons) {
                            LeadingIconTab(selected = selectedTab == index, onClick = { selectedTab = index }, text = text, icon = icon)
                        } else {
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = text, icon = icon)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                when (selectedTab) {
                    0 -> PluginsMarketContent(
                        query = searchQuery,
                        plugins = pluginState.plugins,
                        installedPlugins = pluginState.installedPlugins,
                        updatablePlugins = pluginState.updatablePlugins,
                        favoritedPlugins = pluginState.favoritedPlugins,
                        downloadingPlugins = pluginState.downloadingPlugins,
                        isLoading = pluginState.isLoading,
                        error = pluginState.error,
                        onInstall = viewModel::installPlugin,
                        onCancel = viewModel::cancelPluginInstall,
                        onToggleFavorite = viewModel::togglePluginFavorite,
                        onPluginClick = viewModel::selectPlugin,
                        onRefresh = viewModel::retryLoadPlugins
                    )
                    1 -> PackagesMarketContent(
                        query = searchQuery,
                        packages = packageState.filteredPackages,
                        installStates = packageState.installStates,
                        installingPackages = packageState.installingPackages,
                        isLoading = packageState.isLoading,
                        error = packageState.error,
                        onInstall = viewModel::installPackage,
                        onCancel = viewModel::cancelPackageInstall,
                        onRefresh = viewModel::retryLoadPackages
                    )
                    2 -> VcpkgMarketHost(query = searchQuery)
                }
            }
        }
    }

    packageState.pendingInstallPlan?.let { pendingPlan ->
        MarketPackageInstallConfirmDialog(
            pendingPlan = pendingPlan,
            onConfirm = viewModel::confirmPackageInstall,
            onDismiss = viewModel::dismissPackageInstallConfirm
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsMarketContent(
    query: String,
    plugins: List<PluginSummary>,
    installedPlugins: Set<String>,
    updatablePlugins: Set<String>,
    favoritedPlugins: Set<String>,
    downloadingPlugins: Map<String, Float>,
    isLoading: Boolean,
    error: String?,
    onInstall: (PluginSummary) -> Unit,
    onCancel: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPluginClick: (PluginSummary) -> Unit,
    onRefresh: () -> Unit
) {
    // 分类不再做成筛选条：插件卡片上直接显示分类标签，省下一整行高度。
    val visiblePlugins = remember(plugins, query) {
        filterPlugins(plugins = plugins, query = query)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading && plugins.isEmpty() -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) { items(5) { PluginCardSkeleton() } }
            }
            error != null && plugins.isEmpty() -> {
                TinaErrorState(icon = Icons.Default.Extension, errorMessage = error, onRetry = onRefresh)
            }
            plugins.isEmpty() -> {
                TinaEmptyState(
                    icon = Icons.Default.Extension,
                    title = stringResource(Strings.market_empty_plugins_title),
                    description = stringResource(Strings.market_empty_plugins_desc)
                )
            }
            visiblePlugins.isEmpty() -> {
                TinaEmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(Strings.market_empty_search_title),
                    description = stringResource(Strings.market_empty_search_desc)
                )
            }
            else -> {
                TinaPullToRefreshBox(
                    isRefreshing = isLoading,
                    onRefresh = onRefresh,
                    enableHapticFeedback = true,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(visiblePlugins) { plugin ->
                            PluginCard(
                                plugin = plugin,
                                isInstalled = plugin.pluginId in installedPlugins,
                                isUpdatable = plugin.pluginId in updatablePlugins,
                                isFavorited = plugin.pluginId in favoritedPlugins,
                                downloadProgress = downloadingPlugins[plugin.pluginId],
                                onInstall = { onInstall(plugin) },
                                onCancel = { onCancel(plugin.pluginId) },
                                onToggleFavorite = { onToggleFavorite(plugin.pluginId) },
                                onClick = { onPluginClick(plugin) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── PLACEHOLDER_PLUGIN_CARD ──

@Composable
private fun PluginCard(
    plugin: PluginSummary,
    isInstalled: Boolean,
    isUpdatable: Boolean,
    isFavorited: Boolean,
    downloadProgress: Float?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    TinaCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(plugin.description ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val categoryLabel = pluginCategoryLabel(plugin.category)
                    if (plugin.latestVersion != null || categoryLabel != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            plugin.latestVersion?.let { version ->
                                Text(stringResource(Strings.market_version, version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            categoryLabel?.let { label ->
                                TinaStatusBadge(label, BadgeStatus.INFO)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint = if (isFavorited) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                downloadProgress != null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DownloadProgressButton(progress = downloadProgress, onCancel = onCancel)
                }
                isUpdatable -> TinaPrimaryButton(
                    text = stringResource(Strings.plugin_marketplace_update),
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth()
                )
                isInstalled -> TinaOutlinedButton(
                    text = stringResource(Strings.market_installed),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false
                )
                else -> TinaPrimaryButton(text = stringResource(Strings.market_install), onClick = onInstall, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ── PLACEHOLDER_PACKAGES ──

@Composable
private fun MarketPackageInstallConfirmDialog(
    pendingPlan: MarketPackageInstallPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dependenciesToInstall = pendingPlan.plan.packages
        .filterNot { it.isRoot }
        .filterNot { it.isAlreadyInstalled }
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(
                stringResource(Strings.pkg_manager_install_confirm_title, pendingPlan.packageInfo.name)
            )
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.pkg_manager_install_confirm_message,
                        marketPlatformDisplayName(pendingPlan.platform),
                        pendingPlan.packageInfo.name
                    )
                )
                if (dependenciesToInstall.isNotEmpty()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_install_confirm_dependencies_title),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        dependenciesToInstall.forEach { dependency ->
                            Text(
                                text = "\u2022 " + stringResource(
                                    Strings.pkg_manager_install_confirm_dependency_item,
                                    dependency.packageName,
                                    dependency.version
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.pkg_manager_install_confirm_button),
                onClick = onConfirm
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun marketPlatformDisplayName(platform: Platform): String = when (platform) {
    Platform.LINUX -> stringResource(Strings.pkg_manager_platform_linux)
    Platform.ANDROID -> stringResource(Strings.pkg_manager_platform_android)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackagesMarketContent(
    query: String,
    packages: List<GUIPackage>,
    installStates: Map<String, com.wuxianggujun.tinaide.core.packages.model.PackageInstallState>,
    installingPackages: Map<String, Float>,
    isLoading: Boolean,
    error: String?,
    onInstall: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val visiblePackages = remember(packages, query) {
        filterPackages(packages = packages, query = query)
    }

    when {
        isLoading && packages.isEmpty() -> {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) { items(5) { PluginCardSkeleton() } }
        }
        error != null && packages.isEmpty() -> TinaErrorState(icon = Icons.Default.Inventory, errorMessage = error, onRetry = onRefresh)
        packages.isEmpty() -> TinaEmptyState(icon = Icons.Default.Inventory, title = stringResource(Strings.market_empty_packages_title), description = stringResource(Strings.market_empty_packages_desc))
        visiblePackages.isEmpty() -> TinaEmptyState(icon = Icons.Default.Search, title = stringResource(Strings.market_empty_search_title), description = stringResource(Strings.market_empty_search_desc))
        else -> {
            TinaPullToRefreshBox(isRefreshing = isLoading, onRefresh = onRefresh, enableHapticFeedback = true, modifier = Modifier.fillMaxSize()) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visiblePackages) { pkg ->
                        val installState = installStates[pkg.id]
                        val isInstalled = installState?.linux?.isInstalled == true || installState?.android?.isInstalled == true
                        PackageCard(
                            packageItem = pkg,
                            isInstalled = isInstalled,
                            installProgress = installingPackages[pkg.id],
                            onInstall = { onInstall(pkg.id) },
                            onCancel = { onCancel(pkg.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageCard(
    packageItem: GUIPackage,
    isInstalled: Boolean,
    installProgress: Float?,
    onInstall: () -> Unit,
    onCancel: () -> Unit
) {
    TinaCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Inventory, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(packageItem.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(packageItem.description ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val lv = packageItem.linux?.version
                val av = packageItem.android?.version
                val dv = when {
                    lv != null && av != null -> "$lv (Linux) / $av (Android)"
                    lv != null -> lv
                    av != null -> av
                    else -> null
                }
                if (dv != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(Strings.market_version, dv), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                installProgress != null -> DownloadProgressButton(
                    progress = installProgress,
                    onCancel = onCancel
                )
                isInstalled -> TinaOutlinedButton(
                    text = stringResource(Strings.market_installed),
                    onClick = {},
                    enabled = false
                )
                else -> {
                    TinaPrimaryButton(text = stringResource(Strings.market_install), onClick = onInstall)
                }
            }
        }
    }
}

// ── PLACEHOLDER_HELPERS ──

/**
 * 下载/安装进行中的紧凑控件：进度环中心叠加可点击的取消（X）按钮。
 *
 * 进度为 0（刚点下载、尚未拿到响应头）时显示不确定进度环；点击 X 触发 [onCancel]，
 * ViewModel 会取消协程并通过 OkHttp `Call.cancel()` 立即断开连接。
 */
@Composable
private fun DownloadProgressButton(
    progress: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0f) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(40.dp))
        } else {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(Strings.btn_cancel_install),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 搜索态顶栏：标题让位给输入框，高度与普通顶栏一致，不额外挤压列表。
 */
@Composable
private fun MarketSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onCollapse: () -> Unit,
    focusRequester: FocusRequester,
) {
    TinaTopBar(
        title = {
            TinaInlineSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(Strings.market_search_all_hint),
                onClearClick = { onQueryChange("") },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        navigationIcon = {
            IconButton(onClick = onCollapse) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Strings.content_desc_navigate_back),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailScreen(
    plugin: PluginDetail,
    isPluginDetailLoading: Boolean,
    isInstalled: Boolean,
    isUpdatable: Boolean,
    isFavorited: Boolean,
    downloadProgress: Float?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onToggleFavorite: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TinaTopBar(
                title = plugin.name,
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorited) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TinaCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Extension,
                                null,
                                Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    plugin.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    plugin.publisher.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                plugin.category?.let { cat ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        stringResource(Strings.plugin_marketplace_category_value, cat),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (isPluginDetailLoading) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(Strings.plugin_marketplace_detail_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        when {
                            downloadProgress != null -> Column {
                                Text(
                                    stringResource(Strings.market_downloading, (downloadProgress * 100).toInt()),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = onCancel) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(Strings.btn_cancel_install),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            isUpdatable -> TinaPrimaryButton(
                                text = stringResource(Strings.plugin_marketplace_update),
                                onClick = onInstall,
                                modifier = Modifier.fillMaxWidth()
                            )
                            isInstalled -> TinaOutlinedButton(
                                text = stringResource(Strings.market_installed),
                                onClick = {},
                                modifier = Modifier.fillMaxWidth(),
                                enabled = false
                            )
                            else -> TinaPrimaryButton(
                                text = stringResource(Strings.market_install),
                                onClick = onInstall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            item {
                TinaCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(Strings.plugin_marketplace_info),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(12.dp))

                        PluginInfoRow(
                            label = stringResource(Strings.plugin_marketplace_publisher),
                            value = plugin.publisher.displayName
                        )

                        Spacer(Modifier.height(8.dp))
                        PluginInfoRow(
                            label = stringResource(Strings.plugin_marketplace_version),
                            value = plugin.latestVersionLabel()
                        )

                        plugin.category?.let { cat ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_category),
                                value = cat
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        PluginInfoRow(
                            label = stringResource(Strings.plugin_marketplace_sort_updated),
                            value = plugin.updatedAt.take(10)
                        )

                        plugin.repositoryUrl?.takeIf { it.isNotBlank() }?.let { repositoryUrl ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_repository),
                                value = repositoryUrl
                            )
                        }

                        plugin.homepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_homepage),
                                value = homepageUrl
                            )
                        }

                        plugin.license?.takeIf { it.isNotBlank() }?.let { license ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_license),
                                value = license
                            )
                        }
                    }
                }
            }

            plugin.description?.let { description ->
                item {
                    TinaCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(Strings.plugin_marketplace_description),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (plugin.versions.isNotEmpty()) {
                item {
                    TinaCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(Strings.plugin_marketplace_versions),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(12.dp))
                            plugin.versions.forEachIndexed { index, version ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PluginInfoRow(
                                        label = stringResource(Strings.plugin_marketplace_version),
                                        value = version.version
                                    )
                                    version.changelog?.takeIf { it.isNotBlank() }?.let { changelog ->
                                        Text(
                                            text = changelog,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = version.createdAt.take(10),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (index != plugin.versions.lastIndex) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (plugin.tags.isNotEmpty()) {
                item {
                    TinaCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(Strings.plugin_marketplace_tags),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                plugin.tags.forEach { tag ->
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(tag, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun PluginDetail.latestVersionLabel(): String = versions.firstOrNull()?.version ?: "-"

private fun PluginSummary.toPluginDetailFallback(): PluginDetail {
    val fallbackVersions = latestVersion?.let { version ->
        listOf(
            PluginVersion(
                version = version,
                versionCode = 0,
                fileSize = 0,
                fileHash = "",
                minAppVersion = null,
                changelog = null,
                createdAt = updatedAt
            )
        )
    } ?: emptyList()

    return PluginDetail(
        id = id,
        pluginId = pluginId,
        name = name,
        description = description,
        category = category,
        tags = tags,
        iconUrl = iconUrl,
        repositoryUrl = null,
        homepageUrl = null,
        license = null,
        publisher = publisher,
        versions = fallbackVersions,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )
}

/**
 * 插件分类只作为卡片上的信息标签展示，不再提供筛选入口；未知分类不显示，避免露出英文原文。
 */
@Composable
private fun pluginCategoryLabel(category: String?): String? = when (category?.trim()?.lowercase()) {
    "theme" -> stringResource(Strings.market_category_theme)
    "tool" -> stringResource(Strings.market_category_tool)
    "language" -> stringResource(Strings.market_category_language)
    else -> null
}

private fun filterPlugins(
    plugins: List<PluginSummary>,
    query: String,
): List<PluginSummary> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return plugins
    return plugins.filter { plugin ->
        plugin.name.contains(normalizedQuery, ignoreCase = true) ||
            plugin.description?.contains(normalizedQuery, ignoreCase = true) == true ||
            plugin.tags.any { tag -> tag.contains(normalizedQuery, ignoreCase = true) }
    }
}

private fun filterPackages(
    packages: List<GUIPackage>,
    query: String,
): List<GUIPackage> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return packages
    return packages.filter { pkg ->
        pkg.name.contains(normalizedQuery, ignoreCase = true) ||
            pkg.description?.contains(normalizedQuery, ignoreCase = true) == true ||
            pkg.category?.contains(normalizedQuery, ignoreCase = true) == true ||
            pkg.id.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
private fun PluginInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
