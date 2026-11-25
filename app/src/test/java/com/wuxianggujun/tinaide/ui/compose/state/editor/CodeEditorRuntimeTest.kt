package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import org.junit.Assert.assertThrows
import org.junit.Test

class CodeEditorRuntimeTest {

    @Test
    fun dispose_shouldCloseEditorStateOnlyAfterLastPaneIsRemoved() {
        val buffer = RopeTextBuffer("unsaved text")
        val state = EditorState(buffer)
        val runtime = CodeEditorRuntime(buffer, state)
        state.onRequestHover = { null }
        runtime.acquireUiReference()
        runtime.acquireUiReference()

        runtime.dispose()
        runtime.releaseUiReference()
        assertThat(state.onRequestHover).isNotNull()
        assertThat(buffer.isClosed).isFalse()

        runtime.releaseUiReference()

        assertThat(state.onRequestHover).isNull()
        assertThat(buffer.isClosed).isTrue()
        assertThrows(IllegalStateException::class.java) {
            state.highlighter = object : SyntaxHighlighter {
                override fun openDocumentBlocking(text: String) = Unit
            }
        }
    }

    @Test
    fun dispose_shouldKeepBufferAndBindingsUntilLastPaneIsRemoved() {
        val buffer = RopeTextBuffer("unsaved text")
        val runtime = CodeEditorRuntime(buffer, EditorState(buffer))
        val binding = FakeDocumentBinding(buffer)
        runtime.getOrCreateDocumentBinding { binding }
        runtime.acquireDocumentBinding(binding)
        runtime.acquireUiReference()
        runtime.acquireUiReference()

        runtime.dispose()
        runtime.dispose()
        assertThat(buffer.isClosed).isFalse()
        assertThat(binding.readText()).isEqualTo("unsaved text")
        assertThat(binding.detachCount).isEqualTo(0)

        runtime.releaseUiReference()
        assertThat(buffer.isClosed).isFalse()
        runtime.releaseUiReference()
        assertThat(buffer.isClosed).isTrue()
        assertThat(binding.detachCount).isEqualTo(1)

        runtime.dispose()
        runtime.releaseDocumentBinding(binding)
        assertThat(binding.detachCount).isEqualTo(1)
        assertThrows(IllegalStateException::class.java) { runtime.acquireUiReference() }
    }

    @Test
    fun leavingPage_shouldPreserveCachedRuntimeUntilEviction() {
        val buffer = RopeTextBuffer("cached text")
        val runtime = CodeEditorRuntime(buffer, EditorState(buffer))
        runtime.acquireUiReference()
        runtime.releaseUiReference()
        assertThat(buffer.isClosed).isFalse()
        runtime.acquireUiReference()
        assertThat(buffer.toString()).isEqualTo("cached text")
        runtime.releaseUiReference()
        runtime.dispose()
        assertThat(buffer.isClosed).isTrue()
    }

    @Test
    fun documentBinding_shouldAttachOnceAcrossMirroredPanes() {
        val buffer = RopeTextBuffer("text")
        val runtime = CodeEditorRuntime(
            buffer = buffer,
            editorState = EditorState(buffer)
        )
        val binding = FakeDocumentBinding(buffer)

        assertThat(runtime.getOrCreateDocumentBinding { binding }).isSameInstanceAs(binding)
        assertThat(runtime.getOrCreateDocumentBinding { error("must reuse binding") }).isSameInstanceAs(binding)

        runtime.acquireDocumentBinding(binding)
        runtime.acquireDocumentBinding(binding)
        assertThat(binding.attachCount).isEqualTo(1)

        runtime.releaseDocumentBinding(binding)
        assertThat(binding.detachCount).isEqualTo(0)

        runtime.releaseDocumentBinding(binding)
        assertThat(binding.detachCount).isEqualTo(1)
        runtime.dispose()
    }

    @Test
    fun dispose_shouldDetachActiveDocumentBinding() {
        val buffer = RopeTextBuffer("text")
        val runtime = CodeEditorRuntime(
            buffer = buffer,
            editorState = EditorState(buffer)
        )
        val binding = FakeDocumentBinding(buffer)
        runtime.getOrCreateDocumentBinding { binding }
        runtime.acquireDocumentBinding(binding)

        runtime.dispose()

        assertThat(binding.attachCount).isEqualTo(1)
        assertThat(binding.detachCount).isEqualTo(1)
    }

    @Test
    fun stateBinding_shouldRemainAttachedUntilLastMirroredPaneReleasesIt() {
        val buffer = RopeTextBuffer("text")
        val runtime = CodeEditorRuntime(
            buffer = buffer,
            editorState = EditorState(buffer)
        )
        val binding = FakeStateBinding()

        assertThat(runtime.getOrCreateStateBinding("actions") { binding }).isSameInstanceAs(binding)
        runtime.acquireStateBinding("actions", binding)
        runtime.acquireStateBinding("actions", binding)
        assertThat(binding.attachCount).isEqualTo(1)

        runtime.releaseStateBinding("actions", binding)
        assertThat(binding.detachCount).isEqualTo(0)
        runtime.releaseStateBinding("actions", binding)
        assertThat(binding.detachCount).isEqualTo(1)
        runtime.dispose()
    }

    private class FakeDocumentBinding(
        private val buffer: RopeTextBuffer
    ) : CodeEditorDocumentBinding {
        var attachCount = 0
        var detachCount = 0

        override fun attach() {
            attachCount++
        }

        override fun detach() {
            detachCount++
        }

        override suspend fun <R> withSuppressed(block: suspend () -> R): R = block()

        override fun readText(): String = buffer.toString()

        override fun readSnapshot(): DocumentSession.EditorContentSnapshot =
            DocumentSession.EditorContentSnapshot(buffer.toString(), buffer.version)

        override fun setText(text: CharSequence) {
            buffer.replaceAll(text.toString())
        }

        override fun textLength(): Int = buffer.length

        override fun canUndo(): Boolean = buffer.canUndo()

        override fun canRedo(): Boolean = buffer.canRedo()

        override fun undo() {
            buffer.undo()
        }

        override fun redo() {
            buffer.redo()
        }

        override fun currentDocumentVersion(): Long = buffer.version

        override fun currentViewState(): EditorViewState? = null
    }

    private class FakeStateBinding : CodeEditorStateBinding {
        var attachCount = 0
        var detachCount = 0

        override fun attach() {
            attachCount++
        }

        override fun detach() {
            detachCount++
        }
    }
}
