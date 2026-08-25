package com.wuxianggujun.tinaide.plugin.marketplace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginMarketplaceUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val plugins: List<PluginSummary> = emptyList(),
    val selectedCategory: String? = null,
    val searchQuery: String = "",
    val sortType: PluginSortType = PluginSortType.UPDATED,
    val currentPage: Int = 1,
    val hasMorePages: Boolean = true,
    val error: String? = null,
    val downloadingPlugins: Map<String, Float> = emptyMap(),
    val installedPlugins: Set<String> = emptySet(),
    val updatablePlugins: Set<String> = emptySet(),
    val selectedPluginId: String? = null,
    val pendingInstall: MarketplacePendingPluginInstall? = null,
)

class PluginMarketplaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PluginMarketplaceRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(PluginMarketplaceUiState())
    val uiState: StateFlow<PluginMarketplaceUiState> = _uiState.asStateFlow()

    // 进行中的下载/安装任务：用于支持用户主动取消（取消协程会触发 OkHttp Call.cancel 立即断开连接）。
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        observeInstalledPlugins()
        loadPlugins()
    }

    private fun observeInstalledPlugins() {
        viewModelScope.launch {
            repository.pluginStateFlow.collect {
                syncInstalledPluginState()
            }
        }
    }

    fun loadPlugins(refresh: Boolean = true) {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    currentPage = if (refresh) 1 else it.currentPage
                )
            }

            val state = _uiState.value
            val result = repository.listPlugins(
                page = if (refresh) 1 else state.currentPage,
                category = state.selectedCategory,
                search = state.searchQuery.takeIf { it.isNotBlank() },
                sort = state.sortType.value
            )

            when (result) {
                is ApiResult.Success -> {
                    val data = result.data
                    val newPlugins = if (refresh) {
                        data.plugins
                    } else {
                        state.plugins + data.plugins
                    }
                    val installState = repository.resolveInstallState(newPlugins)

                    _uiState.update {
                        PluginMarketplaceSelectionSupport.applyInstallState(
                            state = it.copy(
                                isLoading = false,
                                plugins = newPlugins,
                                currentPage = data.pagination.page,
                                hasMorePages = data.pagination.page < data.pagination.totalPages,
                            ),
                            installedPlugins = installState.installedPlugins,
                            updatablePlugins = installState.updatablePlugins,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMorePages) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            val result = repository.listPlugins(
                page = state.currentPage + 1,
                category = state.selectedCategory,
                search = state.searchQuery.takeIf { it.isNotBlank() },
                sort = state.sortType.value
            )

            when (result) {
                is ApiResult.Success -> {
                    val data = result.data
                    val newPlugins = state.plugins + data.plugins
                    val installState = repository.resolveInstallState(newPlugins)

                    _uiState.update {
                        PluginMarketplaceSelectionSupport.applyInstallState(
                            state = it.copy(
                                isLoadingMore = false,
                                plugins = newPlugins,
                                currentPage = data.pagination.page,
                                hasMorePages = data.pagination.page < data.pagination.totalPages,
                            ),
                            installedPlugins = installState.installedPlugins,
                            updatablePlugins = installState.updatablePlugins,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    private fun syncInstalledPluginState() {
        _uiState.update { state ->
            val installState = repository.resolveInstallState(state.plugins)
            PluginMarketplaceSelectionSupport.applyInstallState(
                state = state,
                installedPlugins = installState.installedPlugins,
                updatablePlugins = installState.updatablePlugins,
            )
        }
    }

    fun setCategory(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
        loadPlugins()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun search() {
        loadPlugins()
    }

    fun setSortType(sortType: PluginSortType) {
        _uiState.update { it.copy(sortType = sortType) }
        loadPlugins()
    }

    fun installPlugin(plugin: PluginSummary) {
        val pluginId = plugin.pluginId
        if (_uiState.value.downloadingPlugins.containsKey(pluginId)) return
        if (downloadJobs.isNotEmpty()) return
        if (_uiState.value.pendingInstall != null) return
        if (repository.getInstalledVersion(pluginId) == plugin.latestVersion) return

        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(downloadingPlugins = it.downloadingPlugins + (pluginId to 0f))
            }

            val preparation = try {
                repository.preparePluginInstall(
                    pluginId = pluginId,
                    version = plugin.latestVersion,
                    onProgress = { downloaded, total ->
                        val progress = if (total > 0) downloaded.toFloat() / total else 0f
                        _uiState.update {
                            it.copy(
                                downloadingPlugins = it.downloadingPlugins + (pluginId to progress)
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                // 用户主动取消：仅清除进行中状态，不弹失败提示，可重新点击下载。
                _uiState.update {
                    it.copy(downloadingPlugins = it.downloadingPlugins - pluginId)
                }
                throw e
            }

            val pending = preparation.getOrNull()
            if (pending == null) {
                applyInstallResult(
                    pluginId,
                    Result.failure<Any?>(checkNotNull(preparation.exceptionOrNull())),
                )
            } else if (pending.needsUserConfirmation) {
                _uiState.update {
                    it.copy(
                        downloadingPlugins = it.downloadingPlugins - pluginId,
                        pendingInstall = pending,
                    )
                }
            } else {
                applyInstallResult(pluginId, repository.confirmPluginInstall(pending))
            }
        }
        downloadJobs[pluginId] = job
        job.invokeOnCompletion {
            if (downloadJobs[pluginId] === job) downloadJobs.remove(pluginId)
        }
    }

    /**
     * 取消进行中的插件下载/安装。取消协程会触发 OkHttp `Call.cancel()` 立即断开网络连接，
     * 用户可在网络恢复后重新点击下载。
     */
    fun cancelInstall(pluginId: String) {
        downloadJobs.remove(pluginId)?.cancel(CancellationException("Plugin download cancelled by user"))
        _uiState.update {
            it.copy(downloadingPlugins = it.downloadingPlugins - pluginId)
        }
    }

    fun confirmPendingInstall() {
        val pending = _uiState.value.pendingInstall ?: return
        _uiState.update {
            it.copy(
                pendingInstall = null,
                downloadingPlugins = it.downloadingPlugins + (pending.requestedPluginId to 0f),
            )
        }
        val job = viewModelScope.launch {
            applyInstallResult(
                pending.requestedPluginId,
                repository.confirmPluginInstall(pending),
            )
        }
        downloadJobs[pending.requestedPluginId] = job
        job.invokeOnCompletion {
            if (downloadJobs[pending.requestedPluginId] === job) {
                downloadJobs.remove(pending.requestedPluginId)
            }
        }
    }

    fun dismissPendingInstall() {
        val pending = _uiState.value.pendingInstall ?: return
        repository.discardPendingInstall(pending)
        _uiState.update { it.copy(pendingInstall = null) }
    }

    private fun applyInstallResult(pluginId: String, result: Result<*>) {
        _uiState.update {
            val throwable = result.exceptionOrNull()
            val reason = throwable?.message?.trim()?.takeIf { message -> message.isNotBlank() }
                ?: throwable?.cause?.message?.trim()?.takeIf { message -> message.isNotBlank() }
                ?: throwable?.toString()
            it.copy(
                downloadingPlugins = it.downloadingPlugins - pluginId,
                installedPlugins = if (result.isSuccess) it.installedPlugins + pluginId else it.installedPlugins,
                updatablePlugins = if (result.isSuccess) it.updatablePlugins - pluginId else it.updatablePlugins,
                error = reason?.let { message -> Strings.toast_plugins_install_failed.str(message) },
            )
        }
        if (result.isSuccess) loadPlugins()
    }

    override fun onCleared() {
        _uiState.value.pendingInstall?.let(repository::discardPendingInstall)
        super.onCleared()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun showPluginDetails(plugin: PluginSummary) {
        _uiState.update { it.copy(selectedPluginId = plugin.pluginId) }
    }

    fun closePluginDetails() {
        _uiState.update { it.copy(selectedPluginId = null) }
    }
}
