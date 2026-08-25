package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginCommand
import com.wuxianggujun.tinaide.plugin.PluginConfigurationSchema
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticCategory
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticEntry
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticIssue
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSeverity
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSource
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsReport
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsSnapshot
import com.wuxianggujun.tinaide.plugin.PluginFaultPhase
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.PluginMenuItem
import com.wuxianggujun.tinaide.plugin.ResolvedPluginCommandSource
import com.wuxianggujun.tinaide.plugin.ResolvedPluginCommandSurface
import com.wuxianggujun.tinaide.plugin.ResolvedPluginConfigurationProperty
import com.wuxianggujun.tinaide.plugin.ThemeConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInfo
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInstallState
import com.wuxianggujun.tinaide.plugin.lsp.LspToolchainConfig
import com.wuxianggujun.tinaide.plugin.lsp.ToolchainInstallState
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginState
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandAvailability
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandExecutionIssue
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistrationIssue
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import java.util.Locale

internal data class PluginsContributionSummary(
    val themeCount: Int,
    val fileTreeMenuCount: Int,
    val editorContextMenuCount: Int,
    val editorToolbarMenuCount: Int = 0,
)

internal data class PluginsCommandContribution(
    val surface: ResolvedPluginCommandSurface,
    val commandId: String,
    val title: String,
    val group: String,
    val source: ResolvedPluginCommandSource?,
    val status: PluginCommandContributionStatus,
    val whenExpression: String?,
    val statusMessage: String? = null,
)

internal data class PluginsCommandContributionSummary(
    val totalCount: Int,
    val availableCount: Int,
    val issueCount: Int,
)

internal data class PluginCommandContributionFilterOption(
    val filter: PluginCommandContributionFilter,
    val count: Int,
)

internal enum class PluginCommandContributionFilter {
    ALL,
    ISSUES,
    AVAILABLE,
}

internal enum class PluginCommandContributionStatus {
    AVAILABLE,
    MISSING_COMMAND_ID,
    MISSING_COMMAND_DECLARATION,
    MISSING_RUNTIME_REGISTRATION,
    UNAVAILABLE,
    EXECUTION_FAILED,
}

internal data class PluginsRequirementsSummary(
    val recommendedToolchains: List<String>,
    val optionalToolchains: List<String>,
    val packageGroups: List<PluginsPackageRequirementGroup>,
) {
    val hasRequirements: Boolean
        get() = recommendedToolchains.isNotEmpty() ||
            optionalToolchains.isNotEmpty() ||
            packageGroups.isNotEmpty()
}

internal data class PluginsPackageRequirementGroup(
    val manager: String,
    val packages: List<String>,
)

internal data class PluginsConfigurationSummary(
    val title: String?,
    val properties: List<ResolvedPluginConfigurationProperty>,
) {
    val hasProperties: Boolean
        get() = properties.isNotEmpty()
}

internal data class PluginDiagnosticsSummary(
    val totalCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val highestSeverity: PluginDiagnosticSeverity?,
)

internal data class PluginDiagnosticCategoryGroup(
    val category: PluginDiagnosticCategory,
    val entries: List<PluginDiagnosticEntry>,
    val errorCount: Int,
    val warningCount: Int,
)

internal data class PluginDiagnosticsOverviewSummary(
    val installedCount: Int,
    val issuePluginCount: Int,
    val errorPluginCount: Int,
    val warningPluginCount: Int,
    val lspIssuePluginCount: Int,
    val notLoadedCount: Int,
)

internal data class PluginDiagnosticSourceFilterOption(
    val filter: PluginDiagnosticSourceFilter,
    val count: Int,
)

internal enum class PluginDiagnosticsFilter {
    ALL,
    ERROR,
    WARNING,
    NOT_LOADED,
    LSP,
}

internal enum class PluginDiagnosticSourceFilter {
    ALL,
    LOAD,
    HEALTH,
    RUNTIME,
}

internal data class LspRuntimeDiagnosticText(
    val missingRequiredToolchainsTemplate: String,
    val failedRequiredToolchainsTemplate: String,
    val lastErrorTemplate: String,
    val installFixHint: String,
    val repairFixHint: String,
)

internal data class PluginCommandRuntimeDiagnosticText(
    val missingRegistrationTemplate: String,
    val missingRegistrationWithReasonTemplate: String,
    val unavailableTemplate: String,
    val unavailableWithoutReasonTemplate: String,
    val executionFailedTemplate: String,
    val runtimeFixHint: String,
    val permissionFixHint: String,
    val missingCommandIdLabel: String,
)

internal enum class PluginDiagnosticAction {
    OPEN_LOGS,
    RELOAD_PLUGIN,
    SHOW_PERMISSIONS,
    REPAIR_LSP_DEPENDENCIES,
    COPY_DIAGNOSTIC,
}

internal sealed interface PluginsBatchUninstallSpec {
    data object BundledOnly : PluginsBatchUninstallSpec

    data class Confirm(
        val pluginIds: List<String>,
        val pluginNames: String,
    ) : PluginsBatchUninstallSpec
}

internal object PluginsSettingsSectionSupport {

    private const val DEFAULT_PLUGIN_COMMAND_GROUP = "9_plugin"

    fun toggleSelectedPlugin(
        selectedIds: Set<String>,
        pluginId: String,
    ): Set<String> = if (pluginId in selectedIds) {
        selectedIds - pluginId
    } else {
        selectedIds + pluginId
    }

    fun buildPluginThemeIds(
        pluginId: String,
        relativePaths: List<String>,
        themesIndex: Map<String, ThemeConfig>,
    ): List<String> = relativePaths.map { relativePath ->
        "plugin:$pluginId/${relativePath.replace('\\', '/')}"
    }.filter { themeId ->
        themesIndex.containsKey(themeId)
    }

    fun buildPluginThemeOptions(
        plugin: InstalledPlugin,
        themesIndex: Map<String, ThemeConfig>,
    ): List<Pair<String, String>> {
        return buildPluginThemeIds(
            pluginId = plugin.manifest.id,
            relativePaths = plugin.manifest.contributions?.themes.orEmpty(),
            themesIndex = themesIndex,
        ).mapNotNull { themeId ->
            val themeName = themesIndex[themeId]?.name ?: return@mapNotNull null
            themeId to themeName
        }
    }

    fun resolveDetailPlugin(
        selectedPluginId: String?,
        installedPlugins: List<InstalledPlugin>,
    ): InstalledPlugin? = selectedPluginId?.let { pluginId ->
        installedPlugins.find { plugin -> plugin.manifest.id == pluginId }
    }

    fun shouldClosePluginDetails(
        selectedPluginId: String?,
        detailPlugin: InstalledPlugin?,
    ): Boolean = selectedPluginId != null && detailPlugin == null

    fun resolvePluginInitial(name: String): String = name.firstOrNull()?.uppercase() ?: "P"

    @StringRes
    fun resolveLspStatusLabelRes(isReady: Boolean): Int = if (isReady) {
        Strings.lsp_plugin_status_ready
    } else {
        Strings.lsp_plugin_status_not_ready
    }

