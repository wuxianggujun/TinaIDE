package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import org.junit.Test

class EditorStateSemanticTokensTest {

    @Test
    fun replaceSemanticTokens_shouldBumpVersionOnlyWhenContentChanges() {
        val state = createState()
        val tokens = listOf(
            SemanticToken(
                line = 0,
                startColumn = 1,
                length = 3,
                tokenType = SemanticTokenType.FUNCTION
            )
        )

        state.replaceSemanticTokens(tokens)
        val firstVersion = state.semanticTokensVersion
        val firstStylingVersion = state.effectiveStylingVersion
        state.replaceSemanticTokens(tokens)

        assertThat(state.semanticTokens).isEqualTo(tokens)
        assertThat(state.semanticTokensByLine[0]).isEqualTo(tokens)
        assertThat(state.semanticTokensVersion).isEqualTo(firstVersion)
        assertThat(state.effectiveStylingVersion).isEqualTo(firstStylingVersion)
    }

    @Test
    fun mergeSemanticTokens_shouldUpdateOnlyChangedLinesAndBumpVersion() {
        val state = createState()
        state.replaceSemanticTokens(
            listOf(
                SemanticToken(
                    line = 0,
                    startColumn = 0,
                    length = 2,
                    tokenType = SemanticTokenType.KEYWORD
                )
            )
        )
        val versionBeforeMerge = state.semanticTokensVersion

        val mergedToken = SemanticToken(
            line = 2,
            startColumn = 4,
            length = 5,
            tokenType = SemanticTokenType.VARIABLE,
            tokenModifiers = setOf(SemanticTokenModifier.READONLY)
        )
        state.mergeSemanticTokens(listOf(mergedToken))

        assertThat(state.semanticTokensByLine.keys).containsExactly(0, 2)
        assertThat(state.semanticTokensByLine[2]).containsExactly(mergedToken)
        assertThat(state.semanticTokensVersion).isEqualTo(versionBeforeMerge + 1)
    }

    @Test
    fun replaceSemanticTokensInLines_shouldRemoveStaleTokensAndPreserveOtherLines() {
        val state = createState()
        val preserved = SemanticToken(
            line = 0,
            startColumn = 0,
            length = 2,
            tokenType = SemanticTokenType.KEYWORD
        )
        val stale = SemanticToken(
            line = 4,
            startColumn = 1,
            length = 3,
            tokenType = SemanticTokenType.FUNCTION
        )
        state.replaceSemanticTokens(listOf(preserved, stale))

        state.replaceSemanticTokensInLines(3..5, emptyList())

        assertThat(state.semanticTokensByLine.keys).containsExactly(0)
        assertThat(state.semanticTokens).containsExactly(preserved)
    }

    @Test
    fun stylingVersion_shouldBumpForSyntaxAndSemanticChanges() {
        val state = createState()

        state.notifyHighlightChanged()
        val afterSyntaxHighlight = state.effectiveStylingVersion

        state.replaceSemanticTokens(
            listOf(
                SemanticToken(
                    line = 0,
                    startColumn = 0,
                    length = 5,
                    tokenType = SemanticTokenType.KEYWORD
                )
            )
        )

        assertThat(afterSyntaxHighlight).isEqualTo(1L)
        assertThat(state.effectiveStylingVersion).isEqualTo(afterSyntaxHighlight + 1)
    }

    @Test
    fun clearSemanticTokens_shouldResetStateAndBumpVersionOnce() {
        val state = createState()
        state.replaceSemanticTokens(
            listOf(
                SemanticToken(
                    line = 1,
                    startColumn = 2,
                    length = 1,
                    tokenType = SemanticTokenType.NUMBER
                )
            )
        )
        val versionBeforeClear = state.semanticTokensVersion

        state.clearSemanticTokens()
        val clearedVersion = state.semanticTokensVersion
        state.clearSemanticTokens()

        assertThat(state.semanticTokens).isEmpty()
        assertThat(state.semanticTokensByLine).isEmpty()
        assertThat(clearedVersion).isEqualTo(versionBeforeClear + 1)
        assertThat(state.semanticTokensVersion).isEqualTo(clearedVersion)
    }

    @Test
    fun applyTextChangeToSemanticTokens_shouldShiftTokenAfterSingleLineInsertion() {
        val state = createState()
        state.replaceSemanticTokens(
            listOf(
                SemanticToken(
                    line = 1,
                    startColumn = 0,
                    length = 4,
                    tokenType = SemanticTokenType.FUNCTION
                )
            )
        )

        state.applyTextChangeToSemanticTokens(
            TextChange(
                startOffset = 6,
                endOffset = 6,
                oldText = "",
                newText = "//",
                startLine = 1,
                startColumn = 0,
                endLine = 1,
                endColumn = 0
            )
        )

        val shifted = SemanticToken(
            line = 1,
            startColumn = 2,
            length = 4,
            tokenType = SemanticTokenType.FUNCTION
        )
        assertThat(state.semanticTokensByLine[1]).containsExactly(shifted)
        assertThat(state.semanticTokens).containsExactly(shifted)
    }

