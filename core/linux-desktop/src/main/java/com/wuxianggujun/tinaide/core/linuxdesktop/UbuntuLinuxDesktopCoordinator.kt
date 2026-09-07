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
 *
 * 也不 spawn 会话进程。本类跑在主进程，而 init-proot.sh 带 `--kill-on-exit`——在这里
 * spawn 就意味着"关掉 IDE 就杀掉桌面"。所以只负责探测、准备、组装命令行，
 * 真正的 `exec` 与看护都发生在常驻的 `:x11` 进程里（见 [LinuxDesktopHostProcessPlan]）。
 */
class UbuntuLinuxDesktopCoordinator(
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider,
    private val desktopService: LinuxDesktopService,
    private val hostProcessPlannerProvider: () -> LinuxDesktopHostProcessPlanner?,
    private val provisionerFactory: (LinuxEnvironment) -> UbuntuDesktopProvisioner =
        { environment -> UbuntuDesktopProvisioner(environment) },
    private val preparerFactory: (LinuxEnvironment) -> UbuntuDesktopSessionPreparer =
        { environment -> UbuntuDesktopSessionPreparer(environment) },
    private val sessionPlanner: UbuntuDesktopSessionPlanner = UbuntuDesktopSessionPlanner(),
    private val restartPolicy: LinuxDesktopRestartPolicy = LinuxDesktopRestartPolicy(),
    /**
     * 开机分辨率来源。默认值只是给测试兜底；真实实现由 `linuxDesktopModule` 注入
     * [resolveX11DisplayConfig]，按真机屏幕算（见 [X11DisplayConfig] 的说明）。
     */
    private val displayConfigProvider: () -> X11DisplayConfig = { X11DisplayConfig.default() },
) {
    private val lock = Mutex()

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
        config: X11DisplayConfig = displayConfigProvider(),
    ): Result<Unit> = lock.withLock {
        if (desktopService.isGuestSessionRunning()) {
            Timber.tag(TAG).i("Reusing running Ubuntu desktop session on %s", display)
            return@withLock Result.success(Unit)
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

        // 渲染后端按实测能力选，而不是一律 LIBGL_ALWAYS_SOFTWARE=1：
        // gpuCapabilities 已经在 inspect() 里探测过 /dev/dri/renderD128 与 virgl/vulkan。
        val gpuBackend = status.gpuCapabilities.preferredBackend()
        val options = UbuntuDesktopSessionOptions(
            endpoint = LinuxDesktopEndpoint(display = guestDisplay),
            gpuBackend = gpuBackend,
        )
        Timber.tag(TAG).i("Ubuntu desktop GPU backend: %s", gpuBackend)

        preparerFactory(environment).prepare(options).onFailure { error ->
            return@withLock Result.failure(error)
        }

        // 会话在 `:x11` 里 spawn，本进程只组装命令行。理由见 LinuxDesktopHostProcessPlan：
        // init-proot.sh 带 --kill-on-exit，谁 spawn 谁决定 XFCE 的生死。
        val hostPlanner = hostProcessPlannerProvider()
            ?: return@withLock Result.failure(
                IllegalStateException(
                    "PRoot host process planner is unavailable; the Ubuntu rootfs is probably " +
                        "not installed",
                ),
            )
        val hostPlan = try {
            hostPlanner.plan(sessionPlanner.plan(options))
        } catch (error: Throwable) {
            return@withLock Result.failure(error)
        }

        desktopService.startGuestSession(hostPlan, restartPolicy)
    }

    /**
     * 停止 guest XFCE 会话并关掉 X server。
     *
     * 顺序是先停会话再停 server：反过来会让 XFCE 对着已消失的 display 疯狂重连，
     * 而 supervisor 的重启预算会白白烧掉。
     */
    suspend fun stopSession() = lock.withLock {
        desktopService.stopGuestSession()
        desktopService.stopX11Server()
    }

    /** 当前是否有活跃桌面会话，供 UI 决定显示"打开"还是"停止"。 */
    fun isSessionActive(): Boolean = desktopService.isGuestSessionRunning()

    /**
     * 会话看护阶段，供 UI 显示"桌面已崩溃"而不是停留在"运行中"。
     *
     * 状态源在 `:x11`（看护器和被看护进程都在那边），主进程每次都去问而不是缓存：
     * 缓存会在 X server 进程被系统回收后继续报"运行中"。
     */
    fun sessionPhase(): LinuxDesktopSupervisorPhase =
        desktopService.guestSessionPhase() ?: LinuxDesktopSupervisorPhase.IDLE

    private companion object {
        private const val TAG = "UbuntuDesktop"
        private const val DEFAULT_DISPLAY = ":0"
        private const val ENV_DISPLAY = "DISPLAY"
    }
}
