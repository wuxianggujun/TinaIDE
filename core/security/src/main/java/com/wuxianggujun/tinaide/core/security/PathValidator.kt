package com.wuxianggujun.tinaide.core.security

import android.content.Context
import com.wuxianggujun.tinaide.core.exception.TinaIDEException
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File

/**
 * 文件路径验证器
 *
 * **设计目的**:
 * - 防止路径遍历攻击（Path Traversal）
 * - 确保文件操作只在允许的目录内进行
 * - 提供清晰的安全边界
 *
 * **安全策略**:
 * - 只允许访问特定的白名单目录
 * - 禁止使用 ".." 进行目录遍历
 * - 禁止访问系统敏感目录
 * - 验证路径规范化后的结果
 *
 * **使用示例**:
 * ```kotlin
 * val validator = PathValidator(context)
 *
 * try {
 *     validator.validatePath("/workspace/main.cpp")  // 通过
 *     validator.validatePath("../../../etc/passwd")   // 抛出异常
 * } catch (e: TinaIDEException.PathValidationException) {
 *     showError(e.userMessage)
 * }
 * ```
 */
class PathValidator(private val context: Context) {

    companion object {
        private const val TAG = "PathValidator"

        /**
         * PRoot guest 路径白名单前缀
         *
         * 允许访问的 PRoot guest 路径前缀列表
         */
        private val PROOT_GUEST_ALLOWED_PREFIXES = listOf(
            "/workspace", // 工作空间（主要工作目录）
            "/projects", // 私有项目目录（PRootManager 映射）
            "/tmp", // 临时文件
            "/home" // 用户目录
        )

        /**
         * 禁止访问的 PRoot guest 路径
         *
         * 即使在白名单目录下，这些路径也不允许访问
         */
        private val PROOT_GUEST_FORBIDDEN_PATHS = listOf(
            "/etc/passwd",
            "/etc/shadow",
            "/etc/sudoers"
        )

        /**
         * 允许作为 guest 工作目录的前缀。
         * 比文件读写白名单更宽：包含工具链/系统路径（/usr、/bin 等），用于 execute/startInteractive。
         */
        private val PROOT_GUEST_WORKDIR_ALLOWED_PREFIXES = listOf(
            "/workspace",
            "/projects",
            "/tmp",
            "/home",
            "/root",
            "/usr",
            "/bin",
            "/sbin",
            "/lib",
            "/lib64",
            "/opt",
            "/var",
            "/etc",
        )
    }

    /**
     * Android host 路径白名单前缀（延迟初始化）
     */
    private val hostAllowedPrefixes: List<String> by lazy {
        listOf(
            // 工作空间根目录
            ProjectPaths.getWorkspaceRoot(context),
            // 应用私有目录
            context.filesDir,
            context.cacheDir,
            // 外部存储（如果可用）
            context.getExternalFilesDir(null),
            // PRoot rootfs 目录
            File(context.filesDir, "rootfs")
        ).mapNotNull { it?.canonicalPathOrAbsolute() }
    }

    /**
     * 验证 PRoot guest 路径是否合法
     *
     * **检查规则**:
     * 1. 路径必须以允许的前缀开头
     * 2. 路径不能在禁止列表中
     * 3. 规范化后的路径不能逃出白名单目录
     *
     * **示例**:
     * ```kotlin
     * validateGuestPath("/workspace/main.cpp")      // 通过
     * validateGuestPath("/workspace/../etc/passwd") // 抛出异常
     * validateGuestPath("/etc/passwd")              // 抛出异常
     * ```
     *
     * @param path PRoot guest 路径
     * @throws TinaIDEException.PathValidationException 如果路径不合法
     */
    fun validateGuestPath(path: String) {
        // 规范化路径（移除 . 和 ..）
        val normalizedPath = normalizePath(path)

        // 检查是否在禁止列表中
        if (PROOT_GUEST_FORBIDDEN_PATHS.any { normalizedPath.startsWith(it) }) {
            throw TinaIDEException.PathValidationException(
                path = path,
                message = "Forbidden guest path: $path",
                userMessage = Strings.path_error_forbidden.strOr(context, path),
                recoverySuggestion = Strings.path_error_forbidden_suggestion.strOr(context)
            )
        }

        // 检查是否在白名单中
        val isAllowed = PROOT_GUEST_ALLOWED_PREFIXES.any { prefix ->
            normalizedPath.isSameOrChildOf(prefix)
        }

        if (!isAllowed) {
            throw TinaIDEException.PathValidationException(
                path = path,
                message = "Guest path not allowed: $path",
                userMessage = Strings.path_error_not_allowed_guest.strOr(context, path),
                recoverySuggestion = Strings.path_error_allowed_prefixes.strOr(
                    context,
                    PROOT_GUEST_ALLOWED_PREFIXES.joinToString(", ")
                )
            )
        }
    }

