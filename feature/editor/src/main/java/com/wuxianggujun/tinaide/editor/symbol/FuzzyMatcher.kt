package com.wuxianggujun.tinaide.editor.symbol

import java.util.Locale
import java.util.PriorityQueue

/**
 * 模糊匹配工具类
 *
 * 支持多种匹配模式：
 * 1. 前缀匹配（最高优先级）
 * 2. 驼峰匹配（如 "gAL" 匹配 "getArrayLength"）
 * 3. 子序列匹配（如 "gal" 匹配 "getArrayLength"）
 * 4. 包含匹配（如 "array" 匹配 "getArrayLength"）
 *
 * 匹配结果带有分数，用于排序。
 */
object FuzzyMatcher {

    /**
     * 匹配结果
     */
    data class MatchResult(
        val matched: Boolean,
        val score: Int, // 分数越高越好
        val matchedIndices: List<Int> = emptyList(), // 匹配的字符索引（用于高亮）
    ) {
        companion object {
            val NO_MATCH = MatchResult(matched = false, score = 0)
        }
    }

    /**
     * 匹配模式
     */
    enum class MatchMode {
        PREFIX, // 前缀匹配
        CAMEL_CASE, // 驼峰匹配
        SUBSEQUENCE, // 子序列匹配
        CONTAINS, // 包含匹配
    }

    // 分数权重
    private const val SCORE_EXACT_MATCH = 1000
    private const val SCORE_PREFIX_MATCH = 500
    private const val SCORE_CAMEL_CASE_MATCH = 300
    private const val SCORE_SUBSEQUENCE_MATCH = 100
    private const val SCORE_CONTAINS_MATCH = 50
    private const val SCORE_CONSECUTIVE_BONUS = 10
    private const val SCORE_START_BONUS = 20

    /**
     * 执行模糊匹配
     *
     * @param pattern 搜索模式
     * @param text 目标文本
     * @return 匹配结果
     */
    fun match(pattern: String, text: String): MatchResult {
        if (pattern.isEmpty()) return MatchResult(matched = true, score = 0)
        if (text.isEmpty()) return MatchResult.NO_MATCH

        val patternLower = pattern.lowercase(Locale.ROOT)
        val textLower = text.lowercase(Locale.ROOT)

        // 1. 精确匹配
        if (text == pattern) {
            return MatchResult(
                matched = true,
                score = SCORE_EXACT_MATCH,
                matchedIndices = text.indices.toList()
            )
        }

        // 2. 前缀匹配
        if (textLower.startsWith(patternLower)) {
            return MatchResult(
                matched = true,
                score = SCORE_PREFIX_MATCH + (pattern.length * 2),
                matchedIndices = (0 until pattern.length).toList()
            )
        }

        // 3. 驼峰匹配
        val camelResult = matchCamelCase(pattern, text)
        if (camelResult.matched) {
            return camelResult
        }

        // 4. 子序列匹配
        val subsequenceResult = matchSubsequence(patternLower, textLower)
        if (subsequenceResult.matched) {
            return subsequenceResult
        }

        // 5. 包含匹配
        val containsIndex = textLower.indexOf(patternLower)
        if (containsIndex >= 0) {
            return MatchResult(
                matched = true,
                score = SCORE_CONTAINS_MATCH - containsIndex, // 越靠前分数越高
                matchedIndices = (containsIndex until containsIndex + pattern.length).toList()
            )
        }

        return MatchResult.NO_MATCH
    }

    /**
     * 驼峰匹配
     *
     * 例如：
     * - "gAL" 匹配 "getArrayLength"
     * - "GAL" 匹配 "GetArrayLength"
     */
    private fun matchCamelCase(pattern: String, text: String): MatchResult {
        val boundaries = findCamelCaseBoundaries(text)
        if (boundaries.isEmpty()) return MatchResult.NO_MATCH

        val patternLower = pattern.lowercase(Locale.ROOT)
        val textLower = text.lowercase(Locale.ROOT)

        var patternIdx = 0
        val matchedIndices = mutableListOf<Int>()
        var consecutiveCount = 0

        for (boundaryIdx in boundaries) {
            if (patternIdx >= patternLower.length) break

            // 从边界位置开始匹配
            var textIdx = boundaryIdx
            while (patternIdx < patternLower.length && textIdx < textLower.length) {
                if (patternLower[patternIdx] == textLower[textIdx]) {
                    matchedIndices.add(textIdx)
                    patternIdx++
                    textIdx++
                    consecutiveCount++
                } else {
                    break
                }
            }
        }

        return if (patternIdx == patternLower.length) {
            val score = SCORE_CAMEL_CASE_MATCH +
                (consecutiveCount * SCORE_CONSECUTIVE_BONUS) +
                (if (matchedIndices.firstOrNull() == 0) SCORE_START_BONUS else 0)
            MatchResult(matched = true, score = score, matchedIndices = matchedIndices)
        } else {
            MatchResult.NO_MATCH
        }
    }

