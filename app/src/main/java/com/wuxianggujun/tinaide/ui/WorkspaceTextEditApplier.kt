package com.wuxianggujun.tinaide.ui

import org.eclipse.lsp4j.TextEdit

internal object WorkspaceTextEditApplier {
    private data class ResolvedEdit(
        val startOffset: Int,
        val endOffset: Int,
        val newText: String
    )

    fun apply(original: String, edits: List<TextEdit>): String? {
        val lineStarts = computeLineStarts(original)
        val sortedEdits = edits.sortedWith(
            compareByDescending<TextEdit> { it.range.start.line }
                .thenByDescending { it.range.start.character }
                .thenByDescending { it.range.end.line }
                .thenByDescending { it.range.end.character }
        )
        var nextStartOffset = original.length
        val resolved = buildList(sortedEdits.size) {
            sortedEdits.forEach { edit ->
                val startOffset = positionToOffset(
                    text = original,
                    lineStarts = lineStarts,
                    line = edit.range.start.line,
                    column = edit.range.start.character
                ) ?: return null
                val endOffset = positionToOffset(
                    text = original,
                    lineStarts = lineStarts,
                    line = edit.range.end.line,
                    column = edit.range.end.character
                ) ?: return null
                if (endOffset < startOffset || endOffset > nextStartOffset) return null
                add(ResolvedEdit(startOffset, endOffset, edit.newText.orEmpty()))
                nextStartOffset = startOffset
            }
        }

        var updated = original
        resolved.forEach { edit ->
            updated = updated.replaceRange(edit.startOffset, edit.endOffset, edit.newText)
        }
        return updated
    }

    private fun positionToOffset(
        text: String,
        lineStarts: IntArray,
        line: Int,
        column: Int
    ): Int? {
        if (line !in lineStarts.indices || column < 0) return null
        val lineStart = lineStarts[line]
        var lineEnd = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length
        if (lineEnd > lineStart && text[lineEnd - 1] == '\r') {
            lineEnd--
        }
        if (column > lineEnd - lineStart) return null
        return lineStart + column
    }

    private fun computeLineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        text.forEachIndexed { index, character ->
            if (character == '\n') starts.add(index + 1)
        }
        return starts.toIntArray()
    }
}
