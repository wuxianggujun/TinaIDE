package com.wuxianggujun.tinaide.core.compile.strategy

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.AndroidCppRuntimeLinkage
import com.wuxianggujun.tinaide.core.compile.AndroidLinkerCompatibilityFlags
import com.wuxianggujun.tinaide.core.compile.BuildDiagnostic
import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticParser
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildProcessRunner
import com.wuxianggujun.tinaide.core.compile.BuildResourceCleaner
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.CompileTimeoutConfig
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.compile.NativeSysrootPreparer
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.SourceRef
import com.wuxianggujun.tinaide.core.compile.artifact.TrackedInputCollector
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.util.DiagnosticLogFormatter
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import com.wuxianggujun.tinaide.project.NativeBuildFlagTokenizer
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 单文件构建策略。
 *
 * 设计要点:
 * - [describeOutput] 纯查询:只读 FS 预测产物路径与 kind,不 spawn 编译
 * - [execute] 自带完整 clang/gcc 驱动 + sysroot 校验 + 诊断追踪,
 *   结束后产出带 hash 的 [Artifact]
 * - 事件经 [BuildEventEmitter] 细粒度下发(CompileProgress 等)
 */
class SingleFileStrategy(
    context: Context,
    timeoutConfig: CompileTimeoutConfig? = null,
) : BuildStrategy {

    companion object {
        private const val TAG = "SingleFileStrategy"
        private val SOURCE_EXTENSIONS: Set<String> = CxxFileSupport.singleFileBuildSourceExtensions
        private val CPP_EXTENSIONS: Set<String> = CxxFileSupport.cxxSourceExtensions

        internal fun mergeCompileFlagsWithStandard(
            isCpp: Boolean,
            standard: String,
            extraCompileFlags: List<String>,
        ): List<String> = if (isCpp) {
            extraCompileFlags + "-std=$standard"
        } else {
            listOf("-std=$standard") + extraCompileFlags
        }

        internal fun describeFsNode(file: File?): String {
            if (file == null) return "<null>"
            return buildString {
                append("path=").append(file.absolutePath)
                append(", exists=").append(file.exists())
                append(", isFile=").append(file.isFile)
                append(", isDir=").append(file.isDirectory)
                append(", canRead=").append(file.canRead())
                append(", canWrite=").append(file.canWrite())
                append(", size=").append(if (file.exists()) file.length() else 0L)
            }
        }

        internal fun buildArtifactMissingDiagnostic(
            buildDir: File,
            outputFile: File,
            workingDir: File,
            command: List<String>,
            rawOutput: String,
            preferLinker64: Boolean = NativeExecutableRunner.shouldPreferLinker64(),
            maxChildren: Int = 6,
        ): String {
            val fullCommand = if (command.isNotEmpty()) {
                NativeExecutableRunner.buildCommand(
                    executable = command.first(),
                    args = command.drop(1),
                    preferLinker64 = preferLinker64,
                )
            } else {
                emptyList()
            }
            val outputParent = outputFile.parentFile
            val launchMode = if (command.isNotEmpty()) {
                NativeExecutableRunner.describeLaunchMode(command.first(), fullCommand)
            } else {
                "unknown"
            }
            val childSnapshot = outputParent
                ?.takeIf { it.isDirectory }
                ?.list()
                ?.sorted()
                ?.let { children ->
                    when {
                        children.isEmpty() -> "<empty>"
                        children.size <= maxChildren -> children.joinToString(",")
                        else -> children.take(maxChildren).joinToString(",") + ",...(${children.size} total)"
                    }
                }
                ?: "<unavailable>"
            val stdoutFirstLine = rawOutput.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.take(200)
                ?: "<empty>"
            val commandText = fullCommand.joinToString(" ")
                .let { if (it.length <= 240) it else it.take(240) + "...(truncated)" }

            return DiagnosticLogFormatter.format(
                prefix = "Single-file artifact missing diag",
                "preferLinker64" to preferLinker64,
                "launchMode" to launchMode,
                "cwd" to workingDir.absolutePath,
                "buildDir" to "{${describeFsNode(buildDir)}}",
                "outputParent" to "{${describeFsNode(outputParent)}}",
                "output" to "{${describeFsNode(outputFile)}}",
                "outputParentChildren" to childSnapshot,
                "stdoutFirstLine" to stdoutFirstLine,
                "fullCommand" to commandText.ifBlank { "<empty>" }
            )
        }
    }

    private val appContext = context.applicationContext
    private val sharedTimeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(appContext)
    private val toolchainManager = AndroidNativeToolchainManager(appContext)
    private val nativeLibDir: String = appContext.applicationInfo.nativeLibraryDir

    override val buildSystem: BuildSystem = BuildSystem.SINGLE_FILE

    override suspend fun canHandle(projectRoot: File): Boolean {
        if (File(projectRoot, "CMakeLists.txt").exists()) return false
        return findSourceFiles(projectRoot).isNotEmpty()
    }

    override suspend fun describeOutput(ctx: BuildContext): ArtifactSpec? {
        val source = determineSourceFile(ctx.projectRoot, ctx.target, ctx.options.sourceFile)
        if (source == null) {
            Timber.tag(TAG).w("describeOutput: no source found for %s", ctx.projectRoot.absolutePath)
            return null
        }
        if (!source.isFile || !isSourceFile(source)) return null

        val preferShared = ctx.options.preferSharedLibraryForRun
        val outputFileName = if (preferShared) "lib${source.nameWithoutExtension}.so" else source.nameWithoutExtension
        val kind = if (preferShared) ArtifactKind.SHARED_LIBRARY else ArtifactKind.EXECUTABLE

        return ArtifactSpec(
            id = ArtifactId(
                projectId = ctx.projectId,
                targetName = source.nameWithoutExtension,
                variant = resolveVariant(ctx.options),
            ),
            expectedPath = File(ctx.buildDir, outputFileName),
            kind = kind,
            sources = listOf(source),
            reconfigureSources = TrackedInputCollector.collectSingleFileInputs(ctx.projectRoot, source),
        )
    }

    override suspend fun execute(
        ctx: BuildContext,
        spec: ArtifactSpec,
        fingerprint: BuildFingerprint,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome {
        val mainSource = spec.sources.firstOrNull()
            ?: return ExecutionOutcome.Failure("SingleFileStrategy.execute: spec.sources is empty")

        emitter.emit(BuildEvent.Build.CompileStarted(spec.id.targetName))
        val startTime = System.currentTimeMillis()

        if (!mainSource.exists()) {
            return fail(emitter, Strings.compile_single_file_source_not_exist.str(mainSource.absolutePath))
        }
        if (!isSourceFile(mainSource)) {
            return fail(emitter, Strings.compile_single_file_invalid_source.str(mainSource.name))
        }

        val outputFile = spec.expectedPath
        val outputPath = outputFile.absolutePath
        val buildDirReady = ctx.buildDir.exists() || ctx.buildDir.mkdirs()
        val outputParent = outputFile.parentFile ?: ctx.buildDir
        Timber.tag(TAG).d(
            DiagnosticLogFormatter.format(
                prefix = "Single-file path setup",
                "buildDirReady" to buildDirReady,
                "buildDir" to "{${describeFsNode(ctx.buildDir)}}",
                "outputParent" to "{${describeFsNode(outputParent)}}"
            )
        )
        val isCpp = mainSource.extension.lowercase() in CPP_EXTENSIONS
        val options = ctx.options
        val apiLevel = options.sysrootApiLevel
        Timber.tag(TAG).i(
            "Starting single-file build: source=%s output=%s variant=%s kind=%s project=%s",
            mainSource.absolutePath,
            outputPath,
            spec.id.variant,
            spec.kind,
            ctx.projectRoot.absolutePath,
        )

        emitter.tryEmit(
            BuildEvent.Build.CompileProgress(
                Strings.compile_single_file_compiling.str(mainSource.name)
            )
        )

        val cppStandard = if (isCpp) {
            options.cppStandard?.trim().takeUnless { it.isNullOrBlank() } ?: "c++17"
        } else {
            "c11"
        }

        val compilerPath = resolveCompilerPath(isCpp, options) ?: run {
            val msg = when (options.compilerType) {
                CompilerType.CLANG -> Strings.compile_error_clang_unavailable.strOr(appContext)
                CompilerType.GCC -> Strings.compile_error_gcc_unavailable.strOr(appContext)
                CompilerType.CUSTOM -> Strings.compile_error_custom_compiler_unavailable.strOr(
                    appContext,
                    options.customCCompiler.orEmpty(),
                    options.customCppCompiler.orEmpty(),
                )
            }
            return fail(emitter, msg)
        }

        NativeSysrootPreparer.ensureInstalled(
            context = appContext,
            profileId = options.sysrootProfileId,
            apiLevel = apiLevel,
        )?.let { err ->
            Timber.tag(TAG).e(err)
            return fail(emitter, err, BuildDiagnosticParser.parse(err))
        }
        validateSysrootForBuild(isCpp, apiLevel, options.sysrootProfileId)?.let { err ->
            Timber.tag(TAG).e(err)
            return fail(emitter, err, BuildDiagnosticParser.parse(err))
        }

        val sysrootFlags = buildSysrootFlags(isCpp, apiLevel, options.sysrootProfileId)
        val linkerCompatibilityFlags = AndroidLinkerCompatibilityFlags.forCurrentTarget(apiLevel)
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, ctx.projectRoot)
        val extraCompileFlags = NativeBuildFlagTokenizer.tokenize(
            if (isCpp) options.nativeCppFlags else options.nativeCFlags
        )
        val extraLdFlags = NativeBuildFlagTokenizer.tokenize(options.nativeLdFlags)
        val extraLdLibs = NativeBuildFlagTokenizer.tokenize(options.nativeLdLibs)

        val command = buildList {
            add(compilerPath)
            add(mainSource.absolutePath)
            add("-o")
            add(outputPath)
            if (options.compilerType == CompilerType.CLANG) add("-fintegrated-cc1")
            if (options.preferSharedLibraryForRun) {
                add("-shared")
                add("-fPIC")
            }
            add(resolveOptimizationFlag(options))
            if (options.generateDebugInfo) add("-g")
            add("-Wall")
            addAll(mergeCompileFlagsWithStandard(isCpp, cppStandard, extraCompileFlags))
            addAll(sysrootFlags)
            addAll(linkerCompatibilityFlags)
            addAll(
                AndroidCppRuntimeLinkage.flagsForOutput(
                    isCpp = isCpp,
                    outputIsSharedLibrary = options.preferSharedLibraryForRun,
                )
            )
            for (dir in packagePaths.includeDirs) add("-I${dir.absolutePath}")
            for (dir in packagePaths.libDirs) add("-L${dir.absolutePath}")
            for (library in packagePaths.linkLibraries) add("-l$library")
            addAll(extraLdFlags)
            addAll(extraLdLibs)
        }

        val outputBuilder = StringBuilder()
        val onProgress: (String) -> Unit = { line ->
            emitter.tryEmit(BuildEvent.Build.CompileProgress(line))
        }
        val result = executeNativeCommand(
            command = command,
            workingDir = ctx.projectRoot,
            timeout = sharedTimeoutConfig.getMakeBuildTimeout(),
            options = options,
        ) { line ->
            outputBuilder.appendLine(line)
            onProgress(line)
        }
        if (result.exitCode != 0 && isExecStyleFailure(result.output)) {
            appendClangTraceDiagnostics(command, ctx.projectRoot, sharedTimeoutConfig.getEnvCheckTimeout(), options, outputBuilder, onProgress)
            appendPhasedCompileDiagnostics(command, mainSource, ctx.projectRoot, sharedTimeoutConfig.getEnvCheckTimeout(), options, outputBuilder, onProgress)
        }
        val elapsed = System.currentTimeMillis() - startTime
        val rawOutput = outputBuilder.toString().ifBlank { result.output }
        Timber.tag(TAG).i(
            "Single-file build finished: exitCode=%d elapsedMs=%d output=%s exists=%s size=%d",
            result.exitCode,
            elapsed,
            outputPath,
            outputFile.exists(),
            outputFile.length(),
        )

        if (result.exitCode != 0) {
            val diagnostics = BuildDiagnosticParser.parse(rawOutput)
            emitter.emit(BuildEvent.Build.CompileFailed(rawOutput, diagnostics))
            val firstLine = rawOutput.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            Timber.tag(TAG).w(
                "Single-file build failed: source=%s reason=%s",
                mainSource.absolutePath,
                firstLine.ifBlank { "single-file compile failed" },
            )
            return ExecutionOutcome.Failure(
                reason = firstLine.ifBlank { "single-file compile failed" },
                diagnostics = diagnostics,
                rawOutput = rawOutput,
            )
        }

        if (!outputFile.isFile) {
            val reason = "compile succeeded but artifact missing: $outputPath"
            emitter.emit(BuildEvent.Build.CompileFailed(reason, emptyList()))
            Timber.tag(TAG).w(reason)
            Timber.tag(TAG).w(
                "%s",
                buildArtifactMissingDiagnostic(
                    buildDir = ctx.buildDir,
                    outputFile = outputFile,
                    workingDir = ctx.projectRoot,
                    command = command,
                    rawOutput = rawOutput,
                )
            )
            return ExecutionOutcome.Failure(reason)
        }
        runCatching { outputFile.setExecutable(true, false) }

        val artifact = Artifact(
            id = spec.id,
            absolutePath = outputPath,
            kind = spec.kind,
            contentHash = computeContentHash(outputFile),
            fingerprint = fingerprint,
            sources = spec.sources.map { captureSourceRef(it, ctx.projectRoot) },
            compiledAt = System.currentTimeMillis(),
            buildTimeMs = elapsed,
        )
        Timber.tag(TAG).i(
            "Single-file artifact ready: path=%s size=%d hash=%s",
            artifact.absolutePath,
            outputFile.length(),
            artifact.contentHash,
        )
        emitter.emit(BuildEvent.Build.CompileCompleted(artifact))
        return ExecutionOutcome.Success(
            artifact = artifact,
            rawOutput = Strings.compile_single_file_compile_success.str(mainSource.name),
        )
    }

    override suspend fun clean(ctx: BuildContext, reconfigure: Boolean) {
        if (ctx.buildDir.exists()) ctx.buildDir.deleteRecursively()
    }

    override suspend fun getTargets(ctx: BuildContext): List<TargetInfo> = findSourceFiles(ctx.projectRoot).map { file ->
        TargetInfo(
            name = file.nameWithoutExtension,
            type = TargetInfo.Type.EXECUTABLE,
            sources = listOf(file.name),
        )
    }

    // ---------- 编译 / 诊断助手 ----------

    private suspend fun executeNativeCommand(
        command: List<String>,
        workingDir: File,
        timeout: Long,
        options: BuildOptions,
        onOutput: ((String) -> Unit)? = null,
    ): CommandResult {
        val commandTempDir = BuildResourceCleaner.createCommandTempDir(appContext.cacheDir, "single-file")
        return try {
        val executable = command[0]
        val args = command.drop(1)
        val fullCommand = NativeExecutableRunner.buildCommand(executable = executable, args = args)

        val processBuilder = ProcessBuilder(fullCommand).apply {
            directory(workingDir)
            NativeExecutableRunner.configureEnvironment(
                this,
                nativeLibDir,
                toolchainManager.getBinDir(options.toolchainId).absolutePath,
                tmpDir = commandTempDir.absolutePath,
                homeDir = appContext.filesDir.absolutePath,
            )
            NativeExecutableRunner.applyRecommendedTinaExec(
                environment = environment(),
                context = appContext,
                fullCommand = fullCommand,
            )
            redirectErrorStream(true)
        }
        NativeExecutableRunner.logExecutionDiagnostics(
            tag = TAG,
            executable = executable,
            args = args,
            fullCommand = fullCommand,
            workingDir = workingDir,
            environment = processBuilder.environment().toMap(),
            toolchainBinDir = toolchainManager.getBinDir(options.toolchainId).absolutePath,
        )

        val result = BuildProcessRunner.run(
            processBuilder = processBuilder,
            commandLabel = "single-file:${File(executable).name}",
            timeoutMs = timeout,
            onOutputLine = { line ->
                onOutput?.invoke(line)
                Timber.tag(TAG).v(line)
            },
        )

        if (result.timedOut) Timber.tag(TAG).w("Native compile command timed out after %d ms", timeout)
        if (result.exitCode != 0) {
            NativeExecutableRunner.logFailureDiagnostics(
                tag = TAG,
                executable = executable,
                output = result.output,
                toolchainBinDir = toolchainManager.getBinDir().absolutePath,
            )
        }

        CommandResult(exitCode = result.exitCode, output = result.output)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to execute native compile command")
        NativeExecutableRunner.logFailureDiagnostics(
            tag = TAG,
            executable = command.firstOrNull().orEmpty(),
            output = e.message ?: "unknown error",
            toolchainBinDir = toolchainManager.getBinDir().absolutePath,
        )
        CommandResult(exitCode = -1, output = Strings.compile_exec_exception.strOr(appContext, e.message ?: ""))
    } finally {
        BuildResourceCleaner.cleanupCommandTempDir(commandTempDir)
    }
    }

    private suspend fun appendClangTraceDiagnostics(
        originalCommand: List<String>,
        workingDir: File,
        timeout: Long,
        options: BuildOptions,
        outputBuilder: StringBuilder,
        onProgress: ((String) -> Unit)?,
    ) {
        val traceCommand = originalCommand + listOf("-v", "-###")
        val traceBanner = Strings.compile_diag_trace_banner.strOr(appContext, traceCommand.joinToString(" "))
        outputBuilder.appendLine(traceBanner)
        onProgress?.invoke(traceBanner)
        Timber.tag(TAG).w("Exec failure detected, run trace command: %s", traceCommand.joinToString(" "))

        val traceResult = executeNativeCommand(traceCommand, workingDir, timeout, options) { line ->
            val tagged = "[diag] $line"
            outputBuilder.appendLine(tagged)
            onProgress?.invoke(tagged)
        }
        val exitLine = Strings.compile_diag_trace_exit_code.strOr(appContext, traceResult.exitCode)
        outputBuilder.appendLine(exitLine)
        onProgress?.invoke(exitLine)
    }

    private suspend fun appendPhasedCompileDiagnostics(
        originalCommand: List<String>,
        sourceFile: File,
        workingDir: File,
        timeout: Long,
        options: BuildOptions,
        outputBuilder: StringBuilder,
        onProgress: ((String) -> Unit)?,
    ) {
        if (originalCommand.isEmpty()) return
        val compiler = originalCommand.first()
        val args = originalCommand.drop(1)
        val argsWithoutIo = stripInputOutputArgs(args, sourceFile.absolutePath)
        val phasePrefix = "[diag-phase]"
        val diagObject = File(appContext.cacheDir, "${sourceFile.nameWithoutExtension}-${System.currentTimeMillis()}-diag.o")
        val diagBinary = File(appContext.cacheDir, "${sourceFile.nameWithoutExtension}-${System.currentTimeMillis()}-diag-bin")

        val compileOnly = buildList {
            add(compiler)
            add(sourceFile.absolutePath)
            add("-c")
            add("-o")
            add(diagObject.absolutePath)
            addAll(argsWithoutIo)
        }
        emitDiagLine(outputBuilder, onProgress, "$phasePrefix compile-only command: ${compileOnly.joinToString(" ")}")
        val compileOnlyResult = executeNativeCommand(compileOnly, workingDir, timeout, options) { line ->
            emitDiagLine(outputBuilder, onProgress, "$phasePrefix compile-only: $line")
        }
        emitDiagLine(outputBuilder, onProgress, "$phasePrefix compile-only exitCode=${compileOnlyResult.exitCode}, objExists=${diagObject.exists()}, objSize=${diagObject.length()}")

        if (compileOnlyResult.exitCode != 0) {
            emitDiagLine(outputBuilder, onProgress, Strings.compile_diag_phase_compile_failed.strOr(appContext, phasePrefix))
            runCatching { diagObject.delete() }
            return
        }

        val linkOnlyArgs = extractLinkOnlyArgs(argsWithoutIo)
        val linkOnly = buildList {
            add(compiler)
            add(diagObject.absolutePath)
            add("-o")
            add(diagBinary.absolutePath)
            addAll(linkOnlyArgs)
        }
        emitDiagLine(outputBuilder, onProgress, "$phasePrefix link-only command: ${linkOnly.joinToString(" ")}")
        val linkOnlyResult = executeNativeCommand(linkOnly, workingDir, timeout, options) { line ->
            emitDiagLine(outputBuilder, onProgress, "$phasePrefix link-only: $line")
        }
        emitDiagLine(outputBuilder, onProgress, "$phasePrefix link-only exitCode=${linkOnlyResult.exitCode}, binExists=${diagBinary.exists()}, binSize=${diagBinary.length()}")
        if (linkOnlyResult.exitCode != 0) {
            emitDiagLine(outputBuilder, onProgress, Strings.compile_diag_phase_link_failed.strOr(appContext, phasePrefix))
        } else {
            emitDiagLine(outputBuilder, onProgress, Strings.compile_diag_phase_chain_failed.strOr(appContext, phasePrefix))
        }

        runCatching { diagObject.delete() }
        runCatching { diagBinary.delete() }
    }

    private fun stripInputOutputArgs(args: List<String>, sourcePath: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg == sourcePath) {
                i += 1
                continue
            }
            if (arg == "-o") {
                i += 2
                continue
            }
            result.add(arg)
            i += 1
        }
        return result
    }

    private fun extractLinkOnlyArgs(argsWithoutIo: List<String>): List<String> = argsWithoutIo.filterNot { arg ->
        arg == "-fintegrated-cc1" ||
            arg == "-Wall" ||
            arg == "-Wextra" ||
            arg == "-g" ||
            arg == "-c" ||
            arg.startsWith("-std=") ||
            arg.startsWith("-O")
    }

    private fun emitDiagLine(outputBuilder: StringBuilder, onProgress: ((String) -> Unit)?, line: String) {
        outputBuilder.appendLine(line)
        onProgress?.invoke(line)
    }

    private fun resolveCompilerPath(isCpp: Boolean, options: BuildOptions): String? = when (options.compilerType) {
        CompilerType.CLANG -> {
            if (!toolchainManager.isInstalled()) {
                null
            } else {
                File(toolchainManager.getBinDir(options.toolchainId), if (isCpp) "clang++" else "clang")
                    .takeIf { it.isFile }?.absolutePath
            }
        }
        CompilerType.GCC -> {
            if (!toolchainManager.isInstalled()) {
                null
            } else {
                File(toolchainManager.getBinDir(options.toolchainId), if (isCpp) "g++" else "gcc")
                    .takeIf { it.isFile }?.absolutePath
            }
        }
        CompilerType.CUSTOM -> {
            val customPath = (if (isCpp) options.customCppCompiler else options.customCCompiler)?.trim().orEmpty()
            if (customPath.isBlank()) {
                null
            } else {
                val customFile = File(customPath)
                if (customFile.isAbsolute && (!customFile.isFile || !customFile.canExecute())) null else customPath
            }
        }
    }

    private fun resolveOptimizationFlag(options: BuildOptions): String {
        if (options.generateDebugInfo) return "-O0"
        return when (options.optimizationLevel.trim().uppercase()) {
            "O0" -> "-O0"
            "O1" -> "-O1"
            "O2" -> "-O2"
            "O3" -> "-O3"
            else -> "-O2"
        }
    }

    private fun isExecStyleFailure(output: String): Boolean = output.contains("unable to execute command", ignoreCase = true) ||
        output.contains("No such file or directory", ignoreCase = true) ||
        output.contains("clang frontend command failed", ignoreCase = true)

    private fun buildSysrootFlags(isCpp: Boolean, apiLevel: Int, profileId: String?): List<String> {
        val sysrootManager = AndroidSysrootManager(appContext)
        val arch = AndroidSysrootManager.Companion.Arch.current()
        return sysrootManager.getCompilerFlags(
            apiLevel = apiLevel,
            arch = arch,
            isCpp = isCpp,
            profileId = profileId
        )
    }

    private fun validateSysrootForBuild(isCpp: Boolean, apiLevel: Int, profileId: String?): String? {
        val sysrootManager = AndroidSysrootManager(appContext)
        val arch = AndroidSysrootManager.Companion.Arch.current()
        val sysrootDir = sysrootManager.getSysrootDir(arch, profileId)
        if (!sysrootManager.isInstalled(arch, profileId)) {
            return Strings.compile_sysroot_missing.strOr(appContext, sysrootDir.absolutePath)
        }
        val apiLevelHeader = File(sysrootDir, "usr/include/android/api-level.h")
        if (!apiLevelHeader.isFile) return Strings.compile_sysroot_missing_header.strOr(appContext, apiLevelHeader.absolutePath)
        if (isCpp) {
            val iostreamHeader = File(sysrootDir, "usr/include/c++/v1/iostream")
            if (!iostreamHeader.isFile) return Strings.compile_sysroot_missing_libcpp_header.strOr(appContext, iostreamHeader.absolutePath)
        }
        val apiLibDir = File(sysrootDir, "usr/lib/${arch.triple}/$apiLevel")
        if (!apiLibDir.isDirectory) return Strings.compile_sysroot_missing_api_lib_dir.strOr(appContext, apiLevel, apiLibDir.absolutePath)
        return null
    }

    // ---------- 源文件解析 / variant / hash / sourceRef 助手 ----------

    private fun determineSourceFile(projectRoot: File, target: String?, sourceFile: File?): File? {
        sourceFile?.let { if (it.isFile && isSourceFile(it)) return it }
        if (!target.isNullOrBlank()) {
            findSourceFiles(projectRoot).firstOrNull { it.name == target || it.nameWithoutExtension == target }
                ?.let { return it }
        }
        val sources = findSourceFiles(projectRoot)
        if (sources.isEmpty()) return null
        return sources.firstOrNull { it.nameWithoutExtension == "main" } ?: sources.first()
    }

    private fun findSourceFiles(projectRoot: File): List<File> = projectRoot.walkTopDown().filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }.toList()

    private fun isSourceFile(file: File): Boolean = file.isFile && file.extension.lowercase() in SOURCE_EXTENSIONS

    private fun resolveVariant(options: BuildOptions): String {
        val base = options.buildType.name.lowercase()
        return if (options.preferSharedLibraryForRun) "$base-lib" else base
    }

    private fun computeContentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val bytes = digest.digest()
        return buildString(32) {
            for (i in 0 until 16) {
                val b = bytes[i].toInt() and 0xFF
                append(b.toString(16).padStart(2, '0'))
            }
        }
    }

    private fun captureSourceRef(file: File, projectRoot: File): SourceRef =
        SourceRef.capture(file, projectRoot)

    private suspend fun fail(
        emitter: BuildEventEmitter,
        reason: String,
        diagnostics: List<BuildDiagnostic> = emptyList(),
    ): ExecutionOutcome {
        emitter.emit(BuildEvent.Build.CompileFailed(reason, diagnostics))
        return ExecutionOutcome.Failure(reason, diagnostics)
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}
