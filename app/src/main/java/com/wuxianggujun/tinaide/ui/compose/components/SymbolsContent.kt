package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.WorkspaceSymbolItem
import com.wuxianggujun.tinaide.core.symbol.FuzzySymbolMatch
import com.wuxianggujun.tinaide.core.symbol.IProjectSymbolIndexService
import com.wuxianggujun.tinaide.core.symbol.SymbolInfo
import com.wuxianggujun.tinaide.ui.MainActivityNavigationHelper
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveWorkspaceSymbolsTargetResult

@Composable
fun SymbolsContent(
    editorContainerState: EditorContainerState,
    projectSymbolIndexService: IProjectSymbolIndexService?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val service = projectSymbolIndexService
    if (service == null) {
        BoxFill(stringResource(Strings.symbols_service_not_initialized), modifier)
        return
    }

    val status by service.status.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var useFuzzySearch by remember { mutableStateOf(true) } // 默认使用模糊搜索

    val workspaceSymbolsTarget = editorContainerState.getActiveWorkspaceSymbolsTargetResult()
    val workspaceSymbolsTabId =
        (workspaceSymbolsTarget as? ActiveWorkspaceSymbolsTargetResult.Available)?.tabId
    val isWorkspaceSymbolsAvailable = workspaceSymbolsTabId != null

    var useLspSearch by remember { mutableStateOf(true) }
    val effectiveUseLspSearch = useLspSearch && isWorkspaceSymbolsAvailable

    var lspLoading by remember { mutableStateOf(false) }
    var lspResults by remember { mutableStateOf<List<WorkspaceSymbolItem>>(emptyList()) }
    val lspGroupExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val groupedLspResults = remember(lspResults, effectiveUseLspSearch) {
        if (effectiveUseLspSearch) {
            lspResults.groupBy { it.filePath }
        } else {
            emptyMap()
        }
    }

    if (effectiveUseLspSearch) {
        LaunchedEffect(query, workspaceSymbolsTabId) {
            val q = query.trim()
            if (q.length < 2) {
                lspLoading = false
                lspResults = emptyList()
                return@LaunchedEffect
            }

            lspLoading = true
            lspResults = emptyList()

            // debounce：避免每次键入都请求 LSP
            delay(200)

            val tabId = workspaceSymbolsTabId ?: return@LaunchedEffect
            val initial = runCatching {
                editorContainerState.workspaceSymbol(tabId, q)
            }.getOrElse { emptyList() }
            lspResults = initial
            lspLoading = false

            // 后台预 resolve（不阻塞输入/列表展示）：尝试补齐前 N 个 WorkspaceSymbolLocation 的 range
            val candidates = initial.asSequence()
                .filter { it.requiresResolve }
                .take(20)
                .toList()
            if (candidates.isEmpty()) return@LaunchedEffect

            // 留一点“空闲时间”，避免刚出结果就立刻打满 resolve 请求
            delay(150)

            val resolvedById = mutableMapOf<String, WorkspaceSymbolItem>()
            for (candidate in candidates) {
                val resolved = runCatching {
                    editorContainerState.resolveWorkspaceSymbol(tabId, candidate)
                }.getOrNull() ?: continue

                if (resolved.stableId == candidate.stableId && resolved != candidate) {
                    resolvedById[resolved.stableId] = resolved
                }
            }
            if (resolvedById.isNotEmpty()) {
                lspResults = lspResults.map { existing ->
                    resolvedById[existing.stableId] ?: existing
                }
            }
        }
    }

    // 根据搜索模式选择不同的查询方法
    val fuzzyResults by remember(query, status.revision, useFuzzySearch, effectiveUseLspSearch) {
        derivedStateOf {
            if (effectiveUseLspSearch) {
                emptyList<FuzzySymbolMatch>()
            } else {
                if (useFuzzySearch) {
                    service.queryGlobalsFuzzy(query, limit = 200)
                } else {
                    // 前缀搜索，转换为 FuzzySymbolMatch 格式
                    service.queryGlobals(query, limit = 200).map { symbol ->
                        FuzzySymbolMatch(symbol, score = 0, matchedIndices = emptyList())
                    }
                }
                    // 防御性去重：避免缓存/增量索引异常导致的重复项触发 LazyColumn key 冲突崩溃
                    .distinctBy { it.symbol.composeStableKey() }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        TinaOverlayPanelSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when {
                        status.isIndexing -> {
                            if (status.cacheLoaded) {
                                stringResource(Strings.symbols_indexing_with_cache, status.indexedFiles, status.totalFiles, status.cacheHitFiles)
                            } else {
                                stringResource(Strings.symbols_indexing, status.indexedFiles, status.totalFiles)
                            }
                        }
                        status.lastError != null -> stringResource(Strings.symbols_index_failed, status.lastError!!)
                        status.projectRoot != null -> {
                            if (status.cacheLoaded) {
                                stringResource(Strings.symbols_indexed_with_cache, status.indexedFiles, status.totalFiles)
                            } else {
                                stringResource(Strings.symbols_indexed, status.indexedFiles, status.totalFiles)
                            }
                        }
                        else -> stringResource(Strings.symbols_not_started)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (status.isIndexing) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (isWorkspaceSymbolsAvailable) {
            TinaOverlayPanelSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SymbolsToggleButton(
                        text = stringResource(Strings.symbols_lsp_search),
                        selected = effectiveUseLspSearch,
                        onClick = { useLspSearch = true },
                        modifier = Modifier.weight(1f)
                    )
                    SymbolsToggleButton(
                        text = stringResource(Strings.symbols_local_index_search),
                        selected = !effectiveUseLspSearch,
                        onClick = { useLspSearch = false },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // 本地索引搜索模式切换（仅在未使用 LSP 时展示）
        if (!effectiveUseLspSearch) {
            TinaOverlayPanelSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SymbolsToggleButton(
                        text = stringResource(Strings.symbols_fuzzy_search),
                        selected = useFuzzySearch,
                        onClick = { useFuzzySearch = true },
                        modifier = Modifier.weight(1f)
                    )
                    SymbolsToggleButton(
                        text = stringResource(Strings.symbols_prefix_search),
                        selected = !useFuzzySearch,
                        onClick = { useFuzzySearch = false },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        TinaOverlayPanelSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 10.dp),
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = {
                    Text(
                        when {
                            effectiveUseLspSearch -> stringResource(Strings.symbols_search_lsp_hint)
                            useFuzzySearch -> stringResource(Strings.symbols_search_fuzzy_hint)
                            else -> stringResource(Strings.symbols_search_prefix_hint)
                        }
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }

        // 显示结果数量
        if (query.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                val count = if (effectiveUseLspSearch) lspResults.size else fuzzyResults.size
                Text(
                    text = stringResource(Strings.symbols_found_count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (effectiveUseLspSearch && lspLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (effectiveUseLspSearch) {
                groupedLspResults.forEach { (filePath, itemsInFile) ->
                    val fileName = itemsInFile.firstOrNull()?.fileName ?: File(filePath).name
                    val expanded = lspGroupExpanded[filePath] ?: true

                    item(key = "header|$filePath") {
                        LspSymbolFileHeaderRow(
                            fileName = fileName,
                            filePath = filePath,
                            projectRoot = status.projectRoot,
                            count = itemsInFile.size,
                            expanded = expanded,
                            onToggle = { lspGroupExpanded[filePath] = !expanded },
                        )
                    }

                    if (expanded) {
                        items(
                            items = itemsInFile,
                            key = { it.stableId },
                        ) { item ->
                            LspWorkspaceSymbolRow(
                                item = item,
                                onClick = {
                                    scope.launch {
                                        val tabId = workspaceSymbolsTabId ?: return@launch
                                        val resolved = runCatching {
                                            editorContainerState.resolveWorkspaceSymbol(tabId, item)
                                        }.getOrNull()
                                        val finalItem = resolved ?: item
                                        if (resolved != null && resolved !== item) {
                                            lspResults = lspResults.map { existing ->
                                                if (existing.stableId == resolved.stableId) resolved else existing
                                            }
                                        }

                                        val file = File(finalItem.filePath)
                                        MainActivityNavigationHelper.navigateToFilePosition(
                                            file = file,
                                            line = finalItem.line,
                                            column = finalItem.column,
                                            editorContainerState = editorContainerState,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                items(
                    items = fuzzyResults,
                    key = { it.symbol.composeStableKey() },
                ) { result ->
                    FuzzySymbolRow(
                        result = result,
                        query = query,
                        showHighlight = useFuzzySearch && query.isNotEmpty(),
                        onNavigate = { file, line, column ->
                            MainActivityNavigationHelper.navigateToFilePosition(
                                file = file,
                                line = line,
                                column = column,
                                editorContainerState = editorContainerState,
                            )
                        }
                    )
                }
            }
        }
    }
}
private fun SymbolInfo.composeStableKey(): String {
    val location = this.location
    return buildString {
        append(filePath)
        append("|")
        append(kind.name)
        append("|")
        append(name)
        append("|")
        append(location?.startLine ?: -1)
        append("|")
        append(location?.startColumn ?: -1)
    }
}

/**
 * 模糊搜索结果行，支持高亮匹配字符
 */
@Composable
private fun FuzzySymbolRow(
    result: FuzzySymbolMatch,
    query: String,
    showHighlight: Boolean,
    onNavigate: (file: File, line: Int, column: Int) -> Unit,
) {
    val symbol = result.symbol
    val file = remember(symbol.filePath) { File(symbol.filePath) }
    val line = symbol.location?.startLine ?: 0
    val column = symbol.location?.startColumn ?: 0

    SymbolListItemCard(
        onClick = { onNavigate(file, line, column) }
    ) {
        Icon(
            imageVector = symbolKindIcon(symbol.kind.name),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))

        // 符号名称（带高亮）
        if (showHighlight && result.matchedIndices.isNotEmpty()) {
            HighlightedText(
                text = symbol.name,
                highlightIndices = result.matchedIndices,
                modifier = Modifier.weight(0.55f),
            )
        } else {
            Text(
                text = symbol.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = symbol.kind.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.2f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = symbol.displayDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.25f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 高亮显示匹配字符的文本
 */
@Composable
private fun HighlightedText(
    text: String,
    highlightIndices: List<Int>,
    modifier: Modifier = Modifier,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    val normalColor = MaterialTheme.colorScheme.onSurface
    val highlightSet = highlightIndices.toSet()

    val annotatedString = buildAnnotatedString {
        text.forEachIndexed { index, char ->
            if (index in highlightSet) {
                withStyle(SpanStyle(color = highlightColor, background = highlightColor.copy(alpha = 0.1f))) {
                    append(char)
                }
            } else {
                withStyle(SpanStyle(color = normalColor)) {
                    append(char)
                }
            }
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SymbolsToggleButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        minHeight = 32.dp,
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
        },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SymbolListItemCard(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    TinaDialogSelectableCard(
        selected = false,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        unselectedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
        unselectedBorder = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun LspWorkspaceSymbolRow(
    item: WorkspaceSymbolItem,
    onClick: () -> Unit,
) {
    SymbolListItemCard(
        onClick = onClick
    ) {
        Icon(
            imageVector = lspSymbolKindIcon(item.kindValue, item.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = item.kind,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.2f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val lineText = if (item.line >= 0) (item.line + 1).toString() else "?"
        val detail = if (!item.containerName.isNullOrBlank()) {
            "${item.containerName} (${item.fileName}:$lineText)"
        } else {
            "${item.fileName}:$lineText"
        }

        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.25f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LspSymbolFileHeaderRow(
    fileName: String,
    filePath: String,
    projectRoot: String?,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    val dirLabel = remember(filePath, projectRoot) {
        val parent = File(filePath).parent ?: return@remember null
        val root = projectRoot?.trim()?.trimEnd('\\', '/')?.takeIf { it.isNotBlank() }
            ?: return@remember parent
        val parentAbs = File(parent).absolutePath
        val rootPrefix = File(root).absolutePath.trimEnd('\\', '/') + File.separator
        if (parentAbs.startsWith(rootPrefix, ignoreCase = true)) {
            parentAbs.substring(rootPrefix.length)
        } else {
            parentAbs
        }
    }?.takeIf { it.isNotBlank() }
    TinaOverlayPanelSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.small,
        containerColor = bg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dirLabel != null) {
                    Text(
                        text = dirLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TinaOverlayPanelSurface(
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BoxFill(text: String, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
