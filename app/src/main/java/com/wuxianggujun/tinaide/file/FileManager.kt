package com.wuxianggujun.tinaide.file

import android.content.Context
import android.os.Build
import android.os.FileObserver
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.ProjectPathFilters
import com.wuxianggujun.tinaide.core.symbol.IProjectSymbolIndexService
import com.wuxianggujun.tinaide.file.FileWatchRegistration
import com.wuxianggujun.tinaide.file.IFileOperations
import com.wuxianggujun.tinaide.file.IFileWatchService
import com.wuxianggujun.tinaide.file.IRecentFilesProvider
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostEventDispatcher
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.storage.FileDeletionCancellationSignal
import com.wuxianggujun.tinaide.storage.FileDeletionProgress
import com.wuxianggujun.tinaide.storage.FileDeletionResult
import com.wuxianggujun.tinaide.storage.FileDeletionService
import com.wuxianggujun.tinaide.storage.ProjectLocationManager
import com.wuxianggujun.tinaide.storage.StorageManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 文件管理器实现
 */
class FileManager(
    private val context: Context,
    private val configManager: IConfigManager,
    private val projectLocationManager: ProjectLocationManager,
    private val storageManager: StorageManager,
    private val fileDeletionService: FileDeletionService,
    private val projectSymbolIndexServiceProvider: () -> IProjectSymbolIndexService? = { null }
) : IFileOperations,
    IFileDeletionOperations,
    IRecentFilesProvider,
    IFileWatchService,
    IProjectContext,
    IProjectSession,
    ServiceLifecycle {
    companion object {
        private const val TAG = "FileManager"
        private const val MAX_RECENT_FILES = 10
    }

    private val _currentProject = MutableStateFlow<Project?>(null)
    override val currentProjectFlow: StateFlow<Project?> = _currentProject.asStateFlow()
    private val fileWatchLock = Any()
    private val fileWatchTrees = mutableMapOf<String, WatchTree>()
    private val fileListeners = mutableMapOf<String, MutableList<FileChangeListener>>()
    private val fileWatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentFilesLock = Any()
    private val recentFiles = mutableListOf<File>()

    override fun onCreate() {
        loadRecentFiles()
        fileWatcherScope.launch {
            runCatching { fileDeletionService.recoverAbandonedStaging() }
                .onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to recover interrupted staged deletion")
                }
        }
    }

    override fun onDestroy() {
        val watchTrees = synchronized(fileWatchLock) {
            fileWatchTrees.values.toList().also {
                fileWatchTrees.clear()
                fileListeners.clear()
            }
        }
        watchTrees.forEach(WatchTree::stop)
        fileWatcherScope.cancel()
        saveRecentFiles()
    }

    override fun openProject(path: String): Project {
        val projectDir = File(path)
        require(projectDir.exists() && projectDir.isDirectory) { "Invalid project path: $path" }
        val access = storageManager.checkProjectDirAccess(projectDir)
        require(access.canAccess) {
            (access.failureMessageResId ?: Strings.toast_open_failed).strOr(context)
        }

        closeProject()

        val metadata = ProjectMetadataStore.ensure(projectDir, displayNameFallback = projectDir.name)
        val location = projectLocationManager.registerProject(projectDir)
        val workspaceDir = projectLocationManager.getWorkspaceDir(location.projectId)
        val buildDir = projectLocationManager.getBuildDir(location.projectId)
        // 记录“最近打开”时间（用于项目列表排序/分组）
        runCatching {
            ProjectMetadataStore.write(
                projectDir,
                metadata.copy(
                    lastOpenedIdeVersion = ProjectMetadataStore.currentIdeVersion,
                    lastOpenedAt = System.currentTimeMillis()
                )
            )
        }

        // 避免在主线程递归扫描整个项目目录，先仅加载顶层文件，文件树按需懒加载
        val files = readProjectEntries(projectDir)
        val project = Project(
            id = metadata.id,
            name = metadata.displayName,
            rootPath = projectDir.absolutePath,
            workspaceRootPath = workspaceDir.absolutePath,
            files = files,
            buildDirPath = buildDir.absolutePath
        )
        _currentProject.value = project

        // 启动项目级符号索引（后台构建）
        projectSymbolIndexServiceProvider()
            ?.onProjectOpened(projectDir)
        PluginHostEventDispatcher.emitProjectOpened(project.rootPath)

        configManager.set(ConfigKeys.CurrentProject, path)

        return project
    }

    override fun closeProject() {
        closeCurrentProject(preserveLastProject = false)
    }

    override fun clearInMemorySession() {
        closeCurrentProject(preserveLastProject = true)
    }

    private fun closeCurrentProject(preserveLastProject: Boolean) {
        val previousProject = _currentProject.value
        if (previousProject != null) {
            _currentProject.value = null
            stopAllFileWatchersAsync()
            projectSymbolIndexServiceProvider()?.onProjectClosed()
            PluginHostEventDispatcher.emitProjectClosed(previousProject.rootPath)
        }
        if (!preserveLastProject) {
            configManager.remove(ConfigKeys.CurrentProject.key)
        }
    }

    override fun getCurrentProject(): Project? = _currentProject.value

    override fun restoreLastSession(): Project? {
        _currentProject.value?.let { return it }
        try {
            val lastPath = configManager.get(ConfigKeys.CurrentProject)
            if (lastPath.isEmpty()) return null
            val dir = File(lastPath)
            if (!dir.exists() || !dir.isDirectory) return null
            val access = storageManager.checkProjectDirAccess(dir)
            if (!access.canAccess) {
                Timber.tag(TAG).w(
                    "Skip restoring inaccessible project: %s",
                    dir.absolutePath
                )
                configManager.remove(ConfigKeys.CurrentProject.key)
                return null
            }
            val metadata = ProjectMetadataStore.ensure(dir, displayNameFallback = dir.name)
            val location = projectLocationManager.registerProject(dir)
            val workspaceDir = projectLocationManager.getWorkspaceDir(location.projectId)
            val buildDir = projectLocationManager.getBuildDir(location.projectId)
            // 恢复时同样避免深度遍历
            val top = readProjectEntries(dir)
            val project = Project(
                id = metadata.id,
                name = metadata.displayName,
                rootPath = dir.absolutePath,
                workspaceRootPath = workspaceDir.absolutePath,
                files = top,
                buildDirPath = buildDir.absolutePath
            )
            _currentProject.value = project
            projectSymbolIndexServiceProvider()
                ?.onProjectOpened(dir)
            PluginHostEventDispatcher.emitProjectOpened(project.rootPath)
            return project
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error restoring current project")
            configManager.remove(ConfigKeys.CurrentProject.key)
            return null
        }
    }

    private fun readProjectEntries(projectDir: File): List<File> {
        val entries = runCatching { projectDir.listFiles() }
            .getOrNull()
            ?: throw IllegalStateException(Strings.compile_error_cannot_read_dir.strOr(context))
        return entries.toList()
    }

    override fun createFile(parent: File, name: String): File {
        require(parent.exists() && parent.isDirectory) { "Parent directory does not exist: ${parent.absolutePath}" }

        val newFile = File(parent, name)
        require(!newFile.exists()) { "File already exists: ${newFile.absolutePath}" }

        if (newFile.createNewFile()) {
            notifyFileCreated(newFile)
            PluginHostEventDispatcher.emitFileCreated(newFile)
            return newFile
        } else {
            throw IllegalStateException("Failed to create file: ${newFile.absolutePath}")
        }
    }

    override fun createDirectory(parent: File, name: String): File {
        require(parent.exists() && parent.isDirectory) { "Parent directory does not exist: ${parent.absolutePath}" }
        val newDir = File(parent, name)
        require(!newDir.exists()) { "Directory already exists: ${newDir.absolutePath}" }
        if (newDir.mkdirs()) {
            notifyFileCreated(newDir)
            PluginHostEventDispatcher.emitFileCreated(newDir)
            return newDir
        } else {
            throw IllegalStateException("Failed to create directory: ${newDir.absolutePath}")
        }
    }

    override fun deleteFile(file: File): Boolean {
        if (!file.exists()) return false
        val wasDirectory = file.isDirectory
        val deleted = runCatching {
            if (wasDirectory) file.deleteRecursively() else file.delete()
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Failed to delete path: %s", file.absolutePath)
        }.getOrDefault(false)
        if (deleted) {
            notifyFileDeleted(file)
            PluginHostEventDispatcher.emitFileDeleted(file, wasDirectory)
            removeRecentFilesForDeletedPath(file)
        }
        return deleted
    }

    override suspend fun deleteFile(
        file: File,
        cancellationSignal: FileDeletionCancellationSignal,
        onProgress: suspend (FileDeletionProgress) -> Unit,
    ): FileDeletionResult {
        val wasDirectory = file.isDirectory
        val result = fileDeletionService.delete(
            target = file,
            cancellationSignal = cancellationSignal,
            onProgress = onProgress,
        )
        when (result) {
            is FileDeletionResult.Success -> {
                notifyFileDeleted(file)
                PluginHostEventDispatcher.emitFileDeleted(file, wasDirectory)
                removeRecentFilesForDeletedPath(file)
            }
            is FileDeletionResult.Cancelled,
            is FileDeletionResult.Failure -> {
                if (!file.exists()) {
                    notifyFileDeleted(file)
                    removeRecentFilesForDeletedPath(file)
                }
            }
        }
        return result
    }

    override fun renameFile(file: File, newName: String): Boolean {
        if (!file.exists()) return false
        val newFile = File(file.parent, newName)
        require(!newFile.exists()) { "File with new name already exists: ${newFile.absolutePath}" }
        val wasDirectory = file.isDirectory
        val renamed = file.renameTo(newFile)
        if (renamed) {
            notifyFileRenamed(file, newFile)
            PluginHostEventDispatcher.emitFileRenamed(file, newFile, wasDirectory)
            retargetRecentFiles(file, newFile)
        }
        return renamed
    }

    override fun copyFile(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        try {
            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = false)
            } else {
                source.copyTo(destination, overwrite = false)
            }
            notifyFileCreated(destination)
            PluginHostEventDispatcher.emitFileCreated(destination)
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error copying file")
            return false
        }
    }

    override fun moveFile(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        if (destination.exists()) return false
        val wasDirectory = source.isDirectory
        val moved = source.renameTo(destination)
        if (moved) {
            notifyFileRenamed(source, destination)
            PluginHostEventDispatcher.emitFileRenamed(source, destination, wasDirectory)
            retargetRecentFiles(source, destination)
        }
        return moved
    }

    override fun searchFiles(query: String): List<File> {
        val project = _currentProject.value ?: return emptyList()
        val projectDir = File(project.rootPath)
        return searchFilesRecursive(projectDir, query)
    }

    override fun getRecentFiles(): List<File> = synchronized(recentFilesLock) {
        recentFiles.toList()
    }

    override fun addFileWatcher(path: String, listener: FileChangeListener): FileWatchRegistration {
        synchronized(fileWatchLock) {
            fileListeners.getOrPut(path) { mutableListOf() }.add(listener)
            ensureWatchTreeLocked(path)
        }

        var disposed = false
        return object : FileWatchRegistration {
            override fun dispose() {
                if (disposed) return
                disposed = true
                removeFileWatcherListener(path, listener)
            }
        }
    }

    private fun ensureWatchTreeLocked(path: String) {
        if (fileWatchTrees.containsKey(path)) return

        val target = File(path)
        val watchRootDir = when {
            target.isDirectory -> target
            target.parentFile?.isDirectory == true -> target.parentFile
            else -> return
        }
        val watchTree = WatchTree(
            registrationPath = path,
            watchRootDir = watchRootDir
        )
        fileWatchTrees[path] = watchTree
        watchTree.start()
    }

    private fun handleFileEvent(directory: File, event: Int, child: String?) {
        val file = if (child != null) File(directory, child) else directory
        try {
            if ((event and FileObserver.CREATE) != 0 || (event and FileObserver.MOVED_TO) != 0) {
                notifyFileCreated(file)
            }
            if ((event and FileObserver.MODIFY) != 0 || (event and FileObserver.CLOSE_WRITE) != 0) {
                notifyFileModified(file)
            }
            if ((event and FileObserver.DELETE) != 0 ||
                (event and FileObserver.DELETE_SELF) != 0 ||
                (event and FileObserver.MOVED_FROM) != 0
            ) {
                notifyFileDeleted(file)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling file event: $event for $file")
        }
    }
    private fun removeFileWatcherListener(path: String, listener: FileChangeListener) {
        val watchTreeToStop = synchronized(fileWatchLock) {
            val listeners = fileListeners[path] ?: return
            listeners.remove(listener)
            if (listeners.isNotEmpty()) return@synchronized null

            fileListeners.remove(path)
            fileWatchTrees.remove(path)
        }
        if (watchTreeToStop != null) {
            fileWatcherScope.launch { watchTreeToStop.stop() }
        }
    }

    private fun stopAllFileWatchersAsync() {
        val watchTrees = synchronized(fileWatchLock) {
            fileWatchTrees.values.toList().also {
                fileWatchTrees.clear()
                fileListeners.clear()
            }
        }
        watchTrees.forEach { watchTree ->
            fileWatcherScope.launch { watchTree.stop() }
        }
    }

    private fun shouldWatchDirectory(dir: File, watchRootDir: File): Boolean {
        if (dir == watchRootDir) return true
        return !ProjectPathFilters.isNoisyDirectoryName(dir.name)
    }

    private inner class WatchTree(
        private val registrationPath: String,
        private val watchRootDir: File
    ) {
        private val observerLock = Any()
        private val observers = linkedMapOf<String, FileObserver>()
        private val stopped = AtomicBoolean(false)
        private var startJob: Job? = null
        private val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.MODIFY or
            FileObserver.CLOSE_WRITE or FileObserver.DELETE or FileObserver.DELETE_SELF or
            FileObserver.MOVED_FROM

        fun start() {
            startJob = fileWatcherScope.launch {
                registerSubtree(watchRootDir)
                val observerCount = synchronized(observerLock) { observers.size }
                Timber.tag(TAG).d(
                    "WatchTree started: registration=%s root=%s observers=%d",
                    registrationPath,
                    watchRootDir.absolutePath,
                    observerCount
                )
            }
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            startJob?.cancel()
            startJob = null
            val snapshot = synchronized(observerLock) {
                observers.values.toList().also { observers.clear() }
            }
            snapshot.forEach(FileObserver::stopWatching)
        }

        private fun registerSubtree(rootDir: File) {
            if (!rootDir.exists() || !rootDir.isDirectory) return

            val stack = ArrayDeque<File>()
            stack.add(rootDir)
            while (stack.isNotEmpty()) {
                if (stopped.get()) return
                val dir = stack.removeLast()
                if (!shouldWatchDirectory(dir, watchRootDir)) continue
                registerSingleDirectory(dir)
                dir.listFiles()
                    ?.asSequence()
                    ?.filter(File::isDirectory)
                    ?.forEach(stack::addLast)
            }
        }

        private fun registerSingleDirectory(dir: File) {
            if (stopped.get()) return
            val path = dir.absolutePath
            synchronized(observerLock) {
                if (stopped.get()) return
                if (observers.containsKey(path)) return

                val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(dir, mask) {
                        override fun onEvent(event: Int, child: String?) {
                            handleObservedEvent(dir, event, child)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(path, mask) {
                        override fun onEvent(event: Int, child: String?) {
                            handleObservedEvent(dir, event, child)
                        }
                    }
                }
                observer.startWatching()
                observers[path] = observer
            }
        }

        private fun handleObservedEvent(directory: File, event: Int, child: String?) {
            if (stopped.get()) return
            val file = if (child != null) File(directory, child) else directory

            if ((event and (FileObserver.CREATE or FileObserver.MOVED_TO)) != 0 && file.isDirectory) {
                registerSubtree(file)
            }
            if ((event and (FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MOVED_FROM)) != 0) {
                unregisterSubtree(file.absolutePath)
            }

            handleFileEvent(directory, event, child)
        }

        private fun unregisterSubtree(path: String) {
            val normalizedPath = path.trimEnd(File.separatorChar)
            val subtreePrefix = "$normalizedPath${File.separator}"
            val removedObservers = synchronized(observerLock) {
                observers.keys
                    .filter { observerPath ->
                        observerPath == normalizedPath || observerPath.startsWith(subtreePrefix)
                    }
                    .toList()
                    .mapNotNull { observerPath -> observers.remove(observerPath) }
            }
            removedObservers.forEach(FileObserver::stopWatching)
        }
    }

    fun addToRecentFiles(file: File) {
        if (!file.exists() || file.isDirectory) return
        synchronized(recentFilesLock) {
            recentFiles.remove(file)
            recentFiles.add(0, file)
            while (recentFiles.size > MAX_RECENT_FILES) {
                recentFiles.removeAt(recentFiles.size - 1)
            }
        }
        saveRecentFiles()
    }

    private fun searchFilesRecursive(dir: File, query: String): List<File> {
        if (query.isBlank()) return emptyList()

        val results = mutableListOf<File>()
        val directories = ArrayDeque<File>()
        directories.add(dir)

        while (directories.isNotEmpty()) {
            val currentDir = directories.removeLast()
            currentDir.listFiles()?.forEach { child ->
                if (child.name.contains(query, ignoreCase = true)) {
                    results.add(child)
                }
                if (child.isDirectory && shouldWatchDirectory(child, dir)) {
                    directories.addLast(child)
                }
            }
        }
        return results
    }

    private fun notifyFileCreated(file: File) {
        listenersFor(file).forEach { l ->
            try {
                l.onFileCreated(file)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error notifying file created")
            }
        }
    }

    private fun notifyFileModified(file: File) {
        listenersFor(file).forEach { l ->
            try {
                l.onFileModified(file)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error notifying file modified")
            }
        }
    }

    private fun notifyFileDeleted(file: File) {
        listenersFor(file).forEach { l ->
            try {
                l.onFileDeleted(file)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error notifying file deleted")
            }
        }
    }

    private fun notifyFileRenamed(oldFile: File, newFile: File) {
        (listenersFor(oldFile) + listenersFor(newFile)).distinct().forEach { listener ->
            try {
                listener.onFileRenamed(oldFile, newFile)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error notifying file renamed")
            }
        }
    }

    private fun listenersFor(file: File): List<FileChangeListener> {
        val targetPath = file.absoluteFile.normalize().path
        val listenerSnapshot = synchronized(fileWatchLock) {
            fileListeners.mapValues { (_, listeners) -> listeners.toList() }
        }
        return listenerSnapshot.asSequence()
            .filter { (watchPath, _) ->
                val normalizedWatchPath = File(watchPath).absoluteFile.normalize().path
                targetPath == normalizedWatchPath ||
                    targetPath.startsWith(normalizedWatchPath + File.separator)
            }
            .flatMap { it.value.asSequence() }
            .distinct()
            .toList()
    }

    private fun retargetRecentFiles(oldPath: File, newPath: File) {
        val oldRoot = normalizeRecentLookupPath(oldPath.absolutePath).trimEnd('/')
        if (oldRoot.isBlank()) return

        val oldRootCompare = normalizeRecentLookupPathForCompare(oldRoot)
        val oldPrefixCompare = "$oldRootCompare/"
        synchronized(recentFilesLock) {
            recentFiles.indices.forEach { index ->
                val recent = recentFiles[index]
                val recentPath = normalizeRecentLookupPath(recent.absolutePath)
                val recentCompare = normalizeRecentLookupPathForCompare(recentPath)
                recentFiles[index] = when {
                    recentCompare == oldRootCompare -> newPath
                    recentCompare.startsWith(oldPrefixCompare) -> {
                        val suffix = recentPath.substring(oldRoot.length + 1)
                        File(newPath, suffix.replace('/', File.separatorChar))
                    }
                    else -> recent
                }
            }
        }
        saveRecentFiles()
    }

    private fun removeRecentFilesForDeletedPath(deletedPath: File) {
        val targetRoot = normalizeRecentLookupPath(deletedPath.absolutePath).trimEnd('/')
        if (targetRoot.isBlank()) return

        val targetRootCompare = normalizeRecentLookupPathForCompare(targetRoot)
        val targetPrefixCompare = "$targetRootCompare/"
        val removed = synchronized(recentFilesLock) {
            recentFiles.removeAll { recent ->
                val recentCompare = normalizeRecentLookupPathForCompare(
                    normalizeRecentLookupPath(recent.absolutePath)
                )
                recentCompare == targetRootCompare || recentCompare.startsWith(targetPrefixCompare)
            }
        }
        if (removed) saveRecentFiles()
    }

    private fun normalizeRecentLookupPath(path: String): String = path.replace('\\', '/')

    private fun normalizeRecentLookupPathForCompare(path: String): String = if (File.separatorChar == '\\') {
        path.lowercase()
    } else {
        path
    }

    private fun loadRecentFiles() {
        try {
            val paths = configManager.get(ConfigKeys.RecentFiles)
            if (paths.isNotEmpty()) {
                val existingFiles = paths.split(";")
                    .map(::File)
                    .filter(File::exists)
                synchronized(recentFilesLock) {
                    recentFiles.clear()
                    recentFiles.addAll(existingFiles)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error loading recent files")
        }
    }

    private fun saveRecentFiles() {
        try {
            val paths = synchronized(recentFilesLock) {
                recentFiles.joinToString(";") { it.absolutePath }
            }
            configManager.set(ConfigKeys.RecentFiles, paths)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error saving recent files")
        }
    }
}
