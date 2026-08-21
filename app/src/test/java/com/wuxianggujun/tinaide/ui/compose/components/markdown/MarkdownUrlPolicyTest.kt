package com.wuxianggujun.tinaide.ui.compose.components.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownUrlPolicyTest {
    @Test
    fun `links allow only absolute web urls`() {
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("https://example.com/docs")).isNotNull()
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("http://example.com/docs")).isNotNull()
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("javascript:alert(1)")).isNull()
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("file:///data/local/tmp/source.cpp")).isNull()
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("content://provider/private")).isNull()
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("/relative/path")).isNull()
    }

    @Test
    fun `links reject credentials and control characters`() {
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("https://user@example.com/path")).isNull()
        assertThat(MarkdownUrlPolicy.safeLinkUrlOrNull("https://example.com/\npath")).isNull()
    }

    @Test
    fun `images require https and reject local destinations`() {
        assertThat(MarkdownUrlPolicy.safeImageUrlOrNull("https://cdn.example.com/image.png")).isNotNull()
        assertThat(MarkdownUrlPolicy.safeImageUrlOrNull("http://cdn.example.com/image.png")).isNull()
        assertThat(MarkdownUrlPolicy.safeImageUrlOrNull("https://localhost/image.png")).isNull()
        assertThat(MarkdownUrlPolicy.safeImageUrlOrNull("https://service.internal/image.png")).isNull()
        assertThat(MarkdownUrlPolicy.safeImageUrlOrNull("https://127.0.0.1/image.png")).isNull()
        assertThat(MarkdownUrlPolicy.safeImageUrlOrNull("https://[::1]/image.png")).isNull()
        assertThat(MarkdownUrlPolicy.safeImageUrlOrNull("content://provider/image.png")).isNull()
    }
}
