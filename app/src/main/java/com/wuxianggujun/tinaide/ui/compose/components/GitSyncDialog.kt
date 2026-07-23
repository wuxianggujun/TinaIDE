package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.git.GitBranch
import com.wuxianggujun.tinaide.core.git.GitConflictKind
import com.wuxianggujun.tinaide.core.git.GitRemote
import com.wuxianggujun.tinaide.core.i18n.Strings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitSyncDialog(
    currentBranch: String?,
    branches: List<GitBranch>,
    remotes: List<GitRemote>,
    isSyncing: Boolean,
    output: String?,
    error: String?,
    onFetch: (remote: String, branch: String?, prune: Boolean) -> Unit,
    onPull: (remote: String, branch: String?, rebase: Boolean) -> Unit,
    onPush: (remote: String, branch: String?, setUpstream: Boolean, force: Boolean, tags: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    conflicts: List<String> = emptyList(),
    conflictKind: GitConflictKind = GitConflictKind.NONE,
    onOpenConflicts: () -> Unit = {}
) {
    val defaultRemote = remember(remotes) { remotes.firstOrNull()?.name ?: "origin" }
    val defaultBranch = currentBranch ?: branches.firstOrNull { !it.isRemote }?.name

    var remote by remember { mutableStateOf(defaultRemote) }
    var branch by remember { mutableStateOf(defaultBranch.orEmpty()) }
    var rebase by remember { mutableStateOf(false) }
    var prune by remember { mutableStateOf(false) }
    var force by remember { mutableStateOf(false) }
    var setUpstream by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf(false) }
    var showForceConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(defaultRemote) {
        remote = defaultRemote
    }
    LaunchedEffect(defaultBranch) {
        if (branch.isBlank()) {
            branch = defaultBranch.orEmpty()
        }
    }

    val localBranches = remember(branches) {
        branches.filter { !it.isRemote }.map { it.name }.distinct().sorted()
    }
    val remoteNames = remember(remotes) { remotes.map { it.name }.distinct().sorted() }

    fun effectiveBranchOrNull(): String? {
        if (tags) return null
        val typed = branch.trim().takeIf { it.isNotBlank() }
        return typed ?: currentBranch?.trim()?.takeIf { it.isNotBlank() }
    }

    fun resolvedRemote(): String = remote.trim().ifBlank { defaultRemote }

    fun doPush() {
        onPush(
            resolvedRemote(),
            effectiveBranchOrNull(),
            setUpstream,
            force,
            tags
        )
    }

    if (showForceConfirm) {
        TinaConfirmDialog(
            title = stringResource(Strings.git_force_push_title),
            message = stringResource(Strings.git_force_push_message),
            confirmText = stringResource(Strings.git_force_push_confirm),
            dismissText = stringResource(Strings.btn_cancel),
            isDanger = true,
            onConfirm = {
                showForceConfirm = false
                doPush()
            },
            onDismiss = { showForceConfirm = false }
        )
    }

    TinaAlertDialog(
        onDismissRequest = { if (!isSyncing) onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TinaDialogTitleText(stringResource(Strings.git_sync_title))
                currentBranch?.takeIf { it.isNotBlank() }?.let { branchName ->
                    Text(
                        text = stringResource(Strings.git_sync_current_branch, branchName),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TinaDialogCard(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    var remoteMenuExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = remoteMenuExpanded,
                        onExpandedChange = { if (!isSyncing) remoteMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = remote,
                            onValueChange = { remote = it },
                            enabled = !isSyncing,
                            label = { Text(stringResource(Strings.git_sync_remote_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = remoteMenuExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(
                                    ExposedDropdownMenuAnchorType.PrimaryEditable,
                                    enabled = !isSyncing
                                )
                        )
                        TinaDropdownMenu(
                            expanded = remoteMenuExpanded,
                            onDismissRequest = { remoteMenuExpanded = false }
                        ) {
                            remoteNames.forEach { item ->
                                TinaDropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = {
                                        remote = item
                                        remoteMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    var branchMenuExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = branchMenuExpanded,
                        onExpandedChange = { if (!isSyncing && !tags) branchMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = branch,
                            onValueChange = { branch = it },
                            enabled = !isSyncing && !tags,
                            label = { Text(stringResource(Strings.git_sync_branch_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = branchMenuExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(
                                    ExposedDropdownMenuAnchorType.PrimaryEditable,
                                    enabled = !isSyncing && !tags
                                )
                        )
                        TinaDropdownMenu(
                            expanded = branchMenuExpanded,
                            onDismissRequest = { branchMenuExpanded = false }
                        ) {
                            currentBranch?.let { branchName ->
                                TinaDropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                Strings.git_sync_current_branch,
                                                branchName
                                            )
                                        )
                                    },
                                    onClick = {
                                        branch = branchName
                                        branchMenuExpanded = false
                                    }
                                )
                            }
                            localBranches.forEach { item ->
                                TinaDropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = {
                                        branch = item
                                        branchMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TinaPrimaryButton(
                        text = stringResource(Strings.git_action_pull),
                        onClick = {
                            onPull(resolvedRemote(), effectiveBranchOrNull(), rebase)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSyncing
                    )
                    TinaOutlinedButton(
                        text = stringResource(Strings.git_action_fetch),
                        onClick = {
                            onFetch(resolvedRemote(), effectiveBranchOrNull(), prune)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSyncing
                    )
                    TinaSecondaryButton(
                        text = stringResource(Strings.git_action_push),
                        onClick = {
                            if (force) {
                                showForceConfirm = true
                            } else {
                                doPush()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSyncing
                    )
                }

                TinaDialogCard(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SyncOptionRow(
                        text = stringResource(Strings.git_sync_rebase),
                        checked = rebase,
                        enabled = !isSyncing,
                        onCheckedChange = { rebase = it }
                    )
                    SyncOptionRow(
                        text = stringResource(Strings.git_sync_prune),
                        checked = prune,
                        enabled = !isSyncing,
                        onCheckedChange = { prune = it }
                    )
                    SyncOptionRow(
                        text = stringResource(Strings.git_sync_set_upstream),
                        checked = setUpstream,
                        enabled = !isSyncing,
                        onCheckedChange = { setUpstream = it }
                    )
                    SyncOptionRow(
                        text = stringResource(Strings.git_sync_force_push),
                        checked = force,
                        enabled = !isSyncing,
                        onCheckedChange = { force = it }
                    )
                    SyncOptionRow(
                        text = stringResource(Strings.git_sync_push_tags),
                        checked = tags,
                        enabled = !isSyncing,
                        onCheckedChange = { tags = it }
                    )
                }

                if (isSyncing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                error?.let { syncError ->
                    TinaDialogMessageCard(
                        message = syncError,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                if (conflictKind != GitConflictKind.NONE) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Strings.git_conflict_inline_title),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = if (conflicts.isNotEmpty()) {
                                        stringResource(Strings.git_conflict_inline_desc, conflicts.size)
                                    } else {
                                        stringResource(Strings.git_conflict_inline_desc_no_files)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TinaTextButton(
                                text = stringResource(Strings.git_conflict_inline_action),
                                onClick = onOpenConflicts,
                                enabled = !isSyncing
                            )
                        }
                    }
                }

                if (!output.isNullOrBlank()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = PaddingValues(0.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = output,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = {
                    onClear()
                    onDismiss()
                },
                enabled = !isSyncing
            )
        },
        dismissButton = if (
            !output.isNullOrBlank() || error != null || conflictKind != GitConflictKind.NONE
        ) {
            {
                TinaTextButton(
                    text = stringResource(Strings.git_action_clear_output),
                    onClick = onClear,
                    enabled = !isSyncing
                )
            }
        } else {
            null
        },
        modifier = modifier
    )
}

@Composable
private fun SyncOptionRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
