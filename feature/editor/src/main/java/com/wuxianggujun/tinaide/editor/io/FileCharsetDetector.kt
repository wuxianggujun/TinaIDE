package com.wuxianggujun.tinaide.editor.io

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文件编码检测工具。
 *
 * 检测策略：
 * 1. 优先检测 BOM。
 * 2. 无 BOM 时，启发式区分常见 UTF-8 / GBK。
 * 3. 默认返回 UTF-8。
 */
object FileCharsetDetector {

    private const val SAMPLE_SIZE_BYTES = 8192

    /** Validate the complete file before editing; ASCII-only prefixes do not identify the encoding. */
    fun detectForEditing(file: File): Charset {
        val bom = file.inputStream().use { stream ->
            val prefix = ByteArray(4)
            var length = 0
            while (length < prefix.size) {
                val count = stream.read(prefix, length, prefix.size - length)
                if (count < 0) break
                length += count
            }
            detectBom(prefix, length)
        }
        if (bom != null) {
            validate(file, bom)
            return bom
        }
        val candidate = detect(file)
        try {
            validate(file, candidate)
            return candidate
        } catch (invalid: CharacterCodingException) {
            if (candidate != Charsets.UTF_8) throw invalid
            // The first non-ASCII bytes may occur after the initial sample.
            val gbk = Charset.forName("GBK")
            validate(file, gbk)
            return gbk
        }
    }

    fun strictReader(file: File, charset: Charset): InputStreamReader = InputStreamReader(
        file.inputStream(),
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT),
    )

    fun readTextStrict(file: File, charset: Charset): String = strictReader(file, charset).use { it.readText() }

    private fun validate(file: File, charset: Charset) {
        strictReader(file, charset).use { reader ->
            val buffer = CharArray(SAMPLE_SIZE_BYTES)
            while (reader.read(buffer) >= 0) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Text validation interrupted")
            }
        }
    }

    fun detect(file: File): Charset {
        if (!file.exists() || !file.isFile) {
            return Charsets.UTF_8
        }

        return try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(SAMPLE_SIZE_BYTES)
                var bytesRead = 0
                while (bytesRead < buffer.size) {
                    val count = stream.read(buffer, bytesRead, buffer.size - bytesRead)
                    if (count < 0) break
                    bytesRead += count
                }
                if (bytesRead <= 0) {
                    return Charsets.UTF_8
                }

                val reachedEnd = stream.read() == -1
                detectBom(buffer, bytesRead) ?: detectHeuristic(buffer, bytesRead, reachedEnd)
            }
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    private fun detectBom(buffer: ByteArray, bytesRead: Int): Charset? = when {
        bytesRead >= 3 &&
            buffer[0] == 0xEF.toByte() &&
            buffer[1] == 0xBB.toByte() &&
            buffer[2] == 0xBF.toByte() -> Charsets.UTF_8

        bytesRead >= 2 &&
            buffer[0] == 0xFE.toByte() &&
            buffer[1] == 0xFF.toByte() -> Charsets.UTF_16BE

        bytesRead >= 2 &&
            buffer[0] == 0xFF.toByte() &&
            buffer[1] == 0xFE.toByte() -> {
            if (bytesRead >= 4 &&
                buffer[2] == 0x00.toByte() &&
                buffer[3] == 0x00.toByte()
            ) {
                Charset.forName("UTF-32LE")
            } else {
                Charsets.UTF_16LE
            }
        }

        bytesRead >= 4 &&
            buffer[0] == 0x00.toByte() &&
            buffer[1] == 0x00.toByte() &&
            buffer[2] == 0xFE.toByte() &&
            buffer[3] == 0xFF.toByte() -> Charset.forName("UTF-32BE")

        else -> null
    }

    private fun detectHeuristic(buffer: ByteArray, bytesRead: Int, reachedEnd: Boolean): Charset {
        // A sample may end in the middle of a valid character. Only treat an incomplete
        // sequence as malformed when it is also the actual end of the file.
        if (canDecodeSample(Charsets.UTF_8, buffer, bytesRead, reachedEnd)) return Charsets.UTF_8
        val gbk = Charset.forName("GBK")
        return if (canDecodeSample(gbk, buffer, bytesRead, reachedEnd)) gbk else Charsets.UTF_8
    }

    private fun canDecodeSample(charset: Charset, buffer: ByteArray, bytesRead: Int, reachedEnd: Boolean): Boolean =
        !charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(buffer, 0, bytesRead), CharBuffer.allocate(bytesRead), reachedEnd)
            .isError
}
