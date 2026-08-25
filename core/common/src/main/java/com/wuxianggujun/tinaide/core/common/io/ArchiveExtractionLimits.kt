package com.wuxianggujun.tinaide.core.common.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

data class ArchiveExtractionLimits(
    val maxArchiveBytes: Long,
    val maxExpandedBytes: Long,
    val maxEntryBytes: Long,
    val maxEntryCount: Int,
    val maxCompressionRatio: Long,
    val maxPathDepth: Int = 128,
) {
    init {
        require(maxArchiveBytes > 0)
        require(maxExpandedBytes > 0)
        require(maxEntryBytes > 0)
        require(maxEntryCount > 0)
        require(maxCompressionRatio > 0)
        require(maxPathDepth > 0)
    }
}

class ArchiveLimitException(message: String) : IOException(message)

data class ZipArchiveSummary(
    val entryCount: Int,
    val fileCount: Int,
    val declaredExpandedBytes: Long,
)

class ArchiveExtractionBudget(
    private val limits: ArchiveExtractionLimits,
    private val archiveBytes: Long? = null,
) {
    private var entryCount = 0
    private var expandedBytes = 0L
    private val seenPaths = HashSet<String>()

    fun beginEntry(
        entryName: String,
        declaredSize: Long = -1L,
        isDirectory: Boolean = false,
    ) {
        val safeName = ArchivePathSafety.sanitizeRelativePath(entryName)
        val isRootDirectoryPlaceholder =
            isDirectory && ArchivePathSafety.isRootDirectoryPlaceholder(entryName)
        if (safeName.isBlank() && !isRootDirectoryPlaceholder) {
            throw ArchiveLimitException("Archive contains an empty entry path")
        }
        entryCount += 1
        if (entryCount > limits.maxEntryCount) {
            throw ArchiveLimitException("Archive has too many entries")
        }
        if (!isRootDirectoryPlaceholder) {
            requirePathDepth(safeName, limits.maxPathDepth)
            if (!seenPaths.add(safeName)) {
                throw ArchiveLimitException("Archive contains a duplicate entry: $safeName")
            }
        }
        if (declaredSize > limits.maxEntryBytes) {
            throw ArchiveLimitException("Archive entry is too large: $entryName")
        }
        if (declaredSize >= 0L && declaredSize > limits.maxExpandedBytes - expandedBytes) {
            throw ArchiveLimitException("Archive expands beyond the allowed size")
        }
    }

    fun copyEntry(
        input: InputStream,
        output: OutputStream,
        entryName: String,
        ensureActive: () -> Unit = {},
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            entryBytes += count.toLong()
            if (entryBytes > limits.maxEntryBytes) {
                throw ArchiveLimitException("Archive entry is too large: $entryName")
            }
            if (count.toLong() > limits.maxExpandedBytes - expandedBytes) {
                throw ArchiveLimitException("Archive expands beyond the allowed size")
            }
            val nextExpandedBytes = expandedBytes + count.toLong()
            if (hasExcessiveExpansionRatio(nextExpandedBytes, archiveBytes, limits.maxCompressionRatio)) {
                throw ArchiveLimitException("Archive compression ratio is too high")
            }
            output.write(buffer, 0, count)
            expandedBytes = nextExpandedBytes
        }
        return entryBytes
    }

    fun readEntry(input: InputStream, entryName: String): ByteArray {
        val initialSize = minOf(limits.maxEntryBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt()
        return ByteArrayOutputStream(initialSize).use { output ->
            copyEntry(input, output, entryName)
            output.toByteArray()
        }
    }
}

private fun hasExcessiveExpansionRatio(
    expandedBytes: Long,
    archiveBytes: Long?,
    maxRatio: Long,
): Boolean {
    if (archiveBytes == null || archiveBytes <= 0L || expandedBytes <= 0L) return false
    if (archiveBytes > Long.MAX_VALUE / maxRatio) return false
    return expandedBytes > archiveBytes * maxRatio
}

object ZipArchiveValidator {
    fun validate(
        zipFile: File,
        limits: ArchiveExtractionLimits,
    ): ZipArchiveSummary {
        require(zipFile.isFile) { "Archive file does not exist: ${zipFile.absolutePath}" }
        if (zipFile.length() > limits.maxArchiveBytes) {
            throw ArchiveLimitException("Archive is larger than the allowed size")
        }

        val seenPaths = HashSet<String>()
        var entryCount = 0
        var fileCount = 0
        var declaredExpandedBytes = 0L
        ZipFile(zipFile).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                if (entryCount > limits.maxEntryCount) {
                    throw ArchiveLimitException("Archive has too many entries")
                }

                val safeName = ArchivePathSafety.sanitizeRelativePath(entry.name)
                if (safeName.isBlank()) {
                    throw ArchiveLimitException("Archive contains an empty entry path")
                }
                requirePathDepth(safeName, limits.maxPathDepth)
                if (!seenPaths.add(safeName)) {
                    throw ArchiveLimitException("Archive contains a duplicate entry: $safeName")
                }

                if (entry.isDirectory) continue
                fileCount += 1
                val size = entry.size
                if (size > limits.maxEntryBytes) {
                    throw ArchiveLimitException("Archive entry is too large: $safeName")
                }
                if (size >= 0L) {
                    if (size > limits.maxExpandedBytes - declaredExpandedBytes) {
                        throw ArchiveLimitException("Archive expands beyond the allowed size")
                    }
                    declaredExpandedBytes += size
                }

                val compressedSize = entry.compressedSize
                if (hasExcessiveCompressionRatio(size, compressedSize, limits.maxCompressionRatio)) {
                    throw ArchiveLimitException("Archive entry compression ratio is too high: $safeName")
                }
            }
        }
        return ZipArchiveSummary(entryCount, fileCount, declaredExpandedBytes)
    }

    private fun hasExcessiveCompressionRatio(
        expandedSize: Long,
        compressedSize: Long,
        maxRatio: Long,
    ): Boolean {
        if (expandedSize <= 0L || compressedSize < 0L) return false
        if (compressedSize == 0L) return true
        if (compressedSize > Long.MAX_VALUE / maxRatio) return false
        return expandedSize > compressedSize * maxRatio
    }
}

private fun requirePathDepth(path: String, maxPathDepth: Int) {
    val pathDepth = path.count { it == '/' } + 1
    if (pathDepth > maxPathDepth) {
        throw ArchiveLimitException("Archive entry path is too deep: $path")
    }
}
