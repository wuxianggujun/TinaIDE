package com.wuxianggujun.tinaide.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * TinaIDE 统一路径管理
 *
 * 所有应用内的文件路径都应该通过此类获取，避免各处代码分散获取路径。
 *
 * 目录架构说明：
 *
 * 1. 私有目录 (context.filesDir)
 *    路径：/data/data/<package>/files/
 *    特性：无权限限制，可执行，卸载时自动清理
 *    用途：
 *    - projects/      - 私有源码目录（无外部权限时的兜底项目根）
 *    - workspace/     - 私有构建工作区与运行时 staging
 *    - ubuntu/        - Ubuntu/PRoot 根目录
 *    - rootfs/        - Linux rootfs（Ubuntu）
 *    - toolchain/     - 工具链
 *    - proot/         - PRoot 相关文件
 *    - sync-meta/     - 同步元数据
 *    - config.json    - 应用配置
 *
 * 2. 公有源码目录（Documents/TinaIDE）
 *    路径：/sdcard/Documents/TinaIDE/
 *    特性：需要外部存储权限，用户可直接访问
 *    用途：
 *    - 用户源码目录（主源码）
 *
 * 3. 外部应用专属目录 (context.getExternalFilesDir)
 *    路径：/storage/emulated/0/Android/data/<package>/files/
 *    特性：无需存储权限，用户可见，卸载时自动清理
 *    用途：
 *    - terminal_states/ - 终端状态持久化
 *    - logs/            - 应用日志
 *    - install_logs/    - 安装日志
 *    - tombstones/      - Native 崩溃日志
 *    - fonts/           - 编辑器自定义字体
 *    - terminal_fonts/  - 终端自定义字体
 *
 * 4. 缓存目录 (context.cacheDir)
 *    路径：/data/data/<package>/cache/
 *    特性：系统可能自动清理
 *    用途：
 *    - exports/     - 项目导出临时文件
 *    - proot-tmp/   - PRoot 临时文件
 *
 * 核心原则：
 * - 源码目录与构建工作区彻底分离
 * - 用户源码默认放在公有 Documents/TinaIDE 目录
 * - 无外部权限时，源码回退到私有 projects 目录
 * - 构建产物与运行时 staging 固定放在私有 workspace 目录，规避权限与 noexec 问题
 * - 用户可见的数据优先放在公有目录或 Android/data 目录
 * - 临时文件放在 cache 目录
 */
object ProjectPaths {

    // ============ 1. 私有源码目录 ============

    /**
     * 私有源码根目录
     *
     * 典型路径：/data/data/<package>/files/projects
     */
    fun getPrivateProjectsRoot(context: Context): File {
        return File(context.filesDir, "projects")
    }

    /**
     * 获取单个项目的私有源码目录
     */
    fun getPrivateProjectDir(context: Context, projectName: String): File {
        return File(getPrivateProjectsRoot(context), projectName)
    }

    /**
     * 获取私有源码根目录路径（字符串）
     */
    fun getPrivateProjectsRootPath(context: Context): String {
        return getPrivateProjectsRoot(context).absolutePath
    }

    /**
     * 判断给定路径是否位于私有源码根目录下。
     */
    fun isUnderPrivateProjectsRoot(context: Context, path: File): Boolean {
        return isUnderRoot(getPrivateProjectsRoot(context), path)
    }

    // ============ 2. 公有源码目录 ============

