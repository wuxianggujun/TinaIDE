package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RopeTextBufferTest {

    @Test
    fun lineApiShouldWork() {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "hello\nworld")

        assertThat(buffer.lineCount).isEqualTo(2)
        assertThat(buffer.getLine(0)).isEqualTo("hello")
        assertThat(buffer.getLine(1)).isEqualTo("world")
        assertThat(buffer.offsetToLine(7)).isEqualTo(1)
    }

    @Test
    fun undoRedoShouldWork() {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "abc")
        buffer.insert(3, "def")

        assertThat(buffer.toString()).isEqualTo("abcdef")
        // 连续相邻 Insert 会被 EditHistory 合并为一条 —— 一次 undo 直接回到空串，对齐 IntelliJ/Sora 的直觉。
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("")
        assertThat(buffer.redo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("abcdef")
    }

    @Test
    fun undo_shouldReturnAppliedTextChange() {
        val buffer = RopeTextBuffer("abc")
        buffer.insert(1, "XYZ")

        val result = buffer.undo()
        val change = result!!.changes.single()

        assertThat(change.startOffset).isEqualTo(1)
        assertThat(change.oldText).isEqualTo("XYZ")
        assertThat(change.newText).isEqualTo("")
        assertThat(change.fromUndoRedo).isTrue()
        assertThat(result.cursorOffset).isEqualTo(1)
        assertThat(buffer.toString()).isEqualTo("abc")
    }

    @Test
    fun redo_shouldReturnAppliedTextChange() {
        val buffer = RopeTextBuffer("abc")
        buffer.insert(1, "XYZ")
        buffer.undo()

        val result = buffer.redo()
        val change = result!!.changes.single()

        assertThat(change.startOffset).isEqualTo(1)
        assertThat(change.oldText).isEqualTo("")
        assertThat(change.newText).isEqualTo("XYZ")
        assertThat(change.fromUndoRedo).isTrue()
        assertThat(result.cursorOffset).isEqualTo(4)
        assertThat(buffer.toString()).isEqualTo("aXYZbc")
    }

    @Test
    fun editTransaction_shouldUndoAndRedoAllOperationsWithRecordedCursor() {
        val buffer = RopeTextBuffer("abcdef")
        val undoSnapshots = mutableListOf<String>()
        val undoVersions = mutableListOf<Long>()
        buffer.addChangeListener { change ->
            if (change.fromUndoRedo) {
                undoSnapshots += buffer.toString()
                undoVersions += buffer.version
            }
        }

        buffer.editTransaction(
            cursorBefore = 3,
            cursorAfter = { 6 }
        ) {
            replace(4, 6, "XY")
            replace(0, 1, "A")
        }

        assertThat(buffer.toString()).isEqualTo("AbcdXY")

        val undoResult = buffer.undo()

        assertThat(undoResult).isNotNull()
        assertThat(undoResult!!.changes).hasSize(2)
        assertThat(undoResult.cursorOffset).isEqualTo(3)
        assertThat(buffer.toString()).isEqualTo("abcdef")
        assertThat(undoSnapshots).containsExactly("abcdXY", "abcdef").inOrder()
        assertThat(undoVersions).containsExactly(3L, 4L).inOrder()
        assertThat(buffer.canUndo()).isFalse()

        val redoResult = buffer.redo()

        assertThat(redoResult).isNotNull()
        assertThat(redoResult!!.changes).hasSize(2)
        assertThat(redoResult.cursorOffset).isEqualTo(6)
        assertThat(buffer.toString()).isEqualTo("AbcdXY")
    }

    @Test
    fun nestedEditTransaction_shouldCreateSingleHistoryEntry() {
        val buffer = RopeTextBuffer("ab")

        buffer.editTransaction(cursorBefore = 1, cursorAfter = { 3 }) {
            insert(1, "X")
            editTransaction {
                insert(2, "Y")
            }
        }

        assertThat(buffer.toString()).isEqualTo("aXYb")
        assertThat(buffer.undo()!!.cursorOffset).isEqualTo(1)
        assertThat(buffer.toString()).isEqualTo("ab")
        assertThat(buffer.canUndo()).isFalse()
        assertThat(buffer.redo()!!.cursorOffset).isEqualTo(3)
        assertThat(buffer.toString()).isEqualTo("aXYb")
    }

    @Test
    fun noOpEditTransaction_shouldPreserveRedoHistory() {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "x")
        buffer.undo()
        assertThat(buffer.canRedo()).isTrue()

        buffer.editTransaction {
            replace(0, 0, "")
        }

        assertThat(buffer.canRedo()).isTrue()
        buffer.redo()
        assertThat(buffer.toString()).isEqualTo("x")
    }

    @Test
    fun editTransaction_shouldCloseHistoryWhenCursorAfterThrows() {
        val buffer = RopeTextBuffer("abc")

        val failure = runCatching {
            buffer.editTransaction(
                cursorBefore = 1,
                cursorAfter = { error("cursor failure") }
            ) {
                replace(1, 2, "X")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(buffer.toString()).isEqualTo("aXc")
        assertThat(buffer.undo()!!.cursorOffset).isEqualTo(1)
        assertThat(buffer.toString()).isEqualTo("abc")
    }

    @Test
    fun undo_shouldNotMergeAcrossNewline() {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "abc")
        buffer.insert(3, "\n")
        buffer.insert(4, "def")

        assertThat(buffer.toString()).isEqualTo("abc\ndef")
        // 回车是合并边界：undo 第一次应该回到换行前的状态。
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("abc\n")
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("abc")
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("")
    }

    @Test
    fun charAt_shouldReturnSingleCharacterWithoutSubstringAllocation() {
        val buffer = RopeTextBuffer("ab\ncd")

        assertThat(buffer.charAt(0)).isEqualTo('a')
        assertThat(buffer.charAt(2)).isEqualTo('\n')
        assertThat(buffer.charAt(4)).isEqualTo('d')
        assertThat(buffer.charAt(-1)).isNull()
        assertThat(buffer.charAt(buffer.length)).isNull()
    }

    @Test
    fun loadAndSaveShouldWork() = runTest {
        val tempDir = createTempDirectory(prefix = "tina-text-engine-").toFile()
        try {
            val source = File(tempDir, "in.txt").apply { writeText("a\nb\nc") }
            val target = File(tempDir, "out.txt")

            val buffer = RopeTextBuffer()
            val load = buffer.loadFromFile(source)
            assertThat(load.isSuccess).isTrue()
            assertThat(buffer.lineCount).isEqualTo(3)

            buffer.insert(buffer.length, "\nend")
            val save = buffer.saveToFile(target)
            assertThat(save.isSuccess).isTrue()
            assertThat(target.readText()).isEqualTo("a\nb\nc\nend")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun loadFromFile_shouldDispatchFullChangeAndAdvanceVersionFlow() = runTest {
        val tempDir = createTempDirectory(prefix = "tina-text-engine-load-").toFile()
        try {
            val source = File(tempDir, "in.txt").apply { writeText("new\ntext") }
            val buffer = RopeTextBuffer("old")
            val changes = mutableListOf<TextChange>()
            buffer.addChangeListener(changes::add)

            val result = buffer.loadFromFile(source)

            assertThat(result.isSuccess).isTrue()
            assertThat(changes).hasSize(1)
            assertThat(changes.single().oldText).isEqualTo("old")
            assertThat(changes.single().newText).isEqualTo("new\ntext")
            assertThat(buffer.version).isEqualTo(1L)
            assertThat(buffer.versionFlow.value).isEqualTo(buffer.version)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun replaceAll_sameTextShouldClearHistoryWithoutAdvancingVersion() {
        val buffer = RopeTextBuffer("same")
        buffer.insert(buffer.length, "!")
        buffer.delete(buffer.length - 1, buffer.length)
        val versionBefore = buffer.version
        var changeCount = 0
        buffer.addChangeListener { changeCount++ }

        buffer.replaceAll("same")

        assertThat(buffer.version).isEqualTo(versionBefore)
        assertThat(buffer.versionFlow.value).isEqualTo(versionBefore)
        assertThat(changeCount).isEqualTo(0)
        assertThat(buffer.canUndo()).isFalse()
    }

    @Test
    fun replaceAll_shouldReportOldRangeUsingPreviousLineIndex() {
        val buffer = RopeTextBuffer("ab\ncde")
        var captured: TextChange? = null
        buffer.addChangeListener { change ->
            captured = change
        }

        buffer.replaceAll("x\n")

        assertThat(captured).isNotNull()
        assertThat(captured!!.startOffset).isEqualTo(0)
        assertThat(captured!!.endOffset).isEqualTo(6)
        assertThat(captured!!.startLine).isEqualTo(0)
        assertThat(captured!!.startColumn).isEqualTo(0)
        assertThat(captured!!.endLine).isEqualTo(1)
        assertThat(captured!!.endColumn).isEqualTo(3)
    }
}
