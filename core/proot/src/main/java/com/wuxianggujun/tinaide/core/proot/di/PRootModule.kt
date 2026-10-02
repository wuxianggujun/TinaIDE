package com.wuxianggujun.tinaide.core.proot.di

import com.wuxianggujun.tinaide.core.linuxdesktop.LinuxDesktopHostProcessPlanner
import com.wuxianggujun.tinaide.core.linuxdesktop.X11SocketLayout
import com.wuxianggujun.tinaide.core.linuxdesktop.X11SocketLayoutProvider
import com.wuxianggujun.tinaide.core.proot.InstallLogManager
import com.wuxianggujun.tinaide.core.proot.PRootDesktopHostProcessPlanner
import com.wuxianggujun.tinaide.core.proot.RootfsProfileStore
import com.wuxianggujun.tinaide.core.proot.SelfHostedLinuxDistroRuntime
import org.koin.dsl.module

val prootModule = module {
    single { InstallLogManager(get()) }
    single { RootfsProfileStore(get(), get()) }
    // X server 的 $TMPDIR 必须落在当前活动 Ubuntu profile 的 <rootfs>/tmp 上，
    // 那是 host 与 guest 唯一共享的 inode（理由见 X11SocketLayout）。
    single<X11SocketLayoutProvider> {
        val store = get<RootfsProfileStore>()
        X11SocketLayoutProvider {
            val profile = store.getActiveProfileForDistro(
                SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID,
            )
            if (profile == null || !store.isInstalled(profile)) {
                null
            } else {
                X11SocketLayout.forRootfs(profile.rootfsPath)
            }
        }
    }
    // 桌面会话的 host 命令行只有 PRoot 侧算得出来，但会话要在 `:x11` 进程里 spawn。
    // 同样从这边注册进去，避免 :core:linux-desktop 反向依赖 :core:proot（Gradle 循环）。
    single<LinuxDesktopHostProcessPlanner> {
        PRootDesktopHostProcessPlanner(linuxEnvironmentProvider = get())
    }
}
