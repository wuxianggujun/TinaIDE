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
 * Archive native libraries, zip structure, and signing block dialogs.
 */

@Composable
internal fun ArchiveNativeLibrariesDialog(
    entries: List<HexArchiveNativeLibrarySummary>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var loadModeFilter by remember(entries) { mutableStateOf(ArchiveNativeLibraryLoadModeFilter.ALL) }
    val filteredEntries = remember(entries, query, loadModeFilter) {
        filterArchiveNativeLibrarySummaries(
            entries = entries,
            query = query,
            loadModeFilter = loadModeFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_native_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_archive_native_dialog_meta,
                                entries.size,
                                entries.nativeLibraryAbiPreview(),
                                entries.sumOf { entry -> entry.obfuscationMarkers.size }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_archive_native_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.ALL,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.ALL,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.ALL }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.UNKNOWN,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.UNKNOWN,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.UNKNOWN }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_archive_native_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_archive_native_empty),
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
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.localHeaderOffset}-${entry.entryName}"
                                }
                            ) { index ->
                                ArchiveNativeLibraryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
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
internal fun ArchiveNativeLoadModeFilterButton(
    filter: ArchiveNativeLibraryLoadModeFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = archiveNativeLoadModeFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
internal fun ArchiveNativeLibraryRow(
    entry: HexArchiveNativeLibrarySummary,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_archive_native_entry_display,
                    entry.abi.ifBlank { stringResource(Strings.hex_archive_native_unknown) },
                    entry.fileName
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_native_entry_meta,
                    entry.machineName ?: stringResource(Strings.hex_archive_native_unknown),
                    archiveNativeBitnessLabel(entry),
                    entry.compressionMethod,
                    entry.compressedSize,
                    entry.uncompressedSize,
                    entry.analyzedBytes,
                    archiveDexTruncatedLabel(entry.truncated),
                    entry.crc32,
                    entry.localHeaderOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_native_load_mode,
                    archiveNativeLoadModeLabel(entry.loadMode),
                    archiveNativePageAlignmentLabel(entry)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.obfuscationMarkers.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { marker ->
                Text(
                    text = stringResource(
                        Strings.hex_archive_native_obfuscation_marker,
                        hexObfuscationFindingLabel(marker.type),
                        marker.evidence.compactForAnalysisPanel(),
                        marker.relativeOffset ?: 0L
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onGotoOffset(entry.localHeaderOffset) }) {
                    Text(stringResource(Strings.hex_archive_entry_jump_local))
                }
                entry.dataOffset?.let { dataOffset ->
                    TextButton(onClick = { onGotoOffset(dataOffset) }) {
                        Text(stringResource(Strings.hex_archive_entry_jump_data))
                    }
                }
            }
        }
    }
}

@Composable
internal fun archiveNativeBitnessLabel(entry: HexArchiveNativeLibrarySummary): String = when {
    !entry.isElf -> stringResource(Strings.hex_archive_native_not_elf)
    entry.is64Bit == true && entry.endian != null -> stringResource(
        Strings.hex_archive_native_elf64,
        hexEndianLabel(entry.endian)
    )
    entry.is64Bit == false && entry.endian != null -> stringResource(
        Strings.hex_archive_native_elf32,
        hexEndianLabel(entry.endian)
    )
    else -> stringResource(Strings.hex_archive_native_unknown)
}

@Composable
internal fun archiveNativeLoadModeFilterLabel(filter: ArchiveNativeLibraryLoadModeFilter): String = stringResource(
    when (filter) {
        ArchiveNativeLibraryLoadModeFilter.ALL -> Strings.hex_archive_native_load_filter_all
        ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY -> Strings.hex_archive_native_load_filter_direct_mmap
        ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED -> Strings.hex_archive_native_load_filter_stored_unaligned
        ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION -> Strings.hex_archive_native_load_filter_needs_decompression
        ArchiveNativeLibraryLoadModeFilter.UNKNOWN -> Strings.hex_archive_native_load_filter_unknown
    }
)

@Composable
internal fun archiveNativeLoadModeLabel(loadMode: HexArchiveNativeLoadMode): String = stringResource(
    when (loadMode) {
        HexArchiveNativeLoadMode.DIRECT_MMAP_READY -> Strings.hex_archive_native_load_direct_mmap
        HexArchiveNativeLoadMode.STORED_UNALIGNED -> Strings.hex_archive_native_load_stored_unaligned
        HexArchiveNativeLoadMode.NEEDS_DECOMPRESSION -> Strings.hex_archive_native_load_needs_decompression
        HexArchiveNativeLoadMode.UNKNOWN -> Strings.hex_archive_native_load_unknown
    }
)

@Composable
internal fun archiveNativePageAlignmentLabel(entry: HexArchiveNativeLibrarySummary): String {
    val remainder = entry.pageAlignmentRemainder ?: return stringResource(Strings.hex_archive_native_unknown)
    return if (remainder == 0L) {
        stringResource(Strings.hex_archive_native_page_aligned)
    } else {
        stringResource(Strings.hex_archive_native_page_remainder, remainder)
    }
}

@Composable
internal fun ArchiveZipStructureDialog(
    structure: HexArchiveZipStructure,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_zip_structure_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ArchiveZipStructureRow(
                            title = stringResource(Strings.hex_archive_zip_structure_central_directory),
                            meta = stringResource(
                                Strings.hex_archive_zip_structure_central_meta,
                                structure.centralDirectoryOffset,
                                structure.centralDirectorySize,
                                structure.entryCount
                            ),
                            offset = structure.centralDirectoryOffset,
                            onGotoOffset = onGotoOffset
                        )
                        ArchiveZipStructureRow(
                            title = stringResource(Strings.hex_archive_zip_structure_eocd),
                            meta = stringResource(
                                Strings.hex_archive_zip_structure_eocd_meta,
                                structure.eocdOffset,
                                structure.commentLength
                            ),
                            offset = structure.eocdOffset,
                            onGotoOffset = onGotoOffset
                        )
                        structure.zip64LocatorOffset?.let { offset ->
                            ArchiveZipStructureRow(
                                title = stringResource(Strings.hex_archive_zip_structure_zip64_locator),
                                meta = stringResource(
                                    Strings.hex_archive_zip_structure_zip64_meta,
                                    offset
                                ),
                                offset = offset,
                                onGotoOffset = onGotoOffset
                            )
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
internal fun ArchiveZipStructureRow(
    title: String,
    meta: String,
    offset: Long,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(offset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ArchiveSigningBlockDialog(
    entries: List<HexArchiveSigningBlockEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterArchiveSigningBlockEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_signing_block_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_archive_signing_block_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_archive_signing_block_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_archive_signing_block_empty),
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
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.id}-${entry.pairOffset}"
                                }
                            ) { index ->
                                ArchiveSigningBlockRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
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
internal fun ArchiveSigningBlockRow(
    entry: HexArchiveSigningBlockEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.pairOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_archive_signing_block_display,
                    entry.idName,
                    entry.id
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_signing_block_meta,
                    entry.index,
                    entry.valueSize,
                    entry.blockOffset,
                    entry.blockSize,
                    entry.pairOffset,
                    entry.valueOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

