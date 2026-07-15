package com.wuxianggujun.tinaide.ui.compose.state.editor

/**
 * Applies an LSP owner-stop signal only to the attachment generation that created it.
 *
 * A late callback from an older plugin process must never tear down a replacement session.
 */
internal class PluginLspOwnerStopHandler(
    private val transitionIfCurrent: () -> Boolean,
) {
    fun handle(): Boolean = transitionIfCurrent()
}
