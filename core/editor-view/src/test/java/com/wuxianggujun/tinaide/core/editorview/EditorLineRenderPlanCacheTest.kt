package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.treesitter.HighlightLineSegment
import com.wuxianggujun.tinaide.core.treesitter.HighlightType
import org.junit.Test

class EditorLineRenderPlanCacheTest {
    private val colors = EditorColorScheme.builtinGray().syntax
    private val syntax = listOf(HighlightLineSegment(2, 6, HighlightType.KEYWORD))
    private val noRainbow = IntArray(0)

    @Test
    fun stableFramesAndEquivalentHighlightSnapshots_buildOncePerLine() {
        val cache = EditorLineRenderPlanCache()
        val first = resolve(cache)
        repeat(120) {
            assertThat(resolve(cache, syntax = syntax.map { it.copy() })).isSameInstanceAs(first)
        }
        assertThat(cache.stats().builds).isEqualTo(1)
        assertThat(cache.stats().hits).isEqualTo(120)
    }

    @Test
    fun textSyntaxSemanticAndThemeChanges_rebuildPlan() {
        val cache = EditorLineRenderPlanCache()
        val first = resolve(cache)
        val textChanged = resolve(cache, text = "ABCDEFGHIJ")
        assertThat(textChanged).isNotSameInstanceAs(first)
        val syntaxChanged = resolve(cache, text = "ABCDEFGHIJ", syntax = emptyList())
        assertThat(syntaxChanged).isNotSameInstanceAs(textChanged)
        val semantic = listOf(LineSemanticSegment(1, 4, SemanticTokenType.FUNCTION, emptySet()))
        val semanticChanged = resolve(cache, text = "ABCDEFGHIJ", syntax = emptyList(), semantic = semantic)
        assertThat(semanticChanged).isNotSameInstanceAs(syntaxChanged)
        assertThat(clippedRuns(semanticChanged, 1, 4)).containsExactly(TextRenderRun(1, 4, colors.function.toArgb()))
        val deprecated = resolve(cache, text = "ABCDEFGHIJ", syntax = emptyList(),
            semantic = semantic.map { it.copy(tokenModifiers = setOf(SemanticTokenModifier.DEPRECATED)) })
        assertThat(clippedRuns(deprecated, 1, 4)).containsExactly(TextRenderRun(1, 4, colors.deprecated.toArgb()))
        val themeChanged = cache.getOrBuild(0, "ABCDEFGHIJ", emptyList(), emptyList(), emptyList(),
            colors.copy(defaultText = Color.Red), noRainbow)
        assertThat(clippedRuns(themeChanged, 0, 10)).containsExactly(TextRenderRun(0, 10, Color.Red.toArgb()))
    }

    @Test
    fun asynchronousHighlightArrival_doesNotReuseEmptyPlan() {
        val cache = EditorLineRenderPlanCache()
        val empty = resolve(cache, syntax = emptyList())
        val ready = resolve(cache)
        assertThat(ready).isNotSameInstanceAs(empty)
        assertThat(clippedRuns(ready, 2, 6)).containsExactly(TextRenderRun(2, 6, colors.keyword.toArgb()))
    }

    @Test
    fun bracketDepthPaletteAndDisable_updateColorsWithoutTextEdits() {
        val cache = EditorLineRenderPlanCache()
        val palette = intArrayOf(Color.Red.toArgb(), Color.Green.toArgb())
        val first = cache.getOrBuild(0, "()", emptyList(), emptyList(),
            listOf(RainbowBracketComputer.BracketInfo(0, 0, true)), colors, palette)
        val nested = cache.getOrBuild(0, "()", emptyList(), emptyList(),
            listOf(RainbowBracketComputer.BracketInfo(0, 1, true)), colors, palette)
        assertThat(clippedRuns(first, 0, 1)).containsExactly(TextRenderRun(0, 1, palette[0]))
        assertThat(clippedRuns(nested, 0, 1)).containsExactly(TextRenderRun(0, 1, palette[1]))
        val recolored = cache.getOrBuild(0, "()", emptyList(), emptyList(),
            listOf(RainbowBracketComputer.BracketInfo(0, 1, true)), colors, intArrayOf(1, 2))
        assertThat(clippedRuns(recolored, 0, 1)).containsExactly(TextRenderRun(0, 1, 2))
        val disabled = cache.getOrBuild(0, "()", emptyList(), emptyList(), emptyList(), colors, noRainbow)
        assertThat(clippedRuns(disabled, 0, 2)).containsExactly(TextRenderRun(0, 2, colors.defaultText.toArgb()))
    }

