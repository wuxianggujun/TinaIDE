package com.wuxianggujun.tinaide.core.textengine

/**
 * Signature-help text scan helpers for TextScanKernel.
 */

internal enum class SignatureHelpContextKind {
    CallParen,
    ControlParen,
    OtherParen,
    TrailingLambda,
    OtherBrace
}

internal data class SignatureHelpDelimiter(
    val kind: SignatureHelpContextKind
)

internal data class KotlinOpenBracketRecord(
    val offset: Int,
    val bracket: Char,
    val depth: Int
)

internal data class KotlinGuideOpenBracketRecord(
    val line: Int,
    val column: Int,
    val bracket: Char,
    val depth: Int
)

internal data class KotlinSnapshotOpenBracketRecord(
    val offset: Int,
    val line: Int,
    val column: Int,
    val bracket: Char,
    val depth: Int
)

internal enum class SignatureHelpParenKind {
    Call,
    Control,
    Other
}

internal sealed interface SignatureHelpScanToken {
    data class Identifier(val text: String) : SignatureHelpScanToken
    data class Symbol(val value: Char) : SignatureHelpScanToken
    data class ParenClose(val kind: SignatureHelpParenKind) : SignatureHelpScanToken
}

internal fun findForwardMatchingBracket(
    text: String,
    openOffset: Int,
    openChar: Char
): BracketPairMatchResult? {
    val closeChar = matchingCloseBracket(openChar) ?: return null
    var depth = 0
    for (offset in openOffset until text.length) {
        when (text[offset]) {
            openChar -> depth++
            closeChar -> {
                depth--
                if (depth == 0) {
                    return BracketPairMatchResult(openOffset = openOffset, closeOffset = offset)
                }
            }
        }
    }
    return null
}

internal fun findBackwardMatchingBracket(
    text: String,
    closeOffset: Int,
    closeChar: Char
): BracketPairMatchResult? {
    val openChar = matchingOpenBracket(closeChar) ?: return null
    var depth = 0
    for (offset in closeOffset downTo 0) {
        when (text[offset]) {
            closeChar -> depth++
            openChar -> {
                depth--
                if (depth == 0) {
                    return BracketPairMatchResult(openOffset = offset, closeOffset = closeOffset)
                }
            }
        }
    }
    return null
}

internal fun Char.isOpenBracket(): Boolean = this == '(' || this == '[' || this == '{'

internal fun Char.isCloseBracket(): Boolean = this == ')' || this == ']' || this == '}'

internal fun matchingCloseBracket(ch: Char): Char? = when (ch) {
    '(' -> ')'
    '[' -> ']'
    '{' -> '}'
    else -> null
}

internal fun matchingOpenBracket(ch: Char): Char? = when (ch) {
    ')' -> '('
    ']' -> '['
    '}' -> '{'
    else -> null
}

internal fun resolveSignatureHelpParenKind(
    tokens: List<SignatureHelpScanToken>
): SignatureHelpContextKind = when {
    endsWithSignatureHelpCallableExpression(tokens) -> SignatureHelpContextKind.CallParen
    endsWithSignatureHelpControlKeyword(tokens) -> SignatureHelpContextKind.ControlParen
    else -> SignatureHelpContextKind.OtherParen
}

internal fun endsWithSignatureHelpControlKeyword(
    tokens: List<SignatureHelpScanToken>
): Boolean {
    val lastIdentifier = tokens.lastOrNull() as? SignatureHelpScanToken.Identifier ?: return false
    return lastIdentifier.text in SIGNATURE_HELP_CONTROL_KEYWORDS
}

internal fun startsSignatureHelpTrailingLambda(
    tokens: List<SignatureHelpScanToken>
): Boolean {
    val lastToken = tokens.lastOrNull() ?: return false
    return when (lastToken) {
        is SignatureHelpScanToken.ParenClose -> lastToken.kind == SignatureHelpParenKind.Call
        is SignatureHelpScanToken.Identifier,
        is SignatureHelpScanToken.Symbol -> endsWithSignatureHelpCallableExpression(tokens)
    }
}

