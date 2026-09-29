package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditHistoryTest {

    @Test
    fun compoundEdit_shouldNotAdvertiseUndoUntilClosed() {
        val history = DefaultEditHistory()
        history.record(EditOperation.Insert(0, "before"))
        val token = history.beginCompoundEdit(cursorBefore = 6)
        history.record(EditOperation.Insert(6, "x"))

        assertThat(history.canUndo()).isFalse()
        assertThat(runCatching { history.undo() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)

        history.endCompoundEdit(token, cursorAfter = 7)

        assertThat(history.canUndo()).isTrue()
    }

    @Test
    fun clear_shouldResetCompoundStateAndAllowPendingScopeToClose() {
        val history = DefaultEditHistory()
        val staleToken = history.beginCompoundEdit(cursorBefore = 0)
        history.record(EditOperation.Insert(0, "discarded"))

        history.clear()
        val freshToken = history.beginCompoundEdit(cursorBefore = 0)
        history.record(EditOperation.Insert(0, "kept"))

        history.endCompoundEdit(staleToken, cursorAfter = 9)
        assertThat(history.canUndo()).isFalse()
        history.endCompoundEdit(freshToken, cursorAfter = 4)

        assertThat(history.canUndo()).isTrue()
        val operation = history.undo() as EditOperation.Compound
        assertThat(operation.operations).containsExactly(EditOperation.Insert(0, "kept"))
        assertThat(operation.cursorBefore).isEqualTo(0)
        assertThat(operation.cursorAfter).isEqualTo(4)
        assertThat(history.canUndo()).isFalse()
    }

    @Test
    fun compoundEdit_shouldCollapseSequentialCompositionReplacements() {
        val history = DefaultEditHistory()
        val token = history.beginCompoundEdit(cursorBefore = 0)
        history.record(EditOperation.Insert(0, "n"))
        history.record(EditOperation.Replace(0, oldText = "n", newText = "ni"))
        history.record(EditOperation.Replace(0, oldText = "ni", newText = "\u4F60"))
        history.endCompoundEdit(token, cursorAfter = 1)

        val operation = history.undo() as EditOperation.Compound

        assertThat(operation.operations).containsExactly(EditOperation.Insert(0, "\u4F60"))
        assertThat(operation.cursorBefore).isEqualTo(0)
        assertThat(operation.cursorAfter).isEqualTo(1)
    }

    @Test
    fun compoundEdit_collapsedReplacementShouldPreserveOperationCursorSnapshots() {
        val history = DefaultEditHistory()
        val token = history.beginCompoundEdit()
        history.record(
            EditOperation.Insert(
                offset = 2,
                text = "n",
                cursorSnapshot = TextEditCursorSnapshot(cursorBefore = 7, cursorAfter = 3)
            )
        )
        history.record(
            EditOperation.Replace(
                offset = 2,
                oldText = "n",
                newText = "\u4F60",
                cursorSnapshot = TextEditCursorSnapshot(cursorBefore = 3, cursorAfter = 9)
            )
        )
        history.endCompoundEdit(token)

        val operation = history.undo() as EditOperation.Insert

        assertThat(operation.text).isEqualTo("\u4F60")
        assertThat(operation.cursorSnapshot)
            .isEqualTo(TextEditCursorSnapshot(cursorBefore = 7, cursorAfter = 9))
    }

    @Test
    fun compoundEdit_shouldPreserveSelectionSnapshots() {
        val history = DefaultEditHistory()
        val before = TextSelectionSnapshot(anchor = 4, caret = 1)

        val token = history.beginCompoundEdit(cursorBefore = 1, selectionBefore = before)
        history.record(EditOperation.Replace(1, oldText = "bcd", newText = "X"))
        history.endCompoundEdit(token, cursorAfter = 2, selectionAfter = null)

        val operation = history.undo() as EditOperation.Compound
        assertThat(operation.selectionBefore).isEqualTo(before)
        assertThat(operation.selectionAfter).isNull()
    }

    @Test
    fun oversizedEdit_shouldCutOffOlderUndoHistory() {
        val history = DefaultEditHistory(maxHistoryCharacters = 4)
        history.record(EditOperation.Insert(0, "old"))

        history.record(EditOperation.Insert(3, "12345"))

        assertThat(history.canUndo()).isFalse()
        assertThat(history.canRedo()).isFalse()
    }

    @Test
    fun characterBudget_shouldKeepOnlyNewestContiguousOperations() {
        val history = DefaultEditHistory(maxHistoryCharacters = 4)
        val first = EditOperation.Replace(0, oldText = "a", newText = "A")
        val second = EditOperation.Replace(1, oldText = "b", newText = "B")
        val third = EditOperation.Replace(2, oldText = "c", newText = "C")

        history.record(first)
        history.record(second)
        history.record(third)

        assertThat(history.undo()).isEqualTo(third)
        assertThat(history.undo()).isEqualTo(second)
        assertThat(history.canUndo()).isFalse()
    }

    @Test
    fun cursorAwareInsertMerge_shouldKeepFirstBeforeAndLastAfter() {
        val history = DefaultEditHistory(nowMs = { 0L })
        history.record(
            EditOperation.Insert(
                offset = 0,
                text = "a",
                cursorSnapshot = TextEditCursorSnapshot(cursorBefore = 0, cursorAfter = 1)
            )
        )
        history.record(
            EditOperation.Insert(
                offset = 1,
                text = "b",
                cursorSnapshot = TextEditCursorSnapshot(cursorBefore = 1, cursorAfter = 2)
            )
        )

        val merged = history.undo() as EditOperation.Insert

        assertThat(merged.text).isEqualTo("ab")
        assertThat(merged.cursorSnapshot)
            .isEqualTo(TextEditCursorSnapshot(cursorBefore = 0, cursorAfter = 2))
    }

    @Test
    fun cursorAwareBackspaceMerge_shouldKeepOriginalAndFinalCursor() {
        val history = DefaultEditHistory(nowMs = { 0L })
        history.record(
            EditOperation.Delete(
                offset = 2,
                text = "c",
                cursorSnapshot = TextEditCursorSnapshot(cursorBefore = 3, cursorAfter = 2)
            )
        )
        history.record(
            EditOperation.Delete(
                offset = 1,
                text = "b",
                cursorSnapshot = TextEditCursorSnapshot(cursorBefore = 2, cursorAfter = 1)
            )
        )

        val merged = history.undo() as EditOperation.Delete

        assertThat(merged.offset).isEqualTo(1)
        assertThat(merged.text).isEqualTo("bc")
        assertThat(merged.cursorSnapshot)
            .isEqualTo(TextEditCursorSnapshot(cursorBefore = 3, cursorAfter = 1))
    }

    @Test
    fun cursorAwareForwardDeleteMerge_shouldKeepCursorAtDeleteStart() {
        val history = DefaultEditHistory(nowMs = { 0L })
        repeat(2) { index ->
            history.record(
                EditOperation.Delete(
                    offset = 1,
                    text = ('b'.code + index).toChar().toString(),
                    cursorSnapshot = TextEditCursorSnapshot(cursorBefore = 1, cursorAfter = 1)
                )
            )
        }

        val merged = history.undo() as EditOperation.Delete

        assertThat(merged.text).isEqualTo("bc")
        assertThat(merged.cursorSnapshot)
            .isEqualTo(TextEditCursorSnapshot(cursorBefore = 1, cursorAfter = 1))
    }

    @Test
    fun clockRollback_shouldBreakSequentialEditMerge() {
        var now = 1_000L
        val history = DefaultEditHistory(
            mergeWindowMs = 300L,
            nowMs = { now }
        )
        val first = EditOperation.Insert(offset = 0, text = "a")
        val second = EditOperation.Insert(offset = 1, text = "b")
        history.record(first)

        now = 900L
        history.record(second)

        assertThat(history.undo()).isEqualTo(second)
        assertThat(history.undo()).isEqualTo(first)
    }

    @Test
    fun negativeMergeWindow_shouldFailFast() {
        val failure = runCatching {
            DefaultEditHistory(mergeWindowMs = -1L)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("mergeWindowMs")
    }
}
