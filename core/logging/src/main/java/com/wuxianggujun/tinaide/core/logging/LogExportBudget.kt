package com.wuxianggujun.tinaide.core.logging

import java.io.InputStream
import java.util.zip.ZipOutputStream

/**
 * Bounds how many *input* bytes a log bundle may contain.
 *
 * Automatic crash uploads must never be dropped just because a device has a large
 * Timber history: instead the bundle keeps the newest data of every source and stops
 * writing once the budget is exhausted. Text is compressed afterwards, so the final
 * ZIP is comfortably below the caller's byte limit.
 *
 * A child budget created with [reserve] guarantees its slice to one noisy source
 * (logcat, Timber) so it cannot starve the small, high-value entries.
 */
internal class LogExportBudget private constructor(
    private val limitBytes: Long,
    private val parent: LogExportBudget?
) {
    constructor(limitBytes: Long) : this(limitBytes, null)

    private var used = 0L

    /** True when at least one write was clipped by the budget. */
    var truncated: Boolean = false
        private set

    fun remaining(): Long = (limitBytes - used).coerceAtLeast(0L)

    fun exhausted(): Boolean = remaining() <= 0L

    /**
     * Allocates a child budget that may consume at most [fraction] of the currently
     * unused bytes. Anything the child writes is charged to this budget as well.
     */
    fun reserve(fraction: Double): LogExportBudget {
        val slice = (remaining() * fraction.coerceIn(0.0, 1.0)).toLong()
        return LogExportBudget(slice.coerceAtLeast(0L), this)
    }

    fun write(out: ZipOutputStream, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val allowed = minOf(bytes.size.toLong(), remaining()).toInt()
        if (allowed <= 0) {
            markTruncated()
            return false
        }
        out.write(bytes, 0, allowed)
        charge(allowed.toLong())
        if (allowed < bytes.size) {
            markTruncated()
            return false
        }
        return true
    }

    fun writeText(out: ZipOutputStream, text: String): Boolean =
        write(out, text.toByteArray(Charsets.UTF_8))

    fun copy(out: ZipOutputStream, input: InputStream): Long {
        val buffer = ByteArray(16 * 1024)
        var copied = 0L
        while (true) {
            if (exhausted()) {
                markTruncated()
                break
            }
            val read = input.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read.toLong(), remaining()).toInt()
            out.write(buffer, 0, allowed)
            charge(allowed.toLong())
            copied += allowed
            if (allowed < read) {
                markTruncated()
                break
            }
        }
        return copied
    }

    private fun charge(bytes: Long) {
        used += bytes
        parent?.charge(bytes)
    }

    private fun markTruncated() {
        truncated = true
        parent?.markTruncated()
    }
}
