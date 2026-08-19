package com.wuxianggujun.tinaide.core.compile.cmake

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.compile.AndroidLinkerCompatibilityFlags
import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticParser
import com.wuxianggujun.tinaide.core.compile.BuildProcessRunner
import com.wuxianggujun.tinaide.core.compile.BuildResourceCleaner
import com.wuxianggujun.tinaide.core.compile.BuildResult
import com.wuxianggujun.tinaide.core.compile.CMakeConfigurationIdentity
import com.wuxianggujun.tinaide.core.compile.CleanResult
import com.wuxianggujun.tinaide.core.compile.CompileTimeoutConfig
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.MakeCommandOverrides
import com.wuxianggujun.tinaide.core.compile.NativeRuntimeIdentity
import com.wuxianggujun.tinaide.core.compile.NativeSysrootPreparer
import com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.packages.PackageAbiCompatibility
import com.wuxianggujun.tinaide.core.util.AndroidSystemLinker
import com.wuxianggujun.tinaide.core.util.DiagnosticLogFormatter
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 原生 CMake 构建执行器（不使用 PRoot）
 *
 * 通过 linker64 / shell shim 在 Android 上运行 cmake。
 * CMake configure/build/clean 链路显式禁用 tina-exec preload，
 * 避免 try_compile 在 Android 高版本上受到 LD_PRELOAD 干扰。
 *
 * 优势：
 * - 零 PRoot 开销，配置和编译速度提升 2-3x
 * - 内存占用减少 ~100MB
 * - 更简单的路径处理（无需 host/guest 转换）
 *
 * 要求：
 * - 工具链已通过 AndroidNativeToolchainManager 安装
 */
