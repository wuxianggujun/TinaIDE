package com.wuxianggujun.tinaide.core.treesitter

import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSQuery
import timber.log.Timber

/**
 * Tree-sitter Query 编译器（带容错恢复）。
 *
 * 策略：
 * - 首次编译失败后，根据 errorOffset 定位到对应的"顶层 pattern"，将其移除并重试；
 * - 直到编译成功或达到最大恢复次数；
 */
internal object TreeSitterQueryCompiler {
    private const val TAG = "TreeSitter"
    private const val MAX_RECOVERY_PASSES = 28

    fun compileWithRecovery(
        language: TSLanguage,
        queryText: String,
        languageName: String,
        queryName: String
    ): TSQuery? {
        if (queryText.isBlank()) return null

        var current = queryText
        var dropped = 0
        repeat(MAX_RECOVERY_PASSES + 1) { pass ->
            val query = TSQuery.create(language, current)
            if (query.canAccess()) {
                if (dropped > 0) {
                    Timber.tag(TAG).w(
                        "Recovered Tree-sitter query: language=%s query=%s dropped=%d",
                        languageName,
                        queryName,
                        dropped
                    )
                }
                return query
            }

            val errorType = query.errorType
            val errorOffset = query.errorOffset
            Timber.tag(TAG).w(
                "Invalid Tree-sitter query: language=%s query=%s errorType=%s offset=%d pass=%d",
                languageName,
                queryName,
                errorType,
                errorOffset,
                pass
            )
            runCatching { query.close() }

            val span = findTopLevelPatternSpanAtOffset(current, errorOffset) ?: return null
            if (span.endExclusive <= span.startInclusive) return null
            current = buildString(current.length) {
                append(current, 0, span.startInclusive)
                append('\n')
                append(current, span.endExclusive, current.length)
            }
            dropped += 1
        }

        Timber.tag(TAG).w(
            "Failed to recover Tree-sitter query: language=%s query=%s (passes=%d)",
            languageName,
            queryName,
            MAX_RECOVERY_PASSES
        )
        return null
    }

    private data class TopLevelSpan(
        val startInclusive: Int,
        val endExclusive: Int
    )

    private fun findTopLevelPatternSpanAtOffset(
        queryText: String,
        errorOffset: Int
    ): TopLevelSpan? {
        val spans = findTopLevelPatternSpans(queryText)
        if (spans.isEmpty()) return null

        val safeOffset = errorOffset.coerceIn(0, (queryText.length - 1).coerceAtLeast(0))
        return spans.firstOrNull { safeOffset in it.startInclusive until it.endExclusive }
            ?: spans.lastOrNull { it.startInclusive <= safeOffset }
    }

    private fun findTopLevelPatternSpans(queryText: String): List<TopLevelSpan> {
        val starts = ArrayList<Int>(128)

        var inString = false
        var inComment = false
        var parenDepth = 0
        var bracketDepth = 0

        var index = 0
        while (index < queryText.length) {
            val ch = queryText[index]

            if (inComment) {
                if (ch == '\n') inComment = false
                index += 1
                continue
            }

            if (inString) {
                if (ch == '\\') {
                    index = (index + 2).coerceAtMost(queryText.length)
                    continue
                }
                if (ch == '"') inString = false
                index += 1
                continue
            }

            when (ch) {
                ';' -> {
                    inComment = true
                    index += 1
                    continue
                }
                '"' -> {
                    inString = true
                    index += 1
                    continue
                }
            }

            if (parenDepth == 0 && bracketDepth == 0 && (ch == '(' || ch == '[')) {
                starts.add(index)
            }

            when (ch) {
                '(' -> parenDepth += 1
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth += 1
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            }

            index += 1
        }

        if (starts.isEmpty()) return emptyList()
        val spans = ArrayList<TopLevelSpan>(starts.size)
        for (i in starts.indices) {
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else queryText.length
            spans.add(TopLevelSpan(startInclusive = start, endExclusive = end))
        }
        return spans
    }
}
