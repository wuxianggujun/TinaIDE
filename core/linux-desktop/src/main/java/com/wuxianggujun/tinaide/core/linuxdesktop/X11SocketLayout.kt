package com.wuxianggujun.tinaide.core.linuxdesktop

import java.io.File

/**
 * host 侧 X server 与 PRoot guest 之间的唯一交汇点：X11 抽象/UNIX socket 所在目录。
 *
 * lorie 的 `CmdEntryPoint.start()`（cmdentrypoint.cpp）不接受"socket 路径"参数，
 * 它只读 `$TMPDIR`，然后把监听地址硬编码成 `$TMPDIR/.X11-unix/X<display>`。
 * 同时它还用 `dirname($TMPDIR)` 去推导 chroot 根，据此寻找 xkb 数据与字体：
 *
 * ```
 * asprintf(&xtrans_unix_path_x11, "%s/.X11-unix/X", tmp);   // 监听地址
 * snprintf(current_path, ..., "%s/usr/share/X11/xkb", dirname(tmp));  // XKB_CONFIG_ROOT
 * ```
 *
 * 所以要让 guest 里的 X client 连上 host 的 X server，`$TMPDIR` 必须满足两点：
 *
 * 1. host 进程可写（X server 要在里面 bind socket）；
 * 2. 在 guest 里是**同一个** inode，且 guest 路径也叫 `/tmp`（client 端 libX11 同样
 *    按 `/tmp/.X11-unix/X<n>` 拼接，PRoot 不会替它改写路径）。
 *
 * 满足这两点的做法就是把 `<rootfs>/tmp` 同时交给两边：host 用绝对路径，guest 用 `/tmp`。
 * 这样 `dirname` 恰好落在 rootfs 根上，lorie 探测 `<rootfs>/usr/share/X11/xkb` 也能命中
 * guest 里 `xkb-data` 装出来的真实数据 —— 无需再往 APK 里塞一份 xkb 副本。
 *
 * 注意：`PRootManager.buildPRootCommandLine()` 目前把 `<rootfs>/tmp` 绑到 guest 的
 * `/dev/shm`，而 guest `/tmp` 用的是 rootfs 内自己的目录。两者都指向 rootfs 内部，
 * 但**不是**同一个路径，所以此处显式以 `<rootfs>/tmp` 为准并要求 PRoot 侧对齐，
 * 而不是假定现有绑定已经够用。
 */
data class X11SocketLayout(
    /** host 侧可写的 X11 socket 父目录，对应 X server 的 `$TMPDIR` */
    val hostTmpDir: File,
    /** 同一目录在 PRoot guest 内的路径，X client 按此拼接 socket 地址 */
    val guestTmpDir: String,
) {
    init {
        require(guestTmpDir.startsWith("/")) { "guestTmpDir must be absolute: $guestTmpDir" }
    }

    /** `$TMPDIR/.X11-unix`，X server 启动前必须存在且可写 */
    val hostSocketDir: File get() = File(hostTmpDir, X11_SOCKET_DIR_NAME)

    /**
     * lorie 用 `dirname($TMPDIR)` 推导 chroot 根，再据此定位 xkb 数据。
     * 这里显式暴露出来，方便调用方在启动前校验 xkb 是否真的存在。
     */
    val hostXkbConfigRoot: File get() = File(hostTmpDir.parentFile, XKB_RELATIVE_PATH)

    fun hostSocketFile(displayNumber: Int): File =
        File(hostSocketDir, "X$displayNumber")

    /** 供 guest 侧 client 使用的 DISPLAY 值 */
    fun guestDisplay(displayNumber: Int): String = ":$displayNumber"

    /**
     * 准备 socket 目录。X server 只会 bind，不会 mkdir 父目录，所以必须提前建好。
     *
     * 权限设成对 all 可写：guest 里的桌面进程通常以另一个 uid 运行（PRoot 下是
     * 伪 root），而 abstract/UNIX socket 的连接受父目录权限约束。
     */
    fun prepare(): Result<Unit> = runCatching {
        val socketDir = hostSocketDir
        check(socketDir.isDirectory || socketDir.mkdirs()) {
            "Failed to create X11 socket directory: $socketDir"
        }
        // setWritable(true, false) = ownerOnly false，即放开 group/other。
        socketDir.setReadable(true, false)
        socketDir.setWritable(true, false)
        socketDir.setExecutable(true, false)
    }

    /**
     * 清掉上一次会话残留的 socket 文件。
     *
     * X server 异常退出（`_exit()` 路径不会做清理）会留下 stale socket，下次 bind
     * 到同一 display 就会失败。
     */
    fun clearStaleSocket(displayNumber: Int) {
        hostSocketFile(displayNumber).takeIf { it.exists() }?.delete()
    }

    companion object {
        private const val X11_SOCKET_DIR_NAME = ".X11-unix"
        private const val XKB_RELATIVE_PATH = "usr/share/X11/xkb"

        /** guest 内的 `/tmp`；libX11 客户端按此路径查找 socket，不可更改。 */
        const val GUEST_TMP_DIR: String = "/tmp"

        /**
         * 按 PRoot rootfs 布局构造。`<rootfs>/tmp` 在 host 与 guest 里是同一个 inode，
         * 是 host X server 与 guest client 唯一能对上的位置。
         */
        fun forRootfs(rootfsPath: String): X11SocketLayout = X11SocketLayout(
            hostTmpDir = File(rootfsPath, "tmp"),
            guestTmpDir = GUEST_TMP_DIR,
        )
    }
}