    fun buildLspRuntimeEntriesByPluginId(
        lspPlugins: List<LspPluginInfo>,
        installStates: Map<String, LspPluginInstallState>,
        diagnosticText: LspRuntimeDiagnosticText,
    ): Map<String, List<PluginDiagnosticEntry>> = lspPlugins.mapNotNull { lspPlugin ->
        val entries = buildLspRuntimeEntries(
            lspPlugin = lspPlugin,
            installState = installStates[lspPlugin.pluginId],
            diagnosticText = diagnosticText,
        )
        if (entries.isEmpty()) {
            null
        } else {
            lspPlugin.pluginId to entries
        }
    }.toMap()

    private fun buildLspRuntimeEntries(
        lspPlugin: LspPluginInfo,
        installState: LspPluginInstallState?,
        diagnosticText: LspRuntimeDiagnosticText,
    ): List<PluginDiagnosticEntry> {
        val requiredToolchains = lspPlugin.toolchainConfigs.filter { toolchain -> toolchain.required }
        val missingRequiredToolchains = requiredToolchains.filter { toolchain ->
            val state = installState?.toolchainStates?.get(toolchain.id)
            state == null || state == ToolchainInstallState.NOT_INSTALLED
        }
        val failedRequiredToolchains = requiredToolchains.filter { toolchain ->
            installState?.toolchainStates?.get(toolchain.id) == ToolchainInstallState.FAILED
        }
        val lastError = installState?.lastError?.takeIf { error -> error.isNotBlank() }

        return buildList {
            if (missingRequiredToolchains.isNotEmpty()) {
                add(
                    buildLspRuntimeDiagnosticEntry(
                        severity = PluginDiagnosticSeverity.WARNING,
                        message = formatLspDiagnosticText(
                            diagnosticText.missingRequiredToolchainsTemplate,
                            missingRequiredToolchains.toToolchainDisplayNames(),
                        ),
                        fixHint = diagnosticText.installFixHint,
                    )
                )
            }
            if (failedRequiredToolchains.isNotEmpty()) {
                add(
                    buildLspRuntimeDiagnosticEntry(
                        severity = PluginDiagnosticSeverity.ERROR,
                        message = formatLspDiagnosticText(
                            diagnosticText.failedRequiredToolchainsTemplate,
                            failedRequiredToolchains.toToolchainDisplayNames(),
                        ),
                        fixHint = diagnosticText.repairFixHint,
                    )
                )
            }
            lastError?.let { error ->
                add(
                    buildLspRuntimeDiagnosticEntry(
                        severity = PluginDiagnosticSeverity.ERROR,
                        message = formatLspDiagnosticText(diagnosticText.lastErrorTemplate, error),
                        fixHint = diagnosticText.repairFixHint,
                    )
                )
            }
        }
    }

    private fun buildLspRuntimeDiagnosticEntry(
        severity: PluginDiagnosticSeverity,
        message: String,
        fixHint: String,
    ): PluginDiagnosticEntry = PluginDiagnosticEntry(
        source = PluginDiagnosticSource.RUNTIME,
        issue = PluginDiagnosticIssue(
            severity = severity,
            category = PluginDiagnosticCategory.LSP,
            message = message,
            fixHint = fixHint,
        ),
    )

    fun buildCommandRuntimeEntriesByPluginId(
        installedPlugins: List<InstalledPlugin>,
        diagnosticText: PluginCommandRuntimeDiagnosticText,
    ): Map<String, List<PluginDiagnosticEntry>> = installedPlugins.mapNotNull { plugin ->
        if (!plugin.enabled) {
            return@mapNotNull null
        }
        val entries = buildCommandRuntimeEntries(
            commands = resolveCommandContributions(plugin.manifest),
            diagnosticText = diagnosticText,
        )
        if (entries.isEmpty()) {
            null
        } else {
            plugin.manifest.id to entries
        }
    }.toMap()

    fun buildCommandRuntimeEntries(
        commands: List<PluginsCommandContribution>,
        diagnosticText: PluginCommandRuntimeDiagnosticText,
    ): List<PluginDiagnosticEntry> = commands.mapNotNull { command ->
        when (command.status) {
            PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION -> {
                val reason = command.statusMessage?.takeIf { message -> message.isNotBlank() }
                buildCommandRuntimeDiagnosticEntry(
                    severity = PluginDiagnosticSeverity.WARNING,
                    category = PluginDiagnosticCategory.RUNTIME,
                    message = if (reason == null) {
                        formatLspDiagnosticText(
                            diagnosticText.missingRegistrationTemplate,
                            command.commandDisplayName(diagnosticText),
                        )
                    } else {
                        formatLspDiagnosticText(
                            diagnosticText.missingRegistrationWithReasonTemplate,
                            command.commandDisplayName(diagnosticText),
                            reason,
                        )
                    },
                    fixHint = diagnosticText.runtimeFixHint,
                )
            }
            PluginCommandContributionStatus.UNAVAILABLE -> {
                val reason = command.statusMessage?.takeIf { message -> message.isNotBlank() }
                val category = if (reason == null) {
                    PluginDiagnosticCategory.RUNTIME
                } else {
                    PluginDiagnosticCategory.PERMISSIONS
                }
                val message = if (reason == null) {
                    formatLspDiagnosticText(
                        diagnosticText.unavailableWithoutReasonTemplate,
                        command.commandDisplayName(diagnosticText),
                    )
                } else {
                    formatLspDiagnosticText(
                        diagnosticText.unavailableTemplate,
                        command.commandDisplayName(diagnosticText),
                        reason,
                    )
                }
                buildCommandRuntimeDiagnosticEntry(
                    severity = PluginDiagnosticSeverity.WARNING,
                    category = category,
                    message = message,
                    fixHint = if (category == PluginDiagnosticCategory.PERMISSIONS) {
                        diagnosticText.permissionFixHint
                    } else {
                        diagnosticText.runtimeFixHint
                    },
                )
            }
            PluginCommandContributionStatus.EXECUTION_FAILED -> {
                val reason = command.statusMessage.orEmpty()
                buildCommandRuntimeDiagnosticEntry(
                    severity = PluginDiagnosticSeverity.ERROR,
                    category = PluginDiagnosticCategory.RUNTIME,
                    message = formatLspDiagnosticText(
                        diagnosticText.executionFailedTemplate,
                        command.commandDisplayName(diagnosticText),
                        reason,
                    ),
                    fixHint = diagnosticText.runtimeFixHint,
                )
            }
            PluginCommandContributionStatus.AVAILABLE,
            PluginCommandContributionStatus.MISSING_COMMAND_ID,
            PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION -> null
        }
    }

    private fun buildCommandRuntimeDiagnosticEntry(
        severity: PluginDiagnosticSeverity,
        category: PluginDiagnosticCategory,
        message: String,
        fixHint: String,
    ): PluginDiagnosticEntry = PluginDiagnosticEntry(
        source = PluginDiagnosticSource.RUNTIME,
        issue = PluginDiagnosticIssue(
            severity = severity,
            category = category,
            message = message,
            fixHint = fixHint,
        ),
    )

