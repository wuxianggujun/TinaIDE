package com.wuxianggujun.tinaide.core.common.io

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream

/** Extracts data only. Debian maintainer scripts and package-manager configuration are never executed. */
object DebDataExtractor {
    fun extract(archive: File, target: File, limits: ArchiveExtractionLimits, ensureActive: () -> Unit = {}) {
        require(archive.length() <= limits.maxArchiveBytes)
        ArArchiveInputStream(archive.inputStream().buffered()).use { input ->
            var extracted = false
            var entries = 0
            while (true) {
                ensureActive()
                val entry = input.nextArEntry ?: break
                require(++entries <= 8 && entry.length <= limits.maxArchiveBytes)
                if (entry.name in setOf("data.tar", "data.tar.gz", "data.tar.xz", "data.tar.zst")) {
                    require(!extracted) { "Duplicate Debian data archive" }
                    // TarExtractor owns its stream, whereas the outer ar stream still needs validation.
                    val nested = object : FilterInputStream(input) { override fun close() = Unit }
                    TarExtractor.extract(BufferedInputStream(nested), target,
                        symlinkPolicy = TarExtractor.SymlinkPolicy.RELOCATE_ABSOLUTE_INSIDE_ARCHIVE,
                        ensureActive = ensureActive, limits = limits)
                    extracted = true
                }
            }
            require(extracted) { "Missing Debian data archive" }
        }
    }
}
