package com.wuxianggujun.tinaide.editor.bookmark.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 书签 DAO
 */
@Dao
interface BookmarkDao {

    /**
     * 获取指定项目的所有书签（Flow，自动监听数据变化）
     */
    @Query("SELECT * FROM bookmarks WHERE project_path = :projectPath ORDER BY file_path ASC, line ASC")
    fun getBookmarksFlow(projectPath: String): Flow<List<BookmarkEntity>>

    /**
     * 获取指定项目的所有书签（一次性查询）
     */
    @Query("SELECT * FROM bookmarks WHERE project_path = :projectPath ORDER BY file_path ASC, line ASC")
    suspend fun getBookmarks(projectPath: String): List<BookmarkEntity>

    /**
     * 获取指定文件的所有书签
     */
    @Query("SELECT * FROM bookmarks WHERE project_path = :projectPath AND file_path = :filePath ORDER BY line ASC")
    suspend fun getBookmarksByFile(projectPath: String, filePath: String): List<BookmarkEntity>

    /**
     * 获取指定位置的书签
     */
    @Query("SELECT * FROM bookmarks WHERE project_path = :projectPath AND file_path = :filePath AND line = :line LIMIT 1")
    suspend fun getBookmark(projectPath: String, filePath: String, line: Int): BookmarkEntity?

    /**
     * 插入书签
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    /**
     * 批量插入书签
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>)

    @Transaction
    suspend fun replaceProjectBookmarks(projectPath: String, bookmarks: List<BookmarkEntity>) {
        deleteAllBookmarks(projectPath)
        insertBookmarks(bookmarks)
    }

    @Query(
        """
        UPDATE bookmarks
        SET project_path = :newProjectPath,
            file_path = CASE
                WHEN file_path = :oldProjectPath OR substr(file_path, 1, length(:oldProjectPath) + 1) = :oldProjectPath || '/'
                THEN :newProjectPath || substr(file_path, length(:oldProjectPath) + 1)
                ELSE file_path
            END
        WHERE project_path = :oldProjectPath
        """
    )
    suspend fun migrateProjectPath(oldProjectPath: String, newProjectPath: String): Int

    /**
     * 更新书签
     */
    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    /**
     * 删除书签
     */
    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    /**
     * 删除指定位置的书签
     */
    @Query("DELETE FROM bookmarks WHERE project_path = :projectPath AND file_path = :filePath AND line = :line")
    suspend fun deleteBookmarkAt(projectPath: String, filePath: String, line: Int): Int

    /**
     * 删除指定项目的所有书签
     */
    @Query("DELETE FROM bookmarks WHERE project_path = :projectPath")
    suspend fun deleteAllBookmarks(projectPath: String): Int

    /**
     * 删除指定文件的所有书签
     */
    @Query("DELETE FROM bookmarks WHERE project_path = :projectPath AND file_path = :filePath")
    suspend fun deleteBookmarksByFile(projectPath: String, filePath: String): Int

    /**
     * 获取指定项目的书签总数
     */
    @Query("SELECT COUNT(*) FROM bookmarks WHERE project_path = :projectPath")
    suspend fun getBookmarksCount(projectPath: String): Int

    /**
     * 更新书签备注
     */
    @Query("UPDATE bookmarks SET note = :note WHERE project_path = :projectPath AND file_path = :filePath AND line = :line")
    suspend fun updateNote(projectPath: String, filePath: String, line: Int, note: String): Int

    /**
     * 查找下一个书签（用于导航）
     */
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE project_path = :projectPath
        AND (file_path > :currentFilePath OR (file_path = :currentFilePath AND line > :currentLine))
        ORDER BY file_path ASC, line ASC
        LIMIT 1
    """
    )
    suspend fun findNext(projectPath: String, currentFilePath: String, currentLine: Int): BookmarkEntity?

    /**
     * 查找上一个书签（用于导航）
     */
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE project_path = :projectPath
        AND (file_path < :currentFilePath OR (file_path = :currentFilePath AND line < :currentLine))
        ORDER BY file_path DESC, line DESC
        LIMIT 1
    """
    )
    suspend fun findPrevious(projectPath: String, currentFilePath: String, currentLine: Int): BookmarkEntity?
}
