package com.wuxianggujun.tinaide.ui.compose.viewer

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LargeTextPagerTest {

    @Test
    fun readNextRows_splitsLongPhysicalLineIntoBoundedRows() = runTest {
        val file = tempTextFile("abcdefghij\nz", Charsets.UTF_8)
        val pager = LargeTextPager(file, Charsets.UTF_8)
        try {
            assertThat(pager.reset().isSuccess).isTrue()

            val firstPage = pager.readNextRows(
                maxRows = 2,
                maxChars = 8,
                maxCharsPerRow = 4,
            ).getOrThrow()
            assertThat(firstPage.rows.map { it.text }).containsExactly("abcd", "efgh").inOrder()
            assertThat(firstPage.rows.map { it.lineNumber }).containsExactly(1L, 1L).inOrder()
            assertThat(firstPage.rows.map { it.isContinuation }).containsExactly(false, true).inOrder()
            assertThat(firstPage.isEof).isFalse()

            val secondPage = pager.readNextRows(
                maxRows = 3,
                maxChars = 8,
                maxCharsPerRow = 4,
            ).getOrThrow()
            assertThat(secondPage.rows.map { it.text }).containsExactly("ij", "z").inOrder()
            assertThat(secondPage.rows.map { it.lineNumber }).containsExactly(1L, 2L).inOrder()
            assertThat(secondPage.rows.map { it.isContinuation }).containsExactly(true, false).inOrder()
            assertThat(secondPage.isEof).isTrue()
        } finally {
            pager.close()
            file.delete()
        }
    }

    @Test
    fun readNextRows_preservesEmptyCrLfLines() = runTest {
        val file = tempTextFile("a\r\n\r\nb", Charsets.UTF_8)
        val pager = LargeTextPager(file, Charsets.UTF_8)
        try {
            assertThat(pager.reset().isSuccess).isTrue()

            val page = pager.readNextRows(
                maxRows = 4,
                maxChars = 16,
                maxCharsPerRow = 8,
            ).getOrThrow()

            assertThat(page.rows.map { it.text }).containsExactly("a", "", "b").inOrder()
            assertThat(page.rows.map { it.lineNumber }).containsExactly(1L, 2L, 3L).inOrder()
            assertThat(page.isEof).isTrue()
        } finally {
            pager.close()
            file.delete()
        }
    }

    @Test
    fun readNextRows_usesConfiguredCharset() = runTest {
        val gbk = Charset.forName("GBK")
        val file = tempTextFile("中文内容\n", gbk)
        val pager = LargeTextPager(file, gbk)
        try {
            assertThat(pager.reset().isSuccess).isTrue()

            val page = pager.readNextRows(
                maxRows = 2,
                maxChars = 32,
                maxCharsPerRow = 32,
            ).getOrThrow()

            assertThat(page.rows.single().text).isEqualTo("中文内容")
            assertThat(page.isEof).isTrue()
        } finally {
            pager.close()
            file.delete()
        }
    }

    @Test
    fun readNextRows_doesNotSplitSurrogatePairAcrossRows() = runTest {
        val file = tempTextFile("abc😀def", Charsets.UTF_8)
        val pager = LargeTextPager(file, Charsets.UTF_8)
        try {
            assertThat(pager.reset().isSuccess).isTrue()

            val page = pager.readNextRows(
                maxRows = 3,
                maxChars = 8,
                maxCharsPerRow = 4,
            ).getOrThrow()

            assertThat(page.rows.map { it.text }).containsExactly("abc😀", "def").inOrder()
        } finally {
            pager.close()
            file.delete()
        }
    }

    private fun tempTextFile(content: String, charset: Charset): File =
        File.createTempFile("tina-large-text", ".txt").apply {
            writeText(content, charset)
        }
}
