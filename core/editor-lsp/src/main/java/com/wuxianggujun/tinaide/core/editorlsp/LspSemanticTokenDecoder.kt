package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken

object LspSemanticTokenDecoder {
    private const val TOKEN_STEP = 5
    private const val FALLBACK_TOKEN_TYPE = "variable"

    fun decode(
        rawData: List<Int>,
        tokenTypes: List<String>,
        tokenModifiers: List<String>
    ): List<SemanticToken> {
        if (rawData.size < TOKEN_STEP) return emptyList()

        var line = 0
        var startColumn = 0
        val out = ArrayList<SemanticToken>(rawData.size / TOKEN_STEP)
        var index = 0
        while (index + (TOKEN_STEP - 1) < rawData.size) {
            val deltaLine = rawData[index]
            val deltaStart = rawData[index + 1]
            val length = rawData[index + 2]
            val tokenTypeIndex = rawData[index + 3]
            val modifierBits = rawData[index + 4]

            if (deltaLine == 0) {
                startColumn += deltaStart
            } else {
                line += deltaLine
                startColumn = deltaStart
            }

            if (length > 0) {
                out.add(
                    SemanticToken(
                        line = line,
                        startColumn = startColumn.coerceAtLeast(0),
                        length = length,
                        tokenType = tokenTypes.getOrElse(tokenTypeIndex) { FALLBACK_TOKEN_TYPE },
                        tokenModifiers = decodeModifierBits(modifierBits, tokenModifiers)
                    )
                )
            }
            index += TOKEN_STEP
        }
        return out
    }

    private fun decodeModifierBits(
        bitset: Int,
        tokenModifiers: List<String>
    ): Set<String> {
        if (bitset == 0 || tokenModifiers.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        var bits = bitset
        var bitIndex = 0
        while (bits != 0) {
            if ((bits and 1) != 0) {
                tokenModifiers.getOrNull(bitIndex)?.let { out.add(it) }
            }
            bits = bits ushr 1
            bitIndex++
        }
        return out
    }
}