    /**
     * 公有源码根目录。
     *
     * 典型路径：/sdcard/Documents/TinaIDE
     */
    fun getPublicProjectsRoot(context: Context): File {
        val documentsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        } else {
            File(Environment.getExternalStorageDirectory(), "Documents")
        }
        return File(documentsDir, "TinaIDE")
    }

    /**
     * 获取单个项目的公有源码目录。
     */
    fun getPublicProjectDir(context: Context, projectName: String): File {
        return File(getPublicProjectsRoot(context), projectName)
    }

    /**
     * 判断给定路径是否位于默认公有源码根目录下。
     */
    fun isUnderPublicProjectsRoot(context: Context, path: File): Boolean {
        return isUnderRoot(getPublicProjectsRoot(context), path)
    }

    // ============ 3. 私有构建工作区 ============

    /**
     * 私有工作区根目录（用于在 PRoot 内映射为 /workspace）。
     *
     * 典型路径：/data/data/<package>/files/workspace
     */
    fun getWorkspaceRoot(context: Context): File {
        return File(context.filesDir, "workspace")
    }

    /**
     * 获取单个项目的私有构建工作区目录。
     *
     * 以稳定的 projectId 作为目录名，避免项目重命名后工作区抖动。
     */
    fun getProjectWorkspaceDir(context: Context, projectId: String): File {
        return File(getWorkspaceRoot(context), projectId)
    }

    // ============ 4. 项目构建目录 ============

    /**
     * 获取项目默认构建目录。
     *
     * 典型路径：<workspaceRoot>/build
     */
    fun getProjectBuildDir(workspaceRoot: File): File {
        return File(workspaceRoot, "build")
    }

    /**
     * 获取项目默认 APK 输出目录。
     */
    fun getProjectApkOutputDir(workspaceRoot: File): File {
        return File(getProjectBuildDir(workspaceRoot), "apk")
    }

    // ============ 5. 私有目录（Ubuntu/工具链）============

    /**
     * Ubuntu/PRoot 根目录
     *
     * 典型路径：/data/data/<package>/files/ubuntu
     * 特性：
     * - App 私有存储
     * - 可独立重置/迁移
     * - 卸载时自动清理
     */
    fun getUbuntuRoot(context: Context): File {
        return File(context.filesDir, "ubuntu")
    }

    /**
     * 工具链根目录
     */
    fun getToolchainRoot(context: Context): File {
        return File(context.filesDir, "toolchain")
    }

    /**
     * 同步元数据目录（私有）
     */
    fun getSyncMetaRoot(context: Context): File {
        return File(context.filesDir, "sync-meta")
    }

    // ============ 7. 终端状态目录 ============

    /**
     * 终端状态存储根目录
     *
     * 典型路径：/sdcard/Android/data/<package>/files/terminal_states
     * 特性：
     * - 存储在 Android/data 目录，用户可见
     * - 无需存储权限
     * - 卸载时自动清理
     * - 按项目隔离存储终端会话状态
     */
    fun getTerminalStatesRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "terminal_states")
    }

    /**
     * 获取终端状态存储根目录路径（字符串）
     */
    fun getTerminalStatesRootPath(context: Context): String {
        return getTerminalStatesRoot(context).absolutePath
    }

    // ============ 8. 日志目录 ============

    /**
     * 应用日志目录
     *
     * 典型路径：/sdcard/Android/data/<package>/files/logs
     * 用途：构建日志、运行日志等
     */
    fun getLogsRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "logs")
    }

    /**
     * 内部日志目录（私有）
     *
     * 路径：/data/data/<package>/files/logs
     * 用途：存放不希望暴露到 Android/data 的敏感日志（例如：PRoot 命令与会话输出）。
     */
    fun getInternalLogsRoot(context: Context): File {
        return File(context.filesDir, "logs")
    }

    /**
     * PRoot 日志目录（内部）
     *
     * 路径：/data/data/<package>/files/logs/proot
     */
    fun getPRootLogsRoot(context: Context): File {
        return File(getInternalLogsRoot(context), "proot")
    }

    /**
     * 安装日志目录
     *
     * 典型路径：/sdcard/Android/data/<package>/files/install_logs
     * 用途：PRoot 环境安装日志
     */
    fun getInstallLogsRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "install_logs")
    }

    // ============ 9. 字体目录 ============

    /**
     * 编辑器自定义字体目录
     *
     * 典型路径：/sdcard/Android/data/<package>/files/fonts
     * 用途：用户导入的编辑器字体（用户可见，便于管理）
     */
    fun getEditorFontsRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "fonts")
    }

    /**
     * 终端自定义字体目录
     *
     * 典型路径：/sdcard/Android/data/<package>/files/terminal_fonts
     * 用途：用户导入的终端字体（用户可见，便于管理）
     */
    fun getTerminalFontsRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "terminal_fonts")
    }

    /**
     * 用户自定义项目模板目录。
     *
     * 典型路径：/sdcard/Android/data/<package>/files/project_templates
     * 用途：用户放入的 .zip 项目模板，供新建项目向导扫描。
     */
    fun getUserProjectTemplatesRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "project_templates")
    }

    // ============ 10. PRoot 相关目录 ============

    /**
     * PRoot 目录
     *
     * 典型路径：/data/data/<package>/files/proot
     * 用途：PRoot 相关运行时文件
     */
    fun getPRootRoot(context: Context): File {
        return File(context.filesDir, "proot")
    }

    // ============ 11. 缓存目录 ============

    /**
     * 项目导出缓存目录
     *
     * 典型路径：/data/data/<package>/cache/exports
     * 用途：项目导出时的临时文件
     */
    fun getExportCacheRoot(context: Context): File {
        return File(context.cacheDir, "exports")
    }

    /**
     * PRoot 临时目录
     *
     * 典型路径：/data/data/<package>/cache/proot-tmp
     * 用途：PRoot 运行时临时文件
     */
    fun getPRootTmpRoot(context: Context): File {
        return File(context.cacheDir, "proot-tmp")
    }

    /**
     * 下载缓存目录
     *
     * 典型路径：/data/data/<package>/cache/downloads
     * 用途：Linux rootfs 等大文件下载缓存
     */
    fun getDownloadCacheRoot(context: Context): File {
        return File(context.cacheDir, "downloads")
    }

    // ============ 12. 其他目录 ============

    /**
     * 崩溃日志目录
     *
     * 典型路径：/sdcard/Android/data/<package>/files/tombstones
     * 用途：Native 崩溃日志（用户可见，便于反馈问题）
     */
    fun getTombstonesRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "tombstones")
    }

    /**
     * 应用配置文件
     *
     * 典型路径：/data/data/<package>/files/config.json
     * 用途：应用配置持久化
     */
    fun getConfigFile(context: Context): File {
        return File(context.filesDir, "config.json")
    }

    // ============ 辅助方法 ============

    /**
     * 获取私有目录根路径
     *
     * 典型路径：/data/data/<package>/files
     */
    fun getPrivateFilesRoot(context: Context): File {
        return context.filesDir
    }

    /**
     * 获取外部应用专属目录根路径
     *
     * 典型路径：/storage/emulated/0/Android/data/<package>/files
     * 如果外部存储不可用，回退到私有目录
     */
    fun getExternalFilesRoot(context: Context): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    /**
     * 获取缓存目录根路径
     *
     * 典型路径：/data/data/<package>/cache
     */
    fun getCacheRoot(context: Context): File {
        return context.cacheDir
    }

    /**
     * 确保目录存在
     */
    fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun isUnderRoot(root: File, path: File): Boolean {
        val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val candidatePath = runCatching { path.canonicalPath }.getOrElse { path.absolutePath }
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }
}
