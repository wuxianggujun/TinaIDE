package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompletionPopupLayoutResolverTest {

    @Test
    fun resolve_translatesViewportPositionIntoWindowCoordinates() {
        val layout = CompletionPopupLayoutResolver.resolve(
            cursorXInViewportPx = 60f,
            cursorLineTopInViewportPx = 40f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 100),
            canvasWidthPx = 640f,
            canvasHeightPx = 320f,
            windowWidthPx = 800f,
            windowHeightPx = 600f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 300f,
            popupMaxHeightPx = 280f,
            itemCount = 4,
            itemHeightPx = 45f,
            loadingIndicatorHeightPx = 20f,
            isLoading = false,
            marginPx = 8f,
            cursorGapPx = 16f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 96f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(96, 160))
        assertThat(layout.widthPx).isEqualTo(300f)
        assertThat(layout.maxHeightPx).isEqualTo(180f)
    }

    @Test
    fun resolve_usesWidePanelModeForNarrowEditor() {
        val layout = CompletionPopupLayoutResolver.resolve(
            cursorXInViewportPx = 200f,
            cursorLineTopInViewportPx = 24f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(40, 120),
            canvasWidthPx = 320f,
            canvasHeightPx = 260f,
            windowWidthPx = 480f,
            windowHeightPx = 800f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 300f,
            popupMaxHeightPx = 280f,
            itemCount = 3,
            itemHeightPx = 45f,
            loadingIndicatorHeightPx = 20f,
            isLoading = true,
            marginPx = 8f,
            cursorGapPx = 16f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 96f
        )

        assertThat(layout.widthPx).isEqualTo(280f)
        assertThat(layout.offset.x).isEqualTo(60)
        assertThat(layout.maxHeightPx).isEqualTo(155f)
    }

    @Test
    fun resolve_keepsWidePanelCenteredRegardlessOfCursorColumn() {
        val nearLineStart = CompletionPopupLayoutResolver.resolve(
            cursorXInViewportPx = 24f,
            cursorLineTopInViewportPx = 24f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 120),
            canvasWidthPx = 320f,
            canvasHeightPx = 260f,
            windowWidthPx = 480f,
            windowHeightPx = 800f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 300f,
            popupMaxHeightPx = 280f,
            itemCount = 3,
            itemHeightPx = 45f,
            loadingIndicatorHeightPx = 20f,
            isLoading = false,
            marginPx = 8f,
            cursorGapPx = 16f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 96f
        )
        val nearLineEnd = CompletionPopupLayoutResolver.resolve(
            cursorXInViewportPx = 280f,
            cursorLineTopInViewportPx = 24f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 120),
            canvasWidthPx = 320f,
            canvasHeightPx = 260f,
            windowWidthPx = 480f,
            windowHeightPx = 800f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 300f,
            popupMaxHeightPx = 280f,
            itemCount = 3,
            itemHeightPx = 45f,
            loadingIndicatorHeightPx = 20f,
            isLoading = false,
            marginPx = 8f,
            cursorGapPx = 16f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 96f
        )

        assertThat(nearLineStart.offset.x).isEqualTo(40)
        assertThat(nearLineEnd.offset.x).isEqualTo(40)
        assertThat(nearLineStart.widthPx).isEqualTo(280f)
        assertThat(nearLineEnd.widthPx).isEqualTo(280f)
    }

    @Test
    fun resolve_keepsPopupBelowWhenRemainingSpaceIsComparableToAbove() {
        val layout = CompletionPopupLayoutResolver.resolve(
            cursorXInViewportPx = 60f,
            cursorLineTopInViewportPx = 88f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 100),
            canvasWidthPx = 640f,
            canvasHeightPx = 180f,
            windowWidthPx = 800f,
            windowHeightPx = 600f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 300f,
            popupMaxHeightPx = 280f,
            itemCount = 4,
            itemHeightPx = 45f,
            loadingIndicatorHeightPx = 20f,
            isLoading = false,
            marginPx = 8f,
            cursorGapPx = 16f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 96f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(96, 208))
        assertThat(layout.widthPx).isEqualTo(300f)
        assertThat(layout.maxHeightPx).isEqualTo(64f)
    }

    @Test
    fun resolve_clampsFollowCursorModeToRightEdge() {
        val layout = CompletionPopupLayoutResolver.resolve(
            cursorXInViewportPx = 560f,
            cursorLineTopInViewportPx = 30f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(10, 80),
            canvasWidthPx = 620f,
            canvasHeightPx = 280f,
            windowWidthPx = 900f,
            windowHeightPx = 700f,
            imeBottomInsetPx = 0f,
            preferredPopupWidthPx = 300f,
            popupMaxHeightPx = 280f,
            itemCount = 8,
            itemHeightPx = 45f,
            loadingIndicatorHeightPx = 20f,
            isLoading = false,
            marginPx = 8f,
            cursorGapPx = 16f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 96f
        )

        assertThat(layout.offset.x).isEqualTo(322)
        assertThat(layout.widthPx).isEqualTo(300f)
    }

    @Test
    fun resolve_placesPopupAboveWhenImeLeavesTooLittleRoomBelow() {
        val layout = CompletionPopupLayoutResolver.resolve(
            cursorXInViewportPx = 100f,
            cursorLineTopInViewportPx = 200f,
            lineHeightPx = 20f,
            canvasOriginInWindowPx = IntOffset(20, 220),
            canvasWidthPx = 420f,
            canvasHeightPx = 260f,
            windowWidthPx = 500f,
            windowHeightPx = 760f,
            imeBottomInsetPx = 180f,
            preferredPopupWidthPx = 300f,
            popupMaxHeightPx = 280f,
            itemCount = 5,
            itemHeightPx = 45f,
            loadingIndicatorHeightPx = 20f,
            isLoading = false,
            marginPx = 8f,
            cursorGapPx = 16f,
            narrowEditorThresholdPx = 500f,
            minPopupHeightPx = 96f
        )

        assertThat(layout.offset).isEqualTo(IntOffset(46, 228))
        assertThat(layout.widthPx).isEqualTo(367.5f)
        assertThat(layout.maxHeightPx).isEqualTo(192f)
    }
}
