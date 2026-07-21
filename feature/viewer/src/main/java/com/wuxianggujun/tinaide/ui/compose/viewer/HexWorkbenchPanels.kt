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
 * Hex workbench shell panels (analysis dialog host, docked panel, findings).
 */

@Composable
internal fun HexAnalysisDialog(
    analysis: HexBinaryAnalysis?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_analysis_title)) },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                HexAnalysisPanel(
                    analysis = analysis,
                    isLoading = isLoading,
                    onGotoOffset = onGotoOffset,
                    onMarkOffsets = onMarkOffsets
                )
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
internal fun HexDockedAnalysisPanel(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    isLoading: Boolean,
    onClose: () -> Unit,
    onOpenCommands: (HexBinaryFinding?) -> Unit,
    onOpenReport: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    Surface(
        modifier = Modifier
            .width(HexDockedAnalysisPanelWidth)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(Strings.hex_workbench_analysis_panel_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Strings.hex_workbench_close_analysis_panel)
                    )
                }
            }
            HorizontalDivider()
            HexWorkbenchCommandStrip(
                state = state,
                onOpenCommands = { onOpenCommands(null) }
            )
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                HexBinaryFindingsPanel(
                    analysis = analysis,
                    onGotoOffset = onGotoOffset,
                    onMarkOffsets = onMarkOffsets,
                    onOpenCommands = onOpenCommands,
                    onOpenReport = onOpenReport
                )
                HorizontalDivider()
                HexAnalysisPanel(
                    analysis = analysis,
                    isLoading = isLoading,
                    onGotoOffset = onGotoOffset,
                    onMarkOffsets = onMarkOffsets,
                    showTitle = false
                )
            }
        }
    }
}