    /**
     * 验证 guest 工作目录（execute / startInteractive 用）。
     * 允许 `/` 与系统工具路径，但仍拦截 `..` 逃逸与敏感 forbidden 路径。
     */
    fun validateGuestWorkDir(path: String) {
        val normalizedPath = normalizePath(path)
        if (PROOT_GUEST_FORBIDDEN_PATHS.any { normalizedPath.startsWith(it) }) {
            throw TinaIDEException.PathValidationException(
                path = path,
                message = "Forbidden guest workDir: $path",
                userMessage = Strings.path_error_forbidden.strOr(context, path),
                recoverySuggestion = Strings.path_error_forbidden_suggestion.strOr(context)
            )
        }
        if (normalizedPath == "/") return
        val isAllowed = PROOT_GUEST_WORKDIR_ALLOWED_PREFIXES.any { prefix ->
            normalizedPath.isSameOrChildOf(prefix)
        }
        if (!isAllowed) {
            throw TinaIDEException.PathValidationException(
                path = path,
                message = "Guest workDir not allowed: $path",
                userMessage = Strings.path_error_not_allowed_guest.strOr(context, path),
                recoverySuggestion = Strings.path_error_allowed_prefixes.strOr(
                    context,
                    PROOT_GUEST_WORKDIR_ALLOWED_PREFIXES.joinToString(", ")
                )
            )
        }
    }

    fun isGuestWorkDirAllowed(path: String): Boolean = try {
        validateGuestWorkDir(path)
        true
    } catch (_: TinaIDEException.PathValidationException) {
        false
    }

    /**
     * 验证 Android host 路径是否合法
     *
     * **检查规则**:
     * 1. 路径必须在应用私有目录或工作空间内
     * 2. 规范化后的路径不能逃出白名单目录
     * 3. 不允许访问系统目录
     *
     * **示例**:
     * ```kotlin
     * validateHostPath("/data/data/com.app/files/workspace/main.cpp")  // 通过
     * validateHostPath("/data/data/com.app/files/../../../etc/passwd") // 抛出异常
     * validateHostPath("/system/bin/sh")                                // 抛出异常
     * ```
     *
     * @param path Android 文件系统路径
     * @throws TinaIDEException.PathValidationException 如果路径不合法
     */
    fun validateHostPath(path: String) {
        val file = File(path)

        // 获取规范化的绝对路径
        val canonicalPath = try {
            file.canonicalPath
        } catch (e: Exception) {
            throw TinaIDEException.PathValidationException(
                path = path,
                message = "Cannot resolve canonical path: ${e.message ?: ""}",
                userMessage = Strings.path_error_cannot_resolve.strOr(context, e.message ?: ""),
                recoverySuggestion = Strings.path_error_format_suggestion.strOr(context)
            )
        }

        // 检查是否在白名单中
        val isAllowed = hostAllowedPrefixes.any { prefix ->
            canonicalPath.isSameOrChildOf(prefix)
        }

        if (!isAllowed) {
            throw TinaIDEException.PathValidationException(
                path = path,
                message = "Host path not allowed: $path",
                userMessage = Strings.path_error_not_allowed_host.strOr(context, path),
                recoverySuggestion = Strings.path_error_host_suggestion.strOr(context)
            )
        }
    }

    /**
     * 检查 PRoot guest 路径是否合法（不抛出异常）
     *
     * @param path PRoot guest 路径
     * @return true 表示合法，false 表示不合法
     */
    fun isGuestPathAllowed(path: String): Boolean = try {
        validateGuestPath(path)
        true
    } catch (e: TinaIDEException.PathValidationException) {
        false
    }

    /**
     * 检查 Android host 路径是否合法（不抛出异常）
     *
     * @param path Android 文件系统路径
     * @return true 表示合法，false 表示不合法
     */
    fun isHostPathAllowed(path: String): Boolean = try {
        validateHostPath(path)
        true
    } catch (e: TinaIDEException.PathValidationException) {
        false
    }

    /**
     * 规范化路径（移除 . 和 ..）
     *
     * **处理规则**:
     * - 移除 "." 段
     * - 处理 ".." 段（向上一级）
     * - 保持绝对路径的前导 "/"
     *
     * **示例**:
     * ```
     * /workspace/./main.cpp          → /workspace/main.cpp
     * /workspace/subdir/../main.cpp  → /workspace/main.cpp
     * /workspace/../../etc/passwd    → /etc/passwd
     * ```
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    private fun normalizePath(path: String): String {
        val isAbsolute = path.startsWith("/")
        val segments = path.split("/").filter { it.isNotEmpty() }

        val normalized = mutableListOf<String>()

        for (segment in segments) {
            when (segment) {
                "." -> continue // 忽略当前目录
                ".." -> {
                    // 向上一级
                    if (normalized.isNotEmpty()) {
                        normalized.removeAt(normalized.size - 1)
                    }
                }
                else -> normalized.add(segment)
            }
        }

        return if (isAbsolute) {
            "/" + normalized.joinToString("/")
        } else {
            normalized.joinToString("/")
        }
    }

    /**
     * 获取允许的 guest 路径前缀列表
     *
     * @return 白名单前缀列表
     */
    fun getAllowedGuestPrefixes(): List<String> = PROOT_GUEST_ALLOWED_PREFIXES

    /**
     * 获取允许的 host 路径前缀列表
     *
     * @return 白名单前缀列表
     */
    fun getAllowedHostPrefixes(): List<String> = hostAllowedPrefixes

    private fun File.canonicalPathOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

    private fun String.isSameOrChildOf(prefix: String): Boolean {
        val cleanPrefix = prefix.trimEnd('/', File.separatorChar)
        return this == cleanPrefix ||
            startsWith("$cleanPrefix/") ||
            startsWith("$cleanPrefix${File.separator}")
    }
}