    private fun PluginsCommandContribution.commandDisplayName(
        diagnosticText: PluginCommandRuntimeDiagnosticText,
    ): String = commandId
        .takeIf { it.isNotBlank() }
        ?: title.takeIf { it.isNotBlank() }
        ?: diagnosticText.missingCommandIdLabel

    private fun List<LspToolchainConfig>.toToolchainDisplayNames(): String = joinToString(separator = ", ") { toolchain ->
        toolchain.name.takeIf { name -> name.isNotBlank() } ?: toolchain.id
    }

    private fun formatLspDiagnosticText(template: String, vararg values: String): String = String.format(Locale.getDefault(), template, *values)

    private fun normalizeRequirementItems(items: List<String>?): List<String> = items.orEmpty()
        .asSequence()
        .map { item -> item.trim() }
        .filter { item -> item.isNotBlank() }
        .distinct()
        .sorted()
        .toList()

    @StringRes
    fun resolveScriptPluginStateLabelRes(state: ScriptPluginState): Int = when (state) {
        ScriptPluginState.UNLOADED -> Strings.plugins_runtime_state_unloaded
        ScriptPluginState.LOADING -> Strings.plugins_runtime_state_loading
        ScriptPluginState.ACTIVE -> Strings.plugins_runtime_state_active
        ScriptPluginState.ERROR -> Strings.plugins_runtime_state_error
        ScriptPluginState.DISABLED -> Strings.plugins_runtime_state_disabled
        ScriptPluginState.WAITING_PERMISSION -> Strings.plugins_runtime_state_waiting_permission
        ScriptPluginState.QUARANTINED -> Strings.plugins_runtime_state_quarantined
        ScriptPluginState.RUNTIME_UNAVAILABLE -> Strings.plugins_runtime_state_unavailable
    }

    @StringRes
    fun resolvePluginFaultPhaseLabelRes(phase: PluginFaultPhase): Int = when (phase) {
        PluginFaultPhase.STARTUP -> Strings.plugins_fault_phase_startup
        PluginFaultPhase.COMMAND -> Strings.plugins_fault_phase_command
        PluginFaultPhase.EVENT -> Strings.plugins_fault_phase_event
        PluginFaultPhase.API_CALL -> Strings.plugins_fault_phase_api
        PluginFaultPhase.LSP -> Strings.plugins_fault_phase_lsp
        PluginFaultPhase.CONTRIBUTION -> Strings.plugins_fault_phase_contribution
        PluginFaultPhase.UNKNOWN -> Strings.plugins_fault_phase_unknown
    }

    fun isScriptPlugin(manifest: PluginManifest): Boolean = manifest.type.equals("script", ignoreCase = true) ||
        manifest.type.equals("hybrid", ignoreCase = true)

    fun resolveBatchUninstallSpec(
        installedPlugins: List<InstalledPlugin>,
        selectedIds: Set<String>,
    ): PluginsBatchUninstallSpec {
        val pluginsToUninstall = installedPlugins.filter { plugin ->
            plugin.manifest.id in selectedIds && !plugin.manifest.isBundled
        }
        return if (pluginsToUninstall.isEmpty()) {
            PluginsBatchUninstallSpec.BundledOnly
        } else {
            PluginsBatchUninstallSpec.Confirm(
                pluginIds = pluginsToUninstall.map { it.manifest.id },
                pluginNames = pluginsToUninstall.joinToString("\n") { plugin ->
                    "• ${plugin.manifest.name}"
                },
            )
        }
    }

    fun resolveInstallSourceFileName(lastPathSegment: String?): String {
        val sourceName = lastPathSegment
            ?.trim()
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: return "plugin.tinaplug"
        return sourceName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { name -> name.isNotBlank() && name.any { it.isLetterOrDigit() } }
            ?: "plugin.tinaplug"
    }

    fun buildTempInstallFileName(timestampMillis: Long, fileName: String): String = "install_${timestampMillis}_${resolveInstallSourceFileName(fileName)}"

    fun resolveContributionSummary(manifest: PluginManifest): PluginsContributionSummary = PluginsContributionSummary(
        themeCount = manifest.contributions?.themes?.size ?: 0,
        fileTreeMenuCount = manifest.contributions?.menus?.fileTreeContext?.size ?: 0,
        editorContextMenuCount = manifest.contributions?.menus?.editorContext?.size ?: 0,
        editorToolbarMenuCount = manifest.contributions?.menus?.editorToolbar?.size ?: 0,
    )

    fun resolveCommandContributions(
        manifest: PluginManifest,
        checkRuntimeAvailability: Boolean = true,
        isPluginCommandRegistered: (
            commandId: String,
            pluginId: String,
        ) -> Boolean = { commandId, pluginId -> PluginCommandRegistry.isRegistered(commandId, pluginId) },
        pluginCommandAvailability: (
            commandId: String,
            pluginId: String,
        ) -> PluginCommandAvailability = { commandId, pluginId ->
            PluginCommandRegistry.availability(commandId, pluginId)
        },
        pluginCommandRegistrationIssue: (
            commandId: String,
            pluginId: String,
        ) -> PluginCommandRegistrationIssue? = { commandId, pluginId ->
            PluginCommandRegistry.registrationIssue(commandId, pluginId)
        },
        pluginCommandExecutionIssue: (
            commandId: String,
            pluginId: String,
        ) -> PluginCommandExecutionIssue? = { commandId, pluginId ->
            PluginCommandRegistry.executionIssue(commandId, pluginId)
        },
    ): List<PluginsCommandContribution> {
        val contributions = manifest.contributions ?: return emptyList()
        val declaredCommands = contributions.commands.orEmpty()
            .associateBy { command -> command.id.trim() }
        return buildList {
            appendCommandContributions(
                pluginId = manifest.id,
                surface = ResolvedPluginCommandSurface.EDITOR_CONTEXT,
                menuItems = contributions.menus?.editorContext.orEmpty(),
                declaredCommands = declaredCommands,
                checkRuntimeAvailability = checkRuntimeAvailability,
                isPluginCommandRegistered = isPluginCommandRegistered,
                pluginCommandAvailability = pluginCommandAvailability,
                pluginCommandRegistrationIssue = pluginCommandRegistrationIssue,
                pluginCommandExecutionIssue = pluginCommandExecutionIssue,
            )
            appendCommandContributions(
                pluginId = manifest.id,
                surface = ResolvedPluginCommandSurface.EDITOR_TOOLBAR,
                menuItems = contributions.menus?.editorToolbar.orEmpty(),
                declaredCommands = declaredCommands,
                checkRuntimeAvailability = checkRuntimeAvailability,
                isPluginCommandRegistered = isPluginCommandRegistered,
                pluginCommandAvailability = pluginCommandAvailability,
                pluginCommandRegistrationIssue = pluginCommandRegistrationIssue,
                pluginCommandExecutionIssue = pluginCommandExecutionIssue,
            )
            appendCommandContributions(
                pluginId = manifest.id,
                surface = ResolvedPluginCommandSurface.FILE_TREE_CONTEXT,
                menuItems = contributions.menus?.fileTreeContext.orEmpty(),
                declaredCommands = declaredCommands,
                checkRuntimeAvailability = checkRuntimeAvailability,
                isPluginCommandRegistered = isPluginCommandRegistered,
                pluginCommandAvailability = pluginCommandAvailability,
                pluginCommandRegistrationIssue = pluginCommandRegistrationIssue,
                pluginCommandExecutionIssue = pluginCommandExecutionIssue,
            )
        }.sortedWith(
            compareBy<PluginsCommandContribution> { command -> command.surface.ordinal }
                .thenBy { command -> command.group }
                .thenBy { command -> command.title.ifBlank { command.commandId } }
                .thenBy { command -> command.commandId }
        )
    }

