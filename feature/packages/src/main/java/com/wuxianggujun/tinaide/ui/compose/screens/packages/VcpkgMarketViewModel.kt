package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.VcpkgProjectInstaller
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.PackageAbiCompatibility
import com.wuxianggujun.tinaide.core.packages.VcpkgAndroidCatalog
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogSnapshot
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.core.packages.VcpkgProjectCatalog
import com.wuxianggujun.tinaide.core.packages.VcpkgPort
import com.wuxianggujun.tinaide.core.packages.VcpkgRepository
import com.wuxianggujun.tinaide.core.packages.VcpkgSharedLibrary
import com.wuxianggujun.tinaide.core.packages.vcpkgUserMessage
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class VcpkgMarketState(
    val projectPath: String = "",
    /** Debounced filter text; the input field itself lives in the shared market top bar. */
    val appliedQuery: String = "",
    val catalog: VcpkgProjectCatalog? = null,
    val available: VcpkgCatalogSnapshot? = null,
    val androidCatalog: VcpkgAndroidCatalog? = null,
    val loading: Boolean = false,
    val installing: Boolean = false,
    val updating: Boolean = false,
    val downloadProgress: Float? = null,
    val error: String? = null,
    /** One-shot user notice, rendered as a toast and cleared by the screen. */
    val notice: Int? = null,
    val output: List<String> = emptyList(),
    /** The persisted project path no longer points at a usable CMake project. */
    val projectMissing: Boolean = false,
) {
    val busy: Boolean get() = loading || installing || updating
}

class VcpkgMarketViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = VcpkgRepository(application)
    private val installer = VcpkgProjectInstaller(application)
    private val catalogs = VcpkgCatalogStore(application)
    private val abi = PackageAbiCompatibility.currentAppAbi(application.applicationInfo.nativeLibraryDir, Build.SUPPORTED_ABIS)
    private val _state = MutableStateFlow(VcpkgMarketState(projectPath = savedState[PROJECT_PATH] ?: ""))
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private var searchJob: Job? = null

    fun selectProject(path: String, dependency: String? = null) {
        if (_state.value.busy) {
            _state.update { it.copy(notice = Strings.vcpkg_busy_switch_project) }
            return
        }
        savedState[PROJECT_PATH] = path
        _state.update {
            it.copy(
                projectPath = path,
                catalog = null,
                output = emptyList(),
                error = null,
                projectMissing = false,
            )
        }
        if (dependency == null) refresh() else toggleDependency(dependency, true)
    }

    /** Debounced so typing does not restart filtering on every keystroke. */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(appliedQuery = "") }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(appliedQuery = query) }
        }
    }

    /** Pull-to-refresh and the refresh action: re-read project metadata, reuse the loaded catalog. */
    fun refresh() = reload(forceCatalog = false, quiet = false)

    /** Explicitly re-read the snapshot from disk, e.g. after another screen updated the catalog. */
    fun refreshCatalog() = reload(forceCatalog = true, quiet = false)

    /**
     * Called on ON_RESUME. Re-reading a project manifest is cheap, but re-evaluating ~2000 supports
     * expressions is not, so the catalog object is reused unless it is missing.
     */
    fun onResumed() {
        if (_state.value.busy) return
        if (_state.value.available != null && _state.value.projectPath.isBlank()) return
        reload(forceCatalog = false, quiet = true)
    }

    private fun reload(forceCatalog: Boolean, quiet: Boolean) {
        if (_state.value.busy) return
        val path = _state.value.projectPath
        operation = viewModelScope.launch {
            val showProgress = !quiet || _state.value.androidCatalog == null
            _state.update { it.copy(loading = showProgress, error = if (quiet) it.error else null) }
            try {
                val available = loadCatalog(forceCatalog)
                val catalog = if (path.isBlank()) null else readProject(path, available.ports)
                _state.update { it.copy(catalog = catalog, projectMissing = path.isNotBlank() && catalog == null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun updateCatalog() {
        if (_state.value.busy) return
        operation = viewModelScope.launch {
            _state.update { it.copy(updating = true, downloadProgress = null, error = null) }
            try {
                val available = catalogs.update { downloaded, total ->
                    _state.update { it.copy(downloadProgress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null) }
                }
                publishAvailable(available)
                val path = _state.value.projectPath
                if (path.isNotBlank()) {
                    _state.update { it.copy(catalog = readProject(path, available.ports)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                _state.update { it.copy(updating = false, downloadProgress = null) }
            }
        }
    }

    fun toggleDependency(name: String, enabled: Boolean) {
        if (_state.value.busy) {
            _state.update { it.copy(notice = Strings.vcpkg_busy_switch_project) }
            return
        }
        val path = _state.value.projectPath
        if (path.isBlank()) return
        operation = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (_state.value.catalog == null) {
                    val catalog = readProject(path, _state.value.available?.ports)
                    _state.update { it.copy(catalog = catalog) }
                }
                repository.setMarketDependency(File(path), name, enabled,
                    _state.value.androidCatalog ?: VcpkgAndroidCatalog(emptyList(), abi))
                val catalog = readProject(path, _state.value.available?.ports)
                _state.update { it.copy(catalog = catalog, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun useManagedInstallation() {
        val path = _state.value.projectPath
        if (path.isBlank() || _state.value.busy) return
        operation = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                repository.enableManagedManifest(File(path))
                val catalog = readProject(path, _state.value.available?.ports)
                _state.update { it.copy(catalog = catalog) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun install() = startInstall(toSharedLibrary = false)

    /** 装到项目后导出到设备级共享库目录，并把项目切换为预编译库模式供其他项目复用。 */
    fun installToShared() = startInstall(toSharedLibrary = true)

    private fun startInstall(toSharedLibrary: Boolean) {
        val path = _state.value.projectPath
        if (path.isBlank() || _state.value.busy) return
        val settings = _state.value.catalog?.settings ?: return
        if (settings.mode != VcpkgProjectSettings.MANIFEST) return
        operation = viewModelScope.launch {
            _state.update { it.copy(installing = true, error = null, output = emptyList()) }
            try {
                val installingJob = currentCoroutineContext().job
                val log: (String) -> Unit = { line ->
                    if (installingJob.isActive) {
                        _state.update { it.copy(output = (it.output + line.take(MAX_LINE_CHARS)).takeLast(MAX_LOG_LINES)) }
                    }
                }
                val project = File(path)
                val result = installer.install(project, log)
                val failure = (result as? ConfigureResult.Error)?.message
                if (failure == null && toSharedLibrary) {
                    VcpkgSharedLibrary.export(getApplication(), project, settings, abi, log)
                    repository.enableSharedLibrary(project)
                }
                _state.update {
                    it.copy(error = failure,
                        output = (it.output + (failure ?: Strings.vcpkg_install_complete.strOr(getApplication())))
                            .takeLast(MAX_LOG_LINES))
                }
                val catalog = readProject(path, _state.value.available?.ports)
                _state.update { it.copy(catalog = catalog) }
            } catch (cancelled: CancellationException) {
                // vcpkg can install some ports before cancellation; reload the actual status database.
                withContext(NonCancellable) {
                    try {
                        val catalog = readProject(path, _state.value.available?.ports)
                        _state.update { it.copy(catalog = catalog) }
                    } catch (error: Exception) {
                        reportFailure(error, appendToInstallLog = true)
                    }
                }
                _state.update { it.copy(output = (it.output + Strings.vcpkg_install_cancelled.strOr(getApplication())).takeLast(MAX_LOG_LINES)) }
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error, appendToInstallLog = true)
            } finally {
                _state.update { it.copy(installing = false) }
            }
        }
    }

    /** Cancels whichever long-running operation owns the single operation slot. */
    fun cancelOperation() {
        if (_state.value.installing || _state.value.updating) operation?.cancel()
    }

    fun clearNotice() { _state.update { it.copy(notice = null) } }

    fun clearError() { _state.update { it.copy(error = null) } }

    private suspend fun loadCatalog(force: Boolean): VcpkgCatalogSnapshot {
        val current = _state.value.available
        if (!force && current != null) return current
        val available = catalogs.readLocal()
        publishAvailable(available)
        return available
    }

    private suspend fun readProject(path: String, availablePorts: List<VcpkgPort>?): VcpkgProjectCatalog? {
        val project = File(path)
        if (!project.isDirectory || !File(project, "CMakeLists.txt").isFile) return null
        return repository.read(project, abi, availablePorts)
    }

    private suspend fun publishAvailable(available: VcpkgCatalogSnapshot) {
        if (_state.value.androidCatalog != null && _state.value.available?.revision == available.revision) return
        val androidCatalog = withContext(Dispatchers.Default) { VcpkgAndroidCatalog(available.ports, abi) }
        _state.update { it.copy(available = available, androidCatalog = androidCatalog) }
    }

    private fun reportFailure(error: Exception, appendToInstallLog: Boolean = false) {
        Timber.tag("VcpkgMarket").w(error, "vcpkg project operation failed")
        val message = error.vcpkgUserMessage(getApplication())
        _state.update {
            it.copy(error = message, output = if (appendToInstallLog) (it.output + message).takeLast(MAX_LOG_LINES) else it.output)
        }
    }

    private companion object {
        const val PROJECT_PATH = "vcpkg_project_path"
        const val MAX_LOG_LINES = 250
        const val MAX_LINE_CHARS = 2000
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}
