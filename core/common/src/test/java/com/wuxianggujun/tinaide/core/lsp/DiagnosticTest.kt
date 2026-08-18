package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticTest {

    @Test
    fun `metadata defaults keep existing diagnostic construction compatible`() {
        val diagnostic = Diagnostic(
            fileUri = "file:///project/main.cpp",
            fileName = "main.cpp",
            line = 2,
            column = 4,
            message = "unused variable",
            severity = Diagnostic.Severity.WARNING,
        )

        assertThat(diagnostic.codeDescriptionUri).isNull()
        assertThat(diagnostic.tags).isEmpty()
        assertThat(diagnostic.relatedInformation).isEmpty()
        assertThat(diagnostic.data).isNull()
    }

    @Test
    fun `related information keeps its complete source range`() {
        val related = Diagnostic.RelatedInformation(
            fileUri = "file:///project/header.hpp",
            line = 6,
            column = 2,
            endLine = 7,
            endColumn = 9,
            message = "declared here",
        )

        assertThat(related.fileUri).isEqualTo("file:///project/header.hpp")
        assertThat(related.line).isEqualTo(6)
        assertThat(related.column).isEqualTo(2)
        assertThat(related.endLine).isEqualTo(7)
        assertThat(related.endColumn).isEqualTo(9)
        assertThat(related.message).isEqualTo("declared here")
    }
}
