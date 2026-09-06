package com.wuxianggujun.tinaide.core.proot.di

import com.wuxianggujun.tinaide.core.linuxdesktop.X11SocketLayout
import com.wuxianggujun.tinaide.core.linuxdesktop.X11SocketLayoutProvider
import com.wuxianggujun.tinaide.core.proot.InstallLogManager
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
}
