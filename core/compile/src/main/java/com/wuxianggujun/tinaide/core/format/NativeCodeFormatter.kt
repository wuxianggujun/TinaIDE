package com.wuxianggujun.tinaide.core.format

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import java.io.File
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * 原生代码格式化服务（不使用 PRoot）
 *
 * 通过 linker64 + LD_LIBRARY_PATH 直接在 Android 上运行 clang-format。
 *
 * 优势：
 * - 零 PRoot 开销，格式化速度提升 3-5x
 * - 启动延迟降低 ~500ms
 * - 更简单的路径处理（无需 host/guest 转换）
 *
 * 要求：
 * - 工具链已通过 AndroidNativeToolchainManager 安装
 * - clang-format 二进制可用
 */
class NativeCodeFormatter(
    context: Context
) {
    private val appContext = context.applicationContext
    private val toolchainManager = AndroidNativeToolchainManager(appContext)
    private val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
    private val configManager by lazy { ClangFormatConfigManager(appContext) }
    private val styleResolver = FormatStyleResolver()

    companion object {
        private const val TAG = "NativeCodeFormatter"
        private const val FORMAT_TIMEOUT = 30_000L
        private const val VERSION_CHECK_TIMEOUT = 10_000L
    }

    /**
     * 支持格式化的文件扩展名
     */
    private val supportedExtensions: Set<String> =
        CxxFileSupport.editorRelatedExtensions + setOf(
            "java", // Java
            "js",
            "ts", // JavaScript/TypeScript
            "json", // JSON
            "proto" // Protocol Buffers
        )

    // ========== 公开 API ==========

    /**
     * 检查文件是否支持格式化
     */
    fun isSupported(file: File): Boolean = file.extension.lowercase() in supportedExtensions

    /**
     * 检查文件是否支持格式化（通过扩展名）
     */
    fun isSupported(extension: String): Boolean = extension.lowercase() in supportedExtensions

    /**
     * 检查 clang-format 是否可用
     */
    suspend fun checkAvailability(): AvailabilityResult {
        return try {
            val clangFormatBinary = File(toolchainManager.getBinDir(), "clang-format")
            if (!clangFormatBinary.isFile) {
                Timber.tag(TAG).w("clang-format binary not found: ${clangFormatBinary.absolutePath}")
                return AvailabilityResult.NotAvailable(
                    path = clangFormatBinary.absolutePath,
                    reason = "Binary not found"
                )
            }

            val result = executeNativeCommand(
                command = listOf(clangFormatBinary.absolutePath, "--version"),
                workingDir = appContext.filesDir,
                timeout = VERSION_CHECK_TIMEOUT
            )

            if (result.exitCode == 0) {
                val version = result.output.lines().firstOrNull()?.trim()
                AvailabilityResult.Available(clangFormatBinary.absolutePath, version)
            } else {
                AvailabilityResult.NotAvailable(
                    path = clangFormatBinary.absolutePath,
                    reason = result.output.take(200)
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check clang-format availability")
            AvailabilityResult.NotAvailable(
                path = "unknown",
                reason = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * 检查 clang-format 是否可用（简化版本）
     */
    suspend fun isAvailable(): Boolean = checkAvailability() is AvailabilityResult.Available

    /**
     * 获取 clang-format 版本
     */
    suspend fun getVersion(): String? = when (val result = checkAvailability()) {
        is AvailabilityResult.Available -> result.version
        is AvailabilityResult.NotAvailable -> null
    }

    /**
     * 格式化代码内容
     *
     * @param content 要格式化的代码内容
     * @param fileName 文件名（用于推断语言类型）
     * @param style 格式化风格（默认自动检测）
     * @param options 额外的格式化选项
     * @return 格式化结果
     */
    suspend fun format(
        content: String,
        fileName: String,
        style: FormatStyle? = null,
        options: FormatOptions = FormatOptions()
    ): FormatResult {
        val effectiveStyle = style ?: resolveFormatStyle(fileName)
        val command = buildFormatCommand(fileName, effectiveStyle, options)

        // 从文件名提取工作目录
        val workDir = File(fileName).parentFile ?: appContext.filesDir

        val result = executeNativeCommand(
            command = command,
            workingDir = workDir,
            timeout = FORMAT_TIMEOUT,
            stdin = content
        )

        return if (result.exitCode == 0) {
            FormatResult.Success(result.output)
        } else {
            FormatResult.Error(
                message = result.output.ifBlank { Strings.format_failed_simple.strOr(appContext) },
                exitCode = result.exitCode
            )
        }
    }

    /**
     * 格式化文件（直接修改文件）
     *
     * @param filePath 文件路径（Android 主机路径）
     * @param style 格式化风格（默认自动检测）
     * @param options 额外的格式化选项
     * @param inPlace 是否直接修改文件
     * @return 格式化结果
     */
    suspend fun formatFile(
        filePath: String,
        style: FormatStyle? = null,
        options: FormatOptions = FormatOptions(),
        inPlace: Boolean = false
    ): FormatResult {
        val file = File(filePath)
        if (!file.exists()) {
            return FormatResult.Error("File not found: $filePath")
        }

        val effectiveStyle = style ?: resolveFormatStyle(filePath)
        val command = buildFormatCommand(filePath, effectiveStyle, options).toMutableList()

        if (inPlace) {
            command.add("-i")
        }
        command.add(filePath)

        val workDir = file.parentFile ?: appContext.filesDir

        val result = executeNativeCommand(
            command = command,
            workingDir = workDir,
            timeout = FORMAT_TIMEOUT
        )

        return if (result.exitCode == 0) {
            if (inPlace) {
                // inPlace 模式下，clang-format 直接修改文件，stdout 为空
                FormatResult.Success("")
            } else {
                FormatResult.Success(result.output)
            }
        } else {
            FormatResult.Error(
                message = result.output.ifBlank { Strings.format_failed_simple.strOr(appContext) },
                exitCode = result.exitCode
            )
        }
    }

    /**
     * 格式化选中的代码范围
     *
     * @param content 完整的代码内容
     * @param fileName 文件名
     * @param startLine 起始行（1-based）
     * @param endLine 结束行（1-based）
     * @param style 格式化风格（默认自动检测）
     * @return 格式化结果
     */
    suspend fun formatRange(
        content: String,
        fileName: String,
        startLine: Int,
        endLine: Int,
        style: FormatStyle? = null
    ): FormatResult {
        val effectiveStyle = style ?: resolveFormatStyle(fileName)
        val command = buildFormatCommand(fileName, effectiveStyle, FormatOptions()).toMutableList()
        command.add("--lines=$startLine:$endLine")

        val workDir = File(fileName).parentFile ?: appContext.filesDir

        val result = executeNativeCommand(
            command = command,
            workingDir = workDir,
            timeout = FORMAT_TIMEOUT,
            stdin = content
        )

        return if (result.exitCode == 0) {
            FormatResult.Success(result.output)
        } else {
            FormatResult.Error(
                message = result.output.ifBlank { Strings.format_failed_simple.strOr(appContext) },
                exitCode = result.exitCode
            )
        }
    }

    // ========== 风格解析 ==========

    /**
     * 根据文件路径确定要使用的格式化风格
     *
     * 优先级：
     * 1. 如果项目目录中存在 .clang-format 文件，使用 FormatStyle.FILE
     * 2. 否则使用用户在设置中选择的默认风格
     */
    fun resolveFormatStyle(filePath: String): FormatStyle = styleResolver.resolve(File(filePath))

    /**
     * 获取用户设置的默认格式化风格
     */
    fun getUserDefaultStyle(): FormatStyle = styleResolver.getUserDefaultStyle()

    /**
     * 检查指定目录或其父目录中是否存在 .clang-format 文件
     */
    fun hasClangFormatFile(directory: File?, maxDepth: Int = 10): Boolean =
        styleResolver.hasClangFormatFile(directory, maxDepth)

    // ========== 配置管理委托 ==========

    /**
     * 将内置配置文件部署到项目目录
     */
    fun deployConfigToProject(style: FormatStyle, projectDir: File, overwrite: Boolean = false): Boolean = configManager.deployConfig(style, projectDir, overwrite)

    /**
     * 获取内置配置文件的内容
     */
    fun getBuiltinConfigContent(style: FormatStyle): String? = configManager.readConfigContent(style)

    /**
     * 获取所有可用的内置配置
     */
    fun getAvailableConfigs(): List<ClangFormatConfigManager.ConfigInfo> = configManager.availableConfigs

    // ========== 私有方法 ==========

    /**
     * 构建格式化命令
     */
    private fun buildFormatCommand(
        fileName: String,
        style: FormatStyle,
        options: FormatOptions
    ): List<String> {
        val clangFormatBinary = File(toolchainManager.getBinDir(), "clang-format")

        return buildList {
            add(clangFormatBinary.absolutePath)

            // 风格设置
            add(style.toClangFormatArgument())

            // 假设文件名（用于推断语言）
            add("--assume-filename=$fileName")

            // 排序 include
            if (options.sortIncludes) {
                add("--sort-includes")
            }

            // 额外参数
            addAll(options.extraArgs)
        }
    }

    /**
     * 执行原生命令（通过 NativeExecutableRunner）
     */
    private suspend fun executeNativeCommand(
        command: List<String>,
        workingDir: File,
        timeout: Long,
        stdin: String? = null
    ): CommandResult = try {
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
                tmpDir = appContext.cacheDir.absolutePath,
                homeDir = appContext.filesDir.absolutePath
            )
            NativeExecutableRunner.applyRecommendedTinaExec(
                environment = environment(),
                context = appContext,
                fullCommand = fullCommand
            )
            redirectErrorStream(true)
        }

        val process = processBuilder.start()
        val output = StringBuilder()

        // 写入 stdin（如果有）
        if (stdin != null) {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(stdin)
            }
        }

        // 读取输出
        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                output.appendLine(line)
                Timber.tag(TAG).v(line)
            }
        }

        // 等待进程结束
        val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
        val exitCode = if (finished) {
            process.exitValue()
        } else {
            process.destroy()
            -1
        }

        CommandResult(
            exitCode = exitCode,
            output = output.toString()
        )
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to execute native command")
        CommandResult(
            exitCode = -1,
            output = "Exception: ${e.message}"
        )
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )
}
