package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommandLineArgumentsTest {
    @Test
    fun parse_preservesQuotedWhitespaceAndEmptyArguments() {
        val arguments = CommandLineArguments.parse(
            "--name \"Tina IDE\" '' plain"
        )

        assertThat(arguments).containsExactly("--name", "Tina IDE", "", "plain").inOrder()
    }

    @Test
    fun parse_supportsEscapedCharacters() {
        val arguments = CommandLineArguments.parse(
            "one\\ two \"three\\\"four\" path\\\\end"
        )

        assertThat(arguments).containsExactly("one two", "three\"four", "path\\end").inOrder()
    }

    @Test
    fun parse_keepsTrailingBackslashAndUnclosedQuoteContent() {
        assertThat(CommandLineArguments.parse("\"hello world")).containsExactly("hello world")
        assertThat(CommandLineArguments.parse("value\\")).containsExactly("value\\")
    }
}
