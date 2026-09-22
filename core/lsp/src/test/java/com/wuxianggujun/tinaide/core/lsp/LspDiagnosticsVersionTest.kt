package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import java.net.URI
import org.junit.Test

class LspDiagnosticsVersionTest {

    @Test
    fun `unversioned diagnostics remain compatible`() {
        assertThat(
            acceptsDiagnosticsVersion(
                currentDocumentUri = "file:///project/main.cpp",
                currentDocumentVersion = 3,
                publishedUri = "file:///project/main.cpp",
                publishedVersion = null,
            ),
        ).isTrue()

        assertThat(
            acceptsDiagnosticsVersion(
                currentDocumentUri = "file:///project/main.cpp",
                currentDocumentVersion = 3,
                publishedUri = "file:///project/previous.cpp",
                publishedVersion = null,
            ),
        ).isFalse()
    }

    @Test
    fun `only current document version is accepted`() {
        val currentUri = "file:///project/main.cpp"

        assertThat(
            acceptsDiagnosticsVersion(
                currentDocumentUri = currentUri,
                currentDocumentVersion = 3,
                publishedUri = currentUri,
                publishedVersion = 2,
            ),
        ).isFalse()
        assertThat(
            acceptsDiagnosticsVersion(
                currentDocumentUri = currentUri,
                currentDocumentVersion = 3,
                publishedUri = currentUri,
                publishedVersion = 3,
            ),
        ).isTrue()
        assertThat(
            acceptsDiagnosticsVersion(
                currentDocumentUri = currentUri,
                currentDocumentVersion = 3,
                publishedUri = "file:///project/other.cpp",
                publishedVersion = 3,
            ),
        ).isFalse()
    }

    @Test
    fun `equivalent local file URI forms identify the same document`() {
        assertThat(
            acceptsDiagnosticsVersion(
                currentDocumentUri = "file:/data/project/main.cpp",
                currentDocumentVersion = 3,
                publishedUri = "file:///data/project/main.cpp",
                publishedVersion = 3,
            ),
        ).isTrue()
        assertThat(canonicalizeLspDocumentUri("file:/data/project/./src/../main.cpp"))
            .isEqualTo("file:///data/project/main.cpp")
        assertThat(canonicalizeLspDocumentUri("file:/c:/project/main.cpp"))
            .isEqualTo("file:///C:/project/main.cpp")
        assertThat(canonicalizeLspDocumentUri("file://SERVER/share/project/main.cpp"))
            .isEqualTo("file://server/share/project/main.cpp")
        assertThat(canonicalizeLspDocumentUri("file:////SERVER/share/project/main.cpp"))
            .isEqualTo("file://server/share/project/main.cpp")
    }

    @Test
    fun `non ascii client and clangd uri forms identify the same document`() {
        val clientUri = URI("file", null, "/storage/emulated/0/proj/中文.cpp", null).toString()
        val serverUri = "file:///storage/emulated/0/proj/%E4%B8%AD%E6%96%87.cpp"

        assertThat(
            acceptsDiagnosticsVersion(
                currentDocumentUri = clientUri,
                currentDocumentVersion = 1,
                publishedUri = serverUri,
                publishedVersion = 1,
            ),
        ).isTrue()
    }
}
