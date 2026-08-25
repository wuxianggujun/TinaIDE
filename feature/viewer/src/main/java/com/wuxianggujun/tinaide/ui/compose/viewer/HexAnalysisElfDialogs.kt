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
 * ELF structure analysis dialogs (sections, symbols, dynamic).
 */

@Composable
internal fun ElfSectionsDialog(
    sections: List<HexElfSection>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(sections) { mutableStateOf("") }
    var sectionFilter by remember(sections) { mutableStateOf(ElfSectionFilter.ALL) }
    val filteredSections = remember(sections, query, sectionFilter) {
        filterElfSections(
            sections = sections,
            query = query,
            sectionFilter = sectionFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_sections_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_sections_filter_label)) },
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
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.ALL,
                                selected = sectionFilter == ElfSectionFilter.ALL,
                                onClick = { sectionFilter = ElfSectionFilter.ALL }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.ALLOCATED,
                                selected = sectionFilter == ElfSectionFilter.ALLOCATED,
                                onClick = { sectionFilter = ElfSectionFilter.ALLOCATED }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.EXECUTABLE,
                                selected = sectionFilter == ElfSectionFilter.EXECUTABLE,
                                onClick = { sectionFilter = ElfSectionFilter.EXECUTABLE }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.WRITABLE,
                                selected = sectionFilter == ElfSectionFilter.WRITABLE,
                                onClick = { sectionFilter = ElfSectionFilter.WRITABLE }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.STRING_TABLE,
                                selected = sectionFilter == ElfSectionFilter.STRING_TABLE,
                                onClick = { sectionFilter = ElfSectionFilter.STRING_TABLE }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.SYMBOL_TABLE,
                                selected = sectionFilter == ElfSectionFilter.SYMBOL_TABLE,
                                onClick = { sectionFilter = ElfSectionFilter.SYMBOL_TABLE }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_sections_filter_count,
                                filteredSections.size,
                                sections.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredSections.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_sections_empty),
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
                                count = filteredSections.size,
                                key = { index ->
                                    val section = filteredSections[index]
                                    "${section.index}-${section.name}-${section.fileOffset}"
                                }
                            ) { index ->
                                ElfSectionRow(
                                    section = filteredSections[index],
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
internal fun ElfSectionFilterButton(
    filter: ElfSectionFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfSectionFilterLabel(filter),
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
internal fun ElfSectionRow(
    section: HexElfSection,
    onGotoOffset: (Long) -> Unit
) {
    if (section.fileOffset > 0L && section.size > 0L) {
        Surface(
            onClick = { onGotoOffset(section.fileOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSectionRowContent(section)
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSectionRowContent(section)
        }
    }
}

@Composable
internal fun ElfSectionRowContent(section: HexElfSection) {
    val unnamedSection = stringResource(Strings.hex_elf_section_name_empty)
    val sectionName = section.name.ifBlank { unnamedSection }
    val typeLabel = elfSectionTypeLabel(section.type)
    val flagsLabel = elfSectionFlagsLabel(section.flags)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = sectionName,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(
                Strings.hex_elf_section_meta,
                section.index,
                typeLabel,
                flagsLabel,
                section.virtualAddress,
                section.fileOffset,
                section.size
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ElfProgramHeadersDialog(
    programHeaders: List<HexElfProgramHeader>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(programHeaders) { mutableStateOf("") }
    var programHeaderFilter by remember(programHeaders) { mutableStateOf(ElfProgramHeaderFilter.ALL) }
    val filteredProgramHeaders = remember(programHeaders, query, programHeaderFilter) {
        filterElfProgramHeaders(
            programHeaders = programHeaders,
            query = query,
            programHeaderFilter = programHeaderFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_program_headers_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_program_headers_filter_label)) },
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
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.ALL,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.ALL,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.ALL }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.LOAD,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.LOAD,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.LOAD }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.EXECUTABLE,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.EXECUTABLE,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.EXECUTABLE }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.WRITABLE,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.WRITABLE,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.WRITABLE }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.DYNAMIC,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.DYNAMIC,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.DYNAMIC }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.HARDENING,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.HARDENING,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.HARDENING }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_program_headers_filter_count,
                                filteredProgramHeaders.size,
                                programHeaders.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredProgramHeaders.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_program_headers_empty),
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
                                count = filteredProgramHeaders.size,
                                key = { index ->
                                    val programHeader = filteredProgramHeaders[index]
                                    "${programHeader.index}-${programHeader.type}-${programHeader.fileOffset}"
                                }
                            ) { index ->
                                ElfProgramHeaderRow(
                                    programHeader = filteredProgramHeaders[index],
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
internal fun ElfProgramHeaderFilterButton(
    filter: ElfProgramHeaderFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfProgramHeaderFilterLabel(filter),
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
internal fun ElfProgramHeaderRow(
    programHeader: HexElfProgramHeader,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(programHeader.programHeaderFileOffset) },
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
                text = programHeader.typeName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_program_header_meta,
                    programHeader.index,
                    elfProgramHeaderFlagsLabel(programHeader.flags),
                    programHeader.virtualAddress,
                    programHeader.fileOffset,
                    programHeader.fileSize,
                    programHeader.memorySize,
                    programHeader.align
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ElfSectionSegmentsDialog(
    mappings: List<HexElfSectionSegmentMapping>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(mappings) { mutableStateOf("") }
    var sectionSegmentFilter by remember(mappings) { mutableStateOf(ElfSectionSegmentFilter.ALL) }
    val filteredMappings = remember(mappings, query, sectionSegmentFilter) {
        filterElfSectionSegmentMappings(
            mappings = mappings,
            query = query,
            sectionSegmentFilter = sectionSegmentFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_section_segments_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_elf_section_segments_filter_label)) },
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
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.ALL,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.ALL,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.ALL }
                            )
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.EXECUTABLE,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.EXECUTABLE,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.EXECUTABLE }
                            )
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.WRITABLE,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.WRITABLE,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.WRITABLE }
                            )
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.READABLE,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.READABLE,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.READABLE }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_section_segments_filter_count,
                                filteredMappings.size,
                                mappings.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredMappings.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_section_segments_empty),
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
                                count = filteredMappings.size,
                                key = { index ->
                                    val mapping = filteredMappings[index]
                                    "${mapping.sectionIndex}-${mapping.segmentIndex}-${mapping.sectionFileOffset}"
                                }
                            ) { index ->
                                ElfSectionSegmentRow(
                                    mapping = filteredMappings[index],
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
internal fun ElfSectionSegmentFilterButton(
    filter: ElfSectionSegmentFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfSectionSegmentFilterLabel(filter),
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
internal fun ElfSectionSegmentRow(
    mapping: HexElfSectionSegmentMapping,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(mapping.sectionFileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        val unnamedSection = stringResource(Strings.hex_elf_section_name_empty)
        val sectionName = mapping.sectionName.ifBlank { unnamedSection }
        val segmentFlagsLabel = elfProgramHeaderFlagsLabel(mapping.segmentFlags)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_elf_section_segment_display,
                    sectionName,
                    mapping.segmentIndex,
                    mapping.segmentTypeName,
                    segmentFlagsLabel
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_section_segment_meta,
                    mapping.sectionIndex,
                    mapping.sectionVirtualAddress,
                    mapping.sectionFileOffset,
                    mapping.sectionSize,
                    mapping.segmentVirtualAddress,
                    mapping.segmentFileOffset,
                    mapping.segmentFileSize,
                    mapping.segmentMemorySize
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