    fun resolveCommandContributionSummary(
        commands: List<PluginsCommandContribution>,
    ): PluginsCommandContributionSummary = PluginsCommandContributionSummary(
        totalCount = commands.size,
        availableCount = commands.count { command -> command.status == PluginCommandContributionStatus.AVAILABLE },
        issueCount = commands.count { command -> command.status != PluginCommandContributionStatus.AVAILABLE },
    )

    fun resolveCommandContributionFilterOptions(
        summary: PluginsCommandContributionSummary,
    ): List<PluginCommandContributionFilterOption> = buildList {
        add(
            PluginCommandContributionFilterOption(
                filter = PluginCommandContributionFilter.ALL,
                count = summary.totalCount,
            )
        )
        if (summary.issueCount > 0) {
            add(
                PluginCommandContributionFilterOption(
                    filter = PluginCommandContributionFilter.ISSUES,
                    count = summary.issueCount,
                )
            )
        }
        if (summary.availableCount > 0) {
            add(
                PluginCommandContributionFilterOption(
                    filter = PluginCommandContributionFilter.AVAILABLE,
                    count = summary.availableCount,
                )
            )
        }
    }

    fun resolveCommandContributionFilterOrAll(
        filter: PluginCommandContributionFilter,
        availableFilters: List<PluginCommandContributionFilterOption>,
    ): PluginCommandContributionFilter {
        val availableFilterValues = availableFilters.map { option -> option.filter }.toSet()
        return filter.takeIf { it in availableFilterValues } ?: PluginCommandContributionFilter.ALL
    }

    fun filterCommandContributions(
        commands: List<PluginsCommandContribution>,
        filter: PluginCommandContributionFilter,
    ): List<PluginsCommandContribution> = when (filter) {
        PluginCommandContributionFilter.ALL -> commands
        PluginCommandContributionFilter.ISSUES -> commands.filter { command ->
            command.status != PluginCommandContributionStatus.AVAILABLE
        }
        PluginCommandContributionFilter.AVAILABLE -> commands.filter { command ->
            command.status == PluginCommandContributionStatus.AVAILABLE
        }
    }

    private fun MutableList<PluginsCommandContribution>.appendCommandContributions(
        pluginId: String,
        surface: ResolvedPluginCommandSurface,
        menuItems: List<PluginMenuItem>,
        declaredCommands: Map<String, PluginCommand>,
        checkRuntimeAvailability: Boolean,
        isPluginCommandRegistered: (commandId: String, pluginId: String) -> Boolean,
        pluginCommandAvailability: (commandId: String, pluginId: String) -> PluginCommandAvailability,
        pluginCommandRegistrationIssue: (commandId: String, pluginId: String) -> PluginCommandRegistrationIssue?,
        pluginCommandExecutionIssue: (commandId: String, pluginId: String) -> PluginCommandExecutionIssue?,
    ) {
        menuItems.forEach { menuItem ->
            val commandId = menuItem.command.trim()
            val declaredCommand = declaredCommands[commandId]
            val source = when {
                commandId.isBlank() -> null
                HostCommands.isSupported(commandId) -> ResolvedPluginCommandSource.HOST
                declaredCommand != null -> ResolvedPluginCommandSource.PLUGIN
                else -> null
            }
            var registrationIssue: PluginCommandRegistrationIssue? = null
            var executionIssue: PluginCommandExecutionIssue? = null
            val availability = if (source == ResolvedPluginCommandSource.PLUGIN) {
                when {
                    !checkRuntimeAvailability -> PluginCommandAvailability(available = true)
                    isPluginCommandRegistered(commandId, pluginId) -> {
                        pluginCommandAvailability(commandId, pluginId).also { currentAvailability ->
                            if (currentAvailability.available) {
                                executionIssue = pluginCommandExecutionIssue(commandId, pluginId)
                            }
                        }
                    }
                    else -> {
                        registrationIssue = pluginCommandRegistrationIssue(commandId, pluginId)
                        null
                    }
                }
            } else {
                null
            }
            val status = resolveCommandContributionStatus(
                commandId = commandId,
                source = source,
                availability = availability,
                executionIssue = executionIssue,
            )
            add(
                PluginsCommandContribution(
                    surface = surface,
                    commandId = commandId,
                    title = declaredCommand?.title?.trim()?.takeIf { title -> title.isNotBlank() }
                        ?: commandId,
                    group = menuItem.group?.trim()?.takeIf { group -> group.isNotBlank() }
                        ?: DEFAULT_PLUGIN_COMMAND_GROUP,
                    source = source,
                    status = status,
                    whenExpression = menuItem.`when`?.trim()?.takeIf { expression -> expression.isNotBlank() },
                    statusMessage = resolveCommandContributionStatusMessage(
                        status = status,
                        availability = availability,
                        registrationIssue = registrationIssue,
                        executionIssue = executionIssue,
                    ),
                )
            )
        }
    }

    private fun resolveCommandContributionStatus(
        commandId: String,
        source: ResolvedPluginCommandSource?,
        availability: PluginCommandAvailability?,
        executionIssue: PluginCommandExecutionIssue?,
    ): PluginCommandContributionStatus = when {
        commandId.isBlank() -> PluginCommandContributionStatus.MISSING_COMMAND_ID
        source == null -> PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION
        source == ResolvedPluginCommandSource.HOST -> PluginCommandContributionStatus.AVAILABLE
        availability == null -> PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION
        !availability.available -> PluginCommandContributionStatus.UNAVAILABLE
        executionIssue != null -> PluginCommandContributionStatus.EXECUTION_FAILED
        else -> PluginCommandContributionStatus.AVAILABLE
    }

    private fun resolveCommandContributionStatusMessage(
        status: PluginCommandContributionStatus,
        availability: PluginCommandAvailability?,
        registrationIssue: PluginCommandRegistrationIssue?,
        executionIssue: PluginCommandExecutionIssue?,
    ): String? = when (status) {
        PluginCommandContributionStatus.EXECUTION_FAILED -> executionIssue?.message
        else -> availability?.errorMessage ?: registrationIssue?.message
    }?.trim()?.takeIf { message -> message.isNotBlank() }

