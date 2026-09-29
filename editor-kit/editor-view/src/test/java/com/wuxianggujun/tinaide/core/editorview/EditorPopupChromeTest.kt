package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorPopupChromeTest {

    @Test
    fun popupChromeMetrics_shouldRemainAlignedWithPopupShellSpec() {
        assertThat(editorPopupCornerRadius).isEqualTo(8.dp)
        assertThat(editorPopupBorderWidth).isEqualTo(1.dp)
        assertThat(editorPopupElevation).isEqualTo(8.dp)
    }

    @Test
    fun resolveEditorPopupColors_shouldKeepDarkPopupSurfaceCloseToBackground() {
        val scheme = EditorColorScheme.builtinDark()

        val colors = resolveEditorPopupColors(scheme)

        assertThat(colors.primaryTextColor).isEqualTo(scheme.foreground)
        assertThat(colors.accentColor).isEqualTo(scheme.selectionHandle)
        assertThat(colors.matchTextColor)
            .isEqualTo(lerp(scheme.syntax.keyword, scheme.selectionHandle, 0.25f))
        assertThat(colors.containerColor.perceivedBrightness())
            .isGreaterThan(scheme.background.perceivedBrightness())
        assertThat(colors.selectedSurfaceColor).isNotEqualTo(colors.containerColor)
        assertThat(colors.borderColor.alpha).isGreaterThan(colors.dividerColor.alpha)
    }

    @Test
    fun resolveEditorPopupColors_shouldKeepLightPopupSurfaceSubtle() {
        val scheme = EditorColorScheme.builtinLight()

        val colors = resolveEditorPopupColors(scheme)

        assertThat(colors.primaryTextColor).isEqualTo(scheme.foreground)
        assertThat(colors.accentColor).isEqualTo(scheme.selectionHandle)
        assertThat(colors.containerColor.perceivedBrightness())
            .isLessThan(scheme.background.perceivedBrightness())
        assertThat(colors.secondaryTextColor.alpha).isLessThan(1f)
        assertThat(colors.progressTrackColor).isNotEqualTo(colors.containerColor)
        assertThat(colors.borderColor.alpha).isGreaterThan(colors.dividerColor.alpha)
    }

    private fun androidx.compose.ui.graphics.Color.perceivedBrightness(): Float = (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
}
