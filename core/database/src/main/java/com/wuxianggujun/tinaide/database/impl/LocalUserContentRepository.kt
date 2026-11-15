package com.wuxianggujun.tinaide.database.impl

import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.core.user.DownloadHistoryItem
import com.wuxianggujun.tinaide.core.user.DownloadHistoryResponse
import com.wuxianggujun.tinaide.core.user.FavoritePlugin
import com.wuxianggujun.tinaide.core.user.FavoritesResponse
import com.wuxianggujun.tinaide.core.user.UserContentRepository
import com.wuxianggujun.tinaide.database.user.DownloadHistoryDao
import com.wuxianggujun.tinaide.database.user.FavoriteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * 纯本地用户内容仓库实现。
 *
 * 收藏与下载历史只保存在本机 Room 数据库，不联网、不同步到任何服务器。
 */
class LocalUserContentRepository(
    private val favoriteDao: FavoriteDao,
    private val downloadHistoryDao: DownloadHistoryDao,
) : UserContentRepository {

    companion object {
        private const val TAG = "LocalUserContentRepo"
    }

    // ===== 收藏相关 =====

    override fun getFavoritesFlow(): Flow<List<FavoritePlugin>> = favoriteDao.getAllFavoritesFlow().map { entities ->
        entities.map { it.toFavoritePlugin() }
    }

    override suspend fun getFavorites(page: Int, pageSize: Int): Result<FavoritesResponse> = try {
        val offset = (page - 1) * pageSize
        val entities = favoriteDao.getFavoritesPaged(pageSize, offset)
        val total = favoriteDao.getFavoritesCount()

        val plugins = entities.map { it.toFavoritePlugin() }
        Result.success(
            FavoritesResponse(
                plugins = plugins,
                total = total,
                page = page,
                pageSize = pageSize
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun addFavorite(plugin: FavoritePlugin): Result<Unit> = try {
        favoriteDao.insertFavorite(plugin.toFavoriteEntity())
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "addFavorite failed")
        Result.failure(e)
    }

    override suspend fun removeFavorite(pluginId: String): Result<Unit> = try {
        favoriteDao.deleteFavoriteByPluginId(pluginId)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "removeFavorite failed")
        Result.failure(e)
    }

    override suspend fun isFavorite(pluginId: String): Boolean = try {
        favoriteDao.getFavoriteByPluginId(pluginId) != null
    } catch (e: Exception) {
        false
    }

    // ===== 下载历史相关 =====

    override suspend fun getDownloadHistory(page: Int, pageSize: Int): Result<DownloadHistoryResponse> = try {
        val offset = (page - 1) * pageSize
        val entities = downloadHistoryDao.getDownloadsPaged(pageSize, offset)
        val total = downloadHistoryDao.getDownloadsCount()

        val items = entities.map { it.toDownloadHistoryItem() }
        Result.success(
            DownloadHistoryResponse(
                items = items,
                total = total,
                page = page,
                pageSize = pageSize
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun addDownloadHistory(item: DownloadHistoryItem): Result<Unit> = try {
        downloadHistoryDao.insertDownload(item.toDownloadHistoryEntity())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun removeDownloadHistory(id: String): Result<Unit> = try {
        downloadHistoryDao.deleteDownloadById(id)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun clearDownloadHistory(): Result<Unit> = try {
        downloadHistoryDao.deleteAllDownloads()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ===== Entity <-> Domain Model 转换 =====

    private fun com.wuxianggujun.tinaide.database.user.FavoriteEntity.toFavoritePlugin(): FavoritePlugin = FavoritePlugin(
        id = id,
        pluginId = pluginId,
        name = name,
        description = description,
        iconUrl = iconUrl,
        category = category,
        tags = tags?.let { JsonSerializer.decodeOrNull<List<String>>(it) ?: emptyList() },
        latestVersion = latestVersion,
        addedAt = addedAt
    )

    private fun FavoritePlugin.toFavoriteEntity(): com.wuxianggujun.tinaide.database.user.FavoriteEntity = com.wuxianggujun.tinaide.database.user.FavoriteEntity(
        id = id,
        pluginId = pluginId,
        name = name,
        description = description,
        iconUrl = iconUrl,
        category = category,
        tags = tags?.let { JsonSerializer.encode(it) },
        latestVersion = latestVersion,
        addedAt = addedAt
    )

    private fun com.wuxianggujun.tinaide.database.user.DownloadHistoryEntity.toDownloadHistoryItem(): DownloadHistoryItem = DownloadHistoryItem(
        id = id,
        itemType = itemType,
        itemId = itemId,
        version = version,
        downloadedAt = downloadedAt,
        fileSize = fileSize
    )

    private fun DownloadHistoryItem.toDownloadHistoryEntity(): com.wuxianggujun.tinaide.database.user.DownloadHistoryEntity = com.wuxianggujun.tinaide.database.user.DownloadHistoryEntity(
        id = id,
        itemType = itemType,
        itemId = itemId,
        version = version,
        downloadedAt = downloadedAt,
        fileSize = fileSize
    )
}