    fun resolveRequirementsSummary(manifest: PluginManifest): PluginsRequirementsSummary {
        val requirements = manifest.requires
        return PluginsRequirementsSummary(
            recommendedToolchains = normalizeRequirementItems(requirements?.toolchain?.recommended),
            optionalToolchains = normalizeRequirementItems(requirements?.toolchain?.optional),
            packageGroups = requirements?.packages.orEmpty()
                .mapNotNull { (manager, packages) ->
                    val normalizedManager = manager.trim()
                    val normalizedPackages = normalizeRequirementItems(packages)
                    if (normalizedManager.isBlank() || normalizedPackages.isEmpty()) {
                        null
                    } else {
                        PluginsPackageRequirementGroup(
                            manager = normalizedManager,
                            packages = normalizedPackages,
                        )
                    }
                }
                .sortedBy { group -> group.manager },
        )
    }

    fun resolveConfigurationSummary(manifest: PluginManifest): PluginsConfigurationSummary = PluginsConfigurationSummary(
        title = manifest.configuration?.title?.trim()?.takeIf { title -> title.isNotBlank() },
        properties = PluginConfigurationSchema.resolveProperties(manifest),
    )

    fun resolvePluginDiagnosticsSummary(report: PluginDiagnosticsReport?): PluginDiagnosticsSummary {
        val issues = report?.issues.orEmpty()
        val errorCount = issues.count { it.severity == PluginDiagnosticSeverity.ERROR }
        val warningCount = issues.count { it.severity == PluginDiagnosticSeverity.WARNING }
        val infoCount = issues.count { it.severity == PluginDiagnosticSeverity.INFO }
        return PluginDiagnosticsSummary(
            totalCount = issues.size,
            errorCount = errorCount,
            warningCount = warningCount,
            infoCount = infoCount,
            highestSeverity = issues.maxByOrNull { it.severity.priority }?.severity,
        )
    }

    fun resolveLspDiagnosticSeverity(report: PluginDiagnosticsReport?): PluginDiagnosticSeverity? = report
        ?.entries
        .orEmpty()
        .asSequence()
        .filter { entry -> entry.issue.category == PluginDiagnosticCategory.LSP }
        .map { entry -> entry.issue.severity }
        .maxByOrNull { severity -> severity.priority }

    fun hasLspDiagnostics(report: PluginDiagnosticsReport?): Boolean = resolveLspDiagnosticSeverity(report) != null

    fun resolvePluginPreflightDiagnosticGroups(
        report: PluginDiagnosticsReport,
    ): List<PluginDiagnosticCategoryGroup> {
        val visibleEntries = report.entries.filter { entry ->
            entry.issue.severity == PluginDiagnosticSeverity.ERROR ||
                entry.issue.severity == PluginDiagnosticSeverity.WARNING
        }.sortedWith(pluginDiagnosticEntryComparator())

        return visibleEntries.groupBy { entry -> entry.issue.category }
            .map { (category, entries) ->
                PluginDiagnosticCategoryGroup(
                    category = category,
                    entries = entries,
                    errorCount = entries.count { entry ->
                        entry.issue.severity == PluginDiagnosticSeverity.ERROR
                    },
                    warningCount = entries.count { entry ->
                        entry.issue.severity == PluginDiagnosticSeverity.WARNING
                    },
                )
            }
            .sortedWith(
                compareByDescending<PluginDiagnosticCategoryGroup> { group ->
                    group.entries.maxOfOrNull { entry -> entry.issue.severity.priority } ?: 0
                }.thenBy { group -> group.category.ordinal }
            )
    }

    fun resolvePluginDiagnosticsOverviewSummary(
        snapshot: PluginDiagnosticsSnapshot,
    ): PluginDiagnosticsOverviewSummary = resolvePluginDiagnosticsOverviewSummary(
        installedReports = snapshot.installedReports.values,
        loadReports = snapshot.loadReports,
    )

    fun resolvePluginDiagnosticsOverviewSummary(
        installedReports: Collection<PluginDiagnosticsReport>,
        loadReports: List<PluginDiagnosticsReport>,
    ): PluginDiagnosticsOverviewSummary = PluginDiagnosticsOverviewSummary(
        installedCount = installedReports.size,
        issuePluginCount = installedReports.count { it.hasIssues },
        errorPluginCount = installedReports.count {
            it.highestSeverity == PluginDiagnosticSeverity.ERROR
        },
        warningPluginCount = installedReports.count {
            it.highestSeverity == PluginDiagnosticSeverity.WARNING
        },
        lspIssuePluginCount = installedReports.count { report ->
            hasLspDiagnostics(report)
        },
        notLoadedCount = loadReports.size,
    )

    fun togglePluginDiagnosticsFilter(
        currentFilter: PluginDiagnosticsFilter,
        selectedFilter: PluginDiagnosticsFilter,
    ): PluginDiagnosticsFilter {
        if (selectedFilter == PluginDiagnosticsFilter.ALL) {
            return PluginDiagnosticsFilter.ALL
        }
        return if (currentFilter == selectedFilter) {
            PluginDiagnosticsFilter.ALL
        } else {
            selectedFilter
        }
    }

    fun filterInstalledPluginsByDiagnostics(
        installedPlugins: List<InstalledPlugin>,
        snapshot: PluginDiagnosticsSnapshot,
        filter: PluginDiagnosticsFilter,
    ): List<InstalledPlugin> = when (filter) {
        PluginDiagnosticsFilter.ALL -> installedPlugins
        PluginDiagnosticsFilter.ERROR -> installedPlugins.filter { plugin ->
            snapshot.getInstalledReport(plugin.manifest.id)?.highestSeverity ==
                PluginDiagnosticSeverity.ERROR
        }
        PluginDiagnosticsFilter.WARNING -> installedPlugins.filter { plugin ->
            snapshot.getInstalledReport(plugin.manifest.id)?.highestSeverity ==
                PluginDiagnosticSeverity.WARNING
        }
        PluginDiagnosticsFilter.NOT_LOADED -> emptyList()
        PluginDiagnosticsFilter.LSP -> installedPlugins.filter { plugin ->
            hasLspDiagnostics(snapshot.getInstalledReport(plugin.manifest.id))
        }
    }

    fun filterLoadReportsByDiagnostics(
        snapshot: PluginDiagnosticsSnapshot,
        filter: PluginDiagnosticsFilter,
    ): List<PluginDiagnosticsReport> = when (filter) {
        PluginDiagnosticsFilter.ALL,
        PluginDiagnosticsFilter.NOT_LOADED -> snapshot.loadReports
        PluginDiagnosticsFilter.ERROR,
        PluginDiagnosticsFilter.WARNING,
        PluginDiagnosticsFilter.LSP -> emptyList()
    }

    fun shouldShowDiagnosticsFilteredEmptyState(
        installedPlugins: List<InstalledPlugin>,
        loadReports: List<PluginDiagnosticsReport>,
        filter: PluginDiagnosticsFilter,
    ): Boolean = filter != PluginDiagnosticsFilter.ALL &&
        installedPlugins.isEmpty() &&
        loadReports.isEmpty()

