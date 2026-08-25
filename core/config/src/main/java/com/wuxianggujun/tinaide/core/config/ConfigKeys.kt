package com.wuxianggujun.tinaide.core.config

/**
 * 类型安全的配置键定义。
 */
sealed class ConfigKey<T>(val key: String, val default: T) {

    // UI 相关
    object Theme : ConfigKey<AppTheme>("ui.theme", AppTheme.DEFAULT)
    object DebugToolbarPositionMode : ConfigKey<String>(
        key = "ui.debug.toolbar.position",
        default = "top"
    )

    // 面板可见性：ui.panel.<PANEL_NAME> -> Boolean
    class PanelVisible(panelName: String, defaultVisible: Boolean) : ConfigKey<Boolean>("ui.panel.$panelName", defaultVisible)

    object CurrentProject : ConfigKey<String>(
        key = "file.current_project",
        default = ""
    )

    object RecentFiles : ConfigKey<String>(
        key = "file.recent_files",
        default = ""
    )

    // ========== 日志 / 诊断 ==========

    /**
     * 是否启用 TinaIDE 主进程崩溃日志自动上传。
     * 默认开启；用户 native runtime 崩溃仍由隐私策略拦截，不会上报。
     */
    object CrashAutoUploadEnabled : ConfigKey<Boolean>(
        key = "logs.crash.auto_upload",
        default = true
    )

    /** 最近一次已上报的 tombstone 文件名（用于去重）。 */
    object CrashLastUploadedTombstoneName : ConfigKey<String>(
        key = "logs.crash.last_uploaded.name",
        default = ""
    )

    /** 最近一次已上报的 tombstone 文件修改时间（用于去重）。 */
    object CrashLastUploadedTombstoneMtime : ConfigKey<Long>(
        key = "logs.crash.last_uploaded.mtime",
        default = 0L
    )

    // LSP / clangd 配置
    object LspCompletionLimit : ConfigKey<Int>(
        key = "lsp.completion.limit",
        default = 50
    )

    // ========== 远程 LSP 配置 ==========

    /**
     * 是否启用远程 LSP 服务器
     * 启用后将通过 WebSocket 连接远程服务器，而非本地 PRoot
     */
    object RemoteLspEnabled : ConfigKey<Boolean>(
        key = "lsp.remote.enabled",
        default = false
    )

    /**
     * 远程 LSP 服务器地址
     */
    object RemoteLspHost : ConfigKey<String>(
        key = "lsp.remote.host",
        default = ""
    )

    /**
     * 远程 LSP 服务器端口
     */
    object RemoteLspPort : ConfigKey<Int>(
        key = "lsp.remote.port",
        default = 6789
    )

    /** Whether the remote WebSocket uses TLS. Cleartext is allowed only for loopback hosts. */
    object RemoteLspSecureTransport : ConfigKey<Boolean>(
        key = "lsp.remote.secure_transport",
        default = true,
    )

    /**
     * 远程 LSP 同步模式
     * - auto: 自动判断（根据项目大小和特征）
     * - lightweight: 轻量模式（仅传输打开的文件）
     * - project: 项目模式（同步整个项目）
     */
    object RemoteLspSyncMode : ConfigKey<String>(
        key = "lsp.remote.sync_mode",
        default = "auto"
    )

    /**
     * 远程 LSP 项目同步方案
     * - builtin: 内置 WebSocket 同步
     * - rsync: rsync 增量同步
     * - manual: 手动同步
     */
    object RemoteLspSyncMethod : ConfigKey<String>(
        key = "lsp.remote.sync_method",
        default = "builtin"
    )

    /**
     * rsync 远程模块名称（用于 rsync 同步方案）
     */
    object RemoteLspRsyncModule : ConfigKey<String>(
        key = "lsp.remote.rsync_module",
        default = "tina-workspace"
    )

