package com.wuxianggujun.tinaide.database.user

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 下载历史 DAO
 */
@Dao
interface DownloadHistoryDao {

    /**
     * 获取所有下载历史（Flow 自动监听数据变化）
     */
    @Query("SELECT * FROM download_history ORDER BY downloaded_at DESC")
    fun getAllDownloadsFlow(): Flow<List<DownloadHistoryEntity>>

    /**
     * 获取所有下载历史（一次性查询）
     */
    @Query("SELECT * FROM download_history ORDER BY downloaded_at DESC")
    suspend fun getAllDownloads(): List<DownloadHistoryEntity>

    /**
     * 分页获取下载历史
     */
    @Query("SELECT * FROM download_history ORDER BY downloaded_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getDownloadsPaged(limit: Int, offset: Int): List<DownloadHistoryEntity>

    /**
     * 根据类型分页获取下载历史
     */
    @Query("SELECT * FROM download_history WHERE item_type = :itemType ORDER BY downloaded_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getDownloadsByTypePaged(itemType: String, limit: Int, offset: Int): List<DownloadHistoryEntity>

    /**
     * 插入下载历史
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadHistoryEntity)

    /**
     * 批量插入下载历史
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloads(downloads: List<DownloadHistoryEntity>)

    /**
     * 删除下载历史
     */
    @Delete
    suspend fun deleteDownload(download: DownloadHistoryEntity)

    /**
     * 根据 ID 删除下载历史
     */
    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteDownloadById(id: String)

    /**
     * 清空所有下载历史
     */
    @Query("DELETE FROM download_history")
    suspend fun deleteAllDownloads()

    /**
     * 获取下载历史总数
     */
    @Query("SELECT COUNT(*) FROM download_history")
    suspend fun getDownloadsCount(): Int

    /**
     * 根据类型获取下载历史总数
     */
    @Query("SELECT COUNT(*) FROM download_history WHERE item_type = :itemType")
    suspend fun getDownloadsCountByType(itemType: String): Int
}
