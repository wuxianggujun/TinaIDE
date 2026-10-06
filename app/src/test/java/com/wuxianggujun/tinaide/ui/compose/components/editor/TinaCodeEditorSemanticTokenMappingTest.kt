package com.wuxianggujun.tinaide.ui.compose.components.editor

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorapi.SemanticToken
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.SemanticTokenType
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.After
import org.junit.Test

class TinaCodeEditorSemanticTokenMappingTest {
    private val buffer = RopeTextBuffer("function\ntype\ncustom")
    private val state = EditorState(buffer)

    @After
    fun tearDown() {
        buffer.close()
    }

    @Test
    fun applySemanticTokens_shouldPreserveSharedTypesIncludingCustomTokens() {
        // The LSP decoder now supplies the shared editor-api model. The host must
        // pass it through instead of restoring the removed string compatibility mapper.
        val tokens = listOf(
            SemanticToken(0, 0, 8, SemanticTokenType.FUNCTION),
            SemanticToken(1, 0, 4, SemanticTokenType.TYPE_PARAMETER),
            SemanticToken(2, 0, 6, SemanticTokenType.CUSTOM)
        )

        applySemanticTokens(state, tokens, requestedVisibleLines = null)

        assertThat(state.semanticTokens).containsExactlyElementsIn(tokens).inOrder()
    }

    @Test
    fun applySemanticTokens_shouldRejectInvalidCoordinatesAndEmptySpans() {
        val valid = SemanticToken(0, 0, 8, SemanticTokenType.FUNCTION)

        applySemanticTokens(
            state,
            listOf(valid, valid.copy(line = -1), valid.copy(startColumn = -1), valid.copy(length = 0)),
            requestedVisibleLines = null
        )

        assertThat(state.semanticTokens).containsExactly(valid)
    }

    @Test
    fun applySemanticTokens_shouldReplaceOnlyRequestedLines() {
        val preserved = SemanticToken(0, 0, 8, SemanticTokenType.FUNCTION)
        val stale = SemanticToken(1, 0, 4, SemanticTokenType.TYPE)
        applySemanticTokens(state, listOf(preserved, stale), requestedVisibleLines = null)

        applySemanticTokens(state, emptyList(), requestedVisibleLines = 1..1)

        assertThat(state.semanticTokens).containsExactly(preserved)
    }
}
