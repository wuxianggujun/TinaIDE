package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class EditorVisualLineIndexTest {
    @Test
    fun pointUpdates_matchNaivePrefixSumsAndInverseLookup() {
        val random = Random(8123)
        val counts = IntArray(257) { random.nextInt(1, 8) }
        val index = EditorVisualLineIndex(counts.copyOf())
        repeat(100) {
            val row = random.nextInt(counts.size)
            counts[row] = random.nextInt(1, 20)
            index.update(row, counts[row])
            var first = 0
            for (line in counts.indices) {
                assertThat(index.countAt(line)).isEqualTo(counts[line])
                assertThat(index.firstVisualLineAt(line)).isEqualTo(first)
                assertThat(index.visibleIndexForVisualLine(first)).isEqualTo(line)
                assertThat(index.visibleIndexForVisualLine(first + counts[line] - 1)).isEqualTo(line)
                first += counts[line]
            }
            assertThat(index.total).isEqualTo(first)
            assertThat(index.firstVisualLineAt(counts.size)).isEqualTo(first)
            assertThat(index.visibleIndexForVisualLine(-1)).isEqualTo(0)
            assertThat(index.visibleIndexForVisualLine(Int.MAX_VALUE)).isEqualTo(counts.lastIndex)
        }
    }

    @Test
    fun emptyIndex_hasNoVisualLines() {
        val index = EditorVisualLineIndex(IntArray(0))
        assertThat(index.total).isEqualTo(0)
        assertThat(index.firstVisualLineAt(0)).isEqualTo(0)
        assertThat(index.visibleIndexForVisualLine(20)).isEqualTo(0)
    }
}
