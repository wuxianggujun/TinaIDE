package com.wuxianggujun.tinaide.core.compile.elf

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Test

class ElfDynamicSymbolReaderTest {
    @Test
    fun `readDefinedSymbols returns exported entry points and skips undefined ones`() {
        withTempFile { file ->
            file.writeBytes(
                createElf64(
                    symbols = listOf(
                        "SDL_main" to 1,
                        "SDL_AppInit" to 1,
                        "SDL_malloc" to 0,
                        "main" to 0,
                    ),
                )
            )

            assertThat(ElfDynamicSymbolReader.readDefinedSymbols(file))
                .containsExactly("SDL_main", "SDL_AppInit")
        }
    }

    @Test
    fun `readDefinedSymbols returns empty set when every symbol is undefined`() {
        withTempFile { file ->
            file.writeBytes(createElf64(symbols = listOf("main" to 0, "printf" to 0)))

            assertThat(ElfDynamicSymbolReader.readDefinedSymbols(file)).isEmpty()
        }
    }

    @Test
    fun `readDefinedSymbols returns null for non ELF input`() {
        withTempFile { file ->
            file.writeText("not an elf file")

            assertThat(ElfDynamicSymbolReader.readDefinedSymbols(file)).isNull()
        }
    }

    @Test
    fun `readDefinedSymbols returns null when section headers are stripped`() {
        withTempFile { file ->
            val bytes = createElf64(symbols = listOf("SDL_main" to 1))
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(40, 0L)
            file.writeBytes(bytes)

            assertThat(ElfDynamicSymbolReader.readDefinedSymbols(file)).isNull()
        }
    }

    private fun createElf64(symbols: List<Pair<String, Int>>): ByteArray {
        val sectionHeaderOffset = 0x400
        val sectionHeaderSize = 64
        val dynsymOffset = 0x100
        val dynsymEntrySize = 24
        val dynstrOffset = 0x300
        val fileSize = sectionHeaderOffset + sectionHeaderSize * 3

        val stringTable = StringBuilder("\u0000")
        val nameOffsets = symbols.map { (name, _) ->
            val offset = stringTable.length
            stringTable.append(name).append('\u0000')
            offset
        }
        val stringBytes = stringTable.toString().toByteArray(Charsets.UTF_8)

        val bytes = ByteArray(fileSize)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0, 0x7F.toByte())
        buffer.put(1, 'E'.code.toByte())
        buffer.put(2, 'L'.code.toByte())
        buffer.put(3, 'F'.code.toByte())
        buffer.put(4, 2.toByte())
        buffer.put(5, 1.toByte())
        buffer.put(6, 1.toByte())
        buffer.putLong(40, sectionHeaderOffset.toLong())
        buffer.putShort(58, sectionHeaderSize.toShort())
        buffer.putShort(60, 3.toShort())

        // 索引 0 是保留的空 section。
        writeSectionHeader(
            buffer = buffer,
            offset = sectionHeaderOffset,
            type = 0,
            fileOffset = 0,
            size = 0,
            link = 0,
            entrySize = 0,
        )
        writeSectionHeader(
            buffer = buffer,
            offset = sectionHeaderOffset + sectionHeaderSize,
            type = 11,
            fileOffset = dynsymOffset.toLong(),
            size = (symbols.size + 1).toLong() * dynsymEntrySize,
            link = 2,
            entrySize = dynsymEntrySize.toLong(),
        )
        writeSectionHeader(
            buffer = buffer,
            offset = sectionHeaderOffset + sectionHeaderSize * 2,
            type = 3,
            fileOffset = dynstrOffset.toLong(),
            size = stringBytes.size.toLong(),
            link = 0,
            entrySize = 0,
        )

        stringBytes.copyInto(bytes, destinationOffset = dynstrOffset)

        // 第一个符号固定为空符号，随后按 (name, sectionIndex) 写入。
        symbols.forEachIndexed { index, (_, sectionIndex) ->
            val offset = dynsymOffset + (index + 1) * dynsymEntrySize
            buffer.putInt(offset, nameOffsets[index])
            buffer.putShort(offset + 6, sectionIndex.toShort())
        }

        return bytes
    }

    private fun writeSectionHeader(
        buffer: ByteBuffer,
        offset: Int,
        type: Int,
        fileOffset: Long,
        size: Long,
        link: Int,
        entrySize: Long,
    ) {
        buffer.putInt(offset + 4, type)
        buffer.putLong(offset + 24, fileOffset)
        buffer.putLong(offset + 32, size)
        buffer.putInt(offset + 40, link)
        buffer.putLong(offset + 56, entrySize)
    }

    private fun withTempFile(block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory("elf-dynamic-symbol-test").toFile()
        try {
            block(File(tempDir, "libmain.so"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
