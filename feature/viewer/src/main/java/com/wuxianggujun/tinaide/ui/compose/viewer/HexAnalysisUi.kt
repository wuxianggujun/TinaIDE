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
 * Hex binary analysis panel and all analysis dialogs (ELF/DEX/Archive).
 * Extracted from HexViewerScreen.
 */

@Composable
internal fun HexAnalysisPanel(
    analysis: HexBinaryAnalysis?,
    isLoading: Boolean,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit,
    showTitle: Boolean = true
) {
    var showStringsDialog by remember(analysis) { mutableStateOf(false) }
    var showSectionsDialog by remember(analysis) { mutableStateOf(false) }
    var showProgramHeadersDialog by remember(analysis) { mutableStateOf(false) }
    var showSectionSegmentsDialog by remember(analysis) { mutableStateOf(false) }
    var showSectionEntropyDialog by remember(analysis) { mutableStateOf(false) }
    var showNotesDialog by remember(analysis) { mutableStateOf(false) }
    var showSymbolsDialog by remember(analysis) { mutableStateOf(false) }
    var showDynamicEntriesDialog by remember(analysis) { mutableStateOf(false) }
    var showDynamicFlagsDialog by remember(analysis) { mutableStateOf(false) }
    var showEntropyDialog by remember(analysis) { mutableStateOf(false) }
    var showInitArrayDialog by remember(analysis) { mutableStateOf(false) }
    var showRelocationsDialog by remember(analysis) { mutableStateOf(false) }
    var showLinkageDialog by remember(analysis) { mutableStateOf(false) }
    var showDynamicLinkerStepsDialog by remember(analysis) { mutableStateOf(false) }
    var showRiskFindingsDialog by remember(analysis) { mutableStateOf(false) }
    var showNativeApiHintsDialog by remember(analysis) { mutableStateOf(false) }
    var showJniHintsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexStringsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexTypesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexProtosDialog by remember(analysis) { mutableStateOf(false) }
    var showDexFieldsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexMethodsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexClassesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexClassDataDialog by remember(analysis) { mutableStateOf(false) }
    var showDexCodeItemsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexCallReferencesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexStringReferencesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexFieldReferencesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexMapDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveEntriesDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveZipStructureDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveManifestDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveResourcesDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveNativeLibrariesDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveDexDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveSigningBlockDialog by remember(analysis) { mutableStateOf(false) }
    var showFingerprintDialog by remember(analysis) { mutableStateOf(false) }
    var showByteFrequencyDialog by remember(analysis) { mutableStateOf(false) }
    var showRepeatedByteRunsDialog by remember(analysis) { mutableStateOf(false) }
    var showMagicSignaturesDialog by remember(analysis) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showTitle) {
                    Text(
                        text = stringResource(Strings.hex_analysis_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Strings.hex_analysis_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (analysis != null) {
                    Text(
                        text = stringResource(Strings.hex_analysis_file_kind, hexFileKindLabel(analysis.fileKind)),
                        style = MaterialTheme.typography.labelSmall
                    )
                    analysis.fingerprint?.let { fingerprint ->
                        TextButton(onClick = { showFingerprintDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_fingerprint_preview,
                                    fingerprint.sha256.toShortHashPreview()
                                )
                            )
                        }
                    }
                    analysis.byteFrequency?.let { byteFrequency ->
                        val topByteLabel = byteFrequency.topBytes.firstOrNull()?.byteValue?.toHexByteLabel()
                            ?: stringResource(Strings.hex_byte_frequency_none)
                        TextButton(onClick = { showByteFrequencyDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_byte_frequency_preview,
                                    byteFrequency.uniqueByteValues,
                                    topByteLabel
                                )
                            )
                        }
                    }
                    if (analysis.repeatedByteRuns.isNotEmpty()) {
                        val longestRun = analysis.repeatedByteRuns.maxByOrNull { it.length }
                        TextButton(onClick = { showRepeatedByteRunsDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_repeated_runs_preview,
                                    analysis.repeatedByteRuns.size,
                                    longestRun?.length ?: 0L
                                )
                            )
                        }
                    }
                    if (analysis.magicSignatures.isNotEmpty()) {
                        TextButton(onClick = { showMagicSignaturesDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_magic_signatures_preview,
                                    analysis.magicSignatures.size
                                )
                            )
                        }
                    }
                    analysis.entropy.maxByOrNull { it.entropy }?.let { maxEntropy ->
                        Text(
                            text = stringResource(Strings.hex_analysis_entropy_max, maxEntropy.entropy),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        text = stringResource(Strings.hex_analysis_strings_count, analysis.strings.size),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (!analysis?.entropyVisualBuckets.isNullOrEmpty()) {
                val visualBuckets = analysis!!.entropyVisualBuckets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_entropy_map_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { showEntropyDialog = true }) {
                        Text(
                            stringResource(
                                Strings.hex_entropy_show_all,
                                visualBuckets.size
                            )
                        )
                    }
                    visualBuckets.forEach { bucket ->
                        EntropyBucketBar(
                            bucket = bucket,
                            onClick = { onGotoOffset(bucket.startOffset) }
                        )
                    }
                }
            }

            analysis?.dex?.let { dex ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_dex_summary,
                            dex.version,
                            dex.fileSizeFromHeader,
                            dex.headerSize
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_dex_counts,
                            dex.stringIdsSize,
                            dex.typeIdsSize,
                            dex.protoIdsSize,
                            dex.fieldIdsSize,
                            dex.methodIdsSize,
                            dex.classDefsSize
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dex.nativeMethodCount > 0) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_dex_native_methods,
                                dex.nativeMethodCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (dex.stringEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexStringsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_strings_show_all,
                                    dex.stringEntries.size
                                )
                            )
                        }
                    }
                    if (dex.typeEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexTypesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_types_show_all,
                                    dex.typeEntries.size
                                )
                            )
                        }
                    }
                    if (dex.protoEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexProtosDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_protos_show_all,
                                    dex.protoEntries.size
                                )
                            )
                        }
                    }
                    if (dex.fieldEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexFieldsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_fields_show_all,
                                    dex.fieldEntries.size
                                )
                            )
                        }
                    }
                    if (dex.methodEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexMethodsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_methods_show_all,
                                    dex.methodEntries.size
                                )
                            )
                        }
                    }
                    if (dex.classDefEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexClassesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_classes_show_all,
                                    dex.classDefEntries.size
                                )
                            )
                        }
                    }
                    if (dex.classDataMethodEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexClassDataDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_class_data_methods_show_all,
                                    dex.classDataMethodEntries.size
                                )
                            )
                        }
                    }
                    if (dex.codeItemEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexCodeItemsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_code_items_show_all,
                                    dex.codeItemEntries.size
                                )
                            )
                        }
                    }
                    if (dex.callReferenceEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexCallReferencesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_call_references_show_all,
                                    dex.callReferenceEntries.size
                                )
                            )
                        }
                    }
                    if (dex.stringReferenceEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexStringReferencesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_string_references_show_all,
                                    dex.stringReferenceEntries.size
                                )
                            )
                        }
                    }
                    if (dex.fieldReferenceEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexFieldReferencesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_field_references_show_all,
                                    dex.fieldReferenceEntries.size
                                )
                            )
                        }
                    }
                    if (dex.mapEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexMapDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_map_show_all,
                                    dex.mapEntries.size
                                )
                            )
                        }
                    }
                    dex.stringEntries.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { entry ->
                        TextButton(onClick = { onGotoOffset(entry.dataOffset) }) {
                            Text(
                                stringResource(
                                    Strings.hex_analysis_dex_string_item,
                                    entry.index,
                                    entry.value.compactForAnalysisPanel()
                                )
                            )
                        }
                    }
                }
            }

            analysis?.archive?.let { archive ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = { showArchiveEntriesDialog = true }) {
                        Text(
                            stringResource(
                                Strings.hex_archive_entries_show_all,
                                archive.entries.size
                            )
                        )
                    }
                    archive.zipStructure?.let { structure ->
                        TextButton(onClick = { showArchiveZipStructureDialog = true }) {
                            Text(stringResource(Strings.hex_archive_zip_structure_show))
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_zip_structure_preview,
                                structure.entryCount,
                                structure.centralDirectoryOffset,
                                structure.eocdOffset
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_archive_counts,
                            archive.dexFiles.size,
                            archive.nativeLibraries.size,
                            archive.resources.size,
                            archive.signatureFiles.size
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (archive.embeddedDexFiles.isNotEmpty()) {
                        TextButton(onClick = { showArchiveDexDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_archive_dex_show_all,
                                    archive.embeddedDexFiles.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_dex_preview,
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.protoIdsSize },
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.fieldIdsSize },
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.methodIdsSize },
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.classDefsSize }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (archive.signingBlockEntries.isNotEmpty()) {
                        TextButton(onClick = { showArchiveSigningBlockDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_archive_signing_block_show_all,
                                    archive.signingBlockEntries.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_signing_block_preview,
                                archive.signingBlockEntries.signingBlockNamesPreview()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    archive.manifest?.let { manifest ->
                        TextButton(onClick = { onGotoOffset(manifest.localHeaderOffset) }) {
                            Text(stringResource(Strings.hex_analysis_archive_manifest))
                        }
                    }
                    archive.manifestSummary?.let { manifest ->
                        TextButton(onClick = { showArchiveManifestDialog = true }) {
                            Text(stringResource(Strings.hex_archive_manifest_show))
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_manifest_preview,
                                manifest.packageName ?: stringResource(Strings.hex_archive_manifest_package_unknown),
                                manifest.permissions.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    archive.resourcesSummary?.let { resources ->
                        TextButton(onClick = { showArchiveResourcesDialog = true }) {
                            Text(stringResource(Strings.hex_archive_resources_show))
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_resources_preview,
                                resources.packages.size,
                                resources.typeSpecCount,
                                resources.typeChunkCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (archive.nativeLibrarySummaries.isNotEmpty()) {
                        TextButton(onClick = { showArchiveNativeLibrariesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_archive_native_show_all,
                                    archive.nativeLibrarySummaries.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_native_summary_preview,
                                archive.nativeLibrarySummaries.nativeLibraryAbiPreview(),
                                archive.nativeLibrarySummaries.sumOf { entry -> entry.obfuscationMarkers.size }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (archive.nativeLibraries.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_native_preview,
                                archive.nativeLibraries.archiveEntryNamesPreview()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    archive.dexFiles.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { entry ->
                        TextButton(onClick = { onGotoOffset(entry.localHeaderOffset) }) {
                            Text(
                                stringResource(
                                    Strings.hex_analysis_archive_entry_item,
                                    entry.name.compactForAnalysisPanel()
                                )
                            )
                        }
                    }
                }
            }

            analysis?.elf?.let { elf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_elf_summary,
                            if (elf.is64Bit) 64 else 32,
                            hexEndianLabel(elf.endian),
                            elf.machineName
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val entryFileOffset = elf.entryFileOffset
                    if (entryFileOffset != null) {
                        TextButton(onClick = { onGotoOffset(entryFileOffset) }) {
                            Text(
                                stringResource(
                                    Strings.hex_analysis_elf_entry_mapped,
                                    elf.entryPoint,
                                    entryFileOffset
                                )
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(Strings.hex_analysis_elf_entry, elf.entryPoint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_elf_sections,
                            elf.sectionHeaderCount,
                            elf.programHeaderCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (elf.sections.isNotEmpty()) {
                        TextButton(onClick = { showSectionsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_sections_show_all,
                                    elf.sections.size
                                )
                            )
                        }
                    }
                    if (elf.programHeaders.isNotEmpty()) {
                        TextButton(onClick = { showProgramHeadersDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_program_headers_show_all,
                                    elf.programHeaders.size
                                )
                            )
                        }
                    }
                    if (elf.sectionSegmentMappings.isNotEmpty()) {
                        TextButton(onClick = { showSectionSegmentsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_section_segments_show_all,
                                    elf.sectionSegmentMappings.size
                                )
                            )
                        }
                    }
                    if (elf.sectionEntropyEntries.isNotEmpty()) {
                        TextButton(onClick = { showSectionEntropyDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_section_entropy_show_all,
                                    elf.sectionEntropyEntries.size
                                )
                            )
                        }
                    }
                    if (elf.noteEntries.isNotEmpty()) {
                        TextButton(onClick = { showNotesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_notes_show_all,
                                    elf.noteEntries.size
                                )
                            )
                        }
                    }
                    elf.buildId?.let { buildId ->
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_build_id_preview,
                                buildId.descriptionHex.compactForAnalysisPanel()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (elf.initArrayEntries.isNotEmpty()) {
                        TextButton(onClick = { showInitArrayDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_init_array_show_all,
                                    elf.initArrayEntries.size
                                )
                            )
                        }
                    }
                    if (elf.relocations.isNotEmpty()) {
                        TextButton(onClick = { showRelocationsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_relocations_show_all,
                                    elf.relocations.size
                                )
                            )
                        }
                    }
                    if (elf.linkageEntries.isNotEmpty()) {
                        val pltEntries = elf.linkageEntries.count { entry ->
                            entry.entryKind == HexElfLinkageEntryKind.PLT
                        }
                        val lazyEntries = elf.linkageEntries.count { entry ->
                            entry.bindingMode == HexElfLinkageBindingMode.LAZY
                        }
                        TextButton(onClick = { showLinkageDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_linkage_show_all,
                                    elf.linkageEntries.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_linkage_preview,
                                pltEntries,
                                elf.linkageEntries.count { entry ->
                                    entry.slotSectionName?.contains("got", ignoreCase = true) == true ||
                                        entry.entryKind == HexElfLinkageEntryKind.GOT
                                },
                                lazyEntries
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (elf.dynamicLinkerSteps.isNotEmpty()) {
                    val loadingSteps = elf.dynamicLinkerSteps.count { step ->
                        step.type == HexElfDynamicLinkerStepType.MAP_LOAD_SEGMENTS ||
                            step.type == HexElfDynamicLinkerStepType.LOAD_NEEDED_LIBRARIES
                    }
                    val bindingSteps = elf.dynamicLinkerSteps.count { step ->
                        step.type == HexElfDynamicLinkerStepType.RESOLVE_NOW_BINDINGS ||
                            step.type == HexElfDynamicLinkerStepType.ENABLE_LAZY_PLT
                    }
                    val entrypointSteps = elf.dynamicLinkerSteps.count { step ->
                        step.type == HexElfDynamicLinkerStepType.CALL_INIT_ARRAY ||
                            step.type == HexElfDynamicLinkerStepType.EXPOSE_JNI_ENTRYPOINTS
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showDynamicLinkerStepsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_loader_steps_show_all,
                                    elf.dynamicLinkerSteps.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_loader_steps_preview,
                                loadingSteps,
                                bindingSteps,
                                entrypointSteps
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (elf.riskFindings.isNotEmpty()) {
                    val highRisks = elf.riskFindings.count { finding ->
                        finding.severity == HexElfRiskSeverity.HIGH
                    }
                    val warningRisks = elf.riskFindings.count { finding ->
                        finding.severity == HexElfRiskSeverity.WARNING
                    }
                    val infoRisks = elf.riskFindings.count { finding ->
                        finding.severity == HexElfRiskSeverity.INFO
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showRiskFindingsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_risks_show_all,
                                    elf.riskFindings.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_risk_preview,
                                highRisks,
                                warningRisks,
                                infoRisks
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (highRisks > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (elf.hardeningChecks.isNotEmpty()) {
                    val enabledChecks = elf.hardeningChecks.count { check -> check.enabled }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_elf_hardening_summary,
                                enabledChecks,
                                elf.hardeningChecks.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (enabledChecks == elf.hardeningChecks.size) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        elf.hardeningChecks.forEach { check ->
                            val evidenceOffset = check.evidenceFileOffset
                            val text = stringResource(
                                Strings.hex_elf_hardening_item,
                                elfHardeningTypeLabel(check.type),
                                elfHardeningStatusLabel(check.enabled)
                            )
                            if (evidenceOffset != null) {
                                TextButton(onClick = { onGotoOffset(evidenceOffset) }) {
                                    Text(text)
                                }
                            } else {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (elf.dynamicStringEntries.isNotEmpty() || elf.dynamicFlagEntries.isNotEmpty()) {
                    val neededLibraries = elf.neededLibraries
                    val runtimeSearchPaths = elf.runtimeSearchPaths
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_elf_dynamic_counts,
                                neededLibraries.size,
                                elf.soname?.value ?: stringResource(Strings.hex_elf_dynamic_soname_empty),
                                runtimeSearchPaths.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (elf.dynamicStringEntries.isNotEmpty()) {
                            TextButton(onClick = { showDynamicEntriesDialog = true }) {
                                Text(
                                    stringResource(
                                        Strings.hex_elf_dynamic_show_all,
                                        elf.dynamicStringEntries.size
                                    )
                                )
                            }
                        }
                        if (elf.dynamicFlagEntries.isNotEmpty()) {
                            TextButton(onClick = { showDynamicFlagsDialog = true }) {
                                Text(
                                    stringResource(
                                        Strings.hex_elf_dynamic_flags_show_all,
                                        elf.dynamicFlagEntries.size
                                    )
                                )
                            }
                        }
                        if (neededLibraries.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_needed_preview,
                                    neededLibraries.dynamicValuesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (runtimeSearchPaths.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_search_path_preview,
                                    runtimeSearchPaths.dynamicValuesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (elf.dynamicSymbols.isNotEmpty()) {
                    val importedSymbols = elf.importedSymbols
                    val exportedSymbols = elf.exportedSymbols
                    val jniSymbols = elf.jniSymbols
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_elf_symbol_counts,
                                importedSymbols.size,
                                exportedSymbols.size,
                                jniSymbols.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { showSymbolsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_symbols_show_all,
                                    elf.dynamicSymbols.size
                                )
                            )
                        }
                        if (importedSymbols.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_imports_preview,
                                    importedSymbols.symbolNamesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (exportedSymbols.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_exports_preview,
                                    exportedSymbols.symbolNamesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (jniSymbols.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_jni_preview,
                                    jniSymbols.symbolNamesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        (exportedSymbols + jniSymbols)
                            .asSequence()
                            .filter { symbol -> symbol.fileOffset != null }
                            .distinctBy { symbol -> symbol.name }
                            .take(MAX_ANALYSIS_PANEL_ITEMS)
                            .forEach { symbol ->
                                val fileOffset = symbol.fileOffset ?: return@forEach
                                TextButton(onClick = { onGotoOffset(fileOffset) }) {
                                    Text(
                                        stringResource(
                                            Strings.hex_analysis_symbol_with_offset,
                                            symbol.name.compactForAnalysisPanel(),
                                            fileOffset
                                        )
                                    )
                                }
                            }
                    }
                }

                if (elf.nativeApiHints.isNotEmpty()) {
                    val loaderHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.DYNAMIC_LOADING
                    }
                    val memoryHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.MEMORY_PROTECTION
                    }
                    val processHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.PROCESS_CONTROL
                    }
                    val networkHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.NETWORK
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showNativeApiHintsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_native_api_hints_show_all,
                                    elf.nativeApiHints.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_native_api_preview,
                                loaderHints,
                                memoryHints,
                                processHints,
                                networkHints
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (elf.jniRegistrationHints.isNotEmpty()) {
                    val registerNativeHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.REGISTER_NATIVES_SYMBOL ||
                            hint.type == HexElfJniRegistrationHintType.REGISTER_NATIVES_STRING
                    }
                    val entrypointHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.JNI_ONLOAD_ENTRY ||
                            hint.type == HexElfJniRegistrationHintType.JNI_ONUNLOAD_ENTRY
                    }
                    val staticExportHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.STATIC_JNI_EXPORT
                    }
                    val descriptorHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.JAVA_CLASS_DESCRIPTOR ||
                            hint.type == HexElfJniRegistrationHintType.JNI_METHOD_SIGNATURE
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showJniHintsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_jni_hints_show_all,
                                    elf.jniRegistrationHints.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_jni_hints_preview,
                                registerNativeHints,
                                entrypointHints,
                                staticExportHints,
                                descriptorHints
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!analysis?.obfuscationFindings.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_obfuscation_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val obfuscationOffsets = analysis!!.obfuscationFindings.mapNotNull { finding -> finding.offset }
                    if (obfuscationOffsets.isNotEmpty()) {
                        TextButton(onClick = { onMarkOffsets(obfuscationOffsets) }) {
                            Text(stringResource(Strings.hex_bookmark_mark_all))
                        }
                    }
                    analysis!!.obfuscationFindings.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { finding ->
                        val label = hexObfuscationFindingLabel(finding.type)
                        val confidence = hexFindingConfidenceLabel(finding.confidence)
                        val text = stringResource(
                            Strings.hex_obfuscation_item,
                            label,
                            confidence,
                            finding.evidence.compactForAnalysisPanel()
                        )
                        if (finding.offset != null) {
                            TextButton(onClick = { onGotoOffset(finding.offset) }) {
                                Text(text)
                            }
                        } else {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!analysis?.signals.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_analysis_signals_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val signalOffsets = analysis!!.signals.mapNotNull { signal -> signal.offset }
                    if (signalOffsets.isNotEmpty()) {
                        TextButton(onClick = { onMarkOffsets(signalOffsets) }) {
                            Text(stringResource(Strings.hex_bookmark_mark_all))
                        }
                    }
                    analysis!!.signals.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { signal ->
                        val label = hexSignalLabel(signal.type)
                        if (signal.offset != null) {
                            TextButton(onClick = { onGotoOffset(signal.offset) }) {
                                Text(stringResource(Strings.hex_analysis_signal_with_offset, label, signal.offset))
                            }
                        } else {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!analysis?.strings.isNullOrEmpty()) {
                val analysisStrings = analysis!!.strings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_analysis_strings_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { showStringsDialog = true }) {
                        Text(
                            stringResource(
                                Strings.hex_analysis_strings_show_all,
                                analysisStrings.size
                            )
                        )
                    }
                    analysisStrings.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { entry ->
                        TextButton(onClick = { onGotoOffset(entry.offset) }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_string_item,
                                    entry.offset,
                                    stringEncodingLabel(entry.encoding),
                                    entry.value.compactForAnalysisPanel()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStringsDialog && analysis != null) {
        StringsListDialog(
            entries = analysis.strings,
            onDismiss = { showStringsDialog = false },
            onGotoOffset = { offset ->
                showStringsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showFingerprintDialog && analysis?.fingerprint != null) {
        HexFingerprintDialog(
            fingerprint = analysis.fingerprint,
            onDismiss = { showFingerprintDialog = false }
        )
    }

    if (showByteFrequencyDialog && analysis?.byteFrequency != null) {
        HexByteFrequencyDialog(
            byteFrequency = analysis.byteFrequency,
            onDismiss = { showByteFrequencyDialog = false }
        )
    }

    if (showRepeatedByteRunsDialog && !analysis?.repeatedByteRuns.isNullOrEmpty()) {
        HexRepeatedByteRunsDialog(
            runs = analysis!!.repeatedByteRuns,
            onDismiss = { showRepeatedByteRunsDialog = false },
            onGotoOffset = { offset ->
                showRepeatedByteRunsDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showMagicSignaturesDialog && !analysis?.magicSignatures.isNullOrEmpty()) {
        HexMagicSignaturesDialog(
            matches = analysis!!.magicSignatures,
            onDismiss = { showMagicSignaturesDialog = false },
            onGotoOffset = { offset ->
                showMagicSignaturesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexStringsDialog && analysis?.dex != null) {
        DexStringsDialog(
            entries = analysis.dex.stringEntries,
            onDismiss = { showDexStringsDialog = false },
            onGotoOffset = { offset ->
                showDexStringsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexTypesDialog && analysis?.dex != null) {
        DexTypesDialog(
            entries = analysis.dex.typeEntries,
            onDismiss = { showDexTypesDialog = false },
            onGotoOffset = { offset ->
                showDexTypesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexProtosDialog && analysis?.dex != null) {
        DexProtosDialog(
            entries = analysis.dex.protoEntries,
            onDismiss = { showDexProtosDialog = false },
            onGotoOffset = { offset ->
                showDexProtosDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexFieldsDialog && analysis?.dex != null) {
        DexFieldsDialog(
            entries = analysis.dex.fieldEntries,
            onDismiss = { showDexFieldsDialog = false },
            onGotoOffset = { offset ->
                showDexFieldsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexMethodsDialog && analysis?.dex != null) {
        DexMethodsDialog(
            entries = analysis.dex.methodEntries,
            onDismiss = { showDexMethodsDialog = false },
            onGotoOffset = { offset ->
                showDexMethodsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexClassesDialog && analysis?.dex != null) {
        DexClassesDialog(
            entries = analysis.dex.classDefEntries,
            onDismiss = { showDexClassesDialog = false },
            onGotoOffset = { offset ->
                showDexClassesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexClassDataDialog && analysis?.dex != null) {
        DexClassDataMethodsDialog(
            entries = analysis.dex.classDataMethodEntries,
            onDismiss = { showDexClassDataDialog = false },
            onGotoOffset = { offset ->
                showDexClassDataDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexCodeItemsDialog && analysis?.dex != null) {
        DexCodeItemsDialog(
            entries = analysis.dex.codeItemEntries,
            onDismiss = { showDexCodeItemsDialog = false },
            onGotoOffset = { offset ->
                showDexCodeItemsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexCallReferencesDialog && analysis?.dex != null) {
        DexCallReferencesDialog(
            entries = analysis.dex.callReferenceEntries,
            onDismiss = { showDexCallReferencesDialog = false },
            onGotoOffset = { offset ->
                showDexCallReferencesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexStringReferencesDialog && analysis?.dex != null) {
        DexStringReferencesDialog(
            entries = analysis.dex.stringReferenceEntries,
            onDismiss = { showDexStringReferencesDialog = false },
            onGotoOffset = { offset ->
                showDexStringReferencesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexFieldReferencesDialog && analysis?.dex != null) {
        DexFieldReferencesDialog(
            entries = analysis.dex.fieldReferenceEntries,
            onDismiss = { showDexFieldReferencesDialog = false },
            onGotoOffset = { offset ->
                showDexFieldReferencesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexMapDialog && analysis?.dex != null) {
        DexMapEntriesDialog(
            entries = analysis.dex.mapEntries,
            onDismiss = { showDexMapDialog = false },
            onGotoOffset = { offset ->
                showDexMapDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveEntriesDialog && analysis?.archive != null) {
        ArchiveEntriesDialog(
            entries = analysis.archive.entries,
            onDismiss = { showArchiveEntriesDialog = false },
            onGotoOffset = { offset ->
                showArchiveEntriesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveZipStructureDialog && analysis?.archive?.zipStructure != null) {
        ArchiveZipStructureDialog(
            structure = analysis.archive.zipStructure,
            onDismiss = { showArchiveZipStructureDialog = false },
            onGotoOffset = { offset ->
                showArchiveZipStructureDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveManifestDialog && analysis?.archive?.manifestSummary != null) {
        ArchiveManifestDialog(
            manifest = analysis.archive.manifestSummary,
            onDismiss = { showArchiveManifestDialog = false },
            onGotoOffset = { offset ->
                showArchiveManifestDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveResourcesDialog && analysis?.archive?.resourcesSummary != null) {
        ArchiveResourcesDialog(
            resources = analysis.archive.resourcesSummary,
            onDismiss = { showArchiveResourcesDialog = false },
            onGotoOffset = { offset ->
                showArchiveResourcesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveNativeLibrariesDialog && analysis?.archive?.nativeLibrarySummaries?.isNotEmpty() == true) {
        ArchiveNativeLibrariesDialog(
            entries = analysis.archive.nativeLibrarySummaries,
            onDismiss = { showArchiveNativeLibrariesDialog = false },
            onGotoOffset = { offset ->
                showArchiveNativeLibrariesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveDexDialog && analysis?.archive != null) {
        ArchiveDexSummariesDialog(
            entries = analysis.archive.embeddedDexFiles,
            onDismiss = { showArchiveDexDialog = false },
            onGotoOffset = { offset ->
                showArchiveDexDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSectionsDialog && analysis?.elf != null) {
        ElfSectionsDialog(
            sections = analysis.elf.sections,
            onDismiss = { showSectionsDialog = false },
            onGotoOffset = { offset ->
                showSectionsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showProgramHeadersDialog && analysis?.elf != null) {
        ElfProgramHeadersDialog(
            programHeaders = analysis.elf.programHeaders,
            onDismiss = { showProgramHeadersDialog = false },
            onGotoOffset = { offset ->
                showProgramHeadersDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSectionSegmentsDialog && analysis?.elf != null) {
        ElfSectionSegmentsDialog(
            mappings = analysis.elf.sectionSegmentMappings,
            onDismiss = { showSectionSegmentsDialog = false },
            onGotoOffset = { offset ->
                showSectionSegmentsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSectionEntropyDialog && analysis?.elf != null) {
        ElfSectionEntropyDialog(
            entries = analysis.elf.sectionEntropyEntries,
            onDismiss = { showSectionEntropyDialog = false },
            onGotoOffset = { offset ->
                showSectionEntropyDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showNotesDialog && analysis?.elf != null) {
        ElfNotesDialog(
            notes = analysis.elf.noteEntries,
            onDismiss = { showNotesDialog = false },
            onGotoOffset = { offset ->
                showNotesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSymbolsDialog && analysis?.elf != null) {
        ElfSymbolsDialog(
            symbols = analysis.elf.dynamicSymbols,
            onDismiss = { showSymbolsDialog = false },
            onGotoOffset = { offset ->
                showSymbolsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDynamicEntriesDialog && analysis?.elf != null) {
        ElfDynamicEntriesDialog(
            entries = analysis.elf.dynamicStringEntries,
            onDismiss = { showDynamicEntriesDialog = false },
            onGotoOffset = { offset ->
                showDynamicEntriesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDynamicFlagsDialog && analysis?.elf != null) {
        ElfDynamicFlagsDialog(
            entries = analysis.elf.dynamicFlagEntries,
            onDismiss = { showDynamicFlagsDialog = false },
            onGotoOffset = { offset ->
                showDynamicFlagsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showEntropyDialog && analysis != null) {
        EntropyBucketsDialog(
            buckets = analysis.entropyVisualBuckets,
            onDismiss = { showEntropyDialog = false },
            onGotoOffset = { offset ->
                showEntropyDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showInitArrayDialog && analysis?.elf != null) {
        ElfInitArrayDialog(
            entries = analysis.elf.initArrayEntries,
            onDismiss = { showInitArrayDialog = false },
            onGotoOffset = { offset ->
                showInitArrayDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showRelocationsDialog && analysis?.elf != null) {
        ElfRelocationsDialog(
            relocations = analysis.elf.relocations,
            onDismiss = { showRelocationsDialog = false },
            onGotoOffset = { offset ->
                showRelocationsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showLinkageDialog && analysis?.elf != null) {
        ElfLinkageDialog(
            entries = analysis.elf.linkageEntries,
            onDismiss = { showLinkageDialog = false },
            onGotoOffset = { offset ->
                showLinkageDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDynamicLinkerStepsDialog && analysis?.elf != null) {
        ElfDynamicLinkerStepsDialog(
            steps = analysis.elf.dynamicLinkerSteps,
            onDismiss = { showDynamicLinkerStepsDialog = false },
            onGotoOffset = { offset ->
                showDynamicLinkerStepsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showRiskFindingsDialog && analysis?.elf != null) {
        ElfRiskFindingsDialog(
            findings = analysis.elf.riskFindings,
            onDismiss = { showRiskFindingsDialog = false },
            onGotoOffset = { offset ->
                showRiskFindingsDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showNativeApiHintsDialog && analysis?.elf != null) {
        ElfNativeApiHintsDialog(
            hints = analysis.elf.nativeApiHints,
            onDismiss = { showNativeApiHintsDialog = false },
            onGotoOffset = { offset ->
                showNativeApiHintsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showJniHintsDialog && analysis?.elf != null) {
        ElfJniHintsDialog(
            hints = analysis.elf.jniRegistrationHints,
            onDismiss = { showJniHintsDialog = false },
            onGotoOffset = { offset ->
                showJniHintsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveSigningBlockDialog && analysis?.archive != null) {
        ArchiveSigningBlockDialog(
            entries = analysis.archive.signingBlockEntries,
            onDismiss = { showArchiveSigningBlockDialog = false },
            onGotoOffset = { offset ->
                showArchiveSigningBlockDialog = false
                onGotoOffset(offset)
            }
        )
    }
}

@Composable
internal fun StringsListDialog(
    entries: List<HexStringEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var query by remember(entries) { mutableStateOf("") }
    var encodingFilter by remember(entries) { mutableStateOf(StringEntryEncodingFilter.ALL) }
    val filteredEntries = remember(entries, query, encodingFilter) {
        filterStringEntries(
            entries = entries,
            query = query,
            encodingFilter = encodingFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_strings_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_strings_filter_label)) },
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
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.ALL,
                                selected = encodingFilter == StringEntryEncodingFilter.ALL,
                                onClick = { encodingFilter = StringEntryEncodingFilter.ALL }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.ASCII,
                                selected = encodingFilter == StringEntryEncodingFilter.ASCII,
                                onClick = { encodingFilter = StringEntryEncodingFilter.ASCII }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.UTF_8,
                                selected = encodingFilter == StringEntryEncodingFilter.UTF_8,
                                onClick = { encodingFilter = StringEntryEncodingFilter.UTF_8 }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.UTF_16LE,
                                selected = encodingFilter == StringEntryEncodingFilter.UTF_16LE,
                                onClick = { encodingFilter = StringEntryEncodingFilter.UTF_16LE }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.UTF_16BE,
                                selected = encodingFilter == StringEntryEncodingFilter.UTF_16BE,
                                onClick = { encodingFilter = StringEntryEncodingFilter.UTF_16BE }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_strings_filter_count,
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
                            text = stringResource(Strings.hex_strings_empty),
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
                                    "${entry.offset}-${entry.encoding}-${entry.value}"
                                }
                            ) { index ->
                                val entry = filteredEntries[index]
                                StringEntryRow(
                                    entry = entry,
                                    onClick = { onGotoOffset(entry.offset) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.hex_strings_copy_filtered),
                enabled = filteredEntries.isNotEmpty(),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipData.newPlainText(
                                "hex-strings",
                                formatStringEntriesExport(filteredEntries)
                            ).toClipEntry()
                        )
                    }
                }
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
internal fun StringEncodingFilterButton(
    filter: StringEntryEncodingFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = stringEncodingFilterLabel(filter),
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
internal fun StringEntryRow(
    entry: HexStringEntry,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
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
                text = stringResource(
                    Strings.hex_strings_dialog_meta,
                    entry.offset,
                    stringEncodingLabel(entry.encoding)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

