package com.wuxianggujun.tinaide.ui.compose.components

import com.wuxianggujun.tinaide.core.textengine.LineIndex
import com.wuxianggujun.tinaide.core.treesitter.HighlightSpan
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

/** A code block owns an immutable document; cache misses arrive through the push callback. */
internal fun codeBlockHighlightUpdates(code: String, highlighter: SyntaxHighlighter): Flow<List<HighlightSpan>> =
    callbackFlow<Unit> {
        highlighter.setOnStateUpdated { trySend(Unit) }
        try {
            highlighter.openDocument(code)
            trySend(Unit)
            awaitClose()
        } finally {
            highlighter.setOnStateUpdated(null)
        }
    }.conflate().map { collectCodeBlockHighlights(code, highlighter) }

internal fun collectCodeBlockHighlights(code: String, highlighter: SyntaxHighlighter): List<HighlightSpan> =
    LineIndex().use { index ->
        // Reuse the editor's LF/UTF-16 indexing; lineSequence strips CRLF and also splits bare CR.
        index.rebuild(code)
        buildList {
            for (line in 0 until index.lineCount) {
                val offset = index.getLineStart(line)
                val length = index.getLineEnd(line, code.length) - offset
                highlighter.getLineSegments(line).forEach { segment ->
                    val start = offset + segment.startColumn.coerceIn(0, length)
                    val end = offset + segment.endColumn.coerceIn(start - offset, length)
                    if (end > start) add(HighlightSpan(start, end, segment.type, segment.priority))
                }
            }
        }
    }
