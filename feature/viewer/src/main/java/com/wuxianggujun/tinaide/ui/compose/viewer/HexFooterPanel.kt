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
 * Hex footer toolbar, bookmarks, and patch details dialogs.
 */

@Composable
internal fun HexFooter(
    state: HexViewerState,
    canEdit: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onToggleEditMode: () -> Unit,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onMarkSelectionStart: () -> Unit,
    onMarkSelectionEnd: () -> Unit,
    onClearSelection: () -> Unit,
    onInspectSelection: () -> Unit,
    onExportSelection: () -> Unit,
    onUndoPatch: () -> Unit,
    onRedoPatch: () -> Unit,
    onDiscardPatch: (Long) -> Unit,
    onSavePatches: () -> Unit,
    onDiscardPatches: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var showGotoDialog by remember { mutableStateOf(false) }
    var showPatchDetailsDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var areFooterToolsExpanded by remember { mutableStateOf(initialHexFooterToolsExpanded()) }
    val horizontalScrollState = rememberScrollState()
    val selectionRange = state.selectionRange
    val isSelectedBookmarked = isHexBookmarked(state.bookmarkedOffsets, state.selectedOffset)
    val hasPatchActivity = state.stagedPatches.isNotEmpty() || state.redoPatches.isNotEmpty()
    val showFooterDetails = shouldShowHexFooterDetails(
        isUserExpanded = areFooterToolsExpanded,
        hasPatchActivity = hasPatchActivity
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onToggleEditMode,
                    enabled = canEdit,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (state.isEditMode) Icons.Filled.Keyboard else Icons.Filled.Edit,
                        contentDescription = stringResource(Strings.hex_edit_toggle_desc),
                        tint = if (state.isEditMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Text(
                    text = stringResource(Strings.hex_footer_size, formatFileSize(state.fileSize)),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = stringResource(Strings.hex_footer_offset).format(state.currentOffset),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = stringResource(Strings.hex_selected_offset, state.selectedOffset),
                    style = MaterialTheme.typography.bodySmall
                )

                TextButton(onClick = onGoBack, enabled = canGoBack) {
                    Text(stringResource(Strings.hex_history_back))
                }
                TextButton(onClick = onGoForward, enabled = canGoForward) {
                    Text(stringResource(Strings.hex_history_forward))
                }
                TextButton(onClick = { showGotoDialog = true }, enabled = canEdit) {
                    Text(stringResource(Strings.btn_goto))
                }
                Text(
                    text = stringResource(Strings.hex_bookmark_count, state.bookmarkedOffsets.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onToggleBookmark, enabled = canEdit) {
                    Text(
                        stringResource(
                            if (isSelectedBookmarked) {
                                Strings.hex_bookmark_unmark
                            } else {
                                Strings.hex_bookmark_mark
                            }
                        )
                    )
                }
                TextButton(
                    onClick = { showBookmarksDialog = true },
                    enabled = state.bookmarkedOffsets.isNotEmpty()
                ) {
                    Text(stringResource(Strings.hex_bookmark_list))
                }
                TextButton(onClick = { areFooterToolsExpanded = !areFooterToolsExpanded }) {
                    Text(
                        stringResource(
                            if (areFooterToolsExpanded) {
                                Strings.content_desc_collapse
                            } else {
                                Strings.action_more
                            }
                        )
                    )
                }
            }

            if (showFooterDetails) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (selectionRange == null) {
                            stringResource(Strings.hex_selection_empty)
                        } else {
                            stringResource(
                                Strings.hex_selection_range,
                                selectionRange.firstOffset,
                                selectionRange.lastOffset,
                                selectionRange.byteCount
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onMarkSelectionStart, enabled = canEdit) {
                        Text(stringResource(Strings.hex_mark_start))
                    }
                    TextButton(onClick = onMarkSelectionEnd, enabled = canEdit) {
                        Text(stringResource(Strings.hex_mark_end))
                    }
                    TextButton(onClick = onClearSelection, enabled = selectionRange != null) {
                        Text(stringResource(Strings.hex_clear_selection))
                    }
                    TextButton(onClick = onInspectSelection, enabled = canEdit) {
                        Text(stringResource(Strings.hex_inspect_selection))
                    }
                    TextButton(onClick = onExportSelection, enabled = canEdit) {
                        Text(stringResource(Strings.hex_export_selection))
                    }
                }
            }

            if (showFooterDetails && hasPatchActivity) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_patch_pending, state.stagedPatches.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showPatchDetailsDialog = true }, enabled = state.stagedPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_details))
                    }
                    TextButton(onClick = onUndoPatch, enabled = state.stagedPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_undo))
                    }
                    TextButton(onClick = onRedoPatch, enabled = state.redoPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_redo))
                    }
                    TextButton(onClick = onSavePatches, enabled = state.stagedPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_save))
                    }
                    TextButton(onClick = onDiscardPatches, enabled = state.stagedPatches.isNotEmpty() || state.redoPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_discard))
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showGotoDialog) {
        GotoOffsetDialog(
            maxOffset = state.fileSize,
            currentOffset = state.selectedOffset,
            onDismiss = { showGotoDialog = false },
            onConfirm = { offset ->
                onGotoOffset(offset)
                showGotoDialog = false
            }
        )
    }

    if (showPatchDetailsDialog) {
        HexPatchDetailsDialog(
            patches = sortHexPatchesForDisplay(state.stagedPatches),
            onDismiss = { showPatchDetailsDialog = false },
            onGotoOffset = { offset ->
                showPatchDetailsDialog = false
                onGotoOffset(offset)
            },
            onDiscardPatch = onDiscardPatch
        )
    }

    if (showBookmarksDialog) {
        HexBookmarksDialog(
            bookmarks = sortHexBookmarks(state.bookmarkedOffsets),
            currentOffset = state.selectedOffset,
            onDismiss = { showBookmarksDialog = false },
            onGotoOffset = { offset ->
                showBookmarksDialog = false
                onGotoOffset(offset)
            },
            onRemoveBookmark = onRemoveBookmark
        )
    }
}