    /**
     * 子序列匹配
     *
     * 例如：
     * - "gal" 匹配 "getArrayLength"
     */
    private fun matchSubsequence(patternLower: String, textLower: String): MatchResult {
        var patternIdx = 0
        val matchedIndices = mutableListOf<Int>()
        var consecutiveCount = 0
        var lastMatchIdx = -2

        for (textIdx in textLower.indices) {
            if (patternIdx >= patternLower.length) break

            if (patternLower[patternIdx] == textLower[textIdx]) {
                matchedIndices.add(textIdx)
                if (textIdx == lastMatchIdx + 1) {
                    consecutiveCount++
                }
                lastMatchIdx = textIdx
                patternIdx++
            }
        }

        return if (patternIdx == patternLower.length) {
            val score = SCORE_SUBSEQUENCE_MATCH +
                (consecutiveCount * SCORE_CONSECUTIVE_BONUS) +
                (if (matchedIndices.firstOrNull() == 0) SCORE_START_BONUS else 0) -
                (matchedIndices.lastOrNull()?.minus(matchedIndices.firstOrNull() ?: 0) ?: 0) // 跨度越小分数越高
            MatchResult(matched = true, score = score, matchedIndices = matchedIndices)
        } else {
            MatchResult.NO_MATCH
        }
    }

    /**
     * 查找驼峰边界位置
     *
     * 例如 "getArrayLength" 返回 [0, 3, 8]（g, A, L 的位置）
     */
    private fun findCamelCaseBoundaries(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()

        val boundaries = mutableListOf<Int>()
        boundaries.add(0) // 第一个字符总是边界

        for (i in 1 until text.length) {
            val prev = text[i - 1]
            val curr = text[i]

            // 大写字母是边界
            if (curr.isUpperCase() && !prev.isUpperCase()) {
                boundaries.add(i)
            }
            // 下划线后的字符是边界
            else if (prev == '_' && curr != '_') {
                boundaries.add(i)
            }
            // 数字后的字母是边界
            else if (prev.isDigit() && curr.isLetter()) {
                boundaries.add(i)
            }
        }

        return boundaries
    }

    /**
     * 批量匹配并排序
     *
     * @param pattern 搜索模式
     * @param items 待匹配项列表
     * @param getText 获取文本的函数
     * @param limit 返回数量限制
     * @return 匹配结果列表（按分数降序）
     */
    fun <T> matchAndSort(
        pattern: String,
        items: Iterable<T>,
        getText: (T) -> String,
        limit: Int = Int.MAX_VALUE,
    ): List<Pair<T, MatchResult>> {
        if (limit <= 0) return emptyList()
        if (pattern.isEmpty()) {
            return items.take(limit).map { it to MatchResult(matched = true, score = 0) }
        }

        // Keep only the best K matches. Earlier entries win ties, as in a stable sort.
        val worstFirst = compareBy<IndexedValue<Pair<T, MatchResult>>> { it.value.second.score }
            .thenByDescending { it.index }
        val best = PriorityQueue(minOf(limit, 64), worstFirst)
        items.forEachIndexed { index, item ->
            val result = match(pattern, getText(item))
            if (result.matched) {
                val candidate = IndexedValue(index, item to result)
                if (best.size < limit) {
                    best.add(candidate)
                } else if (worstFirst.compare(candidate, best.peek()) > 0) {
                    best.poll()
                    best.add(candidate)
                }
            }
        }
        return best.sortedWith(worstFirst.reversed()).map { it.value }
    }
}
