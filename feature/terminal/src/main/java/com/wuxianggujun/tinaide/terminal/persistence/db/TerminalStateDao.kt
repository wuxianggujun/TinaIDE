package com.wuxianggujun.tinaide.terminal.persistence.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 终端状态 DAO
 */
@Dao
interface TerminalStateDao {

    /**
     * 获取项目的终端状态（Flow）
     */
    @Query("SELECT * FROM terminal_states WHERE project_path = :projectPath LIMIT 1")
    fun getStateFlow(projectPath: String): Flow<TerminalStateEntity?>

    /**
     * 获取项目的终端状态（一次性查询）
     */
    @Query("SELECT * FROM terminal_states WHERE project_path = :projectPath LIMIT 1")
    suspend fun getState(projectPath: String): TerminalStateEntity?

    /**
     * 获取项目的所有会话
     */
    @Query("SELECT * FROM terminal_sessions WHERE project_path = :projectPath ORDER BY created_at ASC")
    suspend fun getSessions(projectPath: String): List<TerminalSessionEntity>

    /**
     * 获取单个会话
     */
    @Query("SELECT * FROM terminal_sessions WHERE project_path = :projectPath AND session_id = :sessionId LIMIT 1")
    suspend fun getSession(projectPath: String, sessionId: String): TerminalSessionEntity?

    /**
     * 插入或更新终端状态
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: TerminalStateEntity): Long

    /**
     * 插入或更新会话
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TerminalSessionEntity): Long

    /**
     * 批量插入或更新会话
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<TerminalSessionEntity>)

    @Transaction
    suspend fun replaceProjectTerminal(
        projectPath: String,
        state: TerminalStateEntity,
        sessions: List<TerminalSessionEntity>,
    ) {
        insertState(state)
        deleteSessions(projectPath)
        insertSessions(sessions)
    }

    /**
     * 删除项目的终端状态
     */
    @Query("DELETE FROM terminal_states WHERE project_path = :projectPath")
    suspend fun deleteState(projectPath: String): Int

    /**
     * 删除项目的所有会话
     */
    @Query("DELETE FROM terminal_sessions WHERE project_path = :projectPath")
    suspend fun deleteSessions(projectPath: String): Int

    /**
     * 删除单个会话
     */
    @Query("DELETE FROM terminal_sessions WHERE project_path = :projectPath AND session_id = :sessionId")
    suspend fun deleteSession(projectPath: String, sessionId: String): Int

    /**
     * 更新活动会话
     */
    @Query("UPDATE terminal_states SET active_session_id = :activeSessionId, updated_at = :updatedAt WHERE project_path = :projectPath")
    suspend fun updateActiveSession(projectPath: String, activeSessionId: String?, updatedAt: Long): Int

    @Query(
        """
        UPDATE terminal_states
        SET project_path = :newProjectPath
        WHERE project_path = :oldProjectPath
        """
    )
    suspend fun migrateStateProjectPath(oldProjectPath: String, newProjectPath: String): Int

    @Query(
        """
        UPDATE terminal_sessions
        SET project_path = :newProjectPath,
            working_directory = CASE
                WHEN working_directory = :oldProjectPath
                    OR substr(working_directory, 1, length(:oldProjectPath) + 1) = :oldProjectPath || '/'
                THEN :newProjectPath || substr(working_directory, length(:oldProjectPath) + 1)
                ELSE working_directory
            END
        WHERE project_path = :oldProjectPath
        """
    )
    suspend fun migrateSessionProjectPath(oldProjectPath: String, newProjectPath: String): Int

    @Transaction
    suspend fun migrateProjectPath(oldProjectPath: String, newProjectPath: String) {
        val hasState = getState(oldProjectPath) != null
        val hasSessions = getSessions(oldProjectPath).isNotEmpty()
        if (!hasState && !hasSessions) return

        clearProjectTerminal(newProjectPath)
        if (hasState) {
            migrateStateProjectPath(oldProjectPath, newProjectPath)
        }
        if (hasSessions) {
            migrateSessionProjectPath(oldProjectPath, newProjectPath)
        }
    }

    /**
     * 清空项目的所有终端数据（状态 + 会话）
     */
    @Transaction
    suspend fun clearProjectTerminal(projectPath: String) {
        deleteState(projectPath)
        deleteSessions(projectPath)
    }
}
