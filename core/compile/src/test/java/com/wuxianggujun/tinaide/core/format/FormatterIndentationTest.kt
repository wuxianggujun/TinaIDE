package com.wuxianggujun.tinaide.core.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormatterIndentationTest {
    @Test
    fun resolvedConfig_handlesIndependentWidthsAndEveryTabPolicy() {
        for (useTab in listOf("Never", "ForIndentation", "ForContinuationAndIndentation", "AlignWithSpaces", "Always")) {
            val resolved = FormatterIndentation.fromResolvedConfig(
                "Language: Cpp\nIndentWidth: 2\nTabWidth: 8\nUseTab: $useTab\n"
            )
            assertThat(resolved).isEqualTo(FormatterIndentation(2, 8, useTab == "Never"))
        }
    }

    @Test
    fun resolvedConfig_ignoresNestedKeys() {
        val resolved = FormatterIndentation.fromResolvedConfig(
            "IndentWidth: 4\nTabWidth: 8\nUseTab: Never\nNested:\n  IndentWidth: 99\n"
        )
        assertThat(resolved).isEqualTo(FormatterIndentation(4, 8, true))
    }

    @Test
    fun projectContinuationWidth_isNotConfusedWithBlockIndentation() {
        val resolved = FormatterIndentation.fromResolvedConfig(
            "IndentWidth: 2\nTabWidth: 8\nContinuationIndentWidth: 4\nUseTab: Never\n"
        )
        assertThat(resolved).isEqualTo(FormatterIndentation(2, 8, true, continuationIndentWidth = 4))
    }

    @Test
    fun malformedOrExcessiveWidths_areNotAppliedToAnEditor() {
        for (config in listOf(
            "IndentWidth: -1\nTabWidth: 8\nUseTab: Never",
            "IndentWidth: 4\nTabWidth: 0\nUseTab: Never",
            "IndentWidth: 99999999\nTabWidth: 8\nUseTab: Never",
            "IndentWidth: 4\nTabWidth: 8\nUseTab: Unknown",
            "IndentWidth: 4\nUseTab: Never",
        )) {
            assertThat(FormatterIndentation.fromResolvedConfig(config)).isNull()
        }
    }

    @Test
    fun zeroIndentWidth_isSupportedWithoutInventingAnIndentLevel() {
        assertThat(FormatterIndentation.fromResolvedConfig("IndentWidth: 0\nTabWidth: 8\nUseTab: Never"))
            .isEqualTo(FormatterIndentation(0, 8, true))
    }
}
