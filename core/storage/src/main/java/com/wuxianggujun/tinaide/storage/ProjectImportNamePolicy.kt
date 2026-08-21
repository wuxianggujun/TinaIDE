package com.wuxianggujun.tinaide.storage

import java.nio.charset.StandardCharsets

internal object ProjectImportNamePolicy {
    private const val MAX_PROJECT_NAME_BYTES = 240
    private const val MAX_CACHE_FILE_NAME_BYTES = 190
    private val invalidFileNameChars = Regex("[\\\\/:*?\"<>|]")

    fun projectName(rawName: String): String {
        val normalized = sanitizeBaseName(rawName)
            .trim()
            .trim('.')
            .truncateUtf8(MAX_PROJECT_NAME_BYTES)
            .trimEnd('.')
        return normalized.ifBlank { "imported_project" }
    }

    fun cacheFileName(rawName: String): String = sanitizeBaseName(rawName)
        .truncateUtf8(MAX_CACHE_FILE_NAME_BYTES)
        .ifBlank { "imported_project.zip" }

    private fun sanitizeBaseName(rawName: String): String = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .map { char -> if (char.isISOControl()) '_' else char }
        .joinToString(separator = "")
        .replace(invalidFileNameChars, "_")

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return this

        val result = StringBuilder()
        var index = 0
        var usedBytes = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val byteCount = text.toByteArray(StandardCharsets.UTF_8).size
            if (usedBytes + byteCount > maxBytes) break
            result.append(text)
            usedBytes += byteCount
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}
