package com.wuxianggujun.tinaide.editor.session

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DocumentSessionTest {

    @Test
    fun reloadFromDisk_shouldRefreshUndoRedoAndCharset() = runTest {
        val gbk = Charset.forName("GBK")
        val file = Files.createTempFile("document-session-reload", ".txt").toFile()
        file.writeText("中文内容", gbk)
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "stale",
            canUndo = true,
            canRedo = true
        )

        try {
            session.attachEditor(binding)

            val reloaded = session.reloadFromDisk()

            assertThat(reloaded).isTrue()
            assertThat(binding.readText()).isEqualTo("中文内容")
            assertThat(session.state.value.canUndo).isFalse()
            assertThat(session.state.value.canRedo).isFalse()
            assertThat(session.state.value.charsetName).isEqualTo(gbk.name())
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldPreserveDetectedCharset() = runTest {
        val gbk = Charset.forName("GBK")
        val file = Files.createTempFile("document-session-save", ".txt").toFile()
        file.writeText("旧内容", gbk)
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "新的中文内容",
            canUndo = true,
            canRedo = false
        )

        try {
            session.attachEditor(binding)

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readBytes()).isEqualTo("新的中文内容".toByteArray(gbk))
            assertThat(session.state.value.charsetName).isEqualTo(gbk.name())
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun detachedSnapshot_shouldSaveDirtyTextWithoutActiveEditor() = runTest {
        val file = Files.createTempFile("document-session-detached", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "old",
            canUndo = false,
            canRedo = false,
            viewState = EditorViewState(cursorLine = 3, cursorColumn = 4, scrollX = 16, scrollY = 32)
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()
            binding.setText("changed while tab is alive")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)

            session.detachEditor(binding)
            val snapshot = session.detachedEditorSnapshot()

            assertThat(snapshot?.text).isEqualTo("changed while tab is alive")
            assertThat(snapshot?.viewState?.cursorLine).isEqualTo(3)
            assertThat(session.state.value.isDirty).isTrue()

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readText()).isEqualTo("changed while tab is alive")
            assertThat(session.state.value.isDirty).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun refreshDirtyStateForSave_shouldRefreshDirtyStateFromActiveBinding() = runTest {
        val file = Files.createTempFile("document-session-dirty-refresh", ".txt").toFile()
        file.writeText("template")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "template",
            canUndo = false,
            canRedo = false
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()

            binding.setText("pasted code")

            assertThat(session.state.value.isDirty).isFalse()
            assertThat(session.refreshDirtyStateForSave()).isTrue()
            assertThat(session.state.value.isDirty).isTrue()

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat((result as SaveResult.Success).target)
                .isEqualTo(SaveTarget(tabId = "tab-id", file = file))
            assertThat(file.readText()).isEqualTo("pasted code")
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldRetrySnapshotReadWhenDocumentChangesDuringCapture() = runTest {
        val file = Files.createTempFile("document-session-racing-save", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "old",
            canUndo = false,
            canRedo = false
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()
            binding.setText("first edit")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            binding.mutateAfterNextRead("second edit")

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readText()).isEqualTo("second edit")
            assertThat(session.state.value.isDirty).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldProtectExternalChangesUntilForceOverwrite() = runTest {
        val file = Files.createTempFile("document-session-conflict", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "old",
            canUndo = false,
            canRedo = false
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()
            binding.setText("mine")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            file.writeText("external")
            session.markExternalModification()

            val blocked = session.save(SaveReason.MANUAL)

            assertThat(blocked).isInstanceOf(SaveResult.Failure::class.java)
            assertThat(file.readText()).isEqualTo("external")

            val overwritten = session.forceOverwrite(SaveReason.MANUAL)

            assertThat(overwritten).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readText()).isEqualTo("mine")
            assertThat(session.state.value.hasExternalModification).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    private fun createSession(
        file: File,
        scope: CoroutineScope = TestScope(StandardTestDispatcher())
    ): DocumentSession = DocumentSession(
        context = RuntimeEnvironment.getApplication(),
        tabId = "tab-id",
        file = file,
        coroutineScope = scope
    )

    private class FakeEditorBinding(
        text: String,
        private var canUndo: Boolean,
        private var canRedo: Boolean,
        private val viewState: EditorViewState? = null
    ) : DocumentSession.EditorBinding {
        private var currentText = text
        private var version = 0L
        private var mutationAfterRead: String? = null

        override fun readText(): String {
            val snapshot = currentText
            mutationAfterRead?.let { replacement ->
                mutationAfterRead = null
                currentText = replacement
                version++
            }
            return snapshot
        }

        override fun setText(text: CharSequence) {
            currentText = text.toString()
            version++
            canUndo = false
            canRedo = false
        }

        override fun textLength(): Int = currentText.length

        override fun canUndo(): Boolean = canUndo

        override fun canRedo(): Boolean = canRedo

        override fun undo() = Unit

        override fun redo() = Unit

        override fun currentDocumentVersion(): Long = version

        override fun currentViewState(): EditorViewState? = viewState

        fun mutateAfterNextRead(text: String) {
            mutationAfterRead = text
        }
    }
}
