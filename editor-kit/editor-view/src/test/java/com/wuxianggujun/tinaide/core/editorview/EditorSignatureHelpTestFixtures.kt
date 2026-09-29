package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult

internal fun EditorState.seedVisibleSignatureHelp(
    result: SignatureHelpResult = SignatureHelpResult(
        signatures = listOf("foo(Int value)", "foo(String value)"),
        activeSignature = 0,
        activeParameter = 0
    ),
    requestId: Long = 1L,
    selectedIndex: Int? = null
) {
    publishSignatureHelpVisible(
        result = result,
        requestId = requestId,
        selectedIndex = selectedIndex
    )
}

internal fun EditorState.seedLoadingSignatureHelp(
    previousResult: SignatureHelpResult? = SignatureHelpResult(
        signatures = listOf("foo(Int value)", "foo(String value)"),
        activeSignature = 0,
        activeParameter = 0
    ),
    requestId: Long = 1L,
    selectedIndex: Int? = null
) {
    publishSignatureHelpLoading(
        previousResult = previousResult,
        requestId = requestId,
        selectedIndex = selectedIndex
    )
}
