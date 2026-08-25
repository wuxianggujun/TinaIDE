package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import org.junit.Test

class LspTabRequestTrackerTest {

    @Test
    fun invalidateTab_shouldAdvanceGenerationAndReturnInflightRequests() {
        val tracker = LspTabRequestTracker()
        val tabId = "tab-1"
        val documentUri = "file:///workspace/main.cpp"
        val inflight = CompletableFuture<Unit>()
        val oldTicket = tracker.createTicket(tabId, documentUri)

        tracker.trackFuture(tabId, inflight)
        val cancelled = tracker.invalidateTab(tabId)
        val newTicket = tracker.createTicket(tabId, documentUri)

        assertThat(cancelled).containsExactly(inflight)
        assertThat(tracker.isStillValid(oldTicket) { connectedState(documentUri) }).isFalse()
        assertThat(tracker.isStillValid(newTicket) { connectedState(documentUri) }).isTrue()
    }

    @Test
    fun untrackFuture_shouldRemoveFutureFromInvalidateResult() {
        val tracker = LspTabRequestTracker()
        val tabId = "tab-1"
        val inflight = CompletableFuture<Unit>()

        tracker.trackFuture(tabId, inflight)
        tracker.untrackFuture(tabId, inflight)

        assertThat(tracker.invalidateTab(tabId)).isEmpty()
    }

    @Test
    fun isStillValid_shouldRequireMatchingUriAndConnectedState() {
        val tracker = LspTabRequestTracker()
        val ticket = tracker.createTicket("tab-1", "file:///workspace/main.cpp")

        assertThat(tracker.isStillValid(ticket) { connectedState("file:///workspace/main.cpp") }).isTrue()
        assertThat(tracker.isStillValid(ticket) { connectedState("file:///workspace/other.cpp") }).isFalse()
        assertThat(
            tracker.isStillValid(ticket) {
                LspTrackedTabRequestState(
                    documentUri = "file:///workspace/main.cpp",
                    isConnected = false
                )
            }
        ).isFalse()
        assertThat(tracker.isStillValid(ticket) { null }).isFalse()
    }

    @Test
    fun drainAll_shouldClearGenerationsAndInflightRequests() {
        val tracker = LspTabRequestTracker()
        val tabId = "tab-1"
        val inflight = CompletableFuture<Unit>()

        tracker.trackFuture(tabId, inflight)
        val drained = tracker.drainAll()

        assertThat(drained).containsExactly(inflight)
        assertThat(tracker.invalidateTab(tabId)).isEmpty()
    }

    private fun connectedState(documentUri: String): LspTrackedTabRequestState =
        LspTrackedTabRequestState(
            documentUri = documentUri,
            isConnected = true
        )
}
