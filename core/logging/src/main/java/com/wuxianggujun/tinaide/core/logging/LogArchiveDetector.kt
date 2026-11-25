package com.wuxianggujun.tinaide.core.logging

import java.io.File
import java.util.Locale

/**
 * 用户手动上传的日志压缩包格式。
 *
 * AegisHub 把附件当作不透明字节保存，这里只负责客户端侧的格式识别与提示，
 * 不会解压或执行压缩包内容。
 */
enum class LogArchiveFormat(
    val label: String,
    val canonicalExtension: String,
) {
    ZIP("zip", "zip"),
    GZIP("gz", "gz"),
    TAR("tar", "tar"),
}

/**
 * 通过文件头魔数识别日志压缩包格式。
 *
 * 扩展名只用于提示，最终以内容为准，避免把改名的可执行文件或普通日志误传。
 */
object LogArchiveDetector {
    /** tar 的 `ustar` 魔数位于第 257 字节，因此头部至少读取 512 字节。 */
    const val HEADER_BYTES = 512

    private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY_ARCHIVE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_SPANNED_ARCHIVE = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    private val GZIP_HEADER = byteArrayOf(0x1F, 0x8B.toByte())
    private const val TAR_MAGIC_OFFSET = 257
    private val TAR_MAGIC = "ustar".toByteArray(Charsets.US_ASCII)

    fun detect(bytes: ByteArray): LogArchiveFormat? = when {
        bytes.startsWith(ZIP_LOCAL_HEADER) ||
            bytes.startsWith(ZIP_EMPTY_ARCHIVE) ||
            bytes.startsWith(ZIP_SPANNED_ARCHIVE) -> LogArchiveFormat.ZIP

        bytes.startsWith(GZIP_HEADER) -> LogArchiveFormat.GZIP

        bytes.size >= TAR_MAGIC_OFFSET + TAR_MAGIC.size &&
            bytes.copyOfRange(
                TAR_MAGIC_OFFSET,
                TAR_MAGIC_OFFSET + TAR_MAGIC.size
            ).contentEquals(TAR_MAGIC) -> LogArchiveFormat.TAR

        else -> null
    }

    fun detect(file: File): LogArchiveFormat? {
        if (!file.isFile || file.length() <= 0L) return null
        val header = ByteArray(HEADER_BYTES)
        val read = file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count <= 0) break
                offset += count
            }
            offset
        }
        if (read <= 0) return null
        return detect(header.copyOf(read))
    }

    /** 仅按文件名推断格式，用于 UI 提示；不要用它替代内容魔数校验。 */
    fun formatOfFileName(fileName: String): LogArchiveFormat? {
        val lower = fileName.trim().lowercase(Locale.US)
        return when {
            lower.endsWith(".tar.gz") ||
                lower.endsWith(".tgz") ||
                lower.endsWith(".gz") ||
                lower.endsWith(".gzip") -> LogArchiveFormat.GZIP

            lower.endsWith(".tar") -> LogArchiveFormat.TAR
            lower.endsWith(".zip") -> LogArchiveFormat.ZIP
            else -> null
        }
    }

    fun isSupportedFileName(fileName: String): Boolean = formatOfFileName(fileName) != null

    fun matchesFileName(fileName: String, format: LogArchiveFormat): Boolean {
        val lower = fileName.trim().lowercase(Locale.US)
        return when (format) {
            LogArchiveFormat.ZIP -> lower.endsWith(".zip")
            LogArchiveFormat.GZIP ->
                lower.endsWith(".gz") || lower.endsWith(".tgz") || lower.endsWith(".gzip")
            LogArchiveFormat.TAR -> lower.endsWith(".tar")
        }
    }

    /** 内容与扩展名不一致时补上正确扩展名，保证服务端保存的文件名可读。 */
    fun appendExtensionIfMissing(fileName: String, format: LogArchiveFormat): String =
        if (matchesFileName(fileName, format)) {
            fileName
        } else {
            "$fileName.${format.canonicalExtension}"
        }

    /**
     * 归一化用户侧文件名：去掉路径与控制字符，保留受限扩展名，并按 UTF-8 字节数收敛。
     *
     * AegisHub 的附件文件名上限是 200 字节，按字符截断中文名会超限。
     */
    fun safeFileName(raw: String?, fallback: String = "tinaide_logs"): String {
        val cleaned = (raw ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(CONTROL_OR_RESERVED, "_")
            .trim()
            .trimStart('.')
            .ifBlank { fallback }

        // 复合扩展名要整体保留：截断长文件名后 ".tar.gz" 不能退化成 ".gz"。
        val lower = cleaned.lowercase(Locale.US)
        val suffix = ARCHIVE_SUFFIXES.firstOrNull { lower.endsWith(it) }
        val dot = cleaned.lastIndexOf('.')
        val stem = when {
            suffix != null -> cleaned.dropLast(suffix.length)
            dot > 0 -> cleaned.substring(0, dot)
            else -> cleaned
        }
        val extension = when {
            suffix != null -> suffix.drop(1)
            dot > 0 -> cleaned.substring(dot + 1).take(MAX_EXTENSION_CHARS)
            else -> ""
        }
        val safeStem = truncateUtf8Bytes(stem, MAX_STEM_BYTES).ifBlank { fallback }
        return if (extension.isBlank()) safeStem else "$safeStem.$extension"
    }

    /** 按 UTF-8 字节数截断，不切坏多字节字符。 */
    fun truncateUtf8Bytes(value: String, maxBytes: Int): String {
        if (value.isEmpty() || maxBytes <= 0) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private const val MAX_STEM_BYTES = 120
    private const val MAX_EXTENSION_CHARS = 12
    private val CONTROL_OR_RESERVED = Regex("[\\u0000-\\u001f:*?\"<>|]")

    /** 最长匹配优先，保证 `.tar.gz` 不被拆成 `.gz`。 */
    private val ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tgz", ".gzip", ".zip", ".gz", ".tar")

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
