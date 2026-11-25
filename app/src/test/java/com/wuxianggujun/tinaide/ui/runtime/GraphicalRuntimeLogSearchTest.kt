package com.wuxianggujun.tinaide.ui.runtime

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class GraphicalRuntimeLogSearchTest {
    private val document = GraphicalRuntimeLogDocument()
    private val search get() = document.search
    @After fun close() = document.close()

    @Test fun literalSearchSelectsMatchesAndWrapsInBothDirections() {
        append("start ERROR\nerror end\n[.*] literal")
        search.open()
        search.updateQuery("error")
        assertThat(search.matchCount).isEqualTo(2)
        assertThat(document.state.selectedText()).isEqualTo("ERROR")
        search.move(true)
        assertThat(document.state.selectedText()).isEqualTo("error")
        search.move(true)
        assertThat(search.currentIndex).isEqualTo(0)
        search.move(false)
        assertThat(search.currentIndex).isEqualTo(1)
        search.updateQuery("[.*]")
        assertThat(document.state.selectedText()).isEqualTo("[.*]")
    }

    @Test fun UnicodeOffsetsAndLongLineHorizontalNavigationStayCorrect() {
        document.state.updateMetrics(20f, 8f, 80f, 120f, 0f)
        append("\u0130 " + "x".repeat(200) + " 中文😀 error")
        search.open()
        search.updateQuery("中文😀")
        assertThat(document.state.selectedText()).isEqualTo("中文😀")
        assertThat(document.state.scrollOffsetXPx).isGreaterThan(0f)
        search.updateQuery("ERROR")
        assertThat(document.state.selectedText()).isEqualTo("error")
    }

    @Test fun appendingAndTrimmingPreserveTheSelectedOccurrenceAndRefreshCount() {
        document.append((1..500).map { GraphicalRuntimeLogEntry('I', "error $it") })
        search.open()
        search.updateQuery("error")
        repeat(100) { search.move(true) }
        val start = document.state.selectionRange!!.start
        append("error 501")
        assertThat(search.matchCount).isEqualTo(500)
        assertThat(search.currentIndex).isEqualTo(99)
        assertThat(document.state.selectedText()).isEqualTo("error")
        assertThat(document.state.selectionRange!!.start).isLessThan(start)
        assertThat(document.followingTail).isFalse()
    }

    @Test fun expiredMatchesDoNotSelectUnrelatedTextAndClearKeepsTheQuery() {
        document.append((1..500).map { GraphicalRuntimeLogEntry('I', if (it == 1) "needle" else "row $it") })
        search.open()
        search.updateQuery("needle")
        append("last row")
        assertThat(search.matchCount).isEqualTo(0)
        assertThat(document.state.selectionRange).isNull()
        search.move(true)
        document.clear()
        assertThat(search.query).isEqualTo("needle")
        assertThat(search.currentIndex).isEqualTo(-1)
        append("needle new")
        assertThat(search.matchCount).isEqualTo(1)
        search.move(true)
        assertThat(document.state.selectedText()).isEqualTo("needle")
    }

    @Test fun frequentMatchesAreBoundedAndClosingReleasesOnlySearchSelection() {
        append("a".repeat(4000))
        search.open()
        search.updateQuery("a")
        assertThat(search.matchCount).isEqualTo(2000)
        assertThat(search.capped).isTrue()
        search.close()
        assertThat(document.state.selectionRange).isNull()
        assertThat(search.query).isEqualTo("a")
        search.open()
        document.state.selectRange(0, 4)
        search.close()
        assertThat(document.state.selectedText()).isEqualTo("aaaa")
    }

    @Test fun reopeningAfterClosedBufferChangesRebuildsMatchesFromTheRetainedLog() {
        append("needle before")
        search.open()
        search.updateQuery("needle")
        search.close()
        document.clear()
        append("new log: needle after")
        search.open()
        assertThat(search.matchCount).isEqualTo(1)
        assertThat(document.state.selectionRange!!.start).isEqualTo(9)
        assertThat(document.state.selectedText()).isEqualTo("needle")
    }

    private fun append(text: String) = document.append(listOf(GraphicalRuntimeLogEntry('I', text)))
}
