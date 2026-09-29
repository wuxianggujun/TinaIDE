package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel

internal enum class SignatureHelpAutoRefreshAction {
    Ignore,
    Refresh,
    Dismiss
}

internal fun resolveSignatureHelpAutoRefreshAction(
    isVisible: Boolean,
    hasSelection: Boolean,
    hasActiveContext: Boolean,
    allowAutoRefresh: Boolean,
    suppressForRecentEdit: Boolean
): SignatureHelpAutoRefreshAction {
    if (!isVisible) return SignatureHelpAutoRefreshAction.Ignore
    if (hasSelection || !hasActiveContext) return SignatureHelpAutoRefreshAction.Dismiss
    if (!allowAutoRefresh || suppressForRecentEdit) return SignatureHelpAutoRefreshAction.Ignore
    return SignatureHelpAutoRefreshAction.Refresh
}

internal fun hasActiveSignatureHelpContext(
    textBuffer: TextBuffer,
    cursorOffset: Int
): Boolean = TextScanKernel.hasActiveSignatureHelpContext(textBuffer, cursorOffset)
