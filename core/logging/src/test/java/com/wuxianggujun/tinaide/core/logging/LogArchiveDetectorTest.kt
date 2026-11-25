package com.wuxianggujun.tinaide.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogArchiveDetectorTest {

    @Test
    fun detectsZipBySignature() {
        assertEquals(
            LogArchiveFormat.ZIP,
            LogArchiveDetector.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        )
        assertEquals(
            LogArchiveFormat.ZIP,
            LogArchiveDetector.detect(byteArrayOf(0x50, 0x4B, 0x05, 0x06))
        )
        assertEquals(
            LogArchiveFormat.ZIP,
            LogArchiveDetector.detect(byteArrayOf(0x50, 0x4B, 0x07, 0x08))
        )
    }

    @Test
    fun detectsGzipBySignature() {
        assertEquals(
            LogArchiveFormat.GZIP,
            LogArchiveDetector.detect(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00))
        )
    }

    @Test
    fun detectsTarByUstarMagicAtOffset257() {
        val header = ByteArray(512)
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        assertEquals(LogArchiveFormat.TAR, LogArchiveDetector.detect(header))
    }

    @Test
    fun rejectsUnknownOrTruncatedInput() {
        assertNull(LogArchiveDetector.detect(ByteArray(0)))
        assertNull(LogArchiveDetector.detect(byteArrayOf(0x50, 0x4B)))
        assertNull(LogArchiveDetector.detect("plain text log".toByteArray()))
        assertNull(LogArchiveDetector.detect(byteArrayOf(0x1F)))
        assertNull(LogArchiveDetector.detect(ByteArray(100) { 'u'.code.toByte() }))
    }

    @Test
    fun mapsSupportedExtensionsIncludingCompoundTarGz() {
        assertEquals(LogArchiveFormat.ZIP, LogArchiveDetector.formatOfFileName("logs.zip"))
        assertEquals(LogArchiveFormat.GZIP, LogArchiveDetector.formatOfFileName("logs.gz"))
        assertEquals(LogArchiveFormat.GZIP, LogArchiveDetector.formatOfFileName("logs.tar.gz"))
        assertEquals(LogArchiveFormat.GZIP, LogArchiveDetector.formatOfFileName("logs.tgz"))
        assertEquals(LogArchiveFormat.TAR, LogArchiveDetector.formatOfFileName("logs.tar"))
        assertEquals(LogArchiveFormat.ZIP, LogArchiveDetector.formatOfFileName("LOGS.ZIP"))
        assertNull(LogArchiveDetector.formatOfFileName("logs.txt"))
        assertTrue(LogArchiveDetector.isSupportedFileName("TinaIDE_logs.tar.gz"))
        assertFalse(LogArchiveDetector.isSupportedFileName("TinaIDE_logs.7z"))
    }

    @Test
    fun appendsCanonicalExtensionOnlyWhenMissing() {
        assertEquals(
            "tinaide_logs.zip",
            LogArchiveDetector.appendExtensionIfMissing("tinaide_logs", LogArchiveFormat.ZIP)
        )
        assertEquals(
            "tinaide_logs.tar.gz",
            LogArchiveDetector.appendExtensionIfMissing("tinaide_logs.tar.gz", LogArchiveFormat.GZIP)
        )
        assertEquals(
            "tinaide_logs.tgz",
            LogArchiveDetector.appendExtensionIfMissing("tinaide_logs.tgz", LogArchiveFormat.GZIP)
        )
        assertEquals(
            "logs.zip.gz",
            LogArchiveDetector.appendExtensionIfMissing("logs.zip", LogArchiveFormat.GZIP)
        )
    }

    @Test
    fun safeFileNameStripsPathsAndControlCharacters() {
        assertEquals("logs.zip", LogArchiveDetector.safeFileName("logs.zip"))
        assertEquals("passwd", LogArchiveDetector.safeFileName("../../etc/passwd"))
        assertEquals("evil.zip", LogArchiveDetector.safeFileName("C:\\Windows\\evil.zip"))
        assertEquals("a_b_c.zip", LogArchiveDetector.safeFileName("a:b*c.zip"))
        assertEquals("tinaide_logs", LogArchiveDetector.safeFileName("   "))
        assertEquals("tinaide_logs", LogArchiveDetector.safeFileName("..."))
        assertEquals("tinaide_logs", LogArchiveDetector.safeFileName(null))
    }

    @Test
    fun safeFileNameKeepsUtf8WithinByteBudget() {
        val longName = "日志".repeat(80) + ".tar.gz"
        val safe = LogArchiveDetector.safeFileName(longName)
        assertTrue(safe.endsWith(".tar.gz"))
        val stem = safe.removeSuffix(".tar.gz")
        assertTrue(stem.toByteArray(Charsets.UTF_8).size <= 120)
        // 不产生替换字符，说明没有从多字节字符中间截断。
        assertFalse(stem.contains('\uFFFD'))
    }
}
