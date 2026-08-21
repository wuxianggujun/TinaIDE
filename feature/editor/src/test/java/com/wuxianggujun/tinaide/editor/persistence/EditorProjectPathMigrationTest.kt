package com.wuxianggujun.tinaide.editor.persistence

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkDatabase
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkEntity
import com.wuxianggujun.tinaide.editor.session.db.EditorFileStateEntity
import com.wuxianggujun.tinaide.editor.session.db.EditorSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EditorProjectPathMigrationTest {
    private lateinit var database: BookmarkDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BookmarkDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun migrate_shouldReplaceStaleTargetStateAndRetargetAbsolutePaths() = runBlocking {
        val oldPath = "/storage/projects/old"
        val newPath = "/storage/projects/new"
        val bookmarkDao = database.bookmarkDao()
        val sessionDao = database.editorSessionDao()

        bookmarkDao.insertBookmark(
            BookmarkEntity(projectPath = oldPath, filePath = "$oldPath/src/main.cpp", line = 4),
        )
        bookmarkDao.insertBookmark(
            BookmarkEntity(projectPath = newPath, filePath = "$newPath/stale.cpp", line = 9),
        )
        sessionDao.insertSession(
            EditorSessionEntity(projectPath = oldPath, activeFile = "$oldPath/src/main.cpp", updatedAt = 20L),
        )
        sessionDao.insertFileState(
            EditorFileStateEntity(projectPath = oldPath, filePath = "$oldPath/src/main.cpp", cursorLine = 12),
        )
        sessionDao.insertSession(
            EditorSessionEntity(projectPath = newPath, activeFile = "$newPath/stale.cpp", updatedAt = 10L),
        )
        sessionDao.insertFileState(
            EditorFileStateEntity(projectPath = newPath, filePath = "$newPath/stale.cpp", cursorLine = 1),
        )

        EditorProjectPathMigration.migrate(database, oldPath, newPath)

        assertThat(bookmarkDao.getBookmarks(oldPath)).isEmpty()
        assertThat(bookmarkDao.getBookmarks(newPath).map { it.filePath })
            .containsExactly("$newPath/src/main.cpp")
        assertThat(sessionDao.getSession(oldPath)).isNull()
        assertThat(sessionDao.getFileStates(oldPath)).isEmpty()
        assertThat(sessionDao.getSession(newPath)?.activeFile).isEqualTo("$newPath/src/main.cpp")
        assertThat(sessionDao.getFileStates(newPath).single().filePath).isEqualTo("$newPath/src/main.cpp")
        assertThat(sessionDao.getFileStates(newPath).single().cursorLine).isEqualTo(12)
    }

    @Test
    fun migrate_withoutSourceState_shouldPreserveTargetState() = runBlocking {
        val oldPath = "/storage/projects/old"
        val newPath = "/storage/projects/new"
        val bookmarkDao = database.bookmarkDao()
        val sessionDao = database.editorSessionDao()
        bookmarkDao.insertBookmark(
            BookmarkEntity(projectPath = newPath, filePath = "$newPath/keep.cpp", line = 3),
        )
        sessionDao.insertSession(
            EditorSessionEntity(projectPath = newPath, activeFile = "$newPath/keep.cpp", updatedAt = 30L),
        )

        EditorProjectPathMigration.migrate(database, oldPath, newPath)

        assertThat(bookmarkDao.getBookmarks(newPath).single().filePath).isEqualTo("$newPath/keep.cpp")
        assertThat(sessionDao.getSession(newPath)?.activeFile).isEqualTo("$newPath/keep.cpp")
    }
}
