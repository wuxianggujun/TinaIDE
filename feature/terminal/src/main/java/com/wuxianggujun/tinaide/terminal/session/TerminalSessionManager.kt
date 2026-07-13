package com.wuxianggujun.tinaide.terminal.session

import android.app.Application
import com.termux.terminal.TerminalSession
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.terminal.persistence.ProjectTerminalState
import com.wuxianggujun.tinaide.terminal.persistence.TerminalSessionSnapshot
import com.wuxianggujun.tinaide.terminal.persistence.TerminalStateStorage
import com.wuxianggujun.tinaide.terminal.shell.ShellResolveResult
import com.wuxianggujun.tinaide.terminal.shell.TerminalBackend
import com.wuxianggujun.tinaide.terminal.shell.TerminalShellResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 基于 Termux 的终端会话管理器
 *
 * 管理多个终端会话的生命周期，支持创建、关闭、切换会话。
 *
 * @param application Android Application 上下文
 * @param scope 协程作用域
 */
class TerminalSessionManager(
    private val application: Application,
    private val scope: CoroutineScope
) {
    private val shellResolver by lazy { TerminalShellResolver(application) }
    private val stateStorage = TerminalStateStorage(application)

    // 会话列表
    private val _sessions = MutableStateFlow<List<TerminalSessionState>>(emptyList())
    val sessions: StateFlow<List<TerminalSessionState>> = _sessions.asStateFlow()

    // 当前活动会话 ID
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    // 用于触发 UI 重绘的帧 ID
    private val _frameId = MutableStateFlow(0L)
    val frameId: StateFlow<Long> = _frameId.asStateFlow()

    // 会话计数器（用于生成标题）
    private var sessionCounter = 0

    // 当前项目路径（用于持久化）
    private var currentProjectPath: String? = null

    // TerminalSessionClient 实例（每个会话共享）
    private lateinit var sessionClient: TinaTerminalSessionClient

    // shell 探测可能挂起数秒；关闭/重启会话时必须取消旧启动任务并拒绝迟到结果。
    private val sessionStartJobs = ConcurrentHashMap<String, Job>()

    // 高频终端输出按显示帧合并，避免每个字符都触发一次 Compose 重组。
    private val frameUpdateScheduled = AtomicBoolean(false)

    /**
     * 初始化会话客户端
     */
    fun initialize() {
        if (::sessionClient.isInitialized) return
        sessionClient = TinaTerminalSessionClient(
            context = application,
            onTextChanged = { session ->
                // 触发 UI 更新
                requestFrameUpdate()
            },
            onTitleChanged = { session ->
                // 更新会话标题
                val title = session.title
                if (!title.isNullOrEmpty()) {
                    updateSessionByTermuxSession(session) { it.withTitle(title) }
                }
            },
            onSessionFinished = { session ->
                // 处理会话结束
                val exitCode = session.exitStatus
                updateSessionByTermuxSession(session) { it.withExited(exitCode) }
                requestFrameUpdate()
            },
            onBell = { _ ->
                // 可以在这里添加响铃反馈
            },
            onColorsChanged = { _ ->
                requestFrameUpdate()
            },
            onCursorStateChange = { _ ->
                requestFrameUpdate()
            },
            onShellPidSet = { session, pid ->
                updateSessionByTermuxSession(session) { it.withShellPid(pid) }
            },
            onCustomOsc = { session, code, text ->
                if (code == OSC_CODE_TINA && text.startsWith(OSC_RUN_END_PREFIX)) {
                    val exitCode = text.substring(OSC_RUN_END_PREFIX.length)
                        .toIntOrNull()
                        ?: return@TinaTerminalSessionClient
                    updateSessionByTermuxSession(session) { it.withRunEnded(exitCode) }
                    requestFrameUpdate()
                }
            }
        )
    }

    /**
     * 获取当前活动会话
     */
    fun getActiveSession(): TerminalSessionState? {
        val activeId = _activeSessionId.value ?: return null
        return _sessions.value.find { it.id == activeId }
    }

    /**
     * 创建新的终端会话
     *
     * @param workDir 工作目录
     * @param rows 终端行数
     * @param cols 终端列数
     * @param backend 终端后端（HOST 或 PROOT）
     * @param initialCommand 一次性执行的内部命令；非空时不启动交互 shell
     * @return 新会话的 ID
     */
    fun createSession(
        workDir: String = "/",
        rows: Int = 24,
        cols: Int = 80,
        backend: TerminalBackend = TerminalBackend.HOST,
        initialCommand: String? = null,
    ): String {
        sessionCounter++
        val title = if (sessionCounter == 1) "Terminal" else "Terminal $sessionCounter"

        val sessionState = TerminalSessionState.create(
            title = title,
            backend = backend
        )

        // 添加到会话列表
        _sessions.update { it + sessionState }

        // 设置为活动会话
        _activeSessionId.value = sessionState.id

        // 启动终端会话
        startTerminalSession(sessionState.id, workDir, rows, cols, initialCommand)

        return sessionState.id
    }

    /**
     * 启动终端会话
     *
     * 注意：TerminalSession 必须在主线程上创建，因为它内部使用了 Handler
     */
    private fun startTerminalSession(
        sessionId: String,
        workDir: String,
        rows: Int,
        cols: Int,
        initialCommand: String? = null,
    ) {
        sessionStartJobs.remove(sessionId)?.cancel()
        val launchJob = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext().job
            try {
                val sessionState = getSessionById(sessionId) ?: return@launch
                val resolveResult = withContext(Dispatchers.IO) {
                    shellResolver.resolveForSession(
                        backend = sessionState.backend,
                        workDir = workDir,
                        rows = rows,
                        cols = cols,
                        initialCommand = initialCommand,
                    )
                }

                val resolution = when (resolveResult) {
                    is ShellResolveResult.Success -> resolveResult.resolution
                    is ShellResolveResult.Error -> {
                        updateSessionState(sessionId) { it.withError(resolveResult.message) }
                        return@launch
                    }
                }

                if (!isCurrentStart(sessionId, runningJob)) return@launch

                // 确定工作目录（在 IO 线程执行文件检查）
                val cwd = withContext(Dispatchers.IO) {
                    if (File(resolution.cwd).exists()) resolution.cwd else "/"
                }

                if (!isCurrentStart(sessionId, runningJob)) return@launch

                // 创建 Termux TerminalSession（必须在主线程）
                val termuxSession = TerminalSession(
                    resolution.shellPath,
                    cwd,
                    resolution.argv,
                    resolution.env,
                    null, // transcriptRows, null 使用默认值
                    sessionClient
                )

                if (!isCurrentStart(sessionId, runningJob)) {
                    termuxSession.suppressExitNotice = true
                    termuxSession.finishIfRunning()
                    return@launch
                }

                // 更新会话状态
                updateSessionState(sessionId) {
                    it.copy(
                        session = termuxSession,
                        status = SessionStatus.RUNNING
                    )
                }
                requestFrameUpdate()
            } catch (_: CancellationException) {
                // 关闭或重启会话时取消旧探测任务；不应把取消显示成启动失败。
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to start terminal session")
                updateSessionState(sessionId) {
                    it.withError(
                        application.getString(
                            Strings.terminal_session_start_failed,
                            t.message ?: t::class.java.simpleName
                        )
                    )
                }
            } finally {
                sessionStartJobs.remove(sessionId, runningJob)
            }
        }
        sessionStartJobs[sessionId] = launchJob
        launchJob.start()
    }

    private fun isCurrentStart(sessionId: String, job: Job): Boolean =
        sessionStartJobs[sessionId] === job && getSessionById(sessionId) != null

    private fun cancelSessionStart(sessionId: String) {
        sessionStartJobs.remove(sessionId)?.cancel()
    }

    private fun requestFrameUpdate() {
        if (!frameUpdateScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Main) {
            delay(FRAME_UPDATE_INTERVAL_MS)
            frameUpdateScheduled.set(false)
            _frameId.update { it + 1 }
        }
    }

    /**
     * 关闭会话
     *
     * @param sessionId 要关闭的会话 ID
     * @param defaultWorkDir 当关闭最后一个会话时，新建默认会话的工作目录
     */
    fun closeSession(
        sessionId: String,
        defaultWorkDir: String = "/",
        createReplacement: Boolean = true
    ) {
        val session = getSessionById(sessionId) ?: return

        cancelSessionStart(sessionId)

        // 停止终端会话
        session.session?.finishIfRunning()

        // 从列表中移除
        _sessions.update { it.filter { s -> s.id != sessionId } }

        // 如果活动会话被关闭或状态已不一致，恢复到一个确定的活动会话。
        val remaining = _sessions.value
        if (_activeSessionId.value == sessionId || remaining.none { it.id == _activeSessionId.value }) {
            if (remaining.isNotEmpty()) {
                _activeSessionId.value = remaining.last().id
            } else if (createReplacement) {
                // 如果没有剩余会话，自动创建一个新的默认会话
                createSession(workDir = defaultWorkDir)
            } else {
                _activeSessionId.value = null
            }
        }

        requestFrameUpdate()
    }

    /**
     * 切换到指定会话
     *
     * @param sessionId 目标会话 ID
     */
    fun switchSession(sessionId: String) {
        if (_sessions.value.any { it.id == sessionId }) {
            _activeSessionId.value = sessionId
            _frameId.update { it + 1 }
        }
    }

    /**
     * 重命名会话
     *
     * @param sessionId 会话 ID
     * @param newTitle 新标题
     */
    fun renameSession(sessionId: String, newTitle: String) {
        updateSessionState(sessionId) { it.withTitle(newTitle) }
        _frameId.update { it + 1 }
    }

    /**
     * 重启会话
     *
     * @param sessionId 要重启的会话 ID
     * @param workDir 工作目录
     */
    fun restartSession(sessionId: String, workDir: String = "/") {
        val session = getSessionById(sessionId) ?: return

        cancelSessionStart(sessionId)

        // 停止旧会话
        session.session?.finishIfRunning()

        // 重置状态
        updateSessionState(sessionId) {
            it.copy(
                status = SessionStatus.STARTING,
                exitCode = null,
                errorMessage = null,
                session = null
            )
        }

        // 重新启动
        startTerminalSession(sessionId, workDir, 24, 80)
    }

    /**
     * 向活动会话发送文本
     */
    fun sendText(text: String) {
        val session = getActiveSession() ?: return
        if (!session.canReceiveInput) return

        session.session?.write(text)
    }

    /**
     * 调整活动会话的终端大小
     */
    fun resize(rows: Int, cols: Int) {
        val session = getActiveSession() ?: return
        session.session?.updateSize(cols, rows, 0, 0)
        _frameId.update { it + 1 }
    }

    /**
     * 发送中断信号到活动会话
     */
    fun sendInterrupt() {
        sendText("\u0003") // Ctrl+C
    }

    /**
     * 根据 ID 获取会话
     */
    fun getSessionById(sessionId: String): TerminalSessionState? = _sessions.value.find { it.id == sessionId }

    /**
     * 根据 Termux Session 查找并更新会话状态
     */
    private fun updateSessionByTermuxSession(
        termuxSession: TerminalSession,
        update: (TerminalSessionState) -> TerminalSessionState
    ) {
        _sessions.update { list ->
            list.map { session ->
                if (session.session?.mHandle == termuxSession.mHandle) update(session) else session
            }
        }
    }

    /**
     * 更新会话状态
     */
    private fun updateSessionState(
        sessionId: String,
        update: (TerminalSessionState) -> TerminalSessionState
    ) {
        _sessions.update { list ->
            list.map { session ->
                if (session.id == sessionId) update(session) else session
            }
        }
    }

    /**
     * 设置当前项目路径
     */
    fun setProjectPath(projectPath: String) {
        currentProjectPath = projectPath
    }

    /**
     * 保存终端状态到项目
     *
     * 将当前所有终端会话的元数据保存到项目的 .tinaide/state/terminal_state.json
     */
    fun saveState() {
        val projectPath = currentProjectPath
        if (projectPath.isNullOrBlank()) {
            Timber.tag(TAG).d("Cannot save terminal state: no project path set")
            return
        }

        scope.launch(Dispatchers.Main) {
            try {
                val sessionStates = _sessions.value
                if (sessionStates.isEmpty()) {
                    stateStorage.clear(projectPath)
                    return@launch
                }

                val snapshots = sessionStates.map { session -> createSnapshot(session) }
                val state = ProjectTerminalState(
                    activeSessionId = _activeSessionId.value,
                    sessions = snapshots,
                    updatedAt = System.currentTimeMillis()
                )

                stateStorage.save(projectPath, state)
                Timber.tag(TAG).d("Saved terminal state: %d sessions", snapshots.size)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to save terminal state")
            }
        }
    }

    /**
     * 从项目恢复终端状态
     *
     * @param projectPath 项目路径
     * @param defaultWorkDir 默认工作目录（用于恢复后创建新终端）
     */
    fun restoreState(projectPath: String, defaultWorkDir: String = "/") {
        currentProjectPath = projectPath

        if (_sessions.value.isNotEmpty()) {
            Timber.tag(TAG).d("Skip restoring terminal state: sessions already exist")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val state = stateStorage.load(projectPath)
                if (state == null) {
                    Timber.tag(TAG).d("No terminal state to restore for project: %s", projectPath)
                    // 如果没有保存的状态，创建一个默认终端
                    withContext(Dispatchers.Main) {
                        if (_sessions.value.isEmpty()) {
                            createSession(workDir = defaultWorkDir)
                        }
                    }
                    return@launch
                }

                Timber.tag(TAG).d("Restoring terminal state: %d sessions", state.sessions.size)

                withContext(Dispatchers.Main) {
                    // 恢复每个会话
                    state.sessions.forEach { snapshot ->
                        restoreSession(snapshot, defaultWorkDir)
                    }

                    // 切换到之前活动的会话
                    state.activeSessionId?.let { activeId ->
                        if (_sessions.value.any { it.id == activeId }) {
                            switchSession(activeId)
                        }
                    }

                    // 如果恢复后没有会话，创建一个默认终端
                    if (_sessions.value.isEmpty()) {
                        createSession(workDir = defaultWorkDir)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to restore terminal state")
                // 恢复失败时创建默认终端
                withContext(Dispatchers.Main) {
                    if (_sessions.value.isEmpty()) {
                        createSession(workDir = defaultWorkDir)
                    }
                }
            }
        }
    }

    /**
     * 创建会话快照
     */
    private fun createSnapshot(state: TerminalSessionState): TerminalSessionSnapshot {
        val session = state.session
        val emulator = session?.emulator

        // 获取工作目录
        val workDir = try {
            session?.cwd
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed to get cwd")
            null
        }

        // 获取 transcript（限制大小）
        val transcriptLinesToSave = try {
            if (emulator == null) {
                0
            } else {
                min(
                    emulator.screen.activeTranscriptRows,
                    TerminalSessionSnapshot.MAX_TRANSCRIPT_ROWS_TO_SAVE
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed to get transcript lines")
            0
        }

        val transcript = try {
            if (emulator == null) {
                null
            } else {
                val buffer = if (emulator.isAlternateBufferActive) {
                    emulator.mainBuffer
                } else {
                    emulator.screen
                }
                val startRow = -transcriptLinesToSave
                val text = buffer
                    .getSelectedText(0, startRow, emulator.mColumns, emulator.mRows)
                    .trim()
                if (text.length <= TerminalSessionSnapshot.MAX_TRANSCRIPT_LENGTH) {
                    text.ifBlank { null }
                } else {
                    // 如果太长，只保留最后部分
                    text.takeLast(TerminalSessionSnapshot.MAX_TRANSCRIPT_LENGTH)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to get transcript for session: %s", state.id)
            null
        }

        return TerminalSessionSnapshot(
            id = state.id,
            title = state.title,
            backend = state.backend.name.lowercase(),
            workingDirectory = workDir,
            cursorRow = emulator?.cursorRow ?: 0,
            cursorColumn = emulator?.cursorCol ?: 0,
            rows = emulator?.mRows ?: 24,
            columns = emulator?.mColumns ?: 80,
            transcript = transcript,
            transcriptLines = transcriptLinesToSave,
            createdAt = state.createdAt,
            savedAt = System.currentTimeMillis()
        )
    }

    /**
     * 恢复单个会话
     *
     * 注意：只能恢复元数据，进程状态无法恢复
     */
    private fun restoreSession(snapshot: TerminalSessionSnapshot, defaultWorkDir: String) {
        val workDir = snapshot.workingDirectory?.takeIf {
            it.isNotBlank() && File(it).exists()
        } ?: defaultWorkDir

        val backend = try {
            TerminalBackend.valueOf(snapshot.backend.uppercase())
        } catch (_: Exception) {
            TerminalBackend.HOST
        }

        // 使用原来的 ID 创建会话
        val sessionState = TerminalSessionState(
            id = snapshot.id,
            title = snapshot.title,
            backend = backend,
            createdAt = snapshot.createdAt
        )

        // 添加到会话列表
        _sessions.update { it + sessionState }

        // 启动终端会话
        startTerminalSession(
            sessionId = snapshot.id,
            workDir = workDir,
            rows = snapshot.rows,
            cols = snapshot.columns
        )

        Timber.tag(TAG).d(
            "Restored session: %s (title=%s, workDir=%s)",
            snapshot.id,
            snapshot.title,
            workDir
        )
    }

    /**
     * 清除项目的终端状态
     */
    fun clearState() {
        val projectPath = currentProjectPath ?: return
        scope.launch(Dispatchers.IO) {
            stateStorage.clear(projectPath)
        }
    }

    /**
     * 清理所有会话
     */
    fun cleanup() {
        sessionStartJobs.values.forEach { it.cancel() }
        sessionStartJobs.clear()
        _sessions.value.forEach { session ->
            session.session?.finishIfRunning()
        }

        _sessions.value = emptyList()
        _activeSessionId.value = null
    }

    /**
     * 为指定会话开启"抑制退出横幅"开关。对 Run 模式会话使用，避免 shell 被 SIGKILL 后
     * Termux 把 `[Process completed - press Enter]` 写入 emulator。
     */
    fun markSuppressExitNotice(sessionId: String) {
        val state = getSessionById(sessionId) ?: return
        state.session?.suppressExitNotice = true
    }

    companion object {
        private const val TAG = "TerminalSessionManager"

        /** Tina 私有 OSC 识别码。非标准，避免与常见 xterm/urxvt 号段冲突。 */
        private const val OSC_CODE_TINA = 777

        /** Run 模式程序结束标记，格式 `tina-run-end;<exitCode>`。 */
        private const val OSC_RUN_END_PREFIX = "tina-run-end;"

        private const val FRAME_UPDATE_INTERVAL_MS = 16L
    }
}
