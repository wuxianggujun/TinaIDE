package com.wuxianggujun.tinaide.di

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.IAppNavigator
import com.wuxianggujun.tinaide.core.symbol.IProjectSymbolIndexService
import com.wuxianggujun.tinaide.file.FileManager
import com.wuxianggujun.tinaide.file.IFileDeletionOperations
import com.wuxianggujun.tinaide.file.IFileOperations
import com.wuxianggujun.tinaide.file.IFileWatchService
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.IProjectSession
import com.wuxianggujun.tinaide.file.IRecentFilesProvider
import org.koin.dsl.module

val appModule = module {
    // 应用级导航器（供 feature 模块跳转 app 内 Activity）
    single<IAppNavigator> {
        object : IAppNavigator {
            override fun navigateToProjectManager(context: Context) {
                val intent = Intent(context, com.wuxianggujun.tinaide.ui.MainPortalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)
            }

            override fun navigateToTerminal(context: Context, workDir: String) {
                val intent = Intent(context, com.wuxianggujun.tinaide.ui.TerminalActivity::class.java).apply {
                    putExtra(com.wuxianggujun.tinaide.ui.TerminalActivity.EXTRA_WORK_DIR, workDir)
                    putExtra(com.wuxianggujun.tinaide.ui.TerminalActivity.EXTRA_PROJECT_PATH, workDir)
                }
                context.startActivity(intent)
            }
        }
    }

    // 文件管理器（app 模块实现）
    single {
        FileManager(
            context = get(),
            configManager = get(),
            projectLocationManager = get(),
            storageManager = get(),
            fileDeletionService = get(),
            projectSymbolIndexServiceProvider = { getKoin().getOrNull<IProjectSymbolIndexService>() },
        ).also { it.onCreate() }
    }
    single<IFileOperations> { get<FileManager>() }
    single<IFileDeletionOperations> { get<FileManager>() }
    single<IRecentFilesProvider> { get<FileManager>() }
    single<IFileWatchService> { get<FileManager>() }
    single<IProjectContext> { get<FileManager>() }
    single<IProjectSession> { get<FileManager>() }
}
