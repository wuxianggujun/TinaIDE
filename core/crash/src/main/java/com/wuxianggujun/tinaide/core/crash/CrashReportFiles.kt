package com.wuxianggujun.tinaide.core.crash

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object CrashReportFiles {
    const val MAX_PREVIEW_CHARS = 120_000

    fun resolveOriginal(tombstoneRoot: File, fileName: String): File? {
        if (fileName.isBlank()) return null
        require(fileName != "." && fileName != ".." && '/' !in fileName && '\\' !in fileName && ':' !in fileName && '\u0000' !in fileName) {
            "Invalid crash log file name"
        }
        val root = tombstoneRoot.canonicalFile
        val candidate = File(root, fileName).canonicalFile
        require(candidate.parentFile == root) { "Crash log is outside the tombstone directory" }
        return candidate.takeIf { it.isFile }
    }

    fun readPreview(file: File): String = file.reader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(MAX_PREVIEW_CHARS + 1)
        var length = 0
        while (length < buffer.size) {
            val count = reader.read(buffer, length, buffer.size - length)
            if (count < 0) break
            length += count
        }
        var previewLength = minOf(length, MAX_PREVIEW_CHARS)
        if (previewLength > 0 && Character.isHighSurrogate(buffer[previewLength - 1])) previewLength--
        String(buffer, 0, previewLength)
    }

    fun export(tombstoneRoot: File, fileName: String, fallbackReport: String, destination: File): File {
        if (destination.parentFile?.let { it.isDirectory || it.mkdirs() } != true) {
            throw IOException("Cannot create crash report export directory")
        }
        val original = resolveOriginal(tombstoneRoot, fileName)
        if (fileName.isNotBlank() && original == null) {
            throw IOException("Original crash log is no longer available")
        }
        val temporary = File.createTempFile("crash-export-", ".tmp", destination.parentFile)
        try {
            if (original != null) {
                original.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            } else {
                temporary.writeText(fallbackReport, Charsets.UTF_8)
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        return destination
    }
}
