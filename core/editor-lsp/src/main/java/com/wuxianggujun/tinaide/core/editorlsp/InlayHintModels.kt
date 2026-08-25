package com.wuxianggujun.tinaide.core.editorlsp

import org.eclipse.lsp4j.InlayHint as LspInlayHint
import org.eclipse.lsp4j.InlayHintKind as LspInlayHintKind

data class InlayHint(
    val line: Int,
    val column: Int,
    val label: String,
    val kind: InlayHintKind,
    val paddingLeft: Boolean,
    val paddingRight: Boolean,
)

enum class InlayHintKind {
    PARAMETER,
    TYPE,
    OTHER,
}

sealed interface InlayHintsRequestResult {
    data class Success(val hints: List<InlayHint>) : InlayHintsRequestResult
    data object Unavailable : InlayHintsRequestResult
}

internal fun LspInlayHint.toEditorInlayHintOrNull(): InlayHint? {
    val hintPosition = position ?: return null
    if (hintPosition.line < 0 || hintPosition.character < 0) return null
    val normalizedLabel = when {
        label?.isLeft == true -> label.left
        label?.isRight == true -> label.right.orEmpty().joinToString(separator = "") { part ->
            part.value.orEmpty()
        }
        else -> null
    }?.takeIf { it.isNotBlank() } ?: return null

    return InlayHint(
        line = hintPosition.line,
        column = hintPosition.character,
        label = normalizedLabel,
        kind = when (kind) {
            LspInlayHintKind.Parameter -> InlayHintKind.PARAMETER
            LspInlayHintKind.Type -> InlayHintKind.TYPE
            else -> InlayHintKind.OTHER
        },
        paddingLeft = paddingLeft == true,
        paddingRight = paddingRight == true,
    )
}

internal fun List<InlayHint>.filterToVisibleLines(visibleLines: IntRange): List<InlayHint> {
    if (isEmpty() || visibleLines.isEmpty()) return emptyList()
    return filter { hint -> hint.line in visibleLines }
}
