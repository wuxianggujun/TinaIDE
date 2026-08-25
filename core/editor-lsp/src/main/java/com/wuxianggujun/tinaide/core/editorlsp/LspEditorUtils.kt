package com.wuxianggujun.tinaide.core.editorlsp

import java.io.File

/**
 * Small pure helpers shared by LspEditorManager.
 */

internal const val SEMANTIC_PREFETCH_MARGIN_LINES = 80
internal const val SEMANTIC_MAX_PREFETCH_SPAN_LINES = 480
internal const val INLAY_HINT_PREFETCH_MARGIN_LINES = 24
internal const val INLAY_HINT_MAX_PREFETCH_SPAN_LINES = 240

internal fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

internal fun fileNameForLog(file: File): String = file.name.ifBlank { file.absolutePath }

internal fun normalizeVisibleLines(visibleLines: IntRange): IntRange {
    if (visibleLines.isEmpty()) return visibleLines
    val start = visibleLines.first.coerceAtLeast(0)
    val end = visibleLines.last.coerceAtLeast(start)
    return start..end
}

internal fun expandSemanticRequestLines(visibleLines: IntRange): IntRange {
    val normalized = normalizeVisibleLines(visibleLines)
    if (normalized.isEmpty()) return normalized
    val start = (normalized.first - SEMANTIC_PREFETCH_MARGIN_LINES).coerceAtLeast(0)
    val maxEnd = start + SEMANTIC_MAX_PREFETCH_SPAN_LINES
    val targetEnd = normalized.last + SEMANTIC_PREFETCH_MARGIN_LINES
    val end = targetEnd.coerceAtMost(maxEnd).coerceAtLeast(start)
    return start..end
}

internal fun expandInlayHintRequestLines(visibleLines: IntRange): IntRange {
    val normalized = normalizeVisibleLines(visibleLines)
    if (normalized.isEmpty()) return normalized
    val start = (normalized.first - INLAY_HINT_PREFETCH_MARGIN_LINES).coerceAtLeast(0)
    val maxEnd = start + INLAY_HINT_MAX_PREFETCH_SPAN_LINES
    val targetEnd = normalized.last + INLAY_HINT_PREFETCH_MARGIN_LINES
    val end = targetEnd.coerceAtMost(maxEnd).coerceAtLeast(start)
    return start..end
}

internal fun IntRange.containsRange(other: IntRange): Boolean {
    if (this.isEmpty() || other.isEmpty()) return false
    return this.first <= other.first && this.last >= other.last
}

internal fun List<SemanticToken>.filterToVisibleLines(visibleLines: IntRange): List<SemanticToken> {
    if (isEmpty() || visibleLines.isEmpty()) return emptyList()
    return asSequence()
        .filter { token -> token.line in visibleLines && token.length > 0 }
        .toList()
}

internal fun languageIdForFile(file: File): String = file.resolveLspLanguageId()
