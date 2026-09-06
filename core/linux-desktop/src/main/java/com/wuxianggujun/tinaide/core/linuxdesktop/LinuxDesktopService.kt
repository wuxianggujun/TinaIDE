package com.wuxianggujun.tinaide.core.linuxdesktop

import kotlinx.coroutines.flow.StateFlow

/**
 * Linux 桌面环境服务接口。
 *
 * 提供 X11 服务器生命周期管理、显示配置与输入映射。
 * 依赖 termux-x11 的 libXlorie.so（GPL-3.0）。
 */
interface LinuxDesktopService {

    /** X11 服务器当前运行状态 */
    val serverState: StateFlow<X11ServerState>

    /**
     * 启动 X11 服务器。
     *
     * 这里不接受 `SurfaceView`：X server 运行在独立进程中（原因见 [X11ServerLauncher]），
     * 渲染用的 Surface 属于那个进程里的 `LorieView`，主进程无从提供。本方法只负责把
     * 服务器拉起来并拿到可用的 DISPLAY。
     *
     * @param display X11 显示号（例如 ":0"）
     * @param config 显示配置（分辨率、DPI 等）
     */
    suspend fun startX11Server(
        display: String,
        config: X11DisplayConfig = X11DisplayConfig.default()
    ): Result<Unit>

    /**
     * 停止 X11 服务器。
     */
    suspend fun stopX11Server()

    /**
     * 导出 X11 环境变量（供 PRoot 环境使用）。
     *
     * 返回需要注入到 PRoot 环境的环境变量 Map，例如：
     * - `DISPLAY=:0`
     * - `XAUTHORITY=/data/data/.../Xauthority`
     */
    fun getX11EnvironmentVariables(): Map<String, String>

    companion object {
        /**
         * @param serverLauncher 独立进程中的 X server 启动器。传 `null` 时
         *   [startX11Server] 会明确失败——X server 不能 in-process 启动，
         *   原因见 [X11ServerLauncher]。
         * @param socketLayoutProvider 提供当前 rootfs 对应的 X11 socket 布局；
         *   rootfs 未安装时返回 `null`。
         */
        fun create(
            serverLauncher: X11ServerLauncher? = null,
            socketLayoutProvider: () -> X11SocketLayout? = { null },
        ): LinuxDesktopService = LinuxDesktopServiceImpl(serverLauncher, socketLayoutProvider)
    }
}

/**
 * X11 服务器状态。
 */
sealed class X11ServerState {
    data object Stopped : X11ServerState()
    data object Starting : X11ServerState()
    data class Running(val display: String) : X11ServerState()
    data class Error(val message: String, val cause: Throwable? = null) : X11ServerState()
}

/**
 * X11 显示配置。
 */
data class X11DisplayConfig(
    /** 显示宽度（像素） */
    val width: Int,
    /** 显示高度（像素） */
    val height: Int,
    /** DPI */
    val dpi: Int,
    /** 色深（bits per pixel） */
    val colorDepth: Int = 24
) {
    companion object {
        fun default() = X11DisplayConfig(
            width = 1920,
            height = 1080,
            dpi = 160,
            colorDepth = 24
        )
    }
}
