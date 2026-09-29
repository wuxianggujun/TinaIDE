package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.toArgb
import com.wuxianggujun.tinaide.core.treesitter.HighlightType
import java.util.LinkedHashMap

internal data class LineSemanticSegment(
    val startColumn: Int,
    val endColumn: Int,
    val tokenType: SemanticTokenType,
    val tokenModifiers: Set<SemanticTokenModifier>,
)

/** Packed, immutable column/color runs. Viewport coordinates never enter this cache. */
internal class TextLineRenderPlan(runs: List<TextRenderRun>) {
    private val packedRuns = IntArray(runs.size * 3).apply {
        runs.forEachIndexed { index, run ->
            this[index * 3] = run.startColumn
            this[index * 3 + 1] = run.endColumn
            this[index * 3 + 2] = run.color
        }
    }
    val runCount: Int get() = packedRuns.size / 3

    fun startColumnAt(index: Int): Int = packedRuns[index * 3]
    fun endColumnAt(index: Int): Int = packedRuns[index * 3 + 1]
    fun colorAt(index: Int): Int = packedRuns[index * 3 + 2]

    fun firstRunEndingAfter(column: Int): Int {
        var low = 0
        var high = runCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (endColumnAt(mid) <= column) low = mid + 1 else high = mid
        }
        return low
    }
}

