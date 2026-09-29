package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal data class SignatureParameterRange(
    val start: Int,
    val end: Int
)

internal fun resolveSignatureParameterRanges(
    signature: String
): List<SignatureParameterRange> {
    val openParen = signature.indexOf('(')
    val closeParen = signature.lastIndexOf(')')
    if (openParen < 0 || closeParen <= openParen) return emptyList()

    val result = mutableListOf<SignatureParameterRange>()
    var segmentStart = openParen + 1
    var roundDepth = 0
    var squareDepth = 0
    var braceDepth = 0
    var angleDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false

    fun appendRange(endExclusive: Int) {
        var start = segmentStart
        var end = endExclusive
        while (start < end && signature[start].isWhitespace()) start++
        while (end > start && signature[end - 1].isWhitespace()) end--
        if (start < end) {
            result += SignatureParameterRange(start = start, end = end)
        }
    }

    var index = openParen + 1
    while (index < closeParen) {
        val current = signature[index]
        if (escaped) {
            escaped = false
            index++
            continue
        }
        if (current == '\\' && (inSingleQuote || inDoubleQuote)) {
            escaped = true
            index++
            continue
        }
        if (inSingleQuote) {
            if (current == '\'') inSingleQuote = false
            index++
            continue
        }
        if (inDoubleQuote) {
            if (current == '"') inDoubleQuote = false
            index++
            continue
        }

        when (current) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '(' -> roundDepth++
            ')' -> if (roundDepth > 0) roundDepth--
            '[' -> squareDepth++
            ']' -> if (squareDepth > 0) squareDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            '<' -> angleDepth++
            '>' -> if (angleDepth > 0) angleDepth--
            ',' -> if (
                roundDepth == 0 &&
                squareDepth == 0 &&
                braceDepth == 0 &&
                angleDepth == 0
            ) {
                appendRange(index)
                segmentStart = index + 1
            }
        }
        index++
    }

    appendRange(closeParen)
    return result
}

internal fun buildSignatureHelpAnnotatedString(
    signature: String,
    activeParameter: Int,
    activeParameterStyle: SpanStyle
): AnnotatedString {
    val activeRange = resolveSignatureParameterRanges(signature)
        .getOrNull(activeParameter.coerceAtLeast(0))
        ?: return AnnotatedString(signature)

    return buildAnnotatedString {
        append(signature)
        addStyle(
            style = activeParameterStyle,
            start = activeRange.start,
            end = activeRange.end
        )
    }
}

internal fun resolveSignatureActiveParameterPreview(
    signature: String,
    activeParameter: Int
): String? {
    val activeRange = resolveSignatureParameterRanges(signature)
        .getOrNull(activeParameter.coerceAtLeast(0))
        ?: return null
    return signature.substring(activeRange.start, activeRange.end)
}
