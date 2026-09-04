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
}
