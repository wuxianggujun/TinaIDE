package com.wuxianggujun.tinaide.core.debug

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 调试会话服务（native LLDB，或显式选择的 PRoot LLDB）
 *
 * 负责：
 * - 启动/停止 LLDB 会话
 * - 执行控制（继续/单步/暂停/停止）
 * - 暂停时刷新调用栈和变量
 * - 将 LLDB 输出透传给 UI（用于调试控制台）
 */
class DebugSessionService internal constructor(
    private val context: Context,
    private val prootEnvironment: PRootEnvironment,
    private val breakpointStore: BreakpointStore,
    private val debuggerFactory: (suspend (DebugBackend, String?) -> LldbDebugger)?,
) {
    constructor(context: Context, prootEnvironment: PRootEnvironment, breakpointStore: BreakpointStore) :
        this(context, prootEnvironment, breakpointStore, null)
    private val toolchainPathResolver by lazy { ToolchainPathResolver(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<DebugState>(DebugState.Idle)
    val state: StateFlow<DebugState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DebugEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<DebugEvent> = _events.asSharedFlow()

    private val _callStack = MutableStateFlow<List<StackFrame>>(emptyList())
    val callStack: StateFlow<List<StackFrame>> = _callStack.asStateFlow()

    private val _variables = MutableStateFlow<List<Variable>>(emptyList())
    val variables: StateFlow<List<Variable>> = _variables.asStateFlow()

    private var debugger: PRootDebugger? = null
    private var outputJob: Job? = null
    private var failureJob: Job? = null
    private val startMutex = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var targetPid: Int? = null
    private var activeBackend = DebugBackend.NATIVE
    private var isFirstStop: Boolean = true

    @Volatile private var breakpointSyncPending: Boolean = false

    private val stateRefreshMutex = Mutex()
    private val breakpointSyncMutex = Mutex()
    private val outputBufferLock = Any()
    private var outputBuffer = StringBuilder()
    private var outputOffset = 0L
    private var lastHandledStopMarker = -1L
    private var lastHandledExitMarker = -1L
    private var lastHandledLaunchMarker = -1L

    private val stopRegex = Regex("""(?im)^\s*Process\s+\d+\s+stopped\b""")
    private val launchRegex = Regex("""(?im)^\s*Process\s+\d+\s+launched\b""")
    private val exitRegex = Regex("""(?im)^\s*Process\s+\d+\s+exited\b""")
    private val errorLineRegex = Regex("""(?im)^\s*error:\s*(.+)\s*$""")

    init {
        // 监听断点变化：尽量在暂停/启动阶段同步到调试器，避免 RUNNING 阶段阻塞交互
        scope.launch {
            breakpointStore.breakpoints
                .map { list -> list.map { it.copy(verified = false, address = 0, hitCount = 0) } }
                .distinctUntilChanged()
                .collect {
                breakpointSyncPending = true
                val current = _state.value
                if (current is DebugState.Paused || current is DebugState.Starting) {
                    syncBreakpointsIfNeeded()
                }
            }
        }
    }

    suspend fun startSession(
        programPath: String,
        workingDirectory: String? = null,
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        backend: DebugBackend = DebugBackend.NATIVE,
        toolchainId: String? = null,
    ): Result<String> = startMutex.withLock {
        startSessionInternal(programPath, workingDirectory, arguments, environment, backend, toolchainId)
    }

    private suspend fun startSessionInternal(
        programPath: String,
        workingDirectory: String?,
        arguments: List<String>,
        environment: Map<String, String>,
        backend: DebugBackend,
        toolchainId: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current !is DebugState.Idle && current !is DebugState.Terminated) {
            return@withContext Result.failure(
                IllegalStateException(Strings.debug_error_cannot_start.strOr(context, current::class.simpleName ?: ""))
            )
        }

        val newSessionId = "dbg-${UUID.randomUUID()}"
        sessionId = newSessionId
        activeBackend = backend
        isFirstStop = true

        _state.value = DebugState.Starting(
            sessionId = newSessionId,
            soPath = programPath,
            entrySymbol = "main"
        )
        _events.tryEmit(DebugEvent.SessionStarting(newSessionId))

        try {
            val workDir = workingDirectory ?: File(programPath).parent ?: "/workspace"

            val dbg = debuggerFactory?.invoke(backend, toolchainId) ?: when (backend) {
                DebugBackend.NATIVE -> NativeDebuggerFactory.create(context, toolchainId)
                DebugBackend.PROOT -> {
                    val availability = prootEnvironment.execute(
                        command = listOf(toolchainPathResolver.getLldb(), "--version"), workDir = "/", timeout = 10_000
                    )
                    check(availability.isSuccess) { Strings.debug_error_lldb_unavailable.strOr(context) }
                    LldbDebugger(context, prootEnvironment.getPRootManager())
                }
            }
            if (sessionId != newSessionId) { dbg.close(); return@withContext Result.failure(CancellationException("Session stopped")) }
            debugger = dbg

            outputJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                dbg.output.collect { chunk ->
                    if (sessionId != newSessionId) return@collect
                    _events.emit(DebugEvent.OutputReceived(chunk.text, isStderr = chunk.isStderr))
                    if (!chunk.isStderr) {
                        onStdoutChunk(chunk.text, newSessionId)
                    }
                }
            }
            failureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                dbg.failure.collect { failure ->
                    if (failure != null && sessionId == newSessionId) {
                        _state.value = DebugState.Terminated(newSessionId, TerminateReason.ERROR, message = failure.message)
                        _events.tryEmit(DebugEvent.Error(Strings.debug_error_execute_failed.strOr(context, failure.message.orEmpty())))
                        cleanupSession()
                    }
                }
            }

            dbg.start(
                programPath = programPath,
                arguments = arguments,
                workingDirectory = workDir,
                environment = environment,
            ).getOrThrow()
            if (sessionId != newSessionId) return@withContext Result.failure(CancellationException("Session stopped"))

            breakpointStore.clearVerification()
            breakpointSyncPending = true
            syncBreakpointsIfNeeded()
            // 总是停在入口（main）：保证一定能进入暂停态，从而可查看变量/调用栈并进行单步调试
            dbg.sendCommand("breakpoint set --name main", timeoutMs = 10_000).getOrThrow()

            dbg.sendCommand("run", timeoutMs = 300_000).getOrThrow()
            // 注意：run 的“stopped/exit”信息往往通过异步 stdout 输出。
            // 这里不要覆盖由 stdout 驱动的状态机（否则会出现“实际已断下但 UI 仍显示 Running”）。
            if (sessionId == newSessionId && _state.value is DebugState.Starting) {
                _state.value = DebugState.Running(newSessionId)
            }
            Result.success(newSessionId)
        } catch (t: Throwable) {
            if (sessionId == newSessionId) {
                _state.value = DebugState.Terminated(
                    sessionId = newSessionId,
                    reason = TerminateReason.ERROR,
                    message = t.message
                )
                _events.tryEmit(DebugEvent.Error(t.message ?: Strings.debug_error_start_failed.strOr(context)))
                cleanupSession()
            }
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    fun continueExecution() {
        val current = _state.value
        if (current !is DebugState.Paused) {
            _events.tryEmit(DebugEvent.Error(Strings.debug_error_cannot_continue.strOr(context)))
            return
        }
        runAsyncCommand(
            sessionId = current.sessionId,
            command = "continue",
            enteringState = DebugState.Running(current.sessionId),
            timeoutMs = 300_000
        )
    }

    fun stepOver() {
        val current = _state.value
        if (current !is DebugState.Paused) {
            _events.tryEmit(DebugEvent.Error(Strings.debug_error_cannot_step.strOr(context)))
            return
        }
        runAsyncCommand(
            sessionId = current.sessionId,
            command = "next",
            enteringState = DebugState.Running(current.sessionId),
            timeoutMs = 60_000
        )
    }

    fun stepInto() {
        val current = _state.value
        if (current !is DebugState.Paused) {
            _events.tryEmit(DebugEvent.Error(Strings.debug_error_cannot_step.strOr(context)))
            return
        }
        runAsyncCommand(
            sessionId = current.sessionId,
            command = "step",
            enteringState = DebugState.Running(current.sessionId),
            timeoutMs = 60_000
        )
    }

    fun stepOut() {
        val current = _state.value
        if (current !is DebugState.Paused) {
            _events.tryEmit(DebugEvent.Error(Strings.debug_error_cannot_step.strOr(context)))
            return
        }
        scope.launch {
            if (sessionId != current.sessionId) return@launch
            val dbg = debugger ?: run {
                _events.emit(DebugEvent.Error(Strings.debug_error_not_paused.strOr(context)))
                return@launch
            }

            val previousState = _state.value
            _state.value = DebugState.Running(current.sessionId)

            val output = runCatching { dbg.sendCommand("finish", timeoutMs = 60_000).getOrThrow() }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    if (sessionId != current.sessionId || debugger !== dbg) return@launch
                    _state.value = previousState
                    _events.emit(DebugEvent.Error(Strings.debug_error_execute_failed.strOr(context, t.message ?: "")))
                    return@launch
                }
            if (sessionId != current.sessionId || debugger !== dbg) return@launch

            val error = parseFirstError(output)
            if (error != null && error.contains("return address breakpoint", ignoreCase = true)) {
                _events.emit(
                    DebugEvent.Error(
                        Strings.debug_error_step_out_no_return.strOr(context)
                    )
                )
                runCatching { dbg.sendCommand("continue", timeoutMs = 300_000).getOrThrow() }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        if (sessionId != current.sessionId || debugger !== dbg) return@launch
                        _state.value = previousState
                        _events.emit(DebugEvent.Error(Strings.debug_error_continue_failed.strOr(context, t.message ?: "")))
                    }
                return@launch
            }

            if (error != null) {
                _state.value = previousState
                _events.emit(DebugEvent.Error(error))
                if (previousState is DebugState.Paused) {
                    refreshPausedState()
                }
            }
        }
    }

    fun pauseExecution() {
        val current = _state.value
        if (current !is DebugState.Running) {
            _events.tryEmit(DebugEvent.Error(Strings.debug_error_cannot_pause.strOr(context)))
            return
        }
        // `run` owns the command channel until a stop. Do not queue an interrupt behind it.
        val pid = targetPid
        if (activeBackend == DebugBackend.NATIVE && pid != null && pid > 0) {
            try {
                android.system.Os.kill(pid, android.system.OsConstants.SIGINT)
            } catch (error: Exception) {
                _events.tryEmit(DebugEvent.Error(Strings.debug_error_execute_failed.strOr(context, error.message.orEmpty())))
            }
        } else {
            _events.tryEmit(DebugEvent.Error(Strings.debug_error_cannot_pause.strOr(context)))
        }
    }

    fun stopSession() {
        val current = _state.value
        val sid = when (current) {
            is DebugState.Starting -> current.sessionId
            is DebugState.Paused -> current.sessionId
            is DebugState.Running -> current.sessionId
            is DebugState.Terminated -> current.sessionId
            else -> sessionId
        } ?: "dbg-unknown"

        _state.value = DebugState.Terminated(sessionId = sid, reason = TerminateReason.USER_STOP)
        cleanupSession()
        _events.tryEmit(DebugEvent.SessionEnded(sid, Strings.debug_session_ended_user_stop.strOr(context)))
    }

    fun requestVariables(frameId: Int) {
        val current = _state.value
        if (current !is DebugState.Paused) return

        scope.launch {
            if (sessionId != current.sessionId) return@launch
            try {
                val dbg = debugger ?: return@launch
                dbg.sendCommand("frame select $frameId", timeoutMs = 5_000).getOrThrow()
                if (sessionId != current.sessionId || debugger !== dbg) return@launch
                val varsOutput = dbg.sendCommand("frame variable", timeoutMs = 10_000).getOrThrow()
                if (sessionId != current.sessionId || debugger !== dbg) return@launch
                _variables.value = parseVariables(varsOutput)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (sessionId != current.sessionId) return@launch
                _events.emit(DebugEvent.Error(Strings.debug_error_get_variables_failed.strOr(context, t.message ?: "")))
            }
        }
    }

    suspend fun evaluate(expression: String): Result<String> = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current !is DebugState.Paused) {
            return@withContext Result.failure(IllegalStateException(Strings.debug_error_not_paused.strOr(context)))
        }
        try {
            val dbg = debugger ?: return@withContext Result.failure(IllegalStateException(Strings.debug_error_not_paused.strOr(context)))
            val output = dbg.sendCommand("expression $expression", timeoutMs = 10_000).getOrThrow()
            if (sessionId != current.sessionId || debugger !== dbg) {
                return@withContext Result.failure(IllegalStateException(Strings.debug_error_not_paused.strOr(context)))
            }
            Result.success(output.trim())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    fun reset() {
        cleanupSession()
        _state.value = DebugState.Idle
    }

    fun close() {
        reset()
        scope.cancel()
    }

    private fun runAsyncCommand(
        sessionId: String,
        command: String,
        enteringState: DebugState,
        timeoutMs: Long
    ) {
        scope.launch {
            if (this@DebugSessionService.sessionId != sessionId) return@launch
            try {
                val dbg = debugger ?: run {
                    _events.emit(DebugEvent.Error(Strings.debug_error_not_paused.strOr(context)))
                    return@launch
                }
                val previousState = _state.value
                _state.value = enteringState
                val output = dbg.sendCommand(command, timeoutMs = timeoutMs).getOrThrow()
                if (this@DebugSessionService.sessionId != sessionId || debugger !== dbg) return@launch
                val error = parseFirstError(output)
                if (error != null) {
                    _state.value = previousState
                    _events.emit(DebugEvent.Error(error))
                    if (previousState is DebugState.Paused) {
                        refreshPausedState()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (this@DebugSessionService.sessionId != sessionId) return@launch
                _state.value = DebugState.Terminated(
                    sessionId = sessionId,
                    reason = TerminateReason.ERROR,
                    message = t.message
                )
                _events.emit(DebugEvent.Error(Strings.debug_error_execute_failed.strOr(context, t.message ?: "")))
                cleanupSession()
            }
        }
    }

    private suspend fun refreshPausedState() {
        val sid = sessionId ?: return
        val dbg = debugger ?: return
        try {
            val btOutput = dbg.sendCommand("thread backtrace", timeoutMs = 10_000).getOrThrow()
            if (sessionId != sid || debugger !== dbg) return
            _callStack.value = parseBacktrace(btOutput)

            val varsOutput = dbg.sendCommand("frame variable", timeoutMs = 10_000).getOrThrow()
            if (sessionId != sid || debugger !== dbg) return
            _variables.value = parseVariables(varsOutput)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (sessionId != sid || debugger !== dbg) return
            _events.emit(DebugEvent.Error(Strings.debug_error_refresh_state_failed.strOr(context, t.message ?: "")))
        }
    }

    private suspend fun syncBreakpointsIfNeeded(): Unit = breakpointSyncMutex.withLock {
        if (!breakpointSyncPending) return
        val sid = sessionId ?: return
        val dbg = debugger ?: return
        breakpointSyncPending = false
        val all = breakpointStore.breakpoints.value
        val enabled = all.filter { it.enabled }

        // 先清空调试器内的断点（KISS：避免维护复杂映射）
        try {
            val listOutput = dbg.sendCommand("breakpoint list", timeoutMs = 10_000).getOrThrow()
            val ids = Regex("""^\s*Breakpoint (\d+):""", RegexOption.MULTILINE)
                .findAll(listOutput)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .distinct()
                .toList()
            ids.forEach { id ->
                if (sessionId != sid || debugger !== dbg) return
                dbg.sendCommand("breakpoint delete $id", timeoutMs = 10_000).getOrThrow()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (sessionId != sid || debugger !== dbg) return
            breakpointSyncPending = true
            _events.emit(DebugEvent.Error(Strings.debug_error_set_breakpoint_failed.strOr(context, error.message.orEmpty())))
            return
        }
        if (sessionId != sid || debugger !== dbg) return

        // 默认全部置为未验证；后续按实际设置结果刷新
        all.forEach { bp ->
            breakpointStore.updateVerified(id = bp.id, address = 0, verified = false)
        }

        if (enabled.isEmpty()) return

        enabled.forEach { bp ->
            if (sessionId != sid || debugger !== dbg) return
            fun buildCmd(): String = buildString {
                append("breakpoint set --file ")
                append(quoteForLldb(bp.file))
                append(" --line ")
                append(bp.line + 1)
                bp.condition?.let { cond ->
                    append(" --condition ")
                    append(quoteForLldb(cond))
                }
            }

            val out = runCatching { dbg.sendCommand(buildCmd(), timeoutMs = 10_000).getOrThrow() }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    if (sessionId != sid || debugger !== dbg) return
                    _events.emit(DebugEvent.Error(Strings.debug_error_set_breakpoint_failed.strOr(context, t.message ?: "")))
                    ""
                }
            if (sessionId != sid || debugger !== dbg) return
            val lowered = out.lowercase()
            val ok = out.contains("Breakpoint", ignoreCase = true) &&
                !lowered.contains("error:") &&
                !lowered.contains("0 locations")

            breakpointStore.updateVerified(
                id = bp.id,
                address = 0,
                verified = ok
            )
        }
    }

    private fun parsePauseReason(output: String): PauseReason {
        if (isFirstStop) return PauseReason.ENTRY
        val lower = output.lowercase()
        return when {
            "stop reason = breakpoint" in lower -> PauseReason.BREAKPOINT
            "stop reason = step" in lower -> PauseReason.STEP
            "stop reason = instruction step" in lower -> PauseReason.STEP
            "stop reason = signal" in lower && "sigint" in lower -> PauseReason.USER_REQUEST
            "stop reason = signal" in lower -> PauseReason.SIGNAL
            else -> PauseReason.BREAKPOINT
        }
    }

    private fun parseFirstError(output: String): String? {
        val m = errorLineRegex.find(output) ?: return null
        return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseExitCode(output: String): Int? {
        val regexes = listOf(
            Regex("""exited with status = (\d+)""", RegexOption.IGNORE_CASE),
            Regex("""exited with status (\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Process \d+ exited with status = (\d+)""", RegexOption.IGNORE_CASE)
        )
        for (r in regexes) {
            val m = r.find(output) ?: continue
            return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun parseLocation(output: String): SourceLocation? {
        val frame0 = Regex(
            """frame #0:\s+(0x[0-9a-fA-F]+)\s+(.+?)(?:\s+at\s+(.+?):(\d+))?\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        ).find(output)

        if (frame0 != null) {
            val address = frame0.groupValues[1].toLongOrNull(16) ?: 0L
            val function = frame0.groupValues[2].trim().ifBlank { null }
            val file = frame0.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() } ?: ""
            val line = frame0.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            if (file.isNotBlank() && line > 0) {
                return SourceLocation(
                    file = file,
                    line = line,
                    function = function,
                    address = address
                )
            }
        }

        val at = Regex("""\sat\s+(.+?):(\d+)\b""").find(output)
        return at?.let {
            SourceLocation(
                file = it.groupValues[1],
                line = it.groupValues[2].toIntOrNull() ?: 0
            )
        }
    }

    private fun parseBacktrace(output: String): List<StackFrame> {
        val frames = mutableListOf<StackFrame>()
        val frameRegex = Regex(
            """^\s*\*?\s*frame #(\d+):\s+(0x[0-9a-fA-F]+)\s+(.+?)(?:\s+at\s+(.+?):(\d+))?\s*$""",
            RegexOption.MULTILINE
        )
        for (m in frameRegex.findAll(output)) {
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val address = m.groupValues[2].toLongOrNull(16) ?: 0L
            val name = m.groupValues[3].trim().ifBlank { "<unknown>" }
            val file = m.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
            val line = m.groupValues.getOrNull(5)?.toIntOrNull()
            frames += StackFrame(
                id = id,
                name = name,
                file = file,
                line = line,
                address = address
            )
        }
        return frames
    }

    private fun parseVariables(output: String): List<Variable> {
        // LLDB 的 `frame variable` 输出会因目标语言/版本有所差异：
        // - "(int) x = 1"
        // - "int x = 1"
        // - "x = 1"
        val vars = mutableListOf<Variable>()
        val lines = output.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()

        val typedParen = Regex("""^\s*\(([^)]+)\)\s+(.+?)\s+=\s+(.*)\s*$""")
        val genericAssign = Regex("""^\s*(.+?)\s+=\s+(.*)\s*$""")

        for (line in lines) {
            val m1 = typedParen.matchEntire(line)
            if (m1 != null) {
                val type = m1.groupValues[1].trim()
                val name = m1.groupValues[2].trim()
                val value = m1.groupValues[3].trim()
                if (name.isNotBlank()) {
                    vars += Variable(name = name, value = value, type = type)
                }
                continue
            }

            val m2 = genericAssign.matchEntire(line) ?: continue
            val left = m2.groupValues[1].trim()
            val value = m2.groupValues[2].trim()
            if (left.isBlank()) continue

            // 尝试从 "int x" 拆出 type/name；否则 type 留空
            val parts = left.split(Regex("""\s+""")).filter { it.isNotBlank() }
            val name = parts.lastOrNull().orEmpty()
            val type = if (parts.size >= 2) parts.dropLast(1).joinToString(" ") else ""
            if (name.isBlank()) continue

            vars += Variable(name = name, value = value, type = type)
        }

        return vars
    }

    private fun quoteForLldb(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun onStdoutChunk(text: String, expectedSessionId: String) {
        if (sessionId != expectedSessionId) return
        val snapshot = synchronized(outputBufferLock) {
            outputBuffer.append(text)
            // 保留尾部，避免无限增长
            val maxChars = 50_000
            if (outputBuffer.length > maxChars) {
                outputOffset += outputBuffer.length - maxChars
                outputBuffer = StringBuilder(outputBuffer.takeLast(maxChars))
            }
            outputBuffer.toString()
        }

        val stop = stopRegex.findAll(snapshot).lastOrNull()
        if (stop != null && stop.range.first + outputOffset != lastHandledStopMarker) {
            lastHandledStopMarker = stop.range.first + outputOffset
            val slice = snapshot.substring(stop.range.first)
            scope.launch { handleStopped(slice, expectedSessionId) }
        }

        val exit = exitRegex.findAll(snapshot).lastOrNull()
        if (exit != null && exit.range.first + outputOffset != lastHandledExitMarker) {
            lastHandledExitMarker = exit.range.first + outputOffset
            val slice = snapshot.substring(exit.range.first)
            val exitCode = parseExitCode(slice)
            scope.launch { handleExited(exitCode, expectedSessionId) }
        }

        val launched = launchRegex.findAll(snapshot).lastOrNull()
        if (launched != null && launched.range.first + outputOffset != lastHandledLaunchMarker) {
            lastHandledLaunchMarker = launched.range.first + outputOffset
            targetPid = Regex("Process\\s+(\\d+)").find(launched.value)?.groupValues?.get(1)?.toIntOrNull()
            val sid = sessionId ?: return
            val current = _state.value
            if (current is DebugState.Starting) {
                _state.value = DebugState.Running(sid)
            }
        }
    }

    private suspend fun handleStopped(outputSnapshot: String, sid: String) {
        stateRefreshMutex.withLock {
            if (sessionId != sid) return
            val reason = parsePauseReason(outputSnapshot)
            val location = parseLocation(outputSnapshot)
            _state.value = DebugState.Paused(
                sessionId = sid,
                reason = reason,
                location = location
            )
            _events.emit(DebugEvent.TargetReady(sid))
            syncBreakpointsIfNeeded()
            refreshPausedState()
            if (sessionId != sid) return
            isFirstStop = false
        }
    }

    private suspend fun handleExited(exitCode: Int?, sid: String) {
        stateRefreshMutex.withLock {
            if (sessionId != sid) return
            _state.value = DebugState.Terminated(
                sessionId = sid,
                reason = TerminateReason.NORMAL_EXIT,
                exitCode = exitCode
            )
            _events.emit(DebugEvent.SessionEnded(sid, Strings.debug_session_ended_exit.strOr(context, exitCode ?: 0)))
            cleanupSession()
        }
    }

    private fun cleanupSession() {
        sessionId = null
        targetPid = null
        val closed = debugger
        debugger = null
        outputJob?.cancel()
        outputJob = null
        failureJob?.cancel()
        failureJob = null
        closed?.close()
        isFirstStop = true
        breakpointSyncPending = false
        synchronized(outputBufferLock) {
            outputBuffer = StringBuilder()
            outputOffset = 0L
            lastHandledStopMarker = -1L
            lastHandledExitMarker = -1L
            lastHandledLaunchMarker = -1L
        }
        _callStack.value = emptyList()
        _variables.value = emptyList()
    }
}

/**
 * 调试事件（一次性通知）
 */
sealed class DebugEvent {
    data class SessionStarting(val sessionId: String) : DebugEvent()
    data class TargetReady(val sessionId: String) : DebugEvent()
    data class SessionEnded(val sessionId: String, val message: String) : DebugEvent()
    data class Error(val message: String) : DebugEvent()
    data class OutputReceived(val text: String, val isStderr: Boolean = false) : DebugEvent()
}
