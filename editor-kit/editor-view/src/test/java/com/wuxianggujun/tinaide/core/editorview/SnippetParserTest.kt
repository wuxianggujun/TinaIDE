package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnippetParserTest {

    @Test
    fun parseSnippet_shouldExpandChoiceAndSortTabstops() {
        val parsed = parseSnippet("fun \${1:name}(\${2|one,two|}) {\n    \$0\n}")

        assertThat(parsed.expandedText).isEqualTo("fun name(one) {\n    \n}")
        assertThat(parsed.placeholders.map { it.tabstopIndex })
            .containsExactly(1, 2, 0)
            .inOrder()
        assertThat(parsed.placeholders.map { it.offsetInText })
            .containsExactly(4, 9, 20)
            .inOrder()
        assertThat(parsed.placeholders.map { it.length })
            .containsExactly(4, 3, 0)
            .inOrder()
        assertThat(parsed.placeholders[1].choices)
            .containsExactly("one", "two")
            .inOrder()
    }

    @Test
    fun parseSnippet_shouldRestoreEscapedReservedChars() {
        val parsed = parseSnippet("val \\${'$'}name = \\} + \\\\ + ${'$'}{1:value}")

        assertThat(parsed.expandedText).isEqualTo("val ${'$'}name = } + \\ + value")
        assertThat(parsed.placeholders.map { it.tabstopIndex })
            .containsExactly(1)
    }
}
