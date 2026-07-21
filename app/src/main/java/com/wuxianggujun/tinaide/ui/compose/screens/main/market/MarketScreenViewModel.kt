package com.wuxianggujun.tinaide.ui.compose.screens.main.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.PackageManagerImpl
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.InstallProgressEvent
import com.wuxianggujun.tinaide.core.packages.model.InstallResult
import com.wuxianggujun.tinaide.core.packages.model.PackageInstallState
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.user.DownloadHistoryItem
import com.wuxianggujun.tinaide.core.user.FavoritePlugin
import com.wuxianggujun.tinaide.core.user.UserContentRepository
import com.wuxianggujun.tinaide.plugin.marketplace.MarketplacePendingPluginInstall
import com.wuxianggujun.tinaide.plugin.marketplace.PluginDetail
import com.wuxianggujun.tinaide.plugin.marketplace.PluginMarketplaceRepository
import com.wuxianggujun.tinaide.plugin.marketplace.PluginSummary
import com.wuxianggujun.tinaide.ui.compose.screens.packages.PackageInstallUiStateSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 市场屏幕 ViewModel
 *
 * 聚合插件市场和包管理的数据。
 */
class MarketScreenViewModel(
    application: Application,
    private val userContentRepository: UserContentRepository,
    private val configManager: IConfigManager,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val pluginRepository = PluginMarketplaceRepository(application.applicationContext)
    private val packageManager: PackageManager

    init {
        val apiClient = PackageApiClient.getInstance(application)
        val installStateStore = LocalInstallStateStore(application)
        val prootEnv = PRootEnvironment(application, configManager)
        packageManager = PackageManagerImpl(application, apiClient, installStateStore, prootEnv = prootEnv)
    }

    private val _pluginState = MutableStateFlow(PluginState())
    val pluginState: StateFlow<PluginState> = _pluginState.asStateFlow()

    private val _packageState = MutableStateFlow(PackageState())
    val packageState: StateFlow<PackageState> = _packageState.asStateFlow()

    // 进行中的下载/安装任务：用于支持用户主动取消（取消协程会触发 OkHttp Call.cancel 立即断开连接）。
    private val pluginDownloadJobs = mutableMapOf<String, Job>()
    private val packageInstallJobs = mutableMapOf<String, Job>()
    private var pendingDownloadHistoryPlugin: PluginSummary? = null

    init {
        observeInstalledPlugins()
        loadPlugins()
        loadPackages()
        loadFavoritedPlugins()
    }

    private fun observeInstalledPlugins() {
        viewModelScope.launch {
            pluginRepository.pluginStateFlow.collect {
                syncPluginInstallState()
            }
        }
    }

    private fun loadFavoritedPlugins() {
        viewModelScope.launch {
            userContentRepository.getFavoritesFlow().collect { favorites ->
                val favoritedIds = favorites.map { it.pluginId }.toSet()
                _pluginState.update { it.copy(favoritedPlugins = favoritedIds) }
            }
        }
    }

    private fun loadPlugins() {
        viewModelScope.launch {
            _pluginState.update { it.copy(isLoading = true, error = null) }

            when (val result = pluginRepository.listPlugins(page = 1)) {
                is ApiResult.Success -> {
                    val plugins = result.data.plugins
                    val installState = pluginRepository.resolveInstallState(plugins)

                    _pluginState.update { state ->
                        MarketScreenPluginStateSupport.applyInstallState(
                            state = state.copy(
                                isLoading = false,
                                plugins = plugins,
                                error = null
                            ),
                            installedPlugins = installState.installedPlugins,
                            updatablePlugins = installState.updatablePlugins,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _pluginState.update { it.copy(isLoading = false, error = result.message) }
                }
                is ApiResult.NetworkError -> {
                    _pluginState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    private fun loadPackages() {
        viewModelScope.launch {
            _packageState.update { it.copy(isLoading = true, error = null) }

            packageManager.getAvailablePackages().onSuccess { packages ->
                val installStates = packages.associate { pkg ->
                    pkg.id to packageManager.getInstallState(pkg.id)
                }

                _packageState.update {
                    it.copy(
                        isLoading = false,
                        packages = packages,
                        filteredPackages = packages,
                        installStates = installStates,
                        error = null
                    )
                }
            }.onFailure { e ->
                _packageState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: Strings.pkg_manager_load_failed.strOr(appContext)
                    )
                }
            }
        }
    }

    fun retryLoadPlugins() {
        loadPlugins()
    }
    fun retryLoadPackages() {
        loadPackages()
    }

    fun installPlugin(plugin: PluginSummary) {
        val pluginId = plugin.pluginId
        val installedVersion = pluginRepository.getInstalledVersion(pluginId)
        if (_pluginState.value.downloadingPlugins.containsKey(pluginId)) return
        if (pluginDownloadJobs.isNotEmpty()) return
        if (_pluginState.value.pendingInstall != null) return
        if (installedVersion != null && installedVersion == plugin.latestVersion) return

        val job = viewModelScope.launch {
            _pluginState.update {
                it.copy(downloadingPlugins = it.downloadingPlugins + (pluginId to 0f))
            }

            val preparation = try {
                pluginRepository.preparePluginInstall(
                    pluginId = pluginId,
                    version = plugin.latestVersion,
                    onProgress = { downloaded, total ->
                        val progress = if (total > 0) downloaded.toFloat() / total else 0f
                        _pluginState.update {
                            it.copy(downloadingPlugins = it.downloadingPlugins + (pluginId to progress))
                        }
                    }
                )
            } catch (e: CancellationException) {
                // 用户主动取消：仅清除进行中状态，不弹失败提示，可重新点击下载。
                _pluginState.update {
                    it.copy(downloadingPlugins = it.downloadingPlugins - pluginId)
                }
                throw e
            }

            val pending = preparation.getOrNull()
            if (pending == null) {
                applyPluginInstallResult(
                    pluginId,
                    Result.failure<Any?>(checkNotNull(preparation.exceptionOrNull())),
                )
            } else if (pending.needsUserConfirmation) {
                pendingDownloadHistoryPlugin = plugin
                _pluginState.update {
                    it.copy(
                        downloadingPlugins = it.downloadingPlugins - pluginId,
                        pendingInstall = pending,
                    )
                }
            } else {
                applyPluginInstallResult(
                    pluginId = pluginId,
                    result = pluginRepository.confirmPluginInstall(pending),
                    historyPlugin = plugin,
                )
            }
        }
        pluginDownloadJobs[pluginId] = job
        job.invokeOnCompletion {
            if (pluginDownloadJobs[pluginId] === job) pluginDownloadJobs.remove(pluginId)
        }
    }

    /**
     * 取消进行中的插件下载/安装。取消协程会触发 OkHttp `Call.cancel()` 立即断开网络连接，
     * 用户可在网络恢复后重新点击下载。
     */
    fun cancelPluginInstall(pluginId: String) {
        pluginDownloadJobs.remove(pluginId)?.cancel(CancellationException("Plugin download cancelled by user"))
        _pluginState.update {
            it.copy(downloadingPlugins = it.downloadingPlugins - pluginId)
        }
    }

    fun confirmPendingPluginInstall() {
        val pending = _pluginState.value.pendingInstall ?: return
        val historyPlugin = pendingDownloadHistoryPlugin
        pendingDownloadHistoryPlugin = null
        _pluginState.update {
            it.copy(
                pendingInstall = null,
                downloadingPlugins = it.downloadingPlugins + (pending.requestedPluginId to 0f),
            )
        }
        val job = viewModelScope.launch {
            applyPluginInstallResult(
                pluginId = pending.requestedPluginId,
                result = pluginRepository.confirmPluginInstall(pending),
                historyPlugin = historyPlugin,
            )
        }
        pluginDownloadJobs[pending.requestedPluginId] = job
        job.invokeOnCompletion {
            if (pluginDownloadJobs[pending.requestedPluginId] === job) {
                pluginDownloadJobs.remove(pending.requestedPluginId)
            }
        }
    }

    fun dismissPendingPluginInstall() {
        val pending = _pluginState.value.pendingInstall ?: return
        pendingDownloadHistoryPlugin = null
        pluginRepository.discardPendingInstall(pending)
        _pluginState.update { it.copy(pendingInstall = null) }
    }

    private fun applyPluginInstallResult(
        pluginId: String,
        result: Result<*>,
        historyPlugin: PluginSummary? = null,
    ) {
        _pluginState.update {
            val throwable = result.exceptionOrNull()
            val reason = throwable?.message?.trim()?.takeIf { message -> message.isNotBlank() }
                ?: throwable?.cause?.message?.trim()?.takeIf { message -> message.isNotBlank() }
                ?: throwable?.toString()
            it.copy(
                downloadingPlugins = it.downloadingPlugins - pluginId,
                installedPlugins = if (result.isSuccess) it.installedPlugins + pluginId else it.installedPlugins,
                updatablePlugins = if (result.isSuccess) it.updatablePlugins - pluginId else it.updatablePlugins,
                message = if (result.isSuccess) {
                    Strings.plugin_marketplace_install_success.str()
                } else if (reason != null) {
                    Strings.toast_plugins_install_failed.str(reason)
                } else {
                    Strings.plugin_marketplace_install_failed.str()
                },
            )
        }
        if (result.isSuccess) {
            (historyPlugin ?: _pluginState.value.plugins.find { it.pluginId == pluginId })
                ?.let(::recordPluginDownload)
            loadPlugins()
        }
    }

    private fun loadPluginDetail(pluginId: String) {
        viewModelScope.launch {
            when (val result = pluginRepository.getPluginDetail(pluginId)) {
                is ApiResult.Success -> {
                    applyPluginDetail(result.data)
                }
                is ApiResult.Error -> {
                    _pluginState.update { state ->
                        if (state.selectedPluginId != pluginId) return@update state
                        state.copy(
                            isPluginDetailLoading = false,
                            error = result.message
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _pluginState.update { state ->
                        if (state.selectedPluginId != pluginId) return@update state
                        state.copy(
                            isPluginDetailLoading = false,
                            error = result.message
                        )
                    }
                }
            }
        }
    }

    private fun applyPluginDetail(detail: PluginDetail) {
        _pluginState.update { state ->
            if (state.selectedPluginId != detail.pluginId) return@update state
            val newPlugins = state.plugins.map { plugin ->
                if (plugin.pluginId == detail.pluginId) {
                    plugin.merge(detail)
                } else {
                    plugin
                }
            }
            state.copy(
                plugins = newPlugins,
                selectedPluginDetail = detail,
                isPluginDetailLoading = false,
                error = null
            )
        }
    }

    private fun syncPluginInstallState() {
        _pluginState.update { state ->
            val installState = pluginRepository.resolveInstallState(state.plugins)
            MarketScreenPluginStateSupport.applyInstallState(
                state = state,
                installedPlugins = installState.installedPlugins,
                updatablePlugins = installState.updatablePlugins,
            )
        }
    }

    private fun recordPluginDownload(plugin: PluginSummary) {
        viewModelScope.launch {
            userContentRepository.addDownloadHistory(
                DownloadHistoryItem(
                    id = java.util.UUID.randomUUID().toString(),
                    itemType = "plugin",
                    itemId = plugin.pluginId,
                    version = plugin.latestVersion,
                    downloadedAt = System.currentTimeMillis().toString(),
                    fileSize = null,
                    synced = false
                )
            )
        }
    }

    fun togglePluginFavorite(pluginId: String) {
        viewModelScope.launch {
            if (_pluginState.value.favoritedPlugins.contains(pluginId)) {
                removePluginFromFavorites(pluginId)
            } else {
                addPluginToFavorites(pluginId)
            }
        }
    }

    private fun addPluginToFavorites(pluginId: String) {
        viewModelScope.launch {
            val plugin = _pluginState.value.plugins.find { it.pluginId == pluginId }
            val favorite = FavoritePlugin(
                id = java.util.UUID.randomUUID().toString(),
                pluginId = pluginId,
                name = plugin?.name ?: pluginId,
                description = plugin?.description,
                iconUrl = plugin?.iconUrl,
                category = plugin?.category,
                tags = plugin?.tags,
                latestVersion = plugin?.latestVersion,
                addedAt = System.currentTimeMillis().toString(),
                synced = false
            )
            userContentRepository.addFavorite(favorite).onFailure { error ->
                _pluginState.update {
                    it.copy(message = error.message ?: Strings.market_favorite_failed.str())
                }
            }
        }
    }

    private fun removePluginFromFavorites(pluginId: String) {
        viewModelScope.launch {
            userContentRepository.removeFavorite(pluginId).onFailure { error ->
                _pluginState.update {
                    it.copy(message = error.message ?: Strings.market_unfavorite_failed.str())
                }
            }
        }
    }

    fun installPackage(packageId: String) {
        if (packageId in _packageState.value.installingPackages) return
        if (packageInstallJobs.containsKey(packageId)) return
        val job = viewModelScope.launch {
            val pkg = _packageState.value.packages.find { p -> p.id == packageId } ?: run {
                _packageState.update {
                    it.copy(
                        message = Strings.market_package_install_failed.strOr(
                            appContext,
                            Strings.pkg_manager_package_not_found.strOr(appContext, packageId)
                        )
                    )
                }
                return@launch
            }
            if (packageId in _packageState.value.installingPackages) return@launch
            val platform = PackageInstallUiStateSupport.resolvePreferredInstallPlatform(pkg) ?: run {
                _packageState.update {
                    it.copy(
                        message = Strings.market_package_install_failed.strOr(
                            appContext,
                            Strings.pkg_manager_package_not_installable.strOr(appContext, pkg.name)
                        )
                    )
                }
                return@launch
            }

            val plan = packageManager.previewInstallPlan(packageId, platform).getOrElse { error ->
                _packageState.update {
                    it.copy(
                        message = Strings.market_package_install_failed.strOr(
                            appContext,
                            error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                        )
                    )
                }
                return@launch
            }

            val dependenciesToInstall = plan.packages
                .filterNot { it.isRoot }
                .filterNot { it.isAlreadyInstalled }
            if (dependenciesToInstall.isNotEmpty()) {
                _packageState.update {
                    it.copy(
                        pendingInstallPlan = MarketPackageInstallPlan(
                            packageId = packageId,
                            packageInfo = pkg,
                            platform = platform,
                            plan = plan
                        ),
                        message = null
                    )
                }
                return@launch
            }

            installPackageWithoutPreview(pkg, platform)
        }
        packageInstallJobs[packageId] = job
        job.invokeOnCompletion { packageInstallJobs.remove(packageId) }
    }

    fun confirmPackageInstall() {
        val pendingPlan = _packageState.value.pendingInstallPlan ?: return
        val packageId = pendingPlan.packageId
        val pkg = _packageState.value.packages.find { it.id == packageId }
            ?: pendingPlan.packageInfo
        _packageState.update { it.copy(pendingInstallPlan = null) }
        if (packageInstallJobs.containsKey(packageId)) return
        val job = viewModelScope.launch {
            installPackageWithoutPreview(pkg, pendingPlan.platform)
        }
        packageInstallJobs[packageId] = job
        job.invokeOnCompletion { packageInstallJobs.remove(packageId) }
    }

    fun dismissPackageInstallConfirm() {
        _packageState.update { it.copy(pendingInstallPlan = null) }
    }

    /**
     * 取消进行中的包下载/安装。取消协程会触发 OkHttp `Call.cancel()` 立即断开网络连接，
     * 用户可在网络恢复后重新点击下载。
     */
    fun cancelPackageInstall(packageId: String) {
        packageInstallJobs.remove(packageId)?.cancel(CancellationException("Package install cancelled by user"))
        _packageState.update {
            it.copy(installingPackages = it.installingPackages - packageId)
        }
    }

    private suspend fun installPackageWithoutPreview(
        pkg: GUIPackage,
        platform: com.wuxianggujun.tinaide.core.packages.model.Platform
    ) {
        val packageId = pkg.id
        if (packageId in _packageState.value.installingPackages) return

        _packageState.update {
            it.copy(
                installingPackages = it.installingPackages + (packageId to 0f),
                pendingInstallPlan = null,
                message = null
            )
        }

        val result = try {
            packageManager.install(packageId, platform) { event ->
                _packageState.update { state ->
                    state.copy(
                        installingPackages = updatePackageInstallProgress(
                            current = state.installingPackages,
                            packageId = packageId,
                            event = event
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            // 用户主动取消：清除进行中状态，不弹失败提示，由协程框架向上抛出。
            _packageState.update {
                it.copy(installingPackages = it.installingPackages - packageId)
            }
            throw e
        } catch (throwable: Throwable) {
            InstallResult.Failure(
                packageId = packageId,
                error = com.wuxianggujun.tinaide.core.packages.model.InstallError.UnknownError(
                    throwable.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                )
            )
        }
        when (result) {
            is InstallResult.Success -> {
                refreshPackageState(packageId)
                recordPackageDownload(pkg, result.version)
                loadPackages()
                _packageState.update {
                    it.copy(
                        installingPackages = it.installingPackages - packageId,
                        message = Strings.market_package_install_success.strOr(appContext, pkg.name)
                    )
                }
            }
            is InstallResult.Failure -> {
                _packageState.update {
                    it.copy(
                        installingPackages = it.installingPackages - packageId,
                        message = Strings.market_package_install_failed.strOr(
                            appContext,
                            result.error.toDisplayMessage()
                        )
                    )
                }
            }
        }
    }

    private fun updatePackageInstallProgress(
        current: Map<String, Float>,
        packageId: String,
        event: InstallProgressEvent
    ): Map<String, Float> {
        val progress = PackageInstallUiStateSupport.progressFromEvent(event) ?: current[packageId] ?: 0f
        return current + (packageId to progress.coerceIn(0f, 1f))
    }

    private fun recordPackageDownload(
        pkg: GUIPackage,
        version: String,
    ) {
        viewModelScope.launch {
            userContentRepository.addDownloadHistory(
                DownloadHistoryItem(
                    id = java.util.UUID.randomUUID().toString(),
                    itemType = "package",
                    itemId = pkg.id,
                    version = version,
                    downloadedAt = System.currentTimeMillis().toString(),
                    fileSize = null,
                    synced = false
                )
            )
        }
    }

    private suspend fun refreshPackageState(packageId: String) {
        val newState = packageManager.getInstallState(packageId)
        _packageState.update {
            it.copy(installStates = it.installStates + (packageId to newState))
        }
    }

    fun clearPluginMessage() {
        _pluginState.update { it.copy(message = null) }
    }

    fun clearPackageMessage() {
        _packageState.update { it.copy(message = null) }
    }

    fun selectPlugin(plugin: PluginSummary) {
        _pluginState.update {
            it.copy(
                selectedPluginId = plugin.pluginId,
                selectedPluginDetail = null,
                isPluginDetailLoading = true
            )
        }
        loadPluginDetail(plugin.pluginId)
    }

    fun closePluginDetails() {
        _pluginState.update {
            it.copy(
                selectedPluginId = null,
                selectedPluginDetail = null,
                isPluginDetailLoading = false
            )
        }
    }

    override fun onCleared() {
        pendingDownloadHistoryPlugin = null
        _pluginState.value.pendingInstall?.let(pluginRepository::discardPendingInstall)
        super.onCleared()
    }
}

private fun PluginSummary.merge(detail: PluginDetail): PluginSummary = copy(
    name = detail.name,
    description = detail.description,
    category = detail.category,
    tags = detail.tags,
    iconUrl = detail.iconUrl,
    publisher = detail.publisher,
    latestVersion = detail.versions.firstOrNull()?.version ?: latestVersion,
    updatedAt = detail.updatedAt
)

data class PluginState(
    val isLoading: Boolean = false,
    val plugins: List<PluginSummary> = emptyList(),
    val installedPlugins: Set<String> = emptySet(),
    val updatablePlugins: Set<String> = emptySet(),
    val favoritedPlugins: Set<String> = emptySet(),
    val downloadingPlugins: Map<String, Float> = emptyMap(),
    val selectedPluginId: String? = null,
    val selectedPluginDetail: PluginDetail? = null,
    val isPluginDetailLoading: Boolean = false,
    val pendingInstall: MarketplacePendingPluginInstall? = null,
    val error: String? = null,
    val message: String? = null
)

data class PackageState(
    val isLoading: Boolean = false,
    val packages: List<GUIPackage> = emptyList(),
    val filteredPackages: List<GUIPackage> = emptyList(),
    val installStates: Map<String, PackageInstallState> = emptyMap(),
    val installingPackages: Map<String, Float> = emptyMap(),
    val pendingInstallPlan: MarketPackageInstallPlan? = null,
    val error: String? = null,
    val message: String? = null
)

data class MarketPackageInstallPlan(
    val packageId: String,
    val packageInfo: GUIPackage,
    val platform: com.wuxianggujun.tinaide.core.packages.model.Platform,
    val plan: PackageInstallPlan
)
