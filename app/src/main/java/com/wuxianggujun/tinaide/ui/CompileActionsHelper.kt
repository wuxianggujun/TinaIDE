package com.wuxianggujun.tinaide.ui

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticsLog
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 编译操作帮助类
 *
 * 封装编译、调试、终端运行等操作的协调逻辑
 * 通过最小协作者接口协调命令执行、UI 同步与进程控制
 */
class CompileActionsHelper(
    private val context: Context,
    private val commandRunner: CommandRunner,
    private val uiBridge: UiBridge,
    private val projectContext: IProjectContext,
    private val editorManager: IEditorManager,
    private val processController: ProcessController,
) {
    interface CommandRunner {
        fun compile(operation: CompileProjectUseCase.Operation)
        fun reconfigureCMake()
        fun clearCMakeBuildDirectory()
        fun clearAndReconfigureCMake()
    }

    interface UiBridge {
        fun setCompiling(compiling: Boolean)
        fun clearBuildLogs()
        fun showBuildLog()
        suspend fun expandBottomPanel()
        fun startDebugSession(
            programPath: String,
            workingDirectory: String?,
            arguments: List<String>,
            environment: Map<String, String>,
        )
    }

    interface ProcessController {
        fun reset()
    }

    /**
     * 上次执行模式
     */
    var lastExecutionMode: CompileProjectUseCase.ExecutionMode = CompileProjectUseCase.ExecutionMode.RUN
        private set

    /**
     * UI 事件流
     */
    sealed class UiEvent {
        data class ShowToast(val message: String, val type: ToastType) : UiEvent()
        data class OpenTerminal(val command: String, val workDir: String?, val backend: TerminalBackend = TerminalBackend.HOST) : UiEvent()
        data class OpenSdl(
            val libraryPath: String,
            val environment: Map<String, String> = emptyMap(),
        ) : UiEvent()
        data class RevealInProjectTree(val file: File, val selectTarget: Boolean = false) : UiEvent()
    }

    enum class ToastType {
        SUCCESS,
        ERROR,
        INFO
    }

    enum class ExecutionProcessState {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
    }

    // 生命周期暂时未到 STARTED 时缓存一次性事件，恢复后逐个消费。
    private val uiEventsChannel = Channel<UiEvent>(capacity = Channel.BUFFERED)
    val uiEvents = uiEventsChannel.receiveAsFlow()

    /**
     * 运行项目
     */
    suspend fun runProject() {
        startCompileAction(
            operation = CompileProjectUseCase.Operation.forRun(),
            actionLabel = Strings.action_run.strOr(context)
        )
    }

    /**
     * 强制重新构建后运行
     */
    suspend fun rebuildAndRunProject() {
        startCompileAction(
            operation = CompileProjectUseCase.Operation.rebuildRun(),
            actionLabel = Strings.action_rebuild_and_run.strOr(context),
            progressToast = Strings.toast_compiling.strOr(context),
        )
    }

    /**
     * 构建项目（仅编译，不运行）
     */
    suspend fun buildProject() {
        startCompileAction(
            operation = CompileProjectUseCase.Operation.forBuild(),
            actionLabel = Strings.cmd_project_build.strOr(context),
            progressToast = Strings.toast_compiling.strOr(context)
        )
    }

    /**
     * 调试项目
     */
    suspend fun debugProject() {
        startCompileAction(
            operation = CompileProjectUseCase.Operation.forDebug(),
            actionLabel = Strings.content_desc_debug.strOr(context),
            progressToast = Strings.toast_preparing_debug.strOr(context)
        )
    }

    /**
     * 在终端中运行
     */
    suspend fun runInTerminal() {
        startCompileAction(
            operation = CompileProjectUseCase.Operation.forTerminal(),
            actionLabel = Strings.menu_terminal.strOr(context),
            progressToast = Strings.toast_compiling.strOr(context)
        )
    }

    suspend fun reconfigureCMake() {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_RECONFIGURE)
    }

    suspend fun clearCMakeBuildDirectory() {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_CLEAR_BUILD_DIRECTORY)
    }

    suspend fun clearAndReconfigureCMake() {
        runCMakeMaintenance(CompileProjectUseCase.Action.CMAKE_CLEAR_AND_RECONFIGURE)
    }

    fun openCMakeArtifactsDirectory() {
        val project = projectContext.getCurrentProject() ?: return
        val artifactsDir = ProjectDirStructure.getArtifactsDir(project.rootPath)
        if (!artifactsDir.isDirectory) {
            emitToast(Strings.toast_cmake_artifacts_dir_missing.strOr(context), ToastType.INFO)
            return
        }

        uiEventsChannel.trySend(UiEvent.RevealInProjectTree(artifactsDir, selectTarget = false))
        emitToast(Strings.toast_cmake_artifacts_dir_revealed.strOr(context), ToastType.SUCCESS)
    }

    /**
     * 处理编译成功事件
     */
    suspend fun handleCompileSuccess(event: CompileEvent.Success) {
        val report = event.report
        uiBridge.setCompiling(false)
        when (report.action) {
            CompileProjectUseCase.Action.DEBUG -> handleDebugSuccess(report)
            CompileProjectUseCase.Action.RUN,
            CompileProjectUseCase.Action.REBUILD_RUN,
            CompileProjectUseCase.Action.TERMINAL -> handleRunLikeSuccess(report)
            CompileProjectUseCase.Action.BUILD -> handleBuildSuccess(report)
            else -> handleMaintenanceSuccess(report)
        }
        finishCompileUi()
    }

    /**
     * 处理编译失败事件
     */
    suspend fun handleCompileError(event: CompileEvent.Error) {
        uiBridge.setCompiling(false)
        val message = if (event.action.isCMakeMaintenance()) {
            val uiText = event.action.resolveUiText(context)
            event.message.ifBlank { uiText.failureMessage }
        } else {
            event.message
        }
        emitToast(message, ToastType.ERROR)
        showBuildLog()
        finishCompileUi()
    }

    /**
     * 处理进程状态变化
     */
    suspend fun handleProcessStateChanged(processState: ExecutionProcessState) {
        when (processState) {
            ExecutionProcessState.IDLE -> {
                uiBridge.setCompiling(false)
            }
            ExecutionProcessState.RUNNING -> {
                uiBridge.setCompiling(true)
                // RUN 模式下：程序开始运行时切到"构建日志"页签
                if (lastExecutionMode == CompileProjectUseCase.ExecutionMode.RUN) {
                    uiBridge.showBuildLog()
                    uiBridge.expandBottomPanel()
                }
            }
            ExecutionProcessState.STOPPED -> {
                uiBridge.setCompiling(false)
            }
            ExecutionProcessState.STARTING,
            ExecutionProcessState.STOPPING -> {
                uiBridge.setCompiling(true)
            }
        }
    }

    /**
     * 准备编译（清空日志、切换页签、展开面板）
     */
    private suspend fun prepareForCompile() {
        uiBridge.clearBuildLogs()
        showBuildLog()
        uiBridge.expandBottomPanel()
    }

    private suspend fun startCompileAction(
        operation: CompileProjectUseCase.Operation,
        actionLabel: String,
        progressToast: String? = null,
    ) {
        BuildDiagnosticsLog.i {
            "ui compile action requested action=${operation.action} mode=${operation.mode} label=$actionLabel"
        }
        if (!ensureAllEditorsSaved(actionLabel)) {
            BuildDiagnosticsLog.w {
                "ui compile action cancelled before dispatch: action=${operation.action} mode=${operation.mode}"
            }
            return
        }

        processController.reset()
        lastExecutionMode = operation.mode
        prepareForCompile()
        uiBridge.setCompiling(true)
        progressToast?.let { emitToast(it, ToastType.INFO) }
        BuildDiagnosticsLog.i {
            "ui compile action dispatching action=${operation.action} mode=${operation.mode}"
        }
        commandRunner.compile(operation)
    }

    private suspend fun runCMakeMaintenance(action: CompileProjectUseCase.Action) {
        val uiText = action.resolveUiText(context)
        BuildDiagnosticsLog.i { "ui cmake maintenance requested action=$action label=${uiText.menuLabel}" }
        if (!ensureAllEditorsSaved(uiText.menuLabel)) {
            BuildDiagnosticsLog.w { "ui cmake maintenance cancelled before dispatch: action=$action" }
            return
        }

        processController.reset()
        prepareForCompile()
        uiBridge.setCompiling(true)
        emitToast(uiText.progressMessage, ToastType.INFO)

        when (action) {
            CompileProjectUseCase.Action.CMAKE_RECONFIGURE -> commandRunner.reconfigureCMake()
            CompileProjectUseCase.Action.CMAKE_CLEAR_BUILD_DIRECTORY -> {
                commandRunner.clearCMakeBuildDirectory()
            }
            CompileProjectUseCase.Action.CMAKE_CLEAR_AND_RECONFIGURE -> {
                commandRunner.clearAndReconfigureCMake()
            }
            else -> return
        }
    }

    /**
     * 确保所有编辑器已保存
     */
    private suspend fun ensureAllEditorsSaved(actionName: String): Boolean {
        val openTabs = editorManager.getOpenTabs()
        BuildDiagnosticsLog.i {
            "saveAll start action=$actionName openTabs=${openTabs.size} " +
                "activeTab=${editorManager.getActiveTabId().orEmpty()} activeFile=${editorManager.getActiveFile()?.absolutePath.orEmpty()}"
        }
        val startMs = System.currentTimeMillis()
        val results = editorManager.saveAll(SaveReason.MANUAL)
        val elapsedMs = System.currentTimeMillis() - startMs
        if (results.isEmpty()) {
            BuildDiagnosticsLog.i { "saveAll done action=$actionName elapsedMs=$elapsedMs result=no-dirty-tabs" }
            return true
        }
        val successes = results.filterIsInstance<SaveResult.Success>()
        val failures = results.filterIsInstance<SaveResult.Failure>()
        val noOps = results.count { it is SaveResult.NoOp }
        BuildDiagnosticsLog.i {
            "saveAll done action=$actionName elapsedMs=$elapsedMs total=${results.size} " +
                "success=${successes.size} failure=${failures.size} noOp=$noOps " +
                "savedTargets=${successes.joinToString(limit = 8) { it.target?.file?.absolutePath ?: "<unknown>" }}"
        }
        if (failures.isNotEmpty()) {
            BuildDiagnosticsLog.w {
                "saveAll failed action=$actionName firstFailure=${failures.first().message} failureCount=${failures.size}"
            }
            emitToast(
                Strings.toast_save_failed_cancelled.strOr(context, actionName, failures.first().message),
                ToastType.ERROR
            )
            return false
        }
        emitToast(Strings.toast_auto_saved.strOr(context, results.size), ToastType.SUCCESS)
        return true
    }

    private fun emitToast(message: String, type: ToastType) {
        uiEventsChannel.trySend(UiEvent.ShowToast(message, type))
    }

    private fun handleDebugSuccess(report: CompileProjectUseCase.Report) {
        val launch = report.launch as? CompileProjectUseCase.LaunchSpec.Debug
        if (launch?.programPath.isNullOrBlank()) {
            emitLaunchUnavailableToast(
                artifact = report.artifact,
                fallbackMessage = Strings.toast_debug_start_failed.strOr(context)
            )
            showBuildLog()
            return
        }

        emitToast(Strings.toast_compile_done_starting_debug.strOr(context), ToastType.SUCCESS)
        uiBridge.startDebugSession(
            programPath = launch.programPath!!,
            workingDirectory = launch.workingDirectory,
            arguments = launch.arguments,
            environment = launch.environment,
        )
        showBuildLog()
    }

    private fun handleRunLikeSuccess(report: CompileProjectUseCase.Report) {
        when (val launch = report.launch) {
            is CompileProjectUseCase.LaunchSpec.Sdl -> handleSdlLaunchSuccess(launch)
            is CompileProjectUseCase.LaunchSpec.PluginInstalled -> handlePluginInstallSuccess(launch)
            is CompileProjectUseCase.LaunchSpec.Terminal -> {
                handleTerminalLaunchSuccess(launch, report.artifact)
            }
            CompileProjectUseCase.LaunchSpec.None,
            is CompileProjectUseCase.LaunchSpec.Debug -> {
                emitLaunchUnavailableToast(
                    artifact = report.artifact,
                    fallbackMessage = Strings.toast_terminal_run_failed.strOr(context)
                )
            }
        }
    }

    private fun handleSdlLaunchSuccess(launch: CompileProjectUseCase.LaunchSpec.Sdl) {
        emitToast(Strings.toast_compile_done_opening_sdl.strOr(context), ToastType.SUCCESS)
        uiEventsChannel.trySend(
            UiEvent.OpenSdl(
                libraryPath = launch.libraryPath,
                environment = launch.environment,
            )
        )
    }

    private fun handlePluginInstallSuccess(launch: CompileProjectUseCase.LaunchSpec.PluginInstalled) {
        emitToast(
            Strings.toast_plugin_project_installed.strOr(context, launch.pluginName),
            ToastType.SUCCESS,
        )
        showBuildLog()
    }

    private fun handleTerminalLaunchSuccess(
        launch: CompileProjectUseCase.LaunchSpec.Terminal,
        artifact: CompileProjectUseCase.BuildArtifact?
    ) {
        when {
            launch.runnablePath.isNullOrBlank() -> {
                emitLaunchUnavailableToast(
                    artifact = artifact,
                    fallbackMessage = Strings.toast_terminal_run_failed.strOr(context)
                )
            }
            launch.command.isBlank() -> {
                emitToast(Strings.toast_terminal_run_failed.strOr(context), ToastType.ERROR)
            }
            else -> {
                emitToast(Strings.toast_compile_done_opening_terminal.strOr(context), ToastType.SUCCESS)
                uiEventsChannel.trySend(
                    UiEvent.OpenTerminal(
                        launch.command,
                        launch.workingDirectory
                    )
                )
            }
        }
    }

    private fun handleBuildSuccess(report: CompileProjectUseCase.Report) {
        val artifact = report.artifact
        val artifactToast = artifact?.let(::buildArtifactToast)
        when {
            artifactToast != null -> emitToast(artifactToast, ToastType.SUCCESS)
            !artifact?.exportedPath.isNullOrBlank() -> {
                emitToast(Strings.toast_compile_done_artifact_exported.strOr(context), ToastType.SUCCESS)
            }
            else -> emitToast(Strings.toast_compile_done.strOr(context), ToastType.SUCCESS)
        }
    }

    private fun handleMaintenanceSuccess(report: CompileProjectUseCase.Report) {
        val uiText = report.action.resolveUiText(context)
        emitToast(report.summary.ifBlank { uiText.successMessage }, ToastType.SUCCESS)
        showBuildLog()
    }

    private fun emitLaunchUnavailableToast(
        artifact: CompileProjectUseCase.BuildArtifact?,
        fallbackMessage: String
    ) {
        if (artifact != null) {
            emitToast(nonRunnableArtifactToast(artifact), ToastType.INFO)
        } else {
            emitToast(fallbackMessage, ToastType.ERROR)
        }
    }

    private fun showBuildLog() {
        uiBridge.showBuildLog()
    }

    private suspend fun finishCompileUi() {
        uiBridge.expandBottomPanel()
    }

    private fun nonRunnableArtifactToast(artifact: CompileProjectUseCase.BuildArtifact): String {
        val artifactLabel = artifact.kind.nonRunnableLabel()
            ?: return Strings.toast_compile_done_no_runnable_output.strOr(context)
        return if (!artifact.exportedPath.isNullOrBlank()) {
            Strings.toast_compile_done_non_runnable_artifact_exported.strOr(context, artifactLabel)
        } else {
            Strings.toast_compile_done_non_runnable_artifact.strOr(context, artifactLabel)
        }
    }

    private fun buildArtifactToast(artifact: CompileProjectUseCase.BuildArtifact): String? {
        val artifactLabel = artifact.kind.buildArtifactLabel() ?: return null
        return if (!artifact.exportedPath.isNullOrBlank()) {
            Strings.toast_compile_done_artifact_kind_exported.strOr(context, artifactLabel)
        } else {
            Strings.toast_compile_done_artifact_kind.strOr(context, artifactLabel)
        }
    }

    private fun CompileProjectUseCase.BuildArtifactKind.nonRunnableLabel(): String? = when (this) {
        CompileProjectUseCase.BuildArtifactKind.SHARED_LIBRARY,
        CompileProjectUseCase.BuildArtifactKind.STATIC_LIBRARY -> buildArtifactLabel()
        else -> null
    }

    private fun CompileProjectUseCase.BuildArtifactKind.buildArtifactLabel(): String? = when (this) {
        CompileProjectUseCase.BuildArtifactKind.EXECUTABLE ->
            Strings.compile_artifact_kind_executable.strOr(context)
        CompileProjectUseCase.BuildArtifactKind.SHARED_LIBRARY ->
            Strings.compile_artifact_kind_shared_library.strOr(context)
        CompileProjectUseCase.BuildArtifactKind.STATIC_LIBRARY ->
            Strings.compile_artifact_kind_static_library.strOr(context)
        CompileProjectUseCase.BuildArtifactKind.PLUGIN_PACKAGE ->
            Strings.compile_artifact_kind_plugin_package.strOr(context)
        CompileProjectUseCase.BuildArtifactKind.UNKNOWN -> null
    }
}

