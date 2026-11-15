package com.wuxianggujun.tinaide.core.user

import kotlinx.coroutines.flow.Flow

/**
 * 用户内容仓库接口
 *
 * 管理用户的收藏、下载历史等纯本地内容
 */
interface UserContentRepository {

    /**
     * 获取收藏列表（Flow，自动监听变化）
     */
    fun getFavoritesFlow(): Flow<List<FavoritePlugin>>

    /**
     * 获取收藏列表
     *
     * @param page 页码（从 1 开始）
     * @param pageSize 每页数量
     * @return 收藏列表响应
     */
    suspend fun getFavorites(page: Int = 1, pageSize: Int = 20): Result<FavoritesResponse>

    /**
     * 添加收藏
     *
     * @param plugin 插件信息
     * @return 操作结果
     */
    suspend fun addFavorite(plugin: FavoritePlugin): Result<Unit>

    /**
     * 移除收藏
     *
     * @param pluginId 插件 ID
     * @return 操作结果
     */
    suspend fun removeFavorite(pluginId: String): Result<Unit>

    /**
     * 检查是否已收藏
     *
     * @param pluginId 插件 ID
     * @return 是否已收藏
     */
    suspend fun isFavorite(pluginId: String): Boolean

    /**
     * 获取下载历史
     *
     * @param page 页码（从 1 开始）
     * @param pageSize 每页数量
     * @return 下载历史响应
     */
    suspend fun getDownloadHistory(page: Int = 1, pageSize: Int = 20): Result<DownloadHistoryResponse>

    /**
     * 添加下载历史
     *
     * @param item 下载历史项
     * @return 操作结果
     */
    suspend fun addDownloadHistory(item: DownloadHistoryItem): Result<Unit>

    /**
     * 移除下载历史
     *
     * @param id 历史记录 ID
     * @return 操作结果
     */
    suspend fun removeDownloadHistory(id: String): Result<Unit>

    /**
     * 清空下载历史
     *
     * @return 操作结果
     */
    suspend fun clearDownloadHistory(): Result<Unit>
}
