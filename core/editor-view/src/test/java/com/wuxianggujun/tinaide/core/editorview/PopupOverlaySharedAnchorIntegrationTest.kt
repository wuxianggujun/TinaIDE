package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PopupOverlaySharedAnchorIntegrationTest {

    @Test
    fun completionPopupLayout_repositionsWhenHorizontalScrollChanges() {
        val env = createEnv(
            text = "abcdefghijklmnopqrstuvwxyz",
            canvasWidthPx = 1200f,
            canvasHeightPx = 320f,
            windowWidthPx = 1440f,
            windowHeightPx = 720f,
            contentStartXPx = 120f
        )
        env.state.cursorOffset = env.buffer.positionToOffset(0, 6)

        val beforeScroll = env.resolveCompletionLayout()
        env.state.scrollOffsetXPx = 18f
        val afterScroll = env.resolveCompletionLayout()

        assertThat(afterScroll.offset.x).isEqualTo(beforeScroll.offset.x - 18)
        assertThat(afterScroll.offset.y).isEqualTo(beforeScroll.offset.y)
    }

    @Test
    fun signatureHelpPopupLayout_repositionsWhenVerticalScrollChanges() {
        val env = createEnv(
            text = (0..12).joinToString("\n") { "line-$it" },
            canvasWidthPx = 720f,
            canvasHeightPx = 340f,
            windowWidthPx = 960f,
            windowHeightPx = 760f
        )
        env.state.cursorOffset = env.buffer.positionToOffset(8, 3)

        val beforeScroll = env.resolveSignatureLayout()
        env.state.scrollOffsetPx = 14f
        val afterScroll = env.resolveSignatureLayout()

        assertThat(afterScroll.offset.y).isEqualTo(beforeScroll.offset.y - 14)
        assertThat(afterScroll.offset.x).isEqualTo(beforeScroll.offset.x)
    }

    @Test
    fun completionAndSignatureHelp_shiftBySameAmountUnderSharedHorizontalAnchor() {
        val env = createEnv(
            text = "abcdefghijklmnopqrstuvwxyz",
            canvasWidthPx = 1200f,
            canvasHeightPx = 320f,
            windowWidthPx = 1440f,
            windowHeightPx = 720f,
            contentStartXPx = 120f
        )
        env.state.cursorOffset = env.buffer.positionToOffset(0, 6)

        val completionBefore = env.resolveCompletionLayout()
        val signatureBefore = env.resolveSignatureLayout()

        env.state.scrollOffsetXPx = 18f

        val completionAfter = env.resolveCompletionLayout()
        val signatureAfter = env.resolveSignatureLayout()

        assertThat(completionAfter.offset.x - completionBefore.offset.x).isEqualTo(-18)
        assertThat(signatureAfter.offset.x - signatureBefore.offset.x).isEqualTo(-18)
    }

    @Test
    fun completionPopupLayout_recomputesWhenImeInsetChanges() {
        val env = createEnv(
            text = (0..8).joinToString("\n") { "line-$it" },
            canvasWidthPx = 420f,
            canvasHeightPx = 240f,
            windowWidthPx = 500f,
            windowHeightPx = 260f,
            canvasOriginInWindowPx = IntOffset(20, 60)
        )
        env.state.cursorOffset = env.buffer.positionToOffset(1, 3)

        val beforeIme = env.resolveCompletionLayout()
        val afterIme = env.copy(imeBottomInsetPx = 80f).resolveCompletionLayout()

        assertThat(afterIme.offset.y).isEqualTo(beforeIme.offset.y)
        assertThat(afterIme.maxHeightPx).isLessThan(beforeIme.maxHeightPx)
    }

    @Test
    fun signatureHelpPopupLayout_recomputesWhenWindowHeightContracts() {
        val env = createEnv(
            text = (0..8).joinToString("\n") { "line-$it" },
            canvasWidthPx = 560f,
            canvasHeightPx = 240f,
            windowWidthPx = 700f,
            windowHeightPx = 300f,
            canvasOriginInWindowPx = IntOffset(20, 60)
        )
        env.state.cursorOffset = env.buffer.positionToOffset(1, 3)

        val tallWindow = env.resolveSignatureLayout()
        val shortWindow = env.copy(windowHeightPx = 220f).resolveSignatureLayout()

        assertThat(shortWindow.maxHeightPx).isLessThan(tallWindow.maxHeightPx)
        assertThat(shortWindow.offset.y).isEqualTo(tallWindow.offset.y)
    }

    @Test
    fun completionPopupLayout_recomputesWhenFontMetricsGrow() {
        val env = createEnv(
            text = "abcdefghijklmnopqrstuvwxyz",
            canvasWidthPx = 1200f,
            canvasHeightPx = 320f,
            windowWidthPx = 1440f,
            windowHeightPx = 720f,
            contentStartXPx = 120f
        )
        env.state.cursorOffset = env.buffer.positionToOffset(0, 8)

        val beforeResize = env.resolveCompletionLayout()
        env.state.updateMetrics(
            lineHeightPx = 30f,
            charWidthPx = 14f,
            viewportHeightPx = env.canvasHeightPx,
            viewportWidthPx = env.canvasWidthPx,
            contentStartXPx = env.state.contentStartXPx
        )
        val afterResize = env.copy(fontSizePx = 22f).resolveCompletionLayout()

        assertThat(afterResize.offset.x).isGreaterThan(beforeResize.offset.x)
        assertThat(afterResize.offset.y).isGreaterThan(beforeResize.offset.y)
    }

    @Test
    fun signatureHelpPopupLayout_recomputesWhenFontMetricsGrow() {
        val env = createEnv(
            text = (0..12).joinToString("\n") { "line-$it" },
            canvasWidthPx = 720f,
            canvasHeightPx = 420f,
            windowWidthPx = 960f,
            windowHeightPx = 860f
        )
        env.state.cursorOffset = env.buffer.positionToOffset(6, 4)

        val beforeResize = env.resolveSignatureLayout()
        env.state.updateMetrics(
            lineHeightPx = 34f,
            charWidthPx = 13f,
            viewportHeightPx = env.canvasHeightPx,
            viewportWidthPx = env.canvasWidthPx,
            contentStartXPx = env.state.contentStartXPx
        )
        val afterResize = env.copy(fontSizePx = 22f).resolveSignatureLayout()

        assertThat(afterResize.offset.x).isGreaterThan(beforeResize.offset.x)
        assertThat(afterResize.offset.y).isGreaterThan(beforeResize.offset.y)
    }

    private fun createEnv(
        text: String,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        windowWidthPx: Float,
        windowHeightPx: Float,
        contentStartXPx: Float = 24f,
        canvasOriginInWindowPx: IntOffset = IntOffset(20, 100)
    ): PopupEnv {
        val buffer = RopeTextBuffer().apply { insert(0, text) }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(
                wordWrap = false,
                codeFolding = false,
                tabSize = 4
            )
        ).apply {
            typeface = Typeface.MONOSPACE
            fontSizeSp = 14f
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = canvasHeightPx,
                viewportWidthPx = canvasWidthPx,
                contentStartXPx = contentStartXPx
            )
        }
        return PopupEnv(
            buffer = buffer,
            state = state,
            textPaint = Paint(Paint.ANTI_ALIAS_FLAG),
            lineLayoutCache = EditorLineLayoutCache(),
            canvasOriginInWindowPx = canvasOriginInWindowPx,
            canvasWidthPx = canvasWidthPx,
            canvasHeightPx = canvasHeightPx,
            windowWidthPx = windowWidthPx,
            windowHeightPx = windowHeightPx,
            imeBottomInsetPx = 0f,
            fontSizePx = 14f
        )
    }

    private data class PopupEnv(
        val buffer: RopeTextBuffer,
        val state: EditorState,
        val textPaint: Paint,
        val lineLayoutCache: EditorLineLayoutCache,
        val canvasOriginInWindowPx: IntOffset,
        val canvasWidthPx: Float,
        val canvasHeightPx: Float,
        val windowWidthPx: Float,
        val windowHeightPx: Float,
        val imeBottomInsetPx: Float,
        val fontSizePx: Float
    ) {
        fun resolveCompletionLayout(): CompletionPopupLayout {
            val anchor = resolveAnchor()
            return CompletionPopupLayoutResolver.resolve(
                cursorXInViewportPx = anchor.cursorXInViewportPx,
                cursorLineTopInViewportPx = anchor.cursorLineTopInViewportPx,
                lineHeightPx = state.lineHeightPx,
                canvasOriginInWindowPx = canvasOriginInWindowPx,
                canvasWidthPx = canvasWidthPx,
                canvasHeightPx = canvasHeightPx,
                windowWidthPx = windowWidthPx,
                windowHeightPx = windowHeightPx,
                imeBottomInsetPx = imeBottomInsetPx,
                preferredPopupWidthPx = 300f,
                popupMaxHeightPx = 280f,
                itemCount = 6,
                itemHeightPx = 45f,
                loadingIndicatorHeightPx = 20f,
                isLoading = false,
                marginPx = 8f,
                cursorGapPx = 16f,
                narrowEditorThresholdPx = 500f,
                minPopupHeightPx = 96f
            )
        }

        fun resolveSignatureLayout(): SignatureHelpPopupLayout {
            val anchor = resolveAnchor()
            return SignatureHelpPopupLayoutResolver.resolve(
                cursorXInViewportPx = anchor.cursorXInViewportPx,
                cursorLineTopInViewportPx = anchor.cursorLineTopInViewportPx,
                lineHeightPx = state.lineHeightPx,
                canvasOriginInWindowPx = canvasOriginInWindowPx,
                canvasWidthPx = canvasWidthPx,
                canvasHeightPx = canvasHeightPx,
                windowWidthPx = windowWidthPx,
                windowHeightPx = windowHeightPx,
                imeBottomInsetPx = imeBottomInsetPx,
                preferredPopupWidthPx = 340f,
                popupMaxHeightPx = 244f,
                preferredContentHeightPx = 120f,
                marginPx = 8f,
                cursorGapPx = 10f,
                narrowEditorThresholdPx = 500f,
                minPopupHeightPx = 68f
            )
        }

        private fun resolveAnchor(): CursorPopupAnchor = CursorPopupAnchorResolver.resolve(
            state = state,
            contentStartXPx = state.contentStartXPx,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            fontSizePx = fontSizePx,
            lineTextProvider = buffer::getLine,
            textScanCache = EditorTextScanCache()
        )
    }
}
