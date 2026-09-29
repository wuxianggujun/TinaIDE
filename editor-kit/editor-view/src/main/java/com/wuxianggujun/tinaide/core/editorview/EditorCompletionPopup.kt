package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup

internal val completionPopupRowMinHeight = 28.dp
internal val completionPopupRowHorizontalPadding = 8.dp
internal val completionPopupRowVerticalPadding = 2.dp
internal val completionPopupRowSpacing = 6.dp
internal val completionPopupDetailSpacing = 0.dp
internal val completionPopupKindSlotSize = 18.dp
internal val completionPopupKindIconSize = 14.dp
internal val completionPopupLabelFontSize = 13.sp
internal val completionPopupDetailFontSize = 10.sp
internal const val COMPLETION_POPUP_TAG = "editor_completion_popup"
internal const val COMPLETION_POPUP_ROW_TAG_PREFIX = "editor_completion_popup_row_"
internal const val COMPLETION_POPUP_KIND_ICON_TAG_PREFIX = "editor_completion_popup_kind_icon_"

@Composable
internal fun EditorCompletionPopup(
    items: List<EditorCompletionItem>,
    selectedIndex: Int,
    query: String,
    offset: IntOffset,
    widthPx: Float,
    maxHeightPx: Float,
    colorScheme: EditorColorScheme,
    isLoading: Boolean,
    onSelectedIndexChange: (Int) -> Unit,
    onSelect: (EditorCompletionItem) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val popupColors = rememberEditorPopupColors(colorScheme)
    val popupWidthDp = with(density) { widthPx.toDp() }
    val popupHeightDp = with(density) { maxHeightPx.toDp() }

    LaunchedEffect(selectedIndex, items.size) {
        if (items.isNotEmpty() && selectedIndex in items.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Popup(
        popupPositionProvider = remember(offset) { AbsoluteWindowPopupPositionProvider(offset) },
        onDismissRequest = onDismiss
    ) {
        EditorPopupScaffold(
            colors = popupColors,
            modifier = Modifier
                .testTag(COMPLETION_POPUP_TAG)
                .width(popupWidthDp)
                .heightIn(max = popupHeightDp),
            contentModifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                EditorPopupLoadingBar(colors = popupColors)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(items) { index, item ->
                    val isSelected = index == selectedIndex
                    val highlightedLabel = remember(item.label, query, popupColors.matchTextColor) {
                        highlightMatchedChars(item.label, query, popupColors.matchTextColor)
                    }
                    val detail = item.detail?.takeIf { it.isNotBlank() }
                        ?: item.insertText.takeIf { it != item.label }?.let { text ->
                            val preview = if (text.length <= 60) text else "${text.take(57)}..."
                            "\u2192 $preview"
                        }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) {
                                    popupColors.selectedSurfaceColor
                                } else {
                                    popupColors.containerColor
                                }
                            )
                            .testTag(completionPopupRowTag(index))
                            .clickable {
                                onSelectedIndexChange(index)
                                onSelect(item)
                            }
                            .heightIn(min = completionPopupRowMinHeight)
                            .padding(
                                start = completionPopupRowHorizontalPadding,
                                end = completionPopupRowHorizontalPadding,
                                top = completionPopupRowVerticalPadding,
                                bottom = completionPopupRowVerticalPadding
                            ),
                        horizontalArrangement = Arrangement.spacedBy(completionPopupRowSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompletionKindIcon(
                            kind = item.kind,
                            modifier = Modifier.testTag(completionPopupKindIconTag(index))
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(completionPopupDetailSpacing)
                        ) {
                            Text(
                                text = highlightedLabel,
                                fontSize = completionPopupLabelFontSize,
                                lineHeight = completionPopupLabelFontSize,
                                color = popupColors.primaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!detail.isNullOrBlank()) {
                                Text(
                                    text = detail,
                                    fontSize = completionPopupDetailFontSize,
                                    lineHeight = completionPopupDetailFontSize,
                                    color = popupColors.secondaryTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionKindIcon(
    kind: EditorCompletionKind,
    modifier: Modifier = Modifier
) {
    val style = completionKindStyle(kind)
    Box(
        modifier = modifier
            .size(completionPopupKindSlotSize),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = style.icon.imageVector(),
            contentDescription = null,
            modifier = Modifier.size(completionPopupKindIconSize),
            tint = style.tintColor
        )
    }
}

internal enum class CompletionKindIconKey {
    CALLABLE,
    CODE,
    MODULE,
    PROPERTY,
    DESCRIPTION,
    FILE,
    FOLDER,
    EVENT,
    OPERATOR,
    COLOR
}

internal data class CompletionKindStyle(
    val icon: CompletionKindIconKey,
    val tintColor: Color
)

internal fun completionKindStyle(kind: EditorCompletionKind): CompletionKindStyle = when (kind) {
    EditorCompletionKind.FUNCTION ->
        CompletionKindStyle(CompletionKindIconKey.CALLABLE, Color(0xFFBD6A7D))
    EditorCompletionKind.METHOD ->
        CompletionKindStyle(CompletionKindIconKey.CALLABLE, Color(0xFFBD6A7D))
    EditorCompletionKind.CONSTRUCTOR ->
        CompletionKindStyle(CompletionKindIconKey.CALLABLE, Color(0xFFBD6A7D))
    EditorCompletionKind.FIELD ->
        CompletionKindStyle(CompletionKindIconKey.PROPERTY, Color(0xFFB98A2C))
    EditorCompletionKind.VARIABLE ->
        CompletionKindStyle(CompletionKindIconKey.PROPERTY, Color(0xFFB98A2C))
    EditorCompletionKind.CLASS ->
        CompletionKindStyle(CompletionKindIconKey.CODE, Color(0xFF4A8FB0))
    EditorCompletionKind.INTERFACE ->
        CompletionKindStyle(CompletionKindIconKey.CODE, Color(0xFF5D9B50))
    EditorCompletionKind.MODULE ->
        CompletionKindStyle(CompletionKindIconKey.MODULE, Color(0xFF4A8FB0))
    EditorCompletionKind.PROPERTY ->
        CompletionKindStyle(CompletionKindIconKey.PROPERTY, Color(0xFF8E6FC8))
    EditorCompletionKind.UNIT ->
        CompletionKindStyle(CompletionKindIconKey.DESCRIPTION, Color(0xFF7D8791))
    EditorCompletionKind.VALUE ->
        CompletionKindStyle(CompletionKindIconKey.PROPERTY, Color(0xFFB98A2C))
    EditorCompletionKind.ENUM ->
        CompletionKindStyle(CompletionKindIconKey.CODE, Color(0xFF4A8FB0))
    EditorCompletionKind.KEYWORD ->
        CompletionKindStyle(CompletionKindIconKey.DESCRIPTION, Color(0xFFCC7832))
    EditorCompletionKind.SNIPPET ->
        CompletionKindStyle(CompletionKindIconKey.DESCRIPTION, Color(0xFF7D8791))
    EditorCompletionKind.COLOR ->
        CompletionKindStyle(CompletionKindIconKey.COLOR, Color(0xFFBD6A7D))
    EditorCompletionKind.FILE ->
        CompletionKindStyle(CompletionKindIconKey.FILE, Color(0xFF7D8791))
    EditorCompletionKind.REFERENCE ->
        CompletionKindStyle(CompletionKindIconKey.DESCRIPTION, Color(0xFF7D8791))
    EditorCompletionKind.FOLDER ->
        CompletionKindStyle(CompletionKindIconKey.FOLDER, Color(0xFF7D8791))
    EditorCompletionKind.ENUM_MEMBER ->
        CompletionKindStyle(CompletionKindIconKey.PROPERTY, Color(0xFF4A8FB0))
    EditorCompletionKind.CONSTANT ->
        CompletionKindStyle(CompletionKindIconKey.PROPERTY, Color(0xFFB98A2C))
    EditorCompletionKind.STRUCT ->
        CompletionKindStyle(CompletionKindIconKey.CODE, Color(0xFF8E6FC8))
    EditorCompletionKind.EVENT ->
        CompletionKindStyle(CompletionKindIconKey.EVENT, Color(0xFF7D8791))
    EditorCompletionKind.OPERATOR ->
        CompletionKindStyle(CompletionKindIconKey.OPERATOR, Color(0xFFD06A81))
    EditorCompletionKind.TYPE_PARAMETER ->
        CompletionKindStyle(CompletionKindIconKey.PROPERTY, Color(0xFFB98A2C))
    EditorCompletionKind.TEXT ->
        CompletionKindStyle(CompletionKindIconKey.DESCRIPTION, Color(0xFF7D8791))
}

private fun CompletionKindIconKey.imageVector(): ImageVector = when (this) {
    CompletionKindIconKey.CALLABLE -> Icons.Default.Functions
    CompletionKindIconKey.CODE -> Icons.Default.Code
    CompletionKindIconKey.MODULE -> Icons.Default.Source
    CompletionKindIconKey.PROPERTY -> Icons.Default.Tag
    CompletionKindIconKey.DESCRIPTION -> Icons.Default.Description
    CompletionKindIconKey.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
    CompletionKindIconKey.FOLDER -> Icons.Default.Folder
    CompletionKindIconKey.EVENT -> Icons.Default.Warning
    CompletionKindIconKey.OPERATOR -> Icons.Default.Build
    CompletionKindIconKey.COLOR -> Icons.Default.Palette
}

internal fun completionPopupRowTag(index: Int): String = "$COMPLETION_POPUP_ROW_TAG_PREFIX$index"

internal fun completionPopupKindIconTag(index: Int): String = "$COMPLETION_POPUP_KIND_ICON_TAG_PREFIX$index"

internal fun highlightMatchedChars(
    label: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(label)

    val matchIndices = fuzzyMatchIndices(label, query)
    if (matchIndices.isEmpty()) return AnnotatedString(label)

    return buildAnnotatedString {
        append(label)
        val style = SpanStyle(color = highlightColor)
        for (i in matchIndices) {
            addStyle(style, start = i, end = i + 1)
        }
    }
}

internal fun fuzzyMatchIndices(label: String, query: String): List<Int> {
    val result = ArrayList<Int>(query.length)
    var labelIdx = 0
    for (queryChar in query) {
        val qLower = queryChar.lowercaseChar()
        while (labelIdx < label.length) {
            if (label[labelIdx].lowercaseChar() == qLower) {
                result.add(labelIdx)
                labelIdx++
                break
            }
            labelIdx++
        }
        if (labelIdx >= label.length && result.size < query.length) {
            return emptyList()
        }
    }
    return if (result.size == query.length) result else emptyList()
}
