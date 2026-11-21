package com.wuxianggujun.tinaide.core.lsp

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * TinaIDE 的 LSP 会话抽象，避免 core:lsp 与任意编辑器框架直接耦合。
 *
 * 说明：
 * - 上层可基于 lsp4j Launcher 自行实现；
 * - 也允许 app 侧把第三方会话适配到该接口。
 */
interface LspSession {

    /**
     * 当前活跃文档 URI（file://...）。
     */
    val documentUri: String

    val supportsCallHierarchy: Boolean
        get() = false

    fun hover(params: HoverParams): CompletableFuture<Hover>?
    fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>>?
    fun references(params: ReferenceParams): CompletableFuture<List<Location?>>?
    fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>>?
    fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>>?
    fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>>?
    fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<List<CallHierarchyIncomingCall>>?
    fun callHierarchyOutgoingCalls(params: org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams): CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyOutgoingCall>>? = null

    fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol?>>>?
    fun resolveWorkspaceSymbol(unresolved: WorkspaceSymbol): CompletableFuture<WorkspaceSymbol>?
    fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>?

    fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>>?
    fun resolveCodeAction(unresolved: CodeAction): CompletableFuture<CodeAction>?
    fun executeCommand(params: ExecuteCommandParams): CompletableFuture<*>?
    fun prepareRename(params: PrepareRenameParams): CompletableFuture<*>?
    fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit>?

    /**
     * 用于 clangd 扩展请求（如 textDocument/switchSourceHeader）。
     */
    fun customRequest(method: String, params: Any): CompletableFuture<*>? = null
}
