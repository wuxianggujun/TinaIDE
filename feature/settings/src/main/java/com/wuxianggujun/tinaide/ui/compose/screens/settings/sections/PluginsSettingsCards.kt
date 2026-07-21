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
 * Plugin list cards, diagnostics overview, and install preflight dialog.
 */

@Composable
internal fun EmptyPluginsCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TinaSpacing.xl),
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TinaSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(Strings.settings_plugins_empty_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(TinaSpacing.xs))
            Text(
                text = stringResource(Strings.settings_plugins_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun PluginDiagnosticsOverviewCard(
    summary: PluginDiagnosticsOverviewSummary,
    selectedFilter: PluginDiagnosticsFilter,
    sourceFilterOptions: List<PluginDiagnosticSourceFilterOption>,
    selectedSourceFilter: PluginDiagnosticSourceFilter,
    onFilterSelected: (PluginDiagnosticsFilter) -> Unit,
    onSourceFilterSelected: (PluginDiagnosticSourceFilter) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TinaSpacing.xl, vertical = TinaSpacing.sm),
        shape = RoundedCornerShape(TinaShapes.SmallCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(TinaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
                PluginDiagnosticsOverviewMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(Strings.settings_plugins_overview_installed_count),
                    value = summary.installedCount.toString(),
                    valueColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    selected = selectedFilter == PluginDiagnosticsFilter.ALL,
                    onClick = { onFilterSelected(PluginDiagnosticsFilter.ALL) },
                )
                PluginDiagnosticsOverviewMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(Strings.settings_plugins_overview_error_count),
                    value = summary.errorPluginCount.toString(),
                    valueColor = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    selected = selectedFilter == PluginDiagnosticsFilter.ERROR,
                    onClick = { onFilterSelected(PluginDiagnosticsFilter.ERROR) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
                PluginDiagnosticsOverviewMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(Strings.settings_plugins_overview_warning_count),
                    value = summary.warningPluginCount.toString(),
                    valueColor = MaterialTheme.colorScheme.tertiary,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selected = selectedFilter == PluginDiagnosticsFilter.WARNING,
                    onClick = { onFilterSelected(PluginDiagnosticsFilter.WARNING) },
                )
                PluginDiagnosticsOverviewMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(Strings.settings_plugins_overview_not_loaded_count),
                    value = summary.notLoadedCount.toString(),
                    valueColor = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                    selected = selectedFilter == PluginDiagnosticsFilter.NOT_LOADED,
                    onClick = { onFilterSelected(PluginDiagnosticsFilter.NOT_LOADED) },
                )
            }
            if (selectedSourceFilter != PluginDiagnosticSourceFilter.ALL ||
                sourceFilterOptions.size > 2
            ) {
                PluginDiagnosticsOverviewSourceFilterRow(
                    options = sourceFilterOptions,
                    selectedFilter = selectedSourceFilter,
                    onFilterSelected = onSourceFilterSelected,
                )
            }
        }
    }
}

