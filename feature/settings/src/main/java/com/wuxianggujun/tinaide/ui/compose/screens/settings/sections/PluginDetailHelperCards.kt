package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.EditorThemeIndex
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginConfigurationPropertyType
import com.wuxianggujun.tinaide.plugin.PluginConfigurationSchema
import com.wuxianggujun.tinaide.plugin.PluginConfigurationStore
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticCategory
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticEntry
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticIssue
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSeverity
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsReport
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsSnapshotFactory
import com.wuxianggujun.tinaide.plugin.PluginDoctor
import com.wuxianggujun.tinaide.plugin.PluginFaultRecord
import com.wuxianggujun.tinaide.plugin.PluginHostLogSources
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.ResolvedPluginConfigurationProperty
import com.wuxianggujun.tinaide.plugin.ThemeConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspInstallProgress
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInfo
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInstallState
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.plugin.lsp.ToolchainInstallState
import com.wuxianggujun.tinaide.plugin.script.PermissionLevel
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginInfo
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginManager
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.plugin.toDiagnosticsReport
import com.wuxianggujun.tinaide.ui.compose.components.DetailHeaderCard
import com.wuxianggujun.tinaide.ui.compose.components.DetailIconPlaceholder
import com.wuxianggujun.tinaide.ui.compose.components.DetailInfoCard
import com.wuxianggujun.tinaide.ui.compose.components.LspToolchainConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.LspToolchainProgressDialog
import com.wuxianggujun.tinaide.ui.compose.components.PluginPermissionDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaInfoDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import java.util.Locale
import java.util.Date
import java.text.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/**
 * Plugin detail helper cards: info rows, command contributions, LSP dependency status.
 */

@Composable
internal fun PluginInfoRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun PluginCommandContributionsCard(
    commands: List<PluginsCommandContribution>,
    summary: PluginsCommandContributionSummary,
    filterOptions: List<PluginCommandContributionFilterOption>,
    selectedFilter: PluginCommandContributionFilter,
    onFilterSelected: (PluginCommandContributionFilter) -> Unit,
    isScriptPlugin: Boolean,
    onActionClick: (PluginDiagnosticAction, PluginsCommandContribution) -> Unit,
) {
    DetailInfoCard(
        title = stringResource(Strings.plugins_commands_title)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
            Text(
                text = stringResource(
                    Strings.plugins_commands_summary,
                    summary.totalCount,
                    summary.availableCount,
                    summary.issueCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filterOptions.size > 1) {
                PluginCommandContributionFilterRow(
                    filters = filterOptions,
                    selectedFilter = selectedFilter,
                    onFilterSelected = onFilterSelected,
                )
            }
            commands.forEachIndexed { index, command ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                PluginCommandContributionRow(
                    command = command,
                    isScriptPlugin = isScriptPlugin,
                    onActionClick = onActionClick,
                )
            }
        }
    }
}

@Composable
internal fun PluginCommandContributionFilterRow(
    filters: List<PluginCommandContributionFilterOption>,
    selectedFilter: PluginCommandContributionFilter,
    onFilterSelected: (PluginCommandContributionFilter) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TinaSpacing.sm),
    ) {
        filters.forEach { option ->
            FilterChip(
                selected = selectedFilter == option.filter,
                onClick = { onFilterSelected(option.filter) },
                label = {
                    Text(
                        text = stringResource(
                            Strings.plugins_diagnostics_source_filter_chip,
                            stringResource(
                                PluginsSettingsSectionSupport.resolvePluginCommandContributionFilterLabelRes(
                                    option.filter,
                                )
                            ),
                            option.count,
                        )
                    )
                },
            )
        }
    }
}

