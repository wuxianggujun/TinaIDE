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
 * ELF dynamic entries and flags dialogs.
 */

@Composable
internal fun ElfDynamicEntriesDialog(
    entries: List<HexElfDynamicStringEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var dynamicEntryFilter by remember(entries) { mutableStateOf(ElfDynamicEntryFilter.ALL) }
    val filteredEntries = remember(entries, query, dynamicEntryFilter) {
        filterElfDynamicEntries(
            entries = entries,
            query = query,
            dynamicEntryFilter = dynamicEntryFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_dynamic_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_dynamic_filter_label)) },
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
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.ALL,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.ALL,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.ALL }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.NEEDED,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.NEEDED,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.NEEDED }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.SONAME,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.SONAME,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.SONAME }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.RPATH,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.RPATH,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.RPATH }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.RUNPATH,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.RUNPATH,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.RUNPATH }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_dynamic_filter_count,
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
                            text = stringResource(Strings.hex_elf_dynamic_empty),
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
                                    "${entry.type}-${entry.entryFileOffset}-${entry.value}"
                                }
                            ) { index ->
                                ElfDynamicEntryRow(
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
internal fun ElfDynamicEntryFilterButton(
    filter: ElfDynamicEntryFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfDynamicEntryFilterLabel(filter),
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
internal fun ElfDynamicEntryRow(
    entry: HexElfDynamicStringEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.entryFileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_dynamic_meta,
                    elfDynamicStringTypeLabel(entry.type),
                    entry.entryFileOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_dynamic_semantic,
                    elfDynamicStringSemanticLabel(entry.semantic)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.loadOrder?.let { loadOrder ->
                Text(
                    text = stringResource(Strings.hex_elf_dynamic_load_order, loadOrder),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun ElfDynamicFlagsDialog(
    entries: List<HexElfDynamicFlagEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var dynamicFlagFilter by remember(entries) { mutableStateOf(ElfDynamicFlagFilter.ALL) }
    val filteredEntries = remember(entries, query, dynamicFlagFilter) {
        filterElfDynamicFlags(
            entries = entries,
            query = query,
            dynamicFlagFilter = dynamicFlagFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_dynamic_flags_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_dynamic_flags_filter_label)) },
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
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.ALL,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.ALL,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.ALL }
                            )
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.BIND_NOW,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.BIND_NOW,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.BIND_NOW }
                            )
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.FLAGS,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.FLAGS,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.FLAGS }
                            )
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.FLAGS_1,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.FLAGS_1,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.FLAGS_1 }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_dynamic_flags_filter_count,
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
                            text = stringResource(Strings.hex_elf_dynamic_flags_empty),
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
                                    "${entry.type}-${entry.entryFileOffset}-${entry.value}"
                                }
                            ) { index ->
                                ElfDynamicFlagRow(
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
internal fun ElfDynamicFlagFilterButton(
    filter: ElfDynamicFlagFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfDynamicFlagFilterLabel(filter),
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
internal fun ElfDynamicFlagRow(
    entry: HexElfDynamicFlagEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.entryFileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = elfDynamicFlagTypeLabel(entry.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_dynamic_flag_meta,
                    entry.value,
                    entry.entryFileOffset,
                    elfDynamicFlagBindNowLabel(entry.isBindNow)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

