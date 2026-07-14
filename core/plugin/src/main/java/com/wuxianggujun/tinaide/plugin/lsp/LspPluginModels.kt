package com.wuxianggujun.tinaide.plugin.lsp

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * LSP 服务器配置
 *
 * 定义 LSP 服务器的连接方式、支持的语言和文件类型
 */
@Serializable
data class LspServerConfig(
    /** 服务器唯一标识 */
    val id: String,
    /** 显示名称 */
    val name: String,
    /** 支持的语言 ID 列表（如 "java", "python"） */
    val languages: List<String>,
    /** 支持的文件扩展名列表（不含点，如 "py", "java"） */
    val fileExtensions: List<String>,
    /** 支持的文件名模式（glob 格式，如 "*.py", "pom.xml"） */
    val filePatterns: List<String>? = null,
    /** 运行时依赖配置 */
    val runtime: LspRuntimeConfig? = null,
    /** 服务器连接配置 */
    val server: LspServerConnectionConfig,
    /** LSP 初始化选项（传递给 initialize 请求） */
    val initializationOptions: JsonElement? = null,
    /** LSP 设置（传递给 workspace/didChangeConfiguration） */
    val settings: JsonElement? = null,
    /** 服务器能力配置 */
    val capabilities: LspCapabilitiesConfig? = null
)

/**
 * 运行时依赖配置
 *
 * 指定 LSP 服务器需要的运行时环境
 */
@Serializable
data class LspRuntimeConfig(
    /** 运行时类型: "jvm", "python", "node", "native" */
    val type: String,
    /** 最低版本要求 */
    val minVersion: String? = null,
    /** 运行时路径（可选，用于指定特定版本） */
    val path: String? = null
)

/**
 * LSP 服务器连接配置
 */
@Serializable
data class LspServerConnectionConfig(
    /** 连接类型: "stdio", "socket", "websocket" */
    val type: String,
    /** 启动命令（stdio 模式） */
    val command: String? = null,
    /** 命令参数（支持变量替换：${workspaceRoot}, ${userHome}） */
    val args: List<String>? = null,
    /** 环境变量 */
    val env: Map<String, String>? = null,
    /** 主机地址（socket/websocket 模式） */
    val host: String? = null,
    /** 端口号（socket/websocket 模式） */
    val port: Int? = null,
    /** WebSocket URL（websocket 模式） */
    val url: String? = null
)

/**
 * LSP 能力配置
 *
 * 声明服务器支持的功能，用于 UI 显示和功能启用
 */
@Serializable
data class LspCapabilitiesConfig(
    val completion: Boolean = true,
    val hover: Boolean = true,
    val signatureHelp: Boolean = true,
    val definition: Boolean = true,
    val references: Boolean = true,
    val documentHighlight: Boolean = true,
    val documentSymbol: Boolean = true,
    val codeAction: Boolean = true,
    val formatting: Boolean = true,
    val rename: Boolean = true,
    val inlayHints: Boolean = false
)

/**
 * 工具链配置
 *
 * 定义 LSP 服务器依赖的工具链及其安装方式
 */
@Serializable
data class LspToolchainConfig(
    /** 工具链唯一标识 */
    val id: String,
    /** 显示名称 */
    val name: String,
    /** 安装类型: "system", "download", "pip", "npm" */
    val type: String,
    /** 包名列表（system/pip/npm 模式） */
    val packages: List<String>? = null,
    /** 按 guest 包管理器覆盖系统包名，例如 apk/apt/pacman/dnf */
    val packagesByManager: Map<String, List<String>>? = null,
    /** 下载 URL（download 模式） */
    val url: String? = null,
    /** SHA256 校验和（download 模式） */
    val sha256: String? = null,
    /** 解压目标路径（download 模式，相对于 rootfs） */
    val extractTo: String? = null,
    /** 是否必需（false 表示可选依赖） */
    val required: Boolean = true,
    /** 验证命令（用于检查是否已安装） */
    val verifyCommand: String? = null,
    /** 验证输出匹配模式（正则表达式） */
    val verifyPattern: String? = null,
    /** 包版本降级列表（用于支持 name=version 的包管理器，按优先级排序） */
    val fallbackVersions: List<String>? = null
)

/**
 * LSP 工具链安装环境状态。
 */
data class LspToolchainEnvironmentStatus(
    val linuxAvailable: Boolean,
    val packageManagerName: String = "unknown",
    val systemPackageManagerAvailable: Boolean = false,
) {
    fun canInstall(toolchains: List<LspToolchainConfig>): Boolean {
        if (!linuxAvailable) return false
        val requiresSystemPackages = toolchains.any { toolchain ->
            toolchain.type.trim().equals("system", ignoreCase = true)
        }
        return !requiresSystemPackages || systemPackageManagerAvailable
    }
}

/**
 * LSP 插件信息
 *
 * 聚合插件的 LSP 相关配置
 */
data class LspPluginInfo(
    /** 插件 ID */
    val pluginId: String,
    /** 插件名称 */
    val pluginName: String,
    /** 插件版本 */
    val pluginVersion: String,
    /** 插件目录 */
    val directory: File,
    /** LSP 服务器配置列表 */
    val serverConfigs: List<LspServerConfig>,
    /** 工具链配置列表 */
    val toolchainConfigs: List<LspToolchainConfig>,
    /** 激活事件列表 */
    val activationEvents: List<String>
) {
    fun supportsLanguageActivation(languageId: String): Boolean {
        if (activationEvents.isEmpty()) return true
        return activationEvents.any { event ->
            event.equals("onLanguage:$languageId", ignoreCase = true)
        }
    }
}

/**
 * LSP 插件安装状态
 */
data class LspPluginInstallState(
    /** 插件 ID */
    val pluginId: String,
    /** 各工具链的安装状态 */
    val toolchainStates: Map<String, ToolchainInstallState>,
    /** 服务器是否就绪 */
    val serverReady: Boolean,
    /** 最后一次错误信息 */
    val lastError: String? = null
)

/**
 * LSP 插件启动前就绪诊断。
 */
data class LspPluginReadinessDiagnostic(
    val ready: Boolean,
    val missingRequiredToolchains: List<String> = emptyList(),
    val failedRequiredToolchains: List<String> = emptyList(),
    val lastError: String? = null,
)

/**
 * 工具链安装状态
 */
enum class ToolchainInstallState {
    /** 未安装 */
    NOT_INSTALLED,

    /** 安装中 */
    INSTALLING,

    /** 已安装 */
    INSTALLED,

    /** 安装失败 */
    FAILED
}

/**
 * LSP 安装进度
 */
data class LspInstallProgress(
    /** 当前阶段描述 */
    val phase: String,
    /** 进度（0.0 - 1.0） */
    val progress: Float,
    /** 当前工具链 ID */
    val toolchainId: String? = null,
    /** 详细消息 */
    val message: String? = null
)