class CompilerViewModelCommandRunner(
    private val compilerViewModel: CompilerViewModel
) : CompileActionsHelper.CommandRunner {
    override fun compile(operation: CompileProjectUseCase.Operation) {
        compilerViewModel.compile(operation)
    }

    override fun reconfigureCMake() {
        compilerViewModel.reconfigureCMake()
    }

    override fun clearCMakeBuildDirectory() {
        compilerViewModel.clearCMakeBuildDirectory()
    }

    override fun clearAndReconfigureCMake() {
        compilerViewModel.clearAndReconfigureCMake()
    }
}

class ViewModelCompileUiBridge(
    private val mainViewModel: MainViewModel,
    private val bottomPanelViewModel: BottomPanelViewModel,
    private val debugViewModel: DebugViewModel,
    private val bottomPanelController: BottomPanelController,
) : CompileActionsHelper.UiBridge {
    override fun setCompiling(compiling: Boolean) {
        mainViewModel.setCompiling(compiling)
    }

    override fun clearBuildLogs() {
        bottomPanelViewModel.clearBuildLogs()
    }

    override fun showBuildLog() {
        bottomPanelViewModel.setSelectedTab(com.wuxianggujun.tinaide.ui.compose.components.BottomPanelTab.BUILD_LOG)
    }

    override suspend fun expandBottomPanel() {
        bottomPanelController.snapToDefault()
    }

    override fun startDebugSession(
        programPath: String,
        workingDirectory: String?,
        arguments: List<String>,
        environment: Map<String, String>,
    ) {
        debugViewModel.startDebugSession(programPath, workingDirectory, arguments, environment)
    }
}

class ProcessManagerCompileProcessController(
    private val processManager: com.wuxianggujun.tinaide.core.compile.ProcessManager
) : CompileActionsHelper.ProcessController {
    override fun reset() {
        processManager.reset()
    }
}
