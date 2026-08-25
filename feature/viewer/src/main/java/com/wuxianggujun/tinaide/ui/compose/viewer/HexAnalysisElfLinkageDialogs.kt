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
 * ELF relocations and linkage dialogs.
 */

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

