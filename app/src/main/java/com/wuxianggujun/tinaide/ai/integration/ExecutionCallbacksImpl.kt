package com.wuxianggujun.tinaide.ai.integration

import com.wuxianggujun.tinaide.ai.tools.executor.execution.*
import com.wuxianggujun.tinaide.core.compile.BuildLogLevel
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.BuildSystemDetector
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.BottomPanelController
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.compose.components.BottomPanelTab
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 执行回调实现
 *
 * 提供项目编译、运行、测试等执行相关的回调功能
 * 与 TinaIDE 的编译系统集成
 */
class ExecutionCallbacksImpl(
    private val projectRoot: String,
    private val processManager: ProcessManager,
    private val runConfigManager: RunConfigurationManager,
    private val editorManager: IEditorManager,
    private val outputManager: IOutputManager,
    private val compileProjectUseCase: CompileProjectUseCase,
    private val scope: CoroutineScope,
    private val bottomPanelViewModel: BottomPanelViewModel,
    private val bottomPanelController: BottomPanelController
) : ExecutionCallbacks {

    companion object {
        private const val TAG = "ExecutionCallbacksImpl"
    }

    // 存储执行状态、结果与输出来源
    private val executionStates = ConcurrentHashMap<String, ExecutionStatus>()
    private val executionResults = ConcurrentHashMap<String, ExecutionResult>()
    private val executionOutputModes = ConcurrentHashMap<String, ExecutionOutputMode>()
    private val executionCompletedAt = ConcurrentHashMap<String, Long>()
    private val activeProcessExecutionId = AtomicReference<String?>(null)

    override fun runProject(request: RunRequest): ExecutionResult {
        val executionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        return try {
            // 标记为运行中
            beginExecution(executionId, ExecutionOutputMode.RUN)
            resetOutputForExecution(ExecutionOutputMode.RUN)
            outputManager.appendOutput("Project execution started\n", IOutputManager.OutputChannel.RUN)

            // 切换到运行输出面板
            scope.launch(Dispatchers.Main) {
                try {
                    bottomPanelViewModel.setSelectedTab(BottomPanelTab.RUN_OUTPUT)
                    bottomPanelController.expandToDefault()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to switch to run output panel")
                }
            }

            // 获取运行配置
            val config = resolveRunConfiguration(request)
            val started = ExecutionResult(
                executionId = executionId,
                success = true,
                exitCode = 0,
                output = buildString {
                    append("Project execution started with configuration: ${config.name}")
                    if (request.arguments.isNotEmpty()) {
                        append("\nArguments: ${request.arguments.joinToString(" ")}")
                    }
                    request.workingDirectory?.takeIf { it.isNotBlank() }?.let {
                        append("\nWorking directory: $it")
                    }
                },
                errorOutput = "",
                duration = 0,
                status = ExecutionStatus.RUNNING
            )
            storeExecutionResult(started)

            // 异步执行保存、编译和运行
            scope.launch(Dispatchers.IO) {
                try {
                    // 先保存所有文件
                    val saveResults = editorManager.saveAll(SaveReason.MANUAL)
                    val saveFailures = saveResults.filterIsInstance<SaveResult.Failure>()
                    if (saveFailures.isNotEmpty()) {
                        Timber.tag(TAG).w("Some files failed to save before running: ${saveFailures.size}")
                    }

                    val result = compileProjectUseCase.execute(
                        operation = CompileProjectUseCase.Operation.forRun(),
                        runConfig = config,
                        launchEnvironment = request.environment,
                        onProgress = { progress ->
                            // 可以在这里记录编译进度
                            Timber.tag(TAG).d("Compile progress: ${progress.current}/${progress.total} - ${progress.fileName}")
                        }
                    )

                    val duration = System.currentTimeMillis() - startTime
                    val executionResult = when (result) {
                        is CompileProjectUseCase.Result.Success -> ExecutionResult(
                            executionId = executionId,
                            success = true,
                            exitCode = 0,
                            output = result.report.summary,
                            errorOutput = "",
                            duration = duration,
                            status = ExecutionStatus.SUCCESS
                        )
                        is CompileProjectUseCase.Result.Error -> ExecutionResult(
                            executionId = executionId,
                            success = false,
                            exitCode = 1,
                            output = "",
                            errorOutput = result.userMessage,
                            duration = duration,
                            status = ExecutionStatus.FAILED
                        )
                    }
                    completeExecutionIfActive(executionResult)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to run project")
                    val duration = System.currentTimeMillis() - startTime
                    completeExecutionIfActive(
                        ExecutionResult(
                            executionId = executionId,
                            success = false,
                            exitCode = -1,
                            output = "",
                            errorOutput = "Failed to run project: ${e.message}",
                            duration = duration,
                            status = ExecutionStatus.FAILED
                        )
                    )
                }
            }

            // 立即返回运行中状态
            started
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start project execution")
            val result = ExecutionResult(
                executionId = executionId,
                success = false,
                exitCode = -1,
                output = "",
                errorOutput = "Failed to start project: ${e.message}",
                duration = 0,
                status = ExecutionStatus.FAILED
            )
            completeExecutionIfActive(result)
            result
        }
    }

    override fun runTests(request: TestRequest): ExecutionResult {
        val executionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        return try {
            beginExecution(executionId, ExecutionOutputMode.RUN)
            resetOutputForExecution(ExecutionOutputMode.RUN)

            scope.launch(Dispatchers.Main) {
                try {
                    bottomPanelViewModel.setSelectedTab(BottomPanelTab.RUN_OUTPUT)
                    bottomPanelController.expandToDefault()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to switch to run output panel for tests")
                }
            }

            outputManager.appendOutput("Test execution started\n", IOutputManager.OutputChannel.RUN)

            val started = ExecutionResult(
                executionId = executionId,
                success = true,
                exitCode = 0,
                output = buildTestStartMessage(request),
                errorOutput = "",
                duration = 0,
                status = ExecutionStatus.RUNNING
            )
            storeExecutionResult(started)

            scope.launch(Dispatchers.IO) {
                try {
                    val saveResults = editorManager.saveAll(SaveReason.MANUAL)
                    val saveFailures = saveResults.filterIsInstance<SaveResult.Failure>()
                    if (saveFailures.isNotEmpty()) {
                        Timber.tag(TAG).w("Some files failed to save before running tests: ${saveFailures.size}")
                    }

                    val testPlan = resolveTestExecutionPlan(request)
                    outputManager.appendOutput(
                        "${testPlan.description}\n",
                        IOutputManager.OutputChannel.RUN
                    )

                    val result = compileProjectUseCase.execute(
                        operation = testPlan.operation,
                        runConfig = testPlan.runConfig,
                        targetName = testPlan.targetName,
                        onProgress = { progress ->
                            Timber.tag(TAG).d("Test progress: ${progress.current}/${progress.total} - ${progress.fileName}")
                        }
                    )

                    val duration = System.currentTimeMillis() - startTime
                    val executionResult = when (result) {
                        is CompileProjectUseCase.Result.Success -> ExecutionResult(
                            executionId = executionId,
                            success = true,
                            exitCode = 0,
                            output = result.report.summary,
                            errorOutput = "",
                            duration = duration,
                            status = ExecutionStatus.SUCCESS
                        )
                        is CompileProjectUseCase.Result.Error -> ExecutionResult(
                            executionId = executionId,
                            success = false,
                            exitCode = 1,
                            output = "",
                            errorOutput = result.userMessage,
                            duration = duration,
                            status = ExecutionStatus.FAILED
                        )
                    }
                    if (completeExecutionIfActive(executionResult)) {
                        when (executionResult.status) {
                            ExecutionStatus.SUCCESS -> outputManager.appendOutput(
                                "Test execution finished\n${executionResult.output}\n",
                                IOutputManager.OutputChannel.RUN
                            )
                            ExecutionStatus.FAILED -> outputManager.appendOutput(
                                "Test execution failed: ${executionResult.errorOutput}\n",
                                IOutputManager.OutputChannel.RUN
                            )
                            else -> Unit
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to run tests")
                    val duration = System.currentTimeMillis() - startTime
                    val message = "Failed to run tests: ${e.message}"
                    if (completeExecutionIfActive(
                        ExecutionResult(
                            executionId = executionId,
                            success = false,
                            exitCode = -1,
                            output = "",
                            errorOutput = message,
                            duration = duration,
                            status = ExecutionStatus.FAILED
                        )
                    )) {
                        outputManager.appendOutput("$message\n", IOutputManager.OutputChannel.RUN)
                    }
                }
            }

            started
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start tests")
            val result = ExecutionResult(
                executionId = executionId,
                success = false,
                exitCode = -1,
                output = "",
                errorOutput = "Failed to start tests: ${e.message}",
                duration = 0,
                status = ExecutionStatus.FAILED
            )
            completeExecutionIfActive(result)
            result
        }
    }

    override fun buildProject(request: BuildRequest): ExecutionResult {
        val executionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        return try {
            beginExecution(executionId, ExecutionOutputMode.BUILD)
            resetOutputForExecution(ExecutionOutputMode.BUILD)
            outputManager.appendOutput("Build started\n", IOutputManager.OutputChannel.BUILD)

            // 切换到构建日志面板
            scope.launch(Dispatchers.Main) {
                try {
                    bottomPanelViewModel.setSelectedTab(BottomPanelTab.BUILD_LOG)
                    bottomPanelController.expandToDefault()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to switch to build log panel")
                }
            }

            // 获取运行配置
            val config = resolveBuildConfiguration(request)
            val buildSystem = detectBuildSystem()
            val buildInfo = buildString {
                append("Build started")
                if (request.clean) {
                    if (buildSystem == BuildSystem.CMAKE) {
                        append(" (clean)")
                    } else {
                        append(" (clean requested, unsupported for $buildSystem)")
                    }
                }
                if (request.rebuild) append(" (rebuild)")
                request.target?.let { append(" - target: $it") }
            }
            val started = ExecutionResult(
                executionId = executionId,
                success = true,
                exitCode = 0,
                output = buildInfo,
                errorOutput = "",
                duration = 0,
                status = ExecutionStatus.RUNNING
            )
            storeExecutionResult(started)

            // 异步执行保存和构建
            scope.launch(Dispatchers.IO) {
                try {
                    // 先保存所有文件
                    val saveResults = editorManager.saveAll(SaveReason.MANUAL)
                    val saveFailures = saveResults.filterIsInstance<SaveResult.Failure>()
                    if (saveFailures.isNotEmpty()) {
                        Timber.tag(TAG).w("Some files failed to save before building: ${saveFailures.size}")
                    }

                    if (request.clean) {
                        val cleanResult = runRequestedCleanBuild(buildSystem)
                        if (cleanResult is CompileProjectUseCase.Result.Error) {
                            val duration = System.currentTimeMillis() - startTime
                            completeExecutionIfActive(
                                ExecutionResult(
                                    executionId = executionId,
                                    success = false,
                                    exitCode = 1,
                                    output = "",
                                    errorOutput = cleanResult.userMessage,
                                    duration = duration,
                                    status = ExecutionStatus.FAILED
                                )
                            )
                            return@launch
                        }
                    }

                    val result = compileProjectUseCase.execute(
                        operation = CompileProjectUseCase.Operation.forBuild(),
                        runConfig = config,
                        targetName = request.target?.takeIf { it.isNotBlank() },
                        onProgress = { progress ->
                            // 可以在这里记录编译进度
                            Timber.tag(TAG).d("Build progress: ${progress.current}/${progress.total} - ${progress.fileName}")
                        }
                    )

                    val duration = System.currentTimeMillis() - startTime
                    val executionResult = when (result) {
                        is CompileProjectUseCase.Result.Success -> ExecutionResult(
                            executionId = executionId,
                            success = true,
                            exitCode = 0,
                            output = result.report.summary,
                            errorOutput = "",
                            duration = duration,
                            status = ExecutionStatus.SUCCESS
                        )
                        is CompileProjectUseCase.Result.Error -> ExecutionResult(
                            executionId = executionId,
                            success = false,
                            exitCode = 1,
                            output = "",
                            errorOutput = result.userMessage,
                            duration = duration,
                            status = ExecutionStatus.FAILED
                        )
                    }
                    completeExecutionIfActive(executionResult)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to build project")
                    val duration = System.currentTimeMillis() - startTime
                    completeExecutionIfActive(
                        ExecutionResult(
                            executionId = executionId,
                            success = false,
                            exitCode = -1,
                            output = "",
                            errorOutput = "Failed to build project: ${e.message}",
                            duration = duration,
                            status = ExecutionStatus.FAILED
                        )
                    )
                }
            }

            // 立即返回构建中状态
            started
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start build")
            val result = ExecutionResult(
                executionId = executionId,
                success = false,
                exitCode = -1,
                output = "",
                errorOutput = "Failed to start build: ${e.message}",
                duration = 0,
                status = ExecutionStatus.FAILED
            )
            completeExecutionIfActive(result)
            result
        }
    }

    private fun resolveRunConfiguration(request: RunRequest): RunConfiguration {
        val baseConfig = if (request.configuration != null) {
            runConfigManager.configurations.find { it.name == request.configuration }
                ?: runConfigManager.selectedConfig
        } else {
            runConfigManager.selectedConfig
        }

        val overriddenArgs = request.arguments
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
            ?: baseConfig.args
        val overriddenWorkDir = request.workingDirectory?.takeIf { it.isNotBlank() } ?: baseConfig.workDir

        if (overriddenArgs == baseConfig.args && overriddenWorkDir == baseConfig.workDir) {
            return baseConfig
        }
        return baseConfig.copy(
            args = overriddenArgs,
            workDir = overriddenWorkDir
        ).normalized()
    }

    private fun resolveBuildConfiguration(request: BuildRequest): RunConfiguration = if (request.target != null) {
        runConfigManager.configurations.find { it.targetName == request.target }
            ?: runConfigManager.selectedConfig
    } else {
        runConfigManager.selectedConfig
    }

    private fun detectBuildSystem(): BuildSystem = BuildSystemDetector.detect(File(projectRoot))

    private suspend fun runRequestedCleanBuild(
        buildSystem: BuildSystem
    ): CompileProjectUseCase.Result? {
        if (buildSystem != BuildSystem.CMAKE) {
            Timber.tag(TAG).i("Ignoring clean build request for unsupported build system: $buildSystem")
            return null
        }
        return compileProjectUseCase.executeCMakeMaintenance(
            CompileProjectUseCase.Action.CMAKE_CLEAR_BUILD_DIRECTORY
        )
    }

    private suspend fun resolveTestExecutionPlan(request: TestRequest): AiTestExecutionPlan {
        val targets = runCatching { compileProjectUseCase.getAvailableTargets() }
            .onFailure { error -> Timber.tag(TAG).w(error, "Failed to query test targets") }
            .getOrDefault(emptyList())
        return AiTestExecutionPlanner.resolve(
            request = request,
            selectedConfig = runConfigManager.selectedConfig,
            targets = targets
        )
    }

    override fun stopExecution(executionId: String): Boolean {
        return try {
        // 停止当前进程
        val status = executionStates[executionId] ?: return false
        if (status != ExecutionStatus.RUNNING && status != ExecutionStatus.PENDING) {
            return false
        }

        if (!activeProcessExecutionId.compareAndSet(executionId, null)) {
            Timber.tag(TAG).w("Skip stopping non-active execution: $executionId")
            return false
        }

        if (!processManager.stopCurrentProcess()) {
            activeProcessExecutionId.compareAndSet(null, executionId)
            Timber.tag(TAG).w("No active process stopped for execution: $executionId")
            return false
        }
        executionStates[executionId] = ExecutionStatus.CANCELLED

        // 更新结果
        executionResults[executionId]?.let { result ->
            storeExecutionResult(
                result.copy(
                    status = ExecutionStatus.CANCELLED,
                    errorOutput = "Execution cancelled by user"
                )
            )
        }
        Timber.tag(TAG).i("Stopped execution: $executionId")
        true
    } catch (e: Exception) {
        activeProcessExecutionId.compareAndSet(null, executionId)
        Timber.tag(TAG).e(e, "Failed to stop execution: $executionId")
        false
    }
    }

    override fun getExecutionStatus(executionId: String): ExecutionStatus? {
        return executionStates[executionId]
    }

    override fun getExecutionOutput(executionId: String): ExecutionOutputResult? {
        val result = executionResults[executionId] ?: return null
        return ExecutionOutputResult(
            executionId = result.executionId,
            output = resolveLiveOutput(result),
            errorOutput = result.errorOutput,
            status = result.status,
            exitCode = result.exitCode
        )
    }

    private enum class ExecutionOutputMode {
        BUILD,
        RUN
    }

    private fun beginExecution(executionId: String, mode: ExecutionOutputMode) {
        executionStates[executionId] = ExecutionStatus.RUNNING
        executionOutputModes[executionId] = mode
        activeProcessExecutionId.set(executionId)
    }

    private fun resetOutputForExecution(mode: ExecutionOutputMode) {
        when (mode) {
            ExecutionOutputMode.BUILD -> outputManager.clearOutput(IOutputManager.OutputChannel.BUILD)
            ExecutionOutputMode.RUN -> {
                outputManager.clearOutput(IOutputManager.OutputChannel.BUILD)
                outputManager.clearOutput(IOutputManager.OutputChannel.RUN)
            }
        }
    }

    private fun resolveLiveOutput(result: ExecutionResult): String {
        val mode = executionOutputModes[result.executionId] ?: return result.output
        val liveOutput = when (mode) {
            ExecutionOutputMode.BUILD -> outputManager.getOutput(IOutputManager.OutputChannel.BUILD)
            ExecutionOutputMode.RUN -> buildString {
                val buildOutput = outputManager.getOutput(IOutputManager.OutputChannel.BUILD).trim()
                val runOutput = outputManager.getOutput(IOutputManager.OutputChannel.RUN).trim()
                if (buildOutput.isNotBlank()) {
                    appendLine("Build log:")
                    appendLine(buildOutput)
                }
                if (runOutput.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    appendLine("Run output:")
                    appendLine(runOutput)
                }
            }
        }.trimEnd()
        return liveOutput.ifBlank { result.output }
    }

    private fun completeExecutionIfActive(result: ExecutionResult): Boolean {
        var completed = false
        executionStates.compute(result.executionId) { _, currentStatus ->
            if (currentStatus == ExecutionStatus.CANCELLED) {
                currentStatus
            } else {
                completed = true
                result.status
            }
        }
        if (!completed) {
            Timber.tag(TAG).i("Skip terminal result for cancelled execution: ${result.executionId}")
            return false
        }
        storeExecutionResult(result)
        clearActiveProcessExecution(result.executionId)
        return true
    }

    private fun clearActiveProcessExecution(executionId: String) {
        activeProcessExecutionId.compareAndSet(executionId, null)
    }

    private fun storeExecutionResult(result: ExecutionResult) {
        executionResults[result.executionId] = result
        if (result.status.isTerminal()) {
            executionCompletedAt[result.executionId] = System.currentTimeMillis()
        } else {
            executionCompletedAt.remove(result.executionId)
        }
    }

    override fun getBuildErrors(executionId: String?): BuildErrorsResult {
        // 如果提供了executionId，尝试从该次执行结果中获取错误
        if (executionId != null) {
            val result = executionResults[executionId]
            if (result != null && result.errorOutput.isNotBlank()) {
                // 解析错误输出
                val errors = mutableListOf<BuildError>()
                var errorCount = 0
                var warningCount = 0

                result.errorOutput.lines().forEach { line ->
                    if (line.isBlank()) return@forEach

                    // 尝试解析编译器错误格式: file:line:column: error/warning: message
                    val errorPattern = """^(.+?):(\d+):(\d+):\s*(error|warning):\s*(.+)$""".toRegex()
                    val match = errorPattern.find(line)

                    if (match != null) {
                        val (file, lineNum, col, severityStr, message) = match.destructured
                        val severity = if (severityStr == "error") {
                            errorCount++
                            ErrorSeverity.ERROR
                        } else {
                            warningCount++
                            ErrorSeverity.WARNING
                        }

                        errors.add(
                            BuildError(
                                file = file,
                                line = lineNum.toIntOrNull(),
                                column = col.toIntOrNull(),
                                message = message,
                                severity = severity
                            )
                        )
                    } else {
                        // 如果无法解析，作为普通错误添加
                        if (line.contains("error", ignoreCase = true)) {
                            errorCount++
                            errors.add(
                                BuildError(
                                    file = null,
                                    line = null,
                                    column = null,
                                    message = line,
                                    severity = ErrorSeverity.ERROR
                                )
                            )
                        } else if (line.contains("warning", ignoreCase = true)) {
                            warningCount++
                            errors.add(
                                BuildError(
                                    file = null,
                                    line = null,
                                    column = null,
                                    message = line,
                                    severity = ErrorSeverity.WARNING
                                )
                            )
                        }
                    }
                }

                return BuildErrorsResult(
                    hasErrors = errorCount > 0,
                    errorCount = errorCount,
                    warningCount = warningCount,
                    errors = errors
                )
            }
        }

        // 否则从全局构建日志和诊断信息中获取
        val buildLogs = bottomPanelViewModel.buildLogs.value
        val diagnostics = bottomPanelViewModel.diagnostics.value

        val errors = mutableListOf<BuildError>()
        var errorCount = 0
        var warningCount = 0

        // 从构建日志中提取错误
        buildLogs.forEach { logEntry ->
            when (logEntry.level) {
                BuildLogLevel.ERROR -> {
                    errorCount++
                    errors.add(
                        BuildError(
                            file = null,
                            line = null,
                            column = null,
                            message = logEntry.message,
                            severity = ErrorSeverity.ERROR
                        )
                    )
                }
                BuildLogLevel.WARN -> {
                    warningCount++
                    errors.add(
                        BuildError(
                            file = null,
                            line = null,
                            column = null,
                            message = logEntry.message,
                            severity = ErrorSeverity.WARNING
                        )
                    )
                }
                else -> {}
            }
        }

        // 从诊断信息中提取错误
        diagnostics.forEach { diagnostic ->
            val severity = when (diagnostic.severity) {
                com.wuxianggujun.tinaide.core.lsp.Diagnostic.Severity.ERROR -> {
                    errorCount++
                    ErrorSeverity.ERROR
                }
                com.wuxianggujun.tinaide.core.lsp.Diagnostic.Severity.WARNING -> {
                    warningCount++
                    ErrorSeverity.WARNING
                }
                else -> ErrorSeverity.INFO
            }

            errors.add(
                BuildError(
                    file = diagnostic.fileName,
                    line = diagnostic.line,
                    column = diagnostic.column,
                    message = diagnostic.message,
                    severity = severity
                )
            )
        }

        return BuildErrorsResult(
            hasErrors = errorCount > 0,
            errorCount = errorCount,
            warningCount = warningCount,
            errors = errors
        )
    }

    override fun navigateToRunOutput() {
        scope.launch(Dispatchers.Main) {
            try {
                // 切换到运行输出标签
                bottomPanelViewModel.setSelectedTab(BottomPanelTab.RUN_OUTPUT)
                // 展开底部面板
                bottomPanelController.expandToDefault()
                Timber.tag(TAG).d("Navigated to run output panel")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to navigate to run output")
            }
        }
    }

    override fun navigateToBuildLog() {
        scope.launch(Dispatchers.Main) {
            try {
                // 切换到构建日志标签
                bottomPanelViewModel.setSelectedTab(BottomPanelTab.BUILD_LOG)
                // 展开底部面板
                bottomPanelController.expandToDefault()
                Timber.tag(TAG).d("Navigated to build log panel")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to navigate to build log")
            }
        }
    }

    /**
     * 获取执行结果（用于查询已完成的执行）
     */
    fun getExecutionResult(executionId: String): ExecutionResult? = executionResults[executionId]

    /**
     * 清理旧的执行记录
     */
    fun cleanupOldExecutions(maxAge: Long = 3600000) { // 默认1小时
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()

        executionResults.forEach { (id, result) ->
            val completedAt = executionCompletedAt[id] ?: return@forEach
            if (result.status.isTerminal() && now - completedAt > maxAge) {
                toRemove.add(id)
            }
        }

        toRemove.forEach { id ->
            executionStates.remove(id)
            executionResults.remove(id)
            executionOutputModes.remove(id)
            executionCompletedAt.remove(id)
        }
    }

    private fun ExecutionStatus.isTerminal(): Boolean = this in setOf(
        ExecutionStatus.SUCCESS,
        ExecutionStatus.FAILED,
        ExecutionStatus.CANCELLED,
        ExecutionStatus.TIMEOUT
    )
}
