package com.wuxianggujun.tinaide.core.editorview

internal data class SignatureHelpVisibleSlice(
    val startIndex: Int,
    val endExclusive: Int,
    val hiddenBefore: Int,
    val hiddenAfter: Int
)

internal fun resolveSignatureHelpVisibleSlice(
    totalCount: Int,
    selectedIndex: Int,
    maxVisibleItems: Int
): SignatureHelpVisibleSlice {
    if (totalCount <= 0) {
        return SignatureHelpVisibleSlice(
            startIndex = 0,
            endExclusive = 0,
            hiddenBefore = 0,
            hiddenAfter = 0
        )
    }

    val safeVisibleItems = maxVisibleItems.coerceAtLeast(1)
    if (totalCount <= safeVisibleItems) {
        return SignatureHelpVisibleSlice(
            startIndex = 0,
            endExclusive = totalCount,
            hiddenBefore = 0,
            hiddenAfter = 0
        )
    }

    val safeSelectedIndex = selectedIndex.coerceIn(0, totalCount - 1)
    val beforeCount = (safeVisibleItems - 1) / 2
    val maxStart = totalCount - safeVisibleItems
    val startIndex = (safeSelectedIndex - beforeCount).coerceIn(0, maxStart)
    val endExclusive = startIndex + safeVisibleItems

    return SignatureHelpVisibleSlice(
        startIndex = startIndex,
        endExclusive = endExclusive,
        hiddenBefore = startIndex,
        hiddenAfter = totalCount - endExclusive
    )
}
