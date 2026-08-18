package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import java.io.File
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 原生 Makefile 构建策略（不使用 PRoot）
 *
 * 通过 linker64 + tina-exec 在 Android 上运行 make。
 *
 * 优势：
 * - 零 PRoot 开销，构建速度提升 1.5-2x
 * - 内存占用减少 ~100MB
 * - 更简单的路径处理（无需 host/guest 转换）
 *
 * 要求：
 * - 工具链已通过 AndroidNativeToolchainManager 安装
 *
 * @param context Android 应用上下文
 * @param timeoutConfig 可选的超时配置（如果为 null，将创建新实例）
 */
class NativeMakeBuildStrategy(
    private val context: Context,
    timeoutConfig: CompileTimeoutConfig? = null
) {

    companion object {
        private const val TAG = "NativeMakeBuildStrategy"
        internal const val ANDROID_MAKE_RECIPE_SHELL = "/system/bin/sh"
        private val SHIM_TOOL_NAMES = setOf(
            "make",
            "cmake",
            "ninja",
            "clang",
            "clang++",
            "ld.lld",
            "lld",
            "ar",
            "ranlib",
            "strip",
            "nm",
            "objcopy",
            "llvm-ar",
            "llvm-ranlib",
            "llvm-strip",
            "llvm-nm",
            "llvm-objcopy"
        )

        internal fun buildRecipeShellAssignment(): String = "SHELL=$ANDROID_MAKE_RECIPE_SHELL"
    }

    // 超时配置（支持共享）
    private val sharedTimeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(context)
    private val toolchainManager = AndroidNativeToolchainManager(context)
    private val sysrootManager = AndroidSysrootManager(context.applicationContext)
    private val shimManager = ToolchainLinker64ShimManager(context.applicationContext)
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir

    val buildSystem = BuildSystem.MAKE

    suspend fun canHandle(projectRoot: File): Boolean = hasMakefile(projectRoot)

    suspend fun build(
        projectRoot: File,
        buildDir: File,
        target: String?,
        options: BuildOptions
    ): BuildResult {
        val startTime = System.currentTimeMillis()
        val outputBuilder = StringBuilder()
        val sysrootApiLevel = options.sysrootApiLevel
        val sysrootProfileId = options.sysrootProfileId

        NativeSysrootPreparer.ensureInstalled(
            context = context,
            profileId = sysrootProfileId,
            apiLevel = sysrootApiLevel,
        )?.let { error ->
            options.onProgress?.invoke(error)
            return makeBuildError(error)
        }
        val sysrootValidationError = validateSysrootForBuild(sysrootApiLevel, sysrootProfileId)
        if (sysrootValidationError != null) {
            options.onProgress?.invoke(sysrootValidationError)
            return makeBuildError(sysrootValidationError)
        }

        options.onProgress?.invoke(Strings.make_building.strOr(context))
        if (!buildDir.exists()) {
            buildDir.mkdirs()
        }

        // 构建 make 命令
        val makeBinary = File(toolchainManager.getBinDir(), "make")
        val makeCommand = mutableListOf(makeBinary.absolutePath)
        val packagePaths = InstalledPackagePathResolver.resolve(context.applicationContext, projectRoot)
        val arch = AndroidSysrootManager.Companion.Arch.current()
        val packageEnvironment = MakeBuildEnvironment.build(
            packagePaths = packagePaths,
            nativeCFlags = options.nativeCFlags,
            nativeCppFlags = options.nativeCppFlags,
            nativeLdFlags = AndroidLinkerCompatibilityFlags.mergeWithUserLdFlags(
                arch = arch,
                apiLevel = sysrootApiLevel,
                userLdFlags = options.nativeLdFlags,
            ),
            nativeLdLibs = options.nativeLdLibs,
            extraLibraryDirs = listOfNotNull(
                sysrootManager.getLibPath(
                    apiLevel = sysrootApiLevel,
                    arch = arch,
                    profileId = sysrootProfileId
                )
            )
        )

        // 这是 Make 配方执行所需的真实 shell 路径，不是额外启动兼容层。
        // Android 上 /bin/sh 不存在，GNU make 默认 SHELL=/bin/sh 会导致 Error 127，
        // 且 make 不会从环境变量继承 SHELL，所以必须通过命令行变量显式覆盖。
        makeCommand.add(buildRecipeShellAssignment())
        appendMakeToolchainOverrides(makeCommand, sysrootApiLevel, sysrootProfileId)

        // 添加并行任务数
        if (options.parallelJobs > 1) {
            makeCommand.add("-j${options.parallelJobs}")
        }

        // 尽量把常见构建输出变量统一指向当前项目的 build 目录。
        makeCommand.add("BUILD_DIR=${buildDir.absolutePath}")
        makeCommand.add("OUT_DIR=${buildDir.absolutePath}")
        makeCommand.add("OBJDIR=${buildDir.absolutePath}")
        makeCommand.add("O=${buildDir.absolutePath}")
        makeCommand.add("BUILD_TYPE=${options.buildType.name.lowercase()}")

        // 添加目标（如果指定）
        if (!target.isNullOrBlank()) {
            makeCommand.add(target)
        }

        Timber.tag(TAG).d("Executing: ${makeCommand.joinToString(" ")}")
        Timber.tag(TAG).d("Working directory: ${projectRoot.absolutePath}")

        // 执行 make
        val result = executeNativeCommand(
            command = makeCommand,
            workingDir = projectRoot,
            timeout = sharedTimeoutConfig.getMakeBuildTimeout(),
            extraEnvironment = packageEnvironment
        ) { line ->
            outputBuilder.appendLine(line)
            options.onProgress?.invoke(line)
        }

        val buildTime = System.currentTimeMillis() - startTime

        return if (result.exitCode == 0) {
            val executablePath = MakeBuildOutputLocator.findExecutable(
                projectRoot = projectRoot,
                buildDir = buildDir,
                target = target,
                makefile = findMakefile(projectRoot)
            )

            Timber.tag(TAG).d("Build succeeded in ${buildTime}ms")
            BuildResult.Success(
                message = Strings.make_build_success.strOr(context),
                buildTimeMs = buildTime,
                outputPath = executablePath
            )
        } else {
            val rawOutput = outputBuilder.toString().ifBlank { result.output }
            Timber.tag(TAG).w("Build failed with exit code ${result.exitCode}")
            Timber.tag(TAG).d("Output: $rawOutput")

            // 解析诊断信息
            val diagnostics = parseDiagnostics(rawOutput)

            BuildResult.Error(
                rawOutput = rawOutput,
                diagnostics = diagnostics
            )
        }
    }

    suspend fun clean(buildDir: File): CleanResult {
        return try {
            val projectRoot = buildDir.parentFile ?: return CleanResult.Error("Invalid build directory")

            if (hasMakefile(projectRoot)) {
                val makeBinary = File(toolchainManager.getBinDir(), "make")
                val result = executeNativeCommand(
                    command = listOf(makeBinary.absolutePath, buildRecipeShellAssignment(), "clean"),
                    workingDir = projectRoot,
                    timeout = sharedTimeoutConfig.getMakeCleanTimeout()
                )

                if (result.exitCode == 0) {
                    Timber.tag(TAG).d("make clean succeeded")
                } else {
                    Timber.tag(TAG).w("make clean failed, falling back to manual cleanup")
                }
            }

            // 无论 make clean 是否成功，都清理 build 目录
            if (buildDir.exists()) {
                buildDir.deleteRecursively()
            }

            CleanResult.Success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Clean failed")
            CleanResult.Error(e.message ?: Strings.make_clean_failed.strOr(context))
        }
    }

    suspend fun getTargets(projectRoot: File, buildDir: File): List<TargetInfo> {
        // 从 Makefile 中解析目标
        val makefile = findMakefile(projectRoot) ?: return emptyList()

        return try {
            val content = makefile.readText()
            parseMakefileTargets(content)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse Makefile targets")
            emptyList()
        }
    }

    /**
     * 检查是否存在 Makefile
     */
    private fun hasMakefile(projectRoot: File): Boolean = findMakefile(projectRoot) != null

    /**
     * 查找 Makefile
     */
    private fun findMakefile(projectRoot: File): File? {
        val candidates = listOf("Makefile", "makefile", "GNUmakefile")
        return candidates.map { File(projectRoot, it) }.firstOrNull { it.exists() }
    }

    /**
     * 从 Makefile 内容中解析目标列表
     */
    private fun parseMakefileTargets(content: String): List<TargetInfo> {
        val targets = mutableListOf<TargetInfo>()

        // 匹配目标定义：target: dependencies
        val targetRegex = Regex("""^([a-zA-Z_][a-zA-Z0-9_-]*)\s*:(?!=)""", RegexOption.MULTILINE)

        for (match in targetRegex.findAll(content)) {
            val targetName = match.groupValues[1]
            // 排除 .PHONY 等特殊目标
            if (!targetName.startsWith(".")) {
                targets.add(
                    TargetInfo(
                        name = targetName,
                        type = if (targetName in listOf("all", "clean", "install", "run", "test")) {
                            TargetInfo.Type.OTHER
                        } else {
                            TargetInfo.Type.EXECUTABLE
                        },
                        sources = emptyList()
                    )
                )
            }
        }

        return targets
    }

    /**
     * 解析编译错误诊断信息
     */
    private fun parseDiagnostics(output: String): List<BuildDiagnostic> = BuildDiagnosticParser.parse(output)

    private fun appendMakeToolchainOverrides(
        makeCommand: MutableList<String>,
        sysrootApiLevel: Int,
        sysrootProfileId: String?
    ) {
        if (!NativeExecutableRunner.shouldPreferLinker64()) {
            Timber.tag(TAG).d("Skip make toolchain overrides because preferLinker64=false")
            return
        }

        val toolchainBinDir = toolchainManager.getBinDir()
        val shimSet = shimManager.prepare(toolchainBinDir, SHIM_TOOL_NAMES) ?: run {
            Timber.tag(TAG).w("Skip make toolchain overrides because shim set preparation failed")
            return
        }
        val cFlagSplit = MakeCommandOverrides.splitCompileAndLinkFlags(
            buildSysrootFlags(isCpp = false, apiLevel = sysrootApiLevel, profileId = sysrootProfileId)
        )
        val cxxFlagSplit = MakeCommandOverrides.splitCompileAndLinkFlags(
            buildSysrootFlags(isCpp = true, apiLevel = sysrootApiLevel, profileId = sysrootProfileId)
        )
        val cSysrootFlags = cFlagSplit.compileFlags
        val cxxSysrootFlags = cxxFlagSplit.compileFlags
        val ccValue = appendMakeVarIfPresent(makeCommand, "CC", shimSet, listOf("clang"), cSysrootFlags)
        val cxxValue = appendMakeVarIfPresent(makeCommand, "CXX", shimSet, listOf("clang++"), cxxSysrootFlags)
        appendMakeVarIfPresent(makeCommand, "LD", shimSet, listOf("ld.lld", "lld"))
        appendMakeVarIfPresent(makeCommand, "AR", shimSet, listOf("llvm-ar", "ar"))
        appendMakeVarIfPresent(makeCommand, "RANLIB", shimSet, listOf("llvm-ranlib", "ranlib"))
        appendMakeVarIfPresent(makeCommand, "STRIP", shimSet, listOf("llvm-strip", "strip"))
        appendMakeVarIfPresent(makeCommand, "NM", shimSet, listOf("llvm-nm", "nm"))
        appendMakeVarIfPresent(makeCommand, "OBJCOPY", shimSet, listOf("llvm-objcopy", "objcopy"))
        appendMakeVarIfPresent(makeCommand, "CMAKE_COMMAND", shimSet, listOf("cmake"))

        Timber.tag(TAG).d("Injected make variable CC=%s", ccValue ?: "<not-set>")
        Timber.tag(TAG).d("Injected make variable CXX=%s", cxxValue ?: "<not-set>")
    }

    private fun appendMakeVarIfPresent(
        makeCommand: MutableList<String>,
        variable: String,
        shimSet: ToolchainLinker64ShimManager.ShimSet,
        candidates: List<String>,
        extraArgs: List<String> = emptyList()
    ): String? {
        val cmd = candidates.firstNotNullOfOrNull { shimSet.shellCommandString(it) } ?: return null
        val fullCmd = MakeCommandOverrides.buildVariableValue(cmd, extraArgs)
        makeCommand.add(
            MakeCommandOverrides.buildVariableAssignment(
                variable = variable,
                shellCommand = cmd,
                extraArgs = extraArgs
            )
        )
        return fullCmd
    }

    private fun buildSysrootFlags(isCpp: Boolean, apiLevel: Int, profileId: String?): List<String> {
        val arch = AndroidSysrootManager.Companion.Arch.current()
        return sysrootManager.getCompilerFlags(
            apiLevel = apiLevel,
            arch = arch,
            isCpp = isCpp,
            profileId = profileId
        )
    }

    private fun validateSysrootForBuild(apiLevel: Int, profileId: String?): String? {
        val arch = AndroidSysrootManager.Companion.Arch.current()
        val sysrootDir = sysrootManager.getSysrootDir(arch, profileId)
        if (!sysrootManager.isInstalled(arch, profileId)) {
            return Strings.compile_sysroot_missing.strOr(context, sysrootDir.absolutePath)
        }

        val apiLevelHeader = File(sysrootDir, "usr/include/android/api-level.h")
        if (!apiLevelHeader.isFile) {
            return Strings.compile_sysroot_missing_header.strOr(context, apiLevelHeader.absolutePath)
        }

        val iostreamHeader = File(sysrootDir, "usr/include/c++/v1/iostream")
        if (!iostreamHeader.isFile) {
            return Strings.compile_sysroot_missing_libcpp_header.strOr(context, iostreamHeader.absolutePath)
        }

        val apiLibDir = File(sysrootDir, "usr/lib/${arch.triple}/$apiLevel")
        if (!apiLibDir.isDirectory) {
            return Strings.compile_sysroot_missing_api_lib_dir.strOr(context, apiLevel, apiLibDir.absolutePath)
        }
        return null
    }

    private fun makeBuildError(message: String): BuildResult.Error {
        Timber.tag(TAG).e(message)
        return BuildResult.Error(
            rawOutput = message,
            diagnostics = parseDiagnostics(message)
        )
    }

    /**
     * 执行原生命令（通过 NativeExecutableRunner）
     */
    private suspend fun executeNativeCommand(
        command: List<String>,
        workingDir: File,
        timeout: Long,
        extraEnvironment: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null
    ): CommandResult {
        val commandTempDir = BuildResourceCleaner.createCommandTempDir(context.cacheDir, "native-make")
        return try {
        // 使用 NativeExecutableRunner 构建命令，自动处理 linker64 启动逻辑
        val executable = command[0]
        val args = command.drop(1)
        val fullCommand = com.wuxianggujun.tinaide.core.util.NativeExecutableRunner.buildCommand(
            executable = executable,
            args = args
        )

        val processBuilder = ProcessBuilder(fullCommand).apply {
            directory(workingDir)
            com.wuxianggujun.tinaide.core.util.NativeExecutableRunner.configureEnvironment(
                this,
                nativeLibDir,
                toolchainManager.getBinDir().absolutePath,
                tmpDir = commandTempDir.absolutePath,
                homeDir = context.filesDir.absolutePath
            )
            NativeExecutableRunner.applyRecommendedTinaExec(
                environment = environment(),
                context = context.applicationContext,
                fullCommand = fullCommand
            )
            applyExtraEnvironment(environment(), extraEnvironment)
            redirectErrorStream(true)
        }
        NativeExecutableRunner.logExecutionDiagnostics(
            tag = TAG,
            executable = executable,
            args = args,
            fullCommand = fullCommand,
            workingDir = workingDir,
            environment = processBuilder.environment().toMap(),
            toolchainBinDir = toolchainManager.getBinDir().absolutePath
        )

        val result = BuildProcessRunner.run(
            processBuilder = processBuilder,
            commandLabel = "native-make:${File(executable).name}",
            timeoutMs = timeout,
            onOutputLine = { line ->
                onOutput?.invoke(line)
                Timber.tag(TAG).v(line)
            },
        )

        // 读取输出
        // 等待进程结束
        if (result.timedOut) {
            Timber.tag(TAG).w("Native make command timed out after %d ms", timeout)
        }
        if (result.exitCode != 0) {
            NativeExecutableRunner.logFailureDiagnostics(
                tag = TAG,
                executable = executable,
                output = result.output,
                toolchainBinDir = toolchainManager.getBinDir().absolutePath
            )
        }

        CommandResult(
            exitCode = result.exitCode,
            output = result.output
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to execute native command")
        NativeExecutableRunner.logFailureDiagnostics(
            tag = TAG,
            executable = command.firstOrNull().orEmpty(),
            output = e.message ?: "unknown error",
            toolchainBinDir = toolchainManager.getBinDir().absolutePath
        )
        CommandResult(
            exitCode = -1,
            output = "Exception: ${e.message}"
        )
    } finally {
        BuildResourceCleaner.cleanupCommandTempDir(commandTempDir)
    }
    }

    private fun applyExtraEnvironment(
        environment: MutableMap<String, String>,
        extraEnvironment: Map<String, String>
    ) {
        if (extraEnvironment.isEmpty()) return
        extraEnvironment.forEach { (key, value) ->
            if (value.isBlank()) return@forEach
            val existing = environment[key].orEmpty()
            environment[key] = if (existing.isBlank()) {
                value
            } else {
                "$value:$existing"
            }
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )
}
