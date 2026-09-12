package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.URI
import org.junit.Test

/**
 * Non-ASCII document URIs: clangd percent-encodes, `File.toURI()` does not.
 * `canonicalizeLspDocumentUri` / `toLspDocumentUri` collapse both forms.
 */
class LspDocumentUriNonAsciiTest {

    private val posixPath = "/storage/emulated/0/proj/中文.cpp"

    /** What LspEditorManager sends: `File.toURI().toString()`. */
    private val clientUri = URI("file", null, posixPath, null).toString()

    /** What clangd publishes back for the same file. */
    private val serverUri = "file:///storage/emulated/0/proj/%E4%B8%AD%E6%96%87.cpp"

    @Test
    fun `ascii uris survive the client server round trip`() {
        val asciiPath = "/storage/emulated/0/proj/main.cpp"
        val client = URI("file", null, asciiPath, null).toString()
        val server = "file://$asciiPath"

        assertThat(lspDocumentUrisEquivalent(client, server)).isTrue()
    }

    @Test
    fun `raw File toURI keeps non ascii literal while clangd percent encodes`() {
        assertThat(clientUri).contains("中文")
        assertThat(clientUri).doesNotContain("%E4%B8%AD")
        assertThat(serverUri).contains("%E4%B8%AD%E6%96%87")
    }

    @Test
    fun `toLspDocumentUri emits the percent encoded form`() {
        val uri = File(posixPath).toLspDocumentUri()

        assertThat(uri).doesNotContain("中文")
        assertThat(uri).contains("%E4%B8%AD%E6%96%87")
    }

    @Test
    fun `lspDocumentUriToFilePath decodes both uri forms`() {
        assertThat(lspDocumentUriToFilePath(serverUri)).isEqualTo(posixPath)
        assertThat(lspDocumentUriToFilePath(clientUri)).isEqualTo(posixPath)
    }

    @Test
    fun `non ascii uris are equivalent across client and server forms`() {
        assertThat(lspDocumentUrisEquivalent(clientUri, serverUri)).isTrue()
        assertThat(canonicalizeLspDocumentUri(clientUri))
            .isEqualTo(canonicalizeLspDocumentUri(serverUri))
    }

    @Test
    fun `canonical form is percent encoded so servers see RFC 3986 uris`() {
        assertThat(canonicalizeLspDocumentUri(clientUri))
            .isEqualTo("file:///storage/emulated/0/proj/%E4%B8%AD%E6%96%87.cpp")
    }

    @Test
    fun `canonicalize is idempotent for non ascii paths`() {
        val once = canonicalizeLspDocumentUri(clientUri)

        assertThat(canonicalizeLspDocumentUri(once)).isEqualTo(once)
    }

    @Test
    fun `lowercase server escapes still match the canonical form`() {
        val lowercased = "file:///storage/emulated/0/proj/%e4%b8%ad%e6%96%87.cpp"

        assertThat(lspDocumentUrisEquivalent(clientUri, lowercased)).isTrue()
    }

    @Test
    fun `both uri forms decode to the same filesystem path`() {
        assertThat(URI(clientUri).path).isEqualTo(posixPath)
        assertThat(URI(serverUri).path).isEqualTo(posixPath)
    }

    @Test
    fun `percent encoding the client uri makes the forms equivalent`() {
        val encoded = URI(clientUri).toASCIIString()

        assertThat(lspDocumentUrisEquivalent(encoded, serverUri)).isTrue()
    }

    @Test
    fun `removePrefix fallback yields a percent encoded path that does not exist`() {
        val fallbackPath = serverUri.removePrefix("file://")

        assertThat(fallbackPath).isEqualTo("/storage/emulated/0/proj/%E4%B8%AD%E6%96%87.cpp")
        assertThat(File(fallbackPath).path).isNotEqualTo(posixPath)
    }
}
