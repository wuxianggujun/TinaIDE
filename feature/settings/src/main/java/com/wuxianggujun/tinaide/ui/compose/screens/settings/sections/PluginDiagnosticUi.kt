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
 * Plugin load issues and diagnostic list UI helpers.
 */

@Composable
internal fun PluginLoadIssuesCard(
    loadReports: List<PluginDiagnosticsReport>,
    onCopyDiagnostic: (PluginDiagnosticsReport, PluginDiagnosticEntry) -> Unit,
) {
    SettingsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TinaSpacing.xl)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.sm)) {
            Text(
                text = stringResource(Strings.settings_plugins_load_issues),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            loadReports.forEachIndexed { index, report ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.xxs)) {
                    Text(
                        text = report.pluginName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    report.entries.forEachIndexed { entryIndex, entry ->
                        if (entryIndex > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        PluginDiagnosticEntryRow(
                            entry = entry,
                            actions = PluginsSettingsSectionSupport.resolvePluginDiagnosticActions(
                                entry = entry,
                                isScriptPlugin = false,
                                isInstalled = report.isInstalled,
                            ),
                            onActionClick = { action ->
                                if (action == PluginDiagnosticAction.COPY_DIAGNOSTIC) {
                                    onCopyDiagnostic(report, entry)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PluginDiagnosticSourceFilterRow(
    filters: List<PluginDiagnosticSourceFilter>,
    selectedFilter: PluginDiagnosticSourceFilter,
    onFilterSelected: (PluginDiagnosticSourceFilter) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TinaSpacing.sm)
    ) {
        filters.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        text = stringResource(
                            PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterLabelRes(
                                filter
                            )
                        )
                    )
                }
            )
        }
    }
}

@Composable
internal fun PluginDiagnosticFilteredEmptyState() {
    Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)) {
        Text(
            text = stringResource(Strings.settings_plugins_diagnostics_filtered_empty_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(Strings.settings_plugins_diagnostics_filtered_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun PluginDiagnosticEntryRow(
    entry: PluginDiagnosticEntry,
    actions: List<PluginDiagnosticAction> = emptyList(),
    onActionClick: (PluginDiagnosticAction) -> Unit = {},
) {
    val severityText = stringResource(
        PluginsSettingsSectionSupport.resolvePluginDiagnosticSeverityLabelRes(
            entry.issue.severity
        )
    )
    val categoryText = stringResource(
        PluginsSettingsSectionSupport.resolvePluginDiagnosticCategoryLabelRes(
            entry.issue.category
        )
    )
    val sourceText = stringResource(
        PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceLabelRes(entry.source)
    )
    val severityColor = when (entry.issue.severity) {
        PluginDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
        PluginDiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        PluginDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.primary
    }
    PluginDiagnosticRow(
        severityText = severityText,
        categoryText = categoryText,
        sourceText = sourceText,
        severityColor = severityColor,
        issue = entry.issue,
        actions = actions,
        onActionClick = onActionClick,
    )
}

@Composable
internal fun PluginDiagnosticRow(
    severityText: String,
    categoryText: String,
    sourceText: String,
    severityColor: Color,
    issue: PluginDiagnosticIssue,
    actions: List<PluginDiagnosticAction> = emptyList(),
    onActionClick: (PluginDiagnosticAction) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)) {
        Text(
            text = stringResource(
                Strings.plugins_diagnostics_header,
                severityText,
                categoryText
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = severityColor
        )
        Text(
            text = sourceText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                )
                .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
        )
        Text(
            text = issue.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        issue.fixHint?.takeIf { it.isNotBlank() }?.let { fixHint ->
            Text(
                text = stringResource(Strings.plugins_diagnostics_fix_hint, fixHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(TinaSpacing.xs)
            ) {
                actions.forEach { action ->
                    TinaTextButton(
                        text = stringResource(
                            PluginsSettingsSectionSupport.resolvePluginDiagnosticActionLabelRes(
                                action
                            )
                        ),
                        onClick = { onActionClick(action) },
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

internal fun copyPluginDiagnosticToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Plugin Diagnostic", text))
}

internal fun List<String>?.toPluginPermissionDisplay(emptyText: String): String = orEmpty()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .joinToString(", ")
    .ifBlank { emptyText }

internal fun LspPluginInstallState?.requiresLspDependencyRepair(): Boolean = this?.lastError?.isNotBlank() == true ||
    this?.toolchainStates?.values?.any { state -> state == ToolchainInstallState.FAILED } == true

internal fun resolveToolchainInstallStateLabelRes(state: ToolchainInstallState): Int = when (state) {
    ToolchainInstallState.NOT_INSTALLED -> Strings.lsp_plugin_toolchain_not_installed
    ToolchainInstallState.INSTALLING -> Strings.lsp_plugin_toolchain_installing
    ToolchainInstallState.INSTALLED -> Strings.lsp_plugin_toolchain_installed
    ToolchainInstallState.FAILED -> Strings.lsp_plugin_toolchain_failed
}

internal fun Set<PluginPermission>.toPluginPermissionDisplay(emptyText: String): String = map { permission -> permission.id }
    .sorted()
    .joinToString(", ")
    .ifBlank { emptyText }

