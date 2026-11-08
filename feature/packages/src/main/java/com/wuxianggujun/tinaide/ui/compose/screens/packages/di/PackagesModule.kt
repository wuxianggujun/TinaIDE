package com.wuxianggujun.tinaide.ui.compose.screens.packages.di

import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.PackageManagerImpl
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.ui.compose.screens.packages.PackageManagerViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.packages.VcpkgMarketViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val packagesModule = module {
    viewModel { VcpkgMarketViewModel(get(), get()) }
    factory<PackageManager> {
        val apiClient = PackageApiClient.getInstance(get())
        val installStateStore = LocalInstallStateStore(get())
        val prootEnv = PRootEnvironment(context = get(), configManager = get())
        PackageManagerImpl(get(), apiClient, installStateStore, prootEnv = prootEnv)
    }
    viewModel { parameters -> PackageManagerViewModel(get(), get(), parameters.getOrNull<Boolean>() ?: false) }
}