@Composable
internal fun PluginCommandContributionRow(
    command: PluginsCommandContribution,
    isScriptPlugin: Boolean,
    onActionClick: (PluginDiagnosticAction, PluginsCommandContribution) -> Unit,
) {
    val missingCommandIdText = stringResource(Strings.plugins_commands_missing_command_id_value)
    val commandIdText = if (command.commandId.isBlank()) {
        missingCommandIdText
    } else {
        command.commandId
    }
    val commandTitle = if (command.title.isBlank()) {
        commandIdText
    } else {
        command.title
    }
    val statusText = stringResource(
        PluginsSettingsSectionSupport.resolvePluginCommandStatusLabelRes(command.status)
    )
    val statusColor = when (command.status) {
        PluginCommandContributionStatus.AVAILABLE -> MaterialTheme.colorScheme.tertiary
        PluginCommandContributionStatus.MISSING_COMMAND_ID,
        PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION,
        PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION,
        PluginCommandContributionStatus.UNAVAILABLE,
        PluginCommandContributionStatus.EXECUTION_FAILED -> MaterialTheme.colorScheme.error
    }

    Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)) {
        Text(
            text = commandTitle,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                Strings.plugins_commands_metadata,
                stringResource(
                    PluginsSettingsSectionSupport.resolvePluginCommandSurfaceLabelRes(
                        command.surface
                    )
                ),
                stringResource(
                    PluginsSettingsSectionSupport.resolvePluginCommandSourceLabelRes(
                        command.source
                    )
                ),
                statusText,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
        PluginInfoRow(stringResource(Strings.plugins_commands_id, commandIdText))
        PluginInfoRow(stringResource(Strings.plugins_commands_group, command.group))
        command.whenExpression?.let { whenExpression ->
            PluginInfoRow(stringResource(Strings.plugins_commands_when, whenExpression))
        }
        command.statusMessage?.let { statusMessage ->
            PluginInfoRow(stringResource(Strings.plugins_commands_status_message, statusMessage))
        }
        val actions = PluginsSettingsSectionSupport.resolvePluginCommandContributionActions(
            command = command,
            isScriptPlugin = isScriptPlugin,
        )
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
            ) {
                actions.forEach { action ->
                    TinaTextButton(
                        text = stringResource(
                            PluginsSettingsSectionSupport.resolvePluginDiagnosticActionLabelRes(action)
                        ),
                        onClick = { onActionClick(action, command) },
                        contentPadding = PaddingValues(
                            horizontal = TinaSpacing.sm,
                            vertical = TinaSpacing.xxs,
                        ),
                    )
                }
            }
        }
    }
}

internal fun List<PluginsPackageRequirementGroup>.toPluginRequirementsPackageDisplay(): String = joinToString(
    separator = "; "
) { group ->
    "${group.manager}: ${group.packages.joinToString(", ")}"
}

@Composable
internal fun LspDependencyStatusCard(
    lspPluginInfo: LspPluginInfo,
    lspInstallState: LspPluginInstallState?,
    onOpenErrorLogs: () -> Unit,
    onInstallLspDeps: () -> Unit,
) {
    val hasToolchains = lspPluginInfo.toolchainConfigs.isNotEmpty()
    val isReady = lspInstallState?.serverReady == true
    val isRepairMode = lspInstallState.requiresLspDependencyRepair()
    val statusText = stringResource(
        PluginsSettingsSectionSupport.resolveLspStatusLabelRes(isReady)
    )
    val statusColor = if (isReady) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }
    val statusBackground = if (isReady) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val descriptionRes = when {
        !hasToolchains -> Strings.lsp_plugin_dependency_status_no_toolchains_desc
        isReady -> Strings.lsp_plugin_dependency_status_ready_desc
        isRepairMode -> Strings.lsp_plugin_dependency_status_repair_desc
        else -> Strings.lsp_plugin_dependency_status_install_desc
    }

    DetailInfoCard(
        title = stringResource(Strings.lsp_plugin_dependency_status_title)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Strings.lsp_plugin_dependency_status_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    modifier = Modifier
                        .background(statusBackground, RoundedCornerShape(TinaShapes.ExtraSmallCorner))
                        .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs),
                )
            }

            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            lspInstallState?.lastError
                ?.takeIf { it.isNotBlank() }
                ?.let { lastError ->
                    Text(
                        text = stringResource(Strings.lsp_plugin_dependency_last_error, lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
                                RoundedCornerShape(TinaShapes.SmallCorner),
                            )
                            .padding(TinaSpacing.sm),
                    )
                }

            if (hasToolchains) {
                Text(
                    text = stringResource(Strings.lsp_plugin_dependency_toolchains_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)) {
                    lspPluginInfo.toolchainConfigs.forEach { toolchain ->
                        val state = lspInstallState?.toolchainStates?.get(toolchain.id)
                            ?: ToolchainInstallState.NOT_INSTALLED
                        val stateText = stringResource(resolveToolchainInstallStateLabelRes(state))
                        val requirementText = stringResource(
                            if (toolchain.required) {
                                Strings.lsp_plugin_toolchain_required
                            } else {
                                Strings.lsp_plugin_toolchain_optional
                            }
                        )
                        Text(
                            text = stringResource(
                                Strings.lsp_plugin_dependency_toolchain_line,
                                toolchain.name,
                                requirementText,
                                stateText,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TinaTextButton(
                    text = stringResource(Strings.lsp_plugin_open_error_logs),
                    onClick = onOpenErrorLogs,
                    contentPadding = PaddingValues(
                        horizontal = TinaSpacing.sm,
                        vertical = TinaSpacing.xxs,
                    ),
                )
                if (hasToolchains && !isReady) {
                    TinaPrimaryButton(
                        text = stringResource(
                            if (isRepairMode) {
                                Strings.lsp_plugin_repair_deps
                            } else {
                                Strings.lsp_plugin_install_deps
                            }
                        ),
                        onClick = onInstallLspDeps,
                        contentPadding = PaddingValues(
                            horizontal = TinaSpacing.md,
                            vertical = TinaSpacing.xs,
                        ),
                    )
                }
            }
        }
    }
}

