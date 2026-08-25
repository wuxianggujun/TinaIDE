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
 * ELF dynamic linker steps, risk findings, native API and JNI hint dialogs.
 */

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




