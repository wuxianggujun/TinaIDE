package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import org.junit.Test

/**
 * [normalizeSignatureHelpResult] / [normalizeSignatureHelpSelection] 纯函数测试。
 *
 * 覆盖：空输入、全空白签名被剔除、activeSignature/activeParameter 越界收敛、null 兜底。
 */
class EditorSignatureHelpNormalizationTest {

    @Test
    fun normalizeResult_returnsNull_whenInputIsNull() {
        assertThat(normalizeSignatureHelpResult(null)).isNull()
    }

    @Test
    fun normalizeResult_returnsNull_whenAllSignaturesBlank() {
        val result = SignatureHelpResult(
            signatures = listOf("", "   ", "\t"),
            activeSignature = 0,
            activeParameter = 0
        )

        assertThat(normalizeSignatureHelpResult(result)).isNull()
    }

    @Test
    fun normalizeResult_filtersBlankSignatures_andKeepsOrder() {
        val result = SignatureHelpResult(
            signatures = listOf("foo(Int)", "  ", "bar(String)"),
            activeSignature = 0,
            activeParameter = 0
        )

        val normalized = normalizeSignatureHelpResult(result)

        assertThat(normalized).isNotNull()
        assertThat(normalized!!.signatures).containsExactly("foo(Int)", "bar(String)").inOrder()
    }

    @Test
    fun normalizeResult_coercesActiveSignatureIntoBounds() {
        val tooHigh = SignatureHelpResult(
            signatures = listOf("a", "b"),
            activeSignature = 99,
            activeParameter = 0
        )
        val negative = SignatureHelpResult(
            signatures = listOf("a", "b"),
            activeSignature = -5,
            activeParameter = 0
        )

        // 保留两个签名（lastIndex = 1），所以 99 -> 1，-5 -> 0。
        assertThat(normalizeSignatureHelpResult(tooHigh)!!.activeSignature).isEqualTo(1)
        assertThat(normalizeSignatureHelpResult(negative)!!.activeSignature).isEqualTo(0)
    }

    @Test
    fun normalizeResult_coercesActiveSignatureAfterBlankFiltering() {
        // 过滤掉中间的空白签名后只剩 1 个，lastIndex = 0，原 activeSignature=2 应收敛到 0。
        val result = SignatureHelpResult(
            signatures = listOf("only(Int)", "   ", ""),
            activeSignature = 2,
            activeParameter = 0
        )

        val normalized = normalizeSignatureHelpResult(result)!!
        assertThat(normalized.signatures).containsExactly("only(Int)")
        assertThat(normalized.activeSignature).isEqualTo(0)
    }

    @Test
    fun normalizeResult_clampsNegativeActiveParameterToZero() {
        val result = SignatureHelpResult(
            signatures = listOf("foo(Int, String)"),
            activeSignature = 0,
            activeParameter = -3
        )

        assertThat(normalizeSignatureHelpResult(result)!!.activeParameter).isEqualTo(0)
    }

    @Test
    fun normalizeResult_keepsValidActiveParameter() {
        val result = SignatureHelpResult(
            signatures = listOf("foo(Int, String)"),
            activeSignature = 0,
            activeParameter = 5
        )

        // activeParameter 只做 coerceAtLeast(0)，不按参数个数封顶，保持原值。
        assertThat(normalizeSignatureHelpResult(result)!!.activeParameter).isEqualTo(5)
    }

    @Test
    fun normalizeSelection_returnsNull_whenResultIsNull() {
        assertThat(normalizeSignatureHelpSelection(null, 0)).isNull()
    }

    @Test
    fun normalizeSelection_returnsNull_whenSignaturesEmpty() {
        val emptyResult = SignatureHelpResult(
            signatures = emptyList(),
            activeSignature = 0,
            activeParameter = 0
        )

        assertThat(normalizeSignatureHelpSelection(emptyResult, 0)).isNull()
    }

    @Test
    fun normalizeSelection_returnsNull_whenSelectedIndexNull() {
        val result = SignatureHelpResult(
            signatures = listOf("a", "b"),
            activeSignature = 0,
            activeParameter = 0
        )

        assertThat(normalizeSignatureHelpSelection(result, null)).isNull()
    }

    @Test
    fun normalizeSelection_coercesSelectedIndexIntoBounds() {
        val result = SignatureHelpResult(
            signatures = listOf("a", "b", "c"),
            activeSignature = 0,
            activeParameter = 0
        )

        // lastIndex = 2
        assertThat(normalizeSignatureHelpSelection(result, 99)).isEqualTo(2)
        assertThat(normalizeSignatureHelpSelection(result, -7)).isEqualTo(0)
        assertThat(normalizeSignatureHelpSelection(result, 1)).isEqualTo(1)
    }
}
