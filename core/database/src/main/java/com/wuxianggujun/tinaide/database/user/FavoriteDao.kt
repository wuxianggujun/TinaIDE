package com.wuxianggujun.tinaide.database.user

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 收藏 DAO
 */
@Dao
interface FavoriteDao {

    /**
     * 获取所有收藏（Flow 自动监听数据变化）
     */
    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    fun getAllFavoritesFlow(): Flow<List<FavoriteEntity>>

    /**
     * 获取所有收藏（一次性查询）
     */
    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    suspend fun getAllFavorites(): List<FavoriteEntity>

    /**
     * 分页获取收藏
     */
    @Query("SELECT * FROM favorites ORDER BY added_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getFavoritesPaged(limit: Int, offset: Int): List<FavoriteEntity>

    /**
     * 根据 ID 获取收藏
     */
    @Query("SELECT * FROM favorites WHERE plugin_id = :pluginId LIMIT 1")
    suspend fun getFavoriteByPluginId(pluginId: String): FavoriteEntity?

    /**
     * 插入收藏
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    /**
     * 批量插入收藏
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(favorites: List<FavoriteEntity>)

    /**
     * 删除收藏
     */
    @Query("DELETE FROM favorites WHERE plugin_id = :pluginId")
    suspend fun deleteFavoriteByPluginId(pluginId: String)

    /**
     * 清空所有收藏
     */
    @Query("DELETE FROM favorites")
    suspend fun deleteAllFavorites()

    /**
     * 获取收藏总数
     */
    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun getFavoritesCount(): Int
}
