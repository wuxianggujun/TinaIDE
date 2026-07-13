package com.wuxianggujun.tinaide.storage.di

import android.content.Context
import com.wuxianggujun.tinaide.storage.FileDeletionService
import com.wuxianggujun.tinaide.storage.ProjectLocationManager
import com.wuxianggujun.tinaide.storage.StorageCleanupManager
import com.wuxianggujun.tinaide.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val storageModule = module {
    single { StorageManager(get()).also { it.onCreate() } }
    single {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        ProjectLocationManager(get(), scope).also { it.onCreate() }
    }
    single { StorageCleanupManager(get()) }
    single { FileDeletionService(get<Context>()) }
}
