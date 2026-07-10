package com.wuxianggujun.tinaide.core.apkbuilder

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class NativeLibraryAbiDetectorTest {

    @Test
    fun `detect reads Android ABI from ELF machine header`() {
        val tempDir = Files.createTempDirectory("native-library-abi-test").toFile()
        try {
            val arm64 = writeElfHeader(File(tempDir, "libarm64.so"), elfClass = 2, machine = 183)
            val x86_64 = writeElfHeader(File(tempDir, "libx86_64.so"), elfClass = 2, machine = 62)
            val arm32 = writeElfHeader(File(tempDir, "libarm32.so"), elfClass = 1, machine = 40)
            val x86 = writeElfHeader(File(tempDir, "libx86.so"), elfClass = 1, machine = 3)

            assertThat(NativeLibraryAbiDetector.detect(arm64)).isEqualTo("arm64-v8a")
            assertThat(NativeLibraryAbiDetector.detect(x86_64)).isEqualTo("x86_64")
            assertThat(NativeLibraryAbiDetector.detect(arm32)).isEqualTo("armeabi-v7a")
            assertThat(NativeLibraryAbiDetector.detect(x86)).isEqualTo("x86")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detect rejects non ELF input`() {
        val library = Files.createTempFile("native-library-invalid", ".so").toFile()
        try {
            library.writeText("not an ELF library")

            assertThat(NativeLibraryAbiDetector.detect(library)).isNull()
        } finally {
            library.delete()
        }
    }

    private fun writeElfHeader(file: File, elfClass: Int, machine: Int): File {
        val header = ByteArray(20)
        header[0] = 0x7F.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = elfClass.toByte()
        header[5] = 1.toByte()
        header[18] = (machine and 0xFF).toByte()
        header[19] = ((machine ushr 8) and 0xFF).toByte()
        file.writeBytes(header)
        return file
    }
}
