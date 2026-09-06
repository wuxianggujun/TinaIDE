package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorPaintApplyMemoTest {
    @Test
    fun unchangedFrames_shouldMeasureFontOnlyOnce() {
        val paint = textPaint()
        val lineNumberPaint = mockk<Paint>(relaxed = true)
        val memo = PaintApplyMemo()

        repeat(120) { memo.apply(paint, lineNumberPaint, Typeface.MONOSPACE, 16f, 1) }

        verify(exactly = 1) { paint.getFontMetrics(any<Paint.FontMetrics>()) }
        verify(exactly = 1) { paint.measureText("0") }
        assertThat(memo.lineHeightPx).isEqualTo(10f)
        assertThat(memo.charWidthPx).isEqualTo(6f)
    }

    @Test
    fun metrics_shouldRefreshForFontChangesButNotColorChanges() {
        val paint = textPaint()
        val lineNumberPaint = mockk<Paint>(relaxed = true)
        val memo = PaintApplyMemo()

        memo.apply(paint, lineNumberPaint, Typeface.MONOSPACE, 16f, 1)
        memo.apply(paint, lineNumberPaint, Typeface.MONOSPACE, 20f, 1)
        memo.apply(paint, lineNumberPaint, Typeface.DEFAULT, 20f, 1)
        memo.apply(paint, lineNumberPaint, Typeface.DEFAULT, 20f, 2)

        verify(exactly = 3) { paint.getFontMetrics(any<Paint.FontMetrics>()) }
        verify(exactly = 3) { paint.measureText("0") }
    }

    private fun textPaint(): Paint = mockk<Paint>(relaxed = true).also { paint ->
        every { paint.getFontMetrics(any<Paint.FontMetrics>()) } answers {
            firstArg<Paint.FontMetrics>().apply {
                ascent = -8f
                descent = 2f
                leading = 0f
            }
            10f
        }
        every { paint.measureText("0") } returns 6f
    }
}
