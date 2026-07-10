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
    fun applyTextChangeToSemanticTokens_shouldDropChangedLineTokens() {
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

        assertThat(state.semanticTokensByLine).doesNotContainKey(1)
        assertThat(state.semanticTokens).isEmpty()
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
