package com.wuxianggujun.tinaide.editor.bookmark.persistence

import android.content.Context
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkDatabase
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkEntity
import com.wuxianggujun.tinaide.editor.bookmark.model.ProjectBookmarkState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 书签状态存储工具
 *
 * 使用 Room 数据库持久化书签数据。
 *
 * 支持数据库版本升级，确保已保存的书签数据能正确加载。
 */
class BookmarkStateStorage(context: Context) {
    private val database = BookmarkDatabase.getInstance(context)
    private val bookmarkDao = database.bookmarkDao()

    companion object {
        private const val TAG = "BookmarkStateStorage"
    }

    /**
     * 转换为持久化路径（相对路径）
     *
     * Android 系统统一使用 / 作为路径分隔符，无需特殊处理。
     */
    private fun toPersistedPath(projectPath: String, filePath: String): String {
        if (filePath.isBlank()) return filePath
        val file = File(filePath)
        if (!file.isAbsolute) {
            return filePath
        }

        return runCatching {
            val root = File(projectPath).canonicalFile
            val target = file.canonicalFile
            if (target.path == root.path) return@runCatching target.name
            if (!target.path.startsWith(root.path + File.separator)) return@runCatching filePath
            target.relativeTo(root).path
        }.getOrElse {
            filePath
        }
    }

    /**
     * 转换为绝对路径
     */
    private fun toAbsolutePath(projectPath: String, storedPath: String): String {
        if (storedPath.isBlank()) return storedPath
        val file = File(storedPath)
        if (file.isAbsolute) return storedPath
        return File(projectPath, storedPath).path
    }

    /**
     * 加载书签状态
     *
     * 自动处理数据库升级后的实体读取和相对路径还原。
     */
    suspend fun load(projectPath: String): ProjectBookmarkState? = withContext(Dispatchers.IO) {
        try {
            val entities = bookmarkDao.getBookmarks(projectPath)
            val bookmarks = entities.map { entity ->
                entity.toDomainModel().copy(
                    filePath = toAbsolutePath(projectPath, entity.filePath)
                )
            }
            ProjectBookmarkState(bookmarks = bookmarks).normalized()
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to load bookmark state: %s", e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 保存书签状态
     */
    suspend fun save(projectPath: String, state: ProjectBookmarkState) = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(projectPath)
            if (!projectDir.isDirectory) {
                Timber.tag(TAG).w("Skipped saving bookmark state for missing project")
                return@withContext
            }
            val normalized = state.copy(
                bookmarks = state.bookmarks.map { bm ->
                    bm.copy(filePath = toPersistedPath(projectPath, bm.filePath))
                }
            ).normalized()

            // 先删除旧数据，再插入新数据
            val entities = normalized.bookmarks.map { bookmark ->
                BookmarkEntity.fromDomainModel(projectPath, bookmark)
            }
            bookmarkDao.replaceProjectBookmarks(projectPath, entities)

            if (!projectDir.isDirectory) {
                bookmarkDao.deleteAllBookmarks(projectPath)
                Timber.tag(TAG).w("Discarded bookmark state written while project moved")
                return@withContext
            }

            Timber.tag(TAG).d("Saved bookmark state: count=%d", normalized.bookmarks.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to save bookmark state: %s", e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 清空书签状态
     */
    suspend fun clear(projectPath: String) = withContext(Dispatchers.IO) {
        try {
            val count = bookmarkDao.deleteAllBookmarks(projectPath)
            Timber.tag(TAG).d("Cleared bookmark state: deleted=%d", count)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to clear bookmark state: %s", e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 检查书签是否存在
     */
    suspend fun exists(projectPath: String): Boolean = withContext(Dispatchers.IO) {
        bookmarkDao.getBookmarksCount(projectPath) > 0
    }
}
