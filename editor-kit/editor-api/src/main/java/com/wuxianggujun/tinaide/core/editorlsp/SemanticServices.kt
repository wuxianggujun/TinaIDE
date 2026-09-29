package com.wuxianggujun.tinaide.core.editorlsp

data class SemanticToken(
    val line: Int,
    val startColumn: Int,
    val length: Int,
    val tokenType: String,
    val tokenModifiers: Set<String> = emptySet()
)

data class SignatureHelpResult(
    val signatures: List<String>,
    val activeSignature: Int,
    val activeParameter: Int
)