    @Test
    fun wrappedSlices_matchUncachedPlannerAndKeepCommentPriority() {
        val cache = EditorLineRenderPlanCache()
        val syntax = listOf(
            HighlightLineSegment(0, 6, HighlightType.COMMENT),
            HighlightLineSegment(1, 4, HighlightType.KEYWORD),
            HighlightLineSegment(7, 10, HighlightType.KEYWORD),
        )
        val semantic = listOf(LineSemanticSegment(2, 9, SemanticTokenType.FUNCTION, emptySet()))
        val plan = resolve(cache, syntax = syntax, semantic = semantic)
        for (start in 0..10) {
            for (end in start..10) {
                val expected = TextRenderPlanner.buildRuns(start, end, colors.defaultText.toArgb(),
                    syntax.map { TextRenderOverlay(it.startColumn, it.endColumn, colors.colorOf(it.type).toArgb(), it.type == HighlightType.COMMENT) },
                    semantic.map { TextRenderOverlay(it.startColumn, it.endColumn, colors.function.toArgb()) })
                assertThat(clippedRuns(plan, start, end)).containsExactlyElementsIn(expected).inOrder()
            }
        }
        assertThat(cache.stats().builds).isEqualTo(1)
    }

    @Test
    fun packedPlan_doesNotChangeWhenPlannerWorkspaceIsReused() {
        val cache = EditorLineRenderPlanCache()
        val first = resolve(cache)
        val original = clippedRuns(first, 0, 10)
        resolve(cache, line = 1, syntax = emptyList())
        assertThat(clippedRuns(first, 0, 10)).containsExactlyElementsIn(original).inOrder()
    }

    @Test
    fun lruEvictionAndBudgets_boundRetainedTextAndRuns() {
        val cache = EditorLineRenderPlanCache(maxEntries = 2, maxTotalChars = 20, maxTotalElements = 8)
        val first = resolve(cache, line = 0)
        val second = resolve(cache, line = 1)
        assertThat(resolve(cache, line = 0)).isSameInstanceAs(first)
        resolve(cache, line = 2)
        assertThat(resolve(cache, line = 1)).isNotSameInstanceAs(second)
        assertThat(cache.stats().entries).isEqualTo(2)
        assertThat(cache.stats().chars).isAtMost(20)
        assertThat(cache.stats().elements).isAtMost(8)
        val beforeOversize = cache.stats()
        resolve(cache, line = 3, text = "x".repeat(21))
        resolve(cache, line = 4, syntax = List(10) { HighlightLineSegment(0, 1, HighlightType.KEYWORD) })
        assertThat(cache.stats().entries).isEqualTo(beforeOversize.entries)
        cache.clear()
        assertThat(cache.stats().entries).isEqualTo(0)
        assertThat(cache.stats().chars).isEqualTo(0)
        assertThat(cache.stats().elements).isEqualTo(0)
    }

    private fun resolve(
        cache: EditorLineRenderPlanCache,
        line: Int = 0,
        text: String = "abcdefghij",
        syntax: List<LineHighlightSegment> = this.syntax,
        semantic: List<LineSemanticSegment> = emptyList(),
    ): TextLineRenderPlan = cache.getOrBuild(line, text, syntax, semantic, emptyList(), colors, noRainbow)

    private fun clippedRuns(plan: TextLineRenderPlan, start: Int, end: Int): List<TextRenderRun> = buildList {
        var index = plan.firstRunEndingAfter(start)
        while (index < plan.runCount && plan.startColumnAt(index) < end) {
            val from = maxOf(start, plan.startColumnAt(index))
            val to = minOf(end, plan.endColumnAt(index))
            if (to > from) add(TextRenderRun(from, to, plan.colorAt(index)))
            index++
        }
    }
}