/** Content keys keep unrelated highlight updates and soft-wrap changes from rebuilding runs. */
internal class EditorLineRenderPlanCache(
    private val maxEntries: Int = 512,
    private val maxTotalChars: Int = 220_000,
    private val maxTotalElements: Int = 32_768,
) {
    private data class Entry(
        val text: String,
        val syntax: List<LineHighlightSegment>,
        val semantic: List<LineSemanticSegment>,
        val brackets: List<RainbowBracketComputer.BracketInfo>,
        val plan: TextLineRenderPlan,
    ) {
        val elementCount: Int get() = syntax.size + semantic.size + brackets.size + plan.runCount
    }

    private val lock = Any()
    private val lru = LinkedHashMap<Int, Entry>(64, 0.75f, true)
    private var totalChars = 0
    private var totalElements = 0
    private var buildCount = 0L
    private var hitCount = 0L
    private var cachedColors: EditorSyntaxColors? = null
    private var cachedRainbowColors = IntArray(0)
    private val syntaxOverlays = ArrayList<TextRenderOverlay>(32)
    private val semanticOverlays = ArrayList<TextRenderOverlay>(16)
    private val syntaxPool = ArrayList<TextRenderOverlay>(32)
    private val semanticPool = ArrayList<TextRenderOverlay>(16)
    private var planner = TextRenderPlanner.Workspace()

    internal data class Stats(val entries: Int, val chars: Int, val elements: Int, val builds: Long, val hits: Long)

    fun stats(): Stats = synchronized(lock) {
        Stats(lru.size, totalChars, totalElements, buildCount, hitCount)
    }

    fun clear() = synchronized(lock) { clearEntries() }

    fun getOrBuild(
        line: Int,
        text: String,
        syntax: List<LineHighlightSegment>,
        semantic: List<LineSemanticSegment>,
        brackets: List<RainbowBracketComputer.BracketInfo>,
        colors: EditorSyntaxColors,
        rainbowColors: IntArray,
    ): TextLineRenderPlan = synchronized(lock) {
        if (cachedColors != colors || !cachedRainbowColors.contentEquals(rainbowColors)) {
            clearEntries()
            cachedColors = colors
            cachedRainbowColors = rainbowColors
        }
        val cached = lru[line]
        if (cached != null && cached.text == text && cached.syntax == syntax &&
            cached.semantic == semantic && cached.brackets == brackets
        ) {
            hitCount++
            return@synchronized cached.plan
        }

        val plan = buildPlan(text.length, syntax, semantic, brackets, colors, rainbowColors)
        buildCount++
        val entry = Entry(text, syntax, semantic, brackets, plan)
        lru.remove(line)?.let(::subtractSize)
        if (maxEntries > 0 && text.length <= maxTotalChars && entry.elementCount <= maxTotalElements) {
            lru[line] = entry
            totalChars += text.length
            totalElements += entry.elementCount
            val iterator = lru.entries.iterator()
            while (lru.size > maxEntries || totalChars > maxTotalChars || totalElements > maxTotalElements) {
                subtractSize(iterator.next().value)
                iterator.remove()
            }
        }
        plan
    }

    private fun buildPlan(
        textLength: Int,
        syntax: List<LineHighlightSegment>,
        semantic: List<LineSemanticSegment>,
        brackets: List<RainbowBracketComputer.BracketInfo>,
        colors: EditorSyntaxColors,
        rainbowColors: IntArray,
    ): TextLineRenderPlan {
        syntaxOverlays.clear()
        syntax.forEachIndexed { index, segment ->
            syntaxOverlays.add(
                obtainOverlay(
                    syntaxPool, index, segment.startColumn, segment.endColumn,
                    colors.colorOf(segment.type).toArgb(), segment.type == HighlightType.COMMENT,
                )
            )
        }
        semanticOverlays.clear()
        semantic.forEachIndexed { index, segment ->
            semanticOverlays.add(
                obtainOverlay(
                    semanticPool, index, segment.startColumn, segment.endColumn,
                    colors.colorOfSemantic(segment.tokenType, segment.tokenModifiers).toArgb(),
                )
            )
        }
        if (rainbowColors.isNotEmpty() && brackets.isNotEmpty()) {
            brackets.forEach { bracket ->
                semanticOverlays.add(
                    obtainOverlay(
                        semanticPool, semanticOverlays.size, bracket.column, bracket.column + 1,
                        rainbowColors[bracket.depth % rainbowColors.size],
                    )
                )
            }
            semanticOverlays.sortWith(OVERLAY_ORDER)
        }
        val plan = TextLineRenderPlan(
            planner.buildRuns(
                visibleStartColumn = 0,
                visibleEndColumn = textLength,
                defaultColor = colors.defaultText.toArgb(),
                syntaxOverlays = syntaxOverlays,
                semanticOverlays = semanticOverlays,
            )
        )
        if (syntaxOverlays.size > MAX_RETAINED_OVERLAYS || semanticOverlays.size > MAX_RETAINED_OVERLAYS ||
            plan.runCount > MAX_RETAINED_OVERLAYS
        ) {
            // A pathological line must not pin an oversized scratch workspace after it leaves view.
            planner = TextRenderPlanner.Workspace()
            syntaxOverlays.clear()
            semanticOverlays.clear()
            syntaxOverlays.trimToSize()
            semanticOverlays.trimToSize()
        }
        return plan
    }

    private fun clearEntries() {
        lru.clear()
        totalChars = 0
        totalElements = 0
    }

    private fun subtractSize(entry: Entry) {
        totalChars -= entry.text.length
        totalElements -= entry.elementCount
    }

    private fun obtainOverlay(
        pool: MutableList<TextRenderOverlay>,
        index: Int,
        start: Int,
        end: Int,
        color: Int,
        blocksSemantic: Boolean = false,
    ): TextRenderOverlay {
        if (index >= MAX_RETAINED_OVERLAYS) return TextRenderOverlay(start, end, color, blocksSemantic)
        val overlay = pool.getOrNull(index) ?: TextRenderOverlay(0, 0, 0).also(pool::add)
        overlay.startColumn = start
        overlay.endColumn = end
        overlay.color = color
        overlay.blocksSemantic = blocksSemantic
        return overlay
    }

    private companion object {
        const val MAX_RETAINED_OVERLAYS = 4096
        val OVERLAY_ORDER = compareBy<TextRenderOverlay> { it.startColumn }.thenByDescending { it.endColumn - it.startColumn }
    }
}
