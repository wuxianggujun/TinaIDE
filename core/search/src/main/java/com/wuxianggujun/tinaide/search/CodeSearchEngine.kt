package com.wuxianggujun.tinaide.search

/**
 * 代码搜索引擎
 *
 * 通过 TextContentProvider 抽象文本访问，避免直接依赖 CodeEditor UI 组件。
 */
class CodeSearchEngine(
    private val contentProvider: TextContentProvider,
    private val maxResults: Int = Int.MAX_VALUE,
) : SearchEngine {
    init { require(maxResults > 0) }

    override fun search(query: String, options: SearchOptions): List<SearchResult> {
        if (query.isEmpty()) return emptyList()

        val regex = if (options.useRegex) {
            try {
                if (options.caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                return emptyList()
            }
        } else {
            null
        }

        val matches = mutableListOf<SearchResult>()
        val content = contentProvider.getText()

        if (regex != null) {
            regex.findAll(content).forEach { match ->
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                if (endIndex <= startIndex) return@forEach
                if (options.wholeWord && !isWholeWord(content, startIndex, endIndex)) return@forEach
                matches.add(CodeSearchResult(buildSearchRange(startIndex, endIndex)))
                if (matches.size >= maxResults) return matches
            }
            return matches
        }

        var startIndex = 0

        while (true) {
            // Keep offsets in the original UTF-16 text (lowercase can change its length).
            val foundIndex = content.indexOf(query, startIndex, ignoreCase = !options.caseSensitive)
            if (foundIndex < 0) break

            val endIndex = foundIndex + query.length
            if (!options.wholeWord || isWholeWord(content, foundIndex, endIndex)) {
                matches.add(CodeSearchResult(buildSearchRange(foundIndex, endIndex)))
                if (matches.size >= maxResults) break
            }
            startIndex = foundIndex + 1
        }

        return matches
    }

    private fun buildSearchRange(startIndex: Int, endIndex: Int): SearchRange {
        val startPos = contentProvider.getPosition(startIndex)
        val endPos = contentProvider.getPosition(endIndex)
        return SearchRange(
            start = SearchPosition(
                line = startPos.line,
                column = startPos.column,
                index = startIndex
            ),
            end = SearchPosition(
                line = endPos.line,
                column = endPos.column,
                index = endIndex
            )
        )
    }

    private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
        if (start > 0) {
            val before = text[start - 1]
            if (before.isLetterOrDigit() || before == '_') return false
        }
        if (end < text.length) {
            val after = text[end]
            if (after.isLetterOrDigit() || after == '_') return false
        }
        return true
    }
}
