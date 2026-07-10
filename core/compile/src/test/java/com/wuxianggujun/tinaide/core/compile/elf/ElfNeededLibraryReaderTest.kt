package com.wuxianggujun.tinaide.core.compile.elf

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Test

class ElfNeededLibraryReaderTest {
    @Test
    fun `read returns only ELF64 DT_NEEDED entries`() {
        withTempFile { file ->
            file.writeBytes(createElf(is64Bit = true))

            assertThat(ElfNeededLibraryReader.read(file))
                .containsExactly("libSDL3.so", "libSDL3_image.so")
                .inOrder()
        }
    }

    @Test
    fun `read returns only ELF32 DT_NEEDED entries`() {
        withTempFile { file ->
            file.writeBytes(createElf(is64Bit = false))

            assertThat(ElfNeededLibraryReader.read(file))
                .containsExactly("libSDL3.so", "libSDL3_image.so")
                .inOrder()
        }
    }

    @Test
    fun `read returns null for non ELF input`() {
        withTempFile { file ->
            file.writeText("embedded libfalse.so text")

            assertThat(ElfNeededLibraryReader.read(file)).isNull()
        }
    }

    private fun createElf(is64Bit: Boolean): ByteArray {
        val fileSize = 0x280
        val programHeaderOffset = if (is64Bit) 64 else 52
        val programHeaderSize = if (is64Bit) 56 else 32
        val dynamicOffset = 0x100
        val stringTableOffset = 0x1C0
        val baseVirtualAddress = 0x1000L
        val stringTableAddress = baseVirtualAddress + stringTableOffset
        val strings = "\u0000libSDL3.so\u0000libSDL3_image.so\u0000libfalse.so\u0000"
            .toByteArray(Charsets.UTF_8)
        val secondNeededOffset = 1L + "libSDL3.so".length + 1L
        val bytes = ByteArray(fileSize)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0, 0x7F.toByte())
        buffer.put(1, 'E'.code.toByte())
        buffer.put(2, 'L'.code.toByte())
        buffer.put(3, 'F'.code.toByte())
        buffer.put(4, (if (is64Bit) 2 else 1).toByte())
        buffer.put(5, 1.toByte())
        buffer.put(6, 1.toByte())

        if (is64Bit) {
            buffer.putLong(32, programHeaderOffset.toLong())
            buffer.putShort(52, 64.toShort())
            buffer.putShort(54, programHeaderSize.toShort())
            buffer.putShort(56, 2.toShort())
        } else {
            buffer.putInt(28, programHeaderOffset)
            buffer.putShort(40, 52.toShort())
            buffer.putShort(42, programHeaderSize.toShort())
            buffer.putShort(44, 2.toShort())
        }

        putProgramHeader(
            buffer = buffer,
            offset = programHeaderOffset,
            is64Bit = is64Bit,
            type = 1,
            fileOffset = 0,
            virtualAddress = baseVirtualAddress,
            fileSize = fileSize.toLong(),
        )
        val dynamicEntrySize = if (is64Bit) 16 else 8
        val dynamicSize = dynamicEntrySize * 5L
        putProgramHeader(
            buffer = buffer,
            offset = programHeaderOffset + programHeaderSize,
            is64Bit = is64Bit,
            type = 2,
            fileOffset = dynamicOffset.toLong(),
            virtualAddress = baseVirtualAddress + dynamicOffset,
            fileSize = dynamicSize,
        )

        putDynamicEntry(buffer, dynamicOffset, is64Bit, tag = 5, value = stringTableAddress)
        putDynamicEntry(buffer, dynamicOffset + dynamicEntrySize, is64Bit, tag = 10, value = strings.size.toLong())
        putDynamicEntry(buffer, dynamicOffset + dynamicEntrySize * 2, is64Bit, tag = 1, value = 1)
        putDynamicEntry(
            buffer,
            dynamicOffset + dynamicEntrySize * 3,
            is64Bit,
            tag = 1,
            value = secondNeededOffset,
        )
        putDynamicEntry(buffer, dynamicOffset + dynamicEntrySize * 4, is64Bit, tag = 0, value = 0)
        strings.copyInto(bytes, destinationOffset = stringTableOffset)
        return bytes
    }

    private fun putProgramHeader(
        buffer: ByteBuffer,
        offset: Int,
        is64Bit: Boolean,
        type: Int,
        fileOffset: Long,
        virtualAddress: Long,
        fileSize: Long,
    ) {
        buffer.putInt(offset, type)
        if (is64Bit) {
            buffer.putLong(offset + 8, fileOffset)
            buffer.putLong(offset + 16, virtualAddress)
            buffer.putLong(offset + 32, fileSize)
            buffer.putLong(offset + 40, fileSize)
        } else {
            buffer.putInt(offset + 4, fileOffset.toInt())
            buffer.putInt(offset + 8, virtualAddress.toInt())
            buffer.putInt(offset + 16, fileSize.toInt())
            buffer.putInt(offset + 20, fileSize.toInt())
        }
    }

    private fun putDynamicEntry(
        buffer: ByteBuffer,
        offset: Int,
        is64Bit: Boolean,
        tag: Long,
        value: Long,
    ) {
        if (is64Bit) {
            buffer.putLong(offset, tag)
            buffer.putLong(offset + 8, value)
        } else {
            buffer.putInt(offset, tag.toInt())
            buffer.putInt(offset + 4, value.toInt())
        }
    }

    private fun withTempFile(block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory("elf-needed-reader-test").toFile()
        try {
            block(File(tempDir, "libmain.so"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
