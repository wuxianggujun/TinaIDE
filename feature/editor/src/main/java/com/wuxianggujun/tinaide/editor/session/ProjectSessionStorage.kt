package com.wuxianggujun.tinaide.editor.session

import android.content.Context
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkDatabase
import com.wuxianggujun.tinaide.editor.session.db.EditorFileStateEntity
import com.wuxianggujun.tinaide.editor.session.db.EditorSessionEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 负责将编辑器会话信息保存到 Room 数据库
 * 既给 EditorManager 使用，也能被 ProjectManager 读取。
 */
data class ProjectSessionFileSnapshot(
    val path: String = "",
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0
)

data class ProjectSessionSnapshot(
    val activeFile: String? = null,
    val files: List<ProjectSessionFileSnapshot> = emptyList(),
    val updatedAt: Long = 0L
) {
    fun normalized(currentTime: Long = System.currentTimeMillis()): ProjectSessionSnapshot {
        val sanitizedFiles = files.filter { it.path.isNotBlank() }
        val sanitizedActive = activeFile?.takeIf { it.isNotBlank() }
        val timestamp = if (updatedAt <= 0L) currentTime else updatedAt
        return copy(activeFile = sanitizedActive, files = sanitizedFiles, updatedAt = timestamp)
    }
}

class ProjectSessionStorage(context: Context) {
    private val database = BookmarkDatabase.getInstance(context)
    private val sessionDao = database.editorSessionDao()

    companion object {
        private const val TAG = "ProjectSessionStorage"
    }

    suspend fun load(projectPath: String): ProjectSessionSnapshot? = withContext(Dispatchers.IO) {
        try {
            val session = sessionDao.getSession(projectPath)
            val fileStates = sessionDao.getFileStates(projectPath)

            if (session == null && fileStates.isEmpty()) {
                return@withContext null
            }

            ProjectSessionSnapshot(
                activeFile = session?.activeFile,
                files = fileStates.map { it.toDomainModel() },
                updatedAt = session?.updatedAt ?: System.currentTimeMillis()
            ).normalized()
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to load editor session: %s", e.javaClass.simpleName)
            throw e
        }
    }

    suspend fun save(projectPath: String, snapshot: ProjectSessionSnapshot) = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(projectPath)
            if (!projectDir.isDirectory) {
                Timber.tag(TAG).w("Skipped saving editor session for missing project")
                return@withContext
            }
            val normalized = snapshot.normalized()

            // 保存会话
            val sessionEntity = EditorSessionEntity.fromSnapshot(projectPath, normalized)
            val fileStateEntities = normalized.files.map { file ->
                EditorFileStateEntity.fromDomainModel(projectPath, file)
            }
            sessionDao.replaceProjectSession(projectPath, sessionEntity, fileStateEntities)

            if (!projectDir.isDirectory) {
                sessionDao.clearProjectSession(projectPath)
                Timber.tag(TAG).w("Discarded editor session written while project moved")
                return@withContext
            }

            Timber.tag(TAG).d("Saved editor session: files=%d", normalized.files.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to save editor session: %s", e.javaClass.simpleName)
            throw e
        }
    }

    suspend fun clear(projectPath: String) = withContext(Dispatchers.IO) {
        try {
            sessionDao.clearProjectSession(projectPath)
            Timber.tag(TAG).d("Cleared editor session")
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to clear editor session: %s", e.javaClass.simpleName)
            throw e
        }
    }
}
