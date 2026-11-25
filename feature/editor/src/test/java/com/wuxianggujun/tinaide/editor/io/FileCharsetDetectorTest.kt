package com.wuxianggujun.tinaide.editor.io

import com.google.common.truth.Truth.assertThat
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Test

class FileCharsetDetectorTest {
    @Test
    fun editingDetection_shouldRecognizeGbkAfterAsciiOnlySample() {
        val file = Files.createTempFile("charset-ascii-prefix", ".txt").toFile()
        try {
            val text = "a".repeat(12_000) + "中文"
            file.writeText(text, Charset.forName("GBK"))
            val charset = FileCharsetDetector.detectForEditing(file)
            assertThat(charset).isEqualTo(Charset.forName("GBK"))
            assertThat(FileCharsetDetector.readTextStrict(file, charset)).isEqualTo(text)
        } finally {
            file.delete()
        }
    }

    @Test
    fun editingDetection_shouldRejectMalformedBomFileAndInvalidTrailingBytes() {
        val file = Files.createTempFile("charset-malformed", ".txt").toFile()
        try {
            for (bytes in listOf(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0xff.toByte()),
                "a".repeat(9_000).toByteArray() + byteArrayOf(0xff.toByte()))) {
                file.writeBytes(bytes)
                assertThat(runCatching { FileCharsetDetector.detectForEditing(file) }.exceptionOrNull())
                    .isInstanceOf(java.nio.charset.CharacterCodingException::class.java)
                assertThat(file.readBytes()).isEqualTo(bytes)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun strictRead_shouldRejectFileThatChangedAfterDetection() {
        val file = Files.createTempFile("charset-changed", ".txt").toFile()
        try {
            file.writeText("valid")
            val charset = FileCharsetDetector.detectForEditing(file)
            file.writeBytes(byteArrayOf(0xff.toByte()))
            assertThat(runCatching { FileCharsetDetector.readTextStrict(file, charset) }.exceptionOrNull())
                .isInstanceOf(java.nio.charset.CharacterCodingException::class.java)
        } finally {
            file.delete()
        }
    }

    @Test
    fun detect_shouldKeepUtf8WhenSampleSplitsMultibyteCharacters() {
        val file = Files.createTempFile("charset-boundary", ".txt").toFile()
        try {
            listOf("é", "中", "🙂").forEach { character ->
                for (bytesInSample in 1 until character.toByteArray(Charsets.UTF_8).size) {
                    file.writeText("a".repeat(8192 - bytesInSample) + character + "\n", Charsets.UTF_8)
                    assertThat(FileCharsetDetector.detect(file)).isEqualTo(Charsets.UTF_8)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun detect_shouldKeepGbkForGbkChineseText() {
        val file = Files.createTempFile("charset-gbk", ".txt").toFile()
        try {
            val gbk = Charset.forName("GBK")
            file.writeText("旧内容", gbk)
            assertThat(FileCharsetDetector.detect(file)).isEqualTo(gbk)
        } finally {
            file.delete()
        }
    }
}
