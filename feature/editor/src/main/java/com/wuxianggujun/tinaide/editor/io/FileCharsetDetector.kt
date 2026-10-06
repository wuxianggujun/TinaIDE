package com.wuxianggujun.tinaide.editor.io

import java.io.File
import java.nio.charset.Charset

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

    fun detect(file: File): Charset {
        if (!file.exists() || !file.isFile) {
            return Charsets.UTF_8
        }

        return try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(SAMPLE_SIZE_BYTES)
                val bytesRead = stream.read(buffer)
                if (bytesRead <= 0) {
                    return Charsets.UTF_8
                }

                detectBom(buffer, bytesRead) ?: detectHeuristic(buffer, bytesRead)
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

    private fun detectHeuristic(buffer: ByteArray, bytesRead: Int): Charset {
        var utf8Score = 0
        var gbkScore = 0
        var index = 0

        while (index < bytesRead) {
            val byte = buffer[index].toInt() and 0xFF
            if (byte < 0x80) {
                index++
                continue
            }

            if (byte and 0xE0 == 0xC0 && index + 1 < bytesRead) {
                val byte2 = buffer[index + 1].toInt() and 0xFF
                if (byte2 and 0xC0 == 0x80) {
                    utf8Score += 2
                    index += 2
                    continue
                }
            } else if (byte and 0xF0 == 0xE0 && index + 2 < bytesRead) {
                val byte2 = buffer[index + 1].toInt() and 0xFF
                val byte3 = buffer[index + 2].toInt() and 0xFF
                if (byte2 and 0xC0 == 0x80 && byte3 and 0xC0 == 0x80) {
                    utf8Score += 3
                    index += 3
                    continue
                }
            } else if (byte and 0xF8 == 0xF0 && index + 3 < bytesRead) {
                val byte2 = buffer[index + 1].toInt() and 0xFF
                val byte3 = buffer[index + 2].toInt() and 0xFF
                val byte4 = buffer[index + 3].toInt() and 0xFF
                if (
                    byte2 and 0xC0 == 0x80 &&
                    byte3 and 0xC0 == 0x80 &&
                    byte4 and 0xC0 == 0x80
                ) {
                    utf8Score += 4
                    index += 4
                    continue
                }
            }

            if (byte in 0x81..0xFE && index + 1 < bytesRead) {
                val byte2 = buffer[index + 1].toInt() and 0xFF
                if (byte2 in 0x40..0x7E || byte2 in 0x80..0xFE) {
                    gbkScore += 2
                    index += 2
                    continue
                }
            }

            index++
        }

        return when {
            utf8Score > gbkScore -> Charsets.UTF_8
            gbkScore > utf8Score -> Charset.forName("GBK")
            else -> Charsets.UTF_8
        }
    }
}
