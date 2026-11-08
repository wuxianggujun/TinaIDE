package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadata
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadataReader
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.PackagePlatformKey
import com.wuxianggujun.tinaide.core.packages.UpdateInfo
import com.wuxianggujun.tinaide.core.packages.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class PackageManagerViewModel(
    private val appContext: Context,
    private val packageManager: PackageManager,
    private val installedOnly: Boolean = false,
) : ViewModel() {

    private companion object {
        private const val TAG = "PackageManagerViewModel"
    }

    private val _uiState = MutableStateFlow(PackageManagerUiState())
    val uiState: StateFlow<PackageManagerUiState> = _uiState.asStateFlow()

    private val _filterState = MutableStateFlow(PackageFilterState(installedOnly = installedOnly))
    val filterState: StateFlow<PackageFilterState> = _filterState.asStateFlow()

    private val _dialogState = MutableStateFlow<PackageDialogState?>(null)
    val dialogState: StateFlow<PackageDialogState?> = _dialogState.asStateFlow()

    // 进行中的安装任务：用于支持用户主动取消（取消协程会触发 OkHttp Call.cancel 立即断开连接）。
    private var installJob: Job? = null
    private var loadJob: Job? = null

    private fun launchPackageOperation(block: suspend () -> Unit) {
        if (installJob?.isCompleted == false) return
        loadJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Package operation failed")
                _dialogState.value = null
                _uiState.update { it.copy(error = Strings.pkg_manager_load_failed.strOr(appContext)) }
            } finally {
                loadPackages()
            }
        }
        installJob = job
        job.invokeOnCompletion { if (installJob === job) installJob = null }
        job.start()
    }

    init {
        loadPackages()
    }

    fun loadPackages() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val availablePackages = if (installedOnly) emptyList() else packageManager.getAllAvailablePackages()
                    .getOrElse { throwable ->
                        Timber.tag(TAG).w(throwable, "Failed to load available packages; falling back to installed packages")
                        emptyList()
                    }
                val installedPackages = packageManager.getInstalledPackages()
                val installedMetadata = buildInstalledMetadataMap(installedPackages)

                // 将 InstalledPackageInfo 转换为 GUIPackage
                val packages = mergeAvailableAndInstalledPackages(
                    availablePackages = availablePackages,
                    installedPackages = installedPackages,
                    installedMetadata = installedMetadata
                )

                val installStates = packages.associate { pkg ->
                    pkg.id to packageManager.getInstallState(pkg.id)
                }

                val filter = _filterState.value
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        packages = packages,
                        filteredPackages = filterPackages(packages, installStates, filter),
                        installStates = installStates,
                        installedMetadata = installedMetadata,
                        availableUpdates = it.availableUpdates.filter { (key, _) ->
                            installStates[key.packageId]?.forPlatform(key.platform) is PlatformInstallState.UpdateAvailable
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to load packages")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = Strings.pkg_manager_load_failed.strOr(appContext)
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _filterState.update { it.copy(searchQuery = query) }
        applyFilter()
    }

    private fun applyFilter() {
        val state = _uiState.value
        val filter = _filterState.value
        _uiState.update {
            it.copy(filteredPackages = filterPackages(state.packages, state.installStates, filter))
        }
    }

    private fun filterPackages(
        packages: List<GUIPackage>,
        installStates: Map<String, PackageInstallState>,
        filter: PackageFilterState
    ): List<GUIPackage> {
        return packages.filter { pkg -> filter.matches(pkg, installStates[pkg.id] ?: PackageInstallState()) }
    }

    fun refreshPackages() {
        viewModelScope.launch {
            if (!installedOnly) packageManager.refreshCache()
            loadPackages()
        }
    }

    fun installPackage(packageId: String, platform: Platform) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: run {
            _dialogState.value = PackageDialogState.InstallComplete(
                packageId = packageId,
                result = InstallResult.Failure(
                    packageId = packageId,
                    error = InstallError.UnknownError(
                        Strings.pkg_manager_package_not_found.strOr(appContext, packageId)
                    )
                )
            )
            return
        }
        val installPlatform = PackageInstallUiStateSupport.resolveAvailableInstallPlatform(pkg, platform) ?: run {
            _dialogState.value = PackageDialogState.InstallComplete(
                packageId = packageId,
                result = InstallResult.Failure(
                    packageId = packageId,
                    error = InstallError.UnknownError(
                        Strings.pkg_manager_package_not_installable.strOr(appContext, packageId)
                    )
                )
            )
            return
        }

        launchPackageOperation {
            val plan = packageManager.previewInstallPlan(packageId, installPlatform).getOrElse { error ->
                _dialogState.value = PackageDialogState.InstallComplete(
                    packageId = packageId,
                    result = InstallResult.Failure(
                        packageId = packageId,
                        error = InstallError.UnknownError(
                            error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                        )
                    )
                )
                return@launchPackageOperation
            }
            val dependenciesToInstall = plan.packages.filterNot { it.isRoot }
            if (dependenciesToInstall.isNotEmpty()) {
                _dialogState.value = PackageDialogState.InstallConfirm(
                    packageId = packageId,
                    packageInfo = pkg,
                    platform = installPlatform,
                    plan = plan
                )
                return@launchPackageOperation
            }

            startInstall(pkg, installPlatform)
        }
    }

    fun confirmInstall(packageId: String, platform: Platform) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: return
        val installPlatform = PackageInstallUiStateSupport.resolveAvailableInstallPlatform(pkg, platform) ?: return
        launchPackageOperation {
            startInstall(pkg, installPlatform)
        }
    }

    /**
     * 取消进行中的安装。取消协程会触发 OkHttp `Call.cancel()` 立即断开网络连接，
     * 关闭对话框，用户可在网络恢复后重新点击下载。
     */
    fun cancelInstall() {
        installJob?.cancel(CancellationException("Package install cancelled by user"))
        if (_dialogState.value !is PackageDialogState.BatchInstalling &&
            _dialogState.value !is PackageDialogState.BatchUpdating &&
            _dialogState.value !is PackageDialogState.BatchUninstalling) _dialogState.value = null
    }

    private suspend fun startInstall(pkg: GUIPackage, installPlatform: Platform) {
        val packageId = pkg.id
        _dialogState.value = PackageDialogState.Installing(
            packageId = packageId,
            packageName = pkg.name,
            platform = installPlatform,
            event = InstallProgressEvent.Preparing(
                Strings.pkg_manager_progress_starting.strOr(appContext)
            )
        )

        val result = try {
            packageManager.install(packageId, installPlatform) { event ->
                _dialogState.update { current ->
                    if (current is PackageDialogState.Installing && current.packageId == packageId) {
                        current.copy(event = event)
                    } else {
                        current
                    }
                }
            }
        } catch (e: CancellationException) {
            // 用户主动取消：对话框已由 cancelInstall 关闭，向上抛出由协程框架处理。
            throw e
        } catch (throwable: Throwable) {
            InstallResult.Failure(
                packageId = packageId,
                error = InstallError.UnknownError(
                    throwable.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                )
            )
        }

        when (result) {
            is InstallResult.Success -> {
                refreshInstallState(packageId)
                refreshInstalledMetadata(packageId)
                _dialogState.value = PackageDialogState.InstallComplete(packageId, result)
                loadPackages()
            }
            is InstallResult.Failure -> {
                _dialogState.value = PackageDialogState.InstallComplete(packageId, result)
            }
        }
    }

    fun requestUninstall(packageId: String, platform: Platform) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: return

        viewModelScope.launch {
            val dependents = packageManager.getDependentPackages(packageId, platform)
            _dialogState.value = PackageDialogState.UninstallConfirm(
                packageId = packageId,
                packageInfo = pkg,
                platform = platform,
                dependentPackages = dependents
            )
        }
    }

    fun confirmUninstall(packageId: String, platform: Platform) {
        launchPackageOperation {
            val result = packageManager.uninstall(packageId, platform)
            refreshInstallState(packageId)
            refreshInstalledMetadata(packageId)
            _dialogState.value = PackageDialogState.UninstallComplete(packageId, result)
        }
    }

    fun showPackageDetails(packageId: String) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: return
        _uiState.update { it.copy(currentDetailPackage = pkg, currentInstallEvent = null) }
    }

    fun closePackageDetails() {
        _uiState.update { it.copy(currentDetailPackage = null, currentInstallEvent = null) }
    }

    fun navigateToDependency(packageId: String) {
        viewModelScope.launch {
            val pkg = _uiState.value.packages.find { it.id == packageId }
            if (pkg != null) {
                _uiState.update { it.copy(currentDetailPackage = pkg, currentInstallEvent = null) }
            } else {
                packageManager.getPackageDetail(packageId).onSuccess { detail ->
                    _uiState.update { it.copy(currentDetailPackage = detail, currentInstallEvent = null) }
                }
            }
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }

    fun toggleSelectionMode() {
        _uiState.update {
            if (it.isSelectionMode) {
                it.copy(isSelectionMode = false, selectedPackageIds = emptySet())
            } else {
                it.copy(isSelectionMode = true)
            }
        }
    }

    fun togglePackageSelection(packageId: String) {
        _uiState.update { state ->
            val newSelection = if (packageId in state.selectedPackageIds) {
                state.selectedPackageIds - packageId
            } else {
                state.selectedPackageIds + packageId
            }
            state.copy(selectedPackageIds = newSelection)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedPackageIds = state.filteredPackages.map { it.id }.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPackageIds = emptySet()) }
    }

    fun batchUninstall(platform: Platform) {
        val selectedIds = _uiState.value.selectedPackageIds.filter {
            _uiState.value.installStates[it]?.forPlatform(platform)?.isInstalled == true
        }
        if (selectedIds.isEmpty()) return

        launchPackageOperation {
            runBatch(selectedIds.map { PackagePlatformKey(it, platform) }, PackageBatchOperation.UNINSTALL)
        }
    }

    fun batchInstall(platform: Platform) {
        val selectedIds = _uiState.value.selectedPackageIds.toList()
        if (selectedIds.isEmpty()) return

        val installableIds = selectedIds.filter { packageId ->
            val pkg = _uiState.value.packages.find { it.id == packageId }
            pkg?.platformPackageFor(platform) != null
        }
        if (installableIds.isEmpty()) return

        launchPackageOperation {
            val plans = previewInstallPlans(
                packageIds = installableIds,
                platform = platform
            ).getOrElse { error ->
                _dialogState.value = PackageDialogState.InstallComplete(
                    packageId = installableIds.first(),
                    result = InstallResult.Failure(
                        packageId = installableIds.first(),
                        error = InstallError.UnknownError(
                            error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                        )
                    )
                )
                return@launchPackageOperation
            }

            if (plans.hasPendingDependencies()) {
                _dialogState.value = PackageDialogState.BatchInstallConfirm(
                    packageIds = installableIds,
                    platform = platform,
                    plans = plans
                )
                return@launchPackageOperation
            }

            startBatchInstall(installableIds, platform)
        }
    }

    fun confirmBatchInstall() {
        val state = _dialogState.value as? PackageDialogState.BatchInstallConfirm ?: return
        launchPackageOperation {
            startBatchInstall(state.packageIds, state.platform)
        }
    }

    fun checkForUpdates() {
        if (installJob?.isCompleted == false) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val updates = packageManager.checkForUpdates()
                _uiState.update { it.copy(availableUpdates = updates) }
                loadPackages()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Cannot check package updates")
                _uiState.update { it.copy(error = Strings.pkg_manager_load_failed.strOr(appContext)) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun updateAllPackages() {
        val updates = _uiState.value.availableUpdates
        if (updates.isEmpty()) return

        val updateList = updates.values.toList()
        launchPackageOperation {
            val plans = previewUpdatePlans(updateList).getOrElse { error ->
                val failedUpdate = updateList.first()
                _dialogState.value = PackageDialogState.InstallComplete(
                    packageId = failedUpdate.packageId,
                    result = InstallResult.Failure(
                        packageId = failedUpdate.packageId,
                        error = InstallError.UnknownError(
                            error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                        )
                    )
                )
                return@launchPackageOperation
            }

            if (plans.hasPendingDependencies()) {
                _dialogState.value = PackageDialogState.BatchUpdateConfirm(
                    updates = updateList,
                    plans = plans
                )
                return@launchPackageOperation
            }

            startUpdateAllPackages(updateList)
        }
    }

    fun confirmBatchUpdate() {
        val state = _dialogState.value as? PackageDialogState.BatchUpdateConfirm ?: return
        launchPackageOperation {
            startUpdateAllPackages(state.updates)
        }
    }

    private suspend fun startBatchInstall(packageIds: List<String>, platform: Platform) {
        runBatch(packageIds.map { PackagePlatformKey(it, platform) }, PackageBatchOperation.INSTALL)
    }

    private suspend fun startUpdateAllPackages(updates: List<UpdateInfo>) {
        runBatch(updates.map { PackagePlatformKey(it.packageId, it.platform) }, PackageBatchOperation.UPDATE)
    }

    private suspend fun runBatch(packages: List<PackagePlatformKey>, operation: PackageBatchOperation) {
        if (packages.isEmpty()) return
        var succeeded = 0
        var cancelled = false
        val failures = mutableListOf<PackageBatchFailure>()
        try {
            for ((index, key) in packages.withIndex()) {
                currentCoroutineContext().ensureActive()
                val name = _uiState.value.packages.find { it.id == key.packageId }?.name ?: key.packageId
                val preparing = InstallProgressEvent.Preparing(Strings.pkg_manager_progress_starting.strOr(appContext))
                _dialogState.value = when (operation) {
                    PackageBatchOperation.INSTALL -> PackageDialogState.BatchInstalling(
                        packages.map { it.packageId }, key.platform, index, packages.size, name, preparing,
                    )
                    PackageBatchOperation.UPDATE -> PackageDialogState.BatchUpdating(
                        index, packages.size, name, preparing,
                    )
                    PackageBatchOperation.UNINSTALL -> PackageDialogState.BatchUninstalling(index, packages.size, name)
                }
                val error = try {
                    if (operation == PackageBatchOperation.UNINSTALL) {
                        when (val result = packageManager.uninstall(key.packageId, key.platform)) {
                            is UninstallResult.Success -> null
                            is UninstallResult.Failure -> result.error.toDisplayMessage()
                        }
                    } else {
                        when (val result = packageManager.install(key.packageId, key.platform) { event ->
                            _dialogState.update { state ->
                                when (state) {
                                    is PackageDialogState.BatchInstalling -> state.copy(event = event)
                                    is PackageDialogState.BatchUpdating -> state.copy(event = event)
                                    else -> state
                                }
                            }
                        }) {
                            is InstallResult.Success -> null
                            is InstallResult.Failure -> {
                                if (result.error == InstallError.Cancelled) throw CancellationException("Package batch cancelled")
                                result.error.toDisplayMessage()
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Package batch operation failed: %s/%s", key.packageId, key.platform)
                    error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                }
                currentCoroutineContext().ensureActive()
                if (error == null) {
                    succeeded++
                    _uiState.update { it.copy(availableUpdates = it.availableUpdates - key) }
                } else {
                    failures += PackageBatchFailure(key, error)
                }
                try {
                    refreshInstallState(key.packageId)
                    refreshInstalledMetadata(key.packageId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Cannot refresh package state after operation: %s", key.packageId)
                }
            }
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            // Keep failed and unattempted updates available for retry.
            withContext(NonCancellable) {
                _dialogState.value = PackageDialogState.BatchComplete(
                    operation, PackageBatchResult(packages.size, succeeded, failures.toList(), cancelled),
                )
                _uiState.update { it.copy(isSelectionMode = false, selectedPackageIds = emptySet()) }
                loadPackages()
            }
        }
    }

    private suspend fun previewInstallPlans(
        packageIds: List<String>,
        platform: Platform
    ): Result<List<PackageInstallPlan>> {
        val plans = mutableListOf<PackageInstallPlan>()
        for (packageId in packageIds) {
            plans += packageManager.previewInstallPlan(packageId, platform).getOrElse { error ->
                return Result.failure(error)
            }
        }
        return Result.success(plans)
    }

    private suspend fun previewUpdatePlans(
        updates: List<com.wuxianggujun.tinaide.core.packages.UpdateInfo>
    ): Result<List<PackageInstallPlan>> {
        val plans = mutableListOf<PackageInstallPlan>()
        for (update in updates) {
            plans += packageManager.previewInstallPlan(update.packageId, update.platform).getOrElse { error ->
                return Result.failure(error)
            }
        }
        return Result.success(plans)
    }

    private fun buildInstalledMetadataMap(
        installedPackages: List<com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo>
    ): Map<String, InstalledPackageMetadata> = installedPackages.asSequence()
        .filter { it.platform == Platform.ANDROID }
        .mapNotNull { installed ->
            InstalledPackageMetadataReader.read(appContext, installed.packageId)
        }
        .associateBy { it.id }

    private fun mergeAvailableAndInstalledPackages(
        availablePackages: List<GUIPackage>,
        installedPackages: List<com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo>,
        installedMetadata: Map<String, InstalledPackageMetadata>
    ): List<GUIPackage> {
        val packagesById = availablePackages.associateByTo(linkedMapOf()) { it.id }
        val availableIds = packagesById.keys.toSet()
        installedPackages.forEach { installed ->
            if (installed.packageId in availableIds) return@forEach
            val existing = packagesById[installed.packageId]
            val metadata = installedMetadata[installed.packageId]
            packagesById[installed.packageId] = GUIPackage(
                id = installed.packageId,
                name = metadata?.name ?: installed.packageName,
                description = metadata?.description,
                category = metadata?.category,
                homepage = metadata?.homepage,
                linux = if (installed.platform == Platform.LINUX) {
                    PlatformPackage(
                        version = installed.version,
                        installType = installed.installType,
                        size = installed.size,
                        dependencies = emptyList()
                    )
                } else {
                    existing?.linux
                },
                android = if (installed.platform == Platform.ANDROID) {
                    PlatformPackage(
                        version = installed.version,
                        installType = installed.installType,
                        size = installed.size,
                        abi = metadata?.abis,
                        dependencies = emptyList()
                    )
                } else {
                    existing?.android
                }
            )
        }
        return packagesById.values.sortedBy { it.name.lowercase() }
    }

    private suspend fun refreshInstallState(packageId: String) {
        val newState = packageManager.getInstallState(packageId)
        _uiState.update { state ->
            val states = state.installStates + (packageId to newState)
            val visible = filterPackages(state.packages, states, _filterState.value)
            state.copy(
                installStates = states,
                filteredPackages = visible,
                selectedPackageIds = state.selectedPackageIds.intersect(visible.map { it.id }.toSet()),
                availableUpdates = state.availableUpdates.filterNot { (key, _) ->
                    key.packageId == packageId && newState.forPlatform(key.platform) !is PlatformInstallState.UpdateAvailable
                },
            )
        }
    }

    private fun refreshInstalledMetadata(packageId: String) {
        val metadata = InstalledPackageMetadataReader.read(appContext, packageId)
        _uiState.update { state ->
            val newMetadata = state.installedMetadata.toMutableMap()
            if (metadata != null) {
                newMetadata[packageId] = metadata
            } else {
                newMetadata.remove(packageId)
            }
            state.copy(installedMetadata = newMetadata)
        }
    }
}

data class PackageManagerUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val packages: List<GUIPackage> = emptyList(),
    val filteredPackages: List<GUIPackage> = emptyList(),
    val installStates: Map<String, PackageInstallState> = emptyMap(),
    val installedMetadata: Map<String, InstalledPackageMetadata> = emptyMap(),
    val selectedPackageIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val currentDetailPackage: GUIPackage? = null,
    val currentInstallEvent: InstallProgressEvent? = null,
    val availableUpdates: Map<PackagePlatformKey, UpdateInfo> = emptyMap()
)

data class PackageFilterState(
    val searchQuery: String = "",
    val installedOnly: Boolean = false,
) {
    fun matches(pkg: GUIPackage, state: PackageInstallState): Boolean {
        if (installedOnly && !state.android.isInstalled && !state.linux.isInstalled) return false
        val tokens = searchQuery.split(',', ' ', '\n', '\t').filter { it.isNotBlank() }
        return tokens.isEmpty() || tokens.any { token ->
            pkg.id.contains(token, ignoreCase = true) || pkg.name.contains(token, ignoreCase = true) ||
                pkg.description?.contains(token, ignoreCase = true) == true ||
                pkg.category?.contains(token, ignoreCase = true) == true
        }
    }
}

enum class PackageBatchOperation { INSTALL, UPDATE, UNINSTALL }
data class PackageBatchFailure(val key: PackagePlatformKey, val message: String)
data class PackageBatchResult(
    val total: Int,
    val succeeded: Int,
    val failures: List<PackageBatchFailure>,
    val cancelled: Boolean,
) {
    val unfinished: Int get() = (total - succeeded - failures.size).coerceAtLeast(0)
}

sealed class PackageDialogState {
    data class InstallConfirm(
        val packageId: String,
        val packageInfo: GUIPackage,
        val platform: Platform,
        val plan: PackageInstallPlan
    ) : PackageDialogState()

    data class Installing(
        val packageId: String,
        val packageName: String,
        val platform: Platform,
        val event: InstallProgressEvent
    ) : PackageDialogState()

    data class InstallComplete(
        val packageId: String,
        val result: InstallResult
    ) : PackageDialogState()

    data class UninstallConfirm(
        val packageId: String,
        val packageInfo: GUIPackage,
        val platform: Platform,
        val dependentPackages: List<String>
    ) : PackageDialogState()

    data class UninstallComplete(
        val packageId: String,
        val result: UninstallResult
    ) : PackageDialogState()

    data class PackageDetails(val packageInfo: GUIPackage) : PackageDialogState()

    data class BatchInstallConfirm(
        val packageIds: List<String>,
        val platform: Platform,
        val plans: List<PackageInstallPlan>
    ) : PackageDialogState()

    data class BatchInstalling(
        val packageIds: List<String>,
        val platform: Platform,
        val currentIndex: Int,
        val totalCount: Int,
        val currentPackageName: String,
        val event: InstallProgressEvent
    ) : PackageDialogState()

    data class BatchComplete(val operation: PackageBatchOperation, val result: PackageBatchResult) : PackageDialogState()

    data class BatchUninstalling(val currentIndex: Int, val totalCount: Int, val currentPackageName: String) : PackageDialogState()

    data class BatchPlatformSelect(
        val packageIds: List<String>
    ) : PackageDialogState()

    data class BatchUpdating(
        val currentIndex: Int,
        val totalCount: Int,
        val currentPackageName: String,
        val event: InstallProgressEvent
    ) : PackageDialogState()

    data class BatchUpdateConfirm(
        val updates: List<com.wuxianggujun.tinaide.core.packages.UpdateInfo>,
        val plans: List<PackageInstallPlan>
    ) : PackageDialogState()

}

private fun GUIPackage.platformPackageFor(platform: Platform): PlatformPackage? = when (platform) {
    Platform.LINUX -> linux
    Platform.ANDROID -> android
}

private fun List<PackageInstallPlan>.hasPendingDependencies(): Boolean = any { plan ->
    plan.packages.any { item -> !item.isRoot && !item.isAlreadyInstalled }
}
