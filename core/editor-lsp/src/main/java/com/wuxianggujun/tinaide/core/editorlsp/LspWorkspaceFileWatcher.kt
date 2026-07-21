package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.lsp.LspClientSession
import com.wuxianggujun.tinaide.file.FileChangeListener
import com.wuxianggujun.tinaide.file.FileWatchRegistration
import com.wuxianggujun.tinaide.file.IFileWatchService
import java.io.File
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.Unregistration
import timber.log.Timber

/**
 * workspace/didChangeWatchedFiles 生命周期与模式匹配。
 * 从 [LspEditorManager] 抽出，降低编辑器会话类体量。
 */
internal class LspWorkspaceFileWatcher(
    private val stateLock: Any,
    private val fileWatchService: IFileWatchService?,
    private val projectRootProvider: () -> String?,
    private val collectSessions: () -> List<LspClientSession>,
    private val hasActiveCxxBindings: () -> Boolean,
    private val hasSharedCxxSession: () -> Boolean,
) {
    companion object {
        private const val TAG = "LspWorkspaceFileWatch"
    }

    private var workspaceFileWatcher: FileWatchRegistration? = null
    private val watchedPatterns = mutableListOf<Pair<String, List<LspFileWatchPattern>>>()

    fun isActive(): Boolean = synchronized(stateLock) {
        workspaceFileWatcher != null
    }

    fun start(workspaceRoot: String) {
        val watchService = fileWatchService
        if (watchService == null) {
            Timber.tag(TAG).w("Workspace file watcher unavailable for: %s", workspaceRoot)
            return
        }
        val rootFile = File(workspaceRoot)
        if (!rootFile.isDirectory) return

        val registration = runCatching {
            watchService.addFileWatcher(workspaceRoot, object : FileChangeListener {
                override fun onFileCreated(file: File) {
                    notifyChange(file, FileChangeType.Created)
                }

                override fun onFileModified(file: File) {
                    notifyChange(file, FileChangeType.Changed)
                }

                override fun onFileDeleted(file: File) {
                    notifyChange(file, FileChangeType.Deleted)
                }

                override fun onFileRenamed(oldFile: File, newFile: File) {
                    notifyChange(oldFile, FileChangeType.Deleted)
                    notifyChange(newFile, FileChangeType.Created)
                }
            })
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Workspace file watcher failed to start for: %s", workspaceRoot)
        }.getOrNull() ?: return

        val previous = synchronized(stateLock) {
            workspaceFileWatcher.also { workspaceFileWatcher = registration }
        }
        previous?.dispose()
        Timber.tag(TAG).i("Workspace file watcher started for: %s", workspaceRoot)
    }

    fun stop() {
        val registration = synchronized(stateLock) {
            watchedPatterns.clear()
            workspaceFileWatcher.also { workspaceFileWatcher = null }
        }
        registration?.dispose()
    }

    fun stopIfCxxIdle(): Boolean {
        val registration = synchronized(stateLock) {
            if (hasSharedCxxSession() || hasActiveCxxBindings()) {
                return false
            }
            watchedPatterns.clear()
            workspaceFileWatcher.also { workspaceFileWatcher = null }
        }
        registration?.dispose()
        return true
    }

    fun onCapabilityRegistered(registrations: List<Registration>) {
        val fileWatcherRegs = registrations.filter { it.method == "workspace/didChangeWatchedFiles" }
        if (fileWatcherRegs.isEmpty()) return
        val projectRoot = projectRootProvider()
        synchronized(stateLock) {
            fileWatcherRegs.forEach { reg ->
                val options = reg.registerOptions
                if (options is DidChangeWatchedFilesRegistrationOptions) {
                    val globs = options.watchers.mapNotNull { watcher ->
                        LspFileWatchPattern.fromWatcher(watcher, projectRoot)
                    }
                    if (globs.isNotEmpty()) {
                        watchedPatterns.add(reg.id to globs)
                        Timber.tag(TAG).i("Registered file watchers [%s]: %s", reg.id, globs)
                    }
                }
            }
        }
    }

    fun onCapabilityUnregistered(unregistrations: List<Unregistration>) {
        val ids = unregistrations.map { it.id }.toSet()
        synchronized(stateLock) { watchedPatterns.removeAll { (id, _) -> id in ids } }
    }

    private fun notifyChange(file: File, eventType: FileChangeType) {
        val path = file.absolutePath
        val patterns = synchronized(stateLock) { watchedPatterns.toList() }
        val eventMask = when (eventType) {
            FileChangeType.Created -> LspFileWatchPattern.CREATE_EVENT
            FileChangeType.Changed -> LspFileWatchPattern.CHANGE_EVENT
            FileChangeType.Deleted -> LspFileWatchPattern.DELETE_EVENT
        }
        val matched = patterns.any { (_, globs) -> globs.any { it.matches(path, eventMask) } }
        if (!matched) return

        val changes = listOf(FileEvent(file.toURI().toString(), eventType))
        collectSessions().forEach { session ->
            runCatching { session.didChangeWatchedFiles(changes) }
                .onFailure { error -> Timber.tag(TAG).w(error, "didChangeWatchedFiles failed: %s", path) }
        }
        Timber.tag(TAG).d("didChangeWatchedFiles: %s (%s)", path, eventType)
    }
}
