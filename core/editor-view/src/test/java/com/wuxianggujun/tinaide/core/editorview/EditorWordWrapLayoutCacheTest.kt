package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorWordWrapLayoutCacheTest {
    @Test
    fun cacheHits_reuseLayoutObjectAndStarts() {
        val cache = EditorWordWrapLayoutCache()
        val first = cache.getWrapLayout(0, "abcdefgh", 0, 4, 4)
        repeat(120) {
            assertThat(cache.getWrapLayout(0, "abcdefgh", 0, 4, 4)).isSameInstanceAs(first)
        }
        assertThat(cache.getWrapLayout(0, "abcdefgh", 0, 2, 4)).isNotSameInstanceAs(first)
        assertThat(first.starts.asList()).containsExactly(0, 4).inOrder()
    }

    @Test
    fun editsAndHints_refreshOnlyAffectedLayouts() {
        val rope = RopeTextBuffer("abcd\nefgh")
        val cache = EditorWordWrapLayoutCache()
        val first = cache.getWrapLayout(0, rope.getLine(0), rope.version, 4, 4)
        val second = cache.getWrapLayout(1, rope.getLine(1), rope.version, 4, 4)
        rope.addChangeListener { cache.applyTextChange(it, rope.version) }
        rope.insert(0, "x")

        assertThat(cache.getWrapLayout(0, rope.getLine(0), rope.version, 4, 4)).isNotSameInstanceAs(first)
        assertThat(cache.getWrapLayout(1, rope.getLine(1), rope.version, 4, 4)).isSameInstanceAs(second)
        assertThat(cache.getWrapLayout(1, rope.getLine(1), rope.version, 4, 4, listOf(EditorInlayHint(1, 1, "v:"))))
            .isNotSameInstanceAs(second)
    }
}
