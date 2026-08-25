package com.wuxianggujun.tinaide.editor

import android.content.Context
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.config.ConfigChangeListener
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.session.AutoSaveScheduler
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.DocumentSessionState
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import com.wuxianggujun.tinaide.editor.session.ProjectSessionFileSnapshot
import com.wuxianggujun.tinaide.editor.session.ProjectSessionSnapshot
import com.wuxianggujun.tinaide.editor.session.ProjectSessionStorage
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.file.IProjectContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

class EditorManager(
    private val context: Context,
    private val configManager: IConfigManager,
    private val projectContextProvider: () -> IProjectContext? = { null },
    private val projectSymbolIndexServiceProvider: () -> ProjectSymbolIndexService? = { null },
) : IEditorManager,
    ServiceLifecycle {

    companion object {
        private const val TAG = "EditorManager"
        private const val KEY_AUTO_SAVE = "project.autoSave"
    }

    private val sessionStorage = ProjectSessionStorage(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val persistMutex = Mutex()
    private val persistSequence = AtomicLong(0L)
    private val sessions = LinkedHashMap<String, DocumentSession>()
    private val tabs = mutableListOf<EditorTab>()
    private val sessionStateJobs = mutableMapOf<String, Job>()
    private var restoreJob: Job? = null
    private var autoSaveIntervalMillis: Long = 60_000L
    private val autoSaveScheduler = AutoSaveScheduler(scope, { autoSaveIntervalMillis }) { session ->
        session.save(SaveReason.AUTO)
        val state = session.state.value
        state.isDirty && !state.hasExternalModification
    }
    private var activeTabId: String? = null
    private var isRestoringState: Boolean = false

    // StateFlow 暴露给 Compose UI（避免 EditorContainerState 维护重复状态）
    private val _tabsFlow = MutableStateFlow<List<EditorTab>>(emptyList())
    override val tabsFlow: StateFlow<List<EditorTab>> = _tabsFlow.asStateFlow()

    private val _activeTabIdFlow = MutableStateFlow<String?>(null)
    override val activeTabIdFlow: StateFlow<String?> = _activeTabIdFlow.asStateFlow()

    private sealed interface PersistTask {
        val projectPath: String

        data class Save(
            override val projectPath: String,
            val snapshot: ProjectSessionSnapshot
        ) : PersistTask

        data class Clear(
            override val projectPath: String
        ) : PersistTask
    }

    private val configListener = object : ConfigChangeListener {
        override fun onConfigChanged(key: String, newValue: Any?) {
            when (key) {
                KEY_AUTO_SAVE -> updateAutoSaveInterval()
                ConfigKeys.CurrentProject.key -> onProjectPathChanged(newValue?.toString())
            }
        }
    }

    override fun onCreate() {
        updateAutoSaveInterval()
        configManager.addListener(KEY_AUTO_SAVE, configListener)
        configManager.addListener(ConfigKeys.CurrentProject.key, configListener)
        restoreEditorState()
    }

    override fun onDestroy() {
        persistEditorState()
        configManager.removeListener(KEY_AUTO_SAVE, configListener)
        configManager.removeListener(ConfigKeys.CurrentProject.key, configListener)
        val jobs: List<Job>
        val sessionsToStop: List<DocumentSession>
        synchronized(stateLock) {
            jobs = sessionStateJobs.values.toList()
            sessionStateJobs.clear()
            sessionsToStop = sessions.values.toList()
            sessions.clear()
            tabs.clear()
            activeTabId = null
            updateStateFlowsLocked()
        }
        jobs.forEach { it.cancel() }
        restoreJob?.cancel()
        restoreJob = null
        autoSaveScheduler.cancelAll()
        sessionsToStop.forEach { it.stopFileWatcher() }
        scope.cancel()
    }

    /**
     * 更新 StateFlow（在修改 tabs 或 activeTabId 后调用）
     */
    private fun updateStateFlowsLocked() {
        _tabsFlow.value = tabs.toList()
        _activeTabIdFlow.value = activeTabId
    }

    override fun openFile(file: File, initialViewState: EditorViewState?): EditorTab {
        while (true) {
            synchronized(stateLock) {
                sessions.values.find { it.file.absolutePath == file.absolutePath }?.let { existing ->
                    val existingTab = tabs.firstOrNull { it.id == existing.tabId }
                    if (existingTab != null) {
                        if (initialViewState != null) {
                            existing.updateViewState(initialViewState)
                        }
                        return existingTab
                    }
                }
            }

            val newTab = EditorTab(id = UUID.randomUUID().toString(), file = file)
            val newSession = DocumentSession(
                context = context,
                tabId = newTab.id,
                file = file,
                projectSymbolIndexServiceProvider = projectSymbolIndexServiceProvider,
                initialViewState = initialViewState,
                coroutineScope = scope
            )

            val created = synchronized(stateLock) {
                sessions.values.find { it.file.absolutePath == file.absolutePath }?.let { existing ->
                    val existingTab = tabs.firstOrNull { it.id == existing.tabId }
                    if (existingTab != null) {
                        if (initialViewState != null) {
                            existing.updateViewState(initialViewState)
                        }
                        return@synchronized false
                    }
                }

                sessions[newTab.id] = newSession
                tabs.add(newTab)
                updateStateFlowsLocked()
                true
            }

            if (!created) {
                newSession.stopFileWatcher()
                continue
            }

            observeSession(newSession)
            Timber.tag(TAG).d("Open file: %s", file.absolutePath)
            persistEditorState()
            return newTab
        }
    }

    override fun closeFile(tab: EditorTab) {
        val sessionToStop: DocumentSession?
        val jobToCancel: Job?
        val removed: Boolean
        synchronized(stateLock) {
            jobToCancel = sessionStateJobs.remove(tab.id)
            sessionToStop = sessions.remove(tab.id)
            removed = tabs.removeAll { it.id == tab.id }
            if (activeTabId == tab.id) {
                activeTabId = null
            }
            if (removed) {
                updateStateFlowsLocked()
            }
        }
        jobToCancel?.cancel()
        autoSaveScheduler.cancel(tab.id)
        sessionToStop?.stopFileWatcher()
        if (!removed) return
        Timber.tag(TAG).d("Close tab: %s", tab.id)
        persistEditorState()
    }

    override fun closeAll(clearPersistentState: Boolean) {
        val snapshot = synchronized(stateLock) { tabs.toList() }
        if (!clearPersistentState) {
            persistEditorState()
        }
        withStatePersistenceSuspended {
            snapshot.forEach { closeFile(it) }
        }
        if (clearPersistentState) {
            persistEditorState()
        }
    }

    override fun getOpenTabs(): List<EditorTab> = synchronized(stateLock) { tabs.toList() }

    override fun getActiveTabId(): String? = synchronized(stateLock) { activeTabId }

    override fun setActiveTab(tabId: String?) {
        val changed = synchronized(stateLock) {
            if (activeTabId == tabId) {
                false
            } else {
                activeTabId = tabId
                updateStateFlowsLocked()
                true
            }
        }
        if (!changed) return
        persistEditorState()
    }

    override fun retargetOpenTabsForMovedPath(oldPath: File, newPath: File): List<EditorTab> {
        val retargetedTabs = mutableListOf<EditorTab>()
        val changed = synchronized(stateLock) {
            var hasChanges = false
            tabs.indices.forEach { index ->
                val tab = tabs[index]
                val retargetedFile = resolveMovedPathForOpenFile(
                    openFile = tab.file,
                    oldPath = oldPath,
                    newPath = newPath
                ) ?: return@forEach

                val updatedTab = tab.copy(file = retargetedFile)
                tabs[index] = updatedTab
                sessions[tab.id]?.retargetFile(retargetedFile)
                retargetedTabs += updatedTab
                hasChanges = true
            }
            if (hasChanges) {
                updateStateFlowsLocked()
            }
            hasChanges
        }
        if (changed) {
            persistEditorState()
        }
        return retargetedTabs
    }

    override fun getSession(tabId: String): DocumentSession? = synchronized(stateLock) { sessions[tabId] }

    override fun getSessionState(tabId: String): StateFlow<DocumentSessionState>? = synchronized(stateLock) { sessions[tabId]?.state }

    override suspend fun save(tabId: String, reason: SaveReason): SaveResult {
        val session = synchronized(stateLock) { sessions[tabId] }
            ?: return SaveResult.Failure(Strings.editor_error_session_not_found.strOr(context))
        return session.save(reason)
    }

    override suspend fun saveAll(reason: SaveReason): List<SaveResult> {
        val sessionSnapshot = synchronized(stateLock) { sessions.values.toList() }
        val targets = sessionSnapshot.filter { it.refreshDirtyStateForSave() }
        if (targets.isEmpty()) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            targets.map { session ->
                session.save(reason)
            }
        }
    }

    override fun performUndo(tabId: String) {
        synchronized(stateLock) { sessions[tabId] }?.requestUndo()
    }

    override fun performRedo(tabId: String) {
        synchronized(stateLock) { sessions[tabId] }?.requestRedo()
    }

    private fun currentProjectPath(): String? {
        val activeProject = projectContextProvider()
            ?.getCurrentProject()
            ?.rootPath
            ?.takeIf { it.isNotBlank() }
        if (!activeProject.isNullOrBlank()) {
            return activeProject
        }
        return configManager.get(ConfigKeys.CurrentProject).takeIf { it.isNotBlank() }
    }

    private inline fun <T> withStatePersistenceSuspended(block: () -> T): T {
        val previous = synchronized(stateLock) {
            val value = isRestoringState
            isRestoringState = true
            value
        }
        return try {
            block()
        } finally {
            synchronized(stateLock) {
                isRestoringState = previous
            }
        }
    }

    private fun onProjectPathChanged(newPath: String?) {
        val normalized = newPath?.takeIf { it.isNotBlank() }
        restoreJob?.cancel()
        restoreJob = null
        withStatePersistenceSuspended {
            closeAll()
        }
        if (normalized != null) {
            restoreEditorState(normalized)
        }
    }

    private fun restoreEditorState(projectPathOverride: String? = currentProjectPath()) {
        val projectPath = projectPathOverride?.takeIf { it.isNotBlank() } ?: return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            try {
                val snapshot = sessionStorage.load(projectPath) ?: return@launch
                val entries = snapshot.files.mapNotNull { fileSnapshot ->
                    val file = File(fileSnapshot.path)
                    if (file.exists()) {
                        file to EditorViewState(
                            cursorLine = fileSnapshot.cursorLine,
                            cursorColumn = fileSnapshot.cursorColumn,
                            scrollX = fileSnapshot.scrollX,
                            scrollY = fileSnapshot.scrollY
                        )
                    } else {
                        Timber.tag(TAG).w("Skip restoring editor tab, file missing: %s", fileSnapshot.path)
                        null
                    }
                }
                val activeFilePath = snapshot.activeFile?.takeIf { it.isNotBlank() }
                withStatePersistenceSuspended {
                    entries.forEach { (file, state) -> openFile(file, state) }
                    synchronized(stateLock) {
                        activeTabId = activeFilePath?.let { desired ->
                            tabs.firstOrNull { it.file.absolutePath == desired }
                        }?.id ?: tabs.firstOrNull()?.id
                        updateStateFlowsLocked()
                    }
                }
                persistEditorState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Failed to restore editor state for project: %s", projectPath)
            }
        }
    }

    override fun persistStateSnapshot() {
        persistEditorState()
    }

    private fun persistEditorState() {
        val projectPath = currentProjectPath() ?: return
        val task = synchronized(stateLock) {
            if (isRestoringState) {
                null
            } else if (tabs.isEmpty()) {
                PersistTask.Clear(projectPath)
            } else {
                val activePath = activeTabId?.let { id ->
                    tabs.firstOrNull { it.id == id }?.file?.absolutePath
                }
                val files = tabs.map { tab ->
                    val state = sessions[tab.id]?.state?.value
                    ProjectSessionFileSnapshot(
                        path = tab.file.absolutePath,
                        cursorLine = state?.cursorLine ?: 0,
                        cursorColumn = state?.cursorColumn ?: 0,
                        scrollX = state?.scrollX ?: 0,
                        scrollY = state?.scrollY ?: 0
                    )
                }
                val snapshot = ProjectSessionSnapshot(
                    activeFile = activePath,
                    files = files,
                    updatedAt = System.currentTimeMillis()
                )
                PersistTask.Save(projectPath, snapshot)
            }
        }
        if (task == null) return
        enqueuePersistTask(task)
    }

    private fun updateAutoSaveInterval() {
        val raw = configManager.get(KEY_AUTO_SAVE, "60")
        val seconds = raw.toString().toLongOrNull() ?: 60L
        autoSaveIntervalMillis = if (seconds <= 0L) {
            0L
        } else {
            seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
        }
        if (autoSaveIntervalMillis <= 0L) {
            autoSaveScheduler.cancelAll()
        } else {
            synchronized(stateLock) {
                sessions.values.forEach { session ->
                    val state = session.state.value
                    if (state.isDirty && !state.hasExternalModification) {
                        autoSaveScheduler.schedule(session)
                    }
                }
            }
        }
        Timber.tag(TAG).d("Auto save interval: %dms", autoSaveIntervalMillis)
    }

    private fun observeSession(session: DocumentSession) {
        val job = scope.launch {
            var previousAutoSaveEligible: Boolean? = null
            var previousError: String? = null
            session.state.collect { state ->
                val autoSaveEligible = state.isDirty && !state.hasExternalModification
                if (autoSaveEligible != previousAutoSaveEligible) {
                    if (autoSaveEligible) {
                        autoSaveScheduler.schedule(session)
                    } else {
                        autoSaveScheduler.cancel(session)
                    }
                    previousAutoSaveEligible = autoSaveEligible
                }
                val lastError = state.lastError
                if (lastError != null && lastError != previousError) {
                    Timber.tag(TAG).w("Session %s error: %s", session.tabId, lastError)
                }
                previousError = lastError
            }
        }
        val previous = synchronized(stateLock) {
            sessionStateJobs.put(session.tabId, job)
        }
        previous?.cancel()
    }

    private fun enqueuePersistTask(task: PersistTask) {
        val sequence = persistSequence.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                if (sequence < persistSequence.get()) return@withLock
                runCatching {
                    when (task) {
                        is PersistTask.Clear -> {
                            sessionStorage.clear(task.projectPath)
                        }
                        is PersistTask.Save -> {
                            sessionStorage.save(task.projectPath, task.snapshot)
                        }
                    }
                }.onFailure { t ->
                    Timber.tag(TAG).e(t, "Failed to persist editor state")
                }
            }
        }
    }

    private fun resolveMovedPathForOpenFile(
        openFile: File,
        oldPath: File,
        newPath: File
    ): File? {
        val oldNormalized = normalizeFileLookupPath(oldPath.absolutePath).trimEnd('/')
        if (oldNormalized.isBlank()) return null

        val openNormalized = normalizeFileLookupPath(openFile.absolutePath)
        val compareOld = normalizeFileLookupPathForCompare(oldNormalized)
        val compareOpen = normalizeFileLookupPathForCompare(openNormalized)
        val oldPrefix = "$compareOld/"
        return when {
            compareOpen == compareOld -> newPath
            compareOpen.startsWith(oldPrefix) -> {
                val suffix = openNormalized.substring(oldNormalized.length + 1)
                File(newPath, suffix.replace('/', File.separatorChar))
            }
            else -> null
        }
    }

    private fun normalizeFileLookupPath(path: String): String = path.replace('\\', '/')

    private fun normalizeFileLookupPathForCompare(path: String): String = if (File.separatorChar == '\\') {
        path.lowercase()
    } else {
        path
    }

    // ========== 外部文件修改相关方法 ==========

    override suspend fun forceOverwrite(tabId: String, reason: SaveReason): SaveResult = synchronized(stateLock) { sessions[tabId] }?.forceOverwrite(reason)
        ?: SaveResult.Failure(Strings.editor_error_not_initialized.strOr(context))

    override suspend fun reloadFromDisk(tabId: String): Boolean = synchronized(stateLock) { sessions[tabId] }?.reloadFromDisk() ?: false

    override fun acknowledgeExternalModification(tabId: String) {
        synchronized(stateLock) { sessions[tabId] }?.acknowledgeExternalModification()
    }
}