internal fun endsWithSignatureHelpCallableExpression(
    tokens: List<SignatureHelpScanToken>
): Boolean {
    if (tokens.isEmpty()) return false
    val index = skipTrailingSignatureHelpTypeArguments(tokens, tokens.lastIndex)
    val token = tokens.getOrNull(index) ?: return false

    return when (token) {
        is SignatureHelpScanToken.ParenClose -> token.kind == SignatureHelpParenKind.Call
        is SignatureHelpScanToken.Identifier -> {
            if (token.text in SIGNATURE_HELP_NON_CALL_TERMINALS) return false
            val chainStart = findSignatureHelpCallChainStart(tokens, index)
            !isSignatureHelpDeclarationContext(tokens, chainStart)
        }
        is SignatureHelpScanToken.Symbol -> false
    }
}

internal fun skipTrailingSignatureHelpTypeArguments(
    tokens: List<SignatureHelpScanToken>,
    startIndex: Int
): Int {
    var index = startIndex
    val closingToken = tokens.getOrNull(index) as? SignatureHelpScanToken.Symbol ?: return index
    if (closingToken.value != '>') return index

    var depth = 0
    while (index >= 0) {
        when (val token = tokens[index]) {
            is SignatureHelpScanToken.Symbol -> when (token.value) {
                '>' -> depth++
                '<' -> {
                    depth--
                    if (depth == 0) {
                        return index - 1
                    }
                }
            }
            else -> Unit
        }
        index--
    }
    return startIndex
}

internal fun findSignatureHelpCallChainStart(
    tokens: List<SignatureHelpScanToken>,
    identifierIndex: Int
): Int {
    var chainStart = identifierIndex
    var cursor = identifierIndex - 1

    while (cursor >= 0) {
        val dotToken = tokens.getOrNull(cursor) as? SignatureHelpScanToken.Symbol ?: break
        if (dotToken.value != '.') break
        cursor--
        val safeCallToken = tokens.getOrNull(cursor) as? SignatureHelpScanToken.Symbol
        if (safeCallToken?.value == '?') {
            cursor--
        }
        cursor = skipTrailingSignatureHelpTypeArguments(tokens, cursor)
        if (tokens.getOrNull(cursor) !is SignatureHelpScanToken.Identifier) {
            break
        }
        chainStart = cursor
        cursor--
    }

    return chainStart
}

internal fun isSignatureHelpDeclarationContext(
    tokens: List<SignatureHelpScanToken>,
    chainStart: Int
): Boolean {
    val prefix = tokens.getOrNull(chainStart - 1)
    if (prefix is SignatureHelpScanToken.Identifier &&
        prefix.text in SIGNATURE_HELP_DECLARATION_KEYWORDS
    ) {
        return true
    }

    val colon = prefix as? SignatureHelpScanToken.Symbol
    if (colon?.value == ':') {
        val owner = tokens.getOrNull(chainStart - 2) as? SignatureHelpScanToken.Identifier
        if (owner?.text in setOf("class", "interface", "object")) {
            return true
        }
    }

    return false
}

internal fun popLastSignatureHelpParen(
    stack: MutableList<SignatureHelpDelimiter>
): SignatureHelpParenKind? {
    for (index in stack.lastIndex downTo 0) {
        when (val kind = stack[index].kind) {
            SignatureHelpContextKind.CallParen -> {
                stack.removeAt(index)
                return SignatureHelpParenKind.Call
            }
            SignatureHelpContextKind.ControlParen -> {
                stack.removeAt(index)
                return SignatureHelpParenKind.Control
            }
            SignatureHelpContextKind.OtherParen -> {
                stack.removeAt(index)
                return SignatureHelpParenKind.Other
            }
            SignatureHelpContextKind.TrailingLambda,
            SignatureHelpContextKind.OtherBrace -> Unit
        }
    }
    return null
}

internal fun popLastSignatureHelpBrace(
    stack: MutableList<SignatureHelpDelimiter>
) {
    for (index in stack.lastIndex downTo 0) {
        when (stack[index].kind) {
            SignatureHelpContextKind.TrailingLambda,
            SignatureHelpContextKind.OtherBrace -> {
                stack.removeAt(index)
                return
            }
            SignatureHelpContextKind.CallParen,
            SignatureHelpContextKind.ControlParen,
            SignatureHelpContextKind.OtherParen -> Unit
        }
    }
}

internal fun Char.isSignatureHelpIdentifierChar(): Boolean = isLetterOrDigit() || this == '_'
