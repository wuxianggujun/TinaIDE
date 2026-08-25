package com.wuxianggujun.tinaide.core.editorlsp

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkedString
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * LSP hover markdown conversion helpers.
 */

@Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")
private typealias ProtocolMarkedString = MarkedString

internal fun Hover.toMarkdown(): String? {
    val payload = contents ?: return null
    return when {
        payload.isRight -> payload.right?.value?.trim().takeIf { !it.isNullOrBlank() }
        else -> payload.left.orEmpty()
            .mapNotNull { it.toMarkdownSection() }
            .joinToString("\n\n")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}

@Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")
private fun Either<String, ProtocolMarkedString>.toMarkdownSection(): String? = if (isLeft) {
    left?.trim().takeIf { !it.isNullOrBlank() }
} else {
    right?.toMarkdownSection()
}

@Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")
private fun ProtocolMarkedString.toMarkdownSection(): String? {
    val body = value?.trim().orEmpty()
    if (body.isBlank()) return null
    val safeLanguage = language?.trim().orEmpty()
    return if (safeLanguage.isBlank()) {
        body
    } else {
        "```$safeLanguage\n$body\n```"
    }
}
