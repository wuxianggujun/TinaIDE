package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import org.junit.Test

class BracketDepthCacheTest {

    @Test
    fun depthAt_shouldReuseCachedLineDepthsAcrossNearbyLookups() {
        val delegate = RopeTextBuffer().apply {
            insert(0, buildDocument())
        }
        val buffer = CountingTextBuffer(delegate)
        val cache = BracketDepthCache()

        val firstDepth = cache.depthAt(buffer, 80, 0)

        assertThat(firstDepth).isEqualTo(1)
        assertThat(buffer.getLineCalls).isEqualTo(0)

        buffer.resetCounters()
        val secondDepth = cache.depthAt(buffer, 81, 0)

        assertThat(secondDepth).isEqualTo(1)
        assertThat(buffer.getLineCalls).isEqualTo(0)
    }

    private fun buildDocument(): String {
        val lines = ArrayList<String>(120)
        lines += "{"
        repeat(118) { lines += "value$it" }
        lines += "}"
        return lines.joinToString("\n")
    }

    private class CountingTextBuffer(
        private val delegate: RopeTextBuffer
    ) : TextBuffer by delegate {
        var getLineCalls: Int = 0
            private set

        override fun getLine(line: Int): String {
            getLineCalls++
            return delegate.getLine(line)
        }

        fun resetCounters() {
            getLineCalls = 0
        }
    }
}
