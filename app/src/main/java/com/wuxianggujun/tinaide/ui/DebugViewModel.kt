package com.wuxianggujun.tinaide.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.debug.BreakpointStore
import com.wuxianggujun.tinaide.core.debug.DebugSessionService
import com.wuxianggujun.tinaide.core.debug.DebugSessionStore
import com.wuxianggujun.tinaide.core.debug.DebugState
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.ui.compose.components.BreakpointInfo
import com.wuxianggujun.tinaide.ui.compose.components.DebugStatus
import com.wuxianggujun.tinaide.ui.compose.components.DebugVariable
import com.wuxianggujun.tinaide.ui.compose.components.StackFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 调试 ViewModel
 *
 * 职责：
 * - 统一管理调试状态，将 DebugSessionService 和 BreakpointStore 的状态转换为 UI 可用的格式
 * - 提供调试操作方法（继续、单步、暂停、停止等）
 * - 管理断点的 UI 操作
 *
 * 设计原则：
 * - 使用 StateFlow 暴露状态给 Compose
 * - 封装 DebugSessionService 的调用
 */
class DebugViewModel(
    application: Application,
    private val breakpointStore: BreakpointStore,
    private val debugSessionStore: DebugSessionStore,
    private val configManager: IConfigManager,
) : AndroidViewModel(application) {

    // 调试服务实例
    private val prootEnv by lazy { PRootEnvironment(getApplication(), configManager) }
    private val debugSessionService = DebugSessionService(getApplication(), prootEnv, breakpointStore)

    // ============ UI 状态 ============

    /**
     * 调试状态（转换为 UI 枚举）
     */
    val debugStatus: StateFlow<DebugStatus> = debugSessionService.state
        .map { state ->
            when (state) {
                is DebugState.Idle -> DebugStatus.IDLE
                is DebugState.Starting -> DebugStatus.STARTING
                is DebugState.Paused -> DebugStatus.PAUSED
                is DebugState.Running -> DebugStatus.RUNNING
                is DebugState.Terminated -> DebugStatus.TERMINATED
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DebugStatus.IDLE)

    /**
     * 是否有活动的调试会话
     */
    val isActive: StateFlow<Boolean> = debugSessionService.state
        .map { state ->
            state is DebugState.Starting || state is DebugState.Paused || state is DebugState.Running
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * 当前会话 ID
     */
    val sessionId: StateFlow<String?> = combine(
        debugSessionStore.descriptor,
        debugSessionService.state
    ) { descriptor, state ->
        descriptor?.sessionId
            ?: (state as? DebugState.Starting)?.sessionId
            ?: (state as? DebugState.Paused)?.sessionId
            ?: (state as? DebugState.Running)?.sessionId
            ?: (state as? DebugState.Terminated)?.sessionId
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 会话信息（描述符路径）
     */
    val sessionInfo: StateFlow<String?> = debugSessionStore.descriptor
        .map { it?.descriptorPath }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 当前位置（仅在暂停时有值）
     */
    val currentLocation: StateFlow<String?> = debugSessionService.state
        .map { state ->
            (state as? DebugState.Paused)?.location?.let { loc ->
                "${loc.function ?: "unknown"}() at ${loc.file.substringAfterLast('/')}:${loc.line}"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 断点列表（转换为 UI 数据类）
     */
    val breakpoints: StateFlow<List<BreakpointInfo>> = breakpointStore.breakpoints
        .map { list ->
            list.map { bp ->
                BreakpointInfo(
                    id = bp.id,
                    file = bp.file,
                    line = bp.line,
                    enabled = bp.enabled,
                    verified = bp.verified
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 变量列表（转换为 UI 数据类）
     */
    val variables: StateFlow<List<DebugVariable>> = debugSessionService.variables
        .map { list ->
            list.map { v ->
                DebugVariable(
                    name = v.name,
                    value = v.value,
                    type = v.type ?: ""
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 调用栈（转换为 UI 数据类）
     */
    val callStack: StateFlow<List<StackFrame>> = debugSessionService.callStack
        .map { list ->
            list.map { f ->
                StackFrame(
                    id = f.id,
                    name = f.name,
                    file = f.file ?: "",
                    line = f.line ?: 0
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 调试控制台输出（LLDB stdout/stderr + 关键事件）
     */
    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    private var consoleCarry = ""

    init {
        viewModelScope.launch {
            debugSessionService.events.collect { event ->
                when (event) {
                    is com.wuxianggujun.tinaide.core.debug.DebugEvent.OutputReceived -> {
                        appendConsole(event.text)
                    }
                    is com.wuxianggujun.tinaide.core.debug.DebugEvent.Error -> {
                        appendConsole("[ERROR] ${event.message}\n")
                    }
                    is com.wuxianggujun.tinaide.core.debug.DebugEvent.SessionStarting -> {
                        appendConsole("[INFO] " + Strings.debug_session_started.str(event.sessionId) + "\n")
                    }
                    is com.wuxianggujun.tinaide.core.debug.DebugEvent.TargetReady -> {
                        appendConsole("[INFO] " + Strings.debug_target_paused.str() + "\n")
                    }
                    is com.wuxianggujun.tinaide.core.debug.DebugEvent.SessionEnded -> {
                        appendConsole("[INFO] ${event.message}\n")
                    }
                }
            }
        }
    }

    // ============ 调试操作 ============

    /**
     * 启动调试会话（由 MainActivity 在 DEBUG 编译成功后调用）
     */
    fun startDebugSession(
        programPath: String,
        workingDirectory: String? = null,
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
    ) {
        viewModelScope.launch {
            clearConsole()
            debugSessionService
                .startSession(programPath, workingDirectory, arguments, environment)
                .onFailure { appendConsole("[ERROR] ${it.message}\n") }
        }
    }

    /**
     * 继续执行
     */
    fun continueExecution() {
        debugSessionService.continueExecution()
    }

    /**
     * 单步跳过
     */
    fun stepOver() {
        debugSessionService.stepOver()
    }

    /**
     * 单步进入
     */
    fun stepInto() {
        debugSessionService.stepInto()
    }

    /**
     * 单步跳出
     */
    fun stepOut() {
        debugSessionService.stepOut()
    }

    /**
     * 暂停执行
     */
    fun pauseExecution() {
        debugSessionService.pauseExecution()
    }

    /**
     * 停止调试会话
     */
    fun stopSession() {
        debugSessionService.stopSession()
    }

    /**
     * 请求指定栈帧的变量
     */
    fun requestVariables(frameId: Int) {
        debugSessionService.requestVariables(frameId)
    }

    fun clearConsole() {
        consoleCarry = ""
        _consoleLines.value = emptyList()
    }

    // ============ 断点操作 ============

    /**
     * 切换断点启用状态
     */
    fun toggleBreakpoint(id: Int) {
        breakpointStore.toggleEnabled(id)
    }

    /**
     * 移除断点
     */
    fun removeBreakpoint(id: Int) {
        breakpointStore.remove(id)
    }

    // ============ 生命周期 ============

    override fun onCleared() {
        super.onCleared()
        // 清理调试服务资源
        debugSessionService.reset()
    }

    private fun appendConsole(text: String) {
        val combined = consoleCarry + text
        val parts = combined.split('\n')
        if (parts.isEmpty()) return

        consoleCarry = parts.last()
        val newLines = parts.dropLast(1).filter { it.isNotEmpty() || parts.size > 1 }
        if (newLines.isEmpty()) return

        val merged = (_consoleLines.value + newLines).takeLast(500)
        _consoleLines.value = merged
    }
}
