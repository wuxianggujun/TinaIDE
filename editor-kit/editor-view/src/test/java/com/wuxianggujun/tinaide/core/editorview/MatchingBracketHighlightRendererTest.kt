package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MatchingBracketHighlightRendererTest {

    @Test
    fun resolveHighlightRects_shouldLookupSameLineTextOnce() {
        val env = createEnv("  call(foo)")
        val renderer = MatchingBracketHighlightRenderer()

        val rects = renderer.resolveHighlightRects(
            frameContext = env.frameContext,
            match = EditorBracketSnapshotCache.BracketMatch(
                openLine = 0,
                openColumn = 6,
                closeLine = 0,
                closeColumn = 10,
                depth = 1
            ),
            textStartX = env.textStartX,
            textPaint = env.textPaint,
            lineLayoutCache = env.lineLayoutCache
        )

        assertThat(rects).hasSize(2)
        assertThat(env.lineTextCalls[0]).isEqualTo(1)
        assertThat(rects[1].left).isGreaterThan(rects[0].left)
        assertThat(rects[0].top).isEqualTo(rects[1].top)
    }

    @Test
    fun resolveHighlightRects_shouldLookupEachLineOnceForCrossLineMatch() {
        val env = createEnv("{\n  call(\n)\n")
        val renderer = MatchingBracketHighlightRenderer()

        val rects = renderer.resolveHighlightRects(
            frameContext = env.frameContext,
            match = EditorBracketSnapshotCache.BracketMatch(
                openLine = 1,
                openColumn = 6,
                closeLine = 2,
                closeColumn = 0,
                depth = 1
            ),
            textStartX = env.textStartX,
            textPaint = env.textPaint,
            lineLayoutCache = env.lineLayoutCache
        )

        assertThat(rects).hasSize(2)
        assertThat(env.lineTextCalls[1]).isEqualTo(1)
        assertThat(env.lineTextCalls[2]).isEqualTo(1)
        assertThat(rects[1].top).isGreaterThan(rects[0].top)
    }

    private fun createEnv(text: String): TestEnv {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = 240f,
                contentStartXPx = 24f
            )
        }
        val lineTextCalls = linkedMapOf<Int, Int>()
        val lineTextProvider: (Int) -> String = { line ->
            lineTextCalls[line] = lineTextCalls.getOrDefault(line, 0) + 1
            buffer.getLine(line)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = state.fontSizeSp
        }

        return TestEnv(
            textStartX = 24f,
            textPaint = textPaint,
            lineLayoutCache = EditorLineLayoutCache(),
            frameContext = EditorRenderFrameContext(
                state = state,
                textVersion = buffer.version,
                textScanCache = EditorTextScanCache(),
                bracketSnapshotCache = EditorBracketSnapshotCache(),
                lineTextProvider = lineTextProvider
            ),
            lineTextCalls = lineTextCalls
        )
    }

    private data class TestEnv(
        val textStartX: Float,
        val textPaint: Paint,
        val lineLayoutCache: EditorLineLayoutCache,
        val frameContext: EditorRenderFrameContext,
        val lineTextCalls: Map<Int, Int>
    )
}
