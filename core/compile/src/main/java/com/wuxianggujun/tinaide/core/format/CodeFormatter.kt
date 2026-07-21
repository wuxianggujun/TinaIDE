package com.wuxianggujun.tinaide.core.format

import android.content.Context
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.proot.PRootManager
import java.io.File

/**
 * 代码格式化服务（支持原生和 PRoot 双模式）
 *
 * 根据配置选择执行模式：
 * - 原生模式：通过 linker64 直接运行 clang-format（推荐，性能更好）
 * - PRoot 模式：通过 PRoot 运行 clang-format（兼容性更好）
 *
 * 格式化风格优先级：
 * 1. 如果项目目录中存在 .clang-format 文件，使用该文件的配置
 * 2. 否则使用用户在设置中选择的默认风格（从内置配置文件加载）
 *
 * @param context Android Context，用于访问 assets
 * @param prootManager PRoot 进程管理器（仅 PRoot 模式需要）
 */
class CodeFormatter(
    private val context: Context,
    private val prootManager: PRootManager? = null,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
) {
    companion object {
        private const val TAG = "CodeFormatter"
    }

    // 根据配置选择执行器
    private val useNativeMode: Boolean
        get() = LinuxRunModePolicy.resolve(
            configuredMode = Prefs.clangFormatRunMode,
            linuxEnvironmentAvailable = linuxEnvironmentProvider.get().isAvailable()
        ) == LinuxRunModePolicy.RunMode.NATIVE

    // PRoot 执行器（懒加载）
    private val prootFormatter by lazy {
        val resolvedProotManager = prootManager
            ?: (linuxEnvironmentProvider.get() as? PRootEnvironment)
                ?.getPRootManager()
        PRootCodeFormatter(
            context,
            requireNotNull(resolvedProotManager) { "PRootManager is required for PRoot mode" }
        )
    }

    // 原生执行器（懒加载）
    private val nativeFormatter by lazy {
        NativeCodeFormatter(context)
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
    fun isSupported(file: File): Boolean = if (useNativeMode) {
        nativeFormatter.isSupported(file)
    } else {
        prootFormatter.isSupported(file)
    }

    /**
     * 检查文件是否支持格式化（通过扩展名）
     */
    fun isSupported(extension: String): Boolean = if (useNativeMode) {
        nativeFormatter.isSupported(extension)
    } else {
        prootFormatter.isSupported(extension)
    }

    /**
     * 检查 clang-format 是否可用
     *
     * @return 可用性检查结果，包含详细状态信息
     */
    suspend fun checkAvailability(): AvailabilityResult = if (useNativeMode) {
        nativeFormatter.checkAvailability()
    } else {
        prootFormatter.checkAvailability()
    }

    /**
     * 检查 clang-format 是否可用（简化版本）
     */
    suspend fun isAvailable(): Boolean = if (useNativeMode) {
        nativeFormatter.isAvailable()
    } else {
        prootFormatter.isAvailable()
    }

    /**
     * 获取 clang-format 版本
     */
    suspend fun getVersion(): String? = if (useNativeMode) {
        nativeFormatter.getVersion()
    } else {
        prootFormatter.getVersion()
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
    ): FormatResult = if (useNativeMode) {
        nativeFormatter.format(content, fileName, style, options)
    } else {
        prootFormatter.format(content, fileName, style, options)
    }

    /**
     * 格式化文件
     *
     * 注意：
     * - 原生模式：操作 Android 主机文件系统
     * - PRoot 模式：操作 PRoot guest 文件系统
     *
     * @param filePath 文件路径
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
    ): FormatResult = if (useNativeMode) {
        nativeFormatter.formatFile(filePath, style, options, inPlace)
    } else {
        prootFormatter.formatGuestFile(filePath, style, options, inPlace)
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
    ): FormatResult = if (useNativeMode) {
        nativeFormatter.formatRange(content, fileName, startLine, endLine, style)
    } else {
        prootFormatter.formatRange(content, fileName, startLine, endLine, style)
    }

    // ========== 风格解析 ==========

    /**
     * 根据文件路径确定要使用的格式化风格
     *
     * 优先级：
     * 1. 如果项目目录中存在 .clang-format 文件，使用 FormatStyle.FILE
     * 2. 否则使用用户在设置中选择的默认风格
     */
    fun resolveFormatStyle(filePath: String): FormatStyle = if (useNativeMode) {
        nativeFormatter.resolveFormatStyle(filePath)
    } else {
        prootFormatter.resolveFormatStyle(filePath)
    }

    /**
     * 获取用户设置的默认格式化风格
     */
    fun getUserDefaultStyle(): FormatStyle = if (useNativeMode) {
        nativeFormatter.getUserDefaultStyle()
    } else {
        prootFormatter.getUserDefaultStyle()
    }

    /**
     * 检查指定目录或其父目录中是否存在 .clang-format 文件
     */
    fun hasClangFormatFile(directory: File?, maxDepth: Int = 10): Boolean = if (useNativeMode) {
        nativeFormatter.hasClangFormatFile(directory, maxDepth)
    } else {
        prootFormatter.hasClangFormatFile(directory, maxDepth)
    }

    // ========== 配置管理委托 ==========

    /**
     * 将内置配置文件部署到项目目录
     */
    fun deployConfigToProject(style: FormatStyle, projectDir: File, overwrite: Boolean = false): Boolean = if (useNativeMode) {
        nativeFormatter.deployConfigToProject(style, projectDir, overwrite)
    } else {
        prootFormatter.deployConfigToProject(style, projectDir, overwrite)
    }

    /**
     * 获取内置配置文件的内容
     */
    fun getBuiltinConfigContent(style: FormatStyle): String? = if (useNativeMode) {
        nativeFormatter.getBuiltinConfigContent(style)
    } else {
        prootFormatter.getBuiltinConfigContent(style)
    }

    /**
     * 获取所有可用的内置配置
     */
    fun getAvailableConfigs(): List<ClangFormatConfigManager.ConfigInfo> = if (useNativeMode) {
        nativeFormatter.getAvailableConfigs()
    } else {
        prootFormatter.getAvailableConfigs()
    }
}

/**
 * clang-format 可用性检查结果
 */
sealed class AvailabilityResult {
    /**
     * clang-format 可用
     */
    data class Available(
        val path: String,
        val version: String?
    ) : AvailabilityResult()

    /**
     * clang-format 不可用
     */
    data class NotAvailable(
        val path: String,
        val reason: String
    ) : AvailabilityResult()
}

/**
 * 格式化选项
 */
data class FormatOptions(
    /** 是否排序 include */
    val sortIncludes: Boolean = false,
    /** 额外的命令行参数 */
    val extraArgs: List<String> = emptyList()
)

/**
 * 格式化结果
 */
sealed class FormatResult {
    /**
     * 格式化成功
     */
    data class Success(val formattedContent: String) : FormatResult()

    /**
     * 格式化失败
     */
    data class Error(
        val message: String,
        val exitCode: Int = -1
    ) : FormatResult()
}
