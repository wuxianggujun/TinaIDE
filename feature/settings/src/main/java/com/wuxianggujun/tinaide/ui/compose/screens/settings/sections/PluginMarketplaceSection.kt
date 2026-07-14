package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.marketplace.PluginMarketplaceSelectionSupport
import com.wuxianggujun.tinaide.plugin.marketplace.PluginMarketplaceViewModel
import com.wuxianggujun.tinaide.plugin.marketplace.PluginSortType
import com.wuxianggujun.tinaide.plugin.marketplace.PluginSummary
import com.wuxianggujun.tinaide.ui.compose.components.DetailHeaderCard
import com.wuxianggujun.tinaide.ui.compose.components.DetailIconPlaceholder
import com.wuxianggujun.tinaide.ui.compose.components.DetailInfoCard
import com.wuxianggujun.tinaide.ui.compose.components.PluginPermissionDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketplaceScreen(
    onBack: () -> Unit,
    viewModel: PluginMarketplaceViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedPlugin = PluginMarketplaceSelectionSupport.resolveSelectedPlugin(
        selectedPluginId = uiState.selectedPluginId,
        plugins = uiState.plugins,
    )

    // 处理系统返回键：优先处理详情页面的返回
    TinaBackHandlers(
        tinaBackAction(enabled = uiState.selectedPluginId != null) {
            viewModel.closePluginDetails()
        }
    )

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.selectedPluginId, selectedPlugin) {
        if (PluginMarketplaceSelectionSupport.shouldClosePluginDetails(uiState.selectedPluginId, selectedPlugin)) {
            viewModel.closePluginDetails()
        }
    }

    // 如果选中了插件，显示详情页面
    selectedPlugin?.let { plugin ->
        PluginDetailScreen(
            plugin = plugin,
            isInstalled = plugin.pluginId in uiState.installedPlugins,
            hasUpdate = plugin.pluginId in uiState.updatablePlugins,
            downloadProgress = uiState.downloadingPlugins[plugin.pluginId],
            onInstall = { viewModel.installPlugin(plugin) },
            onCancel = { viewModel.cancelInstall(plugin.pluginId) },
            onNavigateBack = { viewModel.closePluginDetails() }
        )
        return
    }

    uiState.pendingInstall?.let { pending ->
        PluginPermissionDialog(
            pluginName = pending.manifest.name,
            permissions = pending.permissions,
            onConfirm = viewModel::confirmPendingInstall,
            onDeny = {
                viewModel.dismissPendingInstall()
                Toast.makeText(
                    context,
                    context.getString(Strings.toast_plugins_permission_denied),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onDismiss = viewModel::dismissPendingInstall,
        )
    }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = stringResource(Strings.plugin_marketplace_title),
                onNavigateBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.loadPlugins() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) },
                onSearch = { viewModel.search() }
            )

            CategoryFilter(
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { viewModel.setCategory(it) }
            )

            SortChips(
                selectedSort = uiState.sortType,
                onSortSelected = { viewModel.setSortType(it) }
            )

            when {
                uiState.isLoading && uiState.plugins.isEmpty() -> {
                    LoadingContent()
                }
                uiState.error != null && uiState.plugins.isEmpty() -> {
                    ErrorContent(
                        message = uiState.error ?: "",
                        onRetry = { viewModel.loadPlugins() }
                    )
                }
                uiState.plugins.isEmpty() -> {
                    EmptyContent()
                }
                else -> {
                    PluginList(
                        plugins = uiState.plugins,
                        installedPlugins = uiState.installedPlugins,
                        updatablePlugins = uiState.updatablePlugins,
                        downloadingPlugins = uiState.downloadingPlugins,
                        isLoadingMore = uiState.isLoadingMore,
                        hasMore = uiState.hasMorePages,
                        onLoadMore = { viewModel.loadMore() },
                        onInstall = { viewModel.installPlugin(it) },
                        onCancel = { viewModel.cancelInstall(it.pluginId) },
                        onPluginClick = { viewModel.showPluginDetails(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(Strings.plugin_marketplace_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(TinaShapes.CardCorner)
    )
}

@Composable
private fun CategoryFilter(
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit
) {
    // 预先获取字符串资源，避免在 LazyRow items 中调用 stringResource 导致潜在的 null 问题
    val categoryAll = stringResource(Strings.plugin_marketplace_category_all)
    val categoryTheme = stringResource(Strings.plugin_marketplace_category_theme)
    val categorySnippet = stringResource(Strings.plugin_marketplace_category_snippet)
    val categoryTool = stringResource(Strings.plugin_marketplace_category_tool)
    val categoryLanguage = stringResource(Strings.plugin_marketplace_category_language)

    val categories = remember(categoryAll, categoryTheme, categorySnippet, categoryTool, categoryLanguage) {
        PluginMarketplaceSectionSupport.buildCategorySpecs(
            allLabel = categoryAll,
            themeLabel = categoryTheme,
            snippetLabel = categorySnippet,
            toolLabel = categoryTool,
            languageLabel = categoryLanguage,
        )
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories) { spec ->
            FilterChip(
                selected = selectedCategory == spec.category,
                onClick = { onCategorySelected(spec.category) },
                label = { Text(spec.label) }
            )
        }
    }
}

@Composable
private fun SortChips(
    selectedSort: PluginSortType,
    onSortSelected: (PluginSortType) -> Unit
) {
    // 预先获取字符串资源，避免在 LazyRow items 中调用 stringResource 导致潜在的 null 问题
    val sortNewest = stringResource(Strings.plugin_marketplace_sort_newest)
    val sortUpdated = stringResource(Strings.plugin_marketplace_sort_updated)

    val sortOptions = remember(sortNewest, sortUpdated) {
        PluginMarketplaceSectionSupport.buildSortSpecs(
            newestLabel = sortNewest,
            updatedLabel = sortUpdated,
        )
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sortOptions) { spec ->
            FilterChip(
                selected = selectedSort == spec.sortType,
                onClick = { onSortSelected(spec.sortType) },
                label = { Text(spec.label, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(Strings.plugin_marketplace_loading))
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Strings.plugin_marketplace_load_failed),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(Strings.plugin_marketplace_retry))
            }
        }
    }
}

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Strings.plugin_marketplace_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PluginList(
    plugins: List<PluginSummary>,
    installedPlugins: Set<String>,
    updatablePlugins: Set<String>,
    downloadingPlugins: Map<String, Float>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onInstall: (PluginSummary) -> Unit,
    onCancel: (PluginSummary) -> Unit,
    onPluginClick: (PluginSummary) -> Unit
) {
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            PluginMarketplaceSectionSupport.shouldTriggerLoadMore(
                lastVisibleItemIndex = lastVisibleItem,
                totalCount = plugins.size,
                hasMore = hasMore,
                isLoadingMore = isLoadingMore,
            )
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.lg)
    ) {
        items(plugins, key = { it.pluginId }) { plugin ->
            val isInstalled = plugin.pluginId in installedPlugins
            val hasUpdate = plugin.pluginId in updatablePlugins
            val downloadProgress = downloadingPlugins[plugin.pluginId]

            PluginCard(
                plugin = plugin,
                isInstalled = isInstalled,
                hasUpdate = hasUpdate,
                downloadProgress = downloadProgress,
                onInstall = { onInstall(plugin) },
                onCancel = { onCancel(plugin) },
                onClick = { onPluginClick(plugin) }
            )
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(TinaSpacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginCard(
    plugin: PluginSummary,
    isInstalled: Boolean,
    hasUpdate: Boolean,
    downloadProgress: Float?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onClick: () -> Unit
) {
    val installActionSpec = PluginMarketplaceSectionSupport.resolveInstallActionSpec(
        isInstalled = isInstalled,
        hasUpdate = hasUpdate,
        downloadProgress = downloadProgress,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(TinaShapes.CardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(TinaSpacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(TinaShapes.SmallCorner))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = PluginMarketplaceSectionSupport.resolvePluginInitial(plugin.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(TinaSpacing.lg))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = plugin.publisher.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    plugin.description?.let { desc ->
                        Spacer(modifier = Modifier.height(TinaSpacing.xs))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(TinaSpacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TinaSpacing.xl)
                ) {
                    plugin.latestVersion?.let { version ->
                        Text(
                            text = "v$version",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                                )
                                .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
                        )
                    }
                }

                when (installActionSpec) {
                    is PluginMarketplaceInstallActionSpec.Downloading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(
                                        Strings.plugin_marketplace_download_progress,
                                        installActionSpec.progressPercent
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                LinearProgressIndicator(
                                    progress = { downloadProgress ?: 0f },
                                    modifier = Modifier.width(80.dp)
                                )
                            }
                            // 取消下载：取消协程会触发 OkHttp Call.cancel 立即断开连接。
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(Strings.btn_cancel_install),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    PluginMarketplaceInstallActionSpec.Installed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(TinaShapes.CardCorner)
                                )
                                .padding(horizontal = TinaSpacing.lg, vertical = TinaSpacing.sm)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(TinaSpacing.xs))
                            Text(
                                text = stringResource(Strings.plugin_marketplace_installed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    PluginMarketplaceInstallActionSpec.Update -> {
                        Button(
                            onClick = onInstall,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text(stringResource(Strings.plugin_marketplace_update))
                        }
                    }
                    PluginMarketplaceInstallActionSpec.Install -> {
                        Button(
                            onClick = onInstall,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(Strings.plugin_marketplace_install))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailScreen(
    plugin: PluginSummary,
    isInstalled: Boolean,
    hasUpdate: Boolean,
    downloadProgress: Float?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val installActionSpec = PluginMarketplaceSectionSupport.resolveInstallActionSpec(
        isInstalled = isInstalled,
        hasUpdate = hasUpdate,
        downloadProgress = downloadProgress,
    )
    Scaffold(
        topBar = {
            TinaTopBar(
                title = plugin.name,
                onNavigateBack = onNavigateBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(TinaSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(TinaSpacing.xl)
        ) {
            // 插件头部信息
            item {
                DetailHeaderCard(
                    icon = {
                        DetailIconPlaceholder(text = plugin.name)
                    },
                    title = plugin.name,
                    subtitle = plugin.publisher.displayName,
                    actions = {
                        // 安装按钮
                        when (installActionSpec) {
                            is PluginMarketplaceInstallActionSpec.Downloading -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(
                                            Strings.plugin_marketplace_download_progress,
                                            installActionSpec.progressPercent
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(TinaSpacing.md))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress ?: 0f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(TinaSpacing.md))
                                    // 取消下载：取消协程会触发 OkHttp Call.cancel 立即断开连接。
                                    OutlinedButton(
                                        onClick = onCancel,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(TinaShapes.ButtonCorner)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(TinaSpacing.md))
                                        Text(stringResource(Strings.btn_cancel_install))
                                    }
                                }
                            }
                            PluginMarketplaceInstallActionSpec.Installed -> {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    shape = RoundedCornerShape(TinaShapes.ButtonCorner)
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(TinaSpacing.md))
                                    Text(stringResource(Strings.plugin_marketplace_installed))
                                }
                            }
                            PluginMarketplaceInstallActionSpec.Update -> {
                                Button(
                                    onClick = onInstall,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    shape = RoundedCornerShape(TinaShapes.ButtonCorner)
                                ) {
                                    Text(stringResource(Strings.plugin_marketplace_update))
                                }
                            }
                            PluginMarketplaceInstallActionSpec.Install -> {
                                Button(
                                    onClick = onInstall,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(TinaShapes.ButtonCorner)
                                ) {
                                    Text(stringResource(Strings.plugin_marketplace_install))
                                }
                            }
                        }
                    }
                )
            }

            // 描述
            plugin.description?.let { description ->
                item {
                    DetailInfoCard(
                        title = stringResource(Strings.plugin_marketplace_description)
                    ) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 版本信息
            plugin.latestVersion?.let { version ->
                item {
                    DetailInfoCard(
                        title = stringResource(Strings.plugin_marketplace_version)
                    ) {
                        Text(
                            text = version,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
