package com.wuxianggujun.tinaide.core.linuxdesktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * [LinuxDesktopService] 默认实现。
 *
 * 真正启动 X server 的动作被委派给 [X11ServerLauncher]：X server 必须跑在独立进程里
 * （原因见 [X11ServerLauncher] 的说明——lorie 覆盖了 `exit()`，崩溃会杀掉宿主进程）。
 * 本类只负责状态机、socket 目录准备与环境变量导出。
 *
 * 未注入 launcher 时，[startX11Server] 会明确失败而不是伪装成 Running：桌面会话拿到
 * 一个指向不存在的 X server 的 DISPLAY，只会在 guest 里表现为一堆难以诊断的
 * "cannot open display"。
 */
internal class LinuxDesktopServiceImpl(
    private val serverLauncher: X11ServerLauncher? = null,
    private val socketLayoutProvider: () -> X11SocketLayout? = { null },
) : LinuxDesktopService {

    private val _serverState = MutableStateFlow<X11ServerState>(X11ServerState.Stopped)
    override val serverState: StateFlow<X11ServerState> = _serverState.asStateFlow()

    private val stateLock = Mutex()

    /** Running 时记录本次会话的 socket 布局，供环境变量导出与停止时清理使用。 */
    private var activeLayout: X11SocketLayout? = null
    private var activeDisplayNumber: Int? = null

    override suspend fun startX11Server(
        display: String,
        config: X11DisplayConfig,
    ): Result<Unit> = stateLock.withLock {
        Timber.tag(TAG).i("startX11Server: display=%s, config=%s", display, config)

        if (_serverState.value is X11ServerState.Running) {
            Timber.tag(TAG).w("X11 server already running")
            return@withLock Result.success(Unit)
        }

        val displayNumber = parseDisplayNumber(display)
            ?: return@withLock fail("Invalid X11 display: $display")

        val launcher = serverLauncher
            ?: return@withLock fail(
                "X11 server launcher is not wired up yet: the X server must run in a " +
                    "separate process, see X11ServerLauncher"
            )

        val layout = socketLayoutProvider()
            ?: return@withLock fail(
                "X11 socket layout is unavailable: the Ubuntu rootfs is probably not installed"
            )

        _serverState.value = X11ServerState.Starting

        layout.prepare().onFailure { error ->
            return@withLock fail("Failed to prepare X11 socket directory", error)
        }
        layout.clearStaleSocket(displayNumber)

        if (!layout.hostXkbConfigRoot.isDirectory) {
            // lorie 用 dirname($TMPDIR) 推导 chroot 根再找 xkb；缺数据时 start() 会
            // 直接返回 false，且只在 logcat 里留一行错误，很难定位，这里提前报清楚。
            return@withLock fail(
                "XKB data not found at ${layout.hostXkbConfigRoot.absolutePath}; " +
                    "install the xkb-data package inside the Ubuntu rootfs first"
            )
        }

        val args = X11ServerArgs(displayNumber = displayNumber, config = config)
        return@withLock launcher.launch(args, layout).fold(
            onSuccess = { guestDisplay ->
                activeLayout = layout
                activeDisplayNumber = displayNumber
                _serverState.value = X11ServerState.Running(guestDisplay)
                Timber.tag(TAG).i("X11 server running on %s", guestDisplay)
                Result.success(Unit)
            },
            onFailure = { error -> fail("Failed to start X11 server", error) },
        )
    }

    override suspend fun stopX11Server() = stateLock.withLock {
        Timber.tag(TAG).i("stopX11Server")

        if (_serverState.value is X11ServerState.Stopped) {
            return@withLock
        }

        try {
            serverLauncher?.terminate()
            activeDisplayNumber?.let { number -> activeLayout?.clearStaleSocket(number) }
            _serverState.value = X11ServerState.Stopped
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stop X11 server")
            _serverState.value = X11ServerState.Error("Failed to stop: ${e.message}", e)
        } finally {
            activeLayout = null
            activeDisplayNumber = null
        }
    }

    override fun getX11EnvironmentVariables(): Map<String, String> {
        val state = _serverState.value
        if (state !is X11ServerState.Running) {
            return emptyMap()
        }

        // 不导出 XAUTHORITY：X server 以 `-ac` 启动，socket 目录位于应用私有 rootfs 内，
        // 由 Android 沙箱而非 X 认证来隔离。导出一个并不存在的 .Xauthority 只会让
        // guest 侧的 libX11 去读空文件，反而增加诊断噪音。详见 X11ServerArgs.toArgv()。
        return mapOf("DISPLAY" to state.display)
    }

    private fun fail(message: String, cause: Throwable? = null): Result<Unit> {
        if (cause != null) {
            Timber.tag(TAG).e(cause, message)
        } else {
            Timber.tag(TAG).e(message)
        }
        _serverState.value = X11ServerState.Error(message, cause)
        return Result.failure(cause ?: IllegalStateException(message))
    }

    /** 解析 `:0` / `:0.0` / `host:1` 形式的 display，返回 display 号。 */
    private fun parseDisplayNumber(display: String): Int? {
        val colon = display.lastIndexOf(':')
        if (colon < 0) return null
        return display.substring(colon + 1)
            .substringBefore('.')
            .toIntOrNull()
            ?.takeIf { it >= 0 }
    }

    companion object {
        private const val TAG = "LinuxDesktopService"
    }
}