    @Test
    fun applyTextChangeToSemanticTokens_shouldPreserveBeforeDropOverlapAndShiftAfter() {
        val state = createState()
        val before = SemanticToken(1, 0, 2, SemanticTokenType.KEYWORD)
        val overlap = SemanticToken(1, 3, 3, SemanticTokenType.FUNCTION)
        val after = SemanticToken(1, 8, 2, SemanticTokenType.VARIABLE)
        state.replaceSemanticTokens(listOf(before, overlap, after))

        state.applyTextChangeToSemanticTokens(
            TextChange(
                startOffset = 9,
                endOffset = 11,
                oldText = "ta",
                newText = "x",
                startLine = 1,
                startColumn = 3,
                endLine = 1,
                endColumn = 5
            )
        )

        assertThat(state.semanticTokensByLine[1]).containsExactly(
            before,
            after.copy(startColumn = 7)
        ).inOrder()
    }

    @Test
    fun applyTextChangeToSemanticTokens_shouldDropTokenContainingInsertionPoint() {
        val state = createState()
        val containing = SemanticToken(1, 1, 4, SemanticTokenType.FUNCTION)
        val after = SemanticToken(1, 6, 2, SemanticTokenType.VARIABLE)
        state.replaceSemanticTokens(listOf(containing, after))

        state.applyTextChangeToSemanticTokens(
            TextChange(
                startOffset = 9,
                endOffset = 9,
                oldText = "",
                newText = "xx",
                startLine = 1,
                startColumn = 3,
                endLine = 1,
                endColumn = 3
            )
        )

        assertThat(state.semanticTokensByLine[1]).containsExactly(after.copy(startColumn = 8))
    }

    @Test
    fun applyTextChangeToSemanticTokens_insertionAtTokenBoundaryShouldPreserveLeftAndShiftRight() {
        val state = createState()
        val left = SemanticToken(1, 0, 3, SemanticTokenType.KEYWORD)
        val right = SemanticToken(1, 3, 1, SemanticTokenType.VARIABLE)
        state.replaceSemanticTokens(listOf(left, right))

        state.applyTextChangeToSemanticTokens(
            TextChange(
                startOffset = 9,
                endOffset = 9,
                oldText = "",
                newText = "xx",
                startLine = 1,
                startColumn = 3,
                endLine = 1,
                endColumn = 3
            )
        )

        assertThat(state.semanticTokensByLine[1]).containsExactly(
            left,
            right.copy(startColumn = 5)
        ).inOrder()
    }

    @Test
    fun applyTextChangeToSemanticTokens_replacementTouchingTokenEdgesShouldNotDropTokens() {
        val state = createState()
        val left = SemanticToken(0, 0, 2, SemanticTokenType.KEYWORD)
        val right = SemanticToken(0, 3, 2, SemanticTokenType.VARIABLE)
        state.replaceSemanticTokens(listOf(left, right))

        state.applyTextChangeToSemanticTokens(
            TextChange(
                startOffset = 2,
                endOffset = 3,
                oldText = "p",
                newText = "",
                startLine = 0,
                startColumn = 2,
                endLine = 0,
                endColumn = 3
            )
        )

        assertThat(state.semanticTokensByLine[0]).containsExactly(
            left,
            right.copy(startColumn = 2)
        ).inOrder()
    }

    @Test
    fun applyTextChangeToSemanticTokens_negativeDeltaShouldAllowTokenToShiftToColumnZero() {
        val state = createState()
        val token = SemanticToken(0, 3, 2, SemanticTokenType.VARIABLE)
        state.replaceSemanticTokens(listOf(token))

        state.applyTextChangeToSemanticTokens(
            TextChange(
                startOffset = 0,
                endOffset = 3,
                oldText = "alp",
                newText = "",
                startLine = 0,
                startColumn = 0,
                endLine = 0,
                endColumn = 3
            )
        )

        assertThat(state.semanticTokensByLine[0]).containsExactly(token.copy(startColumn = 0))
    }

    @Test
    fun applyTextChangeToSemanticTokens_shouldShiftMovedLinesAfterWholeLineDeletion() {
        val state = createState()
        val movedToken = SemanticToken(
            line = 2,
            startColumn = 1,
            length = 3,
            tokenType = SemanticTokenType.VARIABLE
        )
        state.replaceSemanticTokens(listOf(movedToken))

        state.applyTextChangeToSemanticTokens(
            TextChange(
                startOffset = 6,
                endOffset = 11,
                oldText = "beta\n",
                newText = "",
                startLine = 1,
                startColumn = 0,
                endLine = 2,
                endColumn = 0
            )
        )

        assertThat(state.semanticTokensByLine.keys).containsExactly(1)
        assertThat(state.semanticTokensByLine[1]).containsExactly(movedToken.copy(line = 1))
    }

    private fun createState(): EditorState {
        val buffer = RopeTextBuffer().apply { insert(0, "alpha\nbeta\ngamma") }
        return EditorState(buffer)
    }
}