@Composable
internal fun HexBookmarksDialog(
    bookmarks: List<Long>,
    currentOffset: Long,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_bookmarks_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Text(
                        text = stringResource(Strings.hex_bookmarks_summary, bookmarks.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TinaDialogCard {
                    if (bookmarks.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_bookmarks_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = bookmarks.size,
                                key = { index -> bookmarks[index] }
                            ) { index ->
                                HexBookmarkRow(
                                    offset = bookmarks[index],
                                    isCurrent = bookmarks[index] == currentOffset,
                                    onGotoOffset = onGotoOffset,
                                    onRemoveBookmark = onRemoveBookmark
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
internal fun HexBookmarkRow(
    offset: Long,
    isCurrent: Boolean,
    onGotoOffset: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(Strings.hex_bookmark_row_meta, offset),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                if (isCurrent) {
                    Text(
                        text = stringResource(Strings.hex_bookmark_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(onClick = { onGotoOffset(offset) }) {
                Text(stringResource(Strings.hex_bookmark_jump))
            }
            TextButton(onClick = { onRemoveBookmark(offset) }) {
                Text(stringResource(Strings.hex_bookmark_remove))
            }
        }
    }
}

@Composable
internal fun HexPatchDetailsDialog(
    patches: List<HexPatch>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onDiscardPatch: (Long) -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_patch_details_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.hex_patch_details_summary, patches.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipData
                                            .newPlainText("hex-patch-r2", formatHexPatchScript(patches))
                                            .toClipEntry()
                                    )
                                }
                            },
                            enabled = patches.isNotEmpty()
                        ) {
                            Text(stringResource(Strings.hex_patch_copy_r2))
                        }
                    }
                }
                TinaDialogCard {
                    if (patches.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_patch_details_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = patches.size,
                                key = { index -> patches[index].offset }
                            ) { index ->
                                HexPatchDetailsRow(
                                    patch = patches[index],
                                    onGotoOffset = onGotoOffset,
                                    onDiscardPatch = onDiscardPatch
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
internal fun HexPatchDetailsRow(
    patch: HexPatch,
    onGotoOffset: (Long) -> Unit,
    onDiscardPatch: (Long) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(Strings.hex_patch_row_meta, patch.offset),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        Strings.hex_patch_row_change,
                        patch.originalByte.toHexCellText(),
                        patch.newByte.toHexCellText()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onGotoOffset(patch.offset) }) {
                Text(stringResource(Strings.hex_patch_jump))
            }
            TextButton(onClick = { onDiscardPatch(patch.offset) }) {
                Text(stringResource(Strings.hex_patch_discard_one))
            }
        }
    }
}
