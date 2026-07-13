package com.wuxianggujun.tinaide.terminal.session

import com.wuxianggujun.tinaide.core.terminal.ITerminalSessionManager
import com.wuxianggujun.tinaide.core.terminal.SessionStatus
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.core.terminal.TerminalSessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * TerminalSessionManager 适配器
 *
 * 将 feature:terminal 层的 TerminalSessionManager 适配为 core:common 层的 ITerminalSessionManager 接口
 *
 * 架构说明：
 * - 内部委托给 TerminalSessionManager（feature:terminal 层）
 * - 对外暴露 ITerminalSessionManager 接口（core:common 层）
 * - 负责类型转换（TerminalSessionState ↔ TerminalSessionInfo）
 */
class TerminalSessionManagerAdapter(
    private val delegate: TerminalSessionManager
) : ITerminalSessionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val sessions: StateFlow<List<TerminalSessionInfo>> =
        delegate.sessions
            .map { sessions -> sessions.map { it.toTerminalSessionInfo() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val activeSessionId: StateFlow<String?> = delegate.activeSessionId

    override val frameId: StateFlow<Int> =
        delegate.frameId
            .map { it.toInt() }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    override fun initialize() {
        delegate.initialize()
    }

    override fun createSession(
        workDir: String,
        rows: Int,
        cols: Int,
        backend: TerminalBackend,
        initialCommand: String?,
    ): String = delegate.createSession(
        workDir = workDir,
        rows = rows,
        cols = cols,
        backend = backend.toFeatureTerminalBackend(),
        initialCommand = initialCommand,
    )

    override fun closeSession(sessionId: String, defaultWorkDir: String, createReplacement: Boolean) {
        delegate.closeSession(sessionId, defaultWorkDir, createReplacement)
    }

    override fun switchSession(sessionId: String) {
        delegate.switchSession(sessionId)
    }

    override fun renameSession(sessionId: String, newTitle: String) {
        delegate.renameSession(sessionId, newTitle)
    }

    override fun restartSession(sessionId: String, workDir: String) {
        delegate.restartSession(sessionId, workDir)
    }

    override fun sendText(text: String) {
        delegate.sendText(text)
    }

    override fun sendInterrupt() {
        delegate.sendInterrupt()
    }

    override fun resize(rows: Int, cols: Int) {
        delegate.resize(rows, cols)
    }

    override fun getSessionById(sessionId: String): TerminalSessionInfo? = delegate.getSessionById(sessionId)?.toTerminalSessionInfo()

    override fun getActiveSession(): TerminalSessionInfo? = delegate.getActiveSession()?.toTerminalSessionInfo()

    override fun setProjectPath(projectPath: String) {
        delegate.setProjectPath(projectPath)
    }

    override fun saveState() {
        delegate.saveState()
    }

    override fun restoreState(projectPath: String, defaultWorkDir: String) {
        delegate.restoreState(projectPath, defaultWorkDir)
    }

    override fun clearState() {
        delegate.clearState()
    }

    override fun cleanup() {
        delegate.cleanup()
    }

    override fun markSuppressExitNotice(sessionId: String) {
        delegate.markSuppressExitNotice(sessionId)
    }

    override fun getInternalSession(sessionId: String): Any? = delegate.getSessionById(sessionId)?.session
}

// ========== 类型转换扩展函数 ==========

/**
 * 将内部 TerminalSessionState 转换为接口 TerminalSessionInfo
 */
private fun TerminalSessionState.toTerminalSessionInfo(): TerminalSessionInfo = TerminalSessionInfo(
    id = id,
    title = title,
    backend = backend.toCoreTerminalBackend(),
    status = status.toCoreSessionStatus(),
    createdAt = createdAt,
    exitCode = exitCode,
    errorMessage = errorMessage,
    shellPid = shellPid,
    canReceiveInput = canReceiveInput,
    isTerminated = isTerminated,
    runExitCode = runExitCode
)

/**
 * 将 feature:terminal 层的 TerminalBackend 转换为 core:common 层的 TerminalBackend
 */
private fun com.wuxianggujun.tinaide.terminal.shell.TerminalBackend.toCoreTerminalBackend(): TerminalBackend = when (this) {
    com.wuxianggujun.tinaide.terminal.shell.TerminalBackend.HOST -> TerminalBackend.HOST
    com.wuxianggujun.tinaide.terminal.shell.TerminalBackend.PROOT -> TerminalBackend.PROOT
}

/**
 * 将 core:common 层的 TerminalBackend 转换为 feature:terminal 层的 TerminalBackend
 */
private fun TerminalBackend.toFeatureTerminalBackend(): com.wuxianggujun.tinaide.terminal.shell.TerminalBackend = when (this) {
    TerminalBackend.HOST -> com.wuxianggujun.tinaide.terminal.shell.TerminalBackend.HOST
    TerminalBackend.PROOT -> com.wuxianggujun.tinaide.terminal.shell.TerminalBackend.PROOT
}

/**
 * 将 feature:terminal 层的 SessionStatus 转换为 core:common 层的 SessionStatus
 */
private fun com.wuxianggujun.tinaide.terminal.session.SessionStatus.toCoreSessionStatus(): SessionStatus = when (this) {
    com.wuxianggujun.tinaide.terminal.session.SessionStatus.STARTING -> SessionStatus.STARTING
    com.wuxianggujun.tinaide.terminal.session.SessionStatus.RUNNING -> SessionStatus.RUNNING
    com.wuxianggujun.tinaide.terminal.session.SessionStatus.EXITED -> SessionStatus.EXITED
    com.wuxianggujun.tinaide.terminal.session.SessionStatus.ERROR -> SessionStatus.ERROR
}
