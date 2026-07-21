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
 * ELF notes and symbols dialogs.
 */

@Composable
internal fun ElfNotesDialog(
    notes: List<HexElfNoteEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(notes) { mutableStateOf("") }
    var noteFilter by remember(notes) { mutableStateOf(ElfNoteFilter.ALL) }
    val filteredNotes = remember(notes, query, noteFilter) {
        filterElfNotes(
            notes = notes,
            query = query,
            noteFilter = noteFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_notes_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_notes_filter_label)) },
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
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.ALL,
                                selected = noteFilter == ElfNoteFilter.ALL,
                                onClick = { noteFilter = ElfNoteFilter.ALL }
                            )
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.BUILD_ID,
                                selected = noteFilter == ElfNoteFilter.BUILD_ID,
                                onClick = { noteFilter = ElfNoteFilter.BUILD_ID }
                            )
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.GNU,
                                selected = noteFilter == ElfNoteFilter.GNU,
                                onClick = { noteFilter = ElfNoteFilter.GNU }
                            )
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.ANDROID,
                                selected = noteFilter == ElfNoteFilter.ANDROID,
                                onClick = { noteFilter = ElfNoteFilter.ANDROID }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_notes_filter_count,
                                filteredNotes.size,
                                notes.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredNotes.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_notes_empty),
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
                                count = filteredNotes.size,
                                key = { index ->
                                    val note = filteredNotes[index]
                                    "${note.sectionName}-${note.noteFileOffset}-${note.type}"
                                }
                            ) { index ->
                                ElfNoteRow(
                                    note = filteredNotes[index],
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
internal fun ElfNoteFilterButton(
    filter: ElfNoteFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfNoteFilterLabel(filter),
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
internal fun ElfNoteRow(
    note: HexElfNoteEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(note.descriptionOffset) },
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
            val noteName = note.name.ifBlank { stringResource(Strings.hex_elf_note_name_empty) }
            Text(
                text = stringResource(Strings.hex_elf_note_title, noteName, note.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_note_meta,
                    note.sectionName.ifBlank { stringResource(Strings.hex_elf_section_name_empty) },
                    note.noteFileOffset,
                    note.descriptionOffset,
                    note.descriptionSize
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            note.descriptionText?.let { text ->
                Text(
                    text = stringResource(Strings.hex_elf_note_description_text, text),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            note.properties.forEach { property ->
                Text(
                    text = stringResource(
                        Strings.hex_elf_note_property,
                        property.typeName,
                        property.valueHex
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (property.features.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            Strings.hex_elf_note_property_features,
                            hexElfNotePropertyFeatureLabels(property.features)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = stringResource(Strings.hex_elf_note_description_hex, note.descriptionHex),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ElfSymbolsDialog(
    symbols: List<HexElfSymbol>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(symbols) { mutableStateOf("") }
    var symbolFilter by remember(symbols) { mutableStateOf(ElfSymbolFilter.ALL) }
    val filteredSymbols = remember(symbols, query, symbolFilter) {
        filterElfSymbols(
            symbols = symbols,
            query = query,
            symbolFilter = symbolFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_symbols_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_symbols_filter_label)) },
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
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.ALL,
                                selected = symbolFilter == ElfSymbolFilter.ALL,
                                onClick = { symbolFilter = ElfSymbolFilter.ALL }
                            )
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.IMPORTED,
                                selected = symbolFilter == ElfSymbolFilter.IMPORTED,
                                onClick = { symbolFilter = ElfSymbolFilter.IMPORTED }
                            )
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.EXPORTED,
                                selected = symbolFilter == ElfSymbolFilter.EXPORTED,
                                onClick = { symbolFilter = ElfSymbolFilter.EXPORTED }
                            )
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.JNI,
                                selected = symbolFilter == ElfSymbolFilter.JNI,
                                onClick = { symbolFilter = ElfSymbolFilter.JNI }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_symbols_filter_count,
                                filteredSymbols.size,
                                symbols.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredSymbols.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_symbols_empty),
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
                                count = filteredSymbols.size,
                                key = { index ->
                                    val symbol = filteredSymbols[index]
                                    "${symbol.name}-${symbol.value}-${symbol.fileOffset}"
                                }
                            ) { index ->
                                ElfSymbolRow(
                                    symbol = filteredSymbols[index],
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
internal fun ElfSymbolFilterButton(
    filter: ElfSymbolFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfSymbolFilterLabel(filter),
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
internal fun ElfSymbolRow(
    symbol: HexElfSymbol,
    onGotoOffset: (Long) -> Unit
) {
    val fileOffset = symbol.fileOffset
    if (fileOffset != null) {
        Surface(
            onClick = { onGotoOffset(fileOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSymbolRowContent(symbol)
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSymbolRowContent(symbol)
        }
    }
}

@Composable
internal fun ElfSymbolRowContent(symbol: HexElfSymbol) {
    val fileOffset = symbol.fileOffset
    val roleLabel = elfSymbolRoleLabel(symbol)
    val typeLabel = elfSymbolTypeLabel(symbol.type)
    val sectionLabel = symbol.sectionName ?: stringResource(Strings.hex_elf_symbol_section_unknown)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = symbol.name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (fileOffset != null) {
                stringResource(
                    Strings.hex_elf_symbol_meta_mapped,
                    roleLabel,
                    typeLabel,
                    sectionLabel,
                    symbol.value,
                    fileOffset
                )
            } else {
                stringResource(
                    Strings.hex_elf_symbol_meta_unmapped,
                    roleLabel,
                    typeLabel,
                    sectionLabel,
                    symbol.value
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

