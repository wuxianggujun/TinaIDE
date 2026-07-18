package com.wuxianggujun.tinaide.ui.compose.screens.help
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.help.HelpCategory
import com.wuxianggujun.tinaide.core.help.HelpDocument
import com.wuxianggujun.tinaide.core.help.HelpQuickAction
import com.wuxianggujun.tinaide.core.help.HelpQuickActionSupport
import com.wuxianggujun.tinaide.core.help.HelpSearchResult
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.MarkdownViewer
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaSearchField
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter

/**
 * 帮助中心主屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    viewModel: HelpViewModel,
    onNavigateBack: () -> Unit,
    onCreatePluginProject: () -> Unit = {},
    onOpenPluginSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val documentContent by viewModel.documentContent.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val uriHandler = LocalUriHandler.current

    val handleBack = {
        if (uiState.selectedDocument != null) {
            viewModel.clearSelectedDocument()
        } else {
            onNavigateBack()
        }
    }

    TinaBackHandlers(
        tinaBackAction(enabled = uiState.selectedDocument != null) {
            viewModel.clearSelectedDocument()
        }
    )

    Scaffold(
        topBar = {
            TinaTopBar(
                title = uiState.selectedDocument?.title
                    ?: stringResource(Strings.settings_title_help),
                onNavigateBack = handleBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            when {
                uiState.isLoading -> {
                    // 加载中
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.selectedDocument != null -> {
                    // 显示文档内容
                    HelpDocumentContent(
                        document = uiState.selectedDocument!!,
                        content = documentContent,
                        isLoading = uiState.isLoadingContent,
                        onCreatePluginProject = onCreatePluginProject,
                        onOpenPluginSettings = onOpenPluginSettings,
                        onRetry = viewModel::retrySelectedDocument,
                        onLinkClick = { target ->
                            if (target.startsWith("#")) {
                                return@HelpDocumentContent
                            }
                            val handled = viewModel.openDocumentByLinkTarget(target)
                            if (!handled) {
                                runCatching { uriHandler.openUri(target) }
                            }
                        },
                    )
                }

                else -> {
                    // 显示文档列表
                    HelpDocumentList(
                        uiState = uiState,
                        searchResults = searchResults,
                        onSearch = viewModel::search,
                        onClearSearch = viewModel::clearSearch,
                        onDocumentClick = viewModel::selectDocument
                    )
                }
            }
        }
    }
}

/**
 * 文档列表
 */
@Composable
private fun HelpDocumentList(
    uiState: HelpUiState,
    searchResults: List<HelpSearchResult>,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onDocumentClick: (HelpDocument) -> Unit
) {
    var searchQuery by remember { mutableStateOf(uiState.searchQuery) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框
        TinaSearchField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                onSearch(it)
            },
            placeholder = stringResource(Strings.help_search_hint),
            onClearClick = {
                searchQuery = ""
                onClearSearch()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TinaSpacing.xl, vertical = TinaSpacing.md)
        )

        // 搜索结果或分类列表
        if (searchQuery.isNotEmpty()) {
            // 搜索结果
            if (uiState.isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(Strings.help_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = TinaSpacing.xl, vertical = TinaSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)
                ) {
                    items(searchResults) { result ->
                        HelpDocumentCard(
                            document = result.document,
                            matchedContent = result.matchedContent,
                            onClick = { onDocumentClick(result.document) }
                        )
                    }
                }
            }
        } else {
            // 分类列表
            if (uiState.documentsByCategory.isEmpty()) {
                // 显示空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)
                    ) {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_help_book),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(Strings.help_no_documents),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = TinaSpacing.xl, vertical = TinaSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TinaSpacing.xl)
                ) {
                    uiState.documentsByCategory.forEach { (category, documents) ->
                        item(key = category.name) {
                            HelpCategorySection(
                                category = category,
                                documents = documents,
                                onDocumentClick = onDocumentClick
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(TinaSpacing.xl))
                    }
                }
            }
        }
    }
}

/**
 * 分类区块
 */
@Composable
private fun HelpCategorySection(
    category: HelpCategory,
    documents: List<HelpDocument>,
    onDocumentClick: (HelpDocument) -> Unit
) {
    Column {
        // 分类标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Icon(
                painter = rememberTinaPainter(category.iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(TinaSpacing.md))
            Text(
                text = stringResource(category.displayNameRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 文档列表
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                documents.forEachIndexed { index, document ->
                    HelpDocumentItem(
                        document = document,
                        showDivider = index < documents.size - 1,
                        onClick = { onDocumentClick(document) }
                    )
                }
            }
        }
    }
}

/**
 * 文档列表项
 */
@Composable
private fun HelpDocumentItem(
    document: HelpDocument,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TinaSpacing.xl, vertical = TinaSpacing.lg)
    ) {
        Text(
            text = document.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (document.summary.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = document.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showDivider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TinaSpacing.xl)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/**
 * 搜索结果卡片
 */
@Composable
private fun HelpDocumentCard(
    document: HelpDocument,
    matchedContent: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(TinaSpacing.xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = rememberTinaPainter(document.category.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(TinaSpacing.md))
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (document.summary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(TinaSpacing.xs))
                Text(
                    text = document.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (matchedContent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = matchedContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                )
            }
        }
    }
}

/**
 * 文档内容显示
 */
@Composable
internal fun HelpDocumentContent(
    document: HelpDocument,
    content: String?,
    isLoading: Boolean,
    onCreatePluginProject: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onRetry: () -> Unit,
    onLinkClick: (String) -> Unit,
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (content != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp)
        ) {
            HelpDocumentQuickActions(
                document = document,
                onCreatePluginProject = onCreatePluginProject,
                onOpenPluginSettings = onOpenPluginSettings,
            )
            MarkdownViewer(
                markdown = content,
                modifier = Modifier.fillMaxWidth(),
                onLinkClick = onLinkClick,
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TinaSpacing.md),
            ) {
                Text(
                    text = stringResource(Strings.help_load_failed),
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = onRetry) {
                    Text(text = stringResource(Strings.action_retry))
                }
            }
        }
    }
}

@Composable
private fun HelpDocumentQuickActions(
    document: HelpDocument,
    onCreatePluginProject: () -> Unit,
    onOpenPluginSettings: () -> Unit,
) {
    val actions = remember(document.id) { HelpQuickActionSupport.resolveActions(document) }
    if (actions.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TinaSpacing.xl, vertical = TinaSpacing.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(TinaSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(TinaSpacing.md),
        ) {
            Text(
                text = stringResource(Strings.help_quick_actions_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    val label = when (action) {
                        HelpQuickAction.CREATE_PLUGIN_PROJECT -> {
                            stringResource(Strings.help_action_create_plugin_project)
                        }
                        HelpQuickAction.OPEN_PLUGIN_SETTINGS -> {
                            stringResource(Strings.help_action_open_plugin_settings)
                        }
                    }
                    Card(
                        modifier = Modifier.clickable {
                            when (action) {
                                HelpQuickAction.CREATE_PLUGIN_PROJECT -> onCreatePluginProject()
                                HelpQuickAction.OPEN_PLUGIN_SETTINGS -> onOpenPluginSettings()
                            }
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                horizontal = TinaSpacing.lg,
                                vertical = TinaSpacing.md,
                            ),
                        )
                    }
                }
            }
        }
    }
}
