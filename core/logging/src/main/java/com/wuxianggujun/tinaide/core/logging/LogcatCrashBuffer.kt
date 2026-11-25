package com.wuxianggujun.tinaide.core.logging

/**
 * Selects the TinaIDE-owned entries from the Android `crash` log buffer.
 *
 * `logcat -b crash` is shared by every process on the device, so the raw buffer is
 * never uploaded. Entries are grouped by their crash header and only groups that
 * mention this application's package are kept. This recovers the fatal exception
 * even after the crashing process is gone, without leaking other apps' logs.
 */
internal object LogcatCrashBuffer {
    internal const val MAX_BYTES = 512 * 1024

    private const val BUFFER_HEADER = "--------- beginning of"
    private const val JAVA_FATAL = "FATAL EXCEPTION"
    private const val NATIVE_HEADER = "*** *** *** *** *** *** *** ***"

    fun filter(output: String, packageName: String, maxBytes: Int = MAX_BYTES): String {
        if (output.isBlank() || packageName.isBlank()) return ""

        val groups = mutableListOf<StringBuilder>()
        var current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                groups += current
                current = StringBuilder()
            }
        }

        output.lineSequence().forEach { line ->
            if (line.startsWith(BUFFER_HEADER) ||
                line.contains(JAVA_FATAL) ||
                line.startsWith(NATIVE_HEADER)
            ) {
                flush()
            }
            current.append(line).append('\n')
        }
        flush()

        val selected = groups
            .filter { group -> group.contains(packageName) }
            .joinToString(separator = "")
            .trim()
        if (selected.isEmpty()) return ""
        return if (selected.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            selected
        } else {
            // Keep the tail: the actual fatal block is the most recent part.
            val bytes = selected.toByteArray(Charsets.UTF_8)
            val start = (bytes.size - maxBytes).coerceAtLeast(0)
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        }
    }
}
