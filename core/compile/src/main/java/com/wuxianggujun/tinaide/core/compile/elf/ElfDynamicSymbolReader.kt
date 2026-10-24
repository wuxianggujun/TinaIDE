package com.wuxianggujun.tinaide.core.compile.elf

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the defined symbols from an ELF shared library's `.dynsym` section.
 *
 * 只依赖 section header，不解析 hash 表：`.dynsym` 是动态链接必需数据，
 * 常规 strip 不会删除它。无法判断时返回 null，调用方应把它当作“未知”而不是“缺失”。
 */
object ElfDynamicSymbolReader {
    private const val ELF_HEADER_32_SIZE = 52L
    private const val ELF_HEADER_64_SIZE = 64L
    private const val SECTION_HEADER_32_SIZE = 40
    private const val SECTION_HEADER_64_SIZE = 64
    private const val SYMBOL_32_SIZE = 16
    private const val SYMBOL_64_SIZE = 24
    private const val MAX_SYMBOL_COUNT = 1_000_000
    private const val MAX_SYMBOL_NAME_LENGTH = 4096

    private const val SHT_DYNSYM = 11L
    private const val SHN_UNDEF = 0

    /**
     * @return 已定义符号名集合；输入不是 ELF、缺少 section header 或没有 `.dynsym` 时返回 null。
     */
    @Throws(IOException::class)
    fun readDefinedSymbols(file: File): Set<String>? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 16L) return null

        val identification = ByteArray(16)
        input.readFully(identification)
        if (
            identification[0] != 0x7F.toByte() ||
            identification[1] != 'E'.code.toByte() ||
            identification[2] != 'L'.code.toByte() ||
            identification[3] != 'F'.code.toByte()
        ) {
            return null
        }

        val is64Bit = when (identification[4].toInt() and 0xFF) {
            1 -> false
            2 -> true
            else -> return null
        }
        val byteOrder = when (identification[5].toInt() and 0xFF) {
            1 -> ByteOrder.LITTLE_ENDIAN
            2 -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val headerSize = if (is64Bit) ELF_HEADER_64_SIZE else ELF_HEADER_32_SIZE
        if (input.length() < headerSize) return null

        val reader = SectionReader(input, byteOrder)
        val sectionHeaderOffset = if (is64Bit) {
            reader.readLong(40L)
        } else {
            reader.readUnsignedInt(32L)
        }
        val sectionHeaderEntrySize = reader.readUnsignedShort(if (is64Bit) 58L else 46L)
        val sectionHeaderCount = reader.readUnsignedShort(if (is64Bit) 60L else 48L)
        val minimumEntrySize = if (is64Bit) SECTION_HEADER_64_SIZE else SECTION_HEADER_32_SIZE
        if (sectionHeaderOffset == 0L || sectionHeaderCount == 0) return null
        if (sectionHeaderEntrySize < minimumEntrySize) return null

        val sections = mutableListOf<SectionHeader>()
        repeat(sectionHeaderCount) { index ->
            val offset = sectionHeaderOffset + index.toLong() * sectionHeaderEntrySize
            if (offset < 0L || offset + minimumEntrySize > input.length()) return null
            sections += if (is64Bit) {
                SectionHeader(
                    type = reader.readUnsignedInt(offset + 4L),
                    offset = reader.readLong(offset + 24L),
                    size = reader.readLong(offset + 32L),
                    link = reader.readUnsignedInt(offset + 40L).toInt(),
                    entrySize = reader.readLong(offset + 56L),
                )
            } else {
                SectionHeader(
                    type = reader.readUnsignedInt(offset + 4L),
                    offset = reader.readUnsignedInt(offset + 16L),
                    size = reader.readUnsignedInt(offset + 20L),
                    link = reader.readUnsignedInt(offset + 24L).toInt(),
                    entrySize = reader.readUnsignedInt(offset + 36L),
                )
            }
        }

        val symbolSection = sections.firstOrNull { it.type == SHT_DYNSYM } ?: return null
        val stringSection = sections.getOrNull(symbolSection.link) ?: return null
        val symbolEntrySize = symbolSection.entrySize.toInt().takeIf { it > 0 }
            ?: if (is64Bit) SYMBOL_64_SIZE else SYMBOL_32_SIZE
        val minimumSymbolSize = if (is64Bit) SYMBOL_64_SIZE else SYMBOL_32_SIZE
        if (symbolEntrySize < minimumSymbolSize) return null
        if (!isRangeInsideFile(symbolSection.offset, symbolSection.size, input.length())) return null
        if (!isRangeInsideFile(stringSection.offset, stringSection.size, input.length())) return null

        val symbolCount = (symbolSection.size / symbolEntrySize).toInt()
        if (symbolCount <= 0 || symbolCount > MAX_SYMBOL_COUNT) return null

        val stringTableEnd = stringSection.offset + stringSection.size
        val symbols = linkedSetOf<String>()
        for (index in 0 until symbolCount) {
            val offset = symbolSection.offset + index.toLong() * symbolEntrySize
            val nameOffset = reader.readUnsignedInt(offset)
            val sectionIndex = reader.readUnsignedShort(offset + if (is64Bit) 6L else 14L)
            if (sectionIndex == SHN_UNDEF || nameOffset == 0L) continue

            val nameStart = stringSection.offset + nameOffset
            if (nameStart !in stringSection.offset until stringTableEnd) continue
            val name = readNullTerminatedString(input, nameStart, stringTableEnd)
            if (name.isNotEmpty()) symbols += name
        }
        symbols
    }

    private fun isRangeInsideFile(offset: Long, size: Long, fileLength: Long): Boolean =
        offset >= 0L && size >= 0L && offset <= fileLength && size <= fileLength - offset

    private fun readNullTerminatedString(
        input: RandomAccessFile,
        offset: Long,
        endOffset: Long,
    ): String {
        input.seek(offset)
        val output = ByteArrayOutputStream()
        while (input.filePointer < endOffset && output.size() < MAX_SYMBOL_NAME_LENGTH) {
            val value = input.read()
            if (value <= 0) break
            output.write(value)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class SectionHeader(
        val type: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entrySize: Long,
    )

    private class SectionReader(
        private val input: RandomAccessFile,
        private val byteOrder: ByteOrder,
    ) {
        fun readUnsignedShort(offset: Long): Int = readBuffer(offset, 2).short.toInt() and 0xFFFF

        fun readUnsignedInt(offset: Long): Long = readBuffer(offset, 4).int.toLong() and 0xFFFF_FFFFL

        fun readLong(offset: Long): Long = readBuffer(offset, 8).long

        private fun readBuffer(offset: Long, size: Int): ByteBuffer {
            val bytes = ByteArray(size)
            input.seek(offset)
            input.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(byteOrder)
        }
    }
}
