package com.wuxianggujun.tinaide.core.compile.cmake

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.BuildDiagnostic
import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticParser
import com.wuxianggujun.tinaide.core.compile.BuildResult
import com.wuxianggujun.tinaide.core.compile.CMakeConfigurationIdentity
import com.wuxianggujun.tinaide.core.compile.CleanResult
import com.wuxianggujun.tinaide.core.compile.CompileTimeoutConfig
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.MakeCommandOverrides
import com.wuxianggujun.tinaide.core.compile.NativeRuntimeIdentity
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner.shellQuotePosix
import java.io.File

/**
 * CMake 构建执行器
 *
 * **功能**:
 * - 封装 CMake 配置、生成、构建流程
 * - 使用可配置的超时值（通过 CompileTimeoutConfig）
 * - 支持多种编译器类型（Clang, GCC）
 *
 * **超时配置**:
 * - 所有超时值来自 CompileTimeoutConfig，用户可在设置中自定义
 * - 程序运行无超时限制（仅编译阶段有超时）
 * - 支持通过构造函数注入共享的 timeoutConfig 实例
 *
 * @param context Android 应用上下文
 * @param prootEnv PRoot 环境管理器
 * @param timeoutConfig 可选的超时配置（如果为 null，将创建新实例）
 */
class CMakeBuildExecutor(
    context: Context,
    private val prootEnv: PRootEnvironment,
    timeoutConfig: CompileTimeoutConfig? = null
) {
    private val appContext = context.applicationContext

    // 超时配置管理器（支持注入共享实例）
    private val timeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(context)
    private val toolchainPathResolver by lazy { ToolchainPathResolver(appContext) }
    companion object {
        private const val DEFAULT_BUILD_DIR = "build"
        private const val COMPILE_COMMANDS_FILE = "compile_commands.json"
    }

    /**
     * CMake 构建选项
     *
     * @property buildType 构建类型（Debug/Release 等）
     * @property generator 生成器类型（Unix Makefiles/Ninja）
     * @property parallelJobs 并行编译任务数，默认根据 CPU 核心数动态计算
     * @property extraCMakeArgs 额外的 CMake 参数
     * @property generateCompileCommands 是否生成 compile_commands.json
     * @property compilerType 编译器类型（Clang/GCC）
     * @property cCompilerPath 动态解析的 C 编译器路径（优先于 compilerType）
     * @property cxxCompilerPath 动态解析的 C++ 编译器路径（优先于 compilerType）
     */
    data class Options(
        val buildType: CMakeBuildType = CMakeBuildType.DEBUG,
        val generator: CMakeGenerator = CMakeGenerator.UNIX_MAKEFILES,
        val parallelJobs: Int = defaultParallelJobs(),
        val extraCMakeArgs: List<String> = emptyList(),
        val generateCompileCommands: Boolean = true,
        val compilerType: CompilerType = CompilerType.CLANG,
        val cCompilerPath: String? = null,
        val cxxCompilerPath: String? = null,
        val sysrootProfileId: String? = null,
        val sysrootApiLevel: Int = MakeCommandOverrides.DEFAULT_SYSROOT_API_LEVEL,
        val cFlags: String = "",
        val cppFlags: String = "",
        val ldFlags: String = "",
        val ldLibs: String = "",
        val cppStandard: String? = null
    ) {
        companion object {
            /**
             * 根据 CPU 核心数计算默认并行任务数
             * 范围限制在 1-8 之间
             */
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
        val result = prootEnv.executeShell(
            "cmake --version",
            timeout = timeoutConfig.getEnvCheckTimeout()
        )
        return result.isSuccess
    }

    /**
     * 获取 CMake 版本
     */
    suspend fun getCMakeVersion(): String? {
        val result = prootEnv.executeShell(
            "cmake --version",
            timeout = timeoutConfig.getEnvCheckTimeout()
        )
        if (!result.isSuccess) return null

        // 解析版本号：cmake version 3.26.4
        val versionLine = result.stdout.lines().firstOrNull() ?: return null
        val match = Regex("""cmake version (\d+\.\d+\.\d+)""").find(versionLine)
        return match?.groupValues?.get(1)
    }

    /**
     * 配置项目
     */
    suspend fun configure(
        projectRoot: File,
        buildDir: File = File(projectRoot, DEFAULT_BUILD_DIR),
        options: Options = Options(),
        progress: (String) -> Unit = {}
    ): ConfigureResult {
        // 创建构建目录
        if (!buildDir.exists()) {
            buildDir.mkdirs()
        }

        // 将宿主路径转换为 PRoot guest 路径
        // 这是必要的，因为在某些设备上 context.filesDir 返回 /data/user/0/... 而不是 /data/data/...
        // PRoot 的 --bind=/data:/data 可能无法正确处理符号链接
        val prootManager = prootEnv.getPRootManager()
        val guestProjectRoot = prootManager.toGuestPath(projectRoot.absolutePath)
        val guestBuildDir = prootManager.toGuestPath(buildDir.absolutePath)
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectRoot)
        val packagePrefixDirs = packagePaths.prefixDirs.map { prootManager.toGuestPath(it.absolutePath) }.distinct()
        val packageIncludeDirs = packagePaths.includeDirs.map { prootManager.toGuestPath(it.absolutePath) }.distinct()
        val packageLibDirs = packagePaths.libDirs.map { prootManager.toGuestPath(it.absolutePath) }.distinct()
        val projectCFlags = options.cFlags
        val projectCppFlags = mergeCppFlags(options.cppFlags, options.cppStandard)
        val projectLdFlags = options.ldFlags
        val projectLdLibs = options.ldLibs
        val projectStandardLibraries = CMakeLinkPolicy.resolveStandardLibraries(projectLdLibs)
        val runtimeIdentity = NativeRuntimeIdentity(
            sysrootProfileId = options.sysrootProfileId,
            sysrootApiLevel = options.sysrootApiLevel,
        )
        val combinedExtraCMakeArgs = options.extraCMakeArgs
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val cmakeIdentity = CMakeConfigurationIdentity.create(
            runMode = "PROOT",
            compilerType = options.compilerType.name,
            toolchainId = CMakeConfigurationIdentity.cacheToolchainId(null, isNative = false),
            androidAbi = "",
            sysrootProfileId = runtimeIdentity.cmakeProfileId,
            sysrootApiLevel = runtimeIdentity.sysrootApiLevel,
            cppStandard = options.cppStandard,
            cFlags = options.cFlags,
            cppFlags = options.cppFlags,
            ldFlags = options.ldFlags,
            ldLibs = options.ldLibs,
            cmakeArgs = combinedExtraCMakeArgs,
        )
        val packageEnv = buildPackageEnvironment(packagePaths, prootManager)

        // 构建 CMake 命令（使用 guest 路径）
        val resolvedCCompiler = runCatching {
            options.cCompilerPath ?: toolchainPathResolver.getCCompiler(options.compilerType)
        }.getOrNull()

        val resolvedCxxCompiler = runCatching {
            options.cxxCompilerPath ?: toolchainPathResolver.getCppCompiler(options.compilerType)
        }.getOrNull()

        if (resolvedCCompiler.isNullOrBlank() || resolvedCxxCompiler.isNullOrBlank()) {
            return ConfigureResult.Error(
                when (options.compilerType) {
                    CompilerType.CUSTOM -> Strings.cmake_error_custom_compiler_path_invalid.strOr(appContext)
                    else -> Strings.cmake_error_resolve_compiler_failed.strOr(appContext, options.compilerType.getDisplayName(appContext))
                }
            )
        }

        val cmakeCommand = buildString {
            append("cmake")
            append(" --no-warn-unused-cli")
            append(" -S $guestProjectRoot")
            append(" -B $guestBuildDir")
            append(" -G \"${options.generator.cmakeValue}\"")
            // 设置编译器（只允许来自路径解析器或显式传入的路径）
            append(" -DCMAKE_C_COMPILER=$resolvedCCompiler")
            append(" -DCMAKE_CXX_COMPILER=$resolvedCxxCompiler")
            if (packagePrefixDirs.isNotEmpty()) {
                append(" -DCMAKE_PREFIX_PATH=${shellQuotePosix(packagePrefixDirs.joinToString(";"))}")
            }
            if (packageIncludeDirs.isNotEmpty()) {
                append(" -DCMAKE_INCLUDE_PATH=${shellQuotePosix(packageIncludeDirs.joinToString(";"))}")
            }
            if (packageLibDirs.isNotEmpty()) {
                append(" -DCMAKE_LIBRARY_PATH=${shellQuotePosix(packageLibDirs.joinToString(";"))}")
            }
            if (projectCFlags.isNotBlank()) {
                append(" -DCMAKE_C_FLAGS=${shellQuotePosix(projectCFlags)}")
            }
            if (projectCppFlags.isNotBlank()) {
                append(" -DCMAKE_CXX_FLAGS=${shellQuotePosix(projectCppFlags)}")
            }
            if (projectLdFlags.isNotBlank()) {
                append(" -DCMAKE_EXE_LINKER_FLAGS=${shellQuotePosix(projectLdFlags)}")
                append(" -DCMAKE_SHARED_LINKER_FLAGS=${shellQuotePosix(projectLdFlags)}")
            }
            if (projectStandardLibraries.isNotBlank()) {
                // 仅透传项目显式声明的链接库，避免已安装共享库被隐式链接到所有目标。
                append(" -DCMAKE_C_STANDARD_LIBRARIES=${shellQuotePosix(projectStandardLibraries)}")
                append(" -DCMAKE_CXX_STANDARD_LIBRARIES=${shellQuotePosix(projectStandardLibraries)}")
            }

            if (options.generateCompileCommands) {
                append(" -DCMAKE_EXPORT_COMPILE_COMMANDS=ON")
            }
            combinedExtraCMakeArgs.forEach { arg ->
                append(" $arg")
            }
            cmakeIdentity.asCMakeCacheEntries().forEach { (key, value) ->
                append(" -D$key:STRING=${shellQuotePosix(value)}")
            }
            // The run configuration owns this value, so it must win over legacy project arguments.
            append(" -DCMAKE_BUILD_TYPE=${options.buildType.cmakeValue}")
        }

        progress(Strings.cmake_progress_configuring.strOr(appContext))
        val result = prootEnv.executeShellWithEnv(
            command = cmakeCommand,
            env = packageEnv,
            timeout = timeoutConfig.getCMakeConfigTimeout(),
            workDir = guestBuildDir
        )

        return if (result.isSuccess) {
            val compileCommandsFile = File(buildDir, COMPILE_COMMANDS_FILE)
            ConfigureResult.Success(
                message = Strings.cmake_config_success.strOr(appContext),
                compileCommandsPath = if (compileCommandsFile.exists()) compileCommandsFile else null
            )
        } else {
            val errorMsg = result.stderr.ifBlank { result.stdout }
            ConfigureResult.Error(errorMsg)
        }
    }

    /**
     * 构建项目
     */
    suspend fun build(
        projectRoot: File,
        buildDir: File,
        target: String? = null,
        parallelJobs: Int = 4,
        progress: (String) -> Unit = {}
    ): BuildResult {
        // 将宿主路径转换为 PRoot guest 路径
        val prootManager = prootEnv.getPRootManager()
        val guestBuildDir = prootManager.toGuestPath(buildDir.absolutePath)

        val buildCommand = buildString {
            append("cmake --build $guestBuildDir")
            if (target != null) {
                append(" --target $target")
            }
            append(" -j$parallelJobs")
        }
        val packageEnv = buildPackageEnvironment(
            packagePaths = InstalledPackagePathResolver.resolve(appContext, projectRoot),
            prootManager = prootManager
        )

        progress(Strings.cmake_progress_building.strOr(appContext))
        val startTime = System.currentTimeMillis()
        val result = prootEnv.executeShellWithEnv(
            command = buildCommand,
            env = packageEnv,
            timeout = timeoutConfig.getCMakeBuildTimeout(),
            workDir = guestBuildDir
        )
        val buildTime = System.currentTimeMillis() - startTime

        return if (result.isSuccess) {
            BuildResult.Success(
                message = Strings.cmake_build_success.strOr(appContext),
                buildTimeMs = buildTime,
                outputPath = findBuildOutput(buildDir, target)
            )
        } else {
            val output = result.stderr.ifBlank { result.stdout }
            BuildResult.Error(
                rawOutput = output,
                diagnostics = parseDiagnostics(output)
            )
        }
    }

    /**
     * 清理构建
     */
    suspend fun clean(buildDir: File): CleanResult {
        // 将宿主路径转换为 PRoot guest 路径
        val prootManager = prootEnv.getPRootManager()
        val guestBuildDir = prootManager.toGuestPath(buildDir.absolutePath)

        val result = prootEnv.executeShell(
            "cmake --build $guestBuildDir --target clean",
            timeout = timeoutConfig.getCMakeCleanTimeout()
        )
        return if (result.isSuccess) {
            CleanResult.Success
        } else {
            CleanResult.Error(result.stderr.ifBlank { result.stdout })
        }
    }

    /**
     * 获取所有目标（通过 cmake --build --target help）
     */
    suspend fun getTargetsFromBuildSystem(buildDir: File): List<String> {
        // 将宿主路径转换为 PRoot guest 路径
        val prootManager = prootEnv.getPRootManager()
        val guestBuildDir = prootManager.toGuestPath(buildDir.absolutePath)

        val result = prootEnv.executeShell(
            "cmake --build $guestBuildDir --target help",
            timeout = timeoutConfig.getCMakeHelpTimeout()
        )

        if (!result.isSuccess) return emptyList()

        return parseTargetsFromHelp(result.stdout)
    }

    /**
     * 查找构建产物
     *
     * **查找策略**（按优先级）：
     * 1. 直接在构建目录下查找
     * 2. 检查常见的子目录（bin, src, Debug, Release 等）
     * 3. 回退到遍历整个构建目录
     *
     * 这种策略可以在大多数情况下避免全目录遍历，提高查找速度。
     *
     * @param buildDir 构建目录
     * @param target 目标名称
     * @return 可执行文件的绝对路径，如果未找到则返回 null
     */
    private fun findBuildOutput(buildDir: File, target: String?): String? {
        if (target == null) return null

        // 策略1：直接在构建目录下查找
        val directPath = File(buildDir, target)
        if (directPath.exists() && directPath.isFile && directPath.canExecute()) {
            return directPath.absolutePath
        }

        // 策略2：检查常见的子目录
        val commonSubdirs = listOf(
            "", // 根目录
            "bin", // 常见的可执行文件目录
            "src", // 源码目录（有时编译产物在这里）
            "Debug", // MSVC/CLion 风格
            "Release", // MSVC/CLion 风格
            "build", // 嵌套构建目录
            "CMakeFiles" // CMake 内部目录（不太常见但有可能）
        )

        for (subdir in commonSubdirs) {
            val searchDir = if (subdir.isEmpty()) buildDir else File(buildDir, subdir)
            if (!searchDir.exists() || !searchDir.isDirectory) continue

            val candidate = File(searchDir, target)
            if (candidate.exists() && candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }

        // 策略3：回退到遍历整个构建目录（但限制深度以避免过长时间）
        val executable = buildDir.walkTopDown()
            .maxDepth(5) // 限制遍历深度
            .filter { it.isFile && it.canExecute() && it.nameWithoutExtension == target }
            .firstOrNull()

        return executable?.absolutePath ?: findFallbackSharedLibraryOutput(buildDir)
    }

    private fun findFallbackSharedLibraryOutput(buildDir: File): String? {
        if (!buildDir.isDirectory) return null

        val preferred = buildDir.walkTopDown()
            .maxDepth(6)
            .firstOrNull { file ->
                file.isFile &&
                    file.name == "libmain.so" &&
                    !file.absolutePath.contains("${File.separator}CMakeFiles${File.separator}")
            }
        if (preferred != null) return preferred.absolutePath

        return buildDir.walkTopDown()
            .maxDepth(6)
            .filter { file ->
                file.isFile &&
                    file.extension == "so" &&
                    !file.absolutePath.contains("${File.separator}CMakeFiles${File.separator}")
            }
            .maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    /**
     * 解析 cmake --build --target help 输出
     */
    private fun parseTargetsFromHelp(output: String): List<String> = output.lines()
        .filter { it.startsWith("...") }
        .map { it.removePrefix("...").trim() }
        .filter { it.isNotBlank() }

    /**
     * 解析编译诊断信息
     */
    private fun parseDiagnostics(output: String): List<BuildDiagnostic> = BuildDiagnosticParser.parse(output)

    private fun buildPackageEnvironment(
        packagePaths: InstalledPackagePathResolver.PackagePaths,
        prootManager: com.wuxianggujun.tinaide.core.proot.PRootManager
    ): Map<String, String> {
        if (packagePaths.isEmpty) return emptyMap()
        val env = linkedMapOf<String, String>()
        putPathIfNotBlank(
            env,
            "CPATH",
            packagePaths.includeDirs.map { prootManager.toGuestPath(it.absolutePath) }
        )
        putPathIfNotBlank(
            env,
            "LIBRARY_PATH",
            packagePaths.libDirs.map { prootManager.toGuestPath(it.absolutePath) }
        )
        putPathIfNotBlank(
            env,
            "PKG_CONFIG_PATH",
            packagePaths.pkgConfigDirs.map { prootManager.toGuestPath(it.absolutePath) }
        )
        return env
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
}
