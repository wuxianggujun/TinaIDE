package com.wuxianggujun.tinaide.editor.symbol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FuzzyMatcherTest {
    @Test
    fun boundedSearch_keepsBestMatchesAndStableTieOrder() {
        val names = (0 until 500).map { "run_${it.toString().padStart(3, '0')}" } + listOf("run", "Run", "other")
        val result = FuzzyMatcher.matchAndSort("run", names, { it }, 4)
        assertThat(result.map { it.first }).containsExactly("run", "run_000", "run_001", "run_002").inOrder()
    }

    @Test
    fun boundedSearch_matchesFullRankingForDifferentPatterns() {
        val names = listOf("read", "readFile", "ReadFile", "renderFrame", "remote_file", "FileReader", "other")
        for (pattern in listOf("r", "rf", "read", "file", "absent")) {
            val expected = names.map { it to FuzzyMatcher.match(pattern, it) }
                .filter { it.second.matched }.sortedByDescending { it.second.score }.take(3)
            assertThat(FuzzyMatcher.matchAndSort(pattern, names, { it }, 3)).containsExactlyElementsIn(expected).inOrder()
        }
    }

    @Test
    fun emptyPatternAndZeroLimit_areBounded() {
        assertThat(FuzzyMatcher.matchAndSort("", listOf("a", "b", "c"), { it }, 2).map { it.first })
            .containsExactly("a", "b").inOrder()
        assertThat(FuzzyMatcher.matchAndSort("a", listOf("a"), { it }, 0)).isEmpty()
    }
}
