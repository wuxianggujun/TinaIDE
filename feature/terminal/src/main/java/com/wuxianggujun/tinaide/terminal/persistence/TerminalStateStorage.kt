package com.wuxianggujun.tinaide.terminal.persistence

import android.content.Context
import com.wuxianggujun.tinaide.terminal.persistence.db.TerminalDatabase
import com.wuxianggujun.tinaide.terminal.persistence.db.TerminalSessionEntity
import com.wuxianggujun.tinaide.terminal.persistence.db.TerminalStateEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 终端状态存储工具
 *
 * 使用 Room 数据库持久化终端会话状态。
 */
class TerminalStateStorage(context: Context) {
    private val database = TerminalDatabase.getInstance(context)
    private val stateDao = database.terminalStateDao()

    companion object {
        private const val TAG = "TerminalStateStorage"
    }

    /**
     * 加载终端状态
     *
     * @param projectPath 项目根目录路径
     * @return 终端状态，如果不存在或解析失败则返回 null
     */
    suspend fun load(projectPath: String): ProjectTerminalState? = withContext(Dispatchers.IO) {
        try {
            val state = stateDao.getState(projectPath)
            val sessions = stateDao.getSessions(projectPath)

            if (state == null && sessions.isEmpty()) {
                return@withContext null
            }

            return@withContext ProjectTerminalState(
                activeSessionId = state?.activeSessionId,
                sessions = sessions.map { it.toDomainModel() },
                updatedAt = state?.updatedAt ?: System.currentTimeMillis()
            ).normalized()
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to load terminal state: %s", e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 保存终端状态
     *
     * @param projectPath 项目根目录路径
     * @param state 要保存的终端状态
     */
    suspend fun save(projectPath: String, state: ProjectTerminalState) = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(projectPath)
            if (!projectDir.isDirectory) {
                Timber.tag(TAG).w("Skipped saving terminal state for missing project")
                return@withContext
            }
            val normalized = state.normalized()
            val stateEntity = TerminalStateEntity.fromSnapshot(projectPath, normalized)
            val sessionEntities = normalized.sessions.map { session ->
                TerminalSessionEntity.fromDomainModel(projectPath, session)
            }
            stateDao.replaceProjectTerminal(projectPath, stateEntity, sessionEntities)

            if (!projectDir.isDirectory) {
                stateDao.clearProjectTerminal(projectPath)
                Timber.tag(TAG).w("Discarded terminal state written while project moved")
                return@withContext
            }

            Timber.tag(TAG).d("Saved terminal state: sessions=%d", normalized.sessions.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to save terminal state: %s", e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 清除终端状态
     *
     * @param projectPath 项目根目录路径
     */
    suspend fun clear(projectPath: String) = withContext(Dispatchers.IO) {
        try {
            stateDao.clearProjectTerminal(projectPath)
            Timber.tag(TAG).d("Cleared terminal state")
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to clear terminal state: %s", e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 检查终端状态是否存在
     *
     * @param projectPath 项目根目录路径
     * @return 如果状态文件存在则返回 true
     */
    suspend fun exists(projectPath: String): Boolean = withContext(Dispatchers.IO) {
        val state = stateDao.getState(projectPath)
        val sessions = stateDao.getSessions(projectPath)
        state != null || sessions.isNotEmpty()
    }

    suspend fun migrateProjectPath(oldProjectPath: String, newProjectPath: String) = withContext(Dispatchers.IO) {
        require(oldProjectPath.isNotBlank() && newProjectPath.isNotBlank())
        if (oldProjectPath != newProjectPath) {
            stateDao.migrateProjectPath(oldProjectPath, newProjectPath)
        }
    }
}
