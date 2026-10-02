package com.wuxianggujun.tinaide.core.proot

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linuxdesktop.LinuxDesktopGuestPlan
import com.wuxianggujun.tinaide.core.linuxdesktop.LinuxDesktopHostProcessPlan
import com.wuxianggujun.tinaide.core.linuxdesktop.LinuxDesktopHostProcessPlanner

/**
 * 用 PRoot 把 guest 桌面命令包成一条 host 命令行。
 *
 * 这个适配器存在的意义是打破依赖方向：`:core:linux-desktop` 不能依赖 `:core:proot`
 * （后者已经依赖前者，反向即 Gradle 循环），但桌面会话又必须由 `:x11` 进程 spawn
 * 一个完整的 proot 命令。于是主进程这边用 [PRootManager] 算出 argv 与环境，
 * 通过 [LinuxDesktopHostProcessPlanner] 接口交回去。
 *
 * 走 [LinuxEnvironmentProvider] 而不是自己 new 一个 [PRootEnvironment]：那样会得到
 * 第二份 PRootManager 缓存，与应用其余部分对"当前活动 rootfs"的认知产生分歧。
 * 每次都重新解析也是刻意的——活动 profile 可以被切换，缓存住会让桌面指向旧 rootfs。
 */
class PRootDesktopHostProcessPlanner(
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider,
) : LinuxDesktopHostProcessPlanner {

    override fun plan(guestPlan: LinuxDesktopGuestPlan): LinuxDesktopHostProcessPlan {
        val environment = linuxEnvironmentProvider.get()
        // 明确失败而不是静默退化：拿不到 PRoot 环境时没有任何可用的 host 命令行，
        // 硬造一条只会让 :x11 exec 出一个立刻退出的进程。
        check(environment is PRootEnvironment) {
            "Linux environment is not PRoot-backed; cannot build a desktop host command line"
        }
        val plan = environment.getPRootManager().buildHostLaunchPlan(
            command = guestPlan.command,
            workDir = guestPlan.workingDirectory,
            extraEnv = guestPlan.environment,
        )
        return LinuxDesktopHostProcessPlan(
            argv = plan.argv,
            environment = plan.environment,
            workingDirectory = plan.workingDirectory,
        )
    }
}
