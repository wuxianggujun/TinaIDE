package com.wuxianggujun.tinaide.core.packages

import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionBudget
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import com.wuxianggujun.tinaide.core.common.io.ArchivePathSafety
import com.wuxianggujun.tinaide.core.common.io.ZipArchiveValidator
import java.io.File
import java.util.zip.ZipFile

internal object PackageArchivePolicy {
    private const val MEBIBYTE = 1024L * 1024L

    val limits = ArchiveExtractionLimits(
        maxArchiveBytes = 512L * MEBIBYTE,
        maxExpandedBytes = 2L * 1024L * MEBIBYTE,
        maxEntryBytes = 512L * MEBIBYTE,
        maxEntryCount = 50_000,
        maxCompressionRatio = 500L,
    )

    fun extractZip(
        archiveFile: File,
        targetDir: File,
        progress: (Float) -> Unit = {},
    ) {
        ZipArchiveValidator.validate(archiveFile, limits)
        val budget = ArchiveExtractionBudget(limits)
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries().toList()
            val total = entries.size.coerceAtLeast(1)

            entries.forEachIndexed { index, entry ->
                budget.beginEntry(entry.name, entry.size)
                val entryFile = ArchivePathSafety.resolveEntryFile(targetDir, entry.name, "zip entry")

                if (entry.isDirectory) {
                    check(entryFile.mkdirs() || entryFile.isDirectory) {
                        "Failed to create package directory: ${entry.name}"
                    }
                } else {
                    val parentDir = checkNotNull(entryFile.parentFile)
                    check(parentDir.mkdirs() || parentDir.isDirectory) {
                        "Failed to create package parent directory: ${entry.name}"
                    }
                    zip.getInputStream(entry).use { input ->
                        entryFile.outputStream().use { output ->
                            budget.copyEntry(input, output, entry.name)
                        }
                    }
                }

                val extracted = index + 1
                if (extracted % 10 == 0 || extracted == total) {
                    progress(extracted.toFloat() / total)
                }
            }
        }
    }
}
