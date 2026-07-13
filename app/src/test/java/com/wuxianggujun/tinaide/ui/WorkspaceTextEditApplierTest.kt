package com.wuxianggujun.tinaide.ui

import com.google.common.truth.Truth.assertThat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.Test

class WorkspaceTextEditApplierTest {
    @Test
    fun apply_shouldUseOriginalCoordinatesForMultipleEdits() {
        val edits = listOf(
            edit(0, 0, 0, 3, "one"),
            edit(1, 0, 1, 3, "two")
        )

        assertThat(WorkspaceTextEditApplier.apply("abc\r\ndef", edits)).isEqualTo("one\r\ntwo")
    }

    @Test
    fun apply_shouldRejectOutOfRangePosition() {
        val edit = edit(4, 0, 4, 0, "x")

        assertThat(WorkspaceTextEditApplier.apply("abc", listOf(edit))).isNull()
    }

    @Test
    fun apply_shouldRejectOverlappingEdits() {
        val edits = listOf(
            edit(0, 0, 0, 2, "x"),
            edit(0, 1, 0, 3, "y")
        )

        assertThat(WorkspaceTextEditApplier.apply("abc", edits)).isNull()
    }

    private fun edit(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        newText: String
    ): TextEdit = TextEdit(
        Range(Position(startLine, startColumn), Position(endLine, endColumn)),
        newText
    )
}
