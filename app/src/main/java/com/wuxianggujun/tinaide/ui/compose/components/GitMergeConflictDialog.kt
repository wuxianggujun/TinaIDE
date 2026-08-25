package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.git.GitConflictKind
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSecondaryButton

@Composable
fun GitMergeConflictDialog(
    conflictKind: GitConflictKind,
    conflicts: List<String>,
    isBusy: Boolean,
    error: String?,
    onOpenFile: (String) -> Unit,
    onUseOurs: (String) -> Unit,
    onUseTheirs: (String) -> Unit,
    onContinue: () -> Unit,
    onSkipRebase: () -> Unit,
    onAbort: () -> Unit,
    onMarkResolved: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(conflicts) { mutableStateOf<Set<String>>(emptySet()) }

    val canAct = !isBusy
    val selectedList = selected.toList().sorted()

    TinaAlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { TinaDialogTitleText(stringResource(Strings.git_conflict_title)) },
        text = {
            TinaDialogContentColumn {
                if (error != null) {
                    TinaDialogMessageCard(
                        message = error,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                        )
                    )
                }

                TinaDialogMessageCard(
                    message = when (conflictKind) {
                        GitConflictKind.REBASE -> stringResource(Strings.git_conflict_desc_rebase)
                        GitConflictKind.MERGE -> stringResource(Strings.git_conflict_desc_merge)
                        GitConflictKind.NONE -> stringResource(Strings.git_conflict_desc)
                    }
                )

                if (conflicts.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(conflicts, key = { it }) { path ->
                            ConflictFileCard(
                                path = path,
                                selected = selected.contains(path),
                                isBusy = isBusy,
                                onSelectedChange = { checked ->
                                    selected = if (checked) selected + path else selected - path
                                },
                                onOpenFile = { onOpenFile(path) },
                                onUseOurs = { onUseOurs(path) },
                                onUseTheirs = { onUseTheirs(path) }
                            )
                        }
                    }
                } else {
                    TinaDialogMessageCard(
                        message = stringResource(Strings.git_conflict_no_files)
                    )
                }

                if (conflicts.isNotEmpty()) {
                    TinaDialogMessageCard(
                        message = if (selected.isEmpty()) {
                            stringResource(Strings.git_conflict_select_hint)
                        } else {
                            stringResource(Strings.git_conflict_selected_count, selected.size)
                        },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        // 工作流多动作：竖排全宽，避免 confirm/dismiss 各塞一排横钮
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TinaPrimaryButton(
                    text = when (conflictKind) {
                        GitConflictKind.REBASE -> stringResource(Strings.git_conflict_continue_rebase)
                        GitConflictKind.MERGE -> stringResource(Strings.git_conflict_finish_merge)
                        GitConflictKind.NONE -> stringResource(Strings.git_conflict_continue_generic)
                    },
                    onClick = onContinue,
                    enabled = canAct && conflictKind != GitConflictKind.NONE && conflicts.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TinaSecondaryButton(
                    text = stringResource(Strings.git_conflict_mark_resolved),
                    onClick = {
                        val targets = if (selectedList.isEmpty()) conflicts else selectedList
                        onMarkResolved(targets)
                    },
                    enabled = canAct && conflicts.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (conflictKind == GitConflictKind.REBASE) {
                    TinaOutlinedButton(
                        text = stringResource(Strings.git_conflict_skip_rebase),
                        onClick = onSkipRebase,
                        enabled = canAct,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TinaDangerButton(
                    text = stringResource(Strings.git_conflict_abort),
                    onClick = onAbort,
                    enabled = canAct,
                    modifier = Modifier.fillMaxWidth(),
                )
                TinaTextButton(
                    text = stringResource(Strings.btn_close),
                    onClick = onDismiss,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun ConflictFileCard(
    path: String,
    selected: Boolean,
    isBusy: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpenFile: () -> Unit,
    onUseOurs: () -> Unit,
    onUseTheirs: () -> Unit
) {
    TinaDialogSelectableCard(
        selected = selected,
        onClick = { if (!isBusy) onSelectedChange(!selected) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        selectedColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f),
        unselectedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
        selectedBorder = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.material3.Checkbox(
                checked = selected,
                onCheckedChange = { checked -> onSelectedChange(checked) },
                enabled = !isBusy
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TinaTextButton(
                text = stringResource(Strings.git_conflict_open_file),
                onClick = onOpenFile,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            TinaOutlinedButton(
                text = stringResource(Strings.git_conflict_use_ours),
                onClick = onUseOurs,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            TinaOutlinedButton(
                text = stringResource(Strings.git_conflict_use_theirs),
                onClick = onUseTheirs,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
