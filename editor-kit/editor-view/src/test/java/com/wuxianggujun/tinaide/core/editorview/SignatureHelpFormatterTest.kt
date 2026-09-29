package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignatureHelpFormatterTest {

    @Test
    fun resolveSignatureParameterRanges_shouldSplitSimpleSignature() {
        val signature = "foo(Int a, String b, Boolean c)"

        val ranges = resolveSignatureParameterRanges(signature)

        assertThat(ranges.map { signature.substring(it.start, it.end) })
            .containsExactly("Int a", "String b", "Boolean c")
            .inOrder()
    }

    @Test
    fun resolveSignatureParameterRanges_shouldIgnoreNestedCommas() {
        val signature = "foo(Map<String, List<Int>> value, (Int, String) -> Unit callback, String name)"

        val ranges = resolveSignatureParameterRanges(signature)

        assertThat(ranges.map { signature.substring(it.start, it.end) })
            .containsExactly(
                "Map<String, List<Int>> value",
                "(Int, String) -> Unit callback",
                "String name"
            )
            .inOrder()
    }

    @Test
    fun resolveSignatureParameterRanges_shouldReturnEmptyForParameterlessSignature() {
        assertThat(resolveSignatureParameterRanges("foo()")).isEmpty()
    }

    @Test
    fun resolveSignatureActiveParameterPreview_shouldReturnActiveParameterText() {
        val signature = "foo(Map<String, List<Int>> value, (Int, String) -> Unit callback, String name)"

        val preview = resolveSignatureActiveParameterPreview(
            signature = signature,
            activeParameter = 1
        )

        assertThat(preview).isEqualTo("(Int, String) -> Unit callback")
    }

    @Test
    fun resolveSignatureActiveParameterPreview_shouldReturnNullWhenParameterIsMissing() {
        assertThat(
            resolveSignatureActiveParameterPreview(
                signature = "foo()",
                activeParameter = 0
            )
        ).isNull()
    }
}
