package com.wuxianggujun.tinaide.core.treesitter

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TreeSitterPrewarmPlanTest {
    @Test
    fun open_shouldCoverDocumentOnceWithViewportChunkFirst() {
        val ranges = TreeSitterPrewarmPlan.ranges(10_000, 5_000, fullDocument = true)

        assertThat(5_000 in ranges.first()).isTrue()
        assertThat(ranges.flatMap { it }.sorted()).containsExactlyElementsIn(0 until 10_000).inOrder()
        assertThat(ranges.all { it.count() <= 2048 }).isTrue()
    }

    @Test
    fun edit_shouldStayWithinViewportBudgetRegardlessOfDocumentSize() {
        val ranges = TreeSitterPrewarmPlan.ranges(100_000, 50_000, fullDocument = false)

        assertThat(50_000 in ranges.first()).isTrue()
        assertThat(ranges.flatMap { it }.sorted()).containsExactlyElementsIn(49_872..50_128).inOrder()
        assertThat(ranges.all { it.count() <= 256 }).isTrue()
    }

    @Test
    fun editAtChunkBoundary_shouldNotDuplicateOrSkipLines() {
        val ranges = TreeSitterPrewarmPlan.ranges(5_000, 2_048, fullDocument = false)

        assertThat(ranges.flatMap { it }.sorted()).containsExactlyElementsIn(1_920..2_176).inOrder()
    }

    @Test
    fun edit_shouldClampToDocumentEdges() {
        val start = TreeSitterPrewarmPlan.ranges(1_000, -1, fullDocument = false)
        val end = TreeSitterPrewarmPlan.ranges(1_000, Int.MAX_VALUE, fullDocument = false)

        assertThat(start.flatMap { it }).containsExactlyElementsIn(0..128).inOrder()
        assertThat(end.flatMap { it }.sorted()).containsExactlyElementsIn(871..999).inOrder()
    }

    @Test
    fun emptyDocument_shouldHaveNoPrewarmWork() {
        assertThat(TreeSitterPrewarmPlan.ranges(0, 0, fullDocument = true)).isEmpty()
        assertThat(TreeSitterPrewarmPlan.ranges(0, 0, fullDocument = false)).isEmpty()
    }

    @Test
    fun editAtMaximumLineCount_shouldNotOverflowRangeEnd() {
        val ranges = TreeSitterPrewarmPlan.ranges(Int.MAX_VALUE, Int.MAX_VALUE, fullDocument = false)

        assertThat(ranges.sumOf { it.count() }).isEqualTo(129)
        assertThat(ranges.maxOf { it.last }).isEqualTo(Int.MAX_VALUE - 1)
        assertThat(ranges.all { it.first >= 0 }).isTrue()
    }
}
