package com.wuxianggujun.tinaide.core.treesitter

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSTree
import com.wuxianggujun.tinaide.core.textengine.TextChange
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IncrementalTreeSitterHighlightStateTest {

    @Test
    fun openDocument_thenImmediateEdit_shouldOnlyPublishLatestSnapshot() {
        val parser = mockk<TSParser>()
        val query = mockQuery()
        val fooWorker = mockTree("fooWorker")
        val fooRender = mockTree("fooRender")
        val barWorker = mockTree("barWorker")
        val barRender = mockTree("barRender")
        val firstParseStarted = CountDownLatch(1)
        val releaseFirstParse = CountDownLatch(1)

        every { parser.reset() } just runs
        every { fooWorker.copy() } returns fooRender
        every { barWorker.copy() } returns barRender
        every { parser.parseString(FOO_TEXT) } answers {
            firstParseStarted.countDown()
            check(releaseFirstParse.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release first parse" }
            fooWorker
        }
        every { parser.parseString(BAR_TEXT) } returns barWorker

        createFixture(parser = parser, query = query).use { fixture ->
            val updates = AtomicInteger(0)
            fixture.state.setOnStateUpdated { updates.incrementAndGet() }

            fixture.state.openDocument(FOO_TEXT)
            assertThat(firstParseStarted.await(5, TimeUnit.SECONDS)).isTrue()
            fixture.state.applyTextChange(
                change = replaceIdentifierChange(oldIdentifier = "foo", newIdentifier = "bar")
            )
            releaseFirstParse.countDown()

            waitUntil { updates.get() == 1 }

            assertThat(fixture.state.readSnapshot(FOO_TEXT)).isNull()
            assertThat(fixture.state.readSnapshot(BAR_TEXT)).isNotNull()
            assertThat(updates.get()).isEqualTo(1)
        }

        verify(exactly = 1) { parser.parseString(FOO_TEXT) }
        verify(exactly = 1) { parser.parseString(BAR_TEXT) }
        verify(exactly = 1) { fooRender.close() }
    }

    @Test
    fun successiveEdits_shouldDropIntermediateParseResult() {
        val parser = mockk<TSParser>()
        val query = mockQuery()
        val fooWorker = mockTree("fooWorker")
        val fooRender = mockTree("fooRender")
        val barWorker = mockTree("barWorker")
        val barRender = mockTree("barRender")
        val bazWorker = mockTree("bazWorker")
        val bazRender = mockTree("bazRender")
        val barParseStarted = CountDownLatch(1)
        val releaseBarParse = CountDownLatch(1)

        every { parser.reset() } just runs
        every { fooWorker.copy() } returns fooRender
        every { barWorker.copy() } returns barRender
        every { bazWorker.copy() } returns bazRender
        every { parser.parseString(FOO_TEXT) } returns fooWorker
        every { parser.parseString(fooWorker, BAR_TEXT) } answers {
            barParseStarted.countDown()
            check(releaseBarParse.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release bar parse" }
            barWorker
        }
        every { parser.parseString(barWorker, BAZ_TEXT) } returns bazWorker

        createFixture(parser = parser, query = query).use { fixture ->
            val updates = AtomicInteger(0)
            fixture.state.setOnStateUpdated { updates.incrementAndGet() }

            fixture.state.openDocument(FOO_TEXT)
            waitUntil { updates.get() == 1 }

            updates.set(0)
            fixture.state.applyTextChange(
                change = replaceIdentifierChange(oldIdentifier = "foo", newIdentifier = "bar")
            )
            assertThat(barParseStarted.await(5, TimeUnit.SECONDS)).isTrue()
            fixture.state.applyTextChange(
                change = replaceIdentifierChange(oldIdentifier = "bar", newIdentifier = "baz")
            )
            releaseBarParse.countDown()

            waitUntil { updates.get() == 1 }

            assertThat(fixture.state.readSnapshot(BAR_TEXT)).isNull()
            assertThat(fixture.state.readSnapshot(BAZ_TEXT)).isNotNull()
            assertThat(updates.get()).isEqualTo(1)
        }

        verify(exactly = 1) { parser.parseString(FOO_TEXT) }
        verify(exactly = 1) { parser.parseString(fooWorker, BAR_TEXT) }
        verify(exactly = 1) { parser.parseString(barWorker, BAZ_TEXT) }
        verify(exactly = 1) { barRender.close() }
    }

    @Test
    fun queuedEdits_shouldResetWhenAnIntermediateEditIsCoalesced() {
        val parser = mockk<TSParser>()
        val query = mockQuery()
        val fooWorker = mockTree("fooWorker")
        val fooRender = mockTree("fooRender")
        val barWorker = mockTree("barWorker")
        val barRender = mockTree("barRender")
        val quxWorker = mockTree("quxWorker")
        val quxRender = mockTree("quxRender")
        val barParseStarted = CountDownLatch(1)
        val releaseBarParse = CountDownLatch(1)

        every { parser.reset() } just runs
        every { fooWorker.copy() } returns fooRender
        every { barWorker.copy() } returns barRender
        every { quxWorker.copy() } returns quxRender
        every { parser.parseString(FOO_TEXT) } returns fooWorker
        every { parser.parseString(fooWorker, BAR_TEXT) } answers {
            barParseStarted.countDown()
            check(releaseBarParse.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release bar parse" }
            barWorker
        }
        every { parser.parseString(QUX_TEXT) } returns quxWorker

        createFixture(parser = parser, query = query).use { fixture ->
            val updates = AtomicInteger(0)
            fixture.state.setOnStateUpdated { updates.incrementAndGet() }

            fixture.state.openDocument(FOO_TEXT)
            waitUntil { updates.get() == 1 }

            updates.set(0)
            fixture.state.applyTextChange(replaceIdentifierChange("foo", "bar"))
            assertThat(barParseStarted.await(5, TimeUnit.SECONDS)).isTrue()
            fixture.state.applyTextChange(replaceIdentifierChange("bar", "baz"))
            fixture.state.applyTextChange(replaceIdentifierChange("baz", "qux"))
            releaseBarParse.countDown()

            waitUntil { updates.get() == 1 }

            assertThat(fixture.state.readSnapshot(BAR_TEXT)).isNull()
            assertThat(fixture.state.readSnapshot(QUX_TEXT)).isNotNull()
        }

        verify(exactly = 1) { parser.parseString(fooWorker, BAR_TEXT) }
        verify(exactly = 1) { parser.parseString(QUX_TEXT) }
        verify(exactly = 0) { parser.parseString(barWorker, QUX_TEXT) }
        verify(exactly = 1) { barRender.close() }
    }

    @Test
    fun getLineSegments_shouldNotUseStaleTreeForDirtyLineWhileIncrementalParsePending() {
        val parser = mockk<TSParser>()
        val query = mockQuery()
        val fooWorker = mockTree("fooWorker")
        val fooRender = mockTree("fooRender")
        val barWorker = mockTree("barWorker")
        val barRender = mockTree("barRender")
        val barParseStarted = CountDownLatch(1)
        val releaseBarParse = CountDownLatch(1)

        every { parser.reset() } just runs
        every { fooWorker.copy() } returns fooRender
        every { barWorker.copy() } returns barRender
        every { parser.parseString(FOO_TEXT) } returns fooWorker
        every { parser.parseString(fooWorker, BAR_TEXT) } answers {
            barParseStarted.countDown()
            check(releaseBarParse.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release bar parse" }
            barWorker
        }

        createFixture(parser = parser, query = query).use { fixture ->
            val updates = AtomicInteger(0)
            fixture.state.setOnStateUpdated { updates.incrementAndGet() }

            fixture.state.openDocument(FOO_TEXT)
            waitUntil { updates.get() == 1 }

            fixture.state.applyTextChange(
                change = replaceIdentifierChange(oldIdentifier = "foo", newIdentifier = "bar")
            )
            assertThat(barParseStarted.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(fixture.state.getLineSegments(0)).isEmpty()

            releaseBarParse.countDown()
            waitUntil { updates.get() == 2 }
        }
    }

    @Test
    fun close_shouldDelayFinalCleanupUntilInFlightIncrementalParseFinishes() {
        val parser = mockk<TSParser>()
        val query = mockQuery()
        val fooWorker = mockTree("fooWorker")
        val fooRender = mockTree("fooRender")
        val barWorker = mockTree("barWorker")
        val incrementalParseStarted = CountDownLatch(1)
        val releaseIncrementalParse = CountDownLatch(1)
        val closeCallback = CountDownLatch(1)
        val fooWorkerClosed = AtomicInteger(0)
        val barWorkerClosed = AtomicInteger(0)

        every { parser.reset() } just runs
        every { fooWorker.copy() } returns fooRender
        every { parser.parseString(FOO_TEXT) } returns fooWorker
        every { parser.parseString(fooWorker, BAR_TEXT) } answers {
            incrementalParseStarted.countDown()
            check(releaseIncrementalParse.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release incremental parse"
            }
            barWorker
        }
        every { fooWorker.close() } answers {
            fooWorkerClosed.incrementAndGet()
            Unit
        }
        every { barWorker.close() } answers {
            barWorkerClosed.incrementAndGet()
            Unit
        }

        createFixture(
            parser = parser,
            query = query,
            onClosed = { closeCallback.countDown() }
        ).use { fixture ->
            val updates = AtomicInteger(0)
            fixture.state.setOnStateUpdated { updates.incrementAndGet() }

            fixture.state.openDocument(FOO_TEXT)
            waitUntil { updates.get() == 1 }

            fixture.state.applyTextChange(
                change = replaceIdentifierChange(oldIdentifier = "foo", newIdentifier = "bar")
            )
            assertThat(incrementalParseStarted.await(5, TimeUnit.SECONDS)).isTrue()

            fixture.state.close()

            assertThat(closeCallback.await(150, TimeUnit.MILLISECONDS)).isFalse()
            assertThat(fooWorkerClosed.get()).isEqualTo(0)
            assertThat(barWorkerClosed.get()).isEqualTo(0)

            releaseIncrementalParse.countDown()

            assertThat(closeCallback.await(5, TimeUnit.SECONDS)).isTrue()
            waitUntil {
                fooWorkerClosed.get() == 1 &&
                    barWorkerClosed.get() == 1
            }
        }

        verify(exactly = 1) { fooRender.close() }
    }

    @Test
    fun openDocument_shouldDropInFlightParseTreeFromPreviousSession() {
        val parser = mockk<TSParser>()
        val query = mockQuery()
        val fooWorker = mockTree("fooWorker")
        val fooRender = mockTree("fooRender")
        val barWorker = mockTree("barWorker")
        val bazWorker = mockTree("bazWorker")
        val bazRender = mockTree("bazRender")
        val incrementalParseStarted = CountDownLatch(1)
        val releaseIncrementalParse = CountDownLatch(1)

        every { parser.reset() } just runs
        every { fooWorker.copy() } returns fooRender
        every { bazWorker.copy() } returns bazRender
        every { parser.parseString(FOO_TEXT) } returns fooWorker
        every { parser.parseString(fooWorker, BAR_TEXT) } answers {
            incrementalParseStarted.countDown()
            check(releaseIncrementalParse.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release incremental parse"
            }
            barWorker
        }
        every { parser.parseString(BAZ_TEXT) } returns bazWorker

        createFixture(parser = parser, query = query).use { fixture ->
            val updates = AtomicInteger(0)
            fixture.state.setOnStateUpdated { updates.incrementAndGet() }

            fixture.state.openDocument(FOO_TEXT)
            waitUntil { updates.get() == 1 }

            fixture.state.applyTextChange(
                change = replaceIdentifierChange(oldIdentifier = "foo", newIdentifier = "bar")
            )
            assertThat(incrementalParseStarted.await(5, TimeUnit.SECONDS)).isTrue()

            fixture.state.openDocument(BAZ_TEXT)
            releaseIncrementalParse.countDown()

            waitUntil { updates.get() == 2 }

            assertThat(fixture.state.readSnapshot(BAR_TEXT)).isNull()
            assertThat(fixture.state.readSnapshot(BAZ_TEXT)).isNotNull()
        }

        verify(exactly = 1) { parser.parseString(fooWorker, BAR_TEXT) }
        verify(exactly = 1) { parser.parseString(BAZ_TEXT) }
        verify(exactly = 1) { barWorker.close() }
        verify(exactly = 1) { fooWorker.close() }
        verify(exactly = 1) { fooRender.close() }
    }

    private fun createFixture(
        parser: TSParser,
        query: TSQuery,
        onClosed: (() -> Unit)? = null
    ): TestFixture = TestFixture(
        state = IncrementalTreeSitterHighlightState(
            parser = parser,
            query = query,
            captureTypeByIndex = emptyArray(),
            onClosed = onClosed
        )
    )

    private fun replaceIdentifierChange(
        oldIdentifier: String,
        newIdentifier: String
    ): TextChange = TextChange(
        startOffset = IDENTIFIER_START_OFFSET,
        endOffset = IDENTIFIER_START_OFFSET + oldIdentifier.length,
        oldText = oldIdentifier,
        newText = newIdentifier,
        startLine = 0,
        startColumn = IDENTIFIER_START_OFFSET,
        endLine = 0,
        endColumn = IDENTIFIER_START_OFFSET + oldIdentifier.length
    )

    private fun waitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) {
            return
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }

    private data class TestFixture(
        val state: IncrementalTreeSitterHighlightState
    ) : AutoCloseable {
        override fun close() {
            state.close()
        }
    }

    private fun mockQuery(): TSQuery = mockk {
        every { canAccess() } returns true
        every { captureNames } returns emptyArray()
        every { patternCount } returns 0
    }

    private fun mockTree(name: String): TSTree = mockk(name = name) {
        every { canAccess() } returns true
        every { edit(any()) } just runs
        every { close() } just runs
    }

    private companion object {
        private const val IDENTIFIER_START_OFFSET = 4
        private const val FOO_TEXT = "val foo = 1"
        private const val BAR_TEXT = "val bar = 1"
        private const val BAZ_TEXT = "val baz = 1"
        private const val QUX_TEXT = "val qux = 1"
    }
}
