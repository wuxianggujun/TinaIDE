package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CursorPopupAnchorResolverTest {

    @Test
    fun resolve_tracksHorizontalScrollForSharedPopupAnchor() {
        val env = createEnv(text = "abcdefghij", viewportWidthPx = 72f)
        env.state.cursorOffset = env.buffer.positionToOffset(0, 7)

        val beforeScroll = env.resolve()
        env.state.scrollOffsetXPx = 18f
        val afterScroll = env.resolve()

        assertThat(afterScroll.cursorXInViewportPx)
            .isWithin(0.01f)
            .of(beforeScroll.cursorXInViewportPx - 18f)
        assertThat(afterScroll.cursorLineTopInViewportPx)
            .isWithin(0.01f)
            .of(beforeScroll.cursorLineTopInViewportPx)
    }

    @Test
    fun resolve_anchorsWrappedCursorAgainstCurrentVisualSegment() {
        val env = createEnv(
            text = "abcdefghijkl",
            wordWrap = true,
            viewportWidthPx = 60f
        )
        env.state.cursorOffset = env.buffer.positionToOffset(0, 8)

        val anchor = env.resolve()
        env.configurePaint()
        val lineText = env.buffer.getLine(0)
        val prefixLayout = env.lineLayoutCache.getPrefixLayout(
            line = 0,
            lineText = lineText,
            textVersion = env.buffer.version,
            paint = env.textPaint,
            tabSize = env.state.config.tabSize
        )
        val prefix = prefixLayout.prefix
        val visualLine = env.state.visualLineForPosition(0, 8)
        val segmentStartColumn = env.state.visualLineStartColumn(visualLine)
        val segmentStartX = prefix[segmentStartColumn.coerceIn(0, prefixLayout.length)]
        val cursorX = prefix[8]
        val measuredDelta = cursorX - segmentStartX
        val fallbackDelta = (8 - segmentStartColumn) * env.state.charWidthPx
        val expectedDelta = if (measuredDelta <= 0f || measuredDelta < fallbackDelta * 0.5f) {
            fallbackDelta
        } else {
            measuredDelta
        }

        assertThat(visualLine).isGreaterThan(0)
        assertThat(anchor.cursorXInViewportPx)
            .isWithin(0.01f)
            .of(env.contentStartXPx + expectedDelta)
    }

    @Test
    fun resolve_tracksVerticalScrollForSharedPopupAnchor() {
        val env = createEnv(text = "alpha\nbeta\ngamma\ndelta")
        env.state.cursorOffset = env.buffer.positionToOffset(2, 2)

        val beforeScroll = env.resolve()
        env.state.scrollOffsetPx = 14f
        val afterScroll = env.resolve()

        assertThat(afterScroll.cursorLineTopInViewportPx)
            .isWithin(0.01f)
            .of(beforeScroll.cursorLineTopInViewportPx - 14f)
        assertThat(afterScroll.cursorXInViewportPx)
            .isWithin(0.01f)
            .of(beforeScroll.cursorXInViewportPx)
    }

    @Test
    fun resolveCursorVisualAnchor_shouldReuseFoldTrimmedColumnsForFoldEndLineCursor() {
        val env = createEnv(text = "alpha   \n    beta")
        env.state.cursorOffset = env.buffer.positionToOffset(1, 6)
        env.configurePaint()

        val startLineText = env.buffer.getLine(0)
        val badgeMargin = 3f
        val badgeWidth = 8f
        val trimmedAnchor = resolveCursorVisualAnchor(
            state = env.state,
            textStartX = env.contentStartXPx,
            textPaint = env.textPaint,
            lineLayoutCache = env.lineLayoutCache,
            lineTextProvider = env.buffer::getLine,
            textScanCache = EditorTextScanCache(),
            foldEndLineInfo = FoldEndLineCursorInfo(
                foldStartLine = 0,
                foldStartLineText = startLineText,
                foldStartLineTrimmedEndCol = 5,
                endLineTrimStartCol = 4,
                badgeMargin = badgeMargin,
                badgeWidth = badgeWidth
            )
        )
        val untrimmedAnchor = resolveCursorVisualAnchor(
            state = env.state,
            textStartX = env.contentStartXPx,
            textPaint = env.textPaint,
            lineLayoutCache = env.lineLayoutCache,
            lineTextProvider = env.buffer::getLine,
            textScanCache = EditorTextScanCache(),
            foldEndLineInfo = FoldEndLineCursorInfo(
                foldStartLine = 0,
                foldStartLineText = startLineText,
                foldStartLineTrimmedEndCol = startLineText.length,
                endLineTrimStartCol = 4,
                badgeMargin = badgeMargin,
                badgeWidth = badgeWidth
            )
        )

        val startPrefixLayout = env.lineLayoutCache.getPrefixLayout(
            line = 0,
            lineText = startLineText,
            textVersion = env.buffer.version,
            paint = env.textPaint,
            tabSize = env.state.config.tabSize
        )
        val expectedDelta = startPrefixLayout.prefix[startLineText.length] - startPrefixLayout.prefix[5]

        assertThat(trimmedAnchor).isNotNull()
        assertThat(untrimmedAnchor).isNotNull()
        assertThat(untrimmedAnchor!!.cursorXInContentPx - trimmedAnchor!!.cursorXInContentPx)
            .isWithin(0.01f)
            .of(expectedDelta)
    }

    private fun createEnv(
        text: String,
        wordWrap: Boolean = false,
        viewportWidthPx: Float = 240f,
        contentStartXPx: Float = 24f
    ): TestEnv {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                wordWrap = wordWrap,
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = viewportWidthPx,
                contentStartXPx = contentStartXPx
            )
        }
        return TestEnv(
            buffer = buffer,
            state = state,
            lineLayoutCache = EditorLineLayoutCache(),
            textPaint = Paint(Paint.ANTI_ALIAS_FLAG),
            contentStartXPx = contentStartXPx
        )
    }

    private data class TestEnv(
        val buffer: RopeTextBuffer,
        val state: EditorState,
        val lineLayoutCache: EditorLineLayoutCache,
        val textPaint: Paint,
        val contentStartXPx: Float
    ) {
        fun resolve(): CursorPopupAnchor = CursorPopupAnchorResolver.resolve(
            state = state,
            contentStartXPx = contentStartXPx,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            fontSizePx = with(Density(1f)) { state.fontSizeSp },
            lineTextProvider = buffer::getLine,
            textScanCache = EditorTextScanCache()
        )

        fun configurePaint() {
            textPaint.typeface = state.typeface
            textPaint.textSize = with(Density(1f)) { state.fontSizeSp }
        }
    }
}
