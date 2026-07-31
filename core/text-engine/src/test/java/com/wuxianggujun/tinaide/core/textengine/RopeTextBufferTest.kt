package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun replace_invalidRangeShouldFailWithoutChangingContent() {
        val buffer = RopeTextBuffer("abc")

        val failure = runCatching { buffer.replace(0, 5, "x") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(buffer.toString()).isEqualTo("abc")
        assertThat(buffer.version).isEqualTo(0L)
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
    fun cursorAwareDelete_shouldRestoreRecordedCursorOnUndoAndRedo() {
        val buffer = RopeTextBuffer("abcd")
        val cursor = TextEditCursorSnapshot(cursorBefore = 1, cursorAfter = 1)

        buffer.delete(start = 1, end = 3, historyCursor = cursor)

        assertThat(buffer.undo()!!.cursorOffset).isEqualTo(1)
        assertThat(buffer.toString()).isEqualTo("abcd")
        assertThat(buffer.redo()!!.cursorOffset).isEqualTo(1)
        assertThat(buffer.toString()).isEqualTo("ad")
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
        assertThat(undoSnapshots).containsExactly("abcdef", "abcdef").inOrder()
        assertThat(undoVersions).containsExactly(4L, 4L).inOrder()
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
    fun undoRedo_shouldReturnNullWhileCompoundEditIsOpen() {
        val buffer = RopeTextBuffer("abc")
        buffer.insert(3, "d")
        buffer.undo()
        val token = buffer.beginCompoundEdit(cursorBefore = 3)
        buffer.insert(3, "x")

        assertThat(buffer.canUndo()).isFalse()
        assertThat(buffer.canRedo()).isFalse()
        assertThat(buffer.undo()).isNull()
        assertThat(buffer.redo()).isNull()
        assertThat(buffer.toString()).isEqualTo("abcx")

        buffer.endCompoundEdit(token, cursorAfter = 4)
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("abc")
    }

    @Test
    fun undoListener_shouldRunAfterWriteLockIsReleased() {
        val buffer = RopeTextBuffer("abc")
        val executor = Executors.newSingleThreadExecutor()
        var crossThreadSnapshot: String? = null
        buffer.insert(3, "d")
        buffer.addChangeListener { change ->
            if (change.fromUndoRedo) {
                crossThreadSnapshot = runCatching {
                    executor.submit<String> { buffer.toString() }.get(1, TimeUnit.SECONDS)
                }.getOrNull()
            }
        }

        try {
            buffer.undo()
        } finally {
            executor.shutdownNow()
        }

        assertThat(crossThreadSnapshot).isEqualTo("abc")
    }

    @Test
    fun editTransactionListener_shouldRunAfterWriteLockIsReleased() {
        val buffer = RopeTextBuffer("abc")
        val executor = Executors.newSingleThreadExecutor()
        val crossThreadSnapshots = mutableListOf<String?>()
        buffer.addChangeListener {
            crossThreadSnapshots += runCatching {
                executor.submit<String> { buffer.toString() }.get(1, TimeUnit.SECONDS)
            }.getOrNull()
        }

        try {
            buffer.editTransaction {
                insert(3, "d")
                insert(4, "e")
            }
        } finally {
            executor.shutdownNow()
        }

        assertThat(crossThreadSnapshots).containsExactly("abcde", "abcde").inOrder()
    }

    @Test
    fun editTransactionListener_shouldNotLetReentrantEditOvertakeDeferredChanges() {
        val buffer = RopeTextBuffer("abc")
        val observedText = mutableListOf<String>()
        var reentered = false
        buffer.addChangeListener { change ->
            observedText += change.newText
            if (!reentered) {
                reentered = true
                buffer.insert(buffer.length, "!")
            }
        }

        buffer.editTransaction {
            insert(3, "d")
            insert(4, "e")
        }

        assertThat(buffer.toString()).isEqualTo("abcde!")
        assertThat(observedText).containsExactly("d", "e", "!").inOrder()
    }

    @Test
    fun replaceAllInsideTransaction_shouldClearHistoryWithoutLeakingCompoundState() {
        val buffer = RopeTextBuffer("old")

        buffer.editTransaction {
            buffer.replaceAll("new")
        }
        buffer.insert(buffer.length, "!")

        assertThat(buffer.canUndo()).isTrue()
        buffer.undo()
        assertThat(buffer.toString()).isEqualTo("new")
        assertThat(buffer.canUndo()).isFalse()
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
    fun lineApi_shouldExcludeCarriageReturnFromCrlfContent() {
        val buffer = RopeTextBuffer("ab\r\ncd\r\n")

        assertThat(buffer.getLine(0)).isEqualTo("ab")
        assertThat(buffer.getLineEnd(0)).isEqualTo(2)
        assertThat(buffer.positionToOffset(0, 100)).isEqualTo(2)
        assertThat(buffer.offsetToPosition(2)).isEqualTo(Position(0, 2))
        assertThat(buffer.offsetToPosition(3)).isEqualTo(Position(0, 2))
        assertThat(buffer.getLine(1)).isEqualTo("cd")
        assertThat(buffer.positionToOffset(1, Int.MAX_VALUE)).isEqualTo(6)
        assertThat(buffer.positionToOffset(1, Int.MIN_VALUE)).isEqualTo(4)
        assertThat(buffer.getLine(2)).isEmpty()
    }

    @Test
    fun lineApi_shouldPreserveLoneTrailingCarriageReturn() {
        val buffer = RopeTextBuffer("ab\r")

        assertThat(buffer.getLine(0)).isEqualTo("ab\r")
        assertThat(buffer.getLineEnd(0)).isEqualTo(3)
        assertThat(buffer.positionToOffset(0, Int.MAX_VALUE)).isEqualTo(3)
        assertThat(buffer.offsetToPosition(3)).isEqualTo(Position(0, 3))
    }

    @Test
    fun publicEdits_shouldRejectOffsetsInsideSurrogatePairs() {
        val buffer = RopeTextBuffer("A\uD83D\uDE00B")

        val insertFailure = runCatching { buffer.insert(2, "x") }.exceptionOrNull()
        val deleteFailure = runCatching { buffer.delete(1, 2) }.exceptionOrNull()
        val replaceFailure = runCatching { buffer.replace(2, 3, "x") }.exceptionOrNull()

        assertThat(insertFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(deleteFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(replaceFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(buffer.toString()).isEqualTo("A\uD83D\uDE00B")
    }

    @Test
    fun publicEdits_shouldAllowCreatingSurrogatePairAcrossEditBoundaryAndRemainUndoable() {
        val high = "\uD83D"
        val low = "\uDE00"
        val cases = listOf(
            Triple(RopeTextBuffer(high), { buffer: RopeTextBuffer -> buffer.insert(1, low) }, high),
            Triple(RopeTextBuffer(low), { buffer: RopeTextBuffer -> buffer.insert(0, high) }, low),
            Triple(
                RopeTextBuffer(high + "x" + low),
                { buffer: RopeTextBuffer -> buffer.delete(1, 2) },
                high + "x" + low
            ),
            Triple(
                RopeTextBuffer(high + "x"),
                { buffer: RopeTextBuffer -> buffer.replace(1, 2, low) },
                high + "x"
            )
        )

        cases.forEach { (buffer, edit, original) ->
            edit(buffer)
            assertThat(buffer.toString()).isEqualTo(high + low)
            assertThat(buffer.undo()).isNotNull()
            assertThat(buffer.toString()).isEqualTo(original)
            assertThat(buffer.redo()).isNotNull()
            assertThat(buffer.toString()).isEqualTo(high + low)
        }
    }

    @Test
    fun insertingCompleteSurrogatePair_shouldRemainUndoable() {
        val buffer = RopeTextBuffer("AB")

        buffer.insert(1, "\uD83D\uDE00")

        assertThat(buffer.toString()).isEqualTo("A\uD83D\uDE00B")
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("AB")
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
            val change = changes.single()
            assertThat(change.oldText).isEmpty()
            assertThat(change.hasCompleteOldText).isFalse()
            assertThat(change.oldTextLength).isEqualTo(3)
            assertThat(change.oldLineBreakCount).isEqualTo(0)
            assertThat(change.oldTextEndsWithLineBreak).isFalse()
            assertThat(change.newText).isEqualTo("new\ntext")
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
