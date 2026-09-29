package com.wuxianggujun.tinaide.core.editorview

import android.os.SystemClock
import java.util.Locale
import timber.log.Timber

internal enum class EditorTouchLogCategory {
    INTERNAL,
    SCALE,
    FOCUS,
    SCROLL,
    FLING
}

internal class EditorTouchDiagnostics(
    private val flagsProvider: () -> EditorTouchDiagnosticsFlags = { EditorTouchDiagnosticsFlags() }
) {
    private val throttledLogTimestamps = HashMap<String, Long>()

    fun isEnabled(): Boolean = flagsProvider().enabled

    fun isVerboseEnabled(): Boolean = flagsProvider().let { it.enabled && it.verbose }

    fun isScaleEnabled(): Boolean = flagsProvider().let { it.enabled && it.scale }

    fun isFocusEnabled(): Boolean = flagsProvider().let { it.enabled && it.focus }

    fun isScrollEnabled(): Boolean = flagsProvider().let { it.enabled && it.scroll }

    fun isFlingEnabled(): Boolean = flagsProvider().let { it.enabled && it.fling }

    fun isEnabled(category: EditorTouchLogCategory): Boolean {
        val flags = flagsProvider()
        if (!flags.enabled) return false
        return when (category) {
            EditorTouchLogCategory.INTERNAL -> flags.verbose
            EditorTouchLogCategory.SCALE -> flags.scale
            EditorTouchLogCategory.FOCUS -> flags.focus
            EditorTouchLogCategory.SCROLL -> flags.scroll
            EditorTouchLogCategory.FLING -> flags.fling
        }
    }

    fun log(message: String, verbose: Boolean = false) {
        log(category = EditorTouchLogCategory.INTERNAL, message = message, verbose = verbose)
    }

    fun log(category: EditorTouchLogCategory, message: String, verbose: Boolean = false) {
        if (!shouldLog(category, verbose)) return
        Timber.tag(tagFor(category)).d(message)
    }

    inline fun logThrottled(
        category: EditorTouchLogCategory,
        throttleKey: String,
        minIntervalMs: Long,
        verbose: Boolean = false,
        message: () -> String
    ) {
        if (!shouldLog(category, verbose)) return
        val safeIntervalMs = minIntervalMs.coerceAtLeast(0L)
        if (safeIntervalMs > 0L) {
            val now = SystemClock.uptimeMillis()
            if (!tryAcquireThrottle(category = category, key = throttleKey, now = now, intervalMs = safeIntervalMs)) {
                return
            }
        }
        Timber.tag(tagFor(category)).d(message())
    }

    private fun shouldLog(category: EditorTouchLogCategory, verbose: Boolean): Boolean {
        if (!isEnabled(category)) return false
        if (verbose && !isVerboseEnabled()) return false
        return true
    }

    private fun tagFor(category: EditorTouchLogCategory): String = "EditorTouchDiag.${category.name.lowercase(Locale.ROOT)}"

    @Synchronized
    private fun tryAcquireThrottle(
        category: EditorTouchLogCategory,
        key: String,
        now: Long,
        intervalMs: Long
    ): Boolean {
        val throttleToken = "${category.name}:$key"
        val last = throttledLogTimestamps[throttleToken] ?: 0L
        if (now - last < intervalMs) {
            return false
        }
        throttledLogTimestamps[throttleToken] = now
        return true
    }
}
