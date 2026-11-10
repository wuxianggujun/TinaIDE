package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageMetadataFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactByteLimitKeepsChineseAndSupplementaryCharacters() {
        val text = "中文😀"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = temporary.newFile().apply { writeBytes(bytes) }
        assertThat(PackageMetadataFiles.decodeUtf8(PackageMetadataFiles.readBytes(file, bytes.size)))
            .isEqualTo(text)
        assertThrows(IOException::class.java) { PackageMetadataFiles.readBytes(file, bytes.size - 1) }
    }

    @Test fun actualReadCannotExceedLimitEvenWhenLengthIsStale() {
        val file = temporary.newFile().apply { writeBytes(ByteArray(32 * 1024)) }
        val staleLength = object : File(file.path) { override fun length(): Long = 1 }
        assertThrows(IOException::class.java) { PackageMetadataFiles.readBytes(staleLength, 16 * 1024) }
    }

    @Test fun cancellationInterruptsAnOngoingReadAndClosesTheFile() {
        val file = temporary.newFile().apply { writeBytes(ByteArray(128 * 1024)) }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PackageMetadataFiles.readBytes(file, 128 * 1024) {
                if (++checks == 3) throw CancellationException("Cancelled during read")
            }
        }
        assertThat(checks).isEqualTo(3)
        assertThat(file.delete()).isTrue()
    }

    @Test fun alreadyCancelledReadDoesNotOpenAMissingFile() {
        assertThrows(CancellationException::class.java) {
            PackageMetadataFiles.readBytes(File(temporary.root, "missing"), 1024) {
                throw CancellationException("Already cancelled")
            }
        }
    }

    @Test fun malformedUtf8IsRejectedWithoutReplacementCharacters() {
        assertThrows(CharacterCodingException::class.java) {
            PackageMetadataFiles.decodeUtf8(byteArrayOf(0x80.toByte()))
        }
    }
}