@Composable
internal fun HexWorkbenchCommandStrip(
    state: HexViewerState,
    onOpenCommands: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(Strings.hex_selected_offset, state.selectedOffset),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        state.selectionRange?.let { selectionRange ->
            Text(
                text = stringResource(
                    Strings.hex_selection_range,
                    selectionRange.firstOffset,
                    selectionRange.lastOffset,
                    selectionRange.byteCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
        TextButton(onClick = onOpenCommands) {
            Text(stringResource(Strings.hex_workbench_commands_title))
        }
    }
}

@Composable
internal fun HexBinaryFindingsPanel(
    analysis: HexBinaryAnalysis?,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit,
    onOpenCommands: (HexBinaryFinding) -> Unit,
    onOpenReport: () -> Unit
) {
    val findings = remember(analysis) { buildHexBinaryFindings(analysis) }
    val markableOffsets = remember(findings) { findings.mapNotNull { finding -> finding.offset }.distinct() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(Strings.hex_workbench_findings_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (findings.isNotEmpty()) {
                Text(
                    text = stringResource(Strings.hex_workbench_findings_count, findings.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (markableOffsets.isNotEmpty()) {
                TextButton(onClick = { onMarkOffsets(markableOffsets) }) {
                    Text(stringResource(Strings.hex_bookmark_mark_all))
                }
            }
            TextButton(
                onClick = onOpenReport,
                enabled = analysis != null
            ) {
                Text(stringResource(Strings.hex_workbench_reports_title))
            }
        }
        if (findings.isEmpty()) {
            Text(
                text = stringResource(Strings.hex_workbench_findings_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            findings.take(MAX_WORKBENCH_FINDING_PANEL_ITEMS).forEachIndexed { index, finding ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                HexBinaryFindingRow(
                    finding = finding,
                    onGotoOffset = onGotoOffset,
                    onOpenCommands = onOpenCommands
                )
            }
        }
    }
}

@Composable
internal fun HexBinaryFindingRow(
    finding: HexBinaryFinding,
    onGotoOffset: (Long) -> Unit,
    onOpenCommands: (HexBinaryFinding) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = hexBinaryFindingSeverityLabel(finding.severity),
                style = MaterialTheme.typography.labelSmall,
                color = hexBinaryFindingSeverityColor(finding.severity),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = hexBinaryFindingKindLabel(finding.kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onOpenCommands(finding) }) {
                Text(stringResource(Strings.hex_workbench_commands_title))
            }
            finding.offset?.let { offset ->
                TextButton(onClick = { onGotoOffset(offset) }) {
                    Text(stringResource(Strings.hex_workbench_goto_finding))
                }
            }
        }
        Text(
            text = finding.primary.compactForAnalysisPanel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        finding.secondary?.takeIf { it.isNotBlank() }?.let { secondary ->
            Text(
                text = secondary.compactForAnalysisPanel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun HexWorkbenchCommandsDialog(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    finding: HexBinaryFinding?,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    fun copyCommand(label: String, command: String) {
        scope.launch {
            clipboard.setClipEntry(
                ClipData.newPlainText(label, command).toClipEntry()
            )
        }
    }

    val navigationScript = formatHexNavigationScript(state.selectedOffset)
    val selectionScript = state.selectionRange?.let { formatHexSelectionDumpScript(it) }
    val patchScript = formatHexPatchScript(state.stagedPatches)
    val fallbackFinding = remember(analysis) { buildHexBinaryFindings(analysis, maxCount = 1).firstOrNull() }
    val activeFinding = finding ?: fallbackFinding
    val workbenchScript = formatHexWorkbenchScript(
        selectedOffset = state.selectedOffset,
        selectionRange = state.selectionRange,
        patches = state.stagedPatches
    )
    val reverseActions = remember(state.selectedOffset, state.selectionRange, analysis, activeFinding) {
        buildHexReverseActions(
            selectedOffset = state.selectedOffset,
            selectionRange = state.selectionRange,
            analysis = analysis,
            finding = activeFinding
        )
    }
    val readOnlyAnalysisScript = reverseActions.actionContent(HexReverseActionKind.READ_ONLY_ANALYSIS)
    val disassemblyPreviewScript = reverseActions.actionContent(HexReverseActionKind.DISASSEMBLY_PREVIEW)
    val fridaHookTemplate = reverseActions.actionContent(HexReverseActionKind.FRIDA_HOOK)
    val lldbBreakpointTemplate = reverseActions.actionContent(HexReverseActionKind.LLDB_BREAKPOINT)

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_workbench_commands_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.hex_workbench_commands_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        activeFinding?.let { selectedFinding ->
                            Text(
                                text = stringResource(
                                    Strings.hex_workbench_active_finding,
                                    hexBinaryFindingKindLabel(selectedFinding.kind),
                                    selectedFinding.primary.compactForAnalysisPanel()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_navigation_script),
                    script = navigationScript,
                    onCopy = { copyCommand("hex-r2-navigation", navigationScript) }
                )
                if (selectionScript == null) {
                    TinaDialogCard {
                        Text(
                            text = stringResource(Strings.hex_workbench_commands_no_selection),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    HexCommandSnippetCard(
                        title = stringResource(Strings.hex_workbench_selection_script),
                        script = selectionScript,
                        onCopy = { copyCommand("hex-r2-selection", selectionScript) }
                    )
                }
                if (patchScript.isBlank()) {
                    TinaDialogCard {
                        Text(
                            text = stringResource(Strings.hex_workbench_commands_no_patches),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    HexCommandSnippetCard(
                        title = stringResource(Strings.hex_workbench_patch_script),
                        script = patchScript,
                        onCopy = { copyCommand("hex-r2-patches", patchScript) }
                    )
                }
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_full_script),
                    script = workbenchScript,
                    onCopy = { copyCommand("hex-r2-workbench", workbenchScript) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_read_only_analysis_script),
                    script = readOnlyAnalysisScript,
                    onCopy = { copyCommand("hex-r2-read-only-analysis", readOnlyAnalysisScript) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_disassembly_preview_script),
                    script = disassemblyPreviewScript,
                    onCopy = { copyCommand("hex-r2-disassembly-preview", disassemblyPreviewScript) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_frida_hook_template),
                    script = fridaHookTemplate,
                    onCopy = { copyCommand("hex-frida-hook-template", fridaHookTemplate) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_lldb_breakpoint_template),
                    script = lldbBreakpointTemplate,
                    onCopy = { copyCommand("hex-lldb-breakpoint-template", lldbBreakpointTemplate) }
                )
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
internal fun HexWorkbenchReportDialog(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val report = remember(analysis) { buildHexJniAnalysisReport(analysis) }
    val reverseActions = remember(state.selectedOffset, state.selectionRange, analysis) {
        buildHexReverseActions(
            selectedOffset = state.selectedOffset,
            selectionRange = state.selectionRange,
            analysis = analysis
        )
    }
    val markdownReport = reverseActions.actionContent(HexReverseActionKind.JNI_MARKDOWN_REPORT)
    val jsonReport = reverseActions.actionContent(HexReverseActionKind.JNI_JSON_REPORT)

    fun copyReport(label: String, reportText: String) {
        scope.launch {
            clipboard.setClipEntry(
                ClipData.newPlainText(label, reportText).toClipEntry()
            )
        }
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_workbench_reports_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Text(
                        text = stringResource(
                            Strings.hex_workbench_reports_summary,
                            report.nativeMethods.size,
                            report.nativeLibraries.size,
                            report.jniHints.size,
                            report.nativeApis.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_markdown_report),
                    script = markdownReport,
                    copyLabel = stringResource(Strings.hex_workbench_copy_report),
                    onCopy = { copyReport("hex-jni-report-markdown", markdownReport) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_json_report),
                    script = jsonReport,
                    copyLabel = stringResource(Strings.hex_workbench_copy_report),
                    onCopy = { copyReport("hex-jni-report-json", jsonReport) }
                )
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
internal fun HexCommandSnippetCard(
    title: String,
    script: String,
    copyLabel: String? = null,
    onCopy: () -> Unit
) {
    TinaDialogCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCopy) {
                    Text(copyLabel ?: stringResource(Strings.hex_workbench_copy_script))
                }
            }
            Text(
                text = script,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}
