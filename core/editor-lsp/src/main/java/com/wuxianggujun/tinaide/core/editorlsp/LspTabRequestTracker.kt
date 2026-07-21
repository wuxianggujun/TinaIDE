package com.wuxianggujun.tinaide.core.editorlsp

import java.util.concurrent.CompletableFuture

data class LspTabRequestTicket(
    val tabId: String,
    val generation: Long,
    val documentUri: String,
)

data class LspTrackedTabRequestState(
    val documentUri: String,
    val isConnected: Boolean,
)

class LspTabRequestTracker(
    private val stateLock: Any = Any(),
) {
    private val tabRequestGenerations = mutableMapOf<String, Long>()
    private val tabInflightRequests = mutableMapOf<String, MutableSet<CompletableFuture<*>>>()

    fun invalidateTab(tabId: String): List<CompletableFuture<*>> = synchronized(stateLock) {
        tabRequestGenerations[tabId] = (tabRequestGenerations[tabId] ?: 0L) + 1L
        tabInflightRequests.remove(tabId)?.toList().orEmpty()
    }

    fun createTicket(tabId: String, documentUri: String): LspTabRequestTicket = synchronized(stateLock) {
        LspTabRequestTicket(
            tabId = tabId,
            generation = tabRequestGenerations[tabId] ?: 0L,
            documentUri = documentUri,
        )
    }

    fun isStillValid(
        ticket: LspTabRequestTicket,
        resolveTabState: (tabId: String) -> LspTrackedTabRequestState?,
    ): Boolean = synchronized(stateLock) {
        val tabState = resolveTabState(ticket.tabId)
        (tabRequestGenerations[ticket.tabId] ?: 0L) == ticket.generation &&
            tabState != null &&
            tabState.documentUri == ticket.documentUri &&
            tabState.isConnected
    }

    fun trackFuture(tabId: String, future: CompletableFuture<*>) {
        synchronized(stateLock) {
            tabInflightRequests.getOrPut(tabId) { mutableSetOf() }.add(future)
        }
    }

    fun untrackFuture(tabId: String, future: CompletableFuture<*>) {
        synchronized(stateLock) {
            tabInflightRequests[tabId]?.remove(future)
            if (tabInflightRequests[tabId].isNullOrEmpty()) {
                tabInflightRequests.remove(tabId)
            }
        }
    }

    fun drainAll(): List<CompletableFuture<*>> = synchronized(stateLock) {
        tabRequestGenerations.clear()
        val inflightRequests = tabInflightRequests.values.flatMap { it.toList() }
        tabInflightRequests.clear()
        inflightRequests
    }
}
