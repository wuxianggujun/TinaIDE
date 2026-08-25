package com.wuxianggujun.tinaide.terminal.persistence

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.terminal.persistence.db.TerminalDatabase
import com.wuxianggujun.tinaide.terminal.persistence.db.TerminalSessionEntity
import com.wuxianggujun.tinaide.terminal.persistence.db.TerminalStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TerminalProjectPathMigrationTest {
    private lateinit var database: TerminalDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            TerminalDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun migrate_shouldReplaceStaleTargetStateAndRetargetWorkingDirectory() = runBlocking {
        val oldPath = "/storage/projects/old"
        val newPath = "/storage/projects/new"
        val dao = database.terminalStateDao()
        dao.insertState(TerminalStateEntity(projectPath = oldPath, activeSessionId = "source", updatedAt = 20L))
        dao.insertSession(
            TerminalSessionEntity(
                projectPath = oldPath,
                sessionId = "source",
                workingDirectory = "$oldPath/build",
            ),
        )
        dao.insertState(TerminalStateEntity(projectPath = newPath, activeSessionId = "stale", updatedAt = 10L))
        dao.insertSession(
            TerminalSessionEntity(
                projectPath = newPath,
                sessionId = "stale",
                workingDirectory = "$newPath/stale",
            ),
        )

        dao.migrateProjectPath(oldPath, newPath)

        assertThat(dao.getState(oldPath)).isNull()
        assertThat(dao.getSessions(oldPath)).isEmpty()
        assertThat(dao.getState(newPath)?.activeSessionId).isEqualTo("source")
        assertThat(dao.getSessions(newPath).single().sessionId).isEqualTo("source")
        assertThat(dao.getSessions(newPath).single().workingDirectory).isEqualTo("$newPath/build")
    }

    @Test
    fun migrate_withoutSourceState_shouldPreserveTargetState() = runBlocking {
        val oldPath = "/storage/projects/old"
        val newPath = "/storage/projects/new"
        val dao = database.terminalStateDao()
        dao.insertState(TerminalStateEntity(projectPath = newPath, activeSessionId = "keep", updatedAt = 30L))

        dao.migrateProjectPath(oldPath, newPath)

        assertThat(dao.getState(newPath)?.activeSessionId).isEqualTo("keep")
    }
}
