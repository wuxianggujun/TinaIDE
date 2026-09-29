package com.wuxianggujun.tinaide.core.editorview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

internal class EditorClipboardCoordinator(
    private val context: Context,
    private val androidClipboardManager: ClipboardManager?,
    private val coroutineScope: CoroutineScope,
    private val onWriteClipboard: suspend (ClipData) -> Unit
) {
    fun readText(): String? {
        val clip = androidClipboardManager?.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val systemText = clip.getItemAt(0).coerceToText(context)?.toString()
        val recovered = EditorClipboardBridge.recoverPossiblyTruncatedClipboardText(systemText)
        if (recovered != null && systemText != null && recovered.length != systemText.length) {
            Timber.tag("EditorImeDiag").d(
                "clipboard recoveredTruncatedText systemChars=%d recoveredChars=%d",
                systemText.length,
                recovered.length
            )
        }
        return recovered
    }

    fun copyText(text: String) {
        EditorClipboardBridge.rememberCopiedText(text)
        coroutineScope.launch {
            onWriteClipboard(ClipData.newPlainText("editor-selection", text))
        }
    }
}
