package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignatureHelpPopupLayoutResolverTest {

    @Test
    fun preferredContentHeight_shouldAddNavigationBodyAndLoadingChrome() {
        val height = calculateSignatureHelpPreferredContentHeightPx(
            chromeHeightPx = 18f,
            navigationHeightPx = 34f,
            bodyHeightPx = 80f,
            loadingHeightPx = 20f
        )

        assertThat(height).isEqualTo(152f)
    }

    @Test
    fun preferredBodyHeight_shouldMeasureEachVisibleOverloadAndOverflowBlock() {
        val rowHeights = listOf(
            estimateSignatureHelpTextLineCount(
                signatureLength = 5,
                estimatedCharsPerLine = 24,
                maxLines = 1
            ) * 19f + 12f,
            estimateSignatureHelpTextLineCount(
                signatureLength = 80,
                estimatedCharsPerLine = 24,
                maxLines = 3
            ) * 19f + 16f + 29f,
            estimateSignatureHelpTextLineCount(
                signatureLength = 4,
                estimatedCharsPerLine = 24,
                maxLines = 1
            ) * 19f + 12f
        )

        val height = calculateSignatureHelpBodyHeightPx(
            rowHeightsPx = rowHeights,
            rowSpacingPx = 4f,
            overflowBlockCount = 2,
            overflowBlockHeightPx = 32f,
            bottomPaddingPx = 8f
        )

        assertThat(rowHeights).containsExactly(31f, 102f, 31f).inOrder()
        assertThat(height).isEqualTo(244f)
    }

    @Test
    fun estimatedTextLineCount_shouldClampInvalidInputsAndPresentationLimit() {
        assertThat(
            estimateSignatureHelpTextLineCount(
                signatureLength = 0,
                estimatedCharsPerLine = 0,
                maxLines = 0
            )
        ).isEqualTo(1)
        assertThat(
            estimateSignatureHelpTextLineCount(
                signatureLength = 200,
                estimatedCharsPerLine = 24,
                maxLines = 3
            )
        ).isEqualTo(3)
    }

    @Test
    fun resolve_prefersAboveWhenThereIsEnoughRoom() {
        val layout = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 120f,
            cursorLineTopInViewportPx = 180f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(40, 100),
            canvasWidthPx = 560f,
            canvasHeightPx = 300f,
            windowWidthPx = 700f,
            windowHeightPx = 700f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 168f,
            preferredContentHeightPx = 96f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(106, 174))
        assertThat(layout.widthPx).isEqualTo(340f)
        assertThat(layout.maxHeightPx).isEqualTo(96f)
    }

    @Test
    fun resolve_usesWidePanelModeOnNarrowEditor() {
        val layout = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 240f,
            cursorLineTopInViewportPx = 30f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 60),
            canvasWidthPx = 320f,
            canvasHeightPx = 220f,
            windowWidthPx = 400f,
            windowHeightPx = 600f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 168f,
            preferredContentHeightPx = 96f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(36, 120))
        assertThat(layout.widthPx).isEqualTo(288f)
        assertThat(layout.maxHeightPx).isEqualTo(96f)
    }

    @Test
    fun resolve_keepsWidePanelCenteredRegardlessOfCursorColumn() {
        val nearLineStart = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 24f,
            cursorLineTopInViewportPx = 30f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 60),
            canvasWidthPx = 320f,
            canvasHeightPx = 220f,
            windowWidthPx = 400f,
            windowHeightPx = 600f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 168f,
            preferredContentHeightPx = 96f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )
        val nearLineEnd = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 280f,
            cursorLineTopInViewportPx = 30f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 60),
            canvasWidthPx = 320f,
            canvasHeightPx = 220f,
            windowWidthPx = 400f,
            windowHeightPx = 600f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 168f,
            preferredContentHeightPx = 96f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )

        assertThat(nearLineStart.offset.x).isEqualTo(36)
        assertThat(nearLineEnd.offset.x).isEqualTo(36)
        assertThat(nearLineStart.widthPx).isEqualTo(288f)
        assertThat(nearLineEnd.widthPx).isEqualTo(288f)
    }

    @Test
    fun resolve_fallsBackBelowWhenAboveSpaceCannotFitPopup() {
        val layout = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 120f,
            cursorLineTopInViewportPx = 40f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(40, 100),
            canvasWidthPx = 560f,
            canvasHeightPx = 300f,
            windowWidthPx = 700f,
            windowHeightPx = 700f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 244f,
            preferredContentHeightPx = 96f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(106, 170))
        assertThat(layout.widthPx).isEqualTo(340f)
        assertThat(layout.maxHeightPx).isEqualTo(96f)
    }

    @Test
    fun resolve_clampsFollowCursorModeToRightEdge() {
        val layout = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 590f,
            cursorLineTopInViewportPx = 40f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(10, 80),
            canvasWidthPx = 620f,
            canvasHeightPx = 280f,
            windowWidthPx = 900f,
            windowHeightPx = 700f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 244f,
            preferredContentHeightPx = 96f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(282, 150))
        assertThat(layout.widthPx).isEqualTo(340f)
        assertThat(layout.maxHeightPx).isEqualTo(96f)
    }

    @Test
    fun resolve_prefersLargerAboveSpaceWhenNeitherSideCanReachMinimumHeight() {
        val layout = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 120f,
            cursorLineTopInViewportPx = 68f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(40, 100),
            canvasWidthPx = 560f,
            canvasHeightPx = 146f,
            windowWidthPx = 700f,
            windowHeightPx = 700f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 244f,
            preferredContentHeightPx = 96f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(106, 108))
        assertThat(layout.widthPx).isEqualTo(340f)
        assertThat(layout.maxHeightPx).isEqualTo(50f)
    }

    @Test
    fun resolve_limitsHeightInsideImeVisibleArea() {
        val layout = SignatureHelpPopupLayoutResolver.resolve(
            cursorXInViewportPx = 120f,
            cursorLineTopInViewportPx = 70f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(40, 200),
            canvasWidthPx = 560f,
            canvasHeightPx = 240f,
            windowWidthPx = 700f,
            windowHeightPx = 520f,
            imeBottomInsetPx = 170f,
            preferredPopupWidthPx = 340f,
            popupMaxHeightPx = 244f,
            preferredContentHeightPx = 180f,
            marginPx = 8f,
            cursorGapPx = 10f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 68f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(106, 208))
        assertThat(layout.widthPx).isEqualTo(340f)
        assertThat(layout.maxHeightPx).isEqualTo(52f)
    }
}
