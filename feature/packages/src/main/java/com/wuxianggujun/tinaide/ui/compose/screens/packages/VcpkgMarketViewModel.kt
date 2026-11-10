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
import com.wuxianggujun.tinaide.core.packages.VcpkgProjectCatalog
import com.wuxianggujun.tinaide.core.packages.VcpkgRepository
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogSnapshot
import com.wuxianggujun.tinaide.core.packages.vcpkgUserMessage
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

data class VcpkgMarketState(
    val projectPath: String = "",
    val catalog: VcpkgProjectCatalog? = null,
    val available: VcpkgCatalogSnapshot? = null,
    val query: String = "",
    val loading: Boolean = false,
    val installing: Boolean = false,
    val updating: Boolean = false,
    val downloadProgress: Float? = null,
    val error: String? = null,
    val output: List<String> = emptyList(),
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

    fun selectProject(path: String, dependency: String? = null) {
        if (_state.value.busy) return
        savedState[PROJECT_PATH] = path
        _state.update { it.copy(projectPath = path, catalog = null, output = emptyList()) }
        if (dependency == null) refresh() else toggleDependency(dependency, true)
    }

    fun search(query: String) { _state.update { it.copy(query = query) } }

    fun refresh() {
        if (_state.value.busy) return
        val path = _state.value.projectPath
        operation = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val available = catalogs.readLocal()
                // Render the offline index before reading project metadata.
                _state.update { it.copy(available = available) }
                val catalog = if (path.isNotBlank()) repository.read(File(path), abi, available.ports) else null
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

    fun updateCatalog() {
        if (_state.value.busy) return
        operation = viewModelScope.launch {
            _state.update { it.copy(updating = true, downloadProgress = null, error = null) }
            try {
                val available = catalogs.update { downloaded, total ->
                    _state.update { it.copy(downloadProgress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null) }
                }
                _state.update { it.copy(available = available) }
                val path = _state.value.projectPath
                if (path.isNotBlank()) {
                    val catalog = repository.read(File(path), abi, available.ports)
                    _state.update { it.copy(catalog = catalog) }
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
        if (_state.value.busy) return
        val path = _state.value.projectPath
        if (path.isBlank()) return
        operation = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                repository.enableManagedManifest(File(path))
                repository.setDependency(File(path), name, enabled)
                val catalog = repository.read(File(path), abi, _state.value.available?.ports)
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

    fun install() {
        val path = _state.value.projectPath
        if (path.isBlank() || _state.value.busy) return
        if (_state.value.catalog?.settings?.mode != VcpkgProjectSettings.MANIFEST) return
        operation = viewModelScope.launch {
            _state.update { it.copy(installing = true, error = null, output = emptyList()) }
            try {
                val installingJob = currentCoroutineContext().job
                val result = installer.install(File(path)) { line ->
                    if (installingJob.isActive) {
                        _state.update { it.copy(output = (it.output + line.take(MAX_LINE_CHARS)).takeLast(MAX_LOG_LINES)) }
                    }
                }
                val catalog = repository.read(File(path), abi, _state.value.available?.ports)
                _state.update {
                    it.copy(catalog = catalog, error = (result as? ConfigureResult.Error)?.message,
                        output = (it.output + if (result is ConfigureResult.Error) emptyList() else
                            listOf(Strings.vcpkg_install_complete.strOr(getApplication()))).takeLast(MAX_LOG_LINES))
                }
            } catch (cancelled: CancellationException) {
                // vcpkg can install some ports before cancellation; reload the actual status database.
                withContext(NonCancellable) {
                    try {
                        val catalog = repository.read(File(path), abi, _state.value.available?.ports)
                        _state.update { it.copy(catalog = catalog) }
                    } catch (error: Exception) {
                        reportFailure(error)
                    }
                }
                _state.update { it.copy(output = (it.output + Strings.vcpkg_install_cancelled.strOr(getApplication())).takeLast(MAX_LOG_LINES)) }
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                _state.update { it.copy(installing = false) }
            }
        }
    }

    fun cancelInstall() { if (_state.value.installing || _state.value.updating) operation?.cancel() }

    private fun reportFailure(error: Exception) {
        Timber.tag("VcpkgMarket").w(error, "vcpkg project operation failed")
        _state.update { it.copy(error = error.vcpkgUserMessage(getApplication())) }
    }

    private companion object {
        const val PROJECT_PATH = "vcpkg_project_path"
        const val MAX_LOG_LINES = 250
        const val MAX_LINE_CHARS = 2000
    }
}
