package com.wuxianggujun.tinaide.plugin.lsp

import android.content.Context
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.proot.GuestSystemPackageManager
import com.wuxianggujun.tinaide.core.proot.RootfsPackageManager
import com.wuxianggujun.tinaide.core.proot.displayName
import com.wuxianggujun.tinaide.core.proot.resolveGuestPackageManager
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginFaultKind
import com.wuxianggujun.tinaide.plugin.PluginFaultPhase
import com.wuxianggujun.tinaide.plugin.PluginFaultRecord
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginRuntimeLifecycle
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * LSP 插件管理器
 *
 * 职责：
 * - 管理 LSP 类型插件的生命周期
 * - 协调工具链安装
 * - 提供 LSP 服务器配置给 LspEditorManager
 */
class LspPluginManager(
    private val context: Context,
    private val pluginManager: PluginManager,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider,
) : ServiceLifecycle {

    companion object {
        private const val TAG = "LspPluginManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startedPluginIds = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleHandler = PluginRuntimeLifecycle.Handler(
        stop = { pluginId ->
            startedPluginIds.remove(pluginId)
            PluginLspSessionRegistry.closeAll(pluginId)
        },
        activate = { pluginId ->
            PluginLspSessionRegistry.activate(pluginId)
            Result.success(Unit)
        },
    )

    private val _lspPluginsFlow = MutableStateFlow<List<LspPluginInfo>>(emptyList())
    val lspPluginsFlow: StateFlow<List<LspPluginInfo>> = _lspPluginsFlow.asStateFlow()

    private val _installStatesFlow = MutableStateFlow<Map<String, LspPluginInstallState>>(emptyMap())
    val installStatesFlow: StateFlow<Map<String, LspPluginInstallState>> = _installStatesFlow.asStateFlow()

    private val toolchainInstaller: LspToolchainInstaller by lazy {
        LspToolchainInstaller(context, linuxEnvironmentProvider)
    }

    private val pluginLogManager: PluginLogManager by lazy {
        PluginLogManager.getInstance(context.applicationContext)
    }

    override fun onCreate() {
        PluginRuntimeLifecycle.register(lifecycleHandler)
        Timber.tag(TAG).i(
            "LspPluginManager binding PluginManager instance=%s",
            pluginManager.instanceId
        )
        // 监听插件列表变化
        scope.launch {
            pluginManager.enabledPluginsFlow.collect { plugins ->
                try {
                    refreshLspPlugins(plugins)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(TAG).e(error, "Failed to refresh LSP plugin contributions")
                }
            }
        }
    }

    override fun onDestroy() {
        PluginRuntimeLifecycle.unregister(lifecycleHandler)
        PluginLspSessionRegistry.closeAll()
        startedPluginIds.clear()
        scope.cancel()
    }

    /**
     * 刷新 LSP 插件列表
     */
    private fun refreshLspPlugins(allPlugins: List<InstalledPlugin>) {
        val previousPluginIds = _lspPluginsFlow.value.mapTo(mutableSetOf()) { it.pluginId }
        val lspPlugins = allPlugins
            .filter { it.manifest.type.equals("lsp", ignoreCase = true) }
            .mapNotNull { plugin ->
                parseLspPluginInfo(plugin)
            }
        _lspPluginsFlow.value = lspPlugins
        val activePluginIds = lspPlugins.mapTo(mutableSetOf()) { it.pluginId }
        activePluginIds.forEach(PluginLspSessionRegistry::activate)
        (previousPluginIds - activePluginIds).forEach { pluginId ->
            startedPluginIds.remove(pluginId)
            PluginLspSessionRegistry.closeAll(pluginId)
        }
        Timber.tag(TAG).i("Refreshed LSP plugins: ${lspPlugins.map { it.pluginId }}")
        scope.launch {
            refreshToolchainInstallStates()
        }
    }

    /**
     * 重新探测 LSP 工具链状态，不执行安装。
     */
    suspend fun refreshToolchainInstallStates(pluginId: String? = null): Result<Unit> {
        val plugins = if (pluginId == null) {
            _lspPluginsFlow.value
        } else {
            listOf(
                _lspPluginsFlow.value.find { plugin -> plugin.pluginId == pluginId }
                    ?: return Result.failure(
                        IllegalArgumentException(
                            Strings.lsp_plugin_error_plugin_not_found.strOr(context, pluginId)
                        )
                    )
            )
        }

        return try {
            plugins.forEach { plugin ->
                refreshPluginToolchainInstallState(plugin)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to refresh LSP toolchain install states")
            Result.failure(e)
        }
    }

    /**
     * 获取支持指定文件扩展名的 LSP 服务器配置
     */
    fun getServerConfigForExtension(extension: String): Pair<LspPluginInfo, LspServerConfig>? {
        val ext = extension.lowercase()
        return findServerConfig { config -> matchesFileExtension(config, ext) }
    }

    /**
     * 获取支持指定文件的 LSP 服务器配置。
     *
     * 优先按扩展名匹配，再按文件名模式匹配，
     * 兼容无扩展名文件与 `pom.xml` 这类固定文件名。
     */
    fun getServerConfigForFile(
        file: File,
        detectedLanguageId: String? = null,
    ): Pair<LspPluginInfo, LspServerConfig>? {
        val fileName = file.name
        val ext = file.extension.lowercase()
        return findServerConfig(detectedLanguageId) { config ->
            matchesFileExtension(config, ext) ||
                matchesFilePattern(config, fileName, ext)
        }
    }

    /**
     * 获取支持指定语言的 LSP 服务器配置
     */
    fun getServerConfigForLanguage(languageId: String): Pair<LspPluginInfo, LspServerConfig>? {
        val langId = languageId.lowercase()
        for (plugin in _lspPluginsFlow.value) {
            if (!plugin.supportsLanguageActivation(langId)) continue
            for (config in plugin.serverConfigs) {
                if (langId in config.languages.map { it.lowercase() }) {
                    return plugin to config
                }
            }
        }
        return null
    }

    /**
     * 获取所有已启用的 LSP 插件
     */
    fun getEnabledLspPlugins(): List<LspPluginInfo> = _lspPluginsFlow.value

    /**
     * 获取插件的安装状态
     */
    fun getInstallState(pluginId: String): LspPluginInstallState? = _installStatesFlow.value[pluginId]

    /**
     * 检查 LSP 工具链安装所需的 Linux 环境。
     */
    fun inspectToolchainEnvironment(): LspToolchainEnvironmentStatus {
        val linuxEnvironment = linuxEnvironmentProvider.get()
        val linuxAvailable = runCatching { linuxEnvironment.isAvailable() }.getOrDefault(false)
        if (!linuxAvailable) {
            return LspToolchainEnvironmentStatus(linuxAvailable = false)
        }
        val packageManager = runCatching { linuxEnvironment.resolveGuestPackageManager() }
            .getOrDefault(RootfsPackageManager.UNKNOWN)
        return LspToolchainEnvironmentStatus(
            linuxAvailable = true,
            packageManagerName = packageManager.displayName(),
            systemPackageManagerAvailable = GuestSystemPackageManager.isPackageManagerSupported(packageManager),
        )
    }

    /**
     * 安装 LSP 插件的工具链
     */
    suspend fun installToolchains(
        pluginId: String,
        progress: (LspInstallProgress) -> Unit
    ): Result<Unit> {
        val plugin = _lspPluginsFlow.value.find { it.pluginId == pluginId }
            ?: return Result.failure(IllegalArgumentException(Strings.lsp_plugin_error_plugin_not_found.strOr(context, pluginId)))

        val toolchains = plugin.toolchainConfigs
        if (toolchains.isEmpty()) {
            updateInstallState(pluginId) { it.copy(serverReady = true, lastError = null) }
            return Result.success(Unit)
        }

        logToolchainInstallEvent(
            plugin = plugin,
            level = PluginLogLevel.INFO,
            message = Strings.lsp_plugin_log_toolchain_install_started.strOr(
                context,
                plugin.pluginName,
                toolchains.size,
            ),
            eventCode = "lsp.toolchain.install_started",
            attributes = mapOf("toolchainCount" to toolchains.size.toString()),
        )
        updateInstallState(pluginId) { it.copy(serverReady = false, lastError = null) }

        for ((index, toolchain) in toolchains.withIndex()) {
            // 检查是否已安装
            if (toolchainInstaller.isInstalled(toolchain)) {
                Timber.tag(TAG).i("Toolchain ${toolchain.id} already installed")
                logToolchainInstallEvent(
                    plugin = plugin,
                    toolchain = toolchain,
                    level = PluginLogLevel.INFO,
                    message = Strings.lsp_plugin_log_toolchain_install_skipped.strOr(context, toolchain.name),
                    eventCode = "lsp.toolchain.install_skipped",
                )
                updateInstallState(pluginId) {
                    it.copy(
                        toolchainStates = it.toolchainStates + (toolchain.id to ToolchainInstallState.INSTALLED)
                    )
                }
                continue
            }

            updateInstallState(pluginId) {
                it.copy(
                    toolchainStates = it.toolchainStates + (toolchain.id to ToolchainInstallState.INSTALLING)
                )
            }
            logToolchainInstallEvent(
                plugin = plugin,
                toolchain = toolchain,
                level = PluginLogLevel.INFO,
                message = Strings.lsp_plugin_log_toolchain_installing.strOr(context, toolchain.name),
                eventCode = "lsp.toolchain.installing",
            )

            progress(
                LspInstallProgress(
                    phase = Strings.lsp_toolchain_phase_installing_toolchain.strOr(context, toolchain.name),
                    progress = index.toFloat() / toolchains.size,
                    toolchainId = toolchain.id
                )
            )

            val result = toolchainInstaller.install(toolchain) { innerProgress ->
                // 转发内部进度
                val overallProgress = (index.toFloat() + innerProgress.progress) / toolchains.size
                progress(innerProgress.copy(progress = overallProgress))
            }

            if (result.isFailure) {
                val failure = result.exceptionOrNull()
                val error = failure?.message ?: Strings.error_unknown.strOr(context)
                Timber.tag(TAG).e("Failed to install toolchain ${toolchain.id}: $error")
                logToolchainInstallEvent(
                    plugin = plugin,
                    toolchain = toolchain,
                    level = PluginLogLevel.ERROR,
                    message = Strings.lsp_plugin_log_toolchain_failed.strOr(context, toolchain.name, error),
                    eventCode = "lsp.toolchain.install_failed",
                    error = failure,
                )

                updateInstallState(pluginId) {
                    it.copy(
                        toolchainStates = it.toolchainStates + (toolchain.id to ToolchainInstallState.FAILED),
                        lastError = error
                    )
                }

                if (toolchain.required) {
                    return result
                }
            } else {
                logToolchainInstallEvent(
                    plugin = plugin,
                    toolchain = toolchain,
                    level = PluginLogLevel.INFO,
                    message = Strings.lsp_plugin_log_toolchain_installed.strOr(context, toolchain.name),
                    eventCode = "lsp.toolchain.installed",
                )
                updateInstallState(pluginId) {
                    it.copy(
                        toolchainStates = it.toolchainStates + (toolchain.id to ToolchainInstallState.INSTALLED)
                    )
                }
            }
        }

        updateInstallState(pluginId) { it.copy(serverReady = true, lastError = null) }
        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_all_installed.strOr(context),
                progress = 1.0f
            )
        )
        logToolchainInstallEvent(
            plugin = plugin,
            level = PluginLogLevel.INFO,
            message = Strings.lsp_plugin_log_toolchain_all_installed.strOr(context, plugin.pluginName),
            eventCode = "lsp.toolchain.install_complete",
            attributes = mapOf("toolchainCount" to toolchains.size.toString()),
        )

        return Result.success(Unit)
    }

    /**
     * 检查 LSP 插件是否就绪（所有必需工具链已安装）
     */
    suspend fun isPluginReady(pluginId: String): Boolean {
        val plugin = _lspPluginsFlow.value.find { it.pluginId == pluginId } ?: return false

        for (toolchain in plugin.toolchainConfigs) {
            if (toolchain.required && !toolchainInstaller.isInstalled(toolchain)) {
                return false
            }
        }
        return true
    }

    /**
     * 同步检查 LSP 插件是否就绪（基于缓存的安装状态）
     *
     * 注意：此方法基于 installStatesFlow 中的缓存状态，
     * 如果工具链是在应用外部安装的，可能不准确。
     * 对于精确检查，请使用 suspend 版本的 isPluginReady()。
     */
    fun isPluginReadySync(pluginId: String): Boolean = inspectPluginReadiness(pluginId).ready

    /**
     * 同步返回插件启动前诊断，供编辑器启动链路写日志和设置页展示。
     */
    fun inspectPluginReadiness(pluginId: String): LspPluginReadinessDiagnostic {
        val plugin = _lspPluginsFlow.value.find { it.pluginId == pluginId }
            ?: return LspPluginReadinessDiagnostic(ready = false)
        val installState = _installStatesFlow.value[pluginId]

        if (installState?.serverReady == true) {
            return LspPluginReadinessDiagnostic(ready = true)
        }

        val missingRequired = mutableListOf<String>()
        val failedRequired = mutableListOf<String>()
        plugin.toolchainConfigs
            .filter { toolchain -> toolchain.required }
            .forEach { toolchain ->
                when (installState?.toolchainStates?.get(toolchain.id)) {
                    ToolchainInstallState.INSTALLED -> Unit
                    ToolchainInstallState.FAILED -> failedRequired += toolchain.name
                    else -> missingRequired += toolchain.name
                }
            }

        return LspPluginReadinessDiagnostic(
            ready = missingRequired.isEmpty() && failedRequired.isEmpty(),
            missingRequiredToolchains = missingRequired.distinct(),
            failedRequiredToolchains = failedRequired.distinct(),
            lastError = installState?.lastError,
        )
    }

    fun markServerStartupFailed(pluginId: String, error: String) {
        updateInstallState(pluginId) {
            it.copy(
                serverReady = false,
                lastError = error,
            )
        }
    }

    fun markServerStartupSucceeded(pluginId: String) {
        startedPluginIds += pluginId
        updateInstallState(pluginId) {
            it.copy(
                serverReady = true,
                lastError = null,
            )
        }
    }

    fun reportUnexpectedServerExit(pluginId: String, error: String) {
        if (!startedPluginIds.remove(pluginId)) return
        scope.launch {
            val plugin = pluginManager.getEnabledPlugin(pluginId) ?: return@launch
            PluginLspSessionRegistry.closeAll(pluginId)
            val record = PluginFaultRecord(
                pluginId = pluginId,
                pluginVersion = plugin.manifest.version,
                phase = PluginFaultPhase.LSP,
                kind = PluginFaultKind.LSP_CRASH,
                message = error,
                timestampMillis = System.currentTimeMillis(),
                executionId = UUID.randomUUID().toString(),
            )
            pluginManager.quarantinePlugin(record).onFailure { failure ->
                Timber.tag(TAG).e(failure, "Failed to quarantine crashed LSP plugin %s", pluginId)
            }
        }
    }

    /**
     * 检查指定扩展名是否有可用的 LSP 支持
     */
    suspend fun hasLspSupportForExtension(extension: String): Boolean {
        val result = getServerConfigForExtension(extension) ?: return false
        return isPluginReady(result.first.pluginId)
    }

    private fun findServerConfig(
        detectedLanguageId: String? = null,
        predicate: (LspServerConfig) -> Boolean,
    ): Pair<LspPluginInfo, LspServerConfig>? {
        for (plugin in _lspPluginsFlow.value) {
            for (config in plugin.serverConfigs) {
                if (plugin.supportsServerActivation(config, detectedLanguageId) && predicate(config)) {
                    return plugin to config
                }
            }
        }
        return null
    }

    private fun matchesFileExtension(config: LspServerConfig, extension: String): Boolean {
        if (extension.isBlank()) return false
        return config.fileExtensions.any { it.equals(extension, ignoreCase = true) }
    }

    private fun matchesFilePattern(
        config: LspServerConfig,
        fileName: String,
        extension: String
    ): Boolean = config.filePatterns?.any { pattern ->
        matchGlobPattern(pattern, fileName) ||
            (extension.isNotBlank() && matchGlobPattern(pattern, ".$extension"))
    } == true

    private fun parseLspPluginInfo(plugin: InstalledPlugin): LspPluginInfo? {
        val contributions = plugin.manifest.contributions ?: return null
        val serverConfigs = contributions.languageServers
        if (serverConfigs.isNullOrEmpty()) return null

        return LspPluginInfo(
            pluginId = plugin.manifest.id,
            pluginName = plugin.manifest.name,
            pluginVersion = plugin.manifest.version,
            directory = plugin.directory,
            serverConfigs = serverConfigs,
            toolchainConfigs = contributions.toolchains ?: emptyList(),
            activationEvents = plugin.manifest.activationEvents ?: emptyList()
        )
    }

    private fun logToolchainInstallEvent(
        plugin: LspPluginInfo,
        level: PluginLogLevel,
        message: String,
        eventCode: String,
        toolchain: LspToolchainConfig? = null,
        error: Throwable? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val mergedAttributes = mutableMapOf(
            "pluginVersion" to plugin.pluginVersion,
        )
        if (toolchain != null) {
            mergedAttributes["toolchainId"] = toolchain.id
            mergedAttributes["toolchainName"] = toolchain.name
            mergedAttributes["toolchainType"] = toolchain.type
            mergedAttributes["toolchainRequired"] = toolchain.required.toString()
        }
        mergedAttributes.putAll(attributes)
        pluginLogManager.log(
            pluginId = plugin.pluginId,
            pluginName = plugin.pluginName,
            level = level,
            message = message,
            stackTrace = error?.stackTraceToString(),
            eventCode = eventCode,
            attributes = mergedAttributes,
        )
    }

    private suspend fun refreshPluginToolchainInstallState(plugin: LspPluginInfo) {
        if (plugin.toolchainConfigs.isEmpty()) {
            updateInstallState(plugin.pluginId) {
                it.copy(
                    toolchainStates = emptyMap(),
                    serverReady = true,
                    lastError = null,
                )
            }
            return
        }

        val previousState = _installStatesFlow.value[plugin.pluginId]
        val refreshedStates = plugin.toolchainConfigs.associate { toolchain ->
            val previousToolchainState = previousState?.toolchainStates?.get(toolchain.id)
            val refreshedState = when {
                previousToolchainState == ToolchainInstallState.INSTALLING -> ToolchainInstallState.INSTALLING
                toolchainInstaller.isInstalled(toolchain) -> ToolchainInstallState.INSTALLED
                previousToolchainState == ToolchainInstallState.FAILED -> ToolchainInstallState.FAILED
                else -> ToolchainInstallState.NOT_INSTALLED
            }
            toolchain.id to refreshedState
        }
        val requiredReady = plugin.toolchainConfigs
            .filter { toolchain -> toolchain.required }
            .all { toolchain -> refreshedStates[toolchain.id] == ToolchainInstallState.INSTALLED }

        updateInstallState(plugin.pluginId) {
            it.copy(
                toolchainStates = refreshedStates,
                serverReady = requiredReady,
                lastError = if (requiredReady) null else it.lastError,
            )
        }
    }

    private fun updateInstallState(pluginId: String, update: (LspPluginInstallState) -> LspPluginInstallState) {
        _installStatesFlow.update { states ->
            val current = states[pluginId] ?: LspPluginInstallState(
                pluginId = pluginId,
                toolchainStates = emptyMap(),
                serverReady = false,
            )
            states + (pluginId to update(current))
        }
    }

    private fun matchGlobPattern(pattern: String, filename: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return try {
            filename.matches(Regex(regex, RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            false
        }
    }
}
