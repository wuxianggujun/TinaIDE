package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import org.junit.Test

class EditorStateInlayHintsTest {
    @Test
    fun replaceInlayHintsInLines_shouldMergeViewportAtCurrentVersion() {
        val buffer = RopeTextBuffer("call(1)\nvalue")
        val state = EditorState(buffer)
        val parameterHint = EditorInlayHint(
            line = 0,
            column = 5,
            label = "value:",
            kind = EditorInlayHintKind.PARAMETER,
            paddingRight = true,
        )
        val typeHint = EditorInlayHint(
            line = 1,
            column = 5,
            label = ": int",
            kind = EditorInlayHintKind.TYPE,
        )

        assertThat(state.replaceInlayHintsInLines(0..0, listOf(parameterHint), buffer.version)).isTrue()
        assertThat(state.replaceInlayHintsInLines(1..1, listOf(typeHint), buffer.version)).isTrue()

        assertThat(state.inlayHints).containsExactly(parameterHint, typeHint).inOrder()
        assertThat(state.inlayHintsByLine.keys).containsExactly(0, 1)
        assertThat(state.inlayHintsDocumentVersion).isEqualTo(buffer.version)
    }

    @Test
    fun replaceInlayHintsInLines_shouldRejectStaleVersion() {
        val buffer = RopeTextBuffer("value")
        val state = EditorState(buffer)
        buffer.insert(0, "x")

        val applied = state.replaceInlayHintsInLines(
            lines = 0..0,
            hints = listOf(EditorInlayHint(0, 1, ": int")),
            documentVersion = buffer.version - 1,
        )

        assertThat(applied).isFalse()
        assertThat(state.inlayHints).isEmpty()
    }

    @Test
    fun applyTextBufferChange_shouldClearPublishedHintsImmediately() {
        val buffer = RopeTextBuffer("call(1)")
        val state = EditorState(buffer)
        state.replaceInlayHintsInLines(
            lines = 0..0,
            hints = listOf(EditorInlayHint(0, 5, "value:", EditorInlayHintKind.PARAMETER)),
            documentVersion = buffer.version,
        )
        val change = TextChange(
            startOffset = 5,
            endOffset = 5,
            oldText = "",
            newText = "2",
            startLine = 0,
            startColumn = 5,
            endLine = 0,
            endColumn = 5,
        )
        buffer.insert(5, "2")

        state.applyTextBufferChange(change)

        assertThat(state.inlayHints).isEmpty()
        assertThat(state.inlayHintsByLine).isEmpty()
        assertThat(state.inlayHintsDocumentVersion).isEqualTo(-1L)
    }
}
