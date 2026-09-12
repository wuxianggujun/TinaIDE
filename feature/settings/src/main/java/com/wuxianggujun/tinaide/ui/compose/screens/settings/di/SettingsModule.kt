package com.wuxianggujun.tinaide.ui.compose.screens.settings.di

import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.settings.StorageCleanupViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsModule = module {
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) }
    viewModel { StorageCleanupViewModel(get()) }
}
