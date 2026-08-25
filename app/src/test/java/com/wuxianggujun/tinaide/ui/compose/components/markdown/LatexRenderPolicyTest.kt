package com.wuxianggujun.tinaide.ui.compose.components.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LatexRenderPolicyTest {
    @Test
    fun `ordinary formula is accepted and delimiters are removed`() {
        assertThat(LatexRenderPolicy.isSafeToRender("$$\\frac{a}{b}$$")).isTrue()
        assertThat(LatexRenderPolicy.prepareForRendering("$$\\frac{a}{b}$$")).isEqualTo("\\frac{a}{b}")
    }

    @Test
    fun `oversized or deeply nested formula is rejected`() {
        val oversized = "x".repeat(LatexRenderPolicy.MAX_SOURCE_CHARS + 1)
        val deeplyNested = "{".repeat(65) + "x" + "}".repeat(65)

        assertThat(LatexRenderPolicy.isSafeToRender(oversized)).isFalse()
        assertThat(LatexRenderPolicy.isSafeToRender(deeplyNested)).isFalse()
        assertThat(LatexRenderPolicy.prepareForRendering(oversized)).isNull()
    }

    @Test
    fun `macro definitions and unbalanced braces are rejected`() {
        assertThat(LatexRenderPolicy.isSafeToRender("\\newcommand{\\x}{x}\\x")).isFalse()
        assertThat(LatexRenderPolicy.isSafeToRender("\\frac{a}{b")).isFalse()
    }

    @Test
    fun `utf8 byte limit is enforced independently from character limit`() {
        assertThat(LatexRenderPolicy.isSafeToRender("界".repeat(1_500))).isFalse()
    }

    @Test
    fun `fallback text is bounded`() {
        val oversized = "x".repeat(LatexRenderPolicy.MAX_SOURCE_CHARS + 100)

        assertThat(LatexRenderPolicy.fallbackText(oversized).length)
            .isEqualTo(LatexRenderPolicy.MAX_SOURCE_CHARS + 3)
    }
}