    fun togglePluginDiagnosticSourceFilter(
        currentFilter: PluginDiagnosticSourceFilter,
        selectedFilter: PluginDiagnosticSourceFilter,
    ): PluginDiagnosticSourceFilter {
        if (selectedFilter == PluginDiagnosticSourceFilter.ALL) {
            return PluginDiagnosticSourceFilter.ALL
        }
        return if (currentFilter == selectedFilter) {
            PluginDiagnosticSourceFilter.ALL
        } else {
            selectedFilter
        }
    }

    fun resolveAvailablePluginDiagnosticSourceFilters(
        entries: List<PluginDiagnosticEntry>,
    ): List<PluginDiagnosticSourceFilter> {
        val availableFilters = buildList {
            add(PluginDiagnosticSourceFilter.ALL)
            if (entries.any { it.source == PluginDiagnosticSource.LOAD }) {
                add(PluginDiagnosticSourceFilter.LOAD)
            }
            if (entries.any { it.source == PluginDiagnosticSource.HEALTH }) {
                add(PluginDiagnosticSourceFilter.HEALTH)
            }
            if (entries.any { it.source == PluginDiagnosticSource.RUNTIME }) {
                add(PluginDiagnosticSourceFilter.RUNTIME)
            }
        }
        return availableFilters
    }

    fun resolvePluginDiagnosticSourceFilterOrAll(
        filter: PluginDiagnosticSourceFilter,
        availableFilters: List<PluginDiagnosticSourceFilter>,
    ): PluginDiagnosticSourceFilter = if (filter in availableFilters) {
        filter
    } else {
        PluginDiagnosticSourceFilter.ALL
    }

    fun filterPluginDiagnosticEntriesBySource(
        entries: List<PluginDiagnosticEntry>,
        filter: PluginDiagnosticSourceFilter,
    ): List<PluginDiagnosticEntry> = when (filter) {
        PluginDiagnosticSourceFilter.ALL -> entries
        PluginDiagnosticSourceFilter.LOAD -> entries.filter { it.source == PluginDiagnosticSource.LOAD }
        PluginDiagnosticSourceFilter.HEALTH -> entries.filter { it.source == PluginDiagnosticSource.HEALTH }
        PluginDiagnosticSourceFilter.RUNTIME -> entries.filter { it.source == PluginDiagnosticSource.RUNTIME }
    }

    fun shouldShowPluginDiagnosticSourceFilteredEmptyState(
        entries: List<PluginDiagnosticEntry>,
        filter: PluginDiagnosticSourceFilter,
    ): Boolean = filter != PluginDiagnosticSourceFilter.ALL && entries.isEmpty()

    fun filterPluginDiagnosticsReportBySource(
        report: PluginDiagnosticsReport,
        filter: PluginDiagnosticSourceFilter,
    ): PluginDiagnosticsReport? {
        if (filter == PluginDiagnosticSourceFilter.ALL) {
            return report
        }
        val filteredEntries = filterPluginDiagnosticEntriesBySource(
            entries = report.entries,
            filter = filter,
        )
        return if (filteredEntries.isEmpty()) {
            null
        } else {
            report.copy(entries = filteredEntries)
        }
    }

    fun filterPluginDiagnosticsReportsBySource(
        reports: List<PluginDiagnosticsReport>,
        filter: PluginDiagnosticSourceFilter,
    ): List<PluginDiagnosticsReport> = reports.mapNotNull { report ->
        filterPluginDiagnosticsReportBySource(
            report = report,
            filter = filter,
        )
    }

    fun filterInstalledPluginDiagnosticsReportsBySource(
        reports: Map<String, PluginDiagnosticsReport>,
        filter: PluginDiagnosticSourceFilter,
    ): Map<String, PluginDiagnosticsReport> = reports.mapNotNull { (pluginId, report) ->
        filterPluginDiagnosticsReportBySource(
            report = report,
            filter = filter,
        )?.let { filteredReport ->
            pluginId to filteredReport
        }
    }.toMap()

    fun resolvePluginDiagnosticActions(
        entry: PluginDiagnosticEntry,
        isScriptPlugin: Boolean,
        isInstalled: Boolean,
    ): List<PluginDiagnosticAction> {
        if (!isInstalled || entry.source == PluginDiagnosticSource.LOAD) {
            return listOf(PluginDiagnosticAction.COPY_DIAGNOSTIC)
        }

        return buildList {
            if (entry.issue.category == PluginDiagnosticCategory.LSP) {
                add(PluginDiagnosticAction.OPEN_LOGS)
                add(PluginDiagnosticAction.REPAIR_LSP_DEPENDENCIES)
            } else if (entry.source == PluginDiagnosticSource.RUNTIME) {
                add(PluginDiagnosticAction.OPEN_LOGS)
                if (isScriptPlugin) {
                    add(PluginDiagnosticAction.RELOAD_PLUGIN)
                }
            }
            if (entry.issue.category == PluginDiagnosticCategory.PERMISSIONS) {
                add(PluginDiagnosticAction.SHOW_PERMISSIONS)
            }
        }.distinct()
    }

    fun resolvePluginCommandContributionActions(
        command: PluginsCommandContribution,
        isScriptPlugin: Boolean,
    ): List<PluginDiagnosticAction> = buildList {
        when (command.status) {
            PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION -> {
                add(PluginDiagnosticAction.OPEN_LOGS)
                if (isScriptPlugin) {
                    add(PluginDiagnosticAction.RELOAD_PLUGIN)
                }
                add(PluginDiagnosticAction.COPY_DIAGNOSTIC)
            }
            PluginCommandContributionStatus.UNAVAILABLE -> {
                add(PluginDiagnosticAction.OPEN_LOGS)
                if (command.statusMessage?.isNotBlank() == true) {
                    add(PluginDiagnosticAction.SHOW_PERMISSIONS)
                }
                if (isScriptPlugin) {
                    add(PluginDiagnosticAction.RELOAD_PLUGIN)
                }
                add(PluginDiagnosticAction.COPY_DIAGNOSTIC)
            }
            PluginCommandContributionStatus.EXECUTION_FAILED -> {
                add(PluginDiagnosticAction.OPEN_LOGS)
                if (isScriptPlugin) {
                    add(PluginDiagnosticAction.RELOAD_PLUGIN)
                }
                add(PluginDiagnosticAction.COPY_DIAGNOSTIC)
            }
            PluginCommandContributionStatus.MISSING_COMMAND_ID,
            PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION -> {
                add(PluginDiagnosticAction.COPY_DIAGNOSTIC)
            }
            PluginCommandContributionStatus.AVAILABLE -> Unit
        }
    }.distinct()

    fun resolvePluginDiagnosticPreferredLogLevel(
        entry: PluginDiagnosticEntry,
    ): PluginLogLevel? {
        if (entry.source != PluginDiagnosticSource.RUNTIME) {
            return null
        }
        return when (entry.issue.severity) {
            PluginDiagnosticSeverity.ERROR -> PluginLogLevel.ERROR
            PluginDiagnosticSeverity.WARNING -> PluginLogLevel.WARN
            PluginDiagnosticSeverity.INFO -> null
        }
    }

