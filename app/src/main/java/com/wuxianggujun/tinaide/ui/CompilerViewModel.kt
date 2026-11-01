package com.wuxianggujun.tinaide.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.BuildSystemDetector
import com.wuxianggujun.tinaide.core.compile.CMakeBuildTypeOption
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.file.IProjectContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 编译 ViewModel（按 AI 方案）
 *
 * 负责：
 * - 调用 CompileProjectUseCase 执行编译
 * - 暴露编译进度与结果事件
 * - 支持取消编译与停止进程
 */
class CompilerViewModel(
    private val compileUseCase: CompileProjectUseCase,
    private val projectContext: IProjectContext,
    private val processManager: ProcessManager
) : ViewModel() {

    private val _progress = MutableStateFlow<CompileProjectUseCase.CompileProgress?>(null)
    val progress: StateFlow<CompileProjectUseCase.CompileProgress?> = _progress.asStateFlow()

    // Activity 暂时不可见时也要保留编译结果，恢复后只消费一次。
    private val eventsChannel = Channel<CompileEvent>(
        capacity = Channel.BUFFERED,
        onUndeliveredElement = { event ->
            Timber.tag(TAG).w("Discarding undelivered compile result ticket=%s", event.ticket.id)
            event.ticket.finish()
        },
    )
    val events = eventsChannel.receiveAsFlow()

    val actionGate = CompileActionGate()
    // Keep the second event queue with the ViewModel too: rotation must not orphan a ticket.
    internal val uiEventsChannel = Channel<CompileActionsHelper.UiEvent>(
        capacity = Channel.BUFFERED,
        onUndeliveredElement = CompileActionsHelper.UiEvent::releaseUndeliveredLaunch,
    )

    private var compileJob: Job? = null

    fun compile(
        operation: CompileProjectUseCase.Operation = CompileProjectUseCase.Operation.forRun(),
        ticket: CompileActionGate.Ticket? = null,
    ) {
        val request = ticket ?: actionGate.tryBegin() ?: return
        launchOperation(operation.action, request) {
            compileUseCase.execute(
                operation = operation,
                onProgress = { p -> if (request.isCurrent) _progress.value = p }
            )
        }
    }

    fun reconfigureCMake(ticket: CompileActionGate.Ticket? = null) {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_RECONFIGURE, ticket)
    }

    fun clearCMakeBuildDirectory(ticket: CompileActionGate.Ticket? = null) {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_CLEAR_BUILD_DIRECTORY, ticket)
    }

    fun clearAndReconfigureCMake(ticket: CompileActionGate.Ticket? = null) {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_CLEAR_AND_RECONFIGURE, ticket)
    }

    fun configureOnlyCMake(ticket: CompileActionGate.Ticket? = null) {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_CONFIGURE_ONLY, ticket)
    }

    private fun runCMakeMaintenance(action: CompileProjectUseCase.Action, ticket: CompileActionGate.Ticket?) {
        val request = ticket ?: actionGate.tryBegin() ?: return
        launchOperation(action, request) {
            compileUseCase.executeCMakeMaintenance(action)
        }
    }

    private fun launchOperation(
        action: CompileProjectUseCase.Action,
        ticket: CompileActionGate.Ticket,
        operation: suspend () -> CompileProjectUseCase.Result
    ) {
        if (!ticket.startCompilation()) return
        val previousJob = compileJob
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext().job
            var resultQueued = false
            try {
                // Cancellation may still be unwinding native/compiler resources.
                previousJob?.cancelAndJoin()
                currentCoroutineContext().ensureActive()
                if (!ticket.isCurrent) return@launch
                val result = operation()
                currentCoroutineContext().ensureActive()
                if (!ticket.isCurrent) return@launch
                when (result) {
                    is CompileProjectUseCase.Result.Success -> {
                        eventsChannel.send(CompileEvent.Success(result.report, ticket))
                    }

                    is CompileProjectUseCase.Result.Error -> {
                        eventsChannel.send(
                            CompileEvent.Error(
                                action = result.action,
                                message = result.userMessage,
                                throwable = result.throwable,
                                ticket = ticket,
                            )
                        )
                    }
                }
                resultQueued = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // 避免未处理异常沿 viewModelScope 冒泡到主线程并导致应用崩溃。
                Timber.tag(TAG).e(t, "Compile operation failed unexpectedly: %s", action)
                if (ticket.isCurrent) {
                    eventsChannel.send(
                        CompileEvent.Error(
                            action = action,
                            message = t.message?.takeIf { it.isNotBlank() }
                                ?: Strings.error_unknown_generic.str(),
                            throwable = t,
                            ticket = ticket,
                        )
                    )
                    resultQueued = true
                }
            } finally {
                if (!resultQueued) ticket.finish()
                if (compileJob === runningJob) {
                    compileJob = null
                }
                processManager.clearCurrentRunJob(runningJob)
            }
        }
        compileJob = job
        processManager.setCurrentRunJob(job)
        job.start()
    }

    /**
     * 获取当前项目的运行配置管理器
     */
    suspend fun loadRunConfigurationManager(): RunConfigurationManager = withContext(Dispatchers.IO) {
        val project = projectContext.getCurrentProject() ?: return@withContext RunConfigurationManager()
        RunConfigurationManager.load(
            projectPath = project.rootPath,
            legacyCMakeBuildType = CMakeBuildTypeOption.fromValue(Prefs.legacyCmakeBuildType),
        )
    }

    /**
     * 保存运行配置管理器
     */
    suspend fun saveRunConfigurationManager(manager: RunConfigurationManager): Boolean = withContext(Dispatchers.IO) {
        val project = projectContext.getCurrentProject() ?: return@withContext false
        RunConfigurationManager.save(project.rootPath, manager)
    }

    /**
     * 获取当前选中的运行配置
     */
    suspend fun getRunConfiguration() = loadRunConfigurationManager().selectedConfig

    /**
     * 获取当前项目的构建系统类型
     */
    suspend fun detectBuildSystem(): BuildSystem = withContext(Dispatchers.IO) {
        val project = projectContext.getCurrentProject() ?: return@withContext BuildSystem.UNKNOWN
        BuildSystemDetector.detect(File(project.rootPath))
    }

    /**
     * 获取当前项目的可用构建目标（仅 CMake 项目有效）
     */
    suspend fun getAvailableTargets() = compileUseCase.getAvailableTargets()

    fun cancelCompile() {
        actionGate.cancel()
        compileJob?.cancel()
    }

    /**
     * 停止当前正在运行的程序
     */
    fun stopRunningProgram() {
        // 先取消编译任务
        cancelCompile()
        // 然后停止进程（直接使用 ProcessManager）
        if (processManager.isRunning()) {
            processManager.stopCurrentProcess()
        }
    }

    /**
     * 强制停止当前正在运行的程序
     */
    fun forceStopRunningProgram() {
        // 先取消编译任务
        cancelCompile()
        // 然后强制停止进程
        if (processManager.isRunning()) {
            processManager.forceStopCurrentProcess()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 被清理时，取消编译任务
        cancelCompile()
        // TerminalActivity 的 PTY 由 ITerminalSessionManager 独立管理，这里不能跨边界清理。
    }

    private companion object {
        private const val TAG = "CompilerViewModel"
    }
}

sealed class CompileEvent {
    abstract val ticket: CompileActionGate.Ticket

    data class Success(
        val report: CompileProjectUseCase.Report,
        override val ticket: CompileActionGate.Ticket,
    ) : CompileEvent()
    data class Error(
        val action: CompileProjectUseCase.Action,
        val message: String,
        val throwable: Throwable?,
        override val ticket: CompileActionGate.Ticket,
    ) : CompileEvent()
}
