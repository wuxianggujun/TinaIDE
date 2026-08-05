package com.wuxianggujun.tinaide.core.format

import android.content.Context
import com.wuxianggujun.tinaide.core.exception.TinaIDEException
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.proot.PRootManager
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import com.wuxianggujun.tinaide.core.security.PathValidator
import java.io.File
import timber.log.Timber

/**
 * PRoot 代码格式化服务
 *
 * 通过 PRoot 调用 clang-format 进行代码格式化。
 * 支持 C/C++/Objective-C 等语言。
 *
 * 格式化风格优先级：
 * 1. 如果项目目录中存在 .clang-format 文件，使用该文件的配置
 * 2. 否则使用用户在设置中选择的默认风格（从内置配置文件加载）
 *
 * @param context Android Context，用于访问 assets
 * @param prootManager PRoot 进程管理器
 */
class PRootCodeFormatter(
    private val context: Context,
    private val prootManager: PRootManager,
    private val pathValidator: PathValidator = PathValidator(context),
) {
    companion object {
        private const val TAG = "PRootCodeFormatter"
        private const val FORMAT_TIMEOUT = 30_000L
        private const val VERSION_CHECK_TIMEOUT = 10_000L
    }

    private val configManager by lazy { ClangFormatConfigManager(context) }
    private val pathResolver by lazy { ToolchainPathResolver(context) }
    private val styleResolver = FormatStyleResolver()

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
     *
     * @return 可用性检查结果，包含详细状态信息
     */
    suspend fun checkAvailability(): AvailabilityResult {
        val clangFormatPath = pathResolver.getClangFormat()

        val result = prootManager.execute(
            command = listOf(clangFormatPath, "--version"),
            workDir = "/",
            timeout = VERSION_CHECK_TIMEOUT
        )

        return if (result.isSuccess) {
            val version = result.stdout.lines().firstOrNull()?.trim()
            AvailabilityResult.Available(clangFormatPath, version)
        } else {
            Timber.tag(TAG).w("clang-format check failed: ${result.combinedOutput}")
            AvailabilityResult.NotAvailable(
                path = clangFormatPath,
                reason = result.stderr.ifBlank { result.combinedOutput }.take(200)
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
     * @param fileName 文件名（用于推断语言类型，应为 guest 路径）
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
        validateGuestPathOrError(fileName)?.let { return it }

        val effectiveStyle = style ?: resolveFormatStyle(fileName)
        val command = buildFormatCommand(fileName, effectiveStyle, options)

        // 从 guest 路径提取工作目录
        val workDir = extractGuestParentDir(fileName)

        val result = prootManager.execute(
            command = command,
            workDir = workDir,
            timeout = FORMAT_TIMEOUT,
            stdin = content
        )

        return if (result.isSuccess) {
            FormatResult.Success(result.stdout)
        } else {
            FormatResult.Error(
                message = result.stderr.ifBlank { Strings.format_failed_simple.strOr(context) },
                exitCode = result.exitCode
            )
        }
    }

    /**
     * 格式化文件（通过 PRoot 内部路径）
     *
     * 注意：此方法操作的是 PRoot guest 文件系统中的文件。
     * 如果需要格式化 Android 主机上的文件，请使用 format() 方法传入文件内容。
     *
     * @param guestFilePath PRoot guest 文件系统中的文件路径
     * @param style 格式化风格（默认自动检测）
     * @param options 额外的格式化选项
     * @param inPlace 是否直接修改文件
     * @return 格式化结果
     */
    suspend fun formatGuestFile(
        guestFilePath: String,
        style: FormatStyle? = null,
        options: FormatOptions = FormatOptions(),
        inPlace: Boolean = false
    ): FormatResult {
        validateGuestPathOrError(guestFilePath)?.let { return it }

        val effectiveStyle = style ?: resolveFormatStyle(guestFilePath)
        val command = buildFormatCommand(guestFilePath, effectiveStyle, options).toMutableList()

        if (inPlace) {
            command.add("-i")
        }
        command.add(guestFilePath)

        val workDir = extractGuestParentDir(guestFilePath)

        val result = prootManager.execute(
            command = command,
            workDir = workDir,
            timeout = FORMAT_TIMEOUT
        )

        return if (result.isSuccess) {
            if (inPlace) {
                // inPlace 模式下，clang-format 直接修改文件，stdout 为空
                // 返回空字符串表示成功，调用方需要重新读取文件
                FormatResult.Success("")
            } else {
                FormatResult.Success(result.stdout)
            }
        } else {
            FormatResult.Error(
                message = result.stderr.ifBlank { Strings.format_failed_simple.strOr(context) },
                exitCode = result.exitCode
            )
        }
    }

    /**
     * 格式化选中的代码范围
     *
     * @param content 完整的代码内容
     * @param fileName 文件名（guest 路径）
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
        validateGuestPathOrError(fileName)?.let { return it }

        val effectiveStyle = style ?: resolveFormatStyle(fileName)
        val command = buildFormatCommand(fileName, effectiveStyle, FormatOptions()).toMutableList()
        command.add("--lines=$startLine:$endLine")

        val workDir = extractGuestParentDir(fileName)

        val result = prootManager.execute(
            command = command,
            workDir = workDir,
            timeout = FORMAT_TIMEOUT,
            stdin = content
        )

        return if (result.isSuccess) {
            FormatResult.Success(result.stdout)
        } else {
            FormatResult.Error(
                message = result.stderr.ifBlank { Strings.format_failed_simple.strOr(context) },
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
    fun hasClangFormatFile(directory: File?, maxDepth: Int = Int.MAX_VALUE): Boolean =
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

    private fun validateGuestPathOrError(guestPath: String): FormatResult.Error? = try {
        pathValidator.validateGuestPath(guestPath)
        null
    } catch (e: TinaIDEException.PathValidationException) {
        Timber.tag(TAG).w("Rejected guest path for format: %s", guestPath)
        FormatResult.Error(
            message = e.userMessage.ifBlank { e.message ?: Strings.format_failed_simple.strOr(context) },
            exitCode = -1,
        )
    }

    /**
     * 从 guest 路径提取父目录
     */
    private fun extractGuestParentDir(guestPath: String): String {
        // guest 路径使用 Unix 风格的 /
        val lastSlash = guestPath.lastIndexOf('/')
        return if (lastSlash > 0) {
            guestPath.substring(0, lastSlash)
        } else {
            "/workspace"
        }
    }

    /**
     * 构建格式化命令
     */
    private fun buildFormatCommand(
        fileName: String,
        style: FormatStyle,
        options: FormatOptions
    ): List<String> = buildList {
        add(pathResolver.getClangFormat())

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
