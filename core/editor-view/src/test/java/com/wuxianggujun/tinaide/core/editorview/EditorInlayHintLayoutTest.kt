package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorInlayHintLayoutTest {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 20f
    }

    @Test
    fun parameterHint_shouldReserveSpaceBeforeAnchoredArgument() {
        val cache = EditorLineLayoutCache()
        val lineText = "call(1)"
        val rawLayout = cache.getPrefixLayout(0, lineText, 1L, paint, 4)
        val layout = cache.getPrefixLayout(
            line = 0,
            lineText = lineText,
            textVersion = 1L,
            paint = paint,
            tabSize = 4,
            inlayHints = listOf(
                EditorInlayHint(
                    line = 0,
                    column = 5,
                    label = "value:",
                    kind = EditorInlayHintKind.PARAMETER,
                ),
            ),
            lineHeightPx = 24f,
        )

        assertThat(layout.segmentStartAdvance(5)).isWithin(0.01f).of(rawLayout.prefix[5])
        assertThat(layout.textStartAdvance(5)).isGreaterThan(layout.segmentStartAdvance(5))
        assertThat(layout.textStartAdvance(6) - rawLayout.prefix[6])
            .isWithin(0.01f)
            .of(layout.textStartAdvance(5) - rawLayout.prefix[5])
        assertThat(layout.inlayHintPlacements).hasSize(1)
        assertThat(layout.inlayHintPlacements.single().startAdvance)
            .isWithin(0.01f)
            .of(rawLayout.prefix[5])
    }

    @Test
    fun lineEndHint_shouldExtendLayoutWithoutMovingExistingText() {
        val cache = EditorLineLayoutCache()
        val lineText = "value"
        val rawLayout = cache.getPrefixLayout(0, lineText, 1L, paint, 4)
        val layout = cache.getPrefixLayout(
            line = 0,
            lineText = lineText,
            textVersion = 1L,
            paint = paint,
            tabSize = 4,
            inlayHints = listOf(
                EditorInlayHint(
                    line = 0,
                    column = lineText.length,
                    label = ": int",
                    kind = EditorInlayHintKind.TYPE,
                ),
            ),
            lineHeightPx = 24f,
        )

        for (column in 0 until lineText.length) {
            assertThat(layout.textStartAdvance(column)).isWithin(0.01f).of(rawLayout.prefix[column])
        }
        assertThat(layout.segmentStartAdvance(lineText.length))
            .isWithin(0.01f)
            .of(rawLayout.prefix[lineText.length])
        assertThat(layout.textStartAdvance(lineText.length))
            .isGreaterThan(layout.segmentStartAdvance(lineText.length))
    }

    @Test
    fun hintsAtSameColumn_shouldBePlacedSequentially() {
        val cache = EditorLineLayoutCache()
        val layout = cache.getPrefixLayout(
            line = 0,
            lineText = "f(x)",
            textVersion = 1L,
            paint = paint,
            tabSize = 4,
            inlayHints = listOf(
                EditorInlayHint(0, 2, "first:"),
                EditorInlayHint(0, 2, "second:"),
            ),
            lineHeightPx = 24f,
        )

        val first = layout.inlayHintPlacements[0]
        val second = layout.inlayHintPlacements[1]
        assertThat(second.startAdvance).isWithin(0.01f).of(first.endAdvance)
        assertThat(layout.textStartAdvance(2)).isWithin(0.01f).of(second.endAdvance)
        assertThat(layout.inlayHintColumns.asList()).containsExactly(2)
    }

    @Test
    fun xToColumn_insideHint_shouldResolveToHintAnchor() {
        val cache = EditorLineLayoutCache()
        val layout = cache.getPrefixLayout(
            line = 0,
            lineText = "call(1)",
            textVersion = 1L,
            paint = paint,
            tabSize = 4,
            inlayHints = listOf(EditorInlayHint(0, 5, "value:")),
            lineHeightPx = 24f,
        )
        val placement = layout.inlayHintPlacements.single()

        assertThat(cache.xToColumn(layout, (placement.startAdvance + placement.endAdvance) / 2f))
            .isEqualTo(5)
    }

    @Test
    fun changedHints_shouldRebuildCachedLayout() {
        val cache = EditorLineLayoutCache()
        val first = cache.getPrefixLayout(
            line = 0,
            lineText = "f(x)",
            textVersion = 1L,
            paint = paint,
            tabSize = 4,
            inlayHints = listOf(EditorInlayHint(0, 2, "a:")),
            lineHeightPx = 24f,
        )
        val second = cache.getPrefixLayout(
            line = 0,
            lineText = "f(x)",
            textVersion = 1L,
            paint = paint,
            tabSize = 4,
            inlayHints = listOf(EditorInlayHint(0, 2, "longParameter:")),
            lineHeightPx = 24f,
        )

        assertThat(second.prefix).isNotSameInstanceAs(first.prefix)
        assertThat(second.textStartAdvance(2)).isGreaterThan(first.textStartAdvance(2))
    }

    @Test
    fun wrapLayout_shouldMoveOverflowingHintAndArgumentToNextVisualLine() {
        val starts = EditorInlayHintColumnLayout.findWrapSegmentStarts(
            lineText = "call(1)",
            wrapColumns = 8,
            tabSize = 4,
            hints = listOf(EditorInlayHint(0, 5, "v:")),
        )

        assertThat(starts.asList()).containsExactly(0, 5).inOrder()
    }

    @Test
    fun surrogateInteriorHint_shouldSnapToCodePointStartAcrossLayouts() {
        val lineText = "a😀b"
        val hint = EditorInlayHint(0, 2, "x")
        val layout = EditorLineLayoutCache().getPrefixLayout(
            line = 0,
            lineText = lineText,
            textVersion = 1L,
            paint = paint,
            tabSize = 4,
            inlayHints = listOf(hint),
            lineHeightPx = 24f,
        )
        val wrapStarts = EditorInlayHintColumnLayout.findWrapSegmentStarts(
            lineText = lineText,
            wrapColumns = 4,
            tabSize = 4,
            hints = listOf(hint),
        )

        assertThat(layout.inlayHintPlacements.single().column).isEqualTo(1)
        assertThat(wrapStarts.asList()).containsExactly(0, 1, 3).inOrder()
    }
}