@Composable
internal fun PluginDiagnosticsOverviewMetric(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color,
    containerColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(TinaShapes.SmallCorner)
    Column(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .background(
                if (selected) containerColor else containerColor.copy(alpha = 0.72f)
            )
            .padding(horizontal = TinaSpacing.lg, vertical = TinaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
internal fun PluginDiagnosticsFilteredEmptyCard() {
    SettingsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TinaSpacing.xl)
    ) {
        Column(
            modifier = Modifier.padding(TinaSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)
        ) {
            Text(
                text = stringResource(Strings.settings_plugins_filtered_empty_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(Strings.settings_plugins_filtered_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginDiagnosticsOverviewSourceFilterRow(
    options: List<PluginDiagnosticSourceFilterOption>,
    selectedFilter: PluginDiagnosticSourceFilter,
    onFilterSelected: (PluginDiagnosticSourceFilter) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TinaSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selectedFilter == option.filter,
                onClick = { onFilterSelected(option.filter) },
                label = {
                    Text(
                        text = stringResource(
                            Strings.plugins_diagnostics_source_filter_chip,
                            stringResource(
                                PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterLabelRes(
                                    option.filter
                                )
                            ),
                            option.count
                        )
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun PluginCard(
    plugin: InstalledPlugin,
    currentEditorThemeId: String,
    themesIndex: Map<String, ThemeConfig>,
    lspPluginInfo: LspPluginInfo? = null,
    lspInstallState: LspPluginInstallState? = null,
    diagnosticsReport: PluginDiagnosticsReport? = null,
    onClick: () -> Unit
) {
    val themeIds = PluginsSettingsSectionSupport.buildPluginThemeIds(
        pluginId = plugin.manifest.id,
        relativePaths = plugin.manifest.contributions?.themes.orEmpty(),
        themesIndex = themesIndex,
    )
    val hasThemes = themeIds.isNotEmpty()

    // LSP 插件相关
    val isLspPlugin = lspPluginInfo != null
    val isLspReady = lspInstallState?.serverReady == true
    val diagnosticsSummary = PluginsSettingsSectionSupport.resolvePluginDiagnosticsSummary(diagnosticsReport)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TinaSpacing.xl)
            .combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TinaSpacing.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 插件图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(TinaShapes.SmallCorner))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = PluginsSettingsSectionSupport.resolvePluginInitial(plugin.manifest.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(TinaSpacing.lg))

            // 插件信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.manifest.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
                ) {
                    Text(
                        text = "v${plugin.manifest.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 状态标签
                    if (!plugin.enabled) {
                        Text(
                            text = stringResource(Strings.plugins_status_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                                )
                                .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
                        )
                    }

                    if (plugin.manifest.isBundled) {
                        Text(
                            text = stringResource(Strings.plugins_status_bundled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                                )
                                .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
                        )
                    }

                    if (hasThemes) {
                        Text(
                            text = stringResource(Strings.plugins_has_themes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                                )
                                .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
                        )
                    }

                    if (isLspPlugin) {
                        val lspStatusText = stringResource(
                            PluginsSettingsSectionSupport.resolveLspStatusLabelRes(isLspReady)
                        )
                        val lspStatusColor = if (isLspReady) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                        Text(
                            text = lspStatusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = lspStatusColor,
                            modifier = Modifier
                                .background(
                                    if (isLspReady) {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    },
                                    RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                                )
                                .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
                        )
                    }

                    if (diagnosticsSummary.totalCount > 0) {
                        val badgeColor = when (diagnosticsSummary.highestSeverity) {
                            PluginDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
                            PluginDiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                            PluginDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.primary
                            null -> MaterialTheme.colorScheme.primary
                        }
                        val badgeBackground = when (diagnosticsSummary.highestSeverity) {
                            PluginDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
                            PluginDiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                            PluginDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.primaryContainer
                            null -> MaterialTheme.colorScheme.primaryContainer
                        }
                        Text(
                            text = stringResource(
                                Strings.plugins_status_diagnostics_issue_count,
                                diagnosticsSummary.totalCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier
                                .background(
                                    badgeBackground,
                                    RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                                )
                                .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PluginSelectableCard(
    plugin: InstalledPlugin,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TinaSpacing.xl)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TinaSpacing.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
            Spacer(modifier = Modifier.width(TinaSpacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.manifest.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${plugin.manifest.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun PluginActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(TinaShapes.SmallCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = TinaSpacing.xl, vertical = TinaSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(TinaSpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontSize = 11.sp
        )
    }
}

@Composable
internal fun PluginInstallPreflightDialog(
    report: PluginDiagnosticsReport,
    isBlocking: Boolean,
    onCopyDiagnostic: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val diagnosticGroups = remember(report) {
        PluginsSettingsSectionSupport.resolvePluginPreflightDiagnosticGroups(report)
    }
    val summary = remember(report) {
        PluginsSettingsSectionSupport.resolvePluginDiagnosticsSummary(report)
    }
    val title = stringResource(
        if (isBlocking) {
            Strings.dialog_title_plugins_preflight_blocked
        } else {
            Strings.dialog_title_plugins_preflight_warning
        }
    )
    val message = stringResource(
        if (isBlocking) {
            Strings.dialog_msg_plugins_preflight_blocked
        } else {
            Strings.dialog_msg_plugins_preflight_warning
        },
        report.pluginName,
    )

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(title) },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                TinaDialogMessageCard(
                    message = message,
                    color = if (isBlocking) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.36f)
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f)
                    },
                    textColor = if (isBlocking) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
                Text(
                    text = stringResource(
                        Strings.plugins_preflight_diagnostics_summary,
                        summary.errorCount,
                        summary.warningCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diagnosticGroups.forEachIndexed { groupIndex, group ->
                    val categoryText = stringResource(
                        PluginsSettingsSectionSupport.resolvePluginDiagnosticCategoryLabelRes(
                            group.category
                        )
                    )
                    val guideText = stringResource(
                        PluginsSettingsSectionSupport.resolvePluginPreflightCategoryGuideRes(
                            group.category
                        )
                    )
                    if (groupIndex > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs)) {
                        Text(
                            text = stringResource(
                                Strings.plugins_preflight_diagnostics_group_header,
                                categoryText,
                                group.errorCount,
                                group.warningCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = guideText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        group.entries.forEachIndexed { entryIndex, entry ->
                            if (entryIndex > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)
                                )
                            }
                            PluginDiagnosticEntryRow(entry = entry)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isBlocking) {
                TinaTextButton(
                    text = stringResource(Strings.btn_close),
                    onClick = onConfirm,
                )
            } else {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_continue_install),
                    onClick = onConfirm,
                )
            }
        },
        dismissButton = if (isBlocking) {
            {
                TinaTextButton(
                    text = stringResource(Strings.action_copy),
                    onClick = onCopyDiagnostic,
                )
            }
        } else {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TinaTextButton(
                        text = stringResource(Strings.action_copy),
                        onClick = onCopyDiagnostic,
                    )
                    TinaTextButton(
                        text = stringResource(Strings.btn_cancel),
                        onClick = onDismiss,
                    )
                }
            }
        },
    )
}

