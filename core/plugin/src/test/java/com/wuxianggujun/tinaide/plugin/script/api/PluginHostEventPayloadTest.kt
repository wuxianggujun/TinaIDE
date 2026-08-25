package com.wuxianggujun.tinaide.plugin.script.api

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import org.junit.Test

class PluginHostEventPayloadTest {
    @Test
    fun `selection payload is bounded and reports truncation`() {
        val payload = EditorSelectionPayload(
            text = "x".repeat(PluginHostEventDispatcher.MAX_SELECTION_TEXT_CHARS + 1),
            startLine = 0,
            startColumn = 0,
            endLine = 1,
            endColumn = 0,
        ).toMap()

        assertThat(payload["text"].toString()).hasLength(PluginHostEventDispatcher.MAX_SELECTION_TEXT_CHARS)
        assertThat(payload["textTruncated"]).isEqualTo(true)
    }

    @Test
    fun `diagnostics payload bounds every repeated string field`() {
        val oversized = "x".repeat(8 * 1024)
        val diagnostics = List(PluginHostEventDispatcher.MAX_DIAGNOSTICS_PER_EVENT + 1) {
            Diagnostic(
                fileUri = oversized,
                fileName = oversized,
                line = it,
                column = 0,
                message = oversized,
                severity = Diagnostic.Severity.ERROR,
                source = oversized,
                code = oversized,
            )
        }

        val payload = PluginHostEventDispatcher.diagnosticsPayload(oversized, diagnostics)
        @Suppress("UNCHECKED_CAST")
        val boundedDiagnostics = payload["diagnostics"] as List<Map<String, Any?>>

        assertThat(payload["diagnosticsTruncated"]).isEqualTo(true)
        assertThat(boundedDiagnostics).hasSize(PluginHostEventDispatcher.MAX_DIAGNOSTICS_PER_EVENT)
        assertThat(boundedDiagnostics.first()["fileUri"].toString())
            .hasLength(PluginHostEventDispatcher.MAX_DIAGNOSTIC_URI_CHARS)
        assertThat(boundedDiagnostics.first()["fileName"].toString())
            .hasLength(PluginHostEventDispatcher.MAX_DIAGNOSTIC_FILE_NAME_CHARS)
        assertThat(boundedDiagnostics.first()["message"].toString())
            .hasLength(PluginHostEventDispatcher.MAX_DIAGNOSTIC_MESSAGE_CHARS)
        assertThat(boundedDiagnostics.first()["source"].toString())
            .hasLength(PluginHostEventDispatcher.MAX_DIAGNOSTIC_METADATA_CHARS)
        assertThat(boundedDiagnostics.first()["code"].toString())
            .hasLength(PluginHostEventDispatcher.MAX_DIAGNOSTIC_METADATA_CHARS)
    }
}
