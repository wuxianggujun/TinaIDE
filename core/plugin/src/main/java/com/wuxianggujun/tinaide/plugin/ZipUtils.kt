package com.wuxianggujun.tinaide.plugin

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal enum class PluginArchiveFailure {
    PACKAGE_TOO_LARGE,
    TOO_MANY_ENTRIES,
    ENTRY_TOO_LARGE,
    EXPANDED_TOO_LARGE,
    COMPRESSION_RATIO_TOO_HIGH,
    LUA_FILE_TOO_LARGE,
    LUA_SOURCES_TOO_LARGE,
    INVALID_ENTRY,
    DUPLICATE_ENTRY,
}

internal class PluginArchiveException(
    val failure: PluginArchiveFailure,
    val entryName: String? = null,
) : IllegalArgumentException("Plugin archive rejected: ${failure.name}")

object ZipUtils {
    const val MAX_PACKAGE_BYTES: Long = 64L * 1024 * 1024
    const val MAX_EXPANDED_BYTES: Long = 256L * 1024 * 1024
    const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024
    const val MAX_ENTRY_COUNT: Int = 4096
    const val MAX_COMPRESSION_RATIO: Long = 100
    const val MAX_LUA_FILE_BYTES: Long = 1024L * 1024
    const val MAX_LUA_SOURCE_BYTES: Long = 8L * 1024 * 1024

    fun unzipToDirectory(zipFile: File, destDir: File) {
        require(zipFile.isFile) { "Plugin archive was not found" }
        if (zipFile.length() > MAX_PACKAGE_BYTES) {
            throw PluginArchiveException(PluginArchiveFailure.PACKAGE_TOO_LARGE)
        }

        validateCentralDirectory(zipFile)
        require(destDir.mkdirs() || destDir.isDirectory) { "Unable to create plugin staging directory" }

        val destination = destDir.canonicalFile
        val seenEntries = HashSet<String>()
        var entryCount = 0
        var expandedBytes = 0L
        var luaSourceBytes = 0L

        FileInputStream(zipFile).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount += 1
                    if (entryCount > MAX_ENTRY_COUNT) {
                        throw PluginArchiveException(PluginArchiveFailure.TOO_MANY_ENTRIES)
                    }

                    val entryName = normalizeAndValidateEntryName(entry.name)
                    if (!seenEntries.add(entryName)) {
                        throw PluginArchiveException(PluginArchiveFailure.DUPLICATE_ENTRY, entryName)
                    }
                    val outputFile = resolveEntry(destination, entryName)

                    if (entry.isDirectory) {
                        require(outputFile.mkdirs() || outputFile.isDirectory) { "Unable to create plugin directory" }
                    } else {
                        outputFile.parentFile?.let { parent ->
                            require(parent.mkdirs() || parent.isDirectory) { "Unable to create plugin directory" }
                        }
                        outputFile.outputStream().use { output ->
                            val entryBytes = copyBounded(zip, output, entryName) { copied ->
                                if (expandedBytes + copied > MAX_EXPANDED_BYTES) {
                                    throw PluginArchiveException(PluginArchiveFailure.EXPANDED_TOO_LARGE)
                                }
                            }
                            expandedBytes += entryBytes
                            if (entryName.endsWith(".lua", ignoreCase = true)) {
                                if (entryBytes > MAX_LUA_FILE_BYTES) {
                                    throw PluginArchiveException(PluginArchiveFailure.LUA_FILE_TOO_LARGE, entryName)
                                }
                                luaSourceBytes += entryBytes
                                if (luaSourceBytes > MAX_LUA_SOURCE_BYTES) {
                                    throw PluginArchiveException(PluginArchiveFailure.LUA_SOURCES_TOO_LARGE)
                                }
                            }
                        }
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun validateCentralDirectory(zipFile: File) {
        ZipFile(zipFile).use { archive ->
            val entries = archive.entries()
            var count = 0
            var declaredExpandedBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count += 1
                if (count > MAX_ENTRY_COUNT) {
                    throw PluginArchiveException(PluginArchiveFailure.TOO_MANY_ENTRIES)
                }
                val entryName = normalizeAndValidateEntryName(entry.name)
                val size = entry.size
                if (size > MAX_ENTRY_BYTES) {
                    throw PluginArchiveException(PluginArchiveFailure.ENTRY_TOO_LARGE, entryName)
                }
                if (size >= 0L) {
                    declaredExpandedBytes += size
                    if (declaredExpandedBytes > MAX_EXPANDED_BYTES) {
                        throw PluginArchiveException(PluginArchiveFailure.EXPANDED_TOO_LARGE)
                    }
                    if (entryName.endsWith(".lua", ignoreCase = true) && size > MAX_LUA_FILE_BYTES) {
                        throw PluginArchiveException(PluginArchiveFailure.LUA_FILE_TOO_LARGE, entryName)
                    }
                }
                val compressedSize = entry.compressedSize
                val exceedsCompressionRatio = size > 0L &&
                    (compressedSize == 0L ||
                        compressedSize > 0L && size > compressedSize * MAX_COMPRESSION_RATIO)
                if (exceedsCompressionRatio) {
                    throw PluginArchiveException(PluginArchiveFailure.COMPRESSION_RATIO_TOO_HIGH, entryName)
                }
            }
        }
    }

    private fun copyBounded(
        input: ZipInputStream,
        output: OutputStream,
        entryName: String,
        validateTotal: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > MAX_ENTRY_BYTES) {
                throw PluginArchiveException(PluginArchiveFailure.ENTRY_TOO_LARGE, entryName)
            }
            validateTotal(copied)
            output.write(buffer, 0, count)
        }
        return copied
    }

    private fun normalizeAndValidateEntryName(rawName: String): String {
        val name = rawName.replace('\\', '/').trimStart()
        if (name.isBlank() || name.startsWith('/') || name.contains('\u0000') || name.contains(':')) {
            throw PluginArchiveException(PluginArchiveFailure.INVALID_ENTRY, rawName.take(160))
        }
        val segments = name.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            throw PluginArchiveException(PluginArchiveFailure.INVALID_ENTRY, rawName.take(160))
        }
        return segments.joinToString("/") + if (name.endsWith('/')) "/" else ""
    }

    private fun resolveEntry(destination: File, entryName: String): File {
        val outputFile = File(destination, entryName).canonicalFile
        val destinationPrefix = destination.path + File.separator
        if (!outputFile.path.startsWith(destinationPrefix)) {
            throw PluginArchiveException(PluginArchiveFailure.INVALID_ENTRY, entryName)
        }
        return outputFile
    }
}
