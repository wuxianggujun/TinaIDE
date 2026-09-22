package com.wuxianggujun.tinaide.core.treesitter

internal object TreeSitterPrewarmPlan {
    private const val OPEN_CHUNK_LINES = 2048
    private const val EDIT_CHUNK_LINES = 256
    private const val EDIT_MARGIN_LINES = 128

    fun ranges(lineCount: Int, viewportHintLine: Int, fullDocument: Boolean): List<IntRange> {
        if (lineCount <= 0) return emptyList()
        val lastDocumentLine = lineCount - 1
        val hint = viewportHintLine.coerceIn(0, lastDocumentLine)
        val firstLine = if (fullDocument) 0 else (hint - EDIT_MARGIN_LINES).coerceAtLeast(0)
        val lastLine = if (fullDocument) {
            lastDocumentLine
        } else {
            (hint.toLong() + EDIT_MARGIN_LINES).coerceAtMost(lastDocumentLine.toLong()).toInt()
        }
        val chunkLines = if (fullDocument) OPEN_CHUNK_LINES else EDIT_CHUNK_LINES
        val firstChunk = firstLine / chunkLines
        val lastChunk = lastLine / chunkLines
        val centerChunk = hint / chunkLines
        val result = ArrayList<IntRange>(lastChunk - firstChunk + 1)

        fun addChunk(chunk: Int) {
            val start = (chunk * chunkLines).coerceAtLeast(firstLine)
            val end = ((chunk.toLong() + 1L) * chunkLines - 1L).coerceAtMost(lastLine.toLong()).toInt()
            result.add(start..end)
        }

        addChunk(centerChunk)
        var distance = 1
        while (result.size < lastChunk - firstChunk + 1) {
            val before = centerChunk - distance
            val after = centerChunk + distance
            if (before >= firstChunk) addChunk(before)
            if (after <= lastChunk) addChunk(after)
            distance++
        }
        return result
    }
}
