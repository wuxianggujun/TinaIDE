package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignatureHelpRowPresentationTest {

    @Test
    fun resolveSignatureHelpRowPresentation_shouldHighlightSelectedAndActiveDifferently() {
        val selected = resolveSignatureHelpRowPresentation(
            isSelected = true,
            isServerActive = false,
            compactMode = false
        )
        val active = resolveSignatureHelpRowPresentation(
            isSelected = false,
            isServerActive = true,
            compactMode = false
        )
        val selectedActive = resolveSignatureHelpRowPresentation(
            isSelected = true,
            isServerActive = true,
            compactMode = false
        )

        assertThat(selected).isEqualTo(
            SignatureHelpRowPresentation(
                marker = SignatureHelpRowMarker.Selected,
                border = SignatureHelpRowBorder.Secondary,
                useSelectedBackground = true,
                maxLines = 4,
                showActiveParameterPreview = true,
                emphasizeActiveParameterPreview = false
            )
        )
        assertThat(active).isEqualTo(
            SignatureHelpRowPresentation(
                marker = SignatureHelpRowMarker.Active,
                border = SignatureHelpRowBorder.Accent,
                useSelectedBackground = false,
                maxLines = 2,
                showActiveParameterPreview = false,
                emphasizeActiveParameterPreview = false
            )
        )
        assertThat(selectedActive).isEqualTo(
            SignatureHelpRowPresentation(
                marker = SignatureHelpRowMarker.SelectedActive,
                border = SignatureHelpRowBorder.Accent,
                useSelectedBackground = true,
                maxLines = 4,
                showActiveParameterPreview = true,
                emphasizeActiveParameterPreview = true
            )
        )
    }

    @Test
    fun resolveSignatureHelpRowPresentation_shouldClampCompactModeLines() {
        val selected = resolveSignatureHelpRowPresentation(
            isSelected = true,
            isServerActive = false,
            compactMode = true
        )
        val regular = resolveSignatureHelpRowPresentation(
            isSelected = false,
            isServerActive = false,
            compactMode = true
        )

        assertThat(selected.maxLines).isEqualTo(3)
        assertThat(regular.maxLines).isEqualTo(1)
        assertThat(selected.showActiveParameterPreview).isTrue()
        assertThat(regular.showActiveParameterPreview).isFalse()
    }

    @Test
    fun resolveSignatureHelpRowPresentation_shouldOnlyEmphasizePreviewWhenSelectedAndActive() {
        val selectedOnly = resolveSignatureHelpRowPresentation(
            isSelected = true,
            isServerActive = false,
            compactMode = false
        )
        val selectedActive = resolveSignatureHelpRowPresentation(
            isSelected = true,
            isServerActive = true,
            compactMode = false
        )
        val activeOnly = resolveSignatureHelpRowPresentation(
            isSelected = false,
            isServerActive = true,
            compactMode = false
        )

        assertThat(selectedOnly.emphasizeActiveParameterPreview).isFalse()
        assertThat(selectedActive.emphasizeActiveParameterPreview).isTrue()
        assertThat(activeOnly.showActiveParameterPreview).isFalse()
    }
}