class NativeCMakeBuildExecutor(
    context: Context,
    timeoutConfig: CompileTimeoutConfig? = null
) {
    private val appContext = context.applicationContext
    private val timeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(context)
    private val toolchainManager = AndroidNativeToolchainManager(appContext)
    private val shimManager = ToolchainLinker64ShimManager(appContext)
    private val nativeLibDir = appContext.applicationInfo.nativeLibraryDir

    companion object {
        private const val TAG = "NativeCMakeBuildExecutor"
        private const val DEFAULT_BUILD_DIR = "build"
        private const val COMPILE_COMMANDS_FILE = "compile_commands.json"
        private const val MAX_CMAKE_DIAGNOSTIC_CHARS = 12_000
        private const val MAX_CMAKE_SCRATCH_DIAGNOSTIC_CHARS = 4_000
        internal const val ENV_TOOLCHAIN_SHIM_TRACE = "TINAIDE_TOOLCHAIN_SHIM_TRACE"
        private val SHIM_TOOL_NAMES = setOf(
            "cmake",
            "make",
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

        internal data class CMakeCompilerCommand(
            val compiler: String,
            val arg1: String? = null
        )

        internal fun resolveConfiguredCompilerPath(configuredPath: String?, fallbackPath: String): String {
            val normalized = configuredPath?.trim().orEmpty()
            return normalized.ifBlank { fallbackPath }
        }

        internal fun shouldPatchNinjaFile(
            content: String,
            cmakePaths: Set<String>,
            shimScriptPaths: Set<String>,
            rawToolPaths: Set<String> = emptySet()
        ): Boolean {
            if (content.isEmpty()) return false
            return cmakePaths.any { it in content } ||
                shimScriptPaths.any { it in content } ||
                rawToolPaths.any { it in content }
        }

        internal fun resolveCMakeCompilerCommand(
            realCompilerPath: String,
            shimCompilerPath: String?,
            preferLinker64: Boolean,
            linker64Path: String,
            useRecommendedTinaExec: Boolean = false
        ): CMakeCompilerCommand {
            if (!preferLinker64) {
                return CMakeCompilerCommand(compiler = realCompilerPath)
            }
            val normalizedShimPath = shimCompilerPath?.trim().orEmpty()
            if (normalizedShimPath.isNotBlank()) {
                return CMakeCompilerCommand(
                    compiler = "/system/bin/sh",
                    arg1 = normalizedShimPath
                )
            }
            val normalizedLauncher = linker64Path.trim()
            if (normalizedLauncher.isBlank()) {
                return CMakeCompilerCommand(compiler = realCompilerPath)
            }
            return CMakeCompilerCommand(
                compiler = normalizedLauncher,
                arg1 = realCompilerPath
            )
        }

        internal fun buildCompilerExecutionFlags(
            compilerType: CompilerType,
            preferLinker64: Boolean
        ): List<String> {
            if (!preferLinker64) return emptyList()
            return when (compilerType) {
                CompilerType.CLANG -> listOf("-fintegrated-cc1")
                else -> emptyList()
            }
        }

        internal fun resolveCMakeToolPath(
            toolchainBinDir: File,
            shimSet: ToolchainLinker64ShimManager.ShimSet?,
            candidates: List<String>,
            preferLinker64: Boolean
        ): String? {
            val toolName = candidates.firstOrNull { candidate ->
                File(toolchainBinDir, candidate).isFile
            } ?: return null
            if (preferLinker64) {
                candidates.firstNotNullOfOrNull { candidate ->
                    shimSet?.shimPath(candidate)
                }?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return File(toolchainBinDir, toolName).absolutePath
        }

        internal fun resolveBuildToolProgram(
            toolName: String,
            realBinary: File,
            shimSet: ToolchainLinker64ShimManager.ShimSet?,
            preferLinker64: Boolean
        ): File? {
            if (!realBinary.isFile) return null
            if (!preferLinker64) {
                // Android 9 / emulator 上 CMake 从 app 私有目录直接 exec ninja 时，
                // 可能只拿到 linker64 helper 文本。用 shebang shell shim 让内核先进入 sh。
                shimSet?.toolMap?.get(toolName)
                    ?.takeIf { it.isFile && it.canExecute() }
                    ?.let { return it }
            }

            // Android 10+ 的 linker64 shim 不能作为 CMAKE_MAKE_PROGRAM 传入，
            // CMake 只能传一个可直接 exec 的构建工具路径，不能带 "/system/bin/sh <script>" 参数。
            return realBinary.takeIf { it.isFile }
        }

        internal fun resolveBuildToolProgramSource(
            toolName: String,
            realBinary: File,
            selectedProgram: File?,
            shimSet: ToolchainLinker64ShimManager.ShimSet?,
            preferLinker64: Boolean
        ): String {
            if (selectedProgram == null) return "missing"

            val selectedPath = selectedProgram.absolutePath
            val realPath = realBinary.absolutePath
            val shimPath = shimSet?.shimPath(toolName)?.trim().orEmpty()

            if (selectedPath == realPath) {
                return "real"
            }
            if (shimPath.isNotBlank() && selectedPath == shimPath) {
                return "shim"
            }
            return "custom"
        }

        internal fun buildCMakeBuildCommand(
            generator: CMakeGenerator,
            toolchainBinDir: File,
            buildDir: File,
            target: String?,
            parallelJobs: Int
        ): List<String> {
            val normalizedParallelJobs = parallelJobs.coerceAtLeast(1)
            val normalizedTarget = target?.trim()?.takeIf { value -> value.isNotEmpty() }
            return when (generator) {
                CMakeGenerator.NINJA -> buildList {
                    add(File(toolchainBinDir, "ninja").absolutePath)
                    add("-C")
                    add(buildDir.absolutePath)
                    add("-j")
                    add(normalizedParallelJobs.toString())
                    normalizedTarget?.let { add(it) }
                }

                CMakeGenerator.UNIX_MAKEFILES -> buildList {
                    add(File(toolchainBinDir, "cmake").absolutePath)
                    add("--build")
                    add(buildDir.absolutePath)
                    normalizedTarget?.let { targetName ->
                        add("--target")
                        add(targetName)
                    }
                    add("--parallel")
                    add(normalizedParallelJobs.toString())
                }
            }
        }

        internal fun addBinaryCommandMapping(
            mappings: MutableMap<String, String>,
            tool: String,
            realBinaryPath: String,
            commandString: String,
            canonicalPath: String? = null
        ) {
            if (realBinaryPath.isBlank() || commandString.isBlank()) return

            NinjaCmakePathPatcher.expandPathVariants(realBinaryPath)
                .forEach { variant ->
                    mappings[variant] = commandString
                }

            val normalizedCanonicalPath = canonicalPath
                ?.takeIf { it.isNotBlank() && it != realBinaryPath }
                ?.takeIf { File(it).name == tool }
                ?: return

            NinjaCmakePathPatcher.expandPathVariants(normalizedCanonicalPath)
                .forEach { variant ->
                    mappings.putIfAbsent(variant, commandString)
                }
        }

        internal fun buildAndroidCompilerCacheHints(
            arch: AndroidSysrootManager.Companion.Arch
        ): Map<String, String> {
            val sizeofDataPtr = when (arch) {
                AndroidSysrootManager.Companion.Arch.ARM64,
                AndroidSysrootManager.Companion.Arch.X86_64 -> "8"
            }
            return linkedMapOf(
                "CMAKE_C_ABI_COMPILED" to "TRUE",
                "CMAKE_CXX_ABI_COMPILED" to "TRUE",
                "CMAKE_C_COMPILER_WORKS" to "TRUE",
                "CMAKE_CXX_COMPILER_WORKS" to "TRUE",
                "CMAKE_C_COMPILER_ABI" to "ELF",
                "CMAKE_CXX_COMPILER_ABI" to "ELF",
                "CMAKE_C_BYTE_ORDER" to "LITTLE_ENDIAN",
                "CMAKE_CXX_BYTE_ORDER" to "LITTLE_ENDIAN",
                "CMAKE_C_SIZEOF_DATA_PTR" to sizeofDataPtr,
                "CMAKE_CXX_SIZEOF_DATA_PTR" to sizeofDataPtr,
                "CMAKE_C_LIBRARY_ARCHITECTURE" to arch.triple,
                "CMAKE_CXX_LIBRARY_ARCHITECTURE" to arch.triple,
                "CMAKE_SIZEOF_VOID_P" to sizeofDataPtr,
                "CMAKE_TRY_COMPILE_TARGET_TYPE" to "STATIC_LIBRARY"
            )
        }

        internal fun buildConfigureFailureMessage(
            primaryOutput: String,
            buildDir: File,
            exitCode: Int,
            generator: String? = null,
            buildToolName: String? = null,
            buildToolSource: String? = null,
            buildToolReal: File? = null,
            buildToolSelected: File? = null,
            buildToolShim: String? = null,
            preferLinker64: Boolean? = null
        ): String {
            val sections = mutableListOf<String>()
            sections += buildConfigureFailureSummary(
                primaryOutput = primaryOutput,
                buildDir = buildDir,
                exitCode = exitCode,
                generator = generator,
                buildToolName = buildToolName,
                buildToolSource = buildToolSource,
                buildToolReal = buildToolReal,
                buildToolSelected = buildToolSelected,
                buildToolShim = buildToolShim,
                preferLinker64 = preferLinker64
            )
            val trimmedPrimary = primaryOutput.trim()
            if (trimmedPrimary.isNotEmpty()) {
                sections += trimmedPrimary
            }

            readConfigureDiagnosticSection(
                buildDir = buildDir,
                label = "CMakeError.log",
                relativePath = "CMakeFiles/CMakeError.log"
            )?.let(sections::add)
            readConfigureDiagnosticSection(
                buildDir = buildDir,
                label = "CMakeOutput.log",
                relativePath = "CMakeFiles/CMakeOutput.log"
            )?.let(sections::add)
            readConfigureDiagnosticSection(
                buildDir = buildDir,
                label = "CMakeConfigureLog.yaml",
                relativePath = "CMakeFiles/CMakeConfigureLog.yaml"
            )?.let(sections::add)
            sections += readTryCompileScratchDiagnostics(buildDir)

            val header = "CMake configure failed with exitCode=$exitCode"
            if (sections.isEmpty()) {
                return header
            }
            return buildString {
                appendLine(header)
                sections.forEachIndexed { index, section ->
                    if (index > 0) appendLine()
                    appendLine(section)
                }
            }.trimEnd()
        }

        internal fun buildConfigureFailureSummary(
            primaryOutput: String,
            buildDir: File,
            exitCode: Int,
            generator: String? = null,
            buildToolName: String? = null,
            buildToolSource: String? = null,
            buildToolReal: File? = null,
            buildToolSelected: File? = null,
            buildToolShim: String? = null,
            preferLinker64: Boolean? = null
        ): String {
            val firstFailure = findFirstConfigureFailureLine(
                buildList {
                    add(primaryOutput)
                    add(readConfigureDiagnosticContent(buildDir, "CMakeFiles/CMakeError.log"))
                    add(readConfigureDiagnosticContent(buildDir, "CMakeFiles/CMakeConfigureLog.yaml"))
                }.joinToString("\n")
            )
            val latestScratch = findLatestTryCompileScratchDir(buildDir)
            val scratchEntryCount = latestScratch
                ?.listFiles()
                ?.size
                ?: 0

            return DiagnosticLogFormatter.format(
                prefix = "CMake configure failure summary",
                "exitCode" to exitCode,
                "generator" to (generator?.takeIf { it.isNotBlank() } ?: "<unknown>"),
                "preferLinker64" to (preferLinker64?.toString() ?: "<unknown>"),
                "buildTool" to (buildToolName?.takeIf { it.isNotBlank() } ?: "<unknown>"),
                "buildToolSource" to (buildToolSource?.takeIf { it.isNotBlank() } ?: "<unknown>"),
                "buildToolReal" to (buildToolReal?.absolutePath ?: "<unknown>"),
                "buildToolSelected" to (buildToolSelected?.absolutePath ?: "<unknown>"),
                "buildToolShim" to (buildToolShim?.takeIf { it.isNotBlank() } ?: "<none>"),
                "latestScratch" to (latestScratch?.absolutePath ?: "<none>"),
                "latestScratchEntries" to scratchEntryCount,
                "firstFailure" to firstFailure
            )
        }

        internal fun findFirstConfigureFailureLine(text: String): String {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()

            val helperLine = lines.firstOrNull {
                it.contains("helper program for dynamic executables", ignoreCase = true)
            }
            if (helperLine != null) return helperLine.take(240)

            val ninjaVersionLine = lines.firstOrNull {
                it.startsWith("The detected version of Ninja", ignoreCase = true)
            }
            if (ninjaVersionLine != null) return ninjaVersionLine.take(240)

            val priorityLine = lines.firstOrNull { line ->
                line.startsWith("CMake Error", ignoreCase = true) ||
                    line.startsWith("ninja:", ignoreCase = true) ||
                    line.contains(" error:", ignoreCase = true) ||
                    line.contains(" failed", ignoreCase = true) ||
                    line.startsWith("Failed ", ignoreCase = true)
            }
            return (priorityLine ?: "<none>").take(240)
        }

        private fun readConfigureDiagnosticContent(buildDir: File, relativePath: String): String {
            val file = File(buildDir, relativePath)
            if (!file.isFile) return ""
            return runCatching { file.readText() }.getOrDefault("")
        }

        private fun findLatestTryCompileScratchDir(buildDir: File): File? {
            val scratchRoot = File(buildDir, "CMakeFiles/CMakeScratch")
            if (!scratchRoot.isDirectory) return null
            return scratchRoot.listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .maxByOrNull { it.lastModified() }
        }

        private fun readConfigureDiagnosticSection(
            buildDir: File,
            label: String,
            relativePath: String
        ): String? {
            val file = File(buildDir, relativePath)
            if (!file.isFile) return null
            val content = runCatching { file.readText() }
                .getOrElse { error ->
                    return "===== $label =====\n<failed to read: ${error.message}>"
                }
                .trim()
            if (content.isEmpty()) return null
            val clipped = if (content.length <= MAX_CMAKE_DIAGNOSTIC_CHARS) {
                content
            } else {
                content.takeLast(MAX_CMAKE_DIAGNOSTIC_CHARS)
            }
            return buildString {
                appendLine("===== $label =====")
                if (clipped !== content) {
                    appendLine("<trimmed to last $MAX_CMAKE_DIAGNOSTIC_CHARS chars>")
                }
                append(clipped)
            }
        }

        private fun readTryCompileScratchDiagnostics(buildDir: File): List<String> {
            val scratchRoot = File(buildDir, "CMakeFiles/CMakeScratch")
            if (!scratchRoot.isDirectory) return emptyList()

            return scratchRoot.listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .sortedByDescending { it.lastModified() }
                .take(2)
                .mapNotNull(::buildTryCompileScratchSection)
        }

        private fun buildTryCompileScratchSection(scratchDir: File): String? {
            val fileSummary = scratchDir.walkTopDown()
                .maxDepth(2)
                .filter { it != scratchDir }
                .take(20)
                .joinToString(separator = "\n") { file ->
                    val type = when {
                        file.isDirectory -> "dir"
                        file.isFile -> "file"
                        else -> "other"
                    }
                    val relative = file.relativeTo(scratchDir).invariantSeparatorsPath
                    "[$type] $relative (${file.length()} bytes)"
                }
                .ifBlank { "<empty>" }

            val buildNinjaSection = buildScratchFileSection(
                scratchDir = scratchDir,
                file = File(scratchDir, "build.ninja"),
                label = "build.ninja"
            )
            val cmakeListsSection = buildScratchFileSection(
                scratchDir = scratchDir,
                file = File(scratchDir, "CMakeLists.txt"),
                label = "CMakeLists.txt"
            )

            return buildString {
                appendLine("===== TryCompileScratch: ${scratchDir.name} =====")
                appendLine("path: ${scratchDir.absolutePath}")
                appendLine("entries:")
                appendLine(fileSummary)
                buildNinjaSection?.let {
                    appendLine()
                    appendLine(it)
                }
                cmakeListsSection?.let {
                    appendLine()
                    appendLine(it)
                }
            }.trimEnd()
        }

        private fun buildScratchFileSection(
            scratchDir: File,
            file: File,
            label: String
        ): String? {
            if (!file.isFile) return null
            val content = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
            if (content.isEmpty()) return null
            val clipped = if (content.length <= MAX_CMAKE_SCRATCH_DIAGNOSTIC_CHARS) {
                content
            } else {
                content.takeLast(MAX_CMAKE_SCRATCH_DIAGNOSTIC_CHARS)
            }
            val relativePath = file.relativeTo(scratchDir).invariantSeparatorsPath
            return buildString {
                appendLine("----- $label ($relativePath) -----")
                if (clipped !== content) {
                    appendLine("<trimmed to last $MAX_CMAKE_SCRATCH_DIAGNOSTIC_CHARS chars>")
                }
                append(clipped)
            }
        }

        internal fun buildCompilePackageEnvironment(
            packagePaths: InstalledPackagePathResolver.PackagePaths
        ): Map<String, String> {
            if (packagePaths.isEmpty) return emptyMap()
            val env = linkedMapOf<String, String>()
            putPathIfNotBlank(env, "CPATH", packagePaths.includeDirs.map { it.absolutePath })
            putPathIfNotBlank(env, "LIBRARY_PATH", packagePaths.libDirs.map { it.absolutePath })
            putPathIfNotBlank(env, "PKG_CONFIG_PATH", packagePaths.pkgConfigDirs.map { it.absolutePath })
            return env
        }

        internal fun buildCMakePackageRootArguments(
            packagePaths: InstalledPackagePathResolver.PackagePaths
        ): List<String> {
            if (packagePaths.prefixDirs.isEmpty()) return emptyList()
            val packagePrefixPath = packagePaths.prefixDirs.joinToString(";") { it.absolutePath }
            return listOf(
                "-DCMAKE_PREFIX_PATH=$packagePrefixPath",
                // Android/cross CMake 会把 find_package 限制在 root path 内，需同步注入包根。
                "-DCMAKE_FIND_ROOT_PATH=$packagePrefixPath"
            )
        }

        internal fun buildAndroidAbiCMakeArgument(androidAbi: String): String {
            val normalizedAbi = androidAbi.trim()
            require(normalizedAbi.isNotEmpty()) { "Android ABI must not be blank" }
            return "-DANDROID_ABI=$normalizedAbi"
        }

        internal fun buildCMakeExtraEnvironment(
            packageEnvironment: Map<String, String>,
            traceToolchainShim: Boolean
        ): Map<String, String> {
            if (!traceToolchainShim) return packageEnvironment
            return linkedMapOf<String, String>().apply {
                putAll(packageEnvironment)
                put(ENV_TOOLCHAIN_SHIM_TRACE, "1")
            }
        }

        internal fun shouldDisableTinaExecForCMakeBuild(
            generator: CMakeGenerator,
            preferLinker64: Boolean,
            useRecommendedTinaExec: Boolean
        ): Boolean = useRecommendedTinaExec && preferLinker64 && generator == CMakeGenerator.NINJA

        internal fun shouldRetryWithoutTinaExecAfterShimFailure(
            output: String,
            useRecommendedTinaExec: Boolean
        ): Boolean {
            if (!useRecommendedTinaExec || output.isBlank()) return false
            val text = output.lowercase()
            val hasNinjaFailure = "ninja: build stopped" in text || "failed:" in text
            val hasShimCompiler = "toolchain-shims" in text &&
                "/system/bin/sh" in text &&
                ("/bin/clang++" in text || "/bin/clang" in text)
            val hasConcreteCompilerDiagnostic = listOf(
                " error:",
                "fatal error:",
                "undefined reference",
                "no such file or directory",
                "permission denied"
            ).any { it in text }
            return hasNinjaFailure && hasShimCompiler && !hasConcreteCompilerDiagnostic
        }

        private fun putPathIfNotBlank(
            env: MutableMap<String, String>,
            key: String,
            values: List<String>
        ) {
            val normalized = values.filter { it.isNotBlank() }.distinct()
            if (normalized.isNotEmpty()) {
                env[key] = normalized.joinToString(":")
            }
        }

        internal fun clearTinaExecEnvironment(environment: MutableMap<String, String>) {
            listOf(
                "LD_PRELOAD",
                "TINA_EXEC__SYSTEM_LINKER_EXEC__MODE",
                "TINA_EXEC__PROC_SELF_EXE",
                "TINA_APP__DATA_DIR",
                "TINA_APP__LEGACY_DATA_DIR",
                "TINA_ROOTFS",
                "TINA_PREFIX"
            ).forEach(environment::remove)
        }

        internal fun appendCommandOutputLine(
            output: StringBuilder,
            line: String,
            onOutputLine: ((String) -> Unit)? = null
        ) {
            output.appendLine(line)
            onOutputLine?.invoke(line)
        }

        internal fun buildNativeCommandResultSummary(
            executable: String,
            fullCommand: List<String>,
            workingDir: File,
            timeoutMs: Long,
            durationMs: Long,
            finished: Boolean,
            exitCode: Int,
            output: String,
            maxValueChars: Int = 240
        ): String {
            val outputFirstLine = output.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.let { clipDiagnosticValue(it, maxValueChars) }
                ?: "<empty>"
            val commandText = fullCommand.joinToString(" ")
                .ifBlank { "<empty>" }
                .let { clipDiagnosticValue(it, maxValueChars) }
            return DiagnosticLogFormatter.format(
                prefix = "Native cmake result",
                "exitCode" to exitCode,
                "finished" to finished,
                "timedOut" to !finished,
                "durationMs" to durationMs,
                "timeoutMs" to timeoutMs,
                "launchMode" to NativeExecutableRunner.describeLaunchMode(executable, fullCommand),
                "cwd" to workingDir.absolutePath,
                "outputChars" to output.length,
                "outputFirstLine" to outputFirstLine,
                "fullCommand" to commandText
            )
        }

        internal data class ProcessExitResult(
            val finished: Boolean,
            val exitCode: Int
        )

        internal fun waitForProcessExit(
            process: Process,
            timeoutMs: Long,
            forceKillGraceMs: Long = 2_000L
        ): ProcessExitResult {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val exitCode = if (finished) {
                process.exitValue()
            } else {
                process.destroy()
                if (!process.waitFor(forceKillGraceMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(forceKillGraceMs, TimeUnit.MILLISECONDS)
                }
                -1
            }
            return ProcessExitResult(
                finished = finished,
                exitCode = exitCode
            )
        }

        private fun clipDiagnosticValue(value: String, maxLen: Int): String {
            if (value.length <= maxLen) return value
            return value.take(maxLen) + "...(truncated)"
        }
    }

    /**
     * CMake 构建选项（与 CMakeBuildExecutor.Options 兼容）
     */
    data class Options(
        val buildType: CMakeBuildType = CMakeBuildType.DEBUG,
        val generator: CMakeGenerator = CMakeGenerator.NINJA,
        val parallelJobs: Int = defaultParallelJobs(),
        val extraCMakeArgs: List<String> = emptyList(),
        val generateCompileCommands: Boolean = true,
        val compilerType: CompilerType = CompilerType.CLANG,
        val toolchainId: String? = null,
        val cCompilerPath: String? = null,
        val cxxCompilerPath: String? = null,
        val sysrootProfileId: String? = null,
        val sysrootApiLevel: Int = MakeCommandOverrides.DEFAULT_SYSROOT_API_LEVEL,
        val useRecommendedTinaExec: Boolean = false,
        val traceToolchainShim: Boolean = false,
        val cFlags: String = "",
        val cppFlags: String = "",
        val ldFlags: String = "",
        val ldLibs: String = "",
        val cppStandard: String? = null,
        val onProgress: ((String) -> Unit)? = null
    ) {
        companion object {
            fun defaultParallelJobs(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        }
    }

    enum class CMakeBuildType(val cmakeValue: String) {
        DEBUG("Debug"),
        RELEASE("Release"),
        REL_WITH_DEB_INFO("RelWithDebInfo"),
        MIN_SIZE_REL("MinSizeRel")
    }

    enum class CMakeGenerator(val cmakeValue: String) {
        UNIX_MAKEFILES("Unix Makefiles"),
        NINJA("Ninja")
    }

    /**
     * 检查 CMake 是否可用
     */
    suspend fun isCMakeAvailable(): Boolean {
        return try {
            val cmakeBinary = File(toolchainManager.getBinDir(), "cmake")
            if (!cmakeBinary.isFile) {
                Timber.tag(TAG).w("cmake binary not found: ${cmakeBinary.absolutePath}")
                return false
            }

            val result = executeNativeCommand(
                command = listOf(cmakeBinary.absolutePath, "--version"),
                workingDir = appContext.filesDir,
                timeout = timeoutConfig.getEnvCheckTimeout()
            )

            result.exitCode == 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check cmake availability")
            false
        }
    }

    /**
     * 获取 CMake 版本
     */
    suspend fun getCMakeVersion(): String? = try {
        val cmakeBinary = File(toolchainManager.getBinDir(), "cmake")
        val result = executeNativeCommand(
            command = listOf(cmakeBinary.absolutePath, "--version"),
            workingDir = appContext.filesDir,
            timeout = timeoutConfig.getEnvCheckTimeout()
        )

        if (result.exitCode == 0) {
            // 解析版本号（第一行通常是 "cmake version X.Y.Z"）
            result.output.lines().firstOrNull()?.let { firstLine ->
                Regex("""cmake version (\d+\.\d+\.\d+)""").find(firstLine)?.groupValues?.get(1)
            }
        } else {
            null
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to get cmake version")
        null
    }

    /**
     * 配置 CMake 项目
     */
    suspend fun configure(
        projectDir: File,
        buildDir: File = File(projectDir, DEFAULT_BUILD_DIR),
        options: Options = Options()
    ): ConfigureResult {
        // 确保构建目录存在
        if (!buildDir.exists()) {
            buildDir.mkdirs()
        }

        // 解析编译器路径
        val toolchainBinDir = toolchainManager.getBinDir(options.toolchainId)
        val cCompiler = resolveConfiguredCompilerPath(
            configuredPath = options.cCompilerPath,
            fallbackPath = File(toolchainBinDir, "clang").absolutePath
        )
        val cxxCompiler = resolveConfiguredCompilerPath(
            configuredPath = options.cxxCompilerPath,
            fallbackPath = File(toolchainBinDir, "clang++").absolutePath
        )

        // 构建 cmake 命令
        val cmakeBinary = File(toolchainBinDir, "cmake")

        // sysroot 路径（Bionic libc 头文件和库）
        val sysrootManager = AndroidSysrootManager(appContext)
        NativeSysrootPreparer.ensureInstalled(
            context = appContext,
            profileId = options.sysrootProfileId,
            apiLevel = options.sysrootApiLevel,
        )?.let { message ->
            Timber.tag(TAG).w(message)
            return ConfigureResult.Error(message)
        }
        val sysrootDir = sysrootManager.getSysrootDir(profileId = options.sysrootProfileId)
        val apiLevelHeader = File(sysrootDir, "usr/include/android/api-level.h")
        val iostreamHeader = File(sysrootDir, "usr/include/c++/v1/iostream")

        if (!apiLevelHeader.isFile) {
            val message = buildString {
                append("Android sysroot is missing or incomplete. ")
                append("Expected file not found: ")
                append(apiLevelHeader.absolutePath)
            }
            Timber.tag(TAG).w(message)
            return ConfigureResult.Error(message)
        }
        if (!iostreamHeader.isFile) {
            val message = buildString {
                append("Android sysroot is missing libc++ headers. ")
                append("Expected file not found: ")
                append(iostreamHeader.absolutePath)
            }
            Timber.tag(TAG).w(message)
            return ConfigureResult.Error(message)
        }

        // 已安装包的 prefix 路径（供 find_package 使用）
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectDir)
        val packageIncludePath = packagePaths.includeDirs.joinToString(";") { it.absolutePath }
        val packageLibraryPath = packagePaths.libDirs.joinToString(";") { it.absolutePath }
        val projectCFlags = options.cFlags
        val projectCppFlags = mergeCppFlags(options.cppFlags, options.cppStandard)
        val projectLdFlags = options.ldFlags
        val projectLdLibs = options.ldLibs
        val projectStandardLibraries = CMakeLinkPolicy.resolveAndroidStandardLibraries(projectLdLibs)
        val combinedExtraCMakeArgs = options.extraCMakeArgs
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val packageEnvironment = buildCompilePackageEnvironment(packagePaths)

        val arch = AndroidSysrootManager.Companion.Arch.current()
        val androidAbi = PackageAbiCompatibility.currentAppAbi(
            nativeLibraryDir = nativeLibDir,
            supportedAbis = Build.SUPPORTED_ABIS,
        )
        val sysrootApiLevel = options.sysrootApiLevel
        val runtimeIdentity = NativeRuntimeIdentity(
            sysrootProfileId = options.sysrootProfileId,
            sysrootApiLevel = sysrootApiLevel,
        )
        val cmakeIdentity = CMakeConfigurationIdentity.create(
            runMode = "NATIVE",
            compilerType = options.compilerType.name,
            toolchainId = CMakeConfigurationIdentity.cacheToolchainId(options.toolchainId, isNative = true),
            androidAbi = androidAbi,
            sysrootProfileId = runtimeIdentity.cmakeProfileId,
            sysrootApiLevel = runtimeIdentity.sysrootApiLevel,
            cppStandard = options.cppStandard,
            cFlags = options.cFlags,
            cppFlags = options.cppFlags,
            ldFlags = options.ldFlags,
            ldLibs = options.ldLibs,
            cmakeArgs = combinedExtraCMakeArgs,
        )
        val apiLibDir = File(sysrootDir, "usr/lib/${arch.triple}/$sysrootApiLevel")
        if (!apiLibDir.isDirectory) {
            val message = buildString {
                append("Android sysroot is missing API-specific libraries. ")
                append("Expected directory not found: ")
                append(apiLibDir.absolutePath)
            }
            Timber.tag(TAG).w(message)
            return ConfigureResult.Error(message)
        }
        val targetTripleWithApi = "${arch.triple}$sysrootApiLevel"
        val cSysrootSplit = MakeCommandOverrides.splitCompileAndLinkFlags(
            sysrootManager.getCompilerFlags(
                apiLevel = sysrootApiLevel,
                arch = arch,
                isCpp = false,
                profileId = options.sysrootProfileId
            )
        )
        val cxxSysrootSplit = MakeCommandOverrides.splitCompileAndLinkFlags(
            sysrootManager.getCompilerFlags(
                apiLevel = sysrootApiLevel,
                arch = arch,
                isCpp = true,
                profileId = options.sysrootProfileId
            )
        )
        val cCompileFlags = cSysrootSplit.compileFlags.joinToString(" ")
        val cxxCompileFlags = cxxSysrootSplit.compileFlags.joinToString(" ")
        val sysrootLinkFlags = cxxSysrootSplit.linkFlags.joinToString(" ")
        val linkerCompatibilityFlags = AndroidLinkerCompatibilityFlags
            .forTarget(arch = arch, apiLevel = sysrootApiLevel)
            .joinToString(" ")
        val preferLinker64 = NativeExecutableRunner.shouldPreferLinker64()
        val toolchainShimSet = if (preferLinker64) {
            shimManager.prepare(toolchainBinDir, SHIM_TOOL_NAMES)
        } else {
            null
        }
        val buildToolName = when (options.generator) {
            CMakeGenerator.UNIX_MAKEFILES -> "make"
            CMakeGenerator.NINJA -> "ninja"
        }
        val buildToolBinary = File(toolchainBinDir, buildToolName)
        val buildToolShimSet = if (preferLinker64) {
            toolchainShimSet
        } else {
            shimManager.prepareDirectShell(toolchainBinDir, setOf(buildToolName))
        }
        val buildToolProgram = resolveBuildToolProgram(
            toolName = buildToolName,
            realBinary = buildToolBinary,
            shimSet = buildToolShimSet,
            preferLinker64 = preferLinker64
        )
        val buildToolProgramSource = resolveBuildToolProgramSource(
            toolName = buildToolName,
            realBinary = buildToolBinary,
            selectedProgram = buildToolProgram,
            shimSet = buildToolShimSet,
            preferLinker64 = preferLinker64
        )
        val compilerExecutionFlags = buildCompilerExecutionFlags(
            compilerType = options.compilerType,
            preferLinker64 = preferLinker64
        ).joinToString(" ")
        val compilerCacheHints = if (preferLinker64) {
            buildAndroidCompilerCacheHints(arch)
        } else {
            emptyMap()
        }
        val linker64 = AndroidSystemLinker.resolve64BitPreferred()
        val cmakeCCompiler = resolveCMakeCompilerCommand(
            realCompilerPath = cCompiler,
            shimCompilerPath = toolchainShimSet?.shimPath("clang"),
            preferLinker64 = preferLinker64,
            linker64Path = linker64,
            useRecommendedTinaExec = options.useRecommendedTinaExec
        )
        val cmakeCxxCompiler = resolveCMakeCompilerCommand(
            realCompilerPath = cxxCompiler,
            shimCompilerPath = toolchainShimSet?.shimPath("clang++"),
            preferLinker64 = preferLinker64,
            linker64Path = linker64,
            useRecommendedTinaExec = options.useRecommendedTinaExec
        )
        val cmakeAr = resolveCMakeToolPath(
            toolchainBinDir = toolchainBinDir,
            shimSet = toolchainShimSet,
            candidates = listOf("llvm-ar", "ar"),
            preferLinker64 = preferLinker64
        )
        val cmakeRanlib = resolveCMakeToolPath(
            toolchainBinDir = toolchainBinDir,
            shimSet = toolchainShimSet,
            candidates = listOf("llvm-ranlib", "ranlib"),
            preferLinker64 = preferLinker64
        )
        Timber.tag(TAG).d(
            "Resolved native compilers: realC=%s, realCxx=%s, cmakeC=%s, cmakeCArg1=%s, cmakeCxx=%s, cmakeCxxArg1=%s, cmakeAr=%s, cmakeRanlib=%s",
            cCompiler,
            cxxCompiler,
            cmakeCCompiler.compiler,
            cmakeCCompiler.arg1 ?: "<none>",
            cmakeCxxCompiler.compiler,
            cmakeCxxCompiler.arg1 ?: "<none>",
            cmakeAr ?: "<none>",
            cmakeRanlib ?: "<none>"
        )
        Timber.tag(TAG).d(
            DiagnosticLogFormatter.format(
                prefix = "Resolved build tool",
                "generator" to options.generator.cmakeValue,
                "buildTool" to buildToolName,
                "preferLinker64" to preferLinker64,
                "buildToolSource" to buildToolProgramSource,
                "buildToolReal" to buildToolBinary.absolutePath,
                "buildToolSelected" to (buildToolProgram?.absolutePath ?: "<missing>"),
                "buildToolShim" to (toolchainShimSet?.shimPath(buildToolName) ?: "<none>")
            )
        )

        val command = buildList {
            add(cmakeBinary.absolutePath)
            // 我们会注入一批内部用的 -D 缓存项；部分 CXX-only 项目不会消费所有项，
            // 这里关闭 unused-cli 噪声，避免“重新配置缓存”被误判为异常。
            add("--no-warn-unused-cli")
            add("-S")
            add(projectDir.absolutePath)
            add("-B")
            add(buildDir.absolutePath)
            add("-DCMAKE_BUILD_TYPE=${options.buildType.cmakeValue}")
            add("-G")
            add(options.generator.cmakeValue)
            // 显式指定系统版本，避免 CMake 在 Android 主机上回退读取 $PREFIX/include/android/api-level.h
            // 导致出现 "/include/android/api-level.h" 路径错误。
            add("-DCMAKE_SYSTEM_VERSION=$sysrootApiLevel")
            // Registry 原生包通过 lib/<ANDROID_ABI> 选择当前 APK 对应的库目录。
            add(buildAndroidAbiCMakeArgument(androidAbi))
            add("-DCMAKE_C_COMPILER=${cmakeCCompiler.compiler}")
            cmakeCCompiler.arg1?.let { add("-DCMAKE_C_COMPILER_ARG1=$it") }
            add("-DCMAKE_CXX_COMPILER=${cmakeCxxCompiler.compiler}")
            cmakeCxxCompiler.arg1?.let { add("-DCMAKE_CXX_COMPILER_ARG1=$it") }
            cmakeAr?.let { add("-DCMAKE_AR=$it") }
            cmakeRanlib?.let { add("-DCMAKE_RANLIB=$it") }
            add("-DCMAKE_C_COMPILER_TARGET=$targetTripleWithApi")
            add("-DCMAKE_CXX_COMPILER_TARGET=$targetTripleWithApi")
            add("-DCMAKE_SYSROOT=${sysrootDir.absolutePath}")

            // 统一复用 sysroot 管理器生成的 flags，确保 CMake 与单文件/Make 路径一致。
            if (sysrootDir.isDirectory) {
                val linkerFlags = mergeFlagSegments(
                    "--target=$targetTripleWithApi",
                    "--sysroot=${sysrootDir.absolutePath}",
                    sysrootLinkFlags,
                    linkerCompatibilityFlags,
                    "-fuse-ld=lld"
                )
                val projectLinkerFlags = mergeFlagSegments(linkerFlags, projectLdFlags)
                val executableLinkerFlags = CMakeLinkPolicy.resolveAndroidExecutableLinkerFlags(
                    projectLinkerFlags
                )
                val sharedLinkerFlags = CMakeLinkPolicy.resolveAndroidSharedLinkerFlags(
                    projectLinkerFlags
                )
                add("-DCMAKE_C_FLAGS=${mergeFlagSegments(cCompileFlags, compilerExecutionFlags, projectCFlags)}")
                add("-DCMAKE_CXX_FLAGS=${mergeFlagSegments(cxxCompileFlags, compilerExecutionFlags, projectCppFlags)}")
                add("-DCMAKE_EXE_LINKER_FLAGS=$executableLinkerFlags")
                add("-DCMAKE_SHARED_LINKER_FLAGS=$sharedLinkerFlags")
            }

            // 指定 CMAKE_MAKE_PROGRAM（Make/Ninja 共用），避免 CMake 内部回退到错误路径。
            if (buildToolProgram != null) {
                add("-DCMAKE_MAKE_PROGRAM=${buildToolProgram.absolutePath}")
            }

            // 注入已安装包的路径，让 find_package() 能找到第三方库
            addAll(buildCMakePackageRootArguments(packagePaths))
            if (packagePaths.includeDirs.isNotEmpty()) {
                add("-DCMAKE_INCLUDE_PATH=$packageIncludePath")
            }
            if (packagePaths.libDirs.isNotEmpty()) {
                add("-DCMAKE_LIBRARY_PATH=$packageLibraryPath")
            }
            if (projectStandardLibraries.isNotBlank()) {
                // 仅透传项目显式声明的链接库，避免已安装共享库被隐式链接到所有目标。
                add("-DCMAKE_C_STANDARD_LIBRARIES=$projectStandardLibraries")
                add("-DCMAKE_CXX_STANDARD_LIBRARIES=$projectStandardLibraries")
            }

            if (options.generateCompileCommands) {
                add("-DCMAKE_EXPORT_COMPILE_COMMANDS=ON")
            }

            // Android 原生宿主上的 CMake try_compile 在 linker64/shim 链路下会出现假阴性，
            // 这里显式注入已知 ABI/编译器可用性缓存，避免 configure 阶段被误判卡死。
            compilerCacheHints.forEach { (key, value) ->
                add("-D$key=$value")
            }

            addAll(combinedExtraCMakeArgs)
            cmakeIdentity.asCMakeCacheEntries().forEach { (key, value) ->
                add("-D$key:STRING=$value")
            }
        }

        Timber.tag(TAG).i("Configuring CMake project: ${projectDir.name}")
        Timber.tag(TAG).d("Command: ${command.joinToString(" ")}")

        val configureEnvironment = buildCMakeExtraEnvironment(
            packageEnvironment = packageEnvironment,
            traceToolchainShim = options.traceToolchainShim
        )
        val result = executeNativeCommand(
            command = command,
            workingDir = buildDir,
            timeout = timeoutConfig.getCMakeConfigTimeout(),
            toolchainId = options.toolchainId,
            extraEnvironment = configureEnvironment,
            onOutputLine = options.onProgress,
            useToolchainShimEnvironment = false,
            useRecommendedTinaExec = options.useRecommendedTinaExec
        )

        val finalResult = if (
            result.exitCode != 0 &&
            !preferLinker64 &&
            buildToolProgramSource == "shim" &&
            buildToolProgram != null &&
            buildToolProgram.absolutePath != buildToolBinary.absolutePath &&
            buildToolBinary.isFile
        ) {
            // 部分 Android 9 真机 ROM 可能禁止 app 私有目录里的 shell 脚本被 CMake 直接执行。
            // 只在 direct shim configure 失败时回退真实构建工具，避免影响正常成功路径。
            val retryCommand = command.map { argument ->
                if (argument.startsWith("-DCMAKE_MAKE_PROGRAM=")) {
                    "-DCMAKE_MAKE_PROGRAM=${buildToolBinary.absolutePath}"
                } else {
                    argument
                }
            }
            Timber.tag(TAG).w(
                "CMake configure with direct build-tool shim failed; retrying with real %s",
                buildToolName
            )
            options.onProgress?.invoke(
                "CMake configure with build-tool shim failed; retrying with real $buildToolName"
            )
            executeNativeCommand(
                command = retryCommand,
                workingDir = buildDir,
                timeout = timeoutConfig.getCMakeConfigTimeout(),
                toolchainId = options.toolchainId,
                extraEnvironment = configureEnvironment,
                onOutputLine = options.onProgress,
                useToolchainShimEnvironment = false,
                useRecommendedTinaExec = options.useRecommendedTinaExec
            )
        } else {
            result
        }

        if (finalResult.exitCode != 0) {
            val errorMessage = buildConfigureFailureMessage(
                primaryOutput = finalResult.output,
                buildDir = buildDir,
                exitCode = finalResult.exitCode,
                generator = options.generator.cmakeValue,
                buildToolName = buildToolName,
                buildToolSource = buildToolProgramSource,
                buildToolReal = buildToolBinary,
                buildToolSelected = buildToolProgram,
                buildToolShim = buildToolShimSet?.shimPath(buildToolName),
                preferLinker64 = preferLinker64
            )
            Timber.tag(TAG).w(errorMessage)
            return ConfigureResult.Error(errorMessage)
        }

        if (options.generator == CMakeGenerator.NINJA && !options.useRecommendedTinaExec) {
            patchNinjaCmakePaths(buildDir, options.toolchainId)
        }

        val compileCommandsFile = File(buildDir, COMPILE_COMMANDS_FILE)
        return ConfigureResult.Success(
            message = finalResult.output,
            compileCommandsPath = compileCommandsFile.takeIf { it.isFile && it.length() > 0L }
        )
    }

    /**
     * 构建 CMake 项目
     */
    suspend fun build(
        projectDir: File,
        buildDir: File,
        target: String? = null,
        options: Options = Options()
    ): BuildResult {
        val preferLinker64 = NativeExecutableRunner.shouldPreferLinker64()
        val disableTinaExecForShellShimBuild = shouldDisableTinaExecForCMakeBuild(
            generator = options.generator,
            preferLinker64 = preferLinker64,
            useRecommendedTinaExec = options.useRecommendedTinaExec
        )
        val buildUseRecommendedTinaExec =
            options.useRecommendedTinaExec && !disableTinaExecForShellShimBuild
        if (options.generator == CMakeGenerator.NINJA && !buildUseRecommendedTinaExec) {
            patchNinjaCmakePaths(buildDir, options.toolchainId)
        }

        val toolchainBinDir = toolchainManager.getBinDir(options.toolchainId)
        val command = buildCMakeBuildCommand(
            generator = options.generator,
            toolchainBinDir = toolchainBinDir,
            buildDir = buildDir,
            target = target,
            parallelJobs = options.parallelJobs
        )
        val buildMode = when (options.generator) {
            CMakeGenerator.NINJA -> "direct ninja"
            CMakeGenerator.UNIX_MAKEFILES -> "cmake --build"
        }
        if (disableTinaExecForShellShimBuild) {
            Timber.tag(TAG).w(
                "Disable tina-exec for CMake Ninja build because generated compiler commands already use shell shim/linker64."
            )
        }

        Timber.tag(TAG).i("Building CMake project in: ${buildDir.name} ($buildMode)")
        Timber.tag(TAG).d("Command: ${command.joinToString(" ")}")
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectDir)
        val packageEnvironment = buildCompilePackageEnvironment(packagePaths)
        val buildEnvironment = buildCMakeExtraEnvironment(
            packageEnvironment = packageEnvironment,
            traceToolchainShim = options.traceToolchainShim
        )

        val firstResult = executeNativeCommand(
            command = command,
            workingDir = buildDir,
            timeout = timeoutConfig.getCMakeBuildTimeout(),
            toolchainId = options.toolchainId,
            extraEnvironment = buildEnvironment,
            onOutputLine = options.onProgress,
            useRecommendedTinaExec = buildUseRecommendedTinaExec,
            useToolchainShimEnvironment = !buildUseRecommendedTinaExec
        )
        var result = firstResult
        if (
            options.generator == CMakeGenerator.NINJA &&
            shouldRetryAfterNinjaPermissionDenied(result.output)
        ) {
            Timber.tag(TAG).w(
                "Detected ninja permission-denied in nested tools, re-patching ninja files and retrying build once."
            )
            patchNinjaCmakePaths(buildDir, options.toolchainId)
            result = executeNativeCommand(
                command = command,
                workingDir = buildDir,
                timeout = timeoutConfig.getCMakeBuildTimeout(),
                toolchainId = options.toolchainId,
                extraEnvironment = buildEnvironment,
                onOutputLine = options.onProgress,
                useRecommendedTinaExec = buildUseRecommendedTinaExec,
                useToolchainShimEnvironment = !buildUseRecommendedTinaExec
            )
        }
        if (shouldRetryWithoutTinaExecAfterShimFailure(result.output, buildUseRecommendedTinaExec)) {
            Timber.tag(TAG).w(
                "Detected silent ninja failure through shell shim with tina-exec enabled; retrying build once with tina-exec disabled."
            )
            val retryResult = executeNativeCommand(
                command = command,
                workingDir = buildDir,
                timeout = timeoutConfig.getCMakeBuildTimeout(),
                toolchainId = options.toolchainId,
                extraEnvironment = buildEnvironment,
                onOutputLine = options.onProgress,
                useRecommendedTinaExec = false
            )
            result = mergeRetryOutput(
                first = result,
                retry = retryResult,
                retryTitle = "CMake build retry without tina-exec"
            )
        }

        val diagnostics = BuildDiagnosticParser.parse(result.output)

        return if (result.exitCode == 0) {
            BuildResult.Success(
                message = result.output,
                buildTimeMs = 0L,
                outputPath = null
            )
        } else {
            BuildResult.Error(
                rawOutput = result.output,
                diagnostics = diagnostics
            )
        }
    }

    private fun shouldRetryAfterNinjaPermissionDenied(output: String): Boolean {
        if (output.isBlank()) return false
        val text = output.lowercase()
        val hasPermissionDenied = "permission denied" in text
        val hasNinjaFailure = "ninja: build stopped" in text || "failed:" in text
        val hasShimToolPath = "toolchain-shims" in text &&
            ("/bin/llvm-ar" in text || "/bin/llvm-ranlib" in text || "/bin/cmake" in text)
        return hasPermissionDenied && hasNinjaFailure && hasShimToolPath
    }

    private fun mergeRetryOutput(
        first: CommandResult,
        retry: CommandResult,
        retryTitle: String
    ): CommandResult {
        val mergedOutput = buildString {
            if (first.output.isNotBlank()) {
                append(first.output.trimEnd())
                appendLine()
                appendLine()
            }
            appendLine("===== $retryTitle =====")
            appendLine(Strings.cmake_retry_without_tina_exec_notice.str())
            if (retry.output.isNotBlank()) {
                append(retry.output)
            }
        }
        return retry.copy(output = mergedOutput)
    }

    /**
     * 清理构建产物
     */
    suspend fun clean(buildDir: File, toolchainId: String? = null): CleanResult {
        val toolchainBinDir = toolchainManager.getBinDir(toolchainId)
        val generator = if (File(buildDir, "build.ninja").isFile) {
            CMakeGenerator.NINJA
        } else {
            CMakeGenerator.UNIX_MAKEFILES
        }
        val command = buildCMakeBuildCommand(
            generator = generator,
            toolchainBinDir = toolchainBinDir,
            buildDir = buildDir,
            target = "clean",
            parallelJobs = 1
        )

        Timber.tag(TAG).i("Cleaning CMake build directory: ${buildDir.name}")

        val result = executeNativeCommand(
            command = command,
            workingDir = buildDir,
            timeout = timeoutConfig.getCMakeCleanTimeout(),
            toolchainId = toolchainId,
            useRecommendedTinaExec = false
        )

        return if (result.exitCode == 0) {
            CleanResult.Success
        } else {
            CleanResult.Error(result.output)
        }
    }

    private fun patchNinjaCmakePaths(
        buildDir: File,
        toolchainId: String?
    ) {
        if (!NativeExecutableRunner.shouldPreferLinker64()) return

        val linker64 = AndroidSystemLinker.resolve64BitPreferred()
        val toolchainBinDir = toolchainManager.getBinDir(toolchainId)
        val cmakeBinary = File(toolchainBinDir, "cmake")
        val shimSet = shimManager.prepare(toolchainBinDir, SHIM_TOOL_NAMES)
        val cmakeShimCommand = shimSet?.shellCommandString("cmake")
        val cmakePaths = buildSet {
            val basePaths = buildList {
                add(cmakeBinary.absolutePath)
                runCatching { add(cmakeBinary.canonicalPath) }
            }
            basePaths
                .filter { it.isNotBlank() }
                .forEach { path ->
                    addAll(NinjaCmakePathPatcher.expandPathVariants(path))
                }
        }
        val shimScriptPaths = buildSet {
            val shimFiles = shimSet?.toolMap?.values.orEmpty()
            shimFiles.forEach { file ->
                val basePaths = buildList {
                    add(file.absolutePath)
                    runCatching { add(file.canonicalPath) }
                }
                basePaths
                    .filter { it.isNotBlank() }
                    .forEach { path ->
                        addAll(NinjaCmakePathPatcher.expandPathVariants(path))
                    }
            }
        }
        val binaryCommandMappings = buildMap {
            val toolMap = shimSet?.toolMap.orEmpty()
            toolMap.forEach { (tool, shimFile) ->
                if (tool == "cmake") return@forEach

                val realBinary = File(toolchainBinDir, tool)
                if (!realBinary.isFile) return@forEach

                val commandString = if (shimFile.isFile) {
                    "/system/bin/sh ${shimFile.absolutePath}"
                } else {
                    "$linker64 ${realBinary.absolutePath}"
                }
                addBinaryCommandMapping(
                    mappings = this,
                    tool = tool,
                    realBinaryPath = realBinary.absolutePath,
                    commandString = commandString,
                    canonicalPath = runCatching { realBinary.canonicalPath }.getOrNull()
                )
            }
        }

        val ninjaFiles = buildSet {
            val rootBuildNinja = File(buildDir, "build.ninja")
            if (rootBuildNinja.isFile) add(rootBuildNinja)

            val rulesNinja = File(buildDir, "CMakeFiles/rules.ninja")
            if (rulesNinja.isFile) add(rulesNinja)

            buildDir.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && it.extension == "ninja" }
                .forEach { add(it) }
        }

        if (ninjaFiles.isEmpty()) return

        var patchedFileCount = 0
        ninjaFiles.forEach { file ->
            val original = runCatching { file.readText() }.getOrNull() ?: return@forEach
            if (!shouldPatchNinjaFile(original, cmakePaths, shimScriptPaths, binaryCommandMappings.keys)) {
                return@forEach
            }

            val patched = NinjaCmakePathPatcher.patchContent(
                original = original,
                cmakePaths = cmakePaths,
                cmakeShimCommand = cmakeShimCommand,
                linker64 = linker64,
                shimScriptPaths = shimScriptPaths,
                binaryCommandMappings = binaryCommandMappings
            )

            if (patched != original) {
                runCatching { file.writeText(patched) }
                    .onSuccess { patchedFileCount++ }
                    .onFailure { err ->
                        Timber.tag(TAG).w(err, "Failed to patch ninja cmake paths: %s", file.absolutePath)
                    }
            }
        }

        if (patchedFileCount > 0) {
            val mode = if (cmakeShimCommand != null) "shim(linker64)" else "linker64"
            Timber.tag(TAG).i("Patched ninja cmake paths to use %s in %d file(s).", mode, patchedFileCount)
        }
    }

    private fun applyToolchainShimEnvironment(
        environment: MutableMap<String, String>,
        toolchainId: String?
    ) {
        if (!NativeExecutableRunner.shouldPreferLinker64()) return

        val toolchainBinDir = toolchainManager.getBinDir(toolchainId)
        val shimSet = shimManager.prepare(toolchainBinDir, SHIM_TOOL_NAMES) ?: return

        val currentPath = environment["PATH"].orEmpty()
        environment["PATH"] = if (currentPath.isBlank()) {
            shimSet.shimDir.absolutePath
        } else {
            "${shimSet.shimDir.absolutePath}:$currentPath"
        }

        putEnvIfPresent(environment, "CC", shimSet, listOf("clang"))
        putEnvIfPresent(environment, "CXX", shimSet, listOf("clang++"))
        putEnvIfPresent(environment, "LD", shimSet, listOf("ld.lld", "lld"))
        putEnvIfPresent(environment, "AR", shimSet, listOf("llvm-ar", "ar"), useShellCommand = false)
        putEnvIfPresent(environment, "RANLIB", shimSet, listOf("llvm-ranlib", "ranlib"), useShellCommand = false)
        putEnvIfPresent(environment, "STRIP", shimSet, listOf("llvm-strip", "strip"))
        putEnvIfPresent(environment, "NM", shimSet, listOf("llvm-nm", "nm"))
        putEnvIfPresent(environment, "OBJCOPY", shimSet, listOf("llvm-objcopy", "objcopy"))
    }

    private fun putEnvIfPresent(
        environment: MutableMap<String, String>,
        envName: String,
        shimSet: ToolchainLinker64ShimManager.ShimSet,
        candidates: List<String>,
        useShellCommand: Boolean = true
    ) {
        if (environment.containsKey(envName)) return
        val value = if (useShellCommand) {
            candidates.firstNotNullOfOrNull { shimSet.shellCommandString(it) }
        } else {
            candidates.firstNotNullOfOrNull { shimSet.shimPath(it) }
        } ?: return
        environment[envName] = value
    }

    private fun mergeFlagSegments(vararg values: String): String = values.asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" ")

    private fun mergeCppFlags(cppFlags: String, cppStandard: String?): String {
        val normalizedFlags = cppFlags.trim()
        val normalizedStd = cppStandard?.trim().orEmpty()
        if (normalizedStd.isBlank()) {
            return normalizedFlags
        }
        if (Regex("""(^|\s)-std=""").containsMatchIn(normalizedFlags)) {
            return normalizedFlags
        }
        return mergeFlagSegments("-std=$normalizedStd", normalizedFlags)
    }

    /**
     * 执行原生命令（通过 NativeExecutableRunner）
     */
    private suspend fun executeNativeCommand(
        command: List<String>,
        workingDir: File,
        timeout: Long,
        toolchainId: String? = null,
        extraEnvironment: Map<String, String> = emptyMap(),
        onOutputLine: ((String) -> Unit)? = null,
        useToolchainShimEnvironment: Boolean = true,
        useRecommendedTinaExec: Boolean = true
    ): CommandResult {
        val commandTempDir = BuildResourceCleaner.createCommandTempDir(appContext.cacheDir, "native-cmake")
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
                toolchainManager.getBinDir(toolchainId).absolutePath,
                tmpDir = commandTempDir.absolutePath,
                homeDir = appContext.filesDir.absolutePath
            )
            if (!useRecommendedTinaExec) {
                // 避免继承外层诊断页或其它链路残留的 tina-exec 环境。
                clearTinaExecEnvironment(environment())
            }
            if (useRecommendedTinaExec) {
                NativeExecutableRunner.applyRecommendedTinaExec(
                    environment = environment(),
                    context = appContext,
                    fullCommand = fullCommand
                )
            }
            if (useToolchainShimEnvironment) {
                applyToolchainShimEnvironment(environment(), toolchainId)
            }
            applyExtraEnvironment(environment(), extraEnvironment)
            redirectErrorStream(true)
        }
        Timber.tag(TAG).d(
            DiagnosticLogFormatter.format(
                prefix = "Native exec policy",
                "tinaExec" to if (useRecommendedTinaExec) "enabled" else "disabled",
                "shimEnv" to if (useToolchainShimEnvironment) "enabled" else "disabled",
                "extraEnvKeys" to extraEnvironment.keys
                    .sorted()
                    .joinToString(",")
                    .ifBlank { "<none>" }
            )
        )
        NativeExecutableRunner.logExecutionDiagnostics(
            tag = TAG,
            executable = executable,
            args = args,
            fullCommand = fullCommand,
            workingDir = workingDir,
            environment = processBuilder.environment().toMap(),
            toolchainBinDir = toolchainManager.getBinDir(toolchainId).absolutePath
        )

        val result = BuildProcessRunner.run(
            processBuilder = processBuilder,
            commandLabel = "native-cmake:${File(executable).name}",
            timeoutMs = timeout,
            onOutputLine = { line ->
                onOutputLine?.invoke(line)
                Timber.tag(TAG).v(line)
            },
        )

        val resultSummary = buildNativeCommandResultSummary(
            executable = executable,
            fullCommand = fullCommand,
            workingDir = workingDir,
            timeoutMs = timeout,
            durationMs = result.durationMs,
            finished = !result.timedOut,
            exitCode = result.exitCode,
            output = result.output
        )
        if (!result.timedOut && result.exitCode == 0) {
            Timber.tag(TAG).d(resultSummary)
        } else {
            Timber.tag(TAG).w(resultSummary)
        }
        if (result.timedOut) {
            Timber.tag(TAG).w("Native cmake command timed out after %d ms", timeout)
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
