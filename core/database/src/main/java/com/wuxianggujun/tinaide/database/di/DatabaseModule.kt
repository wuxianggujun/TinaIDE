package com.wuxianggujun.tinaide.database.di

import com.wuxianggujun.tinaide.core.user.UserContentRepository
import com.wuxianggujun.tinaide.database.impl.LocalUserContentRepository
import com.wuxianggujun.tinaide.database.user.UserContentDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * 数据库模块 DI 配置
 *
 * 架构说明：
 * - 提供 UserContentDatabase 单例
 * - 提供 DAO 实例
 * - 提供 UserContentRepository 实现（纯本地，不联网）
 * - 遵循依赖倒置原则（DIP）：app 层依赖接口，不依赖具体实现
 */
val databaseModule = module {

    // 提供 UserContentDatabase 单例
    single {
        UserContentDatabase.getInstance(androidContext())
    }

    // 提供 FavoriteDao
    single {
        get<UserContentDatabase>().favoriteDao()
    }

    // 提供 DownloadHistoryDao
    single {
        get<UserContentDatabase>().downloadHistoryDao()
    }

    // 提供 UserContentRepository 实现（纯本地）
    single<UserContentRepository> {
        LocalUserContentRepository(
            favoriteDao = get(),
            downloadHistoryDao = get()
        )
    }
}
