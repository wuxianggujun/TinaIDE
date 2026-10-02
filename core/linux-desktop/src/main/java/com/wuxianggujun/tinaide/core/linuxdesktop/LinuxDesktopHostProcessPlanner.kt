package com.wuxianggujun.tinaide.core.linuxdesktop

/**
 * 把一份 guest 命令翻译成可以直接 `exec` 的 host 命令行。
 *
 * 实现方住在 `:core:proot`（只有那边知道 proot 二进制、init 脚本和一整套
 * `ROOTFS_PATH` / `LINKER` / `PROOT_*` 环境变量），并像 [X11SocketLayoutProvider]
 * 一样从 `prootModule` 注册进来。本模块不能反向依赖 `:core:proot`——proot 已经依赖
 * 本模块，反过来就是 Gradle 循环。
 */
fun interface LinuxDesktopHostProcessPlanner {
    /**
     * @param guestPlan guest 侧要跑的命令与环境
     * @return 可交给 `:x11` 进程 `exec` 的 host 命令行；环境未就绪时抛异常
     */
    fun plan(guestPlan: LinuxDesktopGuestPlan): LinuxDesktopHostProcessPlan
}
