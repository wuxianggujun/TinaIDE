package com.wuxianggujun.tinaide.ui.compose.viewer

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hex content rows, visual row shell, placeholder line, and context menu.
 */

@Composable
internal fun HexContent(
    dataManager: HexFileDataManager,
    listState: LazyListState,
    cacheVersion: Int,
    selectedOffset: Long,
    selectionRange: HexSelectionRange?,
    pendingNibble: String,
    patchMap: Map<Long, HexPatch>,
    bookmarkedOffsets: Set<Long>,
    onCacheVersionChanged: () -> Unit,
    onOffsetSelected: (Long) -> Unit,
    onByteLongPressed: (HexContextTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(listState.firstVisibleItemIndex, cacheVersion) {
        if (dataManager.preloadAroundRow(listState.firstVisibleItemIndex)) {
            onCacheVersionChanged()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        items(
            count = dataManager.totalRows,
            key = { it }
        ) { lineIndex ->
            val line = remember(cacheVersion, lineIndex) {
                dataManager.getCachedRow(lineIndex)
            }

            LaunchedEffect(lineIndex, cacheVersion) {
                if (line == null && dataManager.loadChunkForRow(lineIndex)) {
                    onCacheVersionChanged()
                }
            }

            if (line == null) {
                HexPlaceholderLine(offset = dataManager.getRowOffset(lineIndex))
            } else {
                HexByteRowBlock(
                    row = line,
                    selectedOffset = selectedOffset,
                    selectionRange = selectionRange,
                    pendingNibble = pendingNibble,
                    patchMap = patchMap,
                    bookmarkedOffsets = bookmarkedOffsets,
                    onOffsetSelected = onOffsetSelected,
                    onByteLongPressed = onByteLongPressed
                )
            }
        }
    }
}

@Composable
internal fun HexByteRowBlock(
    row: HexByteRow,
    selectedOffset: Long,
    selectionRange: HexSelectionRange?,
    pendingNibble: String,
    patchMap: Map<Long, HexPatch>,
    bookmarkedOffsets: Set<Long>,
    onOffsetSelected: (Long) -> Unit,
    onByteLongPressed: (HexContextTarget) -> Unit
) {
    val firstRowBytes = row.bytes.take(HexFileDataManager.VISUAL_BYTES_PER_ROW)
    val secondRowBytes = row.bytes.drop(HexFileDataManager.VISUAL_BYTES_PER_ROW)

    HexVisualRow(
        offset = row.offset,
        bytes = firstRowBytes,
        selectedOffset = selectedOffset,
        selectionRange = selectionRange,
        pendingNibble = pendingNibble,
        patchMap = patchMap,
        bookmarkedOffsets = bookmarkedOffsets,
        isEven = (row.offset / HexFileDataManager.VISUAL_BYTES_PER_ROW) % 2L == 0L,
        onOffsetSelected = onOffsetSelected,
        onByteLongPressed = onByteLongPressed
    )

    if (secondRowBytes.isNotEmpty()) {
        HexVisualRow(
            offset = row.offset + HexFileDataManager.VISUAL_BYTES_PER_ROW,
            bytes = secondRowBytes,
            selectedOffset = selectedOffset,
            selectionRange = selectionRange,
            pendingNibble = pendingNibble,
            patchMap = patchMap,
            bookmarkedOffsets = bookmarkedOffsets,
            isEven = ((row.offset + HexFileDataManager.VISUAL_BYTES_PER_ROW) / HexFileDataManager.VISUAL_BYTES_PER_ROW) % 2L == 0L,
            onOffsetSelected = onOffsetSelected,
            onByteLongPressed = onByteLongPressed
        )
    }
}

@Composable
internal fun HexVisualRow(
    offset: Long,
    bytes: List<Byte>,
    selectedOffset: Long,
    selectionRange: HexSelectionRange?,
    pendingNibble: String,
    patchMap: Map<Long, HexPatch>,
    bookmarkedOffsets: Set<Long>,
    isEven: Boolean,
    onOffsetSelected: (Long) -> Unit,
    onByteLongPressed: (HexContextTarget) -> Unit
) {
    val rowStartOffset = offset
    val rowEndOffset = offset + bytes.size - 1
    val rowSelected = selectedOffset in rowStartOffset..rowEndOffset
    val selectedColumn = (selectedOffset % HexFileDataManager.VISUAL_BYTES_PER_ROW).toInt()
    val density = LocalDensity.current
    val dividerPx = with(density) { 1.dp.toPx() }
    var hexRowWidthPx by remember { mutableIntStateOf(0) }
    val backgroundColor = if (isEven) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val rangeColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.64f)
    val patchedColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
    val bookmarkColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.54f)
    val columnHighlight = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.36f)
    val byteTextColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(backgroundColor)
            .padding(end = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(AddressColumnWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%08X".format(offset),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        VerticalDivider()

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { hexRowWidthPx = it.width }
                .pointerInput(offset, bytes.size, hexRowWidthPx) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val column = computeHexColumn(
                                tapX = tapOffset.x,
                                totalWidth = hexRowWidthPx.toFloat(),
                                dividerWidth = dividerPx,
                                byteCount = bytes.size
                            )
                            if (column in bytes.indices) {
                                onOffsetSelected(offset + column)
                            }
                        },
                        onLongPress = { tapOffset ->
                            val column = computeHexColumn(
                                tapX = tapOffset.x,
                                totalWidth = hexRowWidthPx.toFloat(),
                                dividerWidth = dividerPx,
                                byteCount = bytes.size
                            )
                            if (column in bytes.indices) {
                                val targetOffset = offset + column
                                val displayByte = patchMap[targetOffset]?.newByte ?: bytes[column]
                                onByteLongPressed(HexContextTarget(offset = targetOffset, byte = displayByte))
                            }
                        }
                    )
                }
                .fillMaxWidth()
                .padding(vertical = 1.dp)
        ) {
            bytes.forEachIndexed { column, byte ->
                if (column == 4) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                val byteOffset = offset + column
                val displayByte = patchMap[byteOffset]?.newByte ?: byte
                val selected = byteOffset == selectedOffset
                val inSelectionRange = selectionRange?.contains(byteOffset) == true
                val patched = patchMap.containsKey(byteOffset)
                val bookmarked = bookmarkedOffsets.contains(byteOffset)
                val highlighted = !selected && (rowSelected || column == selectedColumn)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            when {
                                selected -> selectedColor
                                inSelectionRange -> rangeColor
                                patched -> patchedColor
                                bookmarked -> bookmarkColor
                                highlighted -> columnHighlight
                                else -> Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selected && pendingNibble.isNotEmpty()) {
                            pendingNibble
                        } else {
                            displayByte.toHexCellText()
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = if (selected) selectedTextColor else byteTextColor,
                        textAlign = TextAlign.Center
                    )
                }
            }

            repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW - bytes.size) { padIndex ->
                val column = bytes.size + padIndex
                if (column == 4) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        VerticalDivider()

        Row(
            modifier = Modifier
                .width(AsciiColumnWidth)
                .fillMaxHeight()
                .pointerInput(offset, bytes.size) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val charWidthPx = size.width / HexFileDataManager.VISUAL_BYTES_PER_ROW.toFloat()
                            val column = (tapOffset.x / charWidthPx).toInt().coerceIn(0, bytes.lastIndex)
                            if (column in bytes.indices) {
                                onOffsetSelected(offset + column)
                            }
                        },
                        onLongPress = { tapOffset ->
                            val charWidthPx = size.width / HexFileDataManager.VISUAL_BYTES_PER_ROW.toFloat()
                            val column = (tapOffset.x / charWidthPx).toInt().coerceIn(0, bytes.lastIndex)
                            if (column in bytes.indices) {
                                val targetOffset = offset + column
                                val displayByte = patchMap[targetOffset]?.newByte ?: bytes[column]
                                onByteLongPressed(HexContextTarget(offset = targetOffset, byte = displayByte))
                            }
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            bytes.forEachIndexed { column, byte ->
                val byteOffset = offset + column
                val displayByte = patchMap[byteOffset]?.newByte ?: byte
                val selected = byteOffset == selectedOffset
                val inSelectionRange = selectionRange?.contains(byteOffset) == true
                val patched = patchMap.containsKey(byteOffset)
                val bookmarked = bookmarkedOffsets.contains(byteOffset)
                val highlighted = !selected && (rowSelected || column == selectedColumn)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            when {
                                selected -> selectedColor
                                inSelectionRange -> rangeColor
                                patched -> patchedColor
                                bookmarked -> bookmarkColor
                                highlighted -> columnHighlight
                                else -> Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayByte.toPrintableAscii(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = if (selected) selectedTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW - bytes.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun HexContextMenu(
    target: HexContextTarget?,
    onDismiss: () -> Unit,
    onCopyOffset: (HexContextTarget) -> Unit,
    onCopyByte: (HexContextTarget) -> Unit,
    onCopyAscii: (HexContextTarget) -> Unit,
    onSetSelectionStart: (HexContextTarget) -> Unit,
    onSetSelectionEnd: (HexContextTarget) -> Unit,
    onExportSelection: () -> Unit,
    onToggleBookmark: (HexContextTarget) -> Unit,
    onEditHere: (HexContextTarget) -> Unit
) {
    DropdownMenu(
        expanded = target != null,
        onDismissRequest = onDismiss
    ) {
        if (target != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_copy_offset)) },
                onClick = {
                    onCopyOffset(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_copy_byte)) },
                onClick = {
                    onCopyByte(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_copy_ascii)) },
                onClick = {
                    onCopyAscii(target)
                    onDismiss()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_set_selection_start)) },
                onClick = {
                    onSetSelectionStart(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_set_selection_end)) },
                onClick = {
                    onSetSelectionEnd(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_export_selection)) },
                onClick = {
                    onExportSelection()
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_toggle_bookmark)) },
                onClick = {
                    onToggleBookmark(target)
                    onDismiss()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_edit_here)) },
                onClick = {
                    onEditHere(target)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
internal fun HexPlaceholderLine(offset: Long) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest

    HexVisualRowShell(offset = offset, backgroundColor = MaterialTheme.colorScheme.surface) {
        repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW) { column ->
            if (column == 4) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .padding(horizontal = 3.dp)
                    .background(placeholderColor, MaterialTheme.shapes.extraSmall)
            )
        }
    }
}

@Composable
internal fun HexVisualRowShell(
    offset: Long,
    backgroundColor: Color,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(backgroundColor)
            .padding(end = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(AddressColumnWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%08X".format(offset),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        VerticalDivider()
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        VerticalDivider()
        Spacer(modifier = Modifier.width(AsciiColumnWidth))
    }
}

