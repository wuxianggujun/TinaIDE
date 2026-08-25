package com.wuxianggujun.tinaide.plugin.di

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.plugin.PluginLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginManager
import org.koin.dsl.module

val pluginModule = module {
    // 运行时必须复用 PluginManager 的全局单例，否则设置页和主界面会各持一份状态。
    single { PluginManager.getInstance(get()) }
    single<LinuxEnvironmentProvider> {
        PluginLinuxEnvironmentProvider(
            context = get(),
            pluginManager = get(),
            configManager = get(),
        )
    }
    single { PluginSnippetManager(get()).also { it.onCreate() } }
    single { LspPluginManager(get(), get(), get()).also { it.onCreate() } }
    single(createdAtStart = true) {
        ScriptPluginManager.getInstance(get()).also { manager ->
            manager.setProjectRootProvider {
                get<IProjectContext>().getCurrentProject()?.rootPath
            }
        }
    }
}
