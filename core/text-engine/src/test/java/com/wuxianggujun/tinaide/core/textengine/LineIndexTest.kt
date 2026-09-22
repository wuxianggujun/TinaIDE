package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LineIndexTest {

    @Test
    fun rebuild_shouldProvideStableLineMappings() {
        val index = LineIndex()

        index.rebuild("alpha\nbeta\n")

        assertThat(index.lineCount).isEqualTo(3)
        assertThat(index.getLineStart(0)).isEqualTo(0)
        assertThat(index.getLineStart(1)).isEqualTo(6)
        assertThat(index.getLineStart(2)).isEqualTo(11)
        assertThat(index.getLineEnd(1, 11)).isEqualTo(10)
        assertThat(index.offsetToLine(8)).isEqualTo(1)
        assertThat(index.positionToOffset(1, 2, 11)).isEqualTo(8)
    }

    @Test
    fun applyChange_shouldUpdateLaterLineOffsets() {
        val index = LineIndex()
        index.rebuild("a\nbc\ndef")

        index.applyChange(
            startOffset = 2,
            oldText = "bc\n",
            newText = "middle\nx\n"
        )

        val textLength = "a\nmiddle\nx\ndef".length
        assertThat(index.lineCount).isEqualTo(4)
        assertThat(index.getLineStart(0)).isEqualTo(0)
        assertThat(index.getLineStart(1)).isEqualTo(2)
        assertThat(index.getLineStart(2)).isEqualTo(9)
        assertThat(index.getLineStart(3)).isEqualTo(11)
        assertThat(index.offsetToLine(10)).isEqualTo(2)
        assertThat(index.positionToOffset(3, 2, textLength)).isEqualTo(13)
    }

    @Test
    fun appendChunk_shouldMatchRebuildWhenChunksSplitAtArbitraryOffsets() {
        val text = "alpha\nbeta\ngamma\n"
        val rebuilt = LineIndex()
        rebuilt.rebuild(text)

        val streamed = LineIndex()
        streamed.clear()
        streamed.appendChunk("al")
        streamed.appendChunk("pha\nbe")
        streamed.appendChunk("ta\ngamma\n")

        assertThat(streamed.lineCount).isEqualTo(rebuilt.lineCount)
        for (line in 0 until rebuilt.lineCount) {
            assertThat(streamed.getLineStart(line)).isEqualTo(rebuilt.getLineStart(line))
            assertThat(streamed.getLineEnd(line, text.length)).isEqualTo(rebuilt.getLineEnd(line, text.length))
        }
    }

    @Test
    fun appendChunk_shouldKeepCorrectOffsetsWhenNewlineLandsOnChunkBoundary() {
        val prefix = "x".repeat(15)
        val text = "$prefix\nrest"
        val rebuilt = LineIndex()
        rebuilt.rebuild(text)

        val streamed = LineIndex()
        streamed.clear()
        streamed.appendChunk(prefix)
        streamed.appendChunk("\nrest")

        assertThat(streamed.lineCount).isEqualTo(2)
        assertThat(streamed.getLineStart(1)).isEqualTo(rebuilt.getLineStart(1))
        assertThat(streamed.getLineStart(1)).isEqualTo(prefix.length + 1)
    }

    @Test
    fun stealFrom_shouldTransferLineStartsAndLeaveDonorEmpty() {
        val donor = LineIndex()
        donor.rebuild("a\nb\nc")
        val receiver = LineIndex()
        receiver.rebuild("old")

        receiver.stealFrom(donor)

        assertThat(receiver.lineCount).isEqualTo(3)
        assertThat(receiver.getLineStart(1)).isEqualTo(2)
        assertThat(donor.lineCount).isEqualTo(1)
        assertThat(donor.getLineStart(0)).isEqualTo(0)
    }
}
