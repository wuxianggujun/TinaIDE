package com.wuxianggujun.tinaide.di

import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.PluginProjectActions
import com.wuxianggujun.tinaide.core.git.GitService
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.plugindev.AndroidPluginProjectActions
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.CompilerViewModel
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.GlobalSearchViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.MainViewModel
import com.wuxianggujun.tinaide.ui.MultiTerminalViewModel
import com.wuxianggujun.tinaide.ui.ProjectManagerViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.main.market.MarketScreenViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.main.profile.DownloadHistoryViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.main.profile.FavoritesViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appViewModelModule = module {
    single<PluginProjectActions> { AndroidPluginProjectActions(androidApplication(), get()) }

    factory {
        CompileProjectUseCase(
            appContext = get(),
            projectContext = get(),
            outputManager = get(),
            editorManagerProvider = { get<IEditorManager>() },
            linuxEnvironmentProvider = get(),
            orchestratorProvider = { get() },
            strategyRegistry = get(),
            buildContextFactory = get(),
            terminalCommandBuilder = get(),
            eventBus = get(),
            pluginProjectActions = get(),
        )
    }

    single { GitService(get()) }

    viewModel { CompilerViewModel(get(), get(), get()) }
    viewModel { BottomPanelViewModel(androidApplication(), get()) }
    viewModel { EditorStateViewModel() }
    viewModel { MainViewModel(androidApplication(), get()) }
    viewModel { DebugViewModel(androidApplication(), get(), get()) }
    viewModel { GitViewModel(get()) }
    viewModel {
        MainActivityActionsViewModel(
            application = androidApplication(),
            editorManager = get(),
            projectContext = get(),
            projectSession = get(),
            bookmarkRepository = get(),
            linuxEnvironmentProvider = get(),
        )
    }
    viewModel { ProjectManagerViewModel(androidApplication(), get(), get(), get(), get()) }
    viewModel { FavoritesViewModel(get()) }
    viewModel { DownloadHistoryViewModel(get()) }
    viewModel { MarketScreenViewModel(androidApplication(), get()) }
    viewModel { MultiTerminalViewModel(androidApplication(), get()) }
    viewModel { GlobalSearchViewModel(androidApplication()) }
}
