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
 * ELF runtime analysis dialogs (relocations, linkage, risk, JNI).
 */

@Composable
internal fun ElfSectionEntropyDialog(
    entries: List<HexElfSectionEntropyEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var entropyFilter by remember(entries) { mutableStateOf(EntropyBucketFilter.ALL) }
    val filteredEntries = remember(entries, query, entropyFilter) {
        filterElfSectionEntropyEntries(
            entries = entries,
            query = query,
            entropyFilter = entropyFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_section_entropy_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_section_entropy_filter_label)) },
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
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.ALL,
                                selected = entropyFilter == EntropyBucketFilter.ALL,
                                onClick = { entropyFilter = EntropyBucketFilter.ALL }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.LOW,
                                selected = entropyFilter == EntropyBucketFilter.LOW,
                                onClick = { entropyFilter = EntropyBucketFilter.LOW }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.MEDIUM,
                                selected = entropyFilter == EntropyBucketFilter.MEDIUM,
                                onClick = { entropyFilter = EntropyBucketFilter.MEDIUM }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.HIGH,
                                selected = entropyFilter == EntropyBucketFilter.HIGH,
                                onClick = { entropyFilter = EntropyBucketFilter.HIGH }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_section_entropy_filter_count,
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
                            text = stringResource(Strings.hex_elf_section_entropy_empty),
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
                                    "${entry.sectionIndex}-${entry.fileOffset}-${entry.sampleSize}"
                                }
                            ) { index ->
                                ElfSectionEntropyRow(
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
internal fun ElfSectionEntropyRow(
    entry: HexElfSectionEntropyEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.fileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(34.dp)
                    .background(entropyBucketColor(entry.level))
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val unnamedSection = stringResource(Strings.hex_elf_section_name_empty)
                val sectionName = entry.sectionName.ifBlank { unnamedSection }
                Text(
                    text = stringResource(
                        Strings.hex_elf_section_entropy_display,
                        sectionName,
                        entropyLevelLabel(entry.level),
                        entry.entropy
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        Strings.hex_elf_section_entropy_meta,
                        entry.sectionIndex,
                        entry.virtualAddress,
                        entry.fileOffset,
                        entry.size,
                        entry.sampleSize
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun ElfInitArrayDialog(
    entries: List<HexElfInitArrayEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_init_array_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    if (entries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_init_array_empty),
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
                                count = entries.size,
                                key = { index ->
                                    val entry = entries[index]
                                    "${entry.index}-${entry.pointerFileOffset}-${entry.functionAddress}"
                                }
                            ) { index ->
                                ElfInitArrayRow(
                                    entry = entries[index],
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
internal fun ElfInitArrayRow(
    entry: HexElfInitArrayEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = entry.functionFileOffset ?: entry.pointerFileOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
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
                text = if (entry.functionFileOffset != null) {
                    stringResource(
                        Strings.hex_elf_init_array_meta_mapped,
                        entry.index,
                        entry.pointerFileOffset,
                        entry.functionAddress,
                        entry.functionFileOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_init_array_meta_unmapped,
                        entry.index,
                        entry.pointerFileOffset,
                        entry.functionAddress
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ElfRelocationsDialog(
    relocations: List<HexElfRelocationEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(relocations) { mutableStateOf("") }
    var relocationFilter by remember(relocations) { mutableStateOf(ElfRelocationFilter.ALL) }
    val filteredRelocations = remember(relocations, query, relocationFilter) {
        filterElfRelocations(
            relocations = relocations,
            query = query,
            relocationFilter = relocationFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_relocations_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_relocations_filter_label)) },
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
                            ElfRelocationFilterButton(
                                filter = ElfRelocationFilter.ALL,
                                selected = relocationFilter == ElfRelocationFilter.ALL,
                                onClick = { relocationFilter = ElfRelocationFilter.ALL }
                            )
                            ElfRelocationFilterButton(
                                filter = ElfRelocationFilter.PLT,
                                selected = relocationFilter == ElfRelocationFilter.PLT,
                                onClick = { relocationFilter = ElfRelocationFilter.PLT }
                            )
                            ElfRelocationFilterButton(
                                filter = ElfRelocationFilter.DYNAMIC,
                                selected = relocationFilter == ElfRelocationFilter.DYNAMIC,
                                onClick = { relocationFilter = ElfRelocationFilter.DYNAMIC }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_relocations_filter_count,
                                filteredRelocations.size,
                                relocations.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredRelocations.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_relocations_empty),
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
                                count = filteredRelocations.size,
                                key = { index ->
                                    val relocation = filteredRelocations[index]
                                    "${relocation.sectionName}-${relocation.relocationFileOffset}-${relocation.offsetAddress}"
                                }
                            ) { index ->
                                ElfRelocationRow(
                                    relocation = filteredRelocations[index],
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
internal fun ElfRelocationFilterButton(
    filter: ElfRelocationFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfRelocationFilterLabel(filter),
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
internal fun ElfRelocationRow(
    relocation: HexElfRelocationEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = relocation.offsetFileOffset ?: relocation.relocationFileOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
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
                text = relocation.symbolName
                    ?: stringResource(Strings.hex_elf_relocation_symbol_index, relocation.symbolIndex),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = relocationMetaText(relocation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_relocation_semantic,
                    elfRelocationSemanticLabel(relocation.semantic)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun relocationMetaText(relocation: HexElfRelocationEntry): String {
    val sectionName = relocation.sectionName.ifBlank { stringResource(Strings.hex_elf_section_name_empty) }
    val typeLabel = relocationTypeLabel(relocation)
    val symbolLabel = relocationSymbolMetaLabel(relocation)
    val targetSectionLabel = relocationTargetSectionLabel(relocation)
    val addendLabel = relocation.addend?.let { addend ->
        stringResource(Strings.hex_elf_relocation_addend, addend)
    } ?: stringResource(Strings.hex_elf_relocation_no_addend)
    val offsetFileOffset = relocation.offsetFileOffset
    return if (offsetFileOffset != null) {
        stringResource(
            Strings.hex_elf_relocation_meta_mapped,
            relocation.index,
            sectionName,
            typeLabel,
            symbolLabel,
            targetSectionLabel,
            relocation.relocationFileOffset,
            relocation.offsetAddress,
            offsetFileOffset,
            addendLabel
        )
    } else {
        stringResource(
            Strings.hex_elf_relocation_meta_unmapped,
            relocation.index,
            sectionName,
            typeLabel,
            symbolLabel,
            targetSectionLabel,
            relocation.relocationFileOffset,
            relocation.offsetAddress,
            addendLabel
        )
    }
}

@Composable
internal fun relocationSymbolMetaLabel(relocation: HexElfRelocationEntry): String {
    val binding = relocation.symbolBinding
    val type = relocation.symbolType
    return if (binding != null && type != null) {
        stringResource(
            Strings.hex_elf_relocation_symbol_meta,
            relocationSymbolRoleLabel(relocation),
            elfSymbolTypeLabel(type),
            elfSymbolBindingLabel(binding)
        )
    } else {
        stringResource(Strings.hex_elf_relocation_symbol_meta_unknown)
    }
}

@Composable
internal fun relocationSymbolRoleLabel(relocation: HexElfRelocationEntry): String = stringResource(
    when {
        relocation.isSymbolJni -> Strings.hex_elf_symbol_role_jni
        relocation.isSymbolImported -> Strings.hex_elf_symbol_role_imported
        relocation.isSymbolExported -> Strings.hex_elf_symbol_role_exported
        else -> Strings.hex_elf_symbol_role_local
    }
)

@Composable
internal fun relocationTargetSectionLabel(relocation: HexElfRelocationEntry): String {
    val targetSectionName = relocation.targetSectionName
    return if (targetSectionName != null) {
        stringResource(Strings.hex_elf_relocation_target_section, targetSectionName)
    } else {
        stringResource(Strings.hex_elf_relocation_target_section_unknown)
    }
}

@Composable
internal fun relocationTypeLabel(relocation: HexElfRelocationEntry): String {
    val typeName = relocation.typeName
    return if (typeName != null) {
        stringResource(Strings.hex_elf_relocation_type_named, typeName, relocation.type)
    } else {
        stringResource(Strings.hex_elf_relocation_type_unknown, relocation.type)
    }
}

@Composable
internal fun elfRelocationSemanticLabel(semantic: HexElfRelocationSemantic): String = stringResource(
    when (semantic) {
        HexElfRelocationSemantic.JUMP_SLOT_BINDING -> Strings.hex_elf_relocation_semantic_jump_slot_binding
        HexElfRelocationSemantic.GLOB_DAT_ADDRESS -> Strings.hex_elf_relocation_semantic_glob_dat_address
        HexElfRelocationSemantic.RELATIVE_REBASE -> Strings.hex_elf_relocation_semantic_relative_rebase
        HexElfRelocationSemantic.COPY_RELOCATION -> Strings.hex_elf_relocation_semantic_copy_relocation
        HexElfRelocationSemantic.ABSOLUTE_ADDRESS -> Strings.hex_elf_relocation_semantic_absolute_address
        HexElfRelocationSemantic.PC_RELATIVE_ADDRESS -> Strings.hex_elf_relocation_semantic_pc_relative_address
        HexElfRelocationSemantic.OTHER -> Strings.hex_elf_relocation_semantic_other
    }
)

@Composable
internal fun elfSymbolBindingLabel(binding: HexElfSymbolBinding): String = stringResource(
    when (binding) {
        HexElfSymbolBinding.LOCAL -> Strings.hex_elf_symbol_binding_local
        HexElfSymbolBinding.GLOBAL -> Strings.hex_elf_symbol_binding_global
        HexElfSymbolBinding.WEAK -> Strings.hex_elf_symbol_binding_weak
        HexElfSymbolBinding.OTHER -> Strings.hex_elf_symbol_binding_other
    }
)

@Composable
internal fun ElfLinkageDialog(
    entries: List<HexElfLinkageEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var linkageFilter by remember(entries) { mutableStateOf(ElfLinkageFilter.ALL) }
    val filteredEntries = remember(entries, query, linkageFilter) {
        filterElfLinkageEntries(
            entries = entries,
            query = query,
            linkageFilter = linkageFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_linkage_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_linkage_filter_label)) },
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
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.ALL,
                                selected = linkageFilter == ElfLinkageFilter.ALL,
                                onClick = { linkageFilter = ElfLinkageFilter.ALL }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.IMPORTS,
                                selected = linkageFilter == ElfLinkageFilter.IMPORTS,
                                onClick = { linkageFilter = ElfLinkageFilter.IMPORTS }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.PLT,
                                selected = linkageFilter == ElfLinkageFilter.PLT,
                                onClick = { linkageFilter = ElfLinkageFilter.PLT }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.GOT,
                                selected = linkageFilter == ElfLinkageFilter.GOT,
                                onClick = { linkageFilter = ElfLinkageFilter.GOT }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.JNI,
                                selected = linkageFilter == ElfLinkageFilter.JNI,
                                onClick = { linkageFilter = ElfLinkageFilter.JNI }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.NOW,
                                selected = linkageFilter == ElfLinkageFilter.NOW,
                                onClick = { linkageFilter = ElfLinkageFilter.NOW }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.LAZY,
                                selected = linkageFilter == ElfLinkageFilter.LAZY,
                                onClick = { linkageFilter = ElfLinkageFilter.LAZY }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_linkage_filter_count,
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
                            text = stringResource(Strings.hex_elf_linkage_empty),
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
                                    "${entry.relocationSectionName}-${entry.relocationFileOffset}-${entry.slotAddress}"
                                }
                            ) { index ->
                                ElfLinkageRow(
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
internal fun ElfLinkageFilterButton(
    filter: ElfLinkageFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfLinkageFilterLabel(filter),
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
internal fun ElfLinkageRow(
    entry: HexElfLinkageEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = entry.slotFileOffset ?: entry.relocationFileOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
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
                text = entry.symbolName
                    ?: stringResource(Strings.hex_elf_relocation_symbol_index, entry.symbolIndex),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = linkageMetaText(entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_linkage_resolution_semantic,
                    elfLinkageResolutionSemanticLabel(entry.resolutionSemantic)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.pltStub?.let { pltStub ->
                Text(
                    text = pltStubMetaText(pltStub),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(Strings.hex_elf_linkage_plt_stub_bytes, pltStub.instructionBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onGotoOffset(pltStub.fileOffset) }) {
                        Text(stringResource(Strings.hex_elf_linkage_plt_stub_jump))
                    }
                    pltStub.slotFileOffset?.let { slotFileOffset ->
                        TextButton(onClick = { onGotoOffset(slotFileOffset) }) {
                            Text(stringResource(Strings.hex_elf_linkage_slot_jump))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun linkageMetaText(entry: HexElfLinkageEntry): String {
    val slotSectionLabel = entry.slotSectionName ?: stringResource(Strings.hex_elf_relocation_target_section_unknown)
    val symbolLabel = linkageSymbolMetaLabel(entry)
    val typeLabel = entry.relocationTypeName?.let { typeName ->
        stringResource(Strings.hex_elf_linkage_type_named, typeName)
    } ?: stringResource(Strings.hex_elf_linkage_type_unknown)
    val slotFileOffset = entry.slotFileOffset
    return if (slotFileOffset != null) {
        stringResource(
            Strings.hex_elf_linkage_meta_mapped,
            entry.index,
            elfLinkageKindLabel(entry.entryKind),
            elfLinkageBindingModeLabel(entry.bindingMode),
            symbolLabel,
            typeLabel,
            entry.relocationSectionName,
            entry.relocationFileOffset,
            entry.slotAddress,
            slotFileOffset,
            slotSectionLabel
        )
    } else {
        stringResource(
            Strings.hex_elf_linkage_meta_unmapped,
            entry.index,
            elfLinkageKindLabel(entry.entryKind),
            elfLinkageBindingModeLabel(entry.bindingMode),
            symbolLabel,
            typeLabel,
            entry.relocationSectionName,
            entry.relocationFileOffset,
            entry.slotAddress,
            slotSectionLabel
        )
    }
}

@Composable
internal fun pltStubMetaText(stub: HexElfPltStub): String {
    val slotFileOffset = stub.slotFileOffset
    return if (slotFileOffset != null) {
        stringResource(
            Strings.hex_elf_linkage_plt_stub_meta_mapped,
            stub.fileOffset,
            pltStubArchitectureLabel(stub.architecture),
            pltStubSemanticLabel(stub.semantic),
            stub.byteCount,
            slotFileOffset
        )
    } else {
        stringResource(
            Strings.hex_elf_linkage_plt_stub_meta_unmapped,
            stub.fileOffset,
            pltStubArchitectureLabel(stub.architecture),
            pltStubSemanticLabel(stub.semantic),
            stub.byteCount
        )
    }
}

@Composable
internal fun pltStubArchitectureLabel(architecture: HexElfPltStubArchitecture): String = stringResource(
    when (architecture) {
        HexElfPltStubArchitecture.AARCH64 -> Strings.hex_elf_plt_arch_aarch64
        HexElfPltStubArchitecture.X86_64 -> Strings.hex_elf_plt_arch_x86_64
    }
)

@Composable
internal fun pltStubSemanticLabel(semantic: HexElfPltStubSemantic): String = stringResource(
    when (semantic) {
        HexElfPltStubSemantic.LOAD_GOT_SLOT_AND_BRANCH -> Strings.hex_elf_plt_semantic_load_got_slot_branch
        HexElfPltStubSemantic.UNKNOWN -> Strings.hex_elf_plt_semantic_unknown
    }
)

@Composable
internal fun elfLinkageResolutionSemanticLabel(semantic: HexElfLinkageResolutionSemantic): String = stringResource(
    when (semantic) {
        HexElfLinkageResolutionSemantic.EAGER_PLT_BINDING -> Strings.hex_elf_linkage_semantic_eager_plt_binding
        HexElfLinkageResolutionSemantic.LAZY_PLT_CALL -> Strings.hex_elf_linkage_semantic_lazy_plt_call
        HexElfLinkageResolutionSemantic.LOAD_TIME_GOT_WRITE -> Strings.hex_elf_linkage_semantic_load_time_got_write
        HexElfLinkageResolutionSemantic.RELATIVE_REBASE -> Strings.hex_elf_linkage_semantic_relative_rebase
        HexElfLinkageResolutionSemantic.LOCAL_RELOCATION -> Strings.hex_elf_linkage_semantic_local_relocation
    }
)

@Composable
internal fun linkageSymbolMetaLabel(entry: HexElfLinkageEntry): String {
    val binding = entry.symbolBinding
    val type = entry.symbolType
    return if (binding != null && type != null) {
        stringResource(
            Strings.hex_elf_relocation_symbol_meta,
            linkageSymbolRoleLabel(entry),
            elfSymbolTypeLabel(type),
            elfSymbolBindingLabel(binding)
        )
    } else {
        stringResource(Strings.hex_elf_relocation_symbol_meta_unknown)
    }
}

@Composable
internal fun linkageSymbolRoleLabel(entry: HexElfLinkageEntry): String = stringResource(
    when {
        entry.isJni -> Strings.hex_elf_symbol_role_jni
        entry.isImported -> Strings.hex_elf_symbol_role_imported
        entry.isExported -> Strings.hex_elf_symbol_role_exported
        else -> Strings.hex_elf_symbol_role_local
    }
)

@Composable
internal fun ElfDynamicLinkerStepsDialog(
    steps: List<HexElfDynamicLinkerStep>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(steps) { mutableStateOf("") }
    var stepFilter by remember(steps) { mutableStateOf(ElfDynamicLinkerStepFilter.ALL) }
    val filteredSteps = remember(steps, query, stepFilter) {
        filterElfDynamicLinkerSteps(
            steps = steps,
            query = query,
            stepFilter = stepFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_loader_steps_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_loader_steps_filter_label)) },
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
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.ALL,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.ALL,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.ALL }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.LOADING,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.LOADING,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.LOADING }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.RELOCATIONS,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.RELOCATIONS,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.RELOCATIONS }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.BINDING,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.BINDING,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.BINDING }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.HARDENING,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.HARDENING,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.HARDENING }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.ENTRYPOINTS,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.ENTRYPOINTS,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.ENTRYPOINTS }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_loader_steps_filter_count,
                                filteredSteps.size,
                                steps.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredSteps.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_loader_steps_empty),
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
                                count = filteredSteps.size,
                                key = { index ->
                                    val step = filteredSteps[index]
                                    "${step.index}-${step.type}-${step.evidenceFileOffset}"
                                }
                            ) { index ->
                                ElfDynamicLinkerStepRow(
                                    step = filteredSteps[index],
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
internal fun ElfDynamicLinkerStepFilterButton(
    filter: ElfDynamicLinkerStepFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfDynamicLinkerStepFilterLabel(filter),
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
internal fun ElfDynamicLinkerStepRow(
    step: HexElfDynamicLinkerStep,
    onGotoOffset: (Long) -> Unit
) {
    val evidenceOffset = step.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = elfDynamicLinkerStepTypeLabel(step.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_loader_step_meta_mapped,
                        step.index,
                        step.relatedCount,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_loader_step_meta_unmapped,
                        step.index,
                        step.relatedCount
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            step.detailValue?.let { detail ->
                Text(
                    text = stringResource(Strings.hex_elf_loader_step_detail, detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}

@Composable
internal fun ElfRiskFindingsDialog(
    findings: List<HexElfRiskFinding>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(findings) { mutableStateOf("") }
    var riskFilter by remember(findings) { mutableStateOf(ElfRiskFilter.ALL) }
    val filteredFindings = remember(findings, query, riskFilter) {
        filterElfRiskFindings(
            findings = findings,
            query = query,
            riskFilter = riskFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_risks_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_risks_filter_label)) },
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
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.ALL,
                                selected = riskFilter == ElfRiskFilter.ALL,
                                onClick = { riskFilter = ElfRiskFilter.ALL }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.HIGH,
                                selected = riskFilter == ElfRiskFilter.HIGH,
                                onClick = { riskFilter = ElfRiskFilter.HIGH }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.WARNING,
                                selected = riskFilter == ElfRiskFilter.WARNING,
                                onClick = { riskFilter = ElfRiskFilter.WARNING }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.HARDENING,
                                selected = riskFilter == ElfRiskFilter.HARDENING,
                                onClick = { riskFilter = ElfRiskFilter.HARDENING }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.SEGMENTS,
                                selected = riskFilter == ElfRiskFilter.SEGMENTS,
                                onClick = { riskFilter = ElfRiskFilter.SEGMENTS }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.PATHS,
                                selected = riskFilter == ElfRiskFilter.PATHS,
                                onClick = { riskFilter = ElfRiskFilter.PATHS }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.METADATA,
                                selected = riskFilter == ElfRiskFilter.METADATA,
                                onClick = { riskFilter = ElfRiskFilter.METADATA }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_elf_risks_filter_count,
                                    filteredFindings.size,
                                    findings.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            val filteredEvidenceOffsets = filteredFindings.mapNotNull { finding ->
                                finding.evidenceFileOffset
                            }
                            TextButton(
                                onClick = { onMarkOffsets(filteredEvidenceOffsets) },
                                enabled = filteredEvidenceOffsets.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredFindings.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_risks_empty),
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
                                count = filteredFindings.size,
                                key = { index ->
                                    val finding = filteredFindings[index]
                                    "${finding.index}-${finding.type}-${finding.evidenceFileOffset}"
                                }
                            ) { index ->
                                ElfRiskFindingRow(
                                    finding = filteredFindings[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
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
internal fun ElfRiskFilterButton(
    filter: ElfRiskFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfRiskFilterLabel(filter),
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
internal fun ElfRiskFindingRow(
    finding: HexElfRiskFinding,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    val evidenceOffset = finding.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_elf_risk_row_title,
                    elfRiskSeverityLabel(finding.severity),
                    elfRiskTypeLabel(finding.type)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = when (finding.severity) {
                    HexElfRiskSeverity.HIGH -> MaterialTheme.colorScheme.error
                    HexElfRiskSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                    HexElfRiskSeverity.INFO -> MaterialTheme.colorScheme.onSurface
                },
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_risk_meta_mapped,
                        finding.index,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_risk_meta_unmapped,
                        finding.index
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            finding.detailValue?.let { detail ->
                Text(
                    text = stringResource(Strings.hex_elf_risk_detail, detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (evidenceOffset != null) {
                TextButton(onClick = { onMarkOffset(evidenceOffset) }) {
                    Text(stringResource(Strings.hex_bookmark_mark))
                }
            }
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}

@Composable
internal fun ElfNativeApiHintsDialog(
    hints: List<HexElfNativeApiHint>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(hints) { mutableStateOf("") }
    var apiFilter by remember(hints) { mutableStateOf(ElfNativeApiFilter.ALL) }
    val filteredHints = remember(hints, query, apiFilter) {
        filterElfNativeApiHints(
            hints = hints,
            query = query,
            apiFilter = apiFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_native_api_hints_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_native_api_hints_filter_label)) },
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
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.ALL,
                                selected = apiFilter == ElfNativeApiFilter.ALL,
                                onClick = { apiFilter = ElfNativeApiFilter.ALL }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.DYNAMIC_LOADING,
                                selected = apiFilter == ElfNativeApiFilter.DYNAMIC_LOADING,
                                onClick = { apiFilter = ElfNativeApiFilter.DYNAMIC_LOADING }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.MEMORY,
                                selected = apiFilter == ElfNativeApiFilter.MEMORY,
                                onClick = { apiFilter = ElfNativeApiFilter.MEMORY }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.PROCESS,
                                selected = apiFilter == ElfNativeApiFilter.PROCESS,
                                onClick = { apiFilter = ElfNativeApiFilter.PROCESS }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.FILE,
                                selected = apiFilter == ElfNativeApiFilter.FILE,
                                onClick = { apiFilter = ElfNativeApiFilter.FILE }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.NETWORK,
                                selected = apiFilter == ElfNativeApiFilter.NETWORK,
                                onClick = { apiFilter = ElfNativeApiFilter.NETWORK }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.CRYPTO,
                                selected = apiFilter == ElfNativeApiFilter.CRYPTO,
                                onClick = { apiFilter = ElfNativeApiFilter.CRYPTO }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.THREADING,
                                selected = apiFilter == ElfNativeApiFilter.THREADING,
                                onClick = { apiFilter = ElfNativeApiFilter.THREADING }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.LOGGING,
                                selected = apiFilter == ElfNativeApiFilter.LOGGING,
                                onClick = { apiFilter = ElfNativeApiFilter.LOGGING }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_native_api_hints_filter_count,
                                filteredHints.size,
                                hints.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredHints.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_native_api_hints_empty),
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
                                count = filteredHints.size,
                                key = { index ->
                                    val hint = filteredHints[index]
                                    "${hint.index}-${hint.category}-${hint.symbolName}"
                                }
                            ) { index ->
                                ElfNativeApiHintRow(
                                    hint = filteredHints[index],
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
internal fun ElfNativeApiFilterButton(
    filter: ElfNativeApiFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfNativeApiFilterLabel(filter),
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
internal fun ElfNativeApiHintRow(
    hint: HexElfNativeApiHint,
    onGotoOffset: (Long) -> Unit
) {
    val evidenceOffset = hint.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_elf_native_api_hint_title,
                    elfNativeApiCategoryLabel(hint.category),
                    hint.symbolName
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_native_api_hint_meta_mapped,
                        hint.index,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_native_api_hint_meta_unmapped,
                        hint.index
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}

@Composable
internal fun ElfJniHintsDialog(
    hints: List<HexElfJniRegistrationHint>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(hints) { mutableStateOf("") }
    var hintFilter by remember(hints) { mutableStateOf(ElfJniHintFilter.ALL) }
    val filteredHints = remember(hints, query, hintFilter) {
        filterElfJniRegistrationHints(
            hints = hints,
            query = query,
            hintFilter = hintFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_jni_hints_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_jni_hints_filter_label)) },
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
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.ALL,
                                selected = hintFilter == ElfJniHintFilter.ALL,
                                onClick = { hintFilter = ElfJniHintFilter.ALL }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.REGISTER_NATIVES,
                                selected = hintFilter == ElfJniHintFilter.REGISTER_NATIVES,
                                onClick = { hintFilter = ElfJniHintFilter.REGISTER_NATIVES }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.ENTRYPOINTS,
                                selected = hintFilter == ElfJniHintFilter.ENTRYPOINTS,
                                onClick = { hintFilter = ElfJniHintFilter.ENTRYPOINTS }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.STATIC_EXPORTS,
                                selected = hintFilter == ElfJniHintFilter.STATIC_EXPORTS,
                                onClick = { hintFilter = ElfJniHintFilter.STATIC_EXPORTS }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.DESCRIPTORS,
                                selected = hintFilter == ElfJniHintFilter.DESCRIPTORS,
                                onClick = { hintFilter = ElfJniHintFilter.DESCRIPTORS }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_jni_hints_filter_count,
                                filteredHints.size,
                                hints.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredHints.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_jni_hints_empty),
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
                                count = filteredHints.size,
                                key = { index ->
                                    val hint = filteredHints[index]
                                    "${hint.index}-${hint.type}-${hint.evidenceFileOffset}"
                                }
                            ) { index ->
                                ElfJniHintRow(
                                    hint = filteredHints[index],
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
internal fun ElfJniHintFilterButton(
    filter: ElfJniHintFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfJniHintFilterLabel(filter),
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
internal fun ElfJniHintRow(
    hint: HexElfJniRegistrationHint,
    onGotoOffset: (Long) -> Unit
) {
    val evidenceOffset = hint.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = elfJniHintTypeLabel(hint.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_jni_hint_meta_mapped,
                        hint.index,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_jni_hint_meta_unmapped,
                        hint.index
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            hint.symbolName?.let { symbolName ->
                Text(
                    text = stringResource(Strings.hex_elf_jni_hint_symbol, symbolName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            hint.stringValue?.let { stringValue ->
                Text(
                    text = stringResource(Strings.hex_elf_jni_hint_string, stringValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}




