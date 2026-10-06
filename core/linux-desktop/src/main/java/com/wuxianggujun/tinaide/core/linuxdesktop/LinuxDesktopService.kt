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

    /**
     * 在 X server 所在进程里启动 guest 桌面会话。
     *
     * 会话不能由主进程 spawn：init-proot.sh 带 `--kill-on-exit`，proot 树的存亡跟着
     * spawn 它的进程。挂在常驻的 X server 进程上，桌面才能在 IDE 窗口关闭之后活着。
     * 命令行与环境由主进程组装（见 [LinuxDesktopHostProcessPlan]）。
     *
     * 幂等：已有存活会话时直接成功返回。
     */
    suspend fun startGuestSession(
        plan: LinuxDesktopHostProcessPlan,
        restartPolicy: LinuxDesktopRestartPolicy = LinuxDesktopRestartPolicy(),
    ): Result<Unit>

    /** 终止 guest 桌面会话，保留 X server（重开桌面不必再等 socket）。 */
    suspend fun stopGuestSession()

    /** guest 桌面会话是否存活。 */
    fun isGuestSessionRunning(): Boolean

    /** guest 会话看护阶段；无法获知（server 未起、binder 已断）时为 `null`。 */
    fun guestSessionPhase(): LinuxDesktopSupervisorPhase?

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
        /**
         * 兜底几何，**不是**生产路径该用的值。
         *
         * 真机上走 `resolveX11DisplayConfig(context)`：它按整块屏幕算，
         * 由 `linuxDesktopModule` 注入给 [UbuntuLinuxDesktopCoordinator]。
         * 这里保留一组固定值，只为拿不到屏幕信息时仍有可用的开机尺寸。
         */
        fun default() = X11DisplayConfig(
            width = 1920,
            height = 1080,
            dpi = 160,
            colorDepth = 24
        )
    }
}
