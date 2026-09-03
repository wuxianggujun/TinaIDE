package com.wuxianggujun.tinaide.core.linuxdesktop

import android.content.Context
import android.view.SurfaceView
import com.wuxianggujun.tinaide.core.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [LinuxDesktopService] 默认实现。
 *
 * 当前为骨架实现，等待 libXlorie.so JNI 绑定完成后填充实际逻辑。
 */
internal class LinuxDesktopServiceImpl(
    private val context: Context,
    private val logger: Logger
) : LinuxDesktopService {

    private val _serverState = MutableStateFlow<X11ServerState>(X11ServerState.Stopped)
    override val serverState: StateFlow<X11ServerState> = _serverState.asStateFlow()

    override suspend fun startX11Server(
        display: String,
        surfaceView: SurfaceView,
        config: X11DisplayConfig
    ): Result<Unit> {
        logger.info(TAG, "startX11Server: display=$display, config=$config")

        if (_serverState.value is X11ServerState.Running) {
            logger.warn(TAG, "X11 server already running")
            return Result.success(Unit)
        }

        return try {
            _serverState.value = X11ServerState.Starting

            // TODO: 调用 libXlorie.so JNI 初始化
            // - 创建 X11 Display
            // - 绑定 SurfaceView 的 Surface
            // - 启动 X server 主循环（后台线程）
            // - 设置 Xauthority（可选）

            _serverState.value = X11ServerState.Running(display)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(TAG, "Failed to start X11 server", e)
            _serverState.value = X11ServerState.Error("Failed to start: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun stopX11Server() {
        logger.info(TAG, "stopX11Server")

        if (_serverState.value is X11ServerState.Stopped) {
            return
        }

        try {
            // TODO: 调用 libXlorie.so JNI 清理
            // - 停止 X server 主循环
            // - 释放 Surface 绑定
            // - 清理 X Display

            _serverState.value = X11ServerState.Stopped
        } catch (e: Exception) {
            logger.error(TAG, "Failed to stop X11 server", e)
            _serverState.value = X11ServerState.Error("Failed to stop: ${e.message}", e)
        }
    }

    override fun getX11EnvironmentVariables(): Map<String, String> {
        val state = _serverState.value
        if (state !is X11ServerState.Running) {
            return emptyMap()
        }

        // TODO: 实际 Xauthority 路径由 libXlorie 生成后返回
        val xauthorityPath = context.filesDir.resolve(".Xauthority").absolutePath

        return mapOf(
            "DISPLAY" to state.display,
            "XAUTHORITY" to xauthorityPath
        )
    }

    companion object {
        private const val TAG = "LinuxDesktopService"
    }
}
