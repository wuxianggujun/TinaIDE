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

private const val TAG = "PluginsSettingsSection"

@Composable
internal fun PluginsSettingsSection(
    pluginManager: PluginManager,
    themeRegistry: EditorThemeIndex,
    lspPluginManager: LspPluginManager? = null,
    installFromFileTrigger: Int = 0, // 当此值变化时触发从文件安装
    selectedPluginId: String? = null,
    onPluginDetailChanged: (String?) -> Unit = {},
    isManageMode: Boolean = false,
    selectedForUninstall: Set<String> = emptySet(),
    onManageModeChanged: (Boolean) -> Unit = {},
    onSelectionChanged: (Set<String>) -> Unit = {},
    batchUninstallTrigger: Int = 0,
    onOpenPluginLogs: (
        pluginId: String,
        pluginName: String,
        initialLevel: PluginLogLevel?,
    ) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val permissionMutationMutex = remember { Mutex() }
    val permissionUpdateFailedTemplate = stringResource(Strings.plugin_permission_update_failed)

    fun updateOptionalPermission(
        logMessage: String,
        update: suspend () -> Result<Unit>,
    ) {
        scope.launch {
            val result = permissionMutationMutex.withLock {
                runCatching { update().getOrThrow() }
            }
            result.onFailure { error ->
                Timber.tag(TAG).e(error, logMessage)
                Toast.makeText(
                    context,
                    String.format(
                        Locale.getDefault(),
                        permissionUpdateFailedTemplate,
                        error.message ?: error.javaClass.simpleName,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val toastPluginsInstalledTemplate = stringResource(Strings.toast_plugins_installed)
    val toastPluginsInstallFailedTemplate = stringResource(Strings.toast_plugins_install_failed)
    val toastPluginsToggleFailedTemplate = stringResource(Strings.toast_plugins_toggle_failed)
    val toastPluginsUninstallFailedTemplate = stringResource(Strings.toast_plugins_uninstall_failed)
    val toastPluginsUninstalledTemplate = stringResource(Strings.toast_plugins_uninstalled)
    val toastPluginsThemeApplied = stringResource(Strings.toast_plugins_theme_applied)
    val toastPluginsPermissionDenied = stringResource(Strings.toast_plugins_permission_denied)
    val toastPluginsReloadSucceededTemplate = stringResource(Strings.toast_plugins_reload_started)
    val toastPluginsReloadFailedTemplate = stringResource(Strings.toast_plugins_reload_failed)
    val diagnosticCopiedText = stringResource(Strings.diagnostic_copied)

    val installedPlugins by pluginManager.pluginsFlow.collectAsState()
    val pluginFaults by pluginManager.pluginFaultsFlow().collectAsState()
    val loadIssues by pluginManager.loadIssuesFlow.collectAsState()
    val pluginHealthReports by pluginManager.pluginHealthReportsFlow.collectAsState()
    val themesIndex by themeRegistry.themesFlow.collectAsState()
    val runtimeFixHint = stringResource(Strings.plugins_diagnostics_runtime_fix_hint)
    val permissionRuntimeFixHint = stringResource(Strings.plugins_diagnostics_permission_runtime_fix_hint)

    val permissionManager = remember { PluginPermissionManager.getInstance(appContext) }
    val permissionGrants by permissionManager.grantsFlow.collectAsState()
    val pluginLogManager = remember { PluginLogManager.getInstance(appContext) }
    val pluginLogs by pluginLogManager.logsFlow.collectAsState()
    val scriptPluginManager = remember { ScriptPluginManager.getInstance(appContext) }
    val scriptPluginStates by scriptPluginManager.pluginStates.collectAsState()
    val commandRegistryRevision by PluginCommandRegistry.stateRevision.collectAsState()
    val lspPlugins by lspPluginManager?.lspPluginsFlow?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val lspInstallStates by lspPluginManager?.installStatesFlow?.collectAsState() ?: remember { mutableStateOf(emptyMap()) }
    val lspRuntimeDiagnosticText = LspRuntimeDiagnosticText(
        missingRequiredToolchainsTemplate = stringResource(
            Strings.plugins_diagnostics_lsp_missing_required_toolchains
        ),
        failedRequiredToolchainsTemplate = stringResource(
            Strings.plugins_diagnostics_lsp_failed_required_toolchains
        ),
        lastErrorTemplate = stringResource(Strings.plugins_diagnostics_lsp_last_error),
        installFixHint = stringResource(Strings.plugins_diagnostics_lsp_install_fix_hint),
        repairFixHint = stringResource(Strings.plugins_diagnostics_lsp_repair_fix_hint),
    )
    val commandRuntimeDiagnosticText = PluginCommandRuntimeDiagnosticText(
        missingRegistrationTemplate = stringResource(
            Strings.plugins_commands_diagnostic_missing_registration
        ),
        missingRegistrationWithReasonTemplate = stringResource(
            Strings.plugins_commands_diagnostic_missing_registration_reason
        ),
        unavailableTemplate = stringResource(Strings.plugins_commands_diagnostic_unavailable),
        unavailableWithoutReasonTemplate = stringResource(
            Strings.plugins_commands_diagnostic_unavailable_unknown
        ),
        executionFailedTemplate = stringResource(
            Strings.plugins_commands_diagnostic_execution_failed
        ),
        runtimeFixHint = stringResource(Strings.plugins_commands_diagnostic_runtime_fix_hint),
        permissionFixHint = stringResource(Strings.plugins_commands_diagnostic_permission_fix_hint),
        missingCommandIdLabel = stringResource(Strings.plugins_commands_missing_command_id_value),
    )
    val lspRuntimeEntriesByPluginId = remember(
        lspPlugins,
        lspInstallStates,
        lspRuntimeDiagnosticText,
    ) {
        PluginsSettingsSectionSupport.buildLspRuntimeEntriesByPluginId(
            lspPlugins = lspPlugins,
            installStates = lspInstallStates,
            diagnosticText = lspRuntimeDiagnosticText,
        )
    }
    val commandRuntimeEntriesByPluginId = remember(
        installedPlugins,
        scriptPluginStates,
        permissionGrants,
        commandRegistryRevision,
        commandRuntimeDiagnosticText,
    ) {
        PluginsSettingsSectionSupport.buildCommandRuntimeEntriesByPluginId(
            installedPlugins = installedPlugins,
            diagnosticText = commandRuntimeDiagnosticText,
        )
    }
    val diagnosticsSnapshot = remember(
        context,
        installedPlugins,
        loadIssues,
        pluginHealthReports,
        pluginLogs,
        scriptPluginStates,
        runtimeFixHint,
        permissionRuntimeFixHint,
        lspRuntimeEntriesByPluginId,
        commandRuntimeEntriesByPluginId,
    ) {
        PluginDiagnosticsSnapshotFactory.create(
            installedPlugins = installedPlugins,
            loadReports = loadIssues.map { it.toDiagnosticsReport(context) },
            healthReports = pluginHealthReports,
            scriptPluginStates = scriptPluginStates,
            runtimeFixHint = runtimeFixHint,
            pluginLogs = pluginLogs,
            permissionRuntimeFixHint = permissionRuntimeFixHint,
            lspRuntimeEntriesByPluginId = lspRuntimeEntriesByPluginId,
            commandRuntimeEntriesByPluginId = commandRuntimeEntriesByPluginId,
        )
    }
    var diagnosticsFilter by remember { mutableStateOf(PluginDiagnosticsFilter.ALL) }
    val severityFilteredInstalledPlugins = remember(
        installedPlugins,
        diagnosticsSnapshot,
        diagnosticsFilter,
    ) {
        PluginsSettingsSectionSupport.filterInstalledPluginsByDiagnostics(
            installedPlugins = installedPlugins,
            snapshot = diagnosticsSnapshot,
            filter = diagnosticsFilter,
        )
    }
    val severityFilteredLoadReports = remember(diagnosticsSnapshot, diagnosticsFilter) {
        PluginsSettingsSectionSupport.filterLoadReportsByDiagnostics(
            snapshot = diagnosticsSnapshot,
            filter = diagnosticsFilter,
        )
    }
    val severityFilteredInstalledReports = remember(
        severityFilteredInstalledPlugins,
        diagnosticsSnapshot,
    ) {
        severityFilteredInstalledPlugins.mapNotNull { plugin ->
            diagnosticsSnapshot.getInstalledReport(plugin.manifest.id)?.let { report ->
                plugin.manifest.id to report
            }
        }.toMap()
    }
    val diagnosticSourceFilterOptions = remember(
        severityFilteredInstalledReports,
        severityFilteredLoadReports,
    ) {
        PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterOptions(
            installedReports = severityFilteredInstalledReports.values,
            loadReports = severityFilteredLoadReports,
        )
    }
    var diagnosticsSourceFilter by remember { mutableStateOf(PluginDiagnosticSourceFilter.ALL) }
    LaunchedEffect(diagnosticSourceFilterOptions, diagnosticsSourceFilter) {
        if (diagnosticSourceFilterOptions.none { it.filter == diagnosticsSourceFilter }) {
            diagnosticsSourceFilter = PluginDiagnosticSourceFilter.ALL
        }
    }
    val filteredInstalledReports = remember(
        severityFilteredInstalledReports,
        diagnosticsSourceFilter,
    ) {
        PluginsSettingsSectionSupport.filterInstalledPluginDiagnosticsReportsBySource(
            reports = severityFilteredInstalledReports,
            filter = diagnosticsSourceFilter,
        )
    }
    val filteredInstalledPlugins = remember(
        severityFilteredInstalledPlugins,
        filteredInstalledReports,
        diagnosticsSourceFilter,
    ) {
        if (diagnosticsSourceFilter == PluginDiagnosticSourceFilter.ALL) {
            severityFilteredInstalledPlugins
        } else {
            severityFilteredInstalledPlugins.filter { plugin ->
                filteredInstalledReports.containsKey(plugin.manifest.id)
            }
        }
    }
    val filteredLoadReports = remember(
        severityFilteredLoadReports,
        diagnosticsSourceFilter,
    ) {
        PluginsSettingsSectionSupport.filterPluginDiagnosticsReportsBySource(
            reports = severityFilteredLoadReports,
            filter = diagnosticsSourceFilter,
        )
    }
    val overviewInstalledReports = remember(
        severityFilteredInstalledReports,
        filteredInstalledReports,
        diagnosticsSourceFilter,
    ) {
        if (diagnosticsSourceFilter == PluginDiagnosticSourceFilter.ALL) {
            severityFilteredInstalledReports.values
        } else {
            filteredInstalledReports.values
        }
    }
    val diagnosticsOverviewSummary = remember(
        overviewInstalledReports,
        filteredLoadReports,
    ) {
        PluginsSettingsSectionSupport.resolvePluginDiagnosticsOverviewSummary(
            installedReports = overviewInstalledReports,
            loadReports = filteredLoadReports,
        )
    }
    val shouldShowDiagnosticsFilteredEmptyState = remember(
        filteredInstalledPlugins,
        filteredLoadReports,
        diagnosticsFilter,
        diagnosticsSourceFilter,
    ) {
        (
            diagnosticsFilter != PluginDiagnosticsFilter.ALL ||
                diagnosticsSourceFilter != PluginDiagnosticSourceFilter.ALL
            ) &&
            filteredInstalledPlugins.isEmpty() &&
            filteredLoadReports.isEmpty()
    }

    var pendingUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }
    var pendingQuarantinedReenable by remember { mutableStateOf<InstalledPlugin?>(null) }
    var pendingBatchUninstall by remember { mutableStateOf(false) }
    var pendingPluginInstall by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var pendingPluginInstallWarning by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var blockedPluginInstall by remember { mutableStateOf<PluginInstallPreview.Blocked?>(null) }
    var selectingThemesForPlugin by remember { mutableStateOf<InstalledPlugin?>(null) }

    fun applyPluginEnabled(plugin: InstalledPlugin, enabled: Boolean) {
        scope.launch {
            pluginManager.setPluginEnabled(plugin.manifest.id, enabled)
                .onFailure { error ->
                    Timber.tag(TAG).w(error, "Toggle plugin failed pluginId=%s enabled=%s", plugin.manifest.id, enabled)
                    pluginLogManager.error(
                        source = PluginHostLogSources.Settings,
                        message = "Toggle failed pluginId=${plugin.manifest.id} enabled=$enabled reason=${error.message.orEmpty()}",
                        stackTrace = error.stackTraceToString(),
                    )
                    Toast.makeText(
                        context,
                        String.format(Locale.getDefault(), toastPluginsToggleFailedTemplate, error.message.orEmpty()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    // LSP 插件相关状态
    val lspPluginRefreshKey = remember(lspPlugins) {
        lspPlugins.joinToString(separator = "|") { plugin -> plugin.pluginId }
    }
    var pendingLspInstall by remember { mutableStateOf<LspPluginInfo?>(null) }
    var installingLspPlugin by remember { mutableStateOf<LspPluginInfo?>(null) }
    var currentInstallProgress by remember { mutableStateOf<LspInstallProgress?>(null) }
    val toastLspInstallSuccess = stringResource(Strings.lsp_plugin_install_success)
    val toastLspInstallFailedTemplate = stringResource(Strings.lsp_plugin_install_failed)

    fun continuePluginInstall(pending: PendingPluginInstall) {
        val isScriptPlugin = pending.manifest.type.equals("script", ignoreCase = true) ||
            pending.manifest.type.equals("hybrid", ignoreCase = true)
        val installPermissions = permissionManager.getRequiredPermissionsForInstall(pending.permissions)

        if (isScriptPlugin && installPermissions.needsUserConfirmation) {
            pendingPluginInstall = pending
        } else {
            scope.launch {
                val outcome = finishPluginInstall(
                    pluginManager = pluginManager,
                    pluginFile = pending.tempFile,
                    expectedManifest = pending.manifest,
                    toastPluginsInstalledTemplate = toastPluginsInstalledTemplate,
                    toastPluginsInstallFailedTemplate = toastPluginsInstallFailedTemplate,
                    permissions = pending.permissions.takeIf { isScriptPlugin }.orEmpty(),
                )
                Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val installLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch {
                    when (val preview = previewPluginInstall(appContext, uri)) {
                        is PluginInstallPreview.Ready -> {
                            val pending = preview.pendingInstall
                            if (pending.hasPreflightWarnings) {
                                pluginLogManager.warn(
                                    PluginHostLogSources.Settings,
                                    PluginsSettingsSectionSupport.buildPluginPreflightLogMessage(
                                        report = pending.diagnosticsReport,
                                        blocked = false,
                                    ),
                                )
                                pendingPluginInstallWarning = pending
                            } else {
                                continuePluginInstall(pending)
                            }
                        }

                        is PluginInstallPreview.Blocked -> {
                            pluginLogManager.error(
                                PluginHostLogSources.Settings,
                                PluginsSettingsSectionSupport.buildPluginPreflightLogMessage(
                                    report = preview.diagnosticsReport,
                                    blocked = true,
                                ),
                            )
                            blockedPluginInstall = preview
                        }

                        is PluginInstallPreview.Failed -> {
                            Toast.makeText(
                                context,
                                String.format(
                                    Locale.getDefault(),
                                    toastPluginsInstallFailedTemplate,
                                    preview.message,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(lspPluginManager, lspPluginRefreshKey) {
        if (lspPluginRefreshKey.isNotBlank()) {
            lspPluginManager?.refreshToolchainInstallStates()
        }
    }

    // 当 installFromFileTrigger 变化时触发文件选择
    LaunchedEffect(installFromFileTrigger) {
        if (installFromFileTrigger > 0) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            installLauncher.launch(intent)
        }
    }

    // 监听批量卸载触发器
    LaunchedEffect(batchUninstallTrigger) {
        if (batchUninstallTrigger > 0 && selectedForUninstall.isNotEmpty()) {
            pendingBatchUninstall = true
        }
    }

    // 处理返回键：优先关闭管理模式，其次关闭详情页面
    TinaBackHandlers(
        tinaBackAction(enabled = isManageMode) {
            onManageModeChanged(false)
        },
        tinaBackAction(enabled = selectedPluginId != null) {
            onPluginDetailChanged(null)
        }
    )

    val detailPlugin = PluginsSettingsSectionSupport.resolveDetailPlugin(
        selectedPluginId = selectedPluginId,
        installedPlugins = installedPlugins,
    )

    LaunchedEffect(selectedPluginId, installedPlugins) {
        if (PluginsSettingsSectionSupport.shouldClosePluginDetails(selectedPluginId, detailPlugin)) {
            pluginLogManager.info(
                PluginHostLogSources.Settings,
                "Closing plugin detail because plugin is no longer installed pluginId=${selectedPluginId.orEmpty()} manager=${pluginManager.instanceId}"
            )
            onPluginDetailChanged(null)
        }
    }

    // 如果选中了插件，显示详情页面；否则显示插件列表
    if (detailPlugin != null) {
        InstalledPluginDetailScreen(
            plugin = detailPlugin,
            currentEditorThemeId = Prefs.editorTheme,
            themesIndex = themesIndex,
            lspPluginInfo = lspPlugins.find { it.pluginId == detailPlugin.manifest.id },
            lspInstallState = lspInstallStates[detailPlugin.manifest.id],
            scriptPluginInfo = scriptPluginStates[detailPlugin.manifest.id],
            faultRecord = pluginFaults[detailPlugin.manifest.id],
            diagnosticsReport = diagnosticsSnapshot.getInstalledReport(detailPlugin.manifest.id),
            initialDiagnosticsSourceFilter = diagnosticsSourceFilter,
            grantedPermissions = permissionGrants[detailPlugin.manifest.id].orEmpty(),
            onGrantOptionalPermission = { permission ->
                updateOptionalPermission("Failed to grant optional plugin permission") {
                    pluginManager.setOptionalPermission(
                        pluginId = detailPlugin.manifest.id,
                        permission = permission,
                        granted = true,
                    )
                }
            },
            onRevokeOptionalPermission = { permission ->
                updateOptionalPermission("Failed to revoke optional plugin permission") {
                    pluginManager.setOptionalPermission(
                        pluginId = detailPlugin.manifest.id,
                        permission = permission,
                        granted = false,
                    )
                }
            },
            onNavigateBack = { onPluginDetailChanged(null) },
            onToggleEnabled = { enabled ->
                if (enabled && pluginFaults.containsKey(detailPlugin.manifest.id)) {
                    pendingQuarantinedReenable = detailPlugin
                } else {
                    applyPluginEnabled(detailPlugin, enabled)
                }
            },
            onSelectTheme = { selectingThemesForPlugin = detailPlugin },
            onUninstall = { pendingUninstall = detailPlugin },
            onReload = {
                scope.launch {
                    scriptPluginManager.reloadPlugin(detailPlugin.manifest.id)
                        .onSuccess {
                            Toast.makeText(
                                context,
                                String.format(
                                    Locale.getDefault(),
                                    toastPluginsReloadSucceededTemplate,
                                    detailPlugin.manifest.name
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .onFailure { t ->
                            Toast.makeText(
                                context,
                                String.format(
                                    Locale.getDefault(),
                                    toastPluginsReloadFailedTemplate,
                                    t.message ?: ""
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            onOpenPluginLogs(
                                detailPlugin.manifest.id,
                                detailPlugin.manifest.name,
                                PluginLogLevel.ERROR,
                            )
                        }
                }
            },
            onOpenLogs = { initialLevel ->
                onOpenPluginLogs(
                    detailPlugin.manifest.id,
                    detailPlugin.manifest.name,
                    initialLevel,
                )
            },
            onInstallLspDeps = {
                lspPlugins.find { it.pluginId == detailPlugin.manifest.id }?.let { lspInfo ->
                    pendingLspInstall = lspInfo
                }
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(TinaSpacing.xl))

            if (installedPlugins.isNotEmpty() || diagnosticsSnapshot.loadReports.isNotEmpty()) {
                PluginDiagnosticsOverviewCard(
                    summary = diagnosticsOverviewSummary,
                    selectedFilter = diagnosticsFilter,
                    sourceFilterOptions = diagnosticSourceFilterOptions,
                    selectedSourceFilter = diagnosticsSourceFilter,
                    onFilterSelected = { selectedFilter ->
                        diagnosticsFilter = PluginsSettingsSectionSupport.togglePluginDiagnosticsFilter(
                            currentFilter = diagnosticsFilter,
                            selectedFilter = selectedFilter,
                        )
                    },
                    onSourceFilterSelected = { selectedSourceFilter ->
                        diagnosticsSourceFilter =
                            PluginsSettingsSectionSupport.togglePluginDiagnosticSourceFilter(
                                currentFilter = diagnosticsSourceFilter,
                                selectedFilter = selectedSourceFilter,
                            )
                    }
                )
                Spacer(modifier = Modifier.height(TinaSpacing.md))
            }

            if (filteredLoadReports.isNotEmpty()) {
                PluginLoadIssuesCard(
                    loadReports = filteredLoadReports,
                    onCopyDiagnostic = { report, entry ->
                        copyPluginDiagnosticToClipboard(
                            context = context,
                            text = PluginsSettingsSectionSupport.buildPluginDiagnosticClipboardText(
                                report = report,
                                entry = entry,
                            ),
                        )
                        Toast.makeText(context, diagnosticCopiedText, Toast.LENGTH_SHORT).show()
                    },
                )
                Spacer(modifier = Modifier.height(TinaSpacing.md))
            }

            // 插件列表
            if (installedPlugins.isEmpty()) {
                EmptyPluginsCard()
            } else if (shouldShowDiagnosticsFilteredEmptyState) {
                PluginDiagnosticsFilteredEmptyCard()
            } else {
                filteredInstalledPlugins.forEach { plugin ->
                    // 查找对应的 LSP 插件信息
                    val lspPluginInfo = lspPlugins.find { it.pluginId == plugin.manifest.id }
                    val lspInstallState = lspInstallStates[plugin.manifest.id]

                    if (isManageMode) {
                        val isSelected = plugin.manifest.id in selectedForUninstall
                        PluginSelectableCard(
                            plugin = plugin,
                            isSelected = isSelected,
                            onToggle = {
                                val newSet = PluginsSettingsSectionSupport.toggleSelectedPlugin(
                                    selectedIds = selectedForUninstall,
                                    pluginId = plugin.manifest.id,
                                )
                                onSelectionChanged(newSet)
                            }
                        )
                    } else {
                        PluginCard(
                            plugin = plugin,
                            currentEditorThemeId = Prefs.editorTheme,
                            themesIndex = themesIndex,
                            lspPluginInfo = lspPluginInfo,
                            lspInstallState = lspInstallState,
                            diagnosticsReport = filteredInstalledReports[plugin.manifest.id],
                            onClick = { onPluginDetailChanged(plugin.manifest.id) }
                        )
                    }
                    Spacer(modifier = Modifier.height(TinaSpacing.md))
                }
            }

            Spacer(modifier = Modifier.height(TinaSpacing.xl))
        }
    }

    pendingQuarantinedReenable?.let { plugin ->
        TinaConfirmDialog(
            title = stringResource(Strings.dialog_title_plugins_reenable_quarantined),
            message = stringResource(Strings.dialog_msg_plugins_reenable_quarantined, plugin.manifest.name),
            confirmText = stringResource(Strings.btn_plugins_reenable),
            dismissText = stringResource(Strings.btn_cancel),
            onConfirm = {
                pendingQuarantinedReenable = null
                applyPluginEnabled(plugin, true)
            },
            onDismiss = { pendingQuarantinedReenable = null },
            isDanger = true,
        )
    }

    // 卸载确认对话框
    pendingUninstall?.let { plugin ->
        if (plugin.manifest.isBundled) {
            // 内置插件不允许卸载
            TinaInfoDialog(
                title = stringResource(Strings.dialog_title_plugins_uninstall_bundled),
                message = stringResource(Strings.dialog_msg_plugins_uninstall_bundled),
                confirmText = stringResource(Strings.btn_confirm),
                onDismiss = { pendingUninstall = null }
            )
        } else {
            TinaConfirmDialog(
                title = stringResource(Strings.dialog_title_plugins_uninstall),
                message = stringResource(Strings.dialog_msg_plugins_uninstall, plugin.manifest.name),
                confirmText = stringResource(Strings.btn_confirm),
                dismissText = stringResource(Strings.btn_cancel),
                onConfirm = {
                    scope.launch {
                        Timber.tag(TAG).i(
                            "Uninstall plugin requested pluginId=%s manager=%s",
                            plugin.manifest.id,
                            pluginManager.instanceId
                        )
                        pluginLogManager.info(
                            PluginHostLogSources.Settings,
                            "Uninstall requested pluginId=${plugin.manifest.id} manager=${pluginManager.instanceId}"
                        )
                        pluginManager.uninstallPlugin(plugin.manifest.id)
                            .onFailure { t ->
                                Timber.tag(TAG).w(
                                    t,
                                    "Uninstall plugin failed pluginId=%s manager=%s",
                                    plugin.manifest.id,
                                    pluginManager.instanceId
                                )
                                pluginLogManager.error(
                                    source = PluginHostLogSources.Settings,
                                    message = "Uninstall failed pluginId=${plugin.manifest.id} manager=${pluginManager.instanceId} reason=${t.message.orEmpty()}",
                                    stackTrace = t.stackTraceToString()
                                )
                                Toast.makeText(
                                    context,
                                    String.format(Locale.getDefault(), toastPluginsUninstallFailedTemplate, t.message ?: ""),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .onSuccess {
                                Timber.tag(TAG).i(
                                    "Uninstall plugin applied pluginId=%s manager=%s",
                                    plugin.manifest.id,
                                    pluginManager.instanceId
                                )
                                pluginLogManager.info(
                                    PluginHostLogSources.Settings,
                                    "Uninstall applied pluginId=${plugin.manifest.id} manager=${pluginManager.instanceId}"
                                )
                                if (selectedPluginId == plugin.manifest.id) {
                                    onPluginDetailChanged(null)
                                }
                                Toast.makeText(
                                    context,
                                    String.format(Locale.getDefault(), toastPluginsUninstalledTemplate, plugin.manifest.name),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        pendingUninstall = null
                    }
                },
                onDismiss = { pendingUninstall = null },
                isDanger = true
            )
        }
    }

    // 批量卸载确认对话框
    if (pendingBatchUninstall && selectedForUninstall.isNotEmpty()) {
        when (
            val spec = PluginsSettingsSectionSupport.resolveBatchUninstallSpec(
                installedPlugins = installedPlugins,
                selectedIds = selectedForUninstall,
            )
        ) {
            PluginsBatchUninstallSpec.BundledOnly -> {
                TinaInfoDialog(
                    title = stringResource(Strings.dialog_title_plugins_uninstall_bundled),
                    message = stringResource(Strings.dialog_msg_plugins_batch_uninstall_bundled),
                    confirmText = stringResource(Strings.btn_confirm),
                    onDismiss = {
                        pendingBatchUninstall = false
                        onSelectionChanged(emptySet())
                    }
                )
            }
            is PluginsBatchUninstallSpec.Confirm -> {
                TinaConfirmDialog(
                    title = stringResource(Strings.dialog_title_plugins_batch_uninstall),
                    message = stringResource(
                        Strings.dialog_msg_plugins_batch_uninstall,
                        spec.pluginIds.size,
                        spec.pluginNames,
                    ),
                    confirmText = stringResource(Strings.btn_confirm),
                    dismissText = stringResource(Strings.btn_cancel),
                    onConfirm = {
                        pendingBatchUninstall = false
                        scope.launch {
                            Timber.tag(TAG).i(
                                "Batch uninstall requested pluginIds=%s manager=%s",
                                spec.pluginIds.joinToString(","),
                                pluginManager.instanceId
                            )
                            pluginLogManager.info(
                                PluginHostLogSources.Settings,
                                "Batch uninstall requested pluginIds=${spec.pluginIds.joinToString(",")} manager=${pluginManager.instanceId}"
                            )
                            for (id in spec.pluginIds) {
                                pluginManager.uninstallPlugin(id)
                                    .onFailure { t ->
                                        Timber.tag(TAG).w(
                                            t,
                                            "Batch uninstall failed pluginId=%s manager=%s",
                                            id,
                                            pluginManager.instanceId
                                        )
                                        pluginLogManager.error(
                                            source = PluginHostLogSources.Settings,
                                            message = "Batch uninstall failed pluginId=$id manager=${pluginManager.instanceId} reason=${t.message.orEmpty()}",
                                            stackTrace = t.stackTraceToString()
                                        )
                                        Toast.makeText(
                                            context,
                                            String.format(
                                                Locale.getDefault(),
                                                toastPluginsUninstallFailedTemplate,
                                                t.message ?: "",
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .onSuccess {
                                        Timber.tag(TAG).i(
                                            "Batch uninstall applied pluginId=%s manager=%s",
                                            id,
                                            pluginManager.instanceId
                                        )
                                        pluginLogManager.info(
                                            PluginHostLogSources.Settings,
                                            "Batch uninstall applied pluginId=$id manager=${pluginManager.instanceId}"
                                        )
                                    }
                            }
                            onSelectionChanged(emptySet())
                            onManageModeChanged(false)
                        }
                    },
                    onDismiss = { pendingBatchUninstall = false },
                    isDanger = true
                )
            }
        }
    }

    blockedPluginInstall?.let { blocked ->
        PluginInstallPreflightDialog(
            report = blocked.diagnosticsReport,
            isBlocking = true,
            onCopyDiagnostic = {
                copyPluginDiagnosticToClipboard(
                    context = context,
                    text = PluginsSettingsSectionSupport.buildPluginDiagnosticsClipboardText(
                        blocked.diagnosticsReport
                    ),
                )
                Toast.makeText(context, diagnosticCopiedText, Toast.LENGTH_SHORT).show()
            },
            onConfirm = {
                blockedPluginInstall = null
                runCatching { blocked.tempFile.delete() }
            },
            onDismiss = {
                blockedPluginInstall = null
                runCatching { blocked.tempFile.delete() }
            },
        )
    }

    pendingPluginInstallWarning?.let { pending ->
        PluginInstallPreflightDialog(
            report = pending.diagnosticsReport,
            isBlocking = false,
            onCopyDiagnostic = {
                copyPluginDiagnosticToClipboard(
                    context = context,
                    text = PluginsSettingsSectionSupport.buildPluginDiagnosticsClipboardText(
                        pending.diagnosticsReport
                    ),
                )
                Toast.makeText(context, diagnosticCopiedText, Toast.LENGTH_SHORT).show()
            },
            onConfirm = {
                pendingPluginInstallWarning = null
                continuePluginInstall(pending)
            },
            onDismiss = {
                pendingPluginInstallWarning = null
                runCatching { pending.tempFile.delete() }
            },
        )
    }

    // 安装脚本插件权限对话框（script/hybrid）
    pendingPluginInstall?.let { pending ->
        PluginPermissionDialog(
            pluginName = pending.manifest.name,
            permissions = pending.permissions,
            onConfirm = {
                pendingPluginInstall = null
                scope.launch {
                    val outcome = finishPluginInstall(
                        pluginManager = pluginManager,
                        pluginFile = pending.tempFile,
                        expectedManifest = pending.manifest,
                        toastPluginsInstalledTemplate = toastPluginsInstalledTemplate,
                        toastPluginsInstallFailedTemplate = toastPluginsInstallFailedTemplate,
                        permissions = pending.permissions,
                    )
                    Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
                }
            },
            onDeny = {
                pendingPluginInstall = null
                runCatching { pending.tempFile.delete() }
                Toast.makeText(context, toastPluginsPermissionDenied, Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                pendingPluginInstall = null
                runCatching { pending.tempFile.delete() }
            }
        )
    }

    // 主题选择对话框
    selectingThemesForPlugin?.let { plugin ->
        val themeOptions = PluginsSettingsSectionSupport.buildPluginThemeOptions(
            plugin = plugin,
            themesIndex = themesIndex,
        )

        if (themeOptions.isNotEmpty()) {
            TinaSingleChoiceDialog(
                title = stringResource(Strings.dialog_title_plugins_select_theme),
                options = themeOptions,
                selectedValue = Prefs.editorTheme,
                onSelected = { value ->
                    Prefs.setEditorTheme(value)
                    Toast.makeText(
                        context,
                        toastPluginsThemeApplied,
                        Toast.LENGTH_SHORT
                    ).show()
                    selectingThemesForPlugin = null
                },
                onDismiss = { selectingThemesForPlugin = null }
            )
        } else {
            selectingThemesForPlugin = null
        }
    }

    // LSP 工具链安装确认对话框
    pendingLspInstall?.let { lspPlugin ->
        val installState = lspInstallStates[lspPlugin.pluginId]
        val isRepairMode = installState.requiresLspDependencyRepair()
        LspToolchainConfirmDialog(
            pluginName = lspPlugin.pluginName,
            toolchains = lspPlugin.toolchainConfigs,
            toolchainStates = installState?.toolchainStates ?: emptyMap(),
            environmentStatus = lspPluginManager?.inspectToolchainEnvironment(),
            isRepairMode = isRepairMode,
            onConfirm = {
                pendingLspInstall = null
                installingLspPlugin = lspPlugin
                scope.launch {
                    lspPluginManager?.installToolchains(lspPlugin.pluginId) { progress ->
                        currentInstallProgress = progress
                    }?.onSuccess {
                        Toast.makeText(context, toastLspInstallSuccess, Toast.LENGTH_SHORT).show()
                        installingLspPlugin = null
                        currentInstallProgress = null
                    }?.onFailure { e ->
                        Toast.makeText(
                            context,
                            String.format(Locale.getDefault(), toastLspInstallFailedTemplate, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                        installingLspPlugin = null
                        currentInstallProgress = null
                    }
                }
            },
            onDismiss = { pendingLspInstall = null }
        )
    }

    // LSP 工具链安装进度对话框
    installingLspPlugin?.let { lspPlugin ->
        val installState = lspInstallStates[lspPlugin.pluginId]
        LspToolchainProgressDialog(
            pluginName = lspPlugin.pluginName,
            toolchains = lspPlugin.toolchainConfigs,
            toolchainStates = installState?.toolchainStates ?: emptyMap(),
            currentProgress = currentInstallProgress,
            onDismiss = null // 安装中不允许关闭
        )
    }
}

@Composable
private fun EmptyPluginsCard() {
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
private fun PluginDiagnosticsOverviewCard(
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
private fun PluginDiagnosticsOverviewMetric(
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
private fun PluginDiagnosticsFilteredEmptyCard() {
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
private fun PluginDiagnosticsOverviewSourceFilterRow(
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
private fun PluginCard(
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
private fun PluginSelectableCard(
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
private fun PluginActionButton(
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
private fun PluginInstallPreflightDialog(
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

