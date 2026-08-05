package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.action.BuildIntent
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildReport
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.launcher.LaunchDescriptor
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildContextFactory
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildOrchestrator
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.packages.BundledPackagesReadiness
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.editor.IEditorTabProvider
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 编译项目用例。
 *
 * 作为 BuildOrchestrator 的适配 Facade:
 * - 对外:保留 execute / executeCMakeMaintenance / getAvailableTargets 等 public API
 * - 对内:构建 BuildContext + CompileRequest,委托 BuildOrchestrator 执行
 * - 结果:把 BuildReport + LaunchDescriptor 映射回 Result / LaunchSpec
 *
 * 本类只保留调用编排能力:
 * - `log()` 继续把关键步骤写到 OutputManager(UI 的构建日志面板)
 * - 订阅 [SharedFlowBuildEventEmitter].events 把 CompileProgress 写到 log
 * - getCurrentEditingFile / getSourceFile 用于 SingleFile 源文件选择
 */
class CompileProjectUseCase(
    private val appContext: Context,
    private val projectContext: IProjectContext,
    private val outputManager: IOutputManager,
    private val editorManagerProvider: () -> IEditorTabProvider? = { null },
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
    private val orchestratorProvider: () -> BuildOrchestrator,
    private val strategyRegistry: BuildStrategyRegistry,
    private val buildContextFactory: BuildContextFactory,
    private val terminalCommandBuilder: TerminalCommandBuilder,
    private val eventBus: SharedFlowBuildEventEmitter,
    private val pluginProjectActions: PluginProjectActions? = null,
) {
    companion object {
        private const val TAG = "CompileProjectUseCase"
    }

    // ---------- 嵌套类型(保持外部 API 稳定) ----------

    data class CompileProgress(
        val current: Int,
        val total: Int,
        val fileName: String,
    )

    enum class ExecutionMode {
        BUILD,
        RUN,
        DEBUG,
        TERMINAL
    }

    /**
     * UI/调用层发起的一次编译操作。
     *
     * 把「展示/兼容用的旧 ExecutionMode」「对外可观察的 Action」
     * 与「真正送进 Orchestrator 的 CompileRequest」拆开，避免继续堆布尔参数。
     */
    class Operation private constructor(
        val mode: ExecutionMode,
        val action: Action,
        private val requestFactory: (OutputMode) -> CompileRequest,
    ) {
        fun resolveRequest(outputMode: OutputMode): CompileRequest = requestFactory(outputMode)

        companion object {
            fun forBuild(): Operation = Operation(ExecutionMode.BUILD, Action.BUILD) { CompileRequest.buildOnly() }

            fun forRun(): Operation = Operation(ExecutionMode.RUN, Action.RUN) { outputMode -> CompileRequest.run(outputMode) }

            fun forDebug(): Operation = Operation(ExecutionMode.DEBUG, Action.DEBUG) { CompileRequest.debug() }

            fun forTerminal(): Operation = Operation(ExecutionMode.TERMINAL, Action.TERMINAL) { CompileRequest.terminal() }

            fun rebuildRun(): Operation = Operation(
                mode = ExecutionMode.RUN,
                action = Action.REBUILD_RUN,
            ) { outputMode ->
                CompileRequest.forceRun(outputMode)
            }
        }
    }

    enum class BuildArtifactKind {
        EXECUTABLE,
        SHARED_LIBRARY,
        STATIC_LIBRARY,
        PLUGIN_PACKAGE,
        UNKNOWN;

        fun displayName(context: Context): String = when (this) {
            EXECUTABLE -> Strings.compile_artifact_kind_executable.strOr(context)
            SHARED_LIBRARY -> Strings.compile_artifact_kind_shared_library.strOr(context)
            STATIC_LIBRARY -> Strings.compile_artifact_kind_static_library.strOr(context)
            PLUGIN_PACKAGE -> Strings.compile_artifact_kind_plugin_package.strOr(context)
            UNKNOWN -> Strings.compile_artifact_kind_unknown.strOr(context)
        }
    }

    enum class Action {
        BUILD,
        RUN,
        REBUILD_RUN,
        DEBUG,
        TERMINAL,
        CMAKE_RECONFIGURE,
        CMAKE_CLEAR_BUILD_DIRECTORY,
        CMAKE_CLEAR_AND_RECONFIGURE;

        fun isCMakeMaintenance(): Boolean = when (this) {
            CMAKE_RECONFIGURE, CMAKE_CLEAR_BUILD_DIRECTORY, CMAKE_CLEAR_AND_RECONFIGURE -> true
            else -> false
        }
    }

    data class BuildArtifact(
        val path: String,
        val exportedPath: String? = null,
        val kind: BuildArtifactKind = BuildArtifactKind.UNKNOWN,
    )

    sealed class LaunchSpec {
        data object None : LaunchSpec()
        data class Terminal(
            val command: String,
            val runnablePath: String?,
            val workingDirectory: String,
        ) : LaunchSpec()
        data class Debug(
            val programPath: String?,
            val workingDirectory: String,
            val arguments: List<String> = emptyList(),
            val environment: Map<String, String> = emptyMap(),
        ) : LaunchSpec()
        data class Sdl(
            val libraryPath: String,
            val environment: Map<String, String> = emptyMap(),
            val preferredSdlMajor: Int? = null,
            val orientation: SdlOrientation = SdlOrientation.AUTO,
            val enableFloatingLog: Boolean = false,
        ) : LaunchSpec()
        data class NativeActivity(
            val libraryPath: String,
            val environment: Map<String, String> = emptyMap(),
            val enableFloatingLog: Boolean = false,
        ) : LaunchSpec()
        data class PluginInstalled(
            val pluginId: String,
            val pluginName: String,
            val pluginVersion: String,
            val packagePath: String,
        ) : LaunchSpec()
    }

    data class Report(
        val action: Action,
        val summary: String,
        val artifact: BuildArtifact? = null,
        val launch: LaunchSpec = LaunchSpec.None,
    )

    sealed class Result {
        data class Success(val report: Report) : Result()
        data class Error(
            val action: Action,
            val userMessage: String,
            val throwable: Throwable?,
        ) : Result()
    }

    // ---------- public API ----------

    suspend fun execute(
        operation: Operation,
        onProgress: (CompileProgress) -> Unit,
        runConfig: RunConfiguration? = null,
        targetName: String? = null,
        launchEnvironment: Map<String, String> = emptyMap(),
    ): Result = withContext(Dispatchers.IO) {
        val mode = operation.mode
        val action = operation.action
        log(Strings.compile_start.strOr(appContext))

        val project = projectContext.getCurrentProject() ?: run {
            val msg = Strings.compile_error_no_project.strOr(appContext)
            log(msg)
            return@withContext Result.Error(action, msg, null)
        }

        val projectRoot = File(project.rootPath)
        val buildDir = File(project.buildDirPath)
        log(Strings.compile_project.strOr(appContext, project.name))
        log(Strings.compile_path.strOr(appContext, projectRoot.absolutePath))
        Timber.tag(TAG).i(
            "Compile execute start: action=%s mode=%s project=%s buildDir=%s",
            action,
            mode,
            projectRoot.absolutePath,
            buildDir.absolutePath,
        )
        BuildDiagnosticsLog.i {
            "compile execute start action=$action mode=$mode project=${projectRoot.absolutePath} buildDir=${buildDir.absolutePath}"
        }

        val currentFile = getCurrentEditingFile()?.also {
            log(Strings.compile_current_file.strOr(appContext, it.name))
        }

        val buildSystem = BuildSystemDetector.detect(projectRoot)
        log(Strings.compile_build_system.strOr(appContext, buildSystem.toString()))

        if (buildSystem == BuildSystem.PLUGIN) {
            return@withContext executePluginProjectAction(
                action = action,
                projectRoot = projectRoot,
                buildDir = buildDir,
            )
        }

        awaitBundledPackagesIfNeeded(action)

        if (strategyRegistry.resolve(buildSystem) == null) {
            val errorMsg = when (buildSystem) {
                BuildSystem.UNKNOWN -> {
                    val files = projectRoot.listFiles()
                    when {
                        files == null -> Strings.compile_error_cannot_read_dir.strOr(appContext)
                        files.isEmpty() -> Strings.compile_error_empty_dir.strOr(appContext)
                        else -> Strings.compile_error_no_source_files.strOr(
                            appContext,
                            files.take(10).joinToString(", ") { it.name }
                        )
                    }
                }
                else -> Strings.compile_error_unsupported_build_system.strOr(appContext, buildSystem.toString())
            }
            log(errorMsg)
            return@withContext Result.Error(action, errorMsg, null)
        }

        val config = runConfig ?: getRunConfiguration()
        val runOutputMode = config.outputMode
        val preferSharedLibraryForRun = mode == ExecutionMode.RUN && runOutputMode.isSharedLibraryGraphical()
        val activeOutputMode = if (mode == ExecutionMode.RUN) runOutputMode else OutputMode.TERMINAL
        val request = operation.resolveRequest(activeOutputMode)
        BuildDiagnosticsLog.i {
            "run config resolved action=$action mode=$mode buildSystem=$buildSystem " +
                "runOutputMode=$runOutputMode activeOutputMode=$activeOutputMode " +
                "requestedTarget=${targetName.orEmpty()} configTarget=${config.targetName} " +
                "preferSharedLibraryForRun=$preferSharedLibraryForRun"
        }

        val buildVariablesCtx = BuildVariables.BuildContext(
            projectDir = projectRoot,
            projectName = project.name,
            currentFile = currentFile,
            sourceFile = null,
            buildDir = buildDir,
        )

        var effectiveTargetName = targetName ?: config.targetName.takeIf { it.isNotBlank() }
        var selectedSourceFile: File? = null
        if (buildSystem == BuildSystem.SINGLE_FILE && config.isSingleFileMode()) {
            val sourceFile = config.getSourceFile(projectRoot, currentFile)
            if (sourceFile != null) {
                log(Strings.compile_source_file_mode.strOr(appContext, config.sourceFileModeDisplayName()))
                log(Strings.compile_file.strOr(appContext, sourceFile.name))
                selectedSourceFile = sourceFile
                effectiveTargetName = sourceFile.nameWithoutExtension
            } else {
                logSingleFileResolutionWarning(config)
            }
        }

        val linuxEnvironmentAvailable = linuxEnvironmentProvider.get().isAvailable()
        val options = resolveBuildOptions(
            launch = request.launch,
            buildSystem = buildSystem,
            runConfig = config,
            projectRoot = projectRoot,
            linuxEnvironmentAvailable = linuxEnvironmentAvailable,
            buildForRun = mode == ExecutionMode.RUN || mode == ExecutionMode.TERMINAL,
            preferSharedLibraryForRun = preferSharedLibraryForRun,
            sourceFile = selectedSourceFile,
            onProgress = { msg -> log(msg) },
        )

        var cmakeTargets: List<TargetInfo>? = null
        if (buildSystem == BuildSystem.CMAKE) {
            val metadata = ProjectMetadataStore.read(projectRoot)
            val metadataDefaultTargetName = CMakeRunTargetResolver.defaultTargetName(metadata, config.outputMode)
            if (!metadataDefaultTargetName.isNullOrBlank()) {
                val targets = loadCMakeTargets(projectRoot, buildDir, buildSystem, options)
                cmakeTargets = targets
                BuildDiagnosticsLog.i {
                    "cmake target repair check requested=${effectiveTargetName.orEmpty()} " +
                        "metadataDefault=$metadataDefaultTargetName outputMode=${config.outputMode} " +
                        "targets=${targets.diagnosticsSummary()}"
                }
                val targetRepair = CMakeRunTargetResolver.repairMissingOrInvalidTarget(
                    requestedTargetName = effectiveTargetName,
                    outputMode = config.outputMode,
                    metadata = metadata,
                    targets = targets,
                )
                if (targetRepair.repaired) {
                    Timber.tag(TAG).w(
                        "CMake target is missing or invalid, using metadata default: requested=%s default=%s",
                        targetRepair.requestedTargetName ?: "<empty>",
                        targetRepair.targetName,
                    )
                    BuildDiagnosticsLog.w {
                        "cmake target repaired requested=${targetRepair.requestedTargetName.orEmpty()} " +
                            "effective=${targetRepair.targetName.orEmpty()} outputMode=${config.outputMode}"
                    }
                    effectiveTargetName = targetRepair.targetName
                    val requestedDisplay = targetRepair.requestedTargetName
                        ?: Strings.run_config_default_target.strOr(appContext)
                    log(
                        Strings.cmake_run_target_auto_repaired.strOr(
                            appContext,
                            targetRepair.targetName.orEmpty(),
                            requestedDisplay,
                        )
                    )
                }
            }
        }

        // Graphical shared-library modes must run a shared-library target; repair legacy executable selections.
        if (buildSystem == BuildSystem.CMAKE && preferSharedLibraryForRun) {
            val targets = cmakeTargets ?: loadCMakeTargets(projectRoot, buildDir, buildSystem, options)
            val selectedTarget = effectiveTargetName
                ?.let { name -> targets.firstOrNull { it.name == name } }
            val sharedTarget = targets.firstOrNull { it.type == TargetInfo.Type.SHARED_LIBRARY }
            BuildDiagnosticsLog.i {
                "cmake sdl target check requested=${effectiveTargetName.orEmpty()} " +
                    "selected=${selectedTarget?.diagnosticsSummary().orEmpty()} " +
                    "firstShared=${sharedTarget?.diagnosticsSummary().orEmpty()}"
            }
            if (sharedTarget == null) {
                val errorMsg = if (runOutputMode == OutputMode.NATIVE_ACTIVITY) {
                    Strings.native_activity_runtime_no_shared_library_target.strOr(appContext)
                } else {
                    Strings.sdl_runtime_no_shared_library_target.strOr(appContext)
                }
                BuildDiagnosticsLog.w {
                    "cmake graphical target check failed: mode=$runOutputMode no shared-library target"
                }
                log(errorMsg)
                return@withContext Result.Error(action, errorMsg, null)
            }
            if (selectedTarget == null || selectedTarget.type != TargetInfo.Type.SHARED_LIBRARY) {
                effectiveTargetName = sharedTarget.name
                BuildDiagnosticsLog.i {
                    "cmake graphical target auto-selected shared library target=" +
                        "${sharedTarget.name} mode=$runOutputMode"
                }
                val autoSelectedMessage = if (runOutputMode == OutputMode.NATIVE_ACTIVITY) {
                    Strings.native_activity_runtime_auto_selected_shared_library_target
                        .strOr(appContext, sharedTarget.name)
                } else {
                    Strings.sdl_runtime_auto_selected_shared_library_target
                        .strOr(appContext, sharedTarget.name)
                }
                log(autoSelectedMessage)
            }
        }

        val ctx = buildContextFactory.create(
            appContext = appContext,
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = buildSystem,
            options = options,
            target = effectiveTargetName,
        )
        BuildDiagnosticsLog.i {
            "build context prepared action=$action mode=$mode buildSystem=$buildSystem " +
                "target=${effectiveTargetName.orEmpty()} requestBuild=${request.build::class.simpleName} " +
                "requestLaunch=${request.launch::class.simpleName} " +
                "toolchainId=${options.toolchainId.orEmpty()} sysrootProfile=${options.sysrootProfileId.orEmpty()} " +
                "sysrootApi=${options.sysrootApiLevel} runMode=${options.resolvedRunMode} " +
                "cmakeBuildType=${options.cmakeBuildType.cmakeValue} cmakeGenerator=${options.cmakeGenerator.cmakeValue} " +
                "buildForRun=${options.buildForRun} preferSharedLibraryForRun=${options.preferSharedLibraryForRun}"
        }
        val orchestrator = orchestratorProvider()

        return@withContext runOrchestratorAndMap(
            orchestrator = orchestrator,
            request = request,
            ctx = ctx,
            mode = mode,
            action = action,
            projectRoot = projectRoot,
            buildContext = buildVariablesCtx.copy(sourceFile = selectedSourceFile),
            config = config,
            launchEnvironment = launchEnvironment,
        )
    }

    suspend fun executeCMakeMaintenance(action: Action): Result = withContext(Dispatchers.IO) {
        require(action.isCMakeMaintenance()) { "Unsupported CMake maintenance action: $action" }

        val project = projectContext.getCurrentProject() ?: return@withContext Result.Error(
            action,
            Strings.toast_please_open_project.strOr(appContext),
            null,
        )
        val projectRoot = File(project.rootPath)
        val buildDir = File(project.buildDirPath)
        awaitBundledPackagesIfNeeded(action)

        val buildSystem = BuildSystemDetector.detect(projectRoot)
        if (buildSystem != BuildSystem.CMAKE) {
            val msg = Strings.compile_error_not_cmake_project.strOr(appContext)
            log(msg)
            return@withContext Result.Error(action, msg, null)
        }

        val linuxEnvironmentAvailable = linuxEnvironmentProvider.get().isAvailable()
        val options = resolveBuildOptions(
            launch = LaunchIntent.None,
            buildSystem = buildSystem,
            runConfig = getRunConfiguration(),
            projectRoot = projectRoot,
            linuxEnvironmentAvailable = linuxEnvironmentAvailable,
            onProgress = { msg -> log(msg) },
        )
        val ctx = buildContextFactory.create(
            appContext = appContext,
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = buildSystem,
            options = options,
            target = null,
        )

        val reconfigureAfterClean = action == Action.CMAKE_RECONFIGURE || action == Action.CMAKE_CLEAR_AND_RECONFIGURE
        val cleanRequest = CompileRequest(
            build = BuildIntent.Clean(reconfigure = action == Action.CMAKE_RECONFIGURE),
            launch = LaunchIntent.None,
        )
        val orchestrator = orchestratorProvider()

        val cleanReport = runWithProgressLogging {
            orchestrator.run(cleanRequest, ctx)
        }

        if (cleanReport is BuildReport.Invalid) {
            log(cleanReport.reason)
            return@withContext Result.Error(action, cleanReport.reason, null)
        }

        // CMAKE_CLEAR_AND_RECONFIGURE: 清完再 configure(通过触发一次 IfNeeded Build + None launch)
        if (reconfigureAfterClean && action == Action.CMAKE_CLEAR_AND_RECONFIGURE) {
            val configureRequest = CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None)
            val configureReport = runWithProgressLogging { orchestrator.run(configureRequest, ctx) }
            if (configureReport is BuildReport.BuildFailed) {
                log(configureReport.reason)
                return@withContext Result.Error(action, configureReport.reason, null)
            }
        }

        val summary = when (action) {
            Action.CMAKE_RECONFIGURE -> Strings.compile_cmake_reconfigure_finished.strOr(appContext)
            Action.CMAKE_CLEAR_BUILD_DIRECTORY -> Strings.compile_cmake_clear_build_dir_finished.strOr(appContext)
            Action.CMAKE_CLEAR_AND_RECONFIGURE -> Strings.compile_cmake_reconfigure_finished.strOr(appContext)
            else -> ""
        }
        Result.Success(Report(action = action, summary = summary))
    }

    private suspend fun awaitBundledPackagesIfNeeded(action: Action) {
        when (val readiness = BundledPackagesReadiness.awaitCurrentInstall()) {
            BundledPackagesReadiness.State.FAILED,
            BundledPackagesReadiness.State.TIMED_OUT -> Timber.tag(TAG).w(
                "Bundled package readiness=%s before action=%s; continuing with last stable installation",
                readiness,
                action,
            )
            else -> Unit
        }
    }

    suspend fun getAvailableTargets(): List<TargetInfo> = withContext(Dispatchers.IO) {
        val project = projectContext.getCurrentProject() ?: return@withContext emptyList()
        val projectRoot = File(project.rootPath)
        val buildDir = File(project.buildDirPath)
        val buildSystem = BuildSystemDetector.detect(projectRoot)
        val strategy = strategyRegistry.resolve(buildSystem) ?: return@withContext emptyList()

        val linuxEnvironmentAvailable = linuxEnvironmentProvider.get().isAvailable()
        val options = resolveBuildOptions(
            launch = LaunchIntent.None,
            buildSystem = buildSystem,
            runConfig = getRunConfiguration(),
            projectRoot = projectRoot,
            linuxEnvironmentAvailable = linuxEnvironmentAvailable,
        )
        val ctx = buildContextFactory.create(
            appContext = appContext,
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = buildSystem,
            options = options,
            target = null,
        )
        strategy.getTargets(ctx)
    }

    // ---------- 私有助手 ----------

    private suspend fun executePluginProjectAction(
        action: Action,
        projectRoot: File,
        buildDir: File,
    ): Result {
        val actions = pluginProjectActions ?: run {
            val msg = Strings.compile_plugin_project_actions_unavailable.strOr(appContext)
            log(msg)
            return Result.Error(action, msg, null)
        }

        val taskResult = when (action) {
            Action.BUILD -> {
                log(Strings.compile_plugin_project_validate_start.strOr(appContext))
                log(Strings.compile_plugin_project_package_start.strOr(appContext))
                actions.build(projectRoot, buildDir)
            }
            Action.RUN, Action.REBUILD_RUN, Action.TERMINAL -> {
                log(Strings.compile_plugin_project_validate_start.strOr(appContext))
                log(Strings.compile_plugin_project_package_start.strOr(appContext))
                log(Strings.compile_plugin_project_install_start.strOr(appContext))
                actions.install(projectRoot, buildDir)
            }
            Action.DEBUG -> {
                val msg = Strings.compile_plugin_project_debug_unsupported.strOr(appContext)
                log(msg)
                return Result.Error(action, msg, null)
            }
            else -> {
                val msg = Strings.compile_error_unsupported_build_system.strOr(
                    appContext,
                    BuildSystem.PLUGIN.toString()
                )
                log(msg)
                return Result.Error(action, msg, null)
            }
        }

        return taskResult.fold(
            onSuccess = { result -> pluginProjectSuccess(action, result) },
            onFailure = { throwable ->
                val msg = throwable.message ?: Strings.plugin_error_install_failed.strOr(appContext)
                log(msg)
                Result.Error(action, msg, throwable)
            },
        )
    }

    private fun pluginProjectSuccess(
        action: Action,
        result: PluginProjectActionResult,
    ): Result.Success {
        log(
            Strings.compile_plugin_project_diagnostics_summary.strOr(
                appContext,
                result.errorCount,
                result.warningCount,
            )
        )
        result.diagnostics.forEach { diagnostic ->
            val message = when (diagnostic.severity) {
                PluginProjectDiagnosticSeverity.ERROR ->
                    Strings.compile_plugin_project_diagnostic_error.strOr(appContext, diagnostic.message)
                PluginProjectDiagnosticSeverity.WARNING ->
                    Strings.compile_plugin_project_diagnostic_warning.strOr(appContext, diagnostic.message)
                PluginProjectDiagnosticSeverity.INFO ->
                    Strings.compile_plugin_project_diagnostic_info.strOr(appContext, diagnostic.message)
            }
            log(message)
            diagnostic.fixHint?.takeIf { it.isNotBlank() }?.let { hint ->
                log(Strings.compile_plugin_project_diagnostic_fix.strOr(appContext, hint))
            }
        }

        val artifact = BuildArtifact(
            path = result.packageFile.absolutePath,
            exportedPath = result.packageFile.absolutePath,
            kind = BuildArtifactKind.PLUGIN_PACKAGE,
        )
        return if (result.installed) {
            log(
                Strings.compile_plugin_project_install_complete.strOr(
                    appContext,
                    result.pluginName,
                    result.pluginVersion,
                )
            )
            Result.Success(
                Report(
                    action = action,
                    summary = Strings.compile_plugin_project_install_summary.strOr(
                        appContext,
                        result.pluginName,
                    ),
                    artifact = artifact,
                    launch = LaunchSpec.PluginInstalled(
                        pluginId = result.pluginId,
                        pluginName = result.pluginName,
                        pluginVersion = result.pluginVersion,
                        packagePath = result.packageFile.absolutePath,
                    ),
                )
            )
        } else {
            log(
                Strings.compile_plugin_project_package_complete.strOr(
                    appContext,
                    result.packageFile.absolutePath,
                )
            )
            Result.Success(
                Report(
                    action = action,
                    summary = Strings.compile_plugin_project_package_summary.strOr(appContext),
                    artifact = artifact,
                    launch = LaunchSpec.None,
                )
            )
        }
    }

    private suspend fun runOrchestratorAndMap(
        orchestrator: BuildOrchestrator,
        request: CompileRequest,
        ctx: com.wuxianggujun.tinaide.core.compile.strategy.BuildContext,
        mode: ExecutionMode,
        action: Action,
        projectRoot: File,
        buildContext: BuildVariables.BuildContext,
        config: RunConfiguration,
        launchEnvironment: Map<String, String>,
    ): Result {
        val report = runWithProgressLogging { orchestrator.run(request, ctx) }
        Timber.tag(TAG).i(
            "Compile use case received report: action=%s mode=%s report=%s",
            action,
            mode,
            report::class.simpleName,
        )

        return when (report) {
            is BuildReport.BuiltOnly -> {
                log(Strings.compile_complete.strOr(appContext))
                val artifactKind = mapKind(report.artifact.kind)
                Timber.tag(TAG).i(
                    "Compile built-only success: artifact=%s kind=%s",
                    report.artifact.absolutePath,
                    artifactKind,
                )
                BuildDiagnosticsLog.i {
                    "compile built-only success action=$action artifact=${report.artifact.absolutePath} " +
                        "kind=$artifactKind target=${report.artifact.id.targetName} " +
                        "contentHash=${report.artifact.contentHash.shortHash()} " +
                        "trackedHash=${report.artifact.fingerprint.trackedInputsHash.shortHash()}"
                }
                Result.Success(
                    Report(
                        action = action,
                        summary = buildResultSummary(artifactKind),
                        artifact = toBuildArtifact(projectRoot, report.artifact, artifactKind),
                        launch = LaunchSpec.None,
                    )
                )
            }
            is BuildReport.Success -> {
                log(successLog(mode))
                val launch = mapDescriptor(
                    descriptor = report.descriptor,
                    projectRoot = projectRoot,
                    buildContext = buildContext,
                    config = config,
                    launchEnvironment = launchEnvironment,
                )
                val artifactKind = mapKind(report.artifact.kind)
                Timber.tag(TAG).i(
                    "Compile success: artifact=%s kind=%s launch=%s",
                    report.artifact.absolutePath,
                    artifactKind,
                    launch::class.simpleName,
                )
                BuildDiagnosticsLog.i {
                    "compile success action=$action mode=$mode artifact=${report.artifact.absolutePath} " +
                        "kind=$artifactKind target=${report.artifact.id.targetName} launch=${launch::class.simpleName} " +
                        "contentHash=${report.artifact.contentHash.shortHash()} " +
                        "trackedHash=${report.artifact.fingerprint.trackedInputsHash.shortHash()}"
                }
                Result.Success(
                    Report(
                        action = action,
                        summary = successSummary(mode),
                        artifact = toBuildArtifact(projectRoot, report.artifact, artifactKind),
                        launch = launch,
                    )
                )
            }
            is BuildReport.Cleaned -> Result.Success(
                Report(
                    action = action,
                    summary = Strings.compile_result_build_complete.strOr(appContext),
                )
            )
            is BuildReport.BuildFailed -> {
                log(report.reason)
                Timber.tag(TAG).w(
                    "Compile failed before UI mapping: action=%s reason=%s",
                    action,
                    report.reason,
                )
                BuildDiagnosticsLog.w { "compile failed action=$action reason=${report.reason}" }
                Result.Error(action, report.reason, null)
            }
            is BuildReport.LaunchFailed -> {
                log(report.reason)
                Timber.tag(TAG).w(
                    "Compile launch failed before UI mapping: action=%s reason=%s artifact=%s",
                    action,
                    report.reason,
                    report.artifact.absolutePath,
                )
                BuildDiagnosticsLog.w {
                    "compile launch failed action=$action artifact=${report.artifact.absolutePath} " +
                        "target=${report.artifact.id.targetName} cached=${report.artifactWasCached} reason=${report.reason}"
                }
                Result.Error(action, report.reason, null)
            }
            is BuildReport.Invalid -> {
                log(report.reason)
                Timber.tag(TAG).w(
                    "Compile invalid before UI mapping: action=%s reason=%s",
                    action,
                    report.reason,
                )
                BuildDiagnosticsLog.w { "compile invalid action=$action reason=${report.reason}" }
                Result.Error(action, report.reason, null)
            }
        }
    }

    private suspend fun runWithProgressLogging(block: suspend () -> BuildReport): BuildReport = coroutineScope {
        val collectorJob: Job = eventBus.events
            .onEach { event ->
                when (event) {
                    is BuildEvent.Planning.Started -> log(Strings.compile_checking_artifact.strOr(appContext))
                    is BuildEvent.Build.Skipped -> log(Strings.compile_reusing_artifact.strOr(appContext))
                    is BuildEvent.Planning.Decided -> {
                        if (event.summary.startsWith("build:")) {
                            log(Strings.compile_rebuilding.strOr(appContext))
                            log(Strings.compile_rebuild_reason.strOr(appContext, event.summary.removePrefix("build:").trim()))
                        }
                    }
                    is BuildEvent.AutoFallback -> log(
                        Strings.compile_autofallback_triggered.strOr(appContext, event.firstFailure.reason)
                    )
                    is BuildEvent.Build.CompileProgress -> log(event.message)
                    is BuildEvent.Build.ConfigureFailed -> log(event.reason)
                    is BuildEvent.Build.CompileFailed -> log(event.reason)
                    else -> Unit
                }
            }
            .launchIn(this)
        try {
            block()
        } finally {
            collectorJob.cancel()
        }
    }

    private fun mapDescriptor(
        descriptor: LaunchDescriptor,
        projectRoot: File,
        buildContext: BuildVariables.BuildContext,
        config: RunConfiguration,
        launchEnvironment: Map<String, String>,
    ): LaunchSpec {
        val nativeRuntimeIdentity = NativeRuntimeIdentity(
            sysrootProfileId = descriptor.artifact.fingerprint.sysrootProfileId,
            sysrootApiLevel = descriptor.artifact.fingerprint.sysrootApiLevel,
        )
        val resolvedLaunchEnvironment = resolveLaunchEnvironment(
            projectRoot = projectRoot,
            launchEnvironment = launchEnvironment,
            nativeRuntimeIdentity = nativeRuntimeIdentity,
        )
        return when (descriptor) {
            is LaunchDescriptor.Sdl -> LaunchSpec.Sdl(
                libraryPath = descriptor.libraryPath,
                environment = resolvedLaunchEnvironment,
                preferredSdlMajor = config.sdlVersion?.major,
                orientation = config.sdlOrientation,
                enableFloatingLog = config.enableFloatingLog,
            )
            is LaunchDescriptor.NativeActivity -> LaunchSpec.NativeActivity(
                libraryPath = descriptor.libraryPath,
                environment = resolvedLaunchEnvironment,
                enableFloatingLog = config.enableFloatingLog,
            )
            is LaunchDescriptor.Debug -> LaunchSpec.Debug(
                programPath = descriptor.programPath,
                workingDirectory = descriptor.workingDir,
                arguments = config.getArgsList(buildContext),
                environment = resolvedLaunchEnvironment,
            )
            is LaunchDescriptor.Terminal -> {
                val workingDir = config.getAbsoluteWorkDir(projectRoot.absolutePath, buildContext)
                    .ifBlank { descriptor.workingDir.absolutePath }
                val args = config.getArgsList(buildContext).ifEmpty { descriptor.args }
                val command = terminalCommandBuilder.build(
                    workingDir = workingDir,
                    outputPath = descriptor.runnablePath,
                    args = args,
                    projectRoot = projectRoot,
                    extraEnvironment = launchEnvironment,
                    nativeRuntimeIdentity = nativeRuntimeIdentity,
                    showLinkerWarnings = config.showLinkerWarnings,
                )
                LaunchSpec.Terminal(
                    command = command,
                    runnablePath = descriptor.runnablePath,
                    workingDirectory = workingDir,
                )
            }
        }
    }

    private fun mapKind(kind: com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind): BuildArtifactKind = when (kind) {
        com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind.EXECUTABLE -> BuildArtifactKind.EXECUTABLE
        com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind.SHARED_LIBRARY -> BuildArtifactKind.SHARED_LIBRARY
        com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind.STATIC_LIBRARY -> BuildArtifactKind.STATIC_LIBRARY
        com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind.OBJECT -> BuildArtifactKind.UNKNOWN
        com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind.UNKNOWN -> BuildArtifactKind.UNKNOWN
    }

    private fun toBuildArtifact(
        projectRoot: File,
        artifact: com.wuxianggujun.tinaide.core.compile.artifact.Artifact,
        kind: BuildArtifactKind,
    ): BuildArtifact {
        val presented = PresentedBuildArtifactResolver.resolve(projectRoot, artifact, kind)
        Timber.tag(TAG).i(
            "Presented artifact resolved: source=%s exported=%s kind=%s",
            presented.path,
            presented.exportedPath ?: "<none>",
            presented.kind,
        )
        return presented
    }

    private fun successLog(mode: ExecutionMode): String = when (mode) {
        ExecutionMode.BUILD -> Strings.compile_complete.strOr(appContext)
        ExecutionMode.RUN -> Strings.compile_complete.strOr(appContext)
        ExecutionMode.DEBUG -> Strings.compile_debug_ready.strOr(appContext)
        ExecutionMode.TERMINAL -> Strings.compile_terminal_ready.strOr(appContext)
    }

    private fun successSummary(mode: ExecutionMode): String = when (mode) {
        ExecutionMode.BUILD -> Strings.compile_result_build_complete.strOr(appContext)
        ExecutionMode.RUN -> Strings.compile_result_build_complete.strOr(appContext)
        ExecutionMode.DEBUG -> Strings.compile_result_debug_ready.strOr(appContext)
        ExecutionMode.TERMINAL -> Strings.compile_result_terminal_ready.strOr(appContext)
    }

    private fun buildResultSummary(kind: BuildArtifactKind): String = when (kind) {
        BuildArtifactKind.EXECUTABLE -> Strings.compile_result_build_complete.strOr(appContext)
        BuildArtifactKind.SHARED_LIBRARY -> Strings.compile_result_build_complete.strOr(appContext)
        BuildArtifactKind.STATIC_LIBRARY -> Strings.compile_result_build_complete.strOr(appContext)
        BuildArtifactKind.PLUGIN_PACKAGE -> Strings.compile_plugin_project_package_summary.strOr(appContext)
        BuildArtifactKind.UNKNOWN -> Strings.compile_result_build_complete.strOr(appContext)
    }

    private fun logSingleFileResolutionWarning(config: RunConfiguration) {
        val currentFile = getCurrentEditingFile()
        when (config.sourceFileMode) {
            SourceFileMode.CURRENT_FILE -> {
                if (currentFile == null) {
                    log(Strings.compile_warning_no_file_open.strOr(appContext))
                } else if (!RunConfiguration.isSourceFile(currentFile)) {
                    log(Strings.compile_warning_not_source_file.strOr(appContext))
                }
            }
            SourceFileMode.SPECIFIED_FILE -> log(Strings.compile_warning_specified_file_invalid.strOr(appContext))
            else -> Unit
        }
    }

    private fun List<TargetInfo>.diagnosticsSummary(limit: Int = 12): String =
        if (isEmpty()) {
            "<none>"
        } else {
            take(limit).joinToString(separator = ";") { it.diagnosticsSummary() } +
                if (size > limit) ";...(+${size - limit})" else ""
        }

    private fun TargetInfo.diagnosticsSummary(): String =
        "$name:$type:aux=${name.isAuxiliaryTargetName()}:sources=${sources.size}:deps=${
            dependencies.joinToString(separator = "|", limit = 4)
        }"

    private fun String.isAuxiliaryTargetName(): Boolean {
        val normalized = trim().lowercase()
        return normalized == "test" ||
            normalized == "tests" ||
            normalized.endsWith("_test") ||
            normalized.endsWith("-test") ||
            normalized.endsWith("_tests") ||
            normalized.endsWith("-tests")
    }

    private fun String.shortHash(): String = take(12)

    private fun getCurrentEditingFile(): File? = try {
        editorManagerProvider()?.getActiveFile()
    } catch (e: Exception) {
        Timber.tag(TAG).w(Strings.compile_get_current_file_failed.strOr(appContext, e.message ?: ""))
        null
    }

    private fun getRunConfiguration(): RunConfiguration {
        val project = projectContext.getCurrentProject() ?: return RunConfiguration()
        return RunConfigurationManager.load(project.rootPath).selectedConfig
    }

    private fun resolveLaunchEnvironment(
        projectRoot: File,
        launchEnvironment: Map<String, String>,
        nativeRuntimeIdentity: NativeRuntimeIdentity,
    ): Map<String, String> {
        val normalized = LaunchEnvironment.sanitized(launchEnvironment)

        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectRoot)
        val sysrootRuntimeDirs = NativeRuntimeLibraryPaths.sysrootRuntimeDirs(
            context = appContext,
            sysrootProfileId = nativeRuntimeIdentity.sysrootProfileId,
        )
        return LaunchEnvironment.withPrependedPath(
            environment = normalized,
            variableName = "LD_LIBRARY_PATH",
            paths = (sysrootRuntimeDirs + packagePaths.runtimeLibDirs).map { it.absolutePath },
        )
    }

    private suspend fun loadCMakeTargets(
        projectRoot: File,
        buildDir: File,
        buildSystem: BuildSystem,
        options: BuildOptions,
    ): List<TargetInfo> {
        val ctxForTargetsQuery = buildContextFactory.create(
            appContext = appContext,
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = buildSystem,
            options = options,
            target = null,
        )
        return strategyRegistry.resolve(buildSystem)?.getTargets(ctxForTargetsQuery).orEmpty()
    }

    private fun resolveBuildOptions(
        launch: LaunchIntent,
        buildSystem: BuildSystem,
        runConfig: RunConfiguration,
        projectRoot: File,
        linuxEnvironmentAvailable: Boolean,
        buildForRun: Boolean = false,
        preferSharedLibraryForRun: Boolean = false,
        sourceFile: File? = null,
        onProgress: ((String) -> Unit)? = null,
    ): BuildOptions {
        val effective = EffectiveBuildConfigResolver.resolve(
            EffectiveBuildConfigResolver.Input(
                launch = launch,
                buildSystem = buildSystem,
                runConfig = runConfig,
                projectRoot = projectRoot,
                linuxEnvironmentAvailable = linuxEnvironmentAvailable,
                appContext = appContext,
            )
        )
        return BuildOptions(
            buildType = effective.buildType,
            generateDebugInfo = effective.generateDebugInfo,
            parallelJobs = effective.parallelJobs,
            optimizationLevel = effective.optimizationLevel,
            cmakeBuildType = effective.cmakeBuildType,
            cmakeGenerator = effective.cmakeGenerator,
            compilerType = effective.compilerType,
            sysrootApiLevel = effective.sysrootApiLevel,
            sysrootProfileId = effective.sysrootProfileId,
            nativeCFlags = effective.nativeCFlags,
            nativeCppFlags = effective.nativeCppFlags,
            nativeLdFlags = effective.nativeLdFlags,
            nativeLdLibs = effective.nativeLdLibs,
            nativeCMakeArgs = effective.nativeCMakeArgs,
            cppStandard = effective.cppStandard,
            resolvedRunMode = effective.resolvedRunMode,
            toolchainId = effective.toolchainId,
            customCCompiler = effective.customCCompiler,
            customCppCompiler = effective.customCppCompiler,
            buildForRun = buildForRun,
            preferSharedLibraryForRun = preferSharedLibraryForRun,
            sourceFile = sourceFile,
            onProgress = onProgress,
        )
    }

    private fun log(message: String) {
        if (message.isBlank()) return
        runCatching { outputManager.appendOutput(message + "\n", IOutputManager.OutputChannel.BUILD) }
    }
}
