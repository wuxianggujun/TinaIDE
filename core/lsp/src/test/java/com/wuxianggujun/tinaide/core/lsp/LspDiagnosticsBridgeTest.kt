package com.wuxianggujun.tinaide.core.lsp

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.eclipse.lsp4j.DiagnosticCodeDescription
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LspDiagnosticsBridgeTest {

    @Test
    fun `publish preserves diagnostic metadata`() {
        var published: List<Diagnostic>? = null
        val bridge = LspDiagnosticsBridge { _, diagnostics -> published = diagnostics }
        val protocolData = mapOf("fixId" to "remove-unused-declaration")
        val source = org.eclipse.lsp4j.Diagnostic().apply {
            range = Range(Position(2, 4), Position(2, 9))
            message = "unused declaration"
            severity = DiagnosticSeverity.Warning
            this.source = "clangd"
            code = Either.forRight(1234)
            codeDescription = DiagnosticCodeDescription(
                "https://clang.llvm.org/docs/DiagnosticsReference.html",
            )
            tags = listOf(DiagnosticTag.Unnecessary, DiagnosticTag.Deprecated)
            data = protocolData
            relatedInformation = listOf(
                DiagnosticRelatedInformation(
                    Location(
                        "file:///project/header.hpp",
                        Range(Position(7, 1), Position(8, 3)),
                    ),
                    "declared here",
                ),
            )
        }

        bridge.publish("file:///project/main.cpp", "main.cpp", listOf(source))
        shadowOf(Looper.getMainLooper()).idle()

        val diagnostic = published.orEmpty().single()
        assertThat(diagnostic.code).isEqualTo("1234")
        assertThat(diagnostic.codeDescriptionUri)
            .isEqualTo("https://clang.llvm.org/docs/DiagnosticsReference.html")
        assertThat(diagnostic.tags)
            .containsExactly(Diagnostic.Tag.UNNECESSARY, Diagnostic.Tag.DEPRECATED)
            .inOrder()
        assertThat(diagnostic.relatedInformation).containsExactly(
            Diagnostic.RelatedInformation(
                fileUri = "file:///project/header.hpp",
                line = 7,
                column = 1,
                endLine = 8,
                endColumn = 3,
                message = "declared here",
            ),
        )
        assertThat(diagnostic.data).isSameInstanceAs(protocolData)
    }

    @Test
    fun `publish normalizes reversed related information range`() {
        var published: List<Diagnostic>? = null
        val bridge = LspDiagnosticsBridge { _, diagnostics -> published = diagnostics }
        val source = org.eclipse.lsp4j.Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 1))
            message = "diagnostic"
            relatedInformation = listOf(
                DiagnosticRelatedInformation(
                    Location(
                        "file:///project/main.cpp",
                        Range(Position(5, 8), Position(4, 2)),
                    ),
                    "related",
                ),
            )
        }

        bridge.publish("file:///project/main.cpp", "main.cpp", listOf(source))
        shadowOf(Looper.getMainLooper()).idle()

        val related = published.orEmpty().single().relatedInformation.single()
        assertThat(related.line).isEqualTo(5)
        assertThat(related.column).isEqualTo(8)
        assertThat(related.endLine).isEqualTo(5)
        assertThat(related.endColumn).isEqualTo(8)
    }

    @Test
    fun `publish drops snapshot invalidated before main thread delivery`() {
        var accepted = false
        var published: List<Diagnostic>? = null
        val bridge = LspDiagnosticsBridge { _, diagnostics -> published = diagnostics }
        val source = org.eclipse.lsp4j.Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 1))
            message = "stale diagnostic"
        }

        bridge.publish(
            fileUri = "file:///project/main.cpp",
            fileName = "main.cpp",
            data = listOf(source),
            commitIfCurrent = { _ -> false },
            onAccepted = { accepted = true },
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(accepted).isFalse()
        assertThat(published).isNull()
    }
}
