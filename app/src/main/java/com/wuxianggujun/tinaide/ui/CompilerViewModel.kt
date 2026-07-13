package com.wuxianggujun.tinaide.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.BuildSystemDetector
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.file.IProjectContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
    private val eventsChannel = Channel<CompileEvent>(capacity = Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private var compileJob: Job? = null

    fun compile(
        operation: CompileProjectUseCase.Operation = CompileProjectUseCase.Operation.forRun()
    ) {
        launchOperation(operation.action) {
            compileUseCase.execute(
                operation = operation,
                onProgress = { p -> _progress.value = p }
            )
        }
    }

    fun reconfigureCMake() {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_RECONFIGURE)
    }

    fun clearCMakeBuildDirectory() {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_CLEAR_BUILD_DIRECTORY)
    }

    fun clearAndReconfigureCMake() {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_CLEAR_AND_RECONFIGURE)
    }

    private fun runCMakeMaintenance(action: CompileProjectUseCase.Action) {
        launchOperation(action) {
            compileUseCase.executeCMakeMaintenance(action)
        }
    }

    private fun launchOperation(
        action: CompileProjectUseCase.Action,
        operation: suspend () -> CompileProjectUseCase.Result
    ) {
        compileJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext().job
            try {
                when (val result = operation()) {
                    is CompileProjectUseCase.Result.Success -> {
                        eventsChannel.send(CompileEvent.Success(result.report))
                    }

                    is CompileProjectUseCase.Result.Error -> {
                        eventsChannel.send(
                            CompileEvent.Error(
                                action = result.action,
                                message = result.userMessage,
                                throwable = result.throwable
                            )
                        )
                    }
                }
            } catch (_: CancellationException) {
                // 操作被取消，不发送任何事件
            } catch (t: Throwable) {
                // 避免未处理异常沿 viewModelScope 冒泡到主线程并导致应用崩溃。
                Timber.tag(TAG).e(t, "Compile operation failed unexpectedly: %s", action)
                eventsChannel.send(
                    CompileEvent.Error(
                        action = action,
                        message = t.message?.takeIf { it.isNotBlank() }
                            ?: Strings.error_unknown_generic.str(),
                        throwable = t
                    )
                )
            } finally {
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
    fun getRunConfigurationManager(): RunConfigurationManager {
        val project = projectContext.getCurrentProject() ?: return RunConfigurationManager()
        return RunConfigurationManager.load(project.rootPath)
    }

    /**
     * 保存运行配置管理器
     */
    fun saveRunConfigurationManager(manager: RunConfigurationManager): Boolean {
        val project = projectContext.getCurrentProject() ?: return false
        return RunConfigurationManager.save(project.rootPath, manager)
    }

    /**
     * 获取当前选中的运行配置
     */
    fun getRunConfiguration() = getRunConfigurationManager().selectedConfig

    /**
     * 获取当前项目的构建系统类型
     */
    fun detectBuildSystem(): BuildSystem {
        val project = projectContext.getCurrentProject() ?: return BuildSystem.UNKNOWN
        return BuildSystemDetector.detect(File(project.rootPath))
    }

    /**
     * 获取当前项目的可用构建目标（仅 CMake 项目有效）
     */
    suspend fun getAvailableTargets() = compileUseCase.getAvailableTargets()

    fun cancelCompile() {
        compileJob?.cancel()
        compileJob = null
    }

    /**
     * 停止当前正在运行的程序
     */
    fun stopRunningProgram() {
        // 先取消编译任务
        compileJob?.cancel()
        compileJob = null
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
        compileJob?.cancel()
        compileJob = null
        // 然后强制停止进程
        if (processManager.isRunning()) {
            processManager.forceStopCurrentProcess()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 被清理时，取消编译任务
        compileJob?.cancel()
        compileJob = null
        // TerminalActivity 的 PTY 由 ITerminalSessionManager 独立管理，这里不能跨边界清理。
    }

    private companion object {
        private const val TAG = "CompilerViewModel"
    }
}

sealed class CompileEvent {
    data class Success(val report: CompileProjectUseCase.Report) : CompileEvent()
    data class Error(
        val action: CompileProjectUseCase.Action,
        val message: String,
        val throwable: Throwable?
    ) : CompileEvent()
}
