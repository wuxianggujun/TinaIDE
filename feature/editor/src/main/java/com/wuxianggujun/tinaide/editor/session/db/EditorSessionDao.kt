package com.wuxianggujun.tinaide.editor.session.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 编辑器会话 DAO
 */
@Dao
interface EditorSessionDao {

    /**
     * 获取项目的编辑器会话（Flow）
     */
    @Query("SELECT * FROM editor_sessions WHERE project_path = :projectPath LIMIT 1")
    fun getSessionFlow(projectPath: String): Flow<EditorSessionEntity?>

    /**
     * 获取项目的编辑器会话（一次性查询）
     */
    @Query("SELECT * FROM editor_sessions WHERE project_path = :projectPath LIMIT 1")
    suspend fun getSession(projectPath: String): EditorSessionEntity?

    /**
     * 获取项目的所有文件状态
     */
    @Query("SELECT * FROM editor_file_states WHERE project_path = :projectPath ORDER BY file_path ASC")
    suspend fun getFileStates(projectPath: String): List<EditorFileStateEntity>

    /**
     * 获取单个文件状态
     */
    @Query("SELECT * FROM editor_file_states WHERE project_path = :projectPath AND file_path = :filePath LIMIT 1")
    suspend fun getFileState(projectPath: String, filePath: String): EditorFileStateEntity?

    /**
     * 插入或更新会话
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: EditorSessionEntity): Long

    /**
     * 插入或更新文件状态
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileState(fileState: EditorFileStateEntity): Long

    /**
     * 批量插入或更新文件状态
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileStates(fileStates: List<EditorFileStateEntity>)

    @Transaction
    suspend fun replaceProjectSession(
        projectPath: String,
        session: EditorSessionEntity,
        fileStates: List<EditorFileStateEntity>,
    ) {
        insertSession(session)
        deleteFileStates(projectPath)
        insertFileStates(fileStates)
    }

    @Query(
        """
        UPDATE editor_sessions
        SET project_path = :newProjectPath,
            active_file = CASE
                WHEN active_file = :oldProjectPath OR substr(active_file, 1, length(:oldProjectPath) + 1) = :oldProjectPath || '/'
                THEN :newProjectPath || substr(active_file, length(:oldProjectPath) + 1)
                ELSE active_file
            END
        WHERE project_path = :oldProjectPath
        """
    )
    suspend fun migrateSessionProjectPath(oldProjectPath: String, newProjectPath: String): Int

    @Query(
        """
        UPDATE editor_file_states
        SET project_path = :newProjectPath,
            file_path = CASE
                WHEN file_path = :oldProjectPath OR substr(file_path, 1, length(:oldProjectPath) + 1) = :oldProjectPath || '/'
                THEN :newProjectPath || substr(file_path, length(:oldProjectPath) + 1)
                ELSE file_path
            END
        WHERE project_path = :oldProjectPath
        """
    )
    suspend fun migrateFileStateProjectPath(oldProjectPath: String, newProjectPath: String): Int

    /**
     * 删除项目的会话
     */
    @Query("DELETE FROM editor_sessions WHERE project_path = :projectPath")
    suspend fun deleteSession(projectPath: String): Int

    /**
     * 删除项目的所有文件状态
     */
    @Query("DELETE FROM editor_file_states WHERE project_path = :projectPath")
    suspend fun deleteFileStates(projectPath: String): Int

    /**
     * 删除单个文件状态
     */
    @Query("DELETE FROM editor_file_states WHERE project_path = :projectPath AND file_path = :filePath")
    suspend fun deleteFileState(projectPath: String, filePath: String): Int

    /**
     * 更新活动文件
     */
    @Query("UPDATE editor_sessions SET active_file = :activeFile, updated_at = :updatedAt WHERE project_path = :projectPath")
    suspend fun updateActiveFile(projectPath: String, activeFile: String?, updatedAt: Long): Int

    /**
     * 清空项目的所有会话数据（会话 + 文件状态）
     */
    @Transaction
    suspend fun clearProjectSession(projectPath: String) {
        deleteSession(projectPath)
        deleteFileStates(projectPath)
    }
}
