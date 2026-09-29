package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RopeTest {

    @Test
    fun insertAndDeleteShouldWork() {
        val rope = Rope()
        rope.insert(0, "Hello")
        rope.insert(5, " World")
        assertThat(rope.substring(0, rope.length)).isEqualTo("Hello World")

        rope.delete(5, 11)
        assertThat(rope.substring(0, rope.length)).isEqualTo("Hello")
    }

    @Test
    fun chunkSplitShouldKeepContentCorrect() {
        val rope = Rope()
        val big = "a".repeat(10_000)
        rope.insert(0, big)
        rope.insert(5000, "XYZ")
        assertThat(rope.length).isEqualTo(10_003)
        assertThat(rope.substring(4998, 5005)).isEqualTo("aaXYZaa")
    }

    @Test
    fun deleteAcrossChunksShouldWork() {
        val rope = Rope()
        rope.insert(0, "abc\n".repeat(3000))
        val before = rope.length
        rope.delete(100, 3000)
        assertThat(rope.length).isEqualTo(before - (3000 - 100))
    }
}
