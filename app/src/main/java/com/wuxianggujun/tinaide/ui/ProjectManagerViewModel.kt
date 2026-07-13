package com.wuxianggujun.tinaide.ui

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.compile.LanguageDetector
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.file.IProjectSession
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectSourceLocation
import com.wuxianggujun.tinaide.storage.FileDeletionCancellationSignal
import com.wuxianggujun.tinaide.storage.FileDeletionProgress
import com.wuxianggujun.tinaide.storage.FileDeletionResult
import com.wuxianggujun.tinaide.storage.FileDeletionService
import com.wuxianggujun.tinaide.storage.ProjectLocationManager
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.storage.StorageManager
import com.wuxianggujun.tinaide.update.AppUpdateChecker
import com.wuxianggujun.tinaide.update.AppUpdateInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ProjectManagerViewModel(
    application: Application,
    private val projectSession: IProjectSession,
    private val projectLocationManager: ProjectLocationManager,
    private val storageManager: StorageManager,
    private val fileDeletionService: FileDeletionService,
) : AndroidViewModel(application) {

    private companion object {
        private const val TAG = "ProjectManagerViewModel"
        private const val MIN_REFRESH_VISIBLE_MS = 900L
    }

    private val _projects = MutableStateFlow<List<ProjectListItem>>(emptyList())
    val projects: StateFlow<List<ProjectListItem>> = _projects.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val updateChecker = AppUpdateChecker(getApplication())

    private val _appUpdateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val appUpdateInfo: StateFlow<AppUpdateInfo?> = _appUpdateInfo.asStateFlow()

    private var appUpdateCheckStarted = false

    init {
        checkForAppUpdate()
    }

    fun checkForAppUpdate() {
        if (appUpdateCheckStarted) return
        appUpdateCheckStarted = true

        viewModelScope.launch {
            updateChecker.checkForUpdate()
                .onSuccess { updateInfo ->
                    _appUpdateInfo.value = updateInfo
                }
                .onFailure { throwable ->
                    Timber.tag(TAG).w(throwable, "Failed to check app update")
                }
        }
    }

    fun dismissAppUpdate(info: AppUpdateInfo) {
        updateChecker.markDismissed(info.tagName)
        _appUpdateInfo.value = null
    }

    fun clearAppUpdatePrompt() {
        _appUpdateInfo.value = null
    }

    fun getProjectsRootDir(): File {
        val app = getApplication<Application>()
        return if (storageManager.hasExternalStoragePermission()) {
            ProjectPaths.getPublicProjectsRoot(app)
        } else {
            ProjectPaths.getPrivateProjectsRoot(app)
        }
    }

    fun reloadProjects() {
        if (_isRefreshing.value) return

        viewModelScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            _isRefreshing.value = true
            try {
                val items = withContext(Dispatchers.IO) {
                    val knownDirs = LinkedHashMap<String, File>()
                    val appContext = getApplication<Application>()

                    fun addKnownDir(dir: File) {
                        if (!dir.isDirectory) return
                        if (!isManagedProject(appContext, dir)) return
                        if (!storageManager.canAccessProjectDir(dir)) return
                        if (isEmptyProjectShell(dir)) return
                        val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
                        knownDirs.putIfAbsent(key, dir)
                    }

                    fun scanRoot(root: File) {
                        runCatching {
                            if (!root.exists()) root.mkdirs()
                            root.listFiles()
                                ?.filter { it.isDirectory }
                                ?.forEach(::addKnownDir)
                        }
                    }

                    projectLocationManager.getAllProjects().forEach { location ->
                        addKnownDir(File(location.sourceRootPath))
                    }

                    scanRoot(ProjectPaths.getPrivateProjectsRoot(appContext))
                    if (storageManager.hasExternalStoragePermission()) {
                        scanRoot(ProjectPaths.getPublicProjectsRoot(appContext))
                    }

                    knownDirs.values.map { dir ->
                        val meta = ProjectMetadataStore.read(dir)
                        val language = LanguageDetector.detect(dir)
                        ProjectListItem(
                            dir = dir,
                            displayName = meta?.displayName ?: dir.name,
                            id = meta?.id,
                            lastOpenedAt = meta?.lastOpenedAt,
                            buildSystem = meta?.buildSystem,
                            primaryLanguage = language,
                            sourceLocation = if (ProjectPaths.isUnderPublicProjectsRoot(appContext, dir)) {
                                ProjectSourceLocation.PUBLIC
                            } else {
                                ProjectSourceLocation.PRIVATE
                            }
                        )
                    }
                        .sortedWith(
                            compareBy<ProjectListItem>(
                                { it.displayName.lowercase() },
                                { it.dir.name.lowercase() },
                            )
                        )
                }
                _projects.value = items
            } finally {
                withContext(NonCancellable) {
                    val elapsed = SystemClock.elapsedRealtime() - startMs
                    val remaining = MIN_REFRESH_VISIBLE_MS - elapsed
                    if (remaining > 0) delay(remaining)
                    _isRefreshing.value = false
                }
            }
        }
    }

    suspend fun deleteProject(
        project: ProjectListItem,
        cancellationSignal: FileDeletionCancellationSignal,
        onProgress: suspend (FileDeletionProgress) -> Unit,
    ): FileDeletionResult {
        if (!_isDeleting.compareAndSet(expect = false, update = true)) {
            return FileDeletionResult.Failure(
                deletedItems = 0L,
                totalItems = null,
                failedPath = project.dir.absolutePath,
                remainingAtOriginalPath = project.dir.exists(),
            )
        }

        try {
            val projectId = try {
                withContext(Dispatchers.IO) {
                    ensureProjectFileAccess(project.dir)
                    project.id ?: ProjectMetadataStore.read(project.dir)?.id
                }
            } catch (error: Throwable) {
                return FileDeletionResult.Failure(
                    deletedItems = 0L,
                    totalItems = null,
                    failedPath = project.dir.absolutePath,
                    remainingAtOriginalPath = project.dir.exists(),
                    cause = error,
                )
            }

            var completedItems = 0L
            var completedTotal = 0L
            var stagedOutsidePublicStorage = false

            suspend fun deleteTarget(target: File): FileDeletionResult {
                val result = fileDeletionService.delete(
                    target = target,
                    cancellationSignal = cancellationSignal,
                    onProgress = { progress ->
                        val targetTotalItems = progress.totalItems
                        onProgress(
                            if (targetTotalItems == null) {
                                progress
                            } else {
                                progress.copy(
                                    completedItems = completedItems + progress.completedItems,
                                    totalItems = completedTotal + targetTotalItems,
                                )
                            }
                        )
                    },
                )
                return when (result) {
                    is FileDeletionResult.Success -> {
                        completedItems += result.deletedItems
                        completedTotal += result.totalItems
                        stagedOutsidePublicStorage =
                            stagedOutsidePublicStorage || result.stagedOutsidePublicStorage
                        result
                    }
                    is FileDeletionResult.Cancelled -> result.copy(
                        deletedItems = completedItems + result.deletedItems,
                        totalItems = result.totalItems?.let { completedTotal + it },
                    )
                    is FileDeletionResult.Failure -> result.copy(
                        deletedItems = completedItems + result.deletedItems,
                        totalItems = result.totalItems?.let { completedTotal + it },
                    )
                }
            }

            val workspaceResult = projectId
                ?.let { ProjectPaths.getProjectWorkspaceDir(getApplication(), it) }
                ?.takeIf(File::exists)
                ?.let { workspace -> deleteTarget(workspace) }
            if (workspaceResult != null && workspaceResult !is FileDeletionResult.Success) {
                return workspaceResult
            }

            val sourceResult = deleteTarget(project.dir)
            if (sourceResult !is FileDeletionResult.Success) return sourceResult

            projectId?.let { id ->
                withContext(Dispatchers.IO) {
                    runCatching { projectLocationManager.unregisterProject(id, deleteWorkspace = false) }
                        .onFailure { error ->
                            Timber.tag(TAG).w(error, "Failed to unregister deleted project: %s", id)
                        }
                }
            }
            reloadProjects()
            return FileDeletionResult.Success(
                deletedItems = completedItems,
                totalItems = completedTotal,
                stagedOutsidePublicStorage = stagedOutsidePublicStorage,
            )
        } finally {
            _isDeleting.value = false
        }
    }

    fun openProject(dir: File): Result<Unit> = runCatching {
        ensureProjectFileAccess(dir)
        runCatching { projectLocationManager.registerProject(dir) }
        projectSession.openProject(dir.absolutePath)
    }

    fun renameProject(
        dir: File,
        newName: String,
        onResult: (Result<File>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val app = getApplication<Application>()
                val newDir = withContext(NonCancellable + Dispatchers.IO) {
                    ensureProjectFileAccess(dir)
                    val oldDirName = dir.name
                    val metadata = ProjectMetadataStore.ensure(dir, displayNameFallback = oldDirName)

                    val target = File(dir.parentFile, newName)
                    if (target.exists()) {
                        throw UiMessageException(Strings.error_project_name_exists)
                    }

                    val success = dir.renameTo(target)
                    if (!success) {
                        throw RuntimeException(Strings.toast_rename_failed.strOr(app))
                    }

                    runCatching {
                        val current = ProjectMetadataStore.read(target)
                        if (current != null) {
                            ProjectMetadataStore.write(target, current.copy(displayName = newName))
                        }
                    }

                    runCatching {
                        projectLocationManager.registerProject(target)
                    }

                    target
                }
                reloadProjects()
                newDir
            }
            onResult(result)
        }
    }

    private fun ensureProjectFileAccess(dir: File) {
        val access = storageManager.checkProjectDirAccess(dir)
        if (access.canAccess) {
            return
        }
        throw UiMessageException(access.failureMessageResId ?: Strings.toast_open_failed)
    }

    private fun isManagedProject(appContext: Application, dir: File): Boolean = ProjectPaths.isUnderPublicProjectsRoot(appContext, dir) ||
        ProjectPaths.isUnderPrivateProjectsRoot(appContext, dir)

    private fun isEmptyProjectShell(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        return children.isNotEmpty() && children.all { it.name == ".tinaide" }
    }
}

class UiMessageException(
    @param:StringRes val messageResId: Int
) : RuntimeException()
