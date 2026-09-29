package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.TextChange
import org.junit.Test

class EditorTextScanCacheTest {

    @Test
    fun getTabColumns_shouldReuseCachedArrayForSameLine() {
        val cache = EditorTextScanCache()

        val first = cache.getTabColumns(line = 3, lineText = "a\tb\t", textVersion = 1L)
        val second = cache.getTabColumns(line = 3, lineText = "a\tb\t", textVersion = 1L)

        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun applyTextChange_shouldShiftCachedLinesAfterInsertedNewline() {
        val cache = EditorTextScanCache()
        val cached = cache.getTabColumns(line = 3, lineText = "a\tb", textVersion = 1L)

        cache.applyTextChange(
            change = TextChange(
                startOffset = 0,
                endOffset = 0,
                oldText = "",
                newText = "\n",
                startLine = 0,
                startColumn = 0,
                endLine = 0,
                endColumn = 0
            ),
            currentVersion = 2L
        )

        val shifted = cache.getTabColumns(line = 4, lineText = "a\tb", textVersion = 2L)
        val oldLine = cache.getTabColumns(line = 3, lineText = "a\tb", textVersion = 2L)

        assertThat(shifted).isSameInstanceAs(cached)
        assertThat(oldLine).isNotSameInstanceAs(cached)
    }

    @Test
    fun getWholeWordMatches_shouldReuseCachedMatchesForSameWord() {
        val cache = EditorTextScanCache()

        val first = cache.getWholeWordMatches(
            line = 1,
            lineText = "foo bar foo",
            textVersion = 1L,
            word = "foo"
        )
        val second = cache.getWholeWordMatches(
            line = 1,
            lineText = "foo bar foo",
            textVersion = 1L,
            word = "foo"
        )

        assertThat(first.asList()).containsExactly(0, 8).inOrder()
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun getWhitespaceInfo_shouldReuseCachedInfoForSameLineAndTabSize() {
        val cache = EditorTextScanCache()

        val first = cache.getWhitespaceInfo(
            line = 2,
            lineText = "  foo\t ",
            textVersion = 1L,
            tabSize = 4
        )
        val second = cache.getWhitespaceInfo(
            line = 2,
            lineText = "  foo\t ",
            textVersion = 1L,
            tabSize = 4
        )
        val differentTabSize = cache.getWhitespaceInfo(
            line = 2,
            lineText = "  foo\t ",
            textVersion = 1L,
            tabSize = 2
        )

        assertThat(second).isSameInstanceAs(first)
        assertThat(differentTabSize).isNotSameInstanceAs(first)
    }

    @Test
    fun getVisualColumnPrefix_shouldReuseCachedPrefixForSameLineAndTabSize() {
        val cache = EditorTextScanCache()

        val first = cache.getVisualColumnPrefix(
            line = 4,
            lineText = "a\tbc",
            textVersion = 1L,
            tabSize = 4
        )
        val second = cache.getVisualColumnPrefix(
            line = 4,
            lineText = "a\tbc",
            textVersion = 1L,
            tabSize = 4
        )
        val differentTabSize = cache.getVisualColumnPrefix(
            line = 4,
            lineText = "a\tbc",
            textVersion = 1L,
            tabSize = 2
        )

        assertThat(first.asList()).containsExactly(0, 1, 4, 5, 6).inOrder()
        assertThat(second).isSameInstanceAs(first)
        assertThat(differentTabSize).isNotSameInstanceAs(first)
    }
}
