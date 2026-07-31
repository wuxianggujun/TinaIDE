package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextScanKernelTest {

    @Test
    fun hasActiveSignatureHelpContext_shouldDetectCallContexts() {
        assertThat(TextScanKernel.hasActiveSignatureHelpContext("print(")).isTrue()
        assertThat(
            TextScanKernel.hasActiveSignatureHelpContext(
                "factory<Map<String, List<Int>>>(value, "
            )
        ).isTrue()
        assertThat(TextScanKernel.hasActiveSignatureHelpContext("print(value)")).isFalse()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldIgnoreCommentsAndStrings() {
        assertThat(
            TextScanKernel.hasActiveSignatureHelpContext("print(value, // )\n")
        ).isTrue()
        assertThat(
            TextScanKernel.hasActiveSignatureHelpContext("print(\"\"\"value \" ) still raw\"\"\", ")
        ).isTrue()
        assertThat(
            TextScanKernel.hasActiveSignatureHelpContext("print(value) /* ( */\n")
        ).isFalse()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldHandleTrailingLambda() {
        assertThat(
            TextScanKernel.hasActiveSignatureHelpContext("fold(0) { acc, value -> ")
        ).isTrue()
        assertThat(
            TextScanKernel.hasActiveSignatureHelpContext("if (ready) { ")
        ).isFalse()
    }

    @Test
    fun computeBracketInfo_shouldReturnColumnsAndDepths() {
        assertThat(TextScanKernel.computeBracketInfo(1, "a(b[c]{d})"))
            .containsExactly(
                BracketScanResult(column = 1, depth = 1, isOpen = true),
                BracketScanResult(column = 3, depth = 2, isOpen = true),
                BracketScanResult(column = 5, depth = 2, isOpen = false),
                BracketScanResult(column = 6, depth = 2, isOpen = true),
                BracketScanResult(column = 8, depth = 2, isOpen = false),
                BracketScanResult(column = 9, depth = 1, isOpen = false)
            )
            .inOrder()
    }

    @Test
    fun findMatchingBracket_shouldMatchForwardFromOpenBracket() {
        assertThat(TextScanKernel.findMatchingBracket("call(foo[bar])", 4))
            .isEqualTo(BracketPairMatchResult(openOffset = 4, closeOffset = 13))
    }

    @Test
    fun findMatchingBracket_shouldMatchBackwardFromCloseBracketBeforeCursor() {
        assertThat(TextScanKernel.findMatchingBracket("call(foo[bar])", 14))
            .isEqualTo(BracketPairMatchResult(openOffset = 4, closeOffset = 13))
    }

    @Test
    fun findMatchingBracket_shouldHandleNestedSameTypeBrackets() {
        assertThat(TextScanKernel.findMatchingBracket("((value))", 0))
            .isEqualTo(BracketPairMatchResult(openOffset = 0, closeOffset = 8))
    }

    @Test
    fun findMatchingBracket_shouldReturnNullWhenCursorNotNearBracket() {
        assertThat(TextScanKernel.findMatchingBracket("alpha beta", 3)).isNull()
    }

    @Test
    fun findBracketPairs_shouldReturnNestedPairsWithDepth() {
        assertThat(TextScanKernel.findBracketPairs("{\n  [value]\n}"))
            .containsExactly(
                BracketPairScanResult(openOffset = 4, closeOffset = 10, depth = 1),
                BracketPairScanResult(openOffset = 0, closeOffset = 12, depth = 0)
            )
            .inOrder()
    }

    @Test
    fun findBracketPairs_shouldIgnoreMismatchedCloserTypes() {
        assertThat(TextScanKernel.findBracketPairs("{]")).isEmpty()
    }

    @Test
    fun findBracketGuideSpans_shouldReturnClosedVisibleGuides() {
        assertThat(
            TextScanKernel.findBracketGuideSpans(
                text = "{\n  [value]\n}\n",
                visibleStartLine = 1,
                visibleEndLine = 2,
                includeOpenSpansAtEnd = false
            )
        ).containsExactly(
            BracketGuideSpanScanResult(
                openLine = 0,
                openColumn = 0,
                closeLine = 2,
                closeColumn = 0,
                depth = 0,
                openEnded = false
            )
        )
    }

    @Test
    fun findBracketGuideSpans_shouldReturnOpenEndedGuidesWhenPrefixStopsAtVisibleBottom() {
        assertThat(
            TextScanKernel.findBracketGuideSpans(
                text = "{\n  block {\n    value()\n",
                visibleStartLine = 1,
                visibleEndLine = 2,
                includeOpenSpansAtEnd = true
            )
        ).containsExactly(
            BracketGuideSpanScanResult(
                openLine = 0,
                openColumn = 0,
                closeLine = 2,
                closeColumn = -1,
                depth = 0,
                openEnded = true
            ),
            BracketGuideSpanScanResult(
                openLine = 1,
                openColumn = 8,
                closeLine = 2,
                closeColumn = -1,
                depth = 1,
                openEnded = true
            )
        ).inOrder()
    }

    @Test
    fun findBracketGuideSpans_shouldSkipOpenEndedGuidesWhenDisabled() {
        assertThat(
            TextScanKernel.findBracketGuideSpans(
                text = "{\n  block {\n    value()\n",
                visibleStartLine = 1,
                visibleEndLine = 2,
                includeOpenSpansAtEnd = false
            )
        ).isEmpty()
    }

    @Test
    fun scanBracketSnapshot_shouldBatchPairsGuidesAndVisibleLineBrackets() {
        val snapshot = TextScanKernel.scanBracketSnapshot(
            startDepth = 0,
            text = "{\n  call(foo)\n}\n",
            visibleStartLine = 1,
            visibleEndLine = 2,
            includeOpenSpansAtEnd = false
        )

        assertThat(snapshot.pairs).containsExactly(
            BracketPairScanResult(openOffset = 8, closeOffset = 12, depth = 1),
            BracketPairScanResult(openOffset = 0, closeOffset = 14, depth = 0)
        ).inOrder()
        assertThat(snapshot.guides).containsExactly(
            BracketGuideSpanScanResult(
                openLine = 0,
                openColumn = 0,
                closeLine = 2,
                closeColumn = 0,
                depth = 0,
                openEnded = false
            )
        )
        assertThat(snapshot.visibleLineBrackets).containsExactly(
            VisibleLineBracketScanResult(line = 1, column = 6, depth = 1, isOpen = true),
            VisibleLineBracketScanResult(line = 1, column = 10, depth = 1, isOpen = false),
            VisibleLineBracketScanResult(line = 2, column = 0, depth = 0, isOpen = false)
        ).inOrder()
        assertThat(snapshot.lineBoundaryDepths.asList()).containsExactly(0, 1, 1, 0, 0).inOrder()
    }

    @Test
    fun advanceBracketDepth_shouldClampUnbalancedClosers() {
        assertThat(TextScanKernel.advanceBracketDepth(0, ")))")).isEqualTo(0)
        assertThat(TextScanKernel.advanceBracketDepth(1, "([]")).isEqualTo(2)
    }

    @Test
    fun advanceBracketDepth_shouldSupportPrefixScan() {
        assertThat(TextScanKernel.advanceBracketDepth(1, "a(b[c]{d})", 4)).isEqualTo(3)
        assertThat(TextScanKernel.advanceBracketDepth(2, "}) value", 1)).isEqualTo(1)
        assertThat(TextScanKernel.advanceBracketDepth(2, "}) value", 0)).isEqualTo(2)
    }

    @Test
    fun computeLineBoundaryBracketDepths_shouldTrackLineStartsAndTrailingDepth() {
        assertThat(
            TextScanKernel.computeLineBoundaryBracketDepths(
                startDepth = 1,
                text = "{\n  [value]\n}"
            ).asList()
        ).containsExactly(1, 2, 2, 1).inOrder()
    }

    @Test
    fun findWordBounds_shouldReturnWholeWordWhenCursorInsideIdentifier() {
        assertThat(TextScanKernel.findWordBounds("alphaBeta gamma", 5))
            .isEqualTo(WordBounds(start = 0, end = 9))
    }

    @Test
    fun findWordBounds_shouldUsePreviousCharacterWhenCursorAtWordEnd() {
        assertThat(TextScanKernel.findWordBounds("alpha_beta gamma", 10))
            .isEqualTo(WordBounds(start = 0, end = 10))
    }

    @Test
    fun findWordBounds_shouldReturnNullOnWhitespace() {
        assertThat(TextScanKernel.findWordBounds("alpha  beta", 6)).isNull()
    }

    @Test
    fun findWordBounds_shouldTreatUnderscoreAsWordCharacter() {
        assertThat(TextScanKernel.findWordBounds("foo_bar", 4))
            .isEqualTo(WordBounds(start = 0, end = 7))
    }

    @Test
    fun findWordBounds_shouldSupportUnicodeLetters() {
        assertThat(TextScanKernel.findWordBounds("变量名 value", 2))
            .isEqualTo(WordBounds(start = 0, end = 3))
    }

    @Test
    fun findWordPrefixStart_shouldReturnStartOfWordBeforeCursor() {
        assertThat(TextScanKernel.findWordPrefixStart("alphaBeta gamma", 5))
            .isEqualTo(0)
    }

    @Test
    fun findWordPrefixStart_shouldStopOnWhitespaceBeforeCursor() {
        assertThat(TextScanKernel.findWordPrefixStart("alpha  beta", 6))
            .isEqualTo(6)
    }

    @Test
    fun findWordPrefixStart_shouldSupportUnicodeLetters() {
        assertThat(TextScanKernel.findWordPrefixStart("变量名 value", 2))
            .isEqualTo(0)
    }

    @Test
    fun findWholeWordMatches_shouldMatchOnlyWholeWords() {
        assertThat(TextScanKernel.findWholeWordMatches("foo bar foo_bar foo", "foo").asList())
            .containsExactly(0, 16)
            .inOrder()
    }

    @Test
    fun findWholeWordMatches_shouldAllowAdjacentPunctuation() {
        assertThat(TextScanKernel.findWholeWordMatches("foo(foo) foo,", "foo").asList())
            .containsExactly(0, 4, 9)
            .inOrder()
    }

    @Test
    fun findWholeWordMatches_shouldSupportUnicodeWords() {
        assertThat(TextScanKernel.findWholeWordMatches("变量 变量名 变量", "变量").asList())
            .containsExactly(0, 7)
            .inOrder()
    }

    @Test
    fun findWhitespaceMarkers_shouldReturnBoundaryWhitespaceOnly() {
        assertThat(decodeWhitespaceMarkers(TextScanKernel.findWhitespaceMarkers(" \tfoo \t", boundaryOnly = true)))
            .containsExactly(
                0 to false,
                1 to true,
                5 to false,
                6 to true
            )
            .inOrder()
    }

    @Test
    fun findWhitespaceMarkers_shouldReturnAllWhitespaceWhenRequested() {
        assertThat(decodeWhitespaceMarkers(TextScanKernel.findWhitespaceMarkers(" a \tb ", boundaryOnly = false)))
            .containsExactly(
                0 to false,
                2 to false,
                3 to true,
                5 to false
            )
            .inOrder()
    }

    @Test
    fun findWhitespaceMarkers_shouldTreatBlankLineAsBoundaryWhitespace() {
        assertThat(decodeWhitespaceMarkers(TextScanKernel.findWhitespaceMarkers(" \t ", boundaryOnly = true)))
            .containsExactly(
                0 to false,
                1 to true,
                2 to false
            )
            .inOrder()
    }

    @Test
    fun findTabColumns_shouldReturnAllTabOffsets() {
        assertThat(TextScanKernel.findTabColumns("a\tbc\t\t").asList())
            .containsExactly(1, 4, 5)
            .inOrder()
    }

    @Test
    fun measureVisualColumns_shouldExpandTabs() {
        assertThat(TextScanKernel.measureVisualColumns("ab\tc", tabSize = 4)).isEqualTo(5)
        assertThat(TextScanKernel.measureVisualColumns("\t\t", tabSize = 4)).isEqualTo(8)
    }

    @Test
    fun measureVisualColumns_shouldSupportPrefixScan() {
        assertThat(TextScanKernel.measureVisualColumns("ab\tc", tabSize = 4, endColumn = 2)).isEqualTo(2)
        assertThat(TextScanKernel.measureVisualColumns("ab\tc", tabSize = 4, endColumn = 3)).isEqualTo(4)
        assertThat(TextScanKernel.measureVisualColumns("ab\tc", tabSize = 4, endColumn = 4)).isEqualTo(5)
    }

    @Test
    fun findWrapSegmentStarts_shouldSplitUsingVisualColumns() {
        assertThat(TextScanKernel.findWrapSegmentStarts("ab\tcd", wrapColumns = 4, tabSize = 4).asList())
            .containsExactly(0, 3)
            .inOrder()
    }

    @Test
    fun findWrapSegmentStarts_shouldNotSplitSurrogatePair() {
        val starts = TextScanKernel.findWrapSegmentStarts(
            lineText = "a\uD83D\uDE00b",
            wrapColumns = 2,
            tabSize = 4
        )

        assertThat(starts.asList()).containsExactly(0, 1, 3).inOrder()
        assertThat(starts.asList()).doesNotContain(2)
        assertThat(
            TextScanKernel.countWrapSegments(
                lineText = "a\uD83D\uDE00b",
                wrapColumns = 2,
                tabSize = 4
            )
        ).isEqualTo(starts.size)
    }

    @Test
    fun countWrapSegments_shouldMatchFullLayout() {
        val cases = listOf("", "abc", "abcdef", "ab\tcd", "\t\txyz", "a😀b")

        cases.forEach { lineText ->
            val fullLayout = TextScanKernel.findWrapSegmentStarts(
                lineText = lineText,
                wrapColumns = 4,
                tabSize = 4
            )
            assertThat(
                TextScanKernel.countWrapSegments(
                    lineText = lineText,
                    wrapColumns = 4,
                    tabSize = 4
                )
            ).isEqualTo(fullLayout.size)
        }
    }

    @Test
    fun buildVisualColumnPrefix_shouldTrackExpandedTabColumns() {
        assertThat(TextScanKernel.buildVisualColumnPrefix("a\tb", tabSize = 4).asList())
            .containsExactly(0, 1, 4, 5)
            .inOrder()
    }

    @Test
    fun scanLineWhitespace_shouldReturnLeadingAndTrailingBounds() {
        assertThat(TextScanKernel.scanLineWhitespace(" \tfoo  ", tabSize = 4))
            .isEqualTo(
                LineWhitespaceInfo(
                    leadingWhitespaceEnd = 2,
                    leadingIndentColumns = 4,
                    trailingWhitespaceStart = 5,
                    outdentRemoveCount = 1
                )
            )
    }

    @Test
    fun scanLineWhitespace_shouldTreatNonIndentWhitespaceSeparately() {
        assertThat(TextScanKernel.scanLineWhitespace("\u2003 foo\u2002", tabSize = 4))
            .isEqualTo(
                LineWhitespaceInfo(
                    leadingWhitespaceEnd = 2,
                    leadingIndentColumns = 0,
                    trailingWhitespaceStart = 5,
                    outdentRemoveCount = 0
                )
            )
    }

    private fun decodeWhitespaceMarkers(markers: IntArray): List<Pair<Int, Boolean>> = markers.map { marker ->
        TextScanKernel.whitespaceMarkerColumn(marker) to TextScanKernel.whitespaceMarkerIsTab(marker)
    }
}
