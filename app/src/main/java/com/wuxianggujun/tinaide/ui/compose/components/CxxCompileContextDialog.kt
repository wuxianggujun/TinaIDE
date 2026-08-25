package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.CompileDatabaseProvider
import com.wuxianggujun.tinaide.core.lsp.CxxCompileCommandMatch
import com.wuxianggujun.tinaide.core.lsp.CxxCompileContextIssue
import com.wuxianggujun.tinaide.core.lsp.CxxCompileContextMode
import com.wuxianggujun.tinaide.core.lsp.CxxCompileContextSnapshot
import com.wuxianggujun.tinaide.core.lsp.CxxCompileDatabaseSource
import java.text.DateFormat
import java.util.Date

@Composable
fun CxxCompileContextDialog(
    status: EditorStatus,
    context: CxxCompileContextSnapshot?,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(stringResource(Strings.cxx_context_title))
        },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ContextSection(
                    title = stringResource(Strings.cxx_context_section_status),
                ) {
                    ContextValue(
                        label = stringResource(Strings.cxx_context_lsp_status),
                        value = lspStatusText(status),
                    )
                    if (context == null) {
                        Text(
                            text = if (status == EditorStatus.Connecting) {
                                stringResource(Strings.cxx_context_preparing)
                            } else {
                                stringResource(Strings.cxx_context_unavailable)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ContextValue(
                            label = stringResource(Strings.cxx_context_mode),
                            value = when (context.mode) {
                                CxxCompileContextMode.LOCAL -> stringResource(Strings.cxx_context_mode_local)
                                CxxCompileContextMode.REMOTE -> stringResource(Strings.cxx_context_mode_remote)
                            },
                        )
                        ContextValue(
                            label = stringResource(Strings.cxx_context_workspace),
                            value = context.workspaceRootPath,
                            monospace = true,
                        )
                        context.issue?.let { issue ->
                            Text(
                                text = issueText(issue),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (context?.mode == CxxCompileContextMode.REMOTE) {
                    ContextSection(title = stringResource(Strings.cxx_context_section_database)) {
                        Text(
                            text = stringResource(Strings.cxx_context_remote_managed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (context != null) {
                    CompileDatabaseSection(context)
                    CompileCommandSection(context)
                    if (context.includePaths.isNotEmpty()) {
                        ContextListSection(
                            title = stringResource(Strings.cxx_context_include_paths),
                            values = context.includePaths,
                        )
                    }
                    if (context.defines.isNotEmpty()) {
                        ContextListSection(
                            title = stringResource(Strings.cxx_context_defines),
                            values = context.defines,
                        )
                    }
                    if (context.commandArguments.isNotEmpty()) {
                        ContextListSection(
                            title = stringResource(Strings.cxx_context_arguments),
                            values = context.commandArguments,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss,
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.cxx_context_reload),
                onClick = onReload,
                enabled = status != EditorStatus.Connecting && status != EditorStatus.Busy,
            )
        },
    )
}

@Composable
private fun CompileDatabaseSection(context: CxxCompileContextSnapshot) {
    ContextSection(title = stringResource(Strings.cxx_context_section_database)) {
        ContextValue(
            label = stringResource(Strings.cxx_context_database_path),
            value = context.compileDatabasePath ?: stringResource(Strings.cxx_context_value_unavailable),
            monospace = true,
        )
        ContextValue(
            label = stringResource(Strings.cxx_context_database_source),
            value = when (context.compileDatabaseSource) {
                CxxCompileDatabaseSource.EXTERNAL -> stringResource(Strings.cxx_context_source_external)
                CxxCompileDatabaseSource.TINA_FALLBACK -> stringResource(Strings.cxx_context_source_fallback)
                null -> stringResource(Strings.cxx_context_value_unavailable)
            },
        )
        context.compileDatabaseUpdatedAtMillis?.let { updatedAt ->
            ContextValue(
                label = stringResource(Strings.cxx_context_database_updated_at),
                value = DateFormat.getDateTimeInstance().format(Date(updatedAt)),
            )
        }
        context.projectType?.let { projectType ->
            ContextValue(
                label = stringResource(Strings.cxx_context_project_type),
                value = when (projectType) {
                    CompileDatabaseProvider.ProjectType.CMAKE_PROJECT ->
                        stringResource(Strings.cxx_context_project_cmake)
                    CompileDatabaseProvider.ProjectType.SINGLE_FILE_PROJECT ->
                        stringResource(Strings.cxx_context_project_single_file)
                    CompileDatabaseProvider.ProjectType.STANDALONE_FILE ->
                        stringResource(Strings.cxx_context_project_standalone)
                },
            )
        }
    }
}

@Composable
private fun CompileCommandSection(context: CxxCompileContextSnapshot) {
    ContextSection(title = stringResource(Strings.cxx_context_section_command)) {
        ContextValue(
            label = stringResource(Strings.cxx_context_command_match),
            value = when (context.commandMatch) {
                CxxCompileCommandMatch.EXACT -> stringResource(Strings.cxx_context_match_exact)
                CxxCompileCommandMatch.INFERRED -> stringResource(Strings.cxx_context_match_inferred)
                CxxCompileCommandMatch.MISSING -> stringResource(Strings.cxx_context_match_missing)
                null -> stringResource(Strings.cxx_context_value_unavailable)
            },
        )
        context.matchedSourcePath?.let { value ->
            ContextValue(
                label = stringResource(Strings.cxx_context_matched_source),
                value = value,
                monospace = true,
            )
        }
        OptionalContextValue(Strings.cxx_context_compiler, context.compilerPath, monospace = true)
        OptionalContextValue(Strings.cxx_context_standard, context.languageStandard)
        OptionalContextValue(Strings.cxx_context_target, context.targetTriple)
        OptionalContextValue(Strings.cxx_context_toolchain, context.toolchainId)
        context.sysrootProfileId?.let { profileId ->
            ContextValue(
                label = stringResource(Strings.cxx_context_sysroot_profile),
                value = context.sysrootApiLevel?.let { apiLevel ->
                    stringResource(Strings.cxx_context_sysroot_profile_value, profileId, apiLevel)
                } ?: profileId,
            )
        }
        OptionalContextValue(Strings.cxx_context_sysroot, context.sysrootPath, monospace = true)
        OptionalContextValue(
            Strings.cxx_context_resource_directory,
            context.resourceDirectoryPath,
            monospace = true,
        )
    }
}

@Composable
private fun ContextSection(
    title: String,
    content: @Composable () -> Unit,
) {
    TinaDialogCard(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun ContextListSection(title: String, values: List<String>) {
    ContextSection(title) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            values.forEach { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun OptionalContextValue(labelRes: Int, value: String?, monospace: Boolean = false) {
    value?.takeIf(String::isNotBlank)?.let {
        ContextValue(stringResource(labelRes), it, monospace)
    }
}

@Composable
private fun ContextValue(label: String, value: String, monospace: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun lspStatusText(status: EditorStatus): String = when (status) {
    EditorStatus.Ready -> stringResource(Strings.editor_status_lsp_ready)
    EditorStatus.Connecting -> stringResource(Strings.editor_status_lsp_connecting)
    EditorStatus.Busy -> stringResource(Strings.editor_status_busy)
    EditorStatus.NoLsp -> stringResource(Strings.editor_status_no_lsp)
    EditorStatus.Error -> stringResource(Strings.editor_status_lsp_error)
}

@Composable
private fun issueText(issue: CxxCompileContextIssue): String = when (issue) {
    CxxCompileContextIssue.COMPILE_DATABASE_MISSING ->
        stringResource(Strings.cxx_context_issue_database_missing)
    CxxCompileContextIssue.COMPILE_DATABASE_INVALID ->
        stringResource(Strings.cxx_context_issue_database_invalid)
    CxxCompileContextIssue.FILE_COMMAND_MISSING ->
        stringResource(Strings.cxx_context_issue_command_missing)
    CxxCompileContextIssue.COMPILE_SETUP_UNAVAILABLE ->
        stringResource(Strings.cxx_context_issue_setup_unavailable)
    CxxCompileContextIssue.TOOLCHAIN_SETUP_FAILED ->
        stringResource(Strings.cxx_context_issue_toolchain_failed)
    CxxCompileContextIssue.CLANGD_START_FAILED ->
        stringResource(Strings.cxx_context_issue_clangd_failed)
}
