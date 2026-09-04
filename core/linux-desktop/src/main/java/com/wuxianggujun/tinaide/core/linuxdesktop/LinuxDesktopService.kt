package com.wuxianggujun.tinaide.core.linuxdesktop

import android.content.Context
import android.view.SurfaceView
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
     * @param display X11 显示号（例如 ":0"）
     * @param surfaceView 用于渲染的 Android SurfaceView
     * @param config 显示配置（分辨率、DPI 等）
     */
    suspend fun startX11Server(
        display: String,
        surfaceView: SurfaceView,
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
        fun create(context: Context): LinuxDesktopService = LinuxDesktopServiceImpl(context)
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
