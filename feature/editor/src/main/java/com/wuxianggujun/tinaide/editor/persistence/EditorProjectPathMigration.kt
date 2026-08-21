package com.wuxianggujun.tinaide.editor.persistence

import android.content.Context
import androidx.room.withTransaction
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EditorProjectPathMigration {
    suspend fun migrate(context: Context, oldProjectPath: String, newProjectPath: String) {
        require(oldProjectPath.isNotBlank() && newProjectPath.isNotBlank())
        if (oldProjectPath == newProjectPath) return
        withContext(Dispatchers.IO) {
            migrate(
                database = BookmarkDatabase.getInstance(context.applicationContext),
                oldProjectPath = oldProjectPath,
                newProjectPath = newProjectPath,
            )
        }
    }

    internal suspend fun migrate(
        database: BookmarkDatabase,
        oldProjectPath: String,
        newProjectPath: String,
    ) {
        database.withTransaction {
            val bookmarkDao = database.bookmarkDao()
            val sessionDao = database.editorSessionDao()

            if (bookmarkDao.getBookmarksCount(oldProjectPath) > 0) {
                bookmarkDao.deleteAllBookmarks(newProjectPath)
                bookmarkDao.migrateProjectPath(oldProjectPath, newProjectPath)
            }

            val hasSession = sessionDao.getSession(oldProjectPath) != null
            val hasFileStates = sessionDao.getFileStates(oldProjectPath).isNotEmpty()
            if (hasSession || hasFileStates) {
                sessionDao.clearProjectSession(newProjectPath)
                if (hasSession) {
                    sessionDao.migrateSessionProjectPath(oldProjectPath, newProjectPath)
                }
                if (hasFileStates) {
                    sessionDao.migrateFileStateProjectPath(oldProjectPath, newProjectPath)
                }
            }
        }
    }

    suspend fun clear(context: Context, projectPath: String) {
        require(projectPath.isNotBlank())
        withContext(Dispatchers.IO) {
            val database = BookmarkDatabase.getInstance(context.applicationContext)
            database.withTransaction {
                database.bookmarkDao().deleteAllBookmarks(projectPath)
                database.editorSessionDao().clearProjectSession(projectPath)
            }
        }
    }
}
