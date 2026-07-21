package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LspSemanticTokenDecoderTest {

    @Test
    fun decode_shouldResolveDeltaPositionsAcrossLines() {
        val rawData = listOf(
            0, 4, 3, 1, 0,
            0, 5, 2, 0, 0,
            2, 1, 4, 2, 0
        )

        val tokens = LspSemanticTokenDecoder.decode(
            rawData = rawData,
            tokenTypes = listOf("variable", "function", "class"),
            tokenModifiers = emptyList()
        )

        assertThat(tokens).hasSize(3)
        assertThat(tokens[0].line).isEqualTo(0)
        assertThat(tokens[0].startColumn).isEqualTo(4)
        assertThat(tokens[0].tokenType).isEqualTo("function")

        assertThat(tokens[1].line).isEqualTo(0)
        assertThat(tokens[1].startColumn).isEqualTo(9)
        assertThat(tokens[1].tokenType).isEqualTo("variable")

        assertThat(tokens[2].line).isEqualTo(2)
        assertThat(tokens[2].startColumn).isEqualTo(1)
        assertThat(tokens[2].tokenType).isEqualTo("class")
    }

    @Test
    fun decode_shouldDecodeModifierBits() {
        val rawData = listOf(
            0,
            0,
            5,
            0,
            0b1011
        )

        val tokens = LspSemanticTokenDecoder.decode(
            rawData = rawData,
            tokenTypes = listOf("variable"),
            tokenModifiers = listOf("declaration", "definition", "readonly", "static")
        )

        assertThat(tokens).hasSize(1)
        assertThat(tokens.first().tokenModifiers)
            .containsExactly("declaration", "definition", "static")
    }

    @Test
    fun decode_shouldFallbackTypeWhenTokenTypeIndexOutOfBounds() {
        val rawData = listOf(
            0,
            2,
            1,
            99,
            0
        )

        val tokens = LspSemanticTokenDecoder.decode(
            rawData = rawData,
            tokenTypes = listOf("keyword"),
            tokenModifiers = emptyList()
        )

        assertThat(tokens).hasSize(1)
        assertThat(tokens.first().tokenType).isEqualTo("variable")
    }

    @Test
    fun decode_shouldIgnoreIncompleteTailTuple() {
        val rawData = listOf(
            0,
            0,
            1,
            0,
            0,
            1,
            3
        )

        val tokens = LspSemanticTokenDecoder.decode(
            rawData = rawData,
            tokenTypes = listOf("variable"),
            tokenModifiers = emptyList()
        )

        assertThat(tokens).hasSize(1)
        assertThat(tokens.first().line).isEqualTo(0)
        assertThat(tokens.first().startColumn).isEqualTo(0)
    }

    @Test
    fun decode_shouldPreserveUtf16ColumnsAroundSurrogatePairs() {
        val sampleLine = "a😀bc"
        val utf16ColumnAfterEmoji = "a😀".length
        assertThat(utf16ColumnAfterEmoji).isEqualTo(3)

        val tokens = LspSemanticTokenDecoder.decode(
            rawData = listOf(0, utf16ColumnAfterEmoji, 2, 0, 0),
            tokenTypes = listOf("variable"),
            tokenModifiers = emptyList()
        )

        assertThat(tokens).hasSize(1)
        assertThat(tokens.first().line).isEqualTo(0)
        assertThat(tokens.first().startColumn).isEqualTo(utf16ColumnAfterEmoji)
        assertThat(sampleLine.substring(tokens.first().startColumn)).startsWith("bc")
    }
}
