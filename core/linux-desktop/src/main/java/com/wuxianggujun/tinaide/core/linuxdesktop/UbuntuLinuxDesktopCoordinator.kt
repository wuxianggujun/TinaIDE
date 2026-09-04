package com.wuxianggujun.tinaide.core.linuxdesktop

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * 把 X server 启动与 guest XFCE 会话串成一条路径。
 *
 * DISPLAY 只在 [LinuxDesktopService.serverState] 为 [X11ServerState.Running] 之后注入；
 * 缺 X server、缺 rootfs、缺桌面软件包都会失败，而不会让 `startxfce4` 对着空 display 启动。
 *
 * 本类不打开桌面窗口：渲染 Activity 由 [com.wuxianggujun.tinaide.core.IAppNavigator]
 * 在主进程拉起，避免 feature 模块直接依赖 `com.termux.x11.MainActivity`。
 */
class UbuntuLinuxDesktopCoordinator(
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider,
    private val desktopService: LinuxDesktopService,
    private val provisionerFactory: (LinuxEnvironment) -> UbuntuDesktopProvisioner =
        { environment -> UbuntuDesktopProvisioner(environment) },
    private val sessionLauncherFactory: (LinuxEnvironment) -> UbuntuDesktopSessionLauncher =
        { environment -> UbuntuDesktopSessionLauncher(environment) },
) {
    private val lock = Mutex()
    private var session: LinuxDesktopSession? = null

    suspend fun inspect(): UbuntuDesktopProvisioner.Status =
        provisionerFactory(linuxEnvironmentProvider.get()).inspect()

    suspend fun install(
        progress: (UbuntuDesktopProvisioner.Progress) -> Unit = {},
    ): Result<UbuntuDesktopProvisioner.InstallResult> =
        provisionerFactory(linuxEnvironmentProvider.get()).install(progress)

    /**
     * 启动（或复用）X server，再在 guest 里拉起 XFCE。
     *
     * 已有仍在跑的会话时直接返回，避免点两次打开两个桌面。
     * X server 启动成功但会话启动失败时，X server 保持运行——重试不必再等 socket。
     */
    suspend fun startSession(
        display: String = DEFAULT_DISPLAY,
        config: X11DisplayConfig = X11DisplayConfig.default(),
    ): Result<LinuxDesktopSession> = lock.withLock {
        val existing = session
        if (existing != null && existing.isRunning()) {
            Timber.tag(TAG).i("Reusing running Ubuntu desktop session on %s", display)
            return@withLock Result.success(existing)
        }

        val environment = linuxEnvironmentProvider.get()
        if (!environment.isAvailable()) {
            return@withLock Result.failure(
                IllegalStateException("Ubuntu Linux environment is unavailable"),
            )
        }

        val status = provisionerFactory(environment).inspect()
        if (!status.ready) {
            val missing = status.missingRequiredCommands.joinToString()
            return@withLock Result.failure(
                IllegalStateException(
                    "Ubuntu desktop packages are not installed: missing $missing",
                ),
            )
        }

        desktopService.startX11Server(display, config).onFailure { error ->
            return@withLock Result.failure(error)
        }

        val guestDisplay = desktopService.getX11EnvironmentVariables()[ENV_DISPLAY]
        if (guestDisplay.isNullOrBlank()) {
            return@withLock Result.failure(
                IllegalStateException("X server did not export DISPLAY"),
            )
        }

        val launched = sessionLauncherFactory(environment).launch(
            UbuntuDesktopSessionOptions(
                endpoint = LinuxDesktopEndpoint(display = guestDisplay),
            ),
        )
        launched.onSuccess { next -> session = next }
        launched
    }

    private companion object {
        private const val TAG = "UbuntuDesktop"
        private const val DEFAULT_DISPLAY = ":0"
        private const val ENV_DISPLAY = "DISPLAY"
    }
}