    fun buildPluginDiagnosticClipboardText(
        report: PluginDiagnosticsReport,
        entry: PluginDiagnosticEntry,
    ): String {
        val issue = entry.issue
        return buildString {
            appendLine("Plugin diagnostics")
            appendLine("pluginId: ${report.pluginId ?: "-"}")
            appendLine("pluginName: ${report.pluginName}")
            report.directoryName?.takeIf { it.isNotBlank() }?.let { directoryName ->
                appendLine("directoryName: $directoryName")
            }
            appendLine("installed: ${report.isInstalled}")
            appendLine("source: ${entry.source.name}")
            appendLine("severity: ${issue.severity.name}")
            appendLine("category: ${issue.category.name}")
            appendLine("message: ${issue.message}")
            issue.fixHint?.takeIf { it.isNotBlank() }?.let { fixHint ->
                appendLine("fixHint: $fixHint")
            }
        }.trimEnd()
    }

    fun buildPluginDiagnosticsClipboardText(report: PluginDiagnosticsReport): String = buildString {
        appendLine("Plugin diagnostics")
        appendLine("pluginId: ${report.pluginId ?: "-"}")
        appendLine("pluginName: ${report.pluginName}")
        report.directoryName?.takeIf { it.isNotBlank() }?.let { directoryName ->
            appendLine("directoryName: $directoryName")
        }
        appendLine("installed: ${report.isInstalled}")
        appendLine("issueCount: ${report.entries.size}")
        report.entries.forEachIndexed { index, entry ->
            val issue = entry.issue
            appendLine()
            appendLine("issue[${index + 1}]")
            appendLine("source: ${entry.source.name}")
            appendLine("severity: ${issue.severity.name}")
            appendLine("category: ${issue.category.name}")
            appendLine("message: ${issue.message}")
            issue.fixHint?.takeIf { it.isNotBlank() }?.let { fixHint ->
                appendLine("fixHint: $fixHint")
            }
        }
    }.trimEnd()

    fun buildPluginCommandContributionClipboardText(
        plugin: InstalledPlugin,
        command: PluginsCommandContribution,
    ): String = buildString {
        appendLine("Plugin command contribution")
        appendLine("pluginId: ${plugin.manifest.id}")
        appendLine("pluginName: ${plugin.manifest.name}")
        appendLine("directoryName: ${plugin.directory.name}")
        appendLine("enabled: ${plugin.enabled}")
        appendLine("surface: ${command.surface.name}")
        appendLine("source: ${command.source?.name ?: "-"}")
        appendLine("status: ${command.status.name}")
        appendLine("commandId: ${command.commandId.ifBlank { "-" }}")
        appendLine("title: ${command.title.ifBlank { "-" }}")
        appendLine("group: ${command.group.ifBlank { "-" }}")
        command.whenExpression?.takeIf { it.isNotBlank() }?.let { whenExpression ->
            appendLine("when: $whenExpression")
        }
        command.statusMessage?.takeIf { it.isNotBlank() }?.let { statusMessage ->
            appendLine("reason: $statusMessage")
        }
    }.trimEnd()

    fun buildPluginPreflightLogMessage(
        report: PluginDiagnosticsReport,
        blocked: Boolean,
    ): String {
        val summary = resolvePluginDiagnosticsSummary(report)
        val status = if (blocked) "blocked" else "warning"
        val issueSummary = report.entries.joinToString(separator = "; ") { entry ->
            buildString {
                append(entry.source.name)
                append('/')
                append(entry.issue.severity.name)
                append('/')
                append(entry.issue.category.name)
                append(": ")
                append(entry.issue.message)
                entry.issue.fixHint?.takeIf { it.isNotBlank() }?.let { fixHint ->
                    append(" (fix: ")
                    append(fixHint)
                    append(')')
                }
            }
        }.ifBlank { "none" }
        return "Install preflight $status pluginId=${report.pluginId ?: "-"} " +
            "pluginName=${report.pluginName} errors=${summary.errorCount} " +
            "warnings=${summary.warningCount} issues=[$issueSummary]"
    }

    fun resolveInstalledPluginDetailDiagnosticsReport(
        plugin: InstalledPlugin,
        snapshotReport: PluginDiagnosticsReport?,
        manualDoctorReport: PluginDiagnosticsReport?,
    ): PluginDiagnosticsReport {
        val fallbackReport = snapshotReport ?: PluginDiagnosticsReport(
            pluginId = plugin.manifest.id,
            pluginName = plugin.manifest.name,
            directoryName = plugin.directory.name,
            isInstalled = true,
            entries = emptyList(),
        )
        val doctorReport = manualDoctorReport ?: return fallbackReport
        val hasLoadIssue = doctorReport.entries.any { entry ->
            entry.source == PluginDiagnosticSource.LOAD
        }
        if (hasLoadIssue) {
            return doctorReport
        }

        val mergedEntries = buildList {
            addAll(doctorReport.entries.filter { entry -> entry.source != PluginDiagnosticSource.RUNTIME })
            addAll(fallbackReport.entries.filter { entry -> entry.source == PluginDiagnosticSource.RUNTIME })
        }.distinctBy { entry ->
            listOf(
                entry.source.name,
                entry.issue.severity.name,
                entry.issue.category.name,
                entry.issue.message,
                entry.issue.fixHint.orEmpty(),
            ).joinToString("|")
        }.sortedWith(
            pluginDiagnosticEntryComparator()
        )

        return doctorReport.copy(
            pluginId = fallbackReport.pluginId ?: doctorReport.pluginId,
            pluginName = fallbackReport.pluginName,
            directoryName = fallbackReport.directoryName ?: doctorReport.directoryName,
            isInstalled = fallbackReport.isInstalled,
            entries = mergedEntries,
        )
    }

