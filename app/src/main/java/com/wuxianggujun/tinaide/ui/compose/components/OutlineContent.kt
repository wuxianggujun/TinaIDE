package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.ui.MainActivityNavigationHelper
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveDocumentSymbolsTargetResult

@Composable
fun OutlineContent(
    editorContainerState: EditorContainerState,
    modifier: Modifier = Modifier,
) {
    val documentSymbolsTarget = editorContainerState.getActiveDocumentSymbolsTargetResult()
    val documentSymbolsTabId =
        (documentSymbolsTarget as? ActiveDocumentSymbolsTargetResult.Available)?.tabId
    val isDocumentSymbolsAvailable = documentSymbolsTabId != null

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf<List<DocumentSymbolItem>>(emptyList()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    when (documentSymbolsTarget) {
        ActiveDocumentSymbolsTargetResult.NoOpenFile -> {
            BoxFill(stringResource(Strings.empty_editor_title), modifier)
            return
        }
        ActiveDocumentSymbolsTargetResult.Unavailable -> {
            BoxFill(stringResource(Strings.lsp_error_not_connected), modifier)
            return
        }
        is ActiveDocumentSymbolsTargetResult.Available -> Unit
    }

    val foldingEnabled = query.trim().isBlank()

    val hasChildrenMap = remember(symbols, foldingEnabled) {
        if (!foldingEnabled) {
            emptyMap()
        } else {
            val out = mutableMapOf<String, Boolean>()
            for (index in symbols.indices) {
                val item = symbols[index]
                val next = symbols.getOrNull(index + 1)
                val hasChildren = next != null && next.level > item.level
                out[item.composeStableKey()] = hasChildren
            }
            out
        }
    }

    LaunchedEffect(documentSymbolsTabId) {
        val tabId = documentSymbolsTabId ?: return@LaunchedEffect

        val lastEditAtFlow = editorContainerState.getTabLastEditAtFlow(tabId) ?: return@LaunchedEffect
        var lastEditAt: Long? = null
        lastEditAtFlow.collectLatest { currentLastEditAt ->
            if (currentLastEditAt == null || currentLastEditAt == lastEditAt) {
                return@collectLatest
            }
            lastEditAt = currentLastEditAt
            delay(800)
            refreshToken++
        }
    }

    val filtered by remember(symbols, query) {
        derivedStateOf {
            val needle = query.trim()
            if (needle.isBlank()) {
                symbols
            } else {
                symbols.filter { item ->
                    item.name.contains(needle, ignoreCase = true) ||
                        item.kind.contains(needle, ignoreCase = true) ||
                        (item.containerName?.contains(needle, ignoreCase = true) == true)
                }
            }
        }
    }

    val displaySymbols by remember(symbols, query) {
        derivedStateOf {
            val needle = query.trim()
            if (needle.isNotBlank()) {
                filtered
            } else {
                applyOutlineCollapse(symbols, collapsed)
            }
        }
    }

    LaunchedEffect(documentSymbolsTabId, refreshToken) {
        if (!isDocumentSymbolsAvailable) {
            loading = false
            symbols = emptyList()
            return@LaunchedEffect
        }
        loading = true
        symbols = emptyList()
        delay(200)
        val tabId = documentSymbolsTabId ?: return@LaunchedEffect
        symbols = runCatching { editorContainerState.documentSymbols(tabId) }
            .getOrElse { emptyList() }
            .filter { it.name.isNotBlank() }
        loading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 8.dp)
    ) {
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(Strings.outline_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                val foldingActionsEnabled = foldingEnabled && symbols.isNotEmpty()
                TinaPanelSegmentButton(
                    onClick = { collapsed.clear() },
                    enabled = foldingActionsEnabled,
                    modifier = Modifier.size(32.dp),
                    minHeight = 32.dp,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = stringResource(Strings.content_desc_expand_all),
                    )
                }

                TinaPanelSegmentButton(
                    onClick = {
                        collapsed.clear()
                        for ((key, hasChildren) in hasChildrenMap) {
                            if (hasChildren) {
                                collapsed[key] = true
                            }
                        }
                    },
                    enabled = foldingActionsEnabled,
                    modifier = Modifier.size(32.dp),
                    minHeight = 32.dp,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.UnfoldLess,
                        contentDescription = stringResource(Strings.content_desc_collapse_all),
                    )
                }
                TinaPanelSegmentButton(
                    onClick = { refreshToken++ },
                    modifier = Modifier.size(32.dp),
                    minHeight = 32.dp,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(Strings.menu_refresh),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (query.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(Strings.symbols_found_count, filtered.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
        } else if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(Strings.symbols_found_count, symbols.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = displaySymbols,
                key = { it.composeStableKey() },
            ) { item ->
                val key = item.composeStableKey()
                val hasChildren = foldingEnabled && (hasChildrenMap[key] == true)
                val isCollapsed = foldingEnabled && (collapsed[key] == true)
                DocumentSymbolRow(
                    item = item,
                    hasChildren = hasChildren,
                    collapsed = isCollapsed,
                    onToggleCollapsed = {
                        if (!foldingEnabled) return@DocumentSymbolRow
                        collapsed[key] = !(collapsed[key] == true)
                    },
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

private fun DocumentSymbolItem.composeStableKey(): String = buildString {
    append(filePath)
    append("|")
    append(line)
    append("|")
    append(column)
    append("|")
    append(level)
    append("|")
    append(name)
    append("|")
    append(kind)
    append("|")
    append(containerName ?: "")
}

@Composable
private fun DocumentSymbolRow(
    item: DocumentSymbolItem,
    hasChildren: Boolean,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onNavigate: (file: File, line: Int, column: Int) -> Unit,
) {
    val file = remember(item.filePath) { File(item.filePath) }
    val line = item.line
    val column = item.column
    val indent = (item.level.coerceAtLeast(0) * 12).dp

    TinaDialogSelectableCard(
        selected = false,
        onClick = { onNavigate(file, line, column) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        unselectedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
        unselectedBorder = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .padding(start = indent)
                    .weight(0.55f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasChildren) {
                    TinaPanelSegmentButton(
                        onClick = onToggleCollapsed,
                        modifier = Modifier.size(24.dp),
                        minHeight = 24.dp,
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(24.dp))
                }

                Icon(
                    imageVector = symbolKindIcon(item.kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = item.kind,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.2f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val detail = if (!item.containerName.isNullOrBlank()) {
                "${item.containerName} (${item.line + 1})"
            } else {
                "${item.line + 1}"
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
}

private fun applyOutlineCollapse(
    symbols: List<DocumentSymbolItem>,
    collapsed: Map<String, Boolean>,
): List<DocumentSymbolItem> {
    if (symbols.isEmpty()) return emptyList()

    val out = ArrayList<DocumentSymbolItem>(symbols.size)
    var index = 0
    while (index < symbols.size) {
        val item = symbols[index]
        out.add(item)

        val key = item.composeStableKey()
        val isCollapsed = collapsed[key] == true
        if (!isCollapsed) {
            index++
            continue
        }

        val level = item.level
        index++
        while (index < symbols.size && symbols[index].level > level) {
            index++
        }
    }

    return out
}

@Composable
private fun BoxFill(text: String, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
