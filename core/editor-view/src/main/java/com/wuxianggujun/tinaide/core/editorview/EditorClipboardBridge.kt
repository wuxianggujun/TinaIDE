package com.wuxianggujun.tinaide.core.editorview

import android.os.SystemClock

/**
 * 在部分 ROM/IME 组合下，系统粘贴链路可能将大文本截断。
 * 这里保留编辑器最近一次复制文本，用于在“明显是截断前缀”时恢复完整内容。
 */
internal object EditorClipboardBridge {
    private const val COPY_CACHE_TTL_MS = 120_000L
    private const val KNOWN_TRUNCATION_LIMIT_CHARS = 512
    private const val TRUNCATION_LIMIT_TOLERANCE_CHARS = 8

    private data class CopiedTextSnapshot(
        val text: String,
        val copiedAtUptimeMs: Long,
    )

    @Volatile
    private var lastCopied: CopiedTextSnapshot? = null

    fun rememberCopiedText(text: String) {
        if (text.isEmpty()) return
        lastCopied = CopiedTextSnapshot(
            text = text,
            copiedAtUptimeMs = SystemClock.uptimeMillis(),
        )
    }

    /**
     * 仅当系统文本是最近复制文本的截断前缀时才替换，避免污染正常粘贴行为。
     */
    fun recoverPossiblyTruncatedClipboardText(systemText: String?): String? {
        val source = systemText?.takeIf { it.isNotEmpty() } ?: return null
        val cached = recentCopiedText() ?: return source
        return if (isRecoverableTruncatedPrefix(source, cached)) {
            cached
        } else {
            source
        }
    }

    /**
     * IME 直接 commitText 粘贴时，系统剪贴板链路可能不会经过本地读取。
     * 当大文本 commit 与最近复制文本呈“截断前缀关系”时，恢复为完整文本。
     */
    fun recoverPossiblyTruncatedImeCommitText(rawText: String): String {
        if (rawText.isEmpty()) return rawText
        val cached = recentCopiedText() ?: return rawText
        return if (isRecoverableTruncatedPrefix(rawText, cached)) {
            cached
        } else {
            rawText
        }
    }

    private fun recentCopiedText(): String? {
        val snapshot = lastCopied ?: return null
        if (!isRecentCopy(snapshot.copiedAtUptimeMs, SystemClock.uptimeMillis())) return null
        return snapshot.text
    }

    internal fun isRecentCopy(copiedAtUptimeMs: Long, nowUptimeMs: Long): Boolean =
        nowUptimeMs - copiedAtUptimeMs in 0L..COPY_CACHE_TTL_MS

    private fun isRecoverableTruncatedPrefix(candidate: String, cached: String): Boolean {
        val minimumTruncatedLength = KNOWN_TRUNCATION_LIMIT_CHARS - TRUNCATION_LIMIT_TOLERANCE_CHARS
        return candidate.length in minimumTruncatedLength..KNOWN_TRUNCATION_LIMIT_CHARS &&
            cached.length > KNOWN_TRUNCATION_LIMIT_CHARS &&
            cached.startsWith(candidate)
    }
}
