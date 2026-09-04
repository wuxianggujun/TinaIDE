package com.wuxianggujun.tinaide.core.linuxdesktop.di

import android.content.Context
import com.wuxianggujun.tinaide.core.linuxdesktop.LinuxDesktopService
import com.wuxianggujun.tinaide.core.linuxdesktop.UbuntuLinuxDesktopCoordinator
import com.wuxianggujun.tinaide.core.linuxdesktop.X11ServerLauncher
import com.wuxianggujun.tinaide.core.linuxdesktop.X11ServerProcessLauncher
import com.wuxianggujun.tinaide.core.linuxdesktop.X11SocketLayoutProvider
import org.koin.dsl.module

val linuxDesktopModule = module {
    single<X11ServerLauncher> { X11ServerProcessLauncher(get<Context>()) }
    single<LinuxDesktopService> {
        // provider 缺失时退化成 { null }：启动会明确失败，而不是指向不存在的 socket。
        val layoutProvider = getOrNull<X11SocketLayoutProvider>()
        LinuxDesktopService.create(
            serverLauncher = get(),
            socketLayoutProvider = { layoutProvider?.current() },
        )
    }
    single {
        UbuntuLinuxDesktopCoordinator(
            linuxEnvironmentProvider = get(),
            desktopService = get(),
        )
    }
}
