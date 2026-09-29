package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [filterCompletionItems] 纯函数测试。
 *
 * 覆盖：空输入、空白查询的去重/kind 优先级排序/截断上限、大小写敏感开关、
 * 桶排序（exact > prefix > contains）、filterText 别名仅在大小写不敏感时生效、去重。
 */
class EditorCompletionRankingTest {

    private fun item(
        label: String,
        kind: EditorCompletionKind = EditorCompletionKind.TEXT,
        insertText: String = label,
        detail: String? = null,
        filterText: String? = null
    ): EditorCompletionItem = EditorCompletionItem(
        label = label,
        kind = kind,
        insertText = insertText,
        detail = detail,
        filterText = filterText
    )

    @Test
    fun returnsEmpty_whenItemsEmpty() {
        assertThat(filterCompletionItems(emptyList(), "anything", caseSensitive = false)).isEmpty()
        assertThat(filterCompletionItems(emptyList(), "", caseSensitive = false)).isEmpty()
    }

    @Test
    fun blankQuery_sortsByKindPriority() {
        val items = listOf(
            item("kw", EditorCompletionKind.KEYWORD),      // priority 5
            item("fn", EditorCompletionKind.FUNCTION),     // priority 1
            item("vr", EditorCompletionKind.VARIABLE)      // priority 0
        )

        val result = filterCompletionItems(items, query = "  ", caseSensitive = false)

        assertThat(result.map { it.label }).containsExactly("vr", "fn", "kw").inOrder()
    }

    @Test
    fun blankQuery_dedupsByLabelForNonMethodKinds() {
        val items = listOf(
            item("foo", EditorCompletionKind.TEXT),
            item("foo", EditorCompletionKind.TEXT),
            item("Foo", EditorCompletionKind.CLASS) // 大小写归一后同 key
        )

        val result = filterCompletionItems(items, query = "", caseSensitive = false)

        assertThat(result).hasSize(1)
        assertThat(result.first().label).isEqualTo("foo")
    }

    @Test
    fun blankQuery_keepsMethodOverloadsWithDifferentDetail() {
        val items = listOf(
            item("foo", EditorCompletionKind.METHOD, detail = "(Int): Unit"),
            item("foo", EditorCompletionKind.METHOD, detail = "(String): Unit")
        )

        val result = filterCompletionItems(items, query = "", caseSensitive = false)

        // METHOD 的去重 key 包含 detail/insertText 等，两个重载 key 不同 -> 都保留。
        assertThat(result).hasSize(2)
    }

    @Test
    fun blankQuery_truncatesAt160() {
        val items = (0 until 200).map { item("label_$it") }

        val result = filterCompletionItems(items, query = "", caseSensitive = false)

        assertThat(result).hasSize(160)
    }

    @Test
    fun query_truncatesAt160() {
        val items = (0 until 200).map { item("foo_$it") }

        val result = filterCompletionItems(items, query = "foo", caseSensitive = false)

        assertThat(result).hasSize(160)
    }

    @Test
    fun caseInsensitive_matchesPrefixIgnoringCase() {
        val items = listOf(item("print"), item("private"))

        val result = filterCompletionItems(items, query = "PRI", caseSensitive = false)

        assertThat(result.map { it.label }).containsExactly("print", "private")
    }

    @Test
    fun caseSensitive_dropsMismatchedCase() {
        val items = listOf(item("print"), item("private"))

        val result = filterCompletionItems(items, query = "PRI", caseSensitive = true)

        assertThat(result).isEmpty()
    }

    @Test
    fun caseSensitive_matchesExactCasePrefix() {
        val items = listOf(item("print"), item("Print"))

        val result = filterCompletionItems(items, query = "pri", caseSensitive = true)

        // 只有小写 "print" 以 "pri" 开头；"Print" 不匹配。
        assertThat(result.map { it.label }).containsExactly("print")
    }

    @Test
    fun ranking_exactBeforePrefixBeforeContains() {
        val items = listOf(
            item("xfooy"),   // contains
            item("foobar"),  // prefix
            item("foo")      // exact
        )

        val result = filterCompletionItems(items, query = "foo", caseSensitive = false)

        assertThat(result.map { it.label }).containsExactly("foo", "foobar", "xfooy").inOrder()
    }

    @Test
    fun aliasFilterText_matchesOnlyWhenCaseInsensitive() {
        val withAlias = item(label = "zzz", filterText = "foo")

        val insensitive = filterCompletionItems(listOf(withAlias), query = "foo", caseSensitive = false)
        val sensitive = filterCompletionItems(listOf(withAlias), query = "foo", caseSensitive = true)

        assertThat(insensitive.map { it.label }).containsExactly("zzz")
        assertThat(sensitive).isEmpty()
    }

    @Test
    fun query_dedupsIdenticalItems() {
        val items = listOf(item("foo"), item("foo"))

        val result = filterCompletionItems(items, query = "foo", caseSensitive = false)

        assertThat(result).hasSize(1)
    }

    @Test
    fun nonMatchingItemsAreDropped() {
        val items = listOf(item("alpha"), item("beta"))

        val result = filterCompletionItems(items, query = "zzz", caseSensitive = false)

        assertThat(result).isEmpty()
    }
}
