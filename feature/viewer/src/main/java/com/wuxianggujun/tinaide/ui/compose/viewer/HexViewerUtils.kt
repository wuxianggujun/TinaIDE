package com.wuxianggujun.tinaide.ui.compose.viewer

import androidx.compose.ui.unit.dp

/**
 * Hex viewer layout constants and pure helpers.
 */
internal fun String.compactForAnalysisPanel(): String = if (length <= ANALYSIS_PANEL_STRING_LIMIT) {
    this
} else {
    take(ANALYSIS_PANEL_STRING_LIMIT) + "..."
}

internal fun List<HexReverseAction>.actionContent(kind: HexReverseActionKind): String =
    first { action -> action.kind == kind }.content

internal fun String.toShortHashPreview(): String = if (length <= HASH_PREVIEW_LENGTH) {
    this
} else {
    take(HASH_PREVIEW_LENGTH)
}

internal fun Int.toHexByteLabel(): String = "0x%02X".format(this and 0xFF)

internal fun Long.percentOf(total: Long): Double = if (total <= 0L) {
    0.0
} else {
    toDouble() * 100.0 / total.toDouble()
}

internal fun computeHexColumn(
    tapX: Float,
    totalWidth: Float,
    dividerWidth: Float,
    byteCount: Int
): Int {
    if (byteCount <= 0 || totalWidth <= 0f) return -1
    val cellWidth = (totalWidth - dividerWidth) / HexFileDataManager.VISUAL_BYTES_PER_ROW
    if (tapX < cellWidth * 4) {
        return (tapX / cellWidth).toInt().coerceIn(0, minOf(3, byteCount - 1))
    }
    if (tapX < cellWidth * 4 + dividerWidth) return 3.coerceAtMost(byteCount - 1)
    val rightSideX = tapX - cellWidth * 4 - dividerWidth
    return (4 + (rightSideX / cellWidth).toInt()).coerceIn(4, minOf(7, byteCount - 1))
}

internal val AddressColumnWidth = 84.dp
internal val AsciiColumnWidth = 104.dp
internal val HexDockedAnalysisPanelWidth = 360.dp
internal const val MAX_GOTO_HISTORY = 64
internal const val MAX_ANALYSIS_PANEL_ITEMS = 5
internal const val MAX_WORKBENCH_FINDING_PANEL_ITEMS = 6
internal const val ANALYSIS_PANEL_STRING_LIMIT = 48
internal const val HASH_PREVIEW_LENGTH = 12
