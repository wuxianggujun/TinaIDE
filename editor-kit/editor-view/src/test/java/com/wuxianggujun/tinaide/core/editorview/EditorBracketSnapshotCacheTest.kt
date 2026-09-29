package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import org.junit.Test

class EditorBracketSnapshotCacheTest {

    @Test
    fun resolveMatchingBracket_shouldReuseExistingVisibleSnapshotWithoutRescanningSubstring() {
        val delegate = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        val guides = cache.resolveVisibleGuides(buffer, 0..2)

        assertThat(guides).isNotEmpty()
        assertThat(buffer.substringCalls).isEqualTo(1)

        val match = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 0..2,
            cursorOffset = buffer.positionToOffset(1, 6)
        )

        assertThat(match).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 1,
                openColumn = 6,
                closeLine = 1,
                closeColumn = 10,
                depth = 1
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(1)
    }

    @Test
    fun resolveMatchingBracket_shouldResolveFromAlignedVisibleSnapshotBeforeFallback() {
        val delegate = RopeTextBuffer().apply {
            insert(0, "{\n  call(\n    foo\n  )\n}\n")
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        cache.resolveVisibleGuides(buffer, 0..1)

        assertThat(buffer.substringCalls).isEqualTo(1)

        val match = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 0..1,
            cursorOffset = buffer.positionToOffset(1, 6)
        )

        assertThat(match).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 1,
                openColumn = 6,
                closeLine = 3,
                closeColumn = 2,
                depth = 1
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(1)
    }

    @Test
    fun resolveMatchingBracket_shouldAvoidPositionRoundTripWhenResolvingFromVisibleSnapshot() {
        val delegate = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        cache.resolveVisibleGuides(buffer, 0..2)
        val cursorOffset = buffer.positionToOffset(1, 6)

        buffer.resetCounters()
        val match = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 0..2,
            cursorOffset = cursorOffset
        )

        assertThat(match).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 1,
                openColumn = 6,
                closeLine = 1,
                closeColumn = 10,
                depth = 1
            )
        )
        assertThat(buffer.offsetToPositionCalls).isEqualTo(2)
        assertThat(buffer.positionToOffsetCalls).isEqualTo(0)
    }

    @Test
    fun resolveVisibleLineBrackets_shouldReuseExistingVisibleSnapshotWithoutExtraSubstring() {
        val delegate = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        cache.resolveVisibleGuides(buffer, 0..2)

        assertThat(buffer.substringCalls).isEqualTo(1)

        val lineBrackets = cache.resolveVisibleLineBrackets(buffer, 0..2)

        assertThat(lineBrackets[1]).containsExactly(
            RainbowBracketComputer.BracketInfo(column = 6, depth = 1, isOpen = true),
            RainbowBracketComputer.BracketInfo(column = 10, depth = 1, isOpen = false)
        ).inOrder()
        assertThat(buffer.substringCalls).isEqualTo(1)
    }

    @Test
    fun resolveVisibleSnapshot_shouldReuseAlignedWindowAcrossNearbyVisibleMoves() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildLargeDocumentWithInlinePair(pairLine = 80))
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        cache.resolveVisibleGuides(buffer, 80..80)

        assertThat(buffer.substringCalls).isEqualTo(1)

        cache.resolveVisibleLineBrackets(buffer, 81..81)

        assertThat(buffer.substringCalls).isEqualTo(1)
    }

    @Test
    fun resolveVisibleSnapshot_shouldRetainAdjacentAlignedWindowsForBackAndForthScroll() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildLargeDocumentWithInlinePair(pairLine = 100))
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        cache.resolveVisibleGuides(buffer, 80..80)
        cache.resolveVisibleLineBrackets(buffer, 96..96)

        assertThat(buffer.substringCalls).isEqualTo(2)

        cache.resolveVisibleGuides(buffer, 81..81)

        assertThat(buffer.substringCalls).isEqualTo(2)
    }

    @Test
    fun resolveMatchingBracket_shouldReuseFallbackWindowSnapshotAcrossNearbyCursorMoves() {
        val delegate = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        val openMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 10..12,
            cursorOffset = buffer.positionToOffset(1, 6)
        )

        assertThat(openMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 1,
                openColumn = 6,
                closeLine = 1,
                closeColumn = 10,
                depth = 1
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(2)

        val closeMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 10..12,
            cursorOffset = buffer.positionToOffset(1, 11)
        )

        assertThat(closeMatch).isEqualTo(openMatch)
        assertThat(buffer.substringCalls).isEqualTo(2)
    }

    @Test
    fun resolveMatchingBracket_shouldAvoidPositionRoundTripWhenResolvingFromFallbackSnapshot() {
        val delegate = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()
        val cursorOffset = buffer.positionToOffset(1, 6)

        buffer.resetCounters()
        val match = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 10..12,
            cursorOffset = cursorOffset
        )

        assertThat(match).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 1,
                openColumn = 6,
                closeLine = 1,
                closeColumn = 10,
                depth = 1
            )
        )
        assertThat(buffer.offsetToPositionCalls).isEqualTo(2)
        assertThat(buffer.positionToOffsetCalls).isEqualTo(0)
    }

    @Test
    fun resolveMatchingBracket_shouldReuseCachedMatchAcrossAlignedVisibleMoves() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildLargeDocumentWithInlinePair(pairLine = 80))
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()
        val anchorOffset = buffer.positionToOffset(80, 6)

        val firstMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 80..80,
            cursorOffset = anchorOffset
        )

        assertThat(firstMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 80,
                openColumn = 6,
                closeLine = 80,
                closeColumn = 10,
                depth = 0
            )
        )

        buffer.resetCounters()
        val secondMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 81..81,
            cursorOffset = anchorOffset
        )

        assertThat(secondMatch).isEqualTo(firstMatch)
        assertThat(buffer.substringCalls).isEqualTo(0)
        assertThat(buffer.offsetToPositionCalls).isEqualTo(0)
    }

    @Test
    fun resolveMatchingBracket_shouldReuseCachedMatchAcrossMateAnchorMoves() {
        val delegate = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()
        val openCursorOffset = buffer.positionToOffset(1, 6)
        val closeCursorOffset = buffer.positionToOffset(1, 11)

        val openMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 10..12,
            cursorOffset = openCursorOffset
        )

        assertThat(openMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 1,
                openColumn = 6,
                closeLine = 1,
                closeColumn = 10,
                depth = 1
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(2)

        buffer.resetCounters()
        val closeMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 10..12,
            cursorOffset = closeCursorOffset
        )

        assertThat(closeMatch).isEqualTo(openMatch)
        assertThat(buffer.substringCalls).isEqualTo(0)
        assertThat(buffer.offsetToPositionCalls).isEqualTo(0)
    }

    @Test
    fun resolveMatchingBracket_shouldBuildVisibleSnapshotForHighlightOnlyWhenAnchorIsVisible() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildLargeDocumentWithInlinePair())
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        val match = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 80..80,
            cursorOffset = buffer.positionToOffset(80, 6)
        )

        assertThat(match).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 80,
                openColumn = 6,
                closeLine = 80,
                closeColumn = 10,
                depth = 0
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(1)
        assertThat(buffer.substringRanges).containsExactly(
            CountingTextBuffer.SubstringRange(
                start = 0,
                end = buffer.getLineEnd(95)
            )
        )
    }

    @Test
    fun resolveMatchingBracket_shouldUseSingleFallbackSnapshotForNearbyVisibleAnchor() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildLargeDocumentWithInlinePair())
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        val match = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 82..82,
            cursorOffset = buffer.positionToOffset(80, 6)
        )

        assertThat(match).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 80,
                openColumn = 6,
                closeLine = 80,
                closeColumn = 10,
                depth = 0
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(2)
        assertThat(buffer.substringRanges.first()).isEqualTo(
            CountingTextBuffer.SubstringRange(
                start = 0,
                end = buffer.length
            )
        )
    }

    @Test
    fun resolveMatchingBracket_shouldReuseSingleFallbackSnapshotAfterVisibleSeed() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildLargeDocumentWithInlinePair(pairLine = 100))
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        cache.resolveVisibleGuides(buffer, 80..80)

        assertThat(buffer.substringCalls).isEqualTo(1)

        buffer.resetCounters()
        val match = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 80..80,
            cursorOffset = buffer.positionToOffset(100, 6)
        )

        assertThat(match).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 100,
                openColumn = 6,
                closeLine = 100,
                closeColumn = 10,
                depth = 0
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(1)
        assertThat(buffer.substringRanges.single()).isEqualTo(
            CountingTextBuffer.SubstringRange(
                start = 0,
                end = buffer.length
            )
        )
    }

    @Test
    fun resolveMatchingBracket_shouldReuseNearbyFallbackSnapshotAcrossVisibleMovesWithinSameAnchorBlock() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildLargeDocumentWithInlinePairs(80, 90))
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        val firstMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 82..82,
            cursorOffset = buffer.positionToOffset(80, 6)
        )

        assertThat(firstMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 80,
                openColumn = 6,
                closeLine = 80,
                closeColumn = 10,
                depth = 0
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(2)

        buffer.resetCounters()
        val secondMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 97..97,
            cursorOffset = buffer.positionToOffset(90, 6)
        )

        assertThat(secondMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 90,
                openColumn = 6,
                closeLine = 90,
                closeColumn = 10,
                depth = 0
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(0)
    }

    @Test
    fun resolveMatchingBracket_shouldReuseUnifiedFallbackSnapshotAcrossNearbyCursorMoves() {
        val delegate = RopeTextBuffer().apply {
            insert(
                0,
                buildPaddedDocumentWithInlinePair(
                    totalLines = 2200,
                    pairLine = 1000,
                    fillerWidth = 120
                )
            )
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        val openMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 1700..1700,
            cursorOffset = buffer.positionToOffset(1000, 6)
        )

        assertThat(openMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 1000,
                openColumn = 6,
                closeLine = 1000,
                closeColumn = 10,
                depth = 0
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(2)

        val closeMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 1700..1700,
            cursorOffset = buffer.positionToOffset(1000, 11)
        )

        assertThat(closeMatch).isEqualTo(openMatch)
        assertThat(buffer.substringCalls).isEqualTo(2)
    }

    @Test
    fun resolveMatchingBracket_shouldResolveFarPairsWithSingleFallbackSnapshotAfterVisibleScan() {
        val delegate = RopeTextBuffer().apply {
            insert(
                0,
                buildDocumentWithNestedFarPairs(
                    totalLines = 220,
                    openLine = 80,
                    innerCloseLine = 160,
                    outerCloseLine = 161
                )
            )
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = EditorBracketSnapshotCache()

        val outerMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 80..80,
            cursorOffset = buffer.positionToOffset(80, 0)
        )

        assertThat(outerMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 80,
                openColumn = 0,
                closeLine = 161,
                closeColumn = 0,
                depth = 0
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(2)

        val innerMatch = cache.resolveMatchingBracket(
            textBuffer = buffer,
            visibleLines = 81..81,
            cursorOffset = buffer.positionToOffset(80, 1)
        )

        assertThat(innerMatch).isEqualTo(
            EditorBracketSnapshotCache.BracketMatch(
                openLine = 80,
                openColumn = 1,
                closeLine = 160,
                closeColumn = 0,
                depth = 1
            )
        )
        assertThat(buffer.substringCalls).isEqualTo(2)
    }

    private fun buildLargeDocumentWithInlinePair(pairLine: Int = 80): String {
        val lines = ArrayList<String>(140)
        repeat(140) { index ->
            lines += if (index == pairLine) {
                "  call(foo)"
            } else {
                "value$index"
            }
        }
        return lines.joinToString("\n")
    }

    private fun buildLargeDocumentWithInlinePairs(vararg pairLines: Int): String {
        val pairLineSet = pairLines.toSet()
        val lastPairLine = pairLineSet.maxOrNull() ?: 0
        val totalLines = (lastPairLine + 40).coerceAtLeast(140)
        val lines = ArrayList<String>(totalLines)
        repeat(totalLines) { index ->
            lines += if (index in pairLineSet) {
                "  call(foo)"
            } else {
                "value$index"
            }
        }
        return lines.joinToString("\n")
    }

    private fun buildPaddedDocumentWithInlinePair(
        totalLines: Int,
        pairLine: Int,
        fillerWidth: Int
    ): String {
        val suffix = "x".repeat(fillerWidth.coerceAtLeast(16))
        val lines = ArrayList<String>(totalLines)
        repeat(totalLines) { index ->
            lines += if (index == pairLine) {
                "  call(foo)$suffix"
            } else {
                "line${index.toString().padStart(4, '0')}_$suffix"
            }
        }
        return lines.joinToString("\n")
    }

    private fun buildDocumentWithNestedFarPairs(
        totalLines: Int,
        openLine: Int,
        innerCloseLine: Int,
        outerCloseLine: Int
    ): String {
        val lines = ArrayList<String>(totalLines)
        repeat(totalLines) { index ->
            lines += when (index) {
                openLine -> "{["
                innerCloseLine -> "]"
                outerCloseLine -> "}"
                else -> "value$index"
            }
        }
        return lines.joinToString("\n")
    }

    private class CountingTextBuffer(
        private val delegate: RopeTextBuffer
    ) : TextBuffer by delegate {
        data class SubstringRange(
            val start: Int,
            val end: Int
        )

        var substringCalls: Int = 0
            private set
        var offsetToPositionCalls: Int = 0
            private set
        var positionToOffsetCalls: Int = 0
            private set
        val substringRanges: MutableList<SubstringRange> = mutableListOf()

        override fun substring(start: Int, end: Int): String {
            substringCalls++
            substringRanges += SubstringRange(start = start, end = end)
            return delegate.substring(start, end)
        }

        override fun offsetToPosition(offset: Int): Position {
            offsetToPositionCalls++
            return delegate.offsetToPosition(offset)
        }

        override fun positionToOffset(line: Int, column: Int): Int {
            positionToOffsetCalls++
            return delegate.positionToOffset(line, column)
        }

        fun resetCounters() {
            substringCalls = 0
            offsetToPositionCalls = 0
            positionToOffsetCalls = 0
            substringRanges.clear()
        }
    }
}
