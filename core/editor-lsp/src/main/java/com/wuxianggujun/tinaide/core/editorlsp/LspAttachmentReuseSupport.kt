package com.wuxianggujun.tinaide.core.editorlsp

/**
 * 已打开标签再次变为活动页，或切到另一份同项目 C/C++ 文件时，如何对待现有 clangd。
 *
 * clangd 同一时刻只认一份当前文档，所以离开某个 tab 后 `isCurrentDocument` 会变 false。
 * 那不代表语言服务断了；只要客户端会话还活着，切回来只应 didClose/didOpen，
 * 不要把状态打成 Connecting，也不要拆掉共享会话再 obtainOrCreate。
 */
internal object LspAttachmentReuseSupport {
    fun shouldReuseExistingAttachment(
        sessionConnected: Boolean,
        sessionFilePath: String?,
        requestedFilePath: String,
    ): Boolean {
        if (!sessionConnected) return false
        return sameNormalizedPath(sessionFilePath, requestedFilePath)
    }

    fun shouldAnnounceConnecting(sharedSessionConnected: Boolean): Boolean = !sharedSessionConnected

    fun shouldReleaseTabSessionBeforeSharedAttach(sharedSessionConnected: Boolean): Boolean =
        !sharedSessionConnected

    fun canActivateExistingSharedCxx(
        sharedSessionConnected: Boolean,
        usingRemoteLsp: Boolean,
        wantRemote: Boolean,
        sharedWorkspaceRoot: String?,
        requestedWorkspaceRoot: String?,
    ): Boolean {
        if (!sharedSessionConnected) return false
        if (usingRemoteLsp != wantRemote) return false
        return sameNormalizedPath(sharedWorkspaceRoot, requestedWorkspaceRoot)
    }

    fun needsDocumentActivation(
        sessionKindIsCxx: Boolean,
        sessionConnected: Boolean,
        isCurrentDocument: Boolean,
    ): Boolean = sessionKindIsCxx && sessionConnected && !isCurrentDocument

    private fun sameNormalizedPath(left: String?, right: String?): Boolean {
        val attached = left?.normalizePath().orEmpty()
        val requested = right?.normalizePath().orEmpty()
        return attached.isNotEmpty() && attached == requested
    }

    private fun String.normalizePath(): String = replace('\\', '/')
}
