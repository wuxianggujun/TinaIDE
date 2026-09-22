package com.wuxianggujun.tinaide.core.treesitter

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSTree
import com.wuxianggujun.tinaide.core.textengine.TextChange
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AsyncTreeSitterLineHighlightTest {
    @Test
    fun miss_shouldReturnBeforeQueryFinishesAndDeduplicatePendingReads() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lineQueries = AtomicInteger()
        val ranOnMain = AtomicBoolean()
        val callbackOnMain = AtomicBoolean()
        fixture { _, _, range ->
            if (range == FIRST_LINE_RANGE) {
                lineQueries.incrementAndGet()
                ranOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            keywordSpans(range)
        }.use { fixture ->
            val updates = AtomicInteger()
            fixture.state.setOnStateUpdated {
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                if (updates.incrementAndGet() == 1) {
                    assertThat(fixture.state.getLineSegments(0)).isEmpty()
                }
            }
            try {
                fixture.state.openDocument(TEXT)
                waitUntil { started.count == 0L }
                repeat(100) { assertThat(fixture.state.getLineSegments(0)).isEmpty() }
                assertThat(lineQueries.get()).isEqualTo(1)
                assertThat(ranOnMain.get()).isFalse()

                release.countDown()
                waitUntil { updates.get() >= 2 }
                repeat(100) {
                    assertThat(fixture.state.getLineSegments(0)).containsExactly(FIRST_LINE_SEGMENT)
                }
                assertThat(lineQueries.get()).isEqualTo(1)
                assertThat(callbackOnMain.get()).isTrue()
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun editDuringQuery_shouldDiscardOldSpansBeforeNextParsePublishes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextParseStarted = CountDownLatch(1)
        val releaseNextParse = CountDownLatch(1)
        fixture { _, text, range ->
            if (text == TEXT && range == FIRST_LINE_RANGE) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            keywordSpans(range)
        }.use { fixture ->
            val nextTree = parsedTree()
            every { fixture.parser.parseString(any<TSTree>(), EDITED_TEXT) } answers {
                nextParseStarted.countDown()
                check(releaseNextParse.await(5, TimeUnit.SECONDS))
                nextTree
            }
            val updates = AtomicInteger()
            fixture.state.setOnStateUpdated {
                if (updates.incrementAndGet() == 1) fixture.state.getLineSegments(0)
            }
            try {
                fixture.state.openDocument(TEXT)
                waitUntil { started.count == 0L }
                fixture.state.applyTextChange(replaceIdentifierChange())
                release.countDown()
                waitUntil { nextParseStarted.count == 0L }

                assertThat(fixture.state.getLineSegments(0)).isEmpty()
                assertThat(updates.get()).isEqualTo(1)
                releaseNextParse.countDown()
                waitUntil { fixture.state.readSnapshot(EDITED_TEXT) != null && updates.get() >= 2 }
                waitUntil { fixture.state.getLineSegments(0).isNotEmpty() }
            } finally {
                release.countDown()
                releaseNextParse.countDown()
            }
        }
    }

    @Test
    fun documentSwitchDuringQuery_shouldNotWaitForWorkerOrPublishOldResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queryFinished = AtomicBoolean()
        fixture { _, text, range ->
            if (text == TEXT && range == FIRST_LINE_RANGE) {
                started.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                } finally {
                    queryFinished.set(true)
                }
            }
            keywordSpans(range)
        }.use { fixture ->
            val updates = AtomicInteger()
            fixture.state.setOnStateUpdated {
                if (updates.incrementAndGet() == 1) fixture.state.getLineSegments(0)
            }
            try {
                fixture.state.openDocument(TEXT)
                waitUntil { started.count == 0L }

                fixture.state.openDocument(EDITED_TEXT)

                assertThat(queryFinished.get()).isFalse()
                assertThat(fixture.state.getLineSegments(0)).isEmpty()
                release.countDown()
                waitUntil { fixture.state.readSnapshot(EDITED_TEXT) != null && updates.get() >= 2 }
                assertThat(fixture.state.readSnapshot(TEXT)).isNull()
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun failedQuery_shouldReleasePendingMarkerAndAllowRetry() {
        val lineQueries = AtomicInteger()
        val bulkAttempted = CountDownLatch(1)
        fixture { _, _, range ->
            if (range != FIRST_LINE_RANGE) {
                bulkAttempted.countDown()
                throw IllegalStateException("Injected bulk failure")
            }
            if (lineQueries.incrementAndGet() == 1) throw IllegalStateException("Injected line failure")
            keywordSpans(range)
        }.use { fixture ->
            val updates = AtomicInteger()
            fixture.state.setOnStateUpdated {
                if (updates.incrementAndGet() == 1) fixture.state.getLineSegments(0)
            }
            fixture.state.openDocument(TEXT)
            waitUntil { bulkAttempted.count == 0L }

            assertThat(fixture.state.getLineSegments(0)).isEmpty()
            waitUntil { updates.get() >= 2 }

            assertThat(fixture.state.getLineSegments(0)).containsExactly(FIRST_LINE_SEGMENT)
            assertThat(lineQueries.get()).isEqualTo(2)
        }
    }

    @Test
    fun successfulEmptyQuery_shouldBeCachedAndNotifyOnlyOnceForLine() {
        val lineQueries = AtomicInteger()
        fixture { _, _, range ->
            if (range == FIRST_LINE_RANGE) lineQueries.incrementAndGet()
            emptyList()
        }.use { fixture ->
            val updates = AtomicInteger()
            fixture.state.setOnStateUpdated {
                if (updates.incrementAndGet() == 1) fixture.state.getLineSegments(0)
            }
            fixture.state.openDocument(TEXT)
            waitUntil { updates.get() >= 2 }

            repeat(100) { assertThat(fixture.state.getLineSegments(0)).isEmpty() }
            assertThat(lineQueries.get()).isEqualTo(1)
        }
    }

    private fun fixture(capture: (TSNode, String, IntRange) -> List<HighlightSpan>): Fixture {
        val parser = mockk<TSParser>()
        val query = mockk<TSQuery> {
            every { canAccess() } returns true
            every { captureNames } returns emptyArray()
            every { patternCount } returns 0
        }
        every { parser.reset() } just runs
        every { parser.parseString(any<String>()) } answers { parsedTree() }
        every { parser.parseString(any<TSTree>(), any<String>()) } answers { parsedTree() }
        val closed = CountDownLatch(1)
        return Fixture(
            parser,
            IncrementalTreeSitterHighlightState(
                parser = parser,
                query = query,
                captureTypeByIndex = emptyArray(),
                onClosed = { closed.countDown() },
                captureSpans = capture,
            ),
            closed,
        )
    }

    private fun parsedTree(): TSTree {
        val worker = tree()
        val render = tree()
        every { worker.copy() } returns render
        every { render.copy() } answers { tree() }
        every { worker.getChangedRanges(any()) } returns emptyArray()
        return worker
    }

    private fun tree(): TSTree = mockk<TSTree> {
        every { canAccess() } returns true
        every { close() } just runs
        every { edit(any()) } just runs
        every { rootNode } returns mockk<TSNode>()
    }

    private fun keywordSpans(range: IntRange) = listOf(HighlightSpan(range.first, range.last + 1, HighlightType.KEYWORD))

    private fun replaceIdentifierChange() = TextChange(
        startOffset = 4,
        endOffset = 7,
        oldText = "foo",
        newText = "bar",
        startLine = 0,
        startColumn = 4,
        endLine = 0,
        endColumn = 7,
    )

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Async highlight condition was not met")
    }

    private data class Fixture(
        val parser: TSParser,
        val state: IncrementalTreeSitterHighlightState,
        val closed: CountDownLatch,
    ) : AutoCloseable {
        override fun close() {
            state.close()
            check(closed.await(5, TimeUnit.SECONDS)) { "Highlight worker did not finish cleanup" }
        }
    }

    private companion object {
        private const val TEXT = "val foo = 1\nsecond"
        private const val EDITED_TEXT = "val bar = 1\nsecond"
        private val FIRST_LINE_RANGE = 0..10
        private val FIRST_LINE_SEGMENT = HighlightLineSegment(0, 11, HighlightType.KEYWORD)
    }
}
