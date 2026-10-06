package com.wuxianggujun.tinaide.core.linuxdesktop

/**
 * 启动 lorie X server 所需的一组参数。
 *
 * lorie 的入口是 `CmdEntryPoint.main(String[])` / `native boolean start(String[])`，
 * 参数格式沿用 Xorg 的命令行约定（`argv[0]` 由 native 侧补成 `"Xlorie"`）。
 */
data class X11ServerArgs(
    val displayNumber: Int,
    val config: X11DisplayConfig,
) {
    init {
        require(displayNumber >= 0) { "displayNumber must be >= 0: $displayNumber" }
        require(config.width > 0 && config.height > 0) {
            "Invalid X11 geometry: ${config.width}x${config.height}"
        }
    }

    /**
     * 组装 `CmdEntryPoint.start()` 的 argv（不含 `argv[0]`）。
     *
     * `-ac` 关闭 host-based 访问控制。这在普通 X server 上等于对本机所有用户开放，
     * 但 lorie 只监听 `$TMPDIR/.X11-unix/` 下的 UNIX socket，而该目录位于应用私有
     * 的 rootfs 内，其他应用受 Android 沙箱限制无法访问；因此这里不额外引入
     * Xauthority 也不会扩大暴露面。反过来说，`$TMPDIR` **绝不能**指向
     * `/sdcard`、`/data/local/tmp` 等世界可读位置。
     */
    fun toArgv(): List<String> = buildList {
        add(":$displayNumber")
        add("-ac")
        add("-noreset")
        add("-screen")
        add("${config.width}x${config.height}x${config.colorDepth}")
        add("-dpi")
        add(config.dpi.toString())
    }
}

/**
 * X server 进程的启动入口抽象。
 *
 * 之所以是接口而不是直接调 JNI：`cmdentrypoint.cpp` 把 libc 的 `exit()` / `abort()`
 * 覆盖成了 `_exit()`——
 *
 * ```
 * void abort(void) { _exit(134); }
 * void exit(int code) { _exit(code); }
 * ```
 *
 * 而 X server 主线程执行的是 `exit(dix_main(argc, argv, envp))`。也就是说 X server
 * 任何一次 `FatalError()` 或正常退出，都会**直接终止整个进程**，不走 JVM 的关闭流程、
 * 不抛异常、不可捕获。若 in-process 启动，X server 崩溃将连带杀掉 TinaIDE 主进程，
 * 用户会看到 IDE 无提示消失、未保存的编辑内容丢失。
 *
 * 因此 X server 必须运行在独立进程中（与既有 `:sdl` / `:crash` 进程隔离一致），
 * 由该进程通过 [com.termux.x11.CmdEntryPoint] 调用 JNI，再把 X 连接的
 * `ParcelFileDescriptor` 交回渲染侧。
 */
interface X11ServerLauncher {
    /**
     * 在独立进程中启动 X server。
     *
     * @return 成功时返回 guest 侧可用的 DISPLAY 值
     */
    suspend fun launch(args: X11ServerArgs, layout: X11SocketLayout): Result<String>

    /** 终止 X server 进程。 */
    suspend fun terminate()

    /** X server 进程当前是否存活。 */
    fun isAlive(): Boolean

    /**
     * 在 X server 所在进程里拉起 guest 桌面会话，并交给那一侧看护重启。
     *
     * 之所以不由主进程 spawn：init-proot.sh 带 `--kill-on-exit`，proot 树的存亡跟着
     * spawn 它的进程。会话必须挂在常驻的 X server 进程上，桌面才能在 IDE 窗口关闭、
     * 甚至主进程被系统回收之后继续活着。
     *
     * 幂等：已有存活会话时直接成功返回。
     */
    suspend fun startGuestSession(
        plan: LinuxDesktopHostProcessPlan,
        restartPolicy: LinuxDesktopRestartPolicy,
    ): Result<Unit>

    /** 终止 guest 桌面会话，但保留 X server。 */
    suspend fun stopGuestSession()

    /** guest 桌面会话是否存活。 */
    fun isGuestSessionRunning(): Boolean

    /** guest 会话看护阶段；无法获知时返回 `null`。 */
    fun guestSessionPhase(): LinuxDesktopSupervisorPhase?
}