    fun resolvePluginDiagnosticSourceFilterOptions(
        installedReports: Collection<PluginDiagnosticsReport>,
        loadReports: List<PluginDiagnosticsReport>,
    ): List<PluginDiagnosticSourceFilterOption> {
        val visibleReports = buildList {
            addAll(installedReports)
            addAll(loadReports)
        }
        return buildList {
            add(
                PluginDiagnosticSourceFilterOption(
                    filter = PluginDiagnosticSourceFilter.ALL,
                    count = visibleReports.size,
                )
            )
            appendPluginDiagnosticSourceFilterOptionIfAny(
                reports = visibleReports,
                source = PluginDiagnosticSource.LOAD,
                filter = PluginDiagnosticSourceFilter.LOAD,
            )
            appendPluginDiagnosticSourceFilterOptionIfAny(
                reports = visibleReports,
                source = PluginDiagnosticSource.HEALTH,
                filter = PluginDiagnosticSourceFilter.HEALTH,
            )
            appendPluginDiagnosticSourceFilterOptionIfAny(
                reports = visibleReports,
                source = PluginDiagnosticSource.RUNTIME,
                filter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        }
    }

    private fun MutableList<PluginDiagnosticSourceFilterOption>.appendPluginDiagnosticSourceFilterOptionIfAny(
        reports: List<PluginDiagnosticsReport>,
        source: PluginDiagnosticSource,
        filter: PluginDiagnosticSourceFilter,
    ) {
        val count = reports.count { report ->
            report.entries.any { entry -> entry.source == source }
        }
        if (count > 0) {
            add(
                PluginDiagnosticSourceFilterOption(
                    filter = filter,
                    count = count,
                )
            )
        }
    }

    private fun pluginDiagnosticEntryComparator(): Comparator<PluginDiagnosticEntry> = compareByDescending<PluginDiagnosticEntry> { entry -> entry.issue.severity.priority }
        .thenBy { entry -> entry.issue.category.ordinal }
        .thenBy { entry -> entry.issue.message }
        .thenBy { entry -> entry.source.ordinal }

    @StringRes
    fun resolvePluginDiagnosticSeverityLabelRes(severity: PluginDiagnosticSeverity): Int = when (severity) {
        PluginDiagnosticSeverity.INFO -> Strings.diagnostic_information
        PluginDiagnosticSeverity.WARNING -> Strings.diagnostic_warning
        PluginDiagnosticSeverity.ERROR -> Strings.diagnostic_error
    }

    @StringRes
    fun resolvePluginDiagnosticCategoryLabelRes(category: PluginDiagnosticCategory): Int = when (category) {
        PluginDiagnosticCategory.MANIFEST -> Strings.plugins_diagnostics_category_manifest
        PluginDiagnosticCategory.PERMISSIONS -> Strings.plugins_diagnostics_category_permissions
        PluginDiagnosticCategory.CONTRIBUTIONS -> Strings.plugins_diagnostics_category_contributions
        PluginDiagnosticCategory.COMPATIBILITY -> Strings.plugins_diagnostics_category_compatibility
        PluginDiagnosticCategory.RUNTIME -> Strings.plugins_diagnostics_category_runtime
        PluginDiagnosticCategory.LSP -> Strings.plugins_diagnostics_category_lsp
    }

    @StringRes
    fun resolvePluginPreflightCategoryGuideRes(category: PluginDiagnosticCategory): Int = when (category) {
        PluginDiagnosticCategory.MANIFEST -> Strings.plugins_preflight_guide_manifest
        PluginDiagnosticCategory.PERMISSIONS -> Strings.plugins_preflight_guide_permissions
        PluginDiagnosticCategory.CONTRIBUTIONS -> Strings.plugins_preflight_guide_contributions
        PluginDiagnosticCategory.COMPATIBILITY -> Strings.plugins_preflight_guide_compatibility
        PluginDiagnosticCategory.RUNTIME -> Strings.plugins_preflight_guide_runtime
        PluginDiagnosticCategory.LSP -> Strings.plugins_preflight_guide_lsp
    }

    @StringRes
    fun resolvePluginDiagnosticSourceLabelRes(source: PluginDiagnosticSource): Int = when (source) {
        PluginDiagnosticSource.LOAD -> Strings.plugins_diagnostics_source_load
        PluginDiagnosticSource.HEALTH -> Strings.plugins_diagnostics_source_health
        PluginDiagnosticSource.RUNTIME -> Strings.plugins_diagnostics_source_runtime
    }

    @StringRes
    fun resolvePluginDiagnosticsFilterLabelRes(
        filter: PluginDiagnosticsFilter,
    ): Int = when (filter) {
        PluginDiagnosticsFilter.ALL -> Strings.filter_all
        PluginDiagnosticsFilter.ERROR -> Strings.settings_plugins_overview_error_count
        PluginDiagnosticsFilter.WARNING -> Strings.settings_plugins_overview_warning_count
        PluginDiagnosticsFilter.NOT_LOADED -> Strings.settings_plugins_overview_not_loaded_count
        PluginDiagnosticsFilter.LSP -> Strings.plugins_diagnostics_filter_lsp
    }

    @StringRes
    fun resolvePluginDiagnosticSourceFilterLabelRes(
        filter: PluginDiagnosticSourceFilter,
    ): Int = when (filter) {
        PluginDiagnosticSourceFilter.ALL -> Strings.filter_all
        PluginDiagnosticSourceFilter.LOAD -> Strings.plugins_diagnostics_source_load
        PluginDiagnosticSourceFilter.HEALTH -> Strings.plugins_diagnostics_source_health
        PluginDiagnosticSourceFilter.RUNTIME -> Strings.plugins_diagnostics_source_runtime
    }

    @StringRes
    fun resolvePluginDiagnosticActionLabelRes(action: PluginDiagnosticAction): Int = when (action) {
        PluginDiagnosticAction.OPEN_LOGS -> Strings.settings_plugins_logs
        PluginDiagnosticAction.RELOAD_PLUGIN -> Strings.settings_plugins_reload
        PluginDiagnosticAction.SHOW_PERMISSIONS -> Strings.plugins_diagnostics_action_view_permissions
        PluginDiagnosticAction.REPAIR_LSP_DEPENDENCIES -> Strings.plugins_diagnostics_action_repair_lsp
        PluginDiagnosticAction.COPY_DIAGNOSTIC -> Strings.action_copy
    }

    @StringRes
    fun resolvePluginCommandSurfaceLabelRes(surface: ResolvedPluginCommandSurface): Int = when (surface) {
        ResolvedPluginCommandSurface.EDITOR_CONTEXT -> Strings.plugins_commands_surface_editor_context
        ResolvedPluginCommandSurface.EDITOR_TOOLBAR -> Strings.plugins_commands_surface_editor_toolbar
        ResolvedPluginCommandSurface.FILE_TREE_CONTEXT -> Strings.plugins_commands_surface_file_tree_context
    }

    @StringRes
    fun resolvePluginCommandSourceLabelRes(source: ResolvedPluginCommandSource?): Int = when (source) {
        ResolvedPluginCommandSource.HOST -> Strings.plugins_commands_source_host
        ResolvedPluginCommandSource.PLUGIN -> Strings.plugins_commands_source_plugin
        null -> Strings.plugins_commands_source_unknown
    }

    @StringRes
    fun resolvePluginCommandStatusLabelRes(status: PluginCommandContributionStatus): Int = when (status) {
        PluginCommandContributionStatus.AVAILABLE -> Strings.plugins_commands_status_available
        PluginCommandContributionStatus.MISSING_COMMAND_ID -> Strings.plugins_commands_status_missing_command_id
        PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION -> Strings.plugins_commands_status_missing_declaration
        PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION -> {
            Strings.plugins_commands_status_missing_registration
        }
        PluginCommandContributionStatus.UNAVAILABLE -> Strings.plugins_commands_status_unavailable
        PluginCommandContributionStatus.EXECUTION_FAILED -> Strings.plugins_commands_status_execution_failed
    }

    @StringRes
    fun resolvePluginCommandContributionFilterLabelRes(filter: PluginCommandContributionFilter): Int = when (filter) {
        PluginCommandContributionFilter.ALL -> Strings.filter_all
        PluginCommandContributionFilter.ISSUES -> Strings.plugins_commands_filter_issues
        PluginCommandContributionFilter.AVAILABLE -> Strings.plugins_commands_filter_available
    }
}
