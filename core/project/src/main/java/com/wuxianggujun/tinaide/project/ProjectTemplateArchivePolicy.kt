package com.wuxianggujun.tinaide.project

import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import com.wuxianggujun.tinaide.core.common.io.ZipArchiveSummary
import com.wuxianggujun.tinaide.core.common.io.ZipArchiveValidator
import java.io.File
import java.util.zip.ZipFile

object ProjectTemplateArchivePolicy {
    private const val MEBIBYTE = 1024L * 1024L

    val limits = ArchiveExtractionLimits(
        maxArchiveBytes = 128L * MEBIBYTE,
        maxExpandedBytes = 512L * MEBIBYTE,
        maxEntryBytes = 128L * MEBIBYTE,
        maxEntryCount = 10_000,
        maxCompressionRatio = 250L,
    )

    fun validate(zipFile: File): ZipArchiveSummary {
        val summary = ZipArchiveValidator.validate(zipFile, limits)
        val hasTemplatePayload = ZipFile(zipFile).use { archive ->
            archive.entries().asSequence().any { entry ->
                !entry.isDirectory && !ProjectTemplateMetadataReader.isMetadataEntry(entry.name)
            }
        }
        require(hasTemplatePayload) { "Project template archive contains no project files" }
        return summary
    }
}
