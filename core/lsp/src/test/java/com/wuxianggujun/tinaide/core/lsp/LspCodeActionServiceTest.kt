package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Test

class LspCodeActionServiceTest {

    @Test
    fun `request limits code actions to requested kinds`() {
        runBlocking {
            var captured: org.eclipse.lsp4j.CodeActionParams? = null

            LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 2,
                startColumn = 1,
                endLine = 2,
                endColumn = 4,
                onlyKinds = listOf(CodeActionKind.QuickFix),
                codeActionRequest = { params, _ ->
                    captured = params
                    emptyList()
                },
            )

            assertThat(captured?.context?.only).containsExactly(CodeActionKind.QuickFix)
        }
    }

    @Test
    fun `request leaves code action kinds unrestricted by default`() {
        runBlocking {
            var captured: org.eclipse.lsp4j.CodeActionParams? = null

            LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 2,
                startColumn = 1,
                endLine = 2,
                endColumn = 4,
                codeActionRequest = { params, _ ->
                    captured = params
                    emptyList()
                },
            )

            assertThat(captured?.context?.only).isNull()
        }
    }

    @Test
    fun `request drops actions outside requested kinds`() {
        runBlocking {
            val result = LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 2,
                startColumn = 1,
                endLine = 2,
                endColumn = 4,
                onlyKinds = listOf(CodeActionKind.QuickFix),
                codeActionRequest = { _, _ ->
                    listOf(
                        Either.forRight(
                            CodeAction("Add missing include").apply { kind = CodeActionKind.QuickFix }
                        ),
                        Either.forRight(
                            CodeAction("Extract function").apply { kind = CodeActionKind.RefactorExtract }
                        ),
                        Either.forRight(
                            CodeAction("Organize imports").apply { kind = CodeActionKind.SourceOrganizeImports }
                        ),
                    )
                },
            )

            assertThat(result.map { action -> action.title }).containsExactly("Add missing include")
        }
    }

    @Test
    fun `source fix all requires an explicit matching action kind`() {
        runBlocking {
            val result = LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 0,
                startColumn = 0,
                endLine = 20,
                endColumn = 0,
                onlyKinds = listOf(CodeActionKind.SourceFixAll),
                codeActionRequest = { _, _ ->
                    listOf(
                        Either.forLeft(Command("Untyped command", "clangd.command", emptyList())),
                        Either.forRight(
                            CodeAction("Single quick fix").apply { kind = CodeActionKind.QuickFix },
                        ),
                        Either.forRight(
                            CodeAction("Fix all").apply { kind = CodeActionKind.SourceFixAll },
                        ),
                        Either.forRight(
                            CodeAction("Clangd fix all").apply { kind = "${CodeActionKind.SourceFixAll}.clangd" },
                        ),
                    )
                },
            )

            assertThat(result.map { action -> action.title })
                .containsExactly("Fix all", "Clangd fix all")
                .inOrder()
        }
    }

    @Test
    fun `request propagates server failure`() {
        runBlocking {
            val failure = IllegalStateException("clangd unavailable")

            val thrown = runCatching {
                LspCodeActionService().requestCodeActions(
                    documentUri = "file:///project/main.cpp",
                    startLine = 0,
                    startColumn = 0,
                    endLine = 0,
                    endColumn = 0,
                    codeActionRequest = { _, _ -> throw failure },
                )
            }.exceptionOrNull()

            assertThat(thrown).isSameInstanceAs(failure)
        }
    }

    @Test
    fun `request preserves cancellation`() {
        runBlocking {
            val cancellation = CancellationException("superseded")

            val thrown = runCatching {
                LspCodeActionService().requestCodeActions(
                    documentUri = "file:///project/main.cpp",
                    startLine = 0,
                    startColumn = 0,
                    endLine = 0,
                    endColumn = 0,
                    codeActionRequest = { _, _ -> throw cancellation },
                )
            }.exceptionOrNull()

            assertThat(thrown).isSameInstanceAs(cancellation)
        }
    }

    @Test
    fun `request includes diagnostics intersecting requested range`() {
        runBlocking {
            val relevant = Diagnostic().apply {
                range = Range(Position(3, 2), Position(3, 8))
                message = "unused variable"
                severity = DiagnosticSeverity.Warning
                source = "clangd"
                code = Either.forLeft("unused-variable")
            }
            val unrelated = Diagnostic().apply {
                range = Range(Position(8, 0), Position(8, 4))
                message = "unrelated"
            }
            var captured: org.eclipse.lsp4j.CodeActionParams? = null

            LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 3,
                startColumn = 4,
                endLine = 3,
                endColumn = 4,
                diagnostics = listOf(relevant, unrelated),
                codeActionRequest = { params, _ ->
                    captured = params
                    listOf(Either.forRight(CodeAction().apply { title = "Remove unused variable" }))
                },
            )

            assertThat(captured?.context?.diagnostics).containsExactly(relevant)
            assertThat(captured?.context?.diagnostics?.single()?.code?.left)
                .isEqualTo("unused-variable")
        }
    }

    @Test
    fun `request includes zero width diagnostic at cursor boundary`() {
        runBlocking {
            val diagnostic = Diagnostic().apply {
                range = Range(Position(1, 5), Position(1, 5))
                message = "expected expression"
            }
            var captured: org.eclipse.lsp4j.CodeActionParams? = null

            LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 1,
                startColumn = 5,
                endLine = 1,
                endColumn = 5,
                diagnostics = listOf(diagnostic),
                codeActionRequest = { params, _ ->
                    captured = params
                    emptyList()
                },
            )

            assertThat(captured?.context?.diagnostics).containsExactly(diagnostic)
        }
    }

    @Test
    fun `request exposes disabled reason from server`() {
        runBlocking {
            val result = LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 1,
                startColumn = 0,
                endLine = 1,
                endColumn = 3,
                codeActionRequest = { _, _ ->
                    listOf(
                        Either.forRight(
                            CodeAction().apply {
                                title = "Unavailable fix"
                                disabled = CodeActionDisabled("Header is not writable")
                            }
                        )
                    )
                },
            )

            assertThat(result).hasSize(1)
            assertThat(result.single().isEnabled).isFalse()
            assertThat(result.single().disabledReason).isEqualTo("Header is not writable")
        }
    }

    @Test
    fun `disabled action remains disabled when server omits reason`() {
        runBlocking {
            val result = LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 1,
                startColumn = 0,
                endLine = 1,
                endColumn = 3,
                codeActionRequest = { _, _ ->
                    listOf(
                        Either.forRight(
                            CodeAction().apply {
                                title = "Unavailable fix"
                                disabled = CodeActionDisabled()
                            }
                        )
                    )
                },
            )

            assertThat(result.single().isEnabled).isFalse()
            assertThat(result.single().disabledReason).isNull()
        }
    }

    @Test
    fun `disabled action is never executed`() {
        runBlocking {
            val item = LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 1,
                startColumn = 0,
                endLine = 1,
                endColumn = 3,
                codeActionRequest = { _, _ ->
                    listOf(
                        Either.forRight(
                            CodeAction().apply {
                                title = "Unavailable fix"
                                disabled = CodeActionDisabled("Header is not writable")
                            }
                        )
                    )
                },
            ).single()
            var resolveCalled = false
            var executeCalled = false
            var applyEditCalled = false

            val executed = LspCodeActionService().executeCodeAction(
                item = item,
                resolveCodeActionRequest = { _, _ ->
                    resolveCalled = true
                    null
                },
                executeCommandRequest = { _, _ ->
                    executeCalled = true
                    null
                },
                onApplyEdit = {
                    applyEditCalled = true
                    true
                },
            )

            assertThat(executed).isFalse()
            assertThat(resolveCalled).isFalse()
            assertThat(executeCalled).isFalse()
            assertThat(applyEditCalled).isFalse()
        }
    }

    @Test
    fun `action disabled during resolve is not applied`() {
        runBlocking {
            val item = LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 1,
                startColumn = 0,
                endLine = 1,
                endColumn = 3,
                codeActionRequest = { _, _ ->
                    listOf(Either.forRight(CodeAction("Resolve me")))
                },
            ).single()
            var applyEditCalled = false

            val executed = LspCodeActionService().executeCodeAction(
                item = item,
                resolveCodeActionRequest = { action, _ ->
                    action.apply {
                        disabled = CodeActionDisabled("Document changed")
                        edit = WorkspaceEdit(
                            mapOf(
                                "file:///project/main.cpp" to listOf(
                                    TextEdit(Range(Position(1, 0), Position(1, 3)), "fixed")
                                )
                            )
                        )
                    }
                },
                executeCommandRequest = { _, _ -> null },
                onApplyEdit = {
                    applyEditCalled = true
                    true
                },
            )

            assertThat(executed).isFalse()
            assertThat(applyEditCalled).isFalse()
        }
    }

    @Test
    fun `action applies edit before executing command`() {
        runBlocking {
            val edit = WorkspaceEdit(
                mapOf(
                    "file:///project/main.cpp" to listOf(
                        TextEdit(Range(Position(1, 0), Position(1, 3)), "fixed")
                    )
                )
            )
            val command = Command("Finalize fix", "clangd.finalizeFix", emptyList())
            val item = LspCodeActionService().requestCodeActions(
                documentUri = "file:///project/main.cpp",
                startLine = 1,
                startColumn = 0,
                endLine = 1,
                endColumn = 3,
                codeActionRequest = { _, _ ->
                    listOf(
                        Either.forRight(
                            CodeAction().apply {
                                title = "Apply complete fix"
                                this.edit = edit
                                this.command = command
                            }
                        )
                    )
                },
            ).single()
            val events = mutableListOf<String>()

            val executed = LspCodeActionService().executeCodeAction(
                item = item,
                resolveCodeActionRequest = { _, _ -> null },
                executeCommandRequest = { params, _ ->
                    events += "command:${params.command}"
                    null
                },
                onApplyEdit = {
                    events += "edit"
                    true
                },
            )

            assertThat(executed).isTrue()
            assertThat(events).containsExactly("edit", "command:clangd.finalizeFix").inOrder()
        }
    }
}
