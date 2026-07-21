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
 * Installed plugin detail screen and related configuration/diagnostic UI.
 */

@Composable
internal fun InstalledPluginDetailScreen(
    plugin: InstalledPlugin,
    currentEditorThemeId: String,
    themesIndex: Map<String, ThemeConfig>,
    lspPluginInfo: LspPluginInfo?,
    lspInstallState: LspPluginInstallState?,
    scriptPluginInfo: ScriptPluginInfo?,
    faultRecord: PluginFaultRecord?,
    diagnosticsReport: PluginDiagnosticsReport?,
    initialDiagnosticsSourceFilter: PluginDiagnosticSourceFilter,
    grantedPermissions: Set<PluginPermission>,
    onGrantOptionalPermission: (PluginPermission) -> Unit,
    onRevokeOptionalPermission: (PluginPermission) -> Unit,
    onNavigateBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectTheme: () -> Unit,
    onUninstall: () -> Unit,
    onReload: () -> Unit,
    onOpenLogs: (PluginLogLevel?) -> Unit,
    onInstallLspDeps: () -> Unit
) {
    val context = LocalContext.current
    val manifest = plugin.manifest
    val isScriptPlugin = PluginsSettingsSectionSupport.isScriptPlugin(manifest)
    val themeIds = PluginsSettingsSectionSupport.buildPluginThemeIds(
        pluginId = manifest.id,
        relativePaths = manifest.contributions?.themes.orEmpty(),
        themesIndex = themesIndex,
    )
    val hasThemes = themeIds.isNotEmpty()

    val isLspPlugin = lspPluginInfo != null
    val hasToolchains = lspPluginInfo?.toolchainConfigs?.isNotEmpty() == true
    val isLspReady = lspInstallState?.serverReady == true

    val contributionSummary = PluginsSettingsSectionSupport.resolveContributionSummary(manifest)
    val requirementsSummary = PluginsSettingsSectionSupport.resolveRequirementsSummary(manifest)
    val configurationSummary = PluginsSettingsSectionSupport.resolveConfigurationSummary(manifest)
    val commandContributions = remember(manifest, plugin.enabled, scriptPluginInfo, grantedPermissions) {
        PluginsSettingsSectionSupport.resolveCommandContributions(
            manifest = manifest,
            checkRuntimeAvailability = plugin.enabled,
        )
    }
    val commandContributionSummary = remember(commandContributions) {
        PluginsSettingsSectionSupport.resolveCommandContributionSummary(commandContributions)
    }
    val commandContributionFilterOptions = remember(commandContributionSummary) {
        PluginsSettingsSectionSupport.resolveCommandContributionFilterOptions(commandContributionSummary)
    }
    var commandContributionFilter by remember(manifest.id) {
        mutableStateOf(PluginCommandContributionFilter.ALL)
    }
    LaunchedEffect(commandContributionFilterOptions, commandContributionFilter) {
        val resolvedFilter = PluginsSettingsSectionSupport.resolveCommandContributionFilterOrAll(
            filter = commandContributionFilter,
            availableFilters = commandContributionFilterOptions,
        )
        if (resolvedFilter != commandContributionFilter) {
            commandContributionFilter = resolvedFilter
        }
    }
    val filteredCommandContributions = remember(commandContributions, commandContributionFilter) {
        PluginsSettingsSectionSupport.filterCommandContributions(
            commands = commandContributions,
            filter = commandContributionFilter,
        )
    }
    val scope = rememberCoroutineScope()
    var doctorDiagnosticsReport by remember(manifest.id) { mutableStateOf<PluginDiagnosticsReport?>(null) }
    var isDoctorRunning by remember(manifest.id) { mutableStateOf(false) }
    val resolvedDiagnosticsReport = remember(plugin, diagnosticsReport, doctorDiagnosticsReport) {
        PluginsSettingsSectionSupport.resolveInstalledPluginDetailDiagnosticsReport(
            plugin = plugin,
            snapshotReport = diagnosticsReport,
            manualDoctorReport = doctorDiagnosticsReport,
        )
    }
    val diagnostics = resolvedDiagnosticsReport.entries
    val detailDiagnostics = remember(diagnostics, isLspPlugin) {
        if (isLspPlugin) {
            diagnostics.filterNot { entry -> entry.issue.category == PluginDiagnosticCategory.LSP }
        } else {
            diagnostics
        }
    }
    val diagnosticCopiedText = stringResource(Strings.diagnostic_copied)
    var showPluginPermissionsDialog by remember(manifest.id) { mutableStateOf(false) }
    var pendingOptionalPermission by remember(manifest.id) { mutableStateOf<PluginPermission?>(null) }
    val optionalPermissions = remember(manifest.optionalPermissions) {
        PluginPermission.parseList(manifest.optionalPermissions).sortedBy { permission -> permission.id }
    }
    val availableDiagnosticSourceFilters = remember(detailDiagnostics) {
        PluginsSettingsSectionSupport.resolveAvailablePluginDiagnosticSourceFilters(detailDiagnostics)
    }
    var diagnosticsSourceFilter by remember(
        manifest.id,
        initialDiagnosticsSourceFilter,
    ) {
        mutableStateOf(initialDiagnosticsSourceFilter)
    }
    val permissionNoneText = stringResource(Strings.plugins_details_permissions_none)
    val declaredPermissionsText = stringResource(
        Strings.plugins_details_permissions,
        manifest.permissions.toPluginPermissionDisplay(permissionNoneText),
    )
    val optionalPermissionsText = stringResource(
        Strings.plugins_details_optional_permissions,
        manifest.optionalPermissions.toPluginPermissionDisplay(permissionNoneText),
    )
    val grantedPermissionsText = stringResource(
        Strings.plugins_details_granted_permissions,
        grantedPermissions.toPluginPermissionDisplay(permissionNoneText),
    )
    val permissionDialogMessage = remember(
        declaredPermissionsText,
        optionalPermissionsText,
        grantedPermissionsText,
    ) {
        listOf(
            declaredPermissionsText,
            optionalPermissionsText,
            grantedPermissionsText,
        ).joinToString(separator = "\n")
    }
    LaunchedEffect(availableDiagnosticSourceFilters, diagnosticsSourceFilter) {
        val resolvedFilter = PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterOrAll(
            filter = diagnosticsSourceFilter,
            availableFilters = availableDiagnosticSourceFilters,
        )
        if (resolvedFilter != diagnosticsSourceFilter) {
            diagnosticsSourceFilter = resolvedFilter
        }
    }
    val filteredDiagnostics = remember(detailDiagnostics, diagnosticsSourceFilter) {
        PluginsSettingsSectionSupport.filterPluginDiagnosticEntriesBySource(
            entries = detailDiagnostics,
            filter = diagnosticsSourceFilter,
        )
    }
    val shouldShowDiagnosticsSourceFilteredEmptyState = remember(
        filteredDiagnostics,
        diagnosticsSourceFilter,
    ) {
        PluginsSettingsSectionSupport.shouldShowPluginDiagnosticSourceFilteredEmptyState(
            entries = filteredDiagnostics,
            filter = diagnosticsSourceFilter,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TinaSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(TinaSpacing.xl)
    ) {
        // 插件头部信息
        DetailHeaderCard(
            icon = {
                DetailIconPlaceholder(text = manifest.name)
            },
            title = manifest.name,
            subtitle = "v${manifest.version}",
            actions = {
                // 启用/禁用开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Strings.settings_plugins_enabled),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                }
            }
        )

        // 描述
        manifest.description?.takeIf { it.isNotBlank() }?.let { description ->
            DetailInfoCard(
                title = stringResource(Strings.plugin_marketplace_description)
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 插件详细信息（原对话框内容整合到此处）
        DetailInfoCard(
            title = stringResource(Strings.settings_plugins_details)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.sm)) {
                PluginInfoRow(stringResource(Strings.plugins_details_id, manifest.id))
                PluginInfoRow(stringResource(Strings.plugins_details_version, manifest.version))
                PluginInfoRow(stringResource(Strings.plugins_details_api_version, manifest.apiVersion))
                PluginInfoRow(stringResource(Strings.plugins_details_type, manifest.type))
                PluginInfoRow(
                    stringResource(
                        Strings.plugins_details_enabled,
                        if (plugin.enabled) {
                            stringResource(Strings.plugins_details_enabled_true)
                        } else {
                            stringResource(Strings.plugins_details_enabled_false)
                        }
                    )
                )
                manifest.minAppVersion?.takeIf { it.isNotBlank() }?.let {
                    PluginInfoRow(stringResource(Strings.plugins_details_min_app_version, it))
                }
                manifest.author?.name?.takeIf { it.isNotBlank() }?.let {
                    PluginInfoRow(stringResource(Strings.plugins_details_author, it))
                }
                manifest.repository?.takeIf { it.isNotBlank() }?.let {
                    PluginInfoRow(stringResource(Strings.plugins_details_repository, it))
                }
                manifest.license?.takeIf { it.isNotBlank() }?.let {
                    PluginInfoRow(stringResource(Strings.plugins_details_license, it))
                }
                manifest.permissions?.takeIf { it.isNotEmpty() }?.let {
                    PluginInfoRow(
                        stringResource(
                            Strings.plugins_details_permissions,
                            it.joinToString(", ")
                        )
                    )
                }
                if (grantedPermissions.isNotEmpty()) {
                    PluginInfoRow(
                        stringResource(
                            Strings.plugins_details_granted_permissions,
                            grantedPermissions.joinToString(", ") { permission -> permission.id }
                        )
                    )
                }
                scriptPluginInfo?.let { info ->
                    PluginInfoRow(
                        stringResource(
                            Strings.plugins_details_runtime_state,
                            stringResource(
                                PluginsSettingsSectionSupport.resolveScriptPluginStateLabelRes(
                                    info.state
                                )
                            )
                        )
                    )
                    info.error?.takeIf { it.isNotBlank() }?.let { errorMessage ->
                        PluginInfoRow(
                            stringResource(
                                Strings.plugins_details_runtime_error,
                                errorMessage,
                            )
                        )
                    }
                }
                if (scriptPluginInfo == null && faultRecord != null) {
                    PluginInfoRow(
                        stringResource(
                            Strings.plugins_details_runtime_state,
                            stringResource(Strings.plugins_runtime_state_quarantined),
                        )
                    )
                    PluginInfoRow(
                        stringResource(
                            Strings.plugins_details_runtime_error,
                            faultRecord.message,
                        )
                    )
                }
                faultRecord?.let { fault ->
                    PluginInfoRow(
                        stringResource(
                            Strings.plugins_details_fault_phase,
                            stringResource(
                                PluginsSettingsSectionSupport.resolvePluginFaultPhaseLabelRes(fault.phase)
                            ),
                        )
                    )
                    PluginInfoRow(
                        stringResource(
                            Strings.plugins_details_fault_time,
                            DateFormat.getDateTimeInstance().format(Date(fault.timestampMillis)),
                        )
                    )
                }
                PluginInfoRow(stringResource(Strings.plugins_details_dir, plugin.directory.absolutePath))

                PluginInfoRow(
                    stringResource(
                        Strings.plugins_details_contrib_summary,
                        contributionSummary.themeCount,
                        contributionSummary.fileTreeMenuCount,
                        contributionSummary.editorContextMenuCount,
                        contributionSummary.editorToolbarMenuCount,
                    )
                )
            }
        }

        if (commandContributions.isNotEmpty()) {
            PluginCommandContributionsCard(
                commands = filteredCommandContributions,
                summary = commandContributionSummary,
                filterOptions = commandContributionFilterOptions,
                selectedFilter = commandContributionFilter,
                onFilterSelected = { selectedFilter ->
                    commandContributionFilter = selectedFilter
                },
                isScriptPlugin = isScriptPlugin,
                onActionClick = { action, command ->
                    when (action) {
                        PluginDiagnosticAction.OPEN_LOGS -> onOpenLogs(PluginLogLevel.WARN)
                        PluginDiagnosticAction.RELOAD_PLUGIN -> onReload()
                        PluginDiagnosticAction.SHOW_PERMISSIONS -> {
                            showPluginPermissionsDialog = true
                        }
                        PluginDiagnosticAction.REPAIR_LSP_DEPENDENCIES -> onInstallLspDeps()
                        PluginDiagnosticAction.COPY_DIAGNOSTIC -> {
                            copyPluginDiagnosticToClipboard(
                                context = context,
                                text = PluginsSettingsSectionSupport
                                    .buildPluginCommandContributionClipboardText(
                                        plugin = plugin,
                                        command = command,
                                    ),
                            )
                            Toast.makeText(
                                context,
                                diagnosticCopiedText,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }

        if (optionalPermissions.isNotEmpty()) {
            DetailInfoCard(
                title = stringResource(Strings.plugins_optional_permissions_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.sm)) {
                    Text(
                        text = stringResource(Strings.plugins_optional_permissions_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    optionalPermissions.forEachIndexed { index, permission ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(TinaSpacing.xs),
                            ) {
                                Text(
                                    text = permission.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = stringResource(
                                        when (permission.level) {
                                            PermissionLevel.L0_NO_RISK -> Strings.plugin_permission_no_risk
                                            PermissionLevel.L1_LOW_RISK -> Strings.plugin_permission_low_risk
                                            PermissionLevel.L2_MEDIUM_RISK -> Strings.plugin_permission_medium_risk
                                            PermissionLevel.L3_HIGH_RISK -> Strings.plugin_permission_high_risk
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = permission in grantedPermissions,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        pendingOptionalPermission = permission
                                    } else {
                                        onRevokeOptionalPermission(permission)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (requirementsSummary.hasRequirements) {
            DetailInfoCard(
                title = stringResource(Strings.plugins_details_requirements_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.sm)) {
                    if (requirementsSummary.recommendedToolchains.isNotEmpty()) {
                        PluginInfoRow(
                            stringResource(
                                Strings.plugins_details_requirements_toolchain_recommended,
                                requirementsSummary.recommendedToolchains.joinToString(", ")
                            )
                        )
                    }
                    if (requirementsSummary.optionalToolchains.isNotEmpty()) {
                        PluginInfoRow(
                            stringResource(
                                Strings.plugins_details_requirements_toolchain_optional,
                                requirementsSummary.optionalToolchains.joinToString(", ")
                            )
                        )
                    }
                    if (requirementsSummary.packageGroups.isNotEmpty()) {
                        PluginInfoRow(
                            stringResource(
                                Strings.plugins_details_requirements_packages,
                                requirementsSummary.packageGroups.toPluginRequirementsPackageDisplay()
                            )
                        )
                    }
                    PluginInfoRow(stringResource(Strings.plugins_details_requirements_note))
                }
            }
        }

        if (configurationSummary.hasProperties) {
            PluginConfigurationSettingsCard(
                manifest = manifest,
                configurationSummary = configurationSummary,
            )
        }

        if (isLspPlugin) {
            LspDependencyStatusCard(
                lspPluginInfo = lspPluginInfo,
                lspInstallState = lspInstallState,
                onOpenErrorLogs = { onOpenLogs(PluginLogLevel.ERROR) },
                onInstallLspDeps = onInstallLspDeps,
            )
        }

        if (!isLspPlugin || detailDiagnostics.isNotEmpty()) {
            DetailInfoCard(
                title = stringResource(Strings.settings_plugins_diagnostics)
            ) {
                if (detailDiagnostics.isEmpty()) {
                    Text(
                        text = stringResource(Strings.settings_plugins_diagnostics_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
                        if (availableDiagnosticSourceFilters.size > 2) {
                            PluginDiagnosticSourceFilterRow(
                                filters = availableDiagnosticSourceFilters,
                                selectedFilter = diagnosticsSourceFilter,
                                onFilterSelected = { selectedFilter ->
                                    diagnosticsSourceFilter =
                                        PluginsSettingsSectionSupport.togglePluginDiagnosticSourceFilter(
                                            currentFilter = diagnosticsSourceFilter,
                                            selectedFilter = selectedFilter,
                                        )
                                }
                            )
                        }

                        if (shouldShowDiagnosticsSourceFilteredEmptyState) {
                            PluginDiagnosticFilteredEmptyState()
                        } else {
                            filteredDiagnostics.forEachIndexed { index, entry ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                val issue = entry.issue

                                val severityText = stringResource(
                                    PluginsSettingsSectionSupport.resolvePluginDiagnosticSeverityLabelRes(issue.severity)
                                )
                                val categoryText = stringResource(
                                    PluginsSettingsSectionSupport.resolvePluginDiagnosticCategoryLabelRes(issue.category)
                                )
                                val sourceText = stringResource(
                                    PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceLabelRes(entry.source)
                                )
                                val severityColor = when (issue.severity) {
                                    PluginDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
                                    PluginDiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                    PluginDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.primary
                                }
                                val actions = PluginsSettingsSectionSupport.resolvePluginDiagnosticActions(
                                    entry = entry,
                                    isScriptPlugin = isScriptPlugin,
                                    isInstalled = resolvedDiagnosticsReport.isInstalled,
                                )

                                PluginDiagnosticRow(
                                    severityText = severityText,
                                    categoryText = categoryText,
                                    sourceText = sourceText,
                                    severityColor = severityColor,
                                    issue = issue,
                                    actions = actions,
                                    onActionClick = { action ->
                                        when (action) {
                                            PluginDiagnosticAction.OPEN_LOGS -> {
                                                onOpenLogs(
                                                    PluginsSettingsSectionSupport.resolvePluginDiagnosticPreferredLogLevel(
                                                        entry = entry,
                                                    )
                                                )
                                            }
                                            PluginDiagnosticAction.RELOAD_PLUGIN -> onReload()
                                            PluginDiagnosticAction.SHOW_PERMISSIONS -> {
                                                showPluginPermissionsDialog = true
                                            }
                                            PluginDiagnosticAction.REPAIR_LSP_DEPENDENCIES -> onInstallLspDeps()
                                            PluginDiagnosticAction.COPY_DIAGNOSTIC -> {
                                                copyPluginDiagnosticToClipboard(
                                                    context = context,
                                                    text = PluginsSettingsSectionSupport
                                                        .buildPluginDiagnosticClipboardText(
                                                            report = resolvedDiagnosticsReport,
                                                            entry = entry,
                                                        ),
                                                )
                                                Toast.makeText(
                                                    context,
                                                    diagnosticCopiedText,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 操作按钮
        DetailInfoCard(
            title = stringResource(Strings.settings_plugins_actions)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
                if (isScriptPlugin) {
                    SettingsClickableItem(
                        title = stringResource(Strings.settings_plugins_reload),
                        subtitle = stringResource(Strings.settings_plugins_reload_desc),
                        onClick = onReload
                    )
                }

                SettingsClickableItem(
                    title = stringResource(Strings.settings_plugins_diagnostics_refresh),
                    subtitle = stringResource(Strings.settings_plugins_diagnostics_refresh_desc),
                    value = if (isDoctorRunning) stringResource(Strings.loading) else null,
                    onClick = {
                        if (isDoctorRunning) {
                            return@SettingsClickableItem
                        }
                        scope.launch {
                            try {
                                isDoctorRunning = true
                                doctorDiagnosticsReport = withContext(Dispatchers.IO) {
                                    PluginDoctor.inspectDirectory(
                                        context = context.applicationContext,
                                        pluginDir = plugin.directory,
                                    )
                                }
                            } finally {
                                isDoctorRunning = false
                            }
                        }
                    }
                )

                SettingsClickableItem(
                    title = stringResource(Strings.settings_plugins_logs),
                    subtitle = stringResource(Strings.settings_plugins_logs_desc),
                    onClick = { onOpenLogs(null) }
                )

                // LSP 依赖安装/修复
                if (isLspPlugin && hasToolchains) {
                    val isRepairMode = lspInstallState.requiresLspDependencyRepair()
                    SettingsClickableItem(
                        title = when {
                            isLspReady -> stringResource(Strings.lsp_plugin_deps_ready)
                            isRepairMode -> stringResource(Strings.lsp_plugin_repair_deps)
                            else -> stringResource(Strings.lsp_plugin_install_deps)
                        },
                        subtitle = lspInstallState
                            ?.lastError
                            ?.takeIf(String::isNotBlank)
                            ?: stringResource(
                                if (isRepairMode) {
                                    Strings.lsp_plugin_repair_deps_desc
                                } else {
                                    Strings.lsp_plugin_install_deps_desc
                                }
                            ),
                        onClick = onInstallLspDeps
                    )
                }

                // 主题选择
                if (hasThemes) {
                    SettingsClickableItem(
                        title = stringResource(Strings.settings_plugins_theme),
                        subtitle = stringResource(Strings.settings_plugins_theme_desc),
                        onClick = onSelectTheme
                    )
                }

                // 卸载
                SettingsClickableItem(
                    title = stringResource(Strings.settings_plugins_uninstall),
                    subtitle = stringResource(Strings.settings_plugins_uninstall_desc),
                    onClick = onUninstall
                )
            }
        }
    }

    if (showPluginPermissionsDialog) {
        TinaInfoDialog(
            title = stringResource(Strings.plugins_diagnostics_permissions_dialog_title),
            message = permissionDialogMessage,
            onDismiss = { showPluginPermissionsDialog = false },
        )
    }


    pendingOptionalPermission?.let { permission ->
        PluginPermissionDialog(
            pluginName = manifest.name,
            permissions = setOf(permission),
            onConfirm = {
                pendingOptionalPermission = null
                onGrantOptionalPermission(permission)
            },
            onDeny = { pendingOptionalPermission = null },
            onDismiss = { pendingOptionalPermission = null },
        )
    }
}

