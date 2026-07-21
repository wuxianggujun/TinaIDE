package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorRuntime
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorDocumentBinding
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorStateBinding

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import org.junit.Test

class CodeEditorRuntimeTest {

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
