package com.wuxianggujun.tinaide.terminal.di

import com.wuxianggujun.tinaide.core.terminal.IGuestDevPackagesInstaller
import com.wuxianggujun.tinaide.core.terminal.ILocaleInstaller
import com.wuxianggujun.tinaide.core.terminal.IShellInstaller
import com.wuxianggujun.tinaide.core.terminal.IShellResolver
import com.wuxianggujun.tinaide.core.terminal.ITerminalPreferences
import com.wuxianggujun.tinaide.core.terminal.ITerminalSessionManager
import com.wuxianggujun.tinaide.core.terminal.ITerminalThemeProvider
import com.wuxianggujun.tinaide.terminal.install.GuestDevelopmentPackagesInstaller
import com.wuxianggujun.tinaide.terminal.locale.LocaleInstaller
import com.wuxianggujun.tinaide.terminal.preferences.TerminalPreferences
import com.wuxianggujun.tinaide.terminal.session.TerminalSessionManager
import com.wuxianggujun.tinaide.terminal.session.TerminalSessionManagerAdapter
import com.wuxianggujun.tinaide.terminal.shell.ShellResolverAdapter
import com.wuxianggujun.tinaide.terminal.shell.ZshInstaller
import com.wuxianggujun.tinaide.terminal.theme.TerminalThemeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val terminalModule = module {
    // 终端配置
    single<ITerminalPreferences> { TerminalPreferences.get(get()) }

    // 终端主题
    single<ITerminalThemeProvider> { TerminalThemeProvider() }

    // Locale 安装器
    factory<ILocaleInstaller> {
        LocaleInstaller(
            context = get(),
            linuxEnvironmentProvider = get(),
        )
    }

    // Guest 开发基础包安装器
    factory<IGuestDevPackagesInstaller> {
        GuestDevelopmentPackagesInstaller(
            context = get(),
            linuxEnvironmentProvider = get(),
        )
    }

    // Shell 安装器（Zsh）
    factory<IShellInstaller> {
        ZshInstaller(
            context = get(),
            linuxEnvironmentProvider = get(),
        )
    }

    // Shell 解析器
    factory<IShellResolver> {
        ShellResolverAdapter(
            context = get(),
            linuxEnvironmentProvider = get(),
        )
    }

    // TerminalSessionManager（feature:terminal 层具体类）
    single {
        TerminalSessionManager(
            application = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            linuxEnvironmentProvider = get(),
        )
    }

    // ITerminalSessionManager（core:common 层接口）
    single<ITerminalSessionManager> { TerminalSessionManagerAdapter(get()) }
}
