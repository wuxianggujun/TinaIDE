package com.wuxianggujun.tinaide.core.linuxdesktop.di

import android.content.Context
import com.wuxianggujun.tinaide.core.linuxdesktop.LinuxDesktopHostProcessPlanner
import com.wuxianggujun.tinaide.core.linuxdesktop.LinuxDesktopService
import com.wuxianggujun.tinaide.core.linuxdesktop.UbuntuLinuxDesktopCoordinator
import com.wuxianggujun.tinaide.core.linuxdesktop.X11ServerLauncher
import com.wuxianggujun.tinaide.core.linuxdesktop.X11ServerProcessLauncher
import com.wuxianggujun.tinaide.core.linuxdesktop.X11SocketLayoutProvider
import com.wuxianggujun.tinaide.core.linuxdesktop.resolveX11DisplayConfig
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
            // 由 prootModule 提供；缺失时 startSession 会明确失败，而不是 exec 一条空命令。
            hostProcessPlannerProvider = { getKoin().getOrNull<LinuxDesktopHostProcessPlanner>() },
            // 每次启动重算：折叠屏展开、外接显示器接入都会改变屏幕尺寸。
            displayConfigProvider = { resolveX11DisplayConfig(get<Context>()) },
        )
    }
}
