package com.wuxianggujun.tinaide.core.git

import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.junit.Test

class GitLineChangeMappingTest {

    @Test
    fun identicalTexts_emptyResult() {
        assertThat(map("a\nb\nc", "a\nb\nc")).isEmpty()
    }

    @Test
    fun insertedLine_marksAdded() {
        val changes = map(old = "a\nb\nc", new = "a\nX\nb\nc")
        assertThat(changes).containsExactly(1, GitLineChangeType.ADDED)
    }

    @Test
    fun modifiedLine_marksModified() {
        val changes = map(old = "a\nb\nc", new = "a\nB\nc")
        assertThat(changes).containsExactly(1, GitLineChangeType.MODIFIED)
    }

    @Test
    fun deletedMiddleLine_marksDeletedOnFollowingSurvivingLine() {
        val changes = map(old = "a\nb\nc", new = "a\nc")
        // 删除的行没有新行可挂，锚到下一存活行（c）。
        assertThat(changes).containsExactly(1, GitLineChangeType.DELETED)
    }

    @Test
    fun deletedLastLine_marksDeletedOnPrecedingLineAtEof() {
        val changes = map(old = "a\nb\nc", new = "a\nb")
        // 末尾删除回退到前一行。
        assertThat(changes).containsExactly(1, GitLineChangeType.DELETED)
    }

    @Test
    fun deletedFirstLine_marksDeletedOnLineZero() {
        val changes = map(old = "a\nb\nc", new = "b\nc")
        assertThat(changes).containsExactly(0, GitLineChangeType.DELETED)
    }

    @Test
    fun replaceShrinking_marksModifiedPlusDeletedAnchor() {
        val changes = map(old = "a\nx\ny\nz\nc", new = "a\nq\nc")
        // 三行被替换成一行：新行标 MODIFIED，净删除部分锚到下一存活行。
        assertThat(changes).containsExactly(1, GitLineChangeType.MODIFIED, 2, GitLineChangeType.DELETED)
    }

    @Test
    fun replaceGrowing_marksModifiedOnly() {
        val changes = map(old = "a\nx\nc", new = "a\np\nq\nc")
        assertThat(changes).containsExactly(1, GitLineChangeType.MODIFIED, 2, GitLineChangeType.MODIFIED)
    }

    @Test
    fun untrackedFileFromEmptyHead_marksAllLinesAdded() {
        val changes = map(old = "", new = "a\nb")
        assertThat(changes).containsExactly(0, GitLineChangeType.ADDED, 1, GitLineChangeType.ADDED)
    }

    @Test
    fun emptiedBuffer_emptyResult() {
        // 空文本没有存活行可以承载删除锚点。
        assertThat(map(old = "a\nb", new = "")).isEmpty()
    }

    @Test
    fun twoSeparateHunks_bothMapped() {
        val changes = map(old = "a\nb\nc\nd\ne\nf\ng", new = "a\nB\nc\nd\ne\nG\ng")
        assertThat(changes).containsExactly(
            1, GitLineChangeType.MODIFIED,
            5, GitLineChangeType.MODIFIED
        )
    }

    private fun map(old: String, new: String): Map<Int, GitLineChangeType> {
        val edits = HistogramDiff().diff(
            RawTextComparator.DEFAULT,
            RawText(old.toByteArray()),
            RawText(new.toByteArray())
        )
        val newLineCount = if (new.isEmpty()) 0 else new.count { it == '\n' } + 1
        return mapDiffEditsToLineChanges(edits, newLineCount)
    }
}
