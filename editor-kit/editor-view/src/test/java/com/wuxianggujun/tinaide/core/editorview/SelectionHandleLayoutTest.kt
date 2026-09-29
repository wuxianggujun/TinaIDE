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
class SelectionHandleLayoutTest {

    @Test
    fun resolveSelectionHandleLayout_shouldMatchWrappedSegmentColumnToXBaseline() {
        val buffer = RopeTextBuffer().apply { insert(0, "abcdefghijkl") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                wordWrap = true,
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = 60f,
                contentStartXPx = 24f
            )
            selectionRange = OffsetRange(anchor = 2, caret = 8)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = with(Density(1f)) { state.fontSizeSp }
        }
        val lineLayoutCache = EditorLineLayoutCache()

        val layout = resolveSelectionHandleLayout(
            state = state,
            textStartX = 24f,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            lineTextProvider = buffer::getLine
        )

        assertThat(layout).isNotNull()
        layout ?: return

        val lineText = buffer.getLine(0)
        val textVersion = buffer.version
        val prefixLayout = lineLayoutCache.getPrefixLayout(
            line = 0,
            lineText = lineText,
            textVersion = textVersion,
            paint = textPaint,
            tabSize = state.config.tabSize
        )
        val prefix = prefixLayout.prefix

        val startVisualLine = state.visualLineForPosition(0, 2)
        val startSegmentStartColumn = state.visualLineStartColumn(startVisualLine)
        val startSegmentStartX = prefix[startSegmentStartColumn.coerceIn(0, prefixLayout.length)]
        val startX = prefix[2]

        val endVisualLine = state.visualLineForPosition(0, 8)
        val endSegmentStartColumn = state.visualLineStartColumn(endVisualLine)
        val endSegmentStartX = prefix[endSegmentStartColumn.coerceIn(0, prefixLayout.length)]
        val endX = prefix[8]

        assertThat(layout.startCenter.x)
            .isWithin(0.01f)
            .of(24f + (startX - startSegmentStartX))
        assertThat(layout.endCenter.x)
            .isWithin(0.01f)
            .of(24f + (endX - endSegmentStartX))
        assertThat(layout.endCenter.y).isGreaterThan(layout.startCenter.y)
    }

    @Test
    fun resolveSelectionHandleLayout_shouldLookupSameLineTextOnce() {
        val buffer = RopeTextBuffer().apply { insert(0, "abcdefghijkl") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                wordWrap = true,
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = 60f,
                contentStartXPx = 24f
            )
            selectionRange = OffsetRange(anchor = 2, caret = 8)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = with(Density(1f)) { state.fontSizeSp }
        }
        val lineLayoutCache = EditorLineLayoutCache()
        val lineTextCalls = linkedMapOf<Int, Int>()

        val layout = resolveSelectionHandleLayout(
            state = state,
            textStartX = 24f,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            lineTextProvider = { line ->
                lineTextCalls[line] = lineTextCalls.getOrDefault(line, 0) + 1
                buffer.getLine(line)
            }
        )

        assertThat(layout).isNotNull()
        assertThat(lineTextCalls[0]).isEqualTo(1)
    }

    @Test
    fun viewportDragAnchor_shouldPointToTextEdgeAboveHandleKnob() {
        val buffer = RopeTextBuffer().apply { insert(0, "operator+") }
        val state = EditorState(buffer).apply {
            typeface = Typeface.MONOSPACE
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 240f,
                viewportWidthPx = 400f,
                contentStartXPx = 24f
            )
            selectionRange = OffsetRange(anchor = 0, caret = "operator".length)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = with(Density(1f)) { state.fontSizeSp }
        }
        val layout = resolveSelectionHandleLayout(
            state = state,
            textStartX = 24f,
            textPaint = textPaint,
            lineLayoutCache = EditorLineLayoutCache(),
            lineTextProvider = buffer::getLine
        )

        assertThat(layout).isNotNull()
        layout ?: return
        val endAnchor = layout.viewportDragAnchor(
            kind = SelectionHandleKind.END,
            scrollOffsetXPx = state.scrollOffsetXPx
        )

        assertThat(endAnchor.x).isWithin(0.01f).of(layout.endCenter.x)
        assertThat(endAnchor.y).isLessThan(layout.endCenter.y)
        assertThat(endAnchor.y).isWithin(0.01f).of(state.lineHeightPx)
    }
}
