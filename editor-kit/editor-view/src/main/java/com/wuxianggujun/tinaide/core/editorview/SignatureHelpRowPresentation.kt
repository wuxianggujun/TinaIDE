package com.wuxianggujun.tinaide.core.editorview

internal enum class SignatureHelpRowMarker {
    None,
    Selected,
    Active,
    SelectedActive
}

internal enum class SignatureHelpRowBorder {
    None,
    Secondary,
    Accent
}

internal data class SignatureHelpRowPresentation(
    val marker: SignatureHelpRowMarker,
    val border: SignatureHelpRowBorder,
    val useSelectedBackground: Boolean,
    val maxLines: Int,
    val showActiveParameterPreview: Boolean,
    val emphasizeActiveParameterPreview: Boolean
)

internal fun resolveSignatureHelpRowPresentation(
    isSelected: Boolean,
    isServerActive: Boolean,
    compactMode: Boolean
): SignatureHelpRowPresentation {
    val maxLines = when {
        compactMode && isSelected -> 3
        compactMode -> 1
        isSelected -> 4
        else -> 2
    }

    return when {
        isSelected && isServerActive -> SignatureHelpRowPresentation(
            marker = SignatureHelpRowMarker.SelectedActive,
            border = SignatureHelpRowBorder.Accent,
            useSelectedBackground = true,
            maxLines = maxLines,
            showActiveParameterPreview = true,
            emphasizeActiveParameterPreview = true
        )

        isSelected -> SignatureHelpRowPresentation(
            marker = SignatureHelpRowMarker.Selected,
            border = SignatureHelpRowBorder.Secondary,
            useSelectedBackground = true,
            maxLines = maxLines,
            showActiveParameterPreview = true,
            emphasizeActiveParameterPreview = false
        )

        isServerActive -> SignatureHelpRowPresentation(
            marker = SignatureHelpRowMarker.Active,
            border = SignatureHelpRowBorder.Accent,
            useSelectedBackground = false,
            maxLines = maxLines,
            showActiveParameterPreview = false,
            emphasizeActiveParameterPreview = false
        )

        else -> SignatureHelpRowPresentation(
            marker = SignatureHelpRowMarker.None,
            border = SignatureHelpRowBorder.None,
            useSelectedBackground = false,
            maxLines = maxLines,
            showActiveParameterPreview = false,
            emphasizeActiveParameterPreview = false
        )
    }
}
