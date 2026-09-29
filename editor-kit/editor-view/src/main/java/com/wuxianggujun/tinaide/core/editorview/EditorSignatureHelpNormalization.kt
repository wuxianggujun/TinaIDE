package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult

/**
 * 签名帮助结果的归一化纯函数。
 *
 * 从 [EditorState] 抽出：剔除空白签名、把 activeSignature/activeParameter/选中索引
 * 收敛到合法范围。不持有任何可变状态，行为与原内联实现完全一致。
 */
internal fun normalizeSignatureHelpResult(
    result: SignatureHelpResult?
): SignatureHelpResult? {
    val signatures = result?.signatures.orEmpty().filter { it.isNotBlank() }
    if (signatures.isEmpty()) return null
    return SignatureHelpResult(
        signatures = signatures,
        activeSignature = result?.activeSignature?.coerceIn(0, signatures.lastIndex) ?: 0,
        activeParameter = result?.activeParameter?.coerceAtLeast(0) ?: 0
    )
}

internal fun normalizeSignatureHelpSelection(
    result: SignatureHelpResult?,
    selectedIndex: Int?
): Int? {
    val normalizedResult = result ?: return null
    if (normalizedResult.signatures.isEmpty()) return null
    return selectedIndex?.coerceIn(0, normalizedResult.signatures.lastIndex)
}