    /**
     * rsync daemon 端口（用于 rsync 同步方案）
     */
    object RemoteLspRsyncPort : ConfigKey<Int>(
        key = "lsp.remote.rsync_port",
        default = 873
    )

    /**
     * 远端工作区根 URI（用于 MANUAL/RSYNC 等非内置同步模式的路径映射）
     *
     * 示例：file:///C:/Users/me/project 或 file:///home/me/project
     */
    object RemoteLspWorkspaceRootUri : ConfigKey<String>(
        key = "lsp.remote.workspace_root_uri",
        default = ""
    )

    // 存储相关配置

    /**
     * 外部工作空间根目录 SAF URI（空表示未配置）
     */
    object WorkspaceRootUri : ConfigKey<String>(
        key = "storage.workspace.root_uri",
        default = ""
    )

    /**
     * 同步模式（保留：现有同步链路依赖该配置；同步功能本迭代不做新增）
     */
    object SyncMode : ConfigKey<String>(
        key = "storage.sync.mode",
        default = "sync_to_external"
    )

    /**
     * 当前活动 Linux rootfs 根目录路径。
     * 默认为空表示尚未安装或未选择 rootfs profile。
     */
    object RootfsPath : ConfigKey<String>(
        key = "storage.linux.rootfs_path",
        default = ""
    )

    /**
     * 工作空间配置流程是否已完成
     * 包括：用户已选择工作空间 或 用户已明确跳过
     */
    object WorkspaceSetupCompleted : ConfigKey<Boolean>(
        key = "storage.workspace.setup_completed",
        default = false
    )

    /**
     * MT 管理器文件提供器开关
     * 默认开启，便于用户在日志上传失败或应用启动异常时通过 MT 管理器导出诊断文件。
     */
    object MTFileProviderEnabled : ConfigKey<Boolean>(
        key = "storage.mt_file_provider.enabled",
        default = true
    )
}

/**
 * 便于集中管理的 ConfigKeys 别名
 */
object ConfigKeys {
    val Theme = ConfigKey.Theme
    val DebugToolbarPosition = ConfigKey.DebugToolbarPositionMode
    fun panelVisible(panelName: String, defaultVisible: Boolean) = ConfigKey.PanelVisible(panelName, defaultVisible)

    val CurrentProject = ConfigKey.CurrentProject
    val RecentFiles = ConfigKey.RecentFiles

    // 日志/诊断
    val CrashAutoUploadEnabled = ConfigKey.CrashAutoUploadEnabled
    val CrashLastUploadedTombstoneName = ConfigKey.CrashLastUploadedTombstoneName
    val CrashLastUploadedTombstoneMtime = ConfigKey.CrashLastUploadedTombstoneMtime

    val LspCompletionLimit = ConfigKey.LspCompletionLimit

    // 远程 LSP 配置
    val RemoteLspEnabled = ConfigKey.RemoteLspEnabled
    val RemoteLspHost = ConfigKey.RemoteLspHost
    val RemoteLspPort = ConfigKey.RemoteLspPort
    val RemoteLspSecureTransport = ConfigKey.RemoteLspSecureTransport
    val RemoteLspSyncMode = ConfigKey.RemoteLspSyncMode
    val RemoteLspSyncMethod = ConfigKey.RemoteLspSyncMethod
    val RemoteLspRsyncModule = ConfigKey.RemoteLspRsyncModule
    val RemoteLspRsyncPort = ConfigKey.RemoteLspRsyncPort
    val RemoteLspWorkspaceRootUri = ConfigKey.RemoteLspWorkspaceRootUri

    // 存储配置
    val WorkspaceRootUri = ConfigKey.WorkspaceRootUri
    val SyncMode = ConfigKey.SyncMode
    val RootfsPath = ConfigKey.RootfsPath
    val WorkspaceSetupCompleted = ConfigKey.WorkspaceSetupCompleted
    val MTFileProviderEnabled = ConfigKey.MTFileProviderEnabled
}
