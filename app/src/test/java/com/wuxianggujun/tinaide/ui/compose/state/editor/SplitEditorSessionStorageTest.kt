package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SplitEditorSessionStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storage: SplitEditorSessionStorage

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tinaide_editor_split_state", 0).edit().clear().commit()
        storage = SplitEditorSessionStorage(context)
    }

    @Test
    fun migrateProjectPath_shouldReplaceStaleTargetAndRetargetAllStoredPaths() {
        val oldPath = temporaryFolder.newFolder("old").absolutePath
        val newPath = temporaryFolder.newFolder("new").absolutePath
        storage.save(
            newPath,
            SplitEditorStateSnapshot(
                isEnabled = true,
                activeFilePathByPane = mapOf(EditorPaneId.PRIMARY to "$newPath/stale.cpp"),
            ),
        )
        storage.save(
            oldPath,
            SplitEditorStateSnapshot(
                isEnabled = true,
                tabPaneAssignments = mapOf("$oldPath/src/main.cpp" to EditorPaneId.SECONDARY),
                mirroredFilePathsByPane = mapOf(
                    EditorPaneId.SECONDARY to setOf("$oldPath/src/main.cpp"),
                ),
                activeFilePathByPane = mapOf(
                    EditorPaneId.SECONDARY to "$oldPath/src/main.cpp",
                ),
            ),
        )

        storage.migrateProjectPath(oldPath, newPath)

        assertThat(storage.load(oldPath)).isNull()
        val migrated = storage.load(newPath)!!
        assertThat(migrated.tabPaneAssignments)
            .containsExactly("$newPath/src/main.cpp", EditorPaneId.SECONDARY)
        assertThat(migrated.mirroredFilePathsByPane[EditorPaneId.SECONDARY])
            .containsExactly("$newPath/src/main.cpp")
        assertThat(migrated.activeFilePathByPane[EditorPaneId.SECONDARY])
            .isEqualTo("$newPath/src/main.cpp")
    }

    @Test
    fun save_shouldIgnoreStateForMissingProjectDirectory() {
        val missingPath = temporaryFolder.root.resolve("missing").absolutePath

        storage.save(
            missingPath,
            SplitEditorStateSnapshot(isEnabled = true),
        )

        assertThat(storage.load(missingPath)).isNull()
    }
}
