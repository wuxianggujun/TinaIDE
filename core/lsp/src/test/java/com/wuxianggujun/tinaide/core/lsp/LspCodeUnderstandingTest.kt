package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Test

class LspCodeUnderstandingTest {
    private val service = LspNavigationService()
    private fun range(line: Int) = Range(Position(line, 2), Position(line, 8))
    private fun item(name: String, uri: String, line: Int) = CallHierarchyItem().apply {
        this.name = name; this.uri = uri; kind = SymbolKind.Method; range = range(line); selectionRange = range(line)
    }

    @Test fun outgoingNavigatesToCalleeNotCallSite() = runTest {
        val from = item("caller", "file:///caller.cpp", 3)
        val target = item("callee", "file:///target.cpp", 25)
        val call = CallHierarchyOutgoingCall(target, listOf(range(7)))
        val result = service.callHierarchyOutgoingCalls(from.uri, 3, 2, { _, _ -> listOf(from) }, { _, _ -> listOf(call, call) })
        assertThat(result).hasSize(1)
        assertThat(result.single().uri).isEqualTo(target.uri)
        assertThat(result.single().line).isEqualTo(25)
        assertThat(result.single().previewText).isEqualTo("callee")
    }

    @Test fun outgoingPropagatesFailureAndCancellation() = runTest {
        for (error in listOf(IllegalStateException("offline"), CancellationException("stale"))) {
            val result = runCatching { service.callHierarchyOutgoingCalls("file:///a.cpp", 0, 0, { _, _ -> throw error }, { _, _ -> emptyList() }) }
            assertThat(result.exceptionOrNull()).isInstanceOf(error.javaClass)
        }
    }

    @Test fun documentSymbolsKeepSignatureAndQualifiedContainer() = runTest {
        fun symbol(name: String, kind: SymbolKind, detail: String? = null) = DocumentSymbol().apply {
            this.name = name; this.kind = kind; this.detail = detail; range = range(1); selectionRange = range(1)
        }
        val method = symbol("run", SymbolKind.Method, "int run(int value) const")
        val type = symbol("Worker", SymbolKind.Class).apply { children = listOf(method) }
        val namespace = symbol("jobs", SymbolKind.Namespace).apply { children = listOf(type) }
        val result = service.documentSymbols("file:///jobs.cpp") { _, _ -> listOf(Either.forRight(namespace)) }
        assertThat(result.last().containerName).isEqualTo("jobs::Worker")
        assertThat(result.last().detail).isEqualTo("int run(int value) const")
    }
}
