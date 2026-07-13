package com.wuxianggujun.tinaide.core.compile.elf

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ElfNeededLibraryReader {
    private const val ELF_HEADER_32_SIZE = 52L
    private const val ELF_HEADER_64_SIZE = 64L
    private const val PROGRAM_HEADER_32_SIZE = 32
    private const val PROGRAM_HEADER_64_SIZE = 56
    private const val DYNAMIC_ENTRY_32_SIZE = 8L
    private const val DYNAMIC_ENTRY_64_SIZE = 16L
    private const val MAX_DYNAMIC_STRING_LENGTH = 4096

    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L

    @Throws(IOException::class)
    fun read(file: File): Set<String>? = RandomAccessFile(file, "r").use { input ->
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

        val elfClass = when (identification[4].toInt() and 0xFF) {
            1 -> ElfClass.ELF32
            2 -> ElfClass.ELF64
            else -> throw IOException("Unsupported ELF class in ${file.name}")
        }
        val byteOrder = when (identification[5].toInt() and 0xFF) {
            1 -> ByteOrder.LITTLE_ENDIAN
            2 -> ByteOrder.BIG_ENDIAN
            else -> throw IOException("Unsupported ELF byte order in ${file.name}")
        }
        val minimumHeaderSize = if (elfClass == ElfClass.ELF32) ELF_HEADER_32_SIZE else ELF_HEADER_64_SIZE
        if (input.length() < minimumHeaderSize) {
            throw IOException("Truncated ELF header in ${file.name}")
        }

        val reader = EndianReader(input, byteOrder)
        val programHeaderOffset = when (elfClass) {
            ElfClass.ELF32 -> reader.readUnsignedInt(28L)
            ElfClass.ELF64 -> reader.readLong(32L)
        }
        val programHeaderEntrySize = reader.readUnsignedShort(
            if (elfClass == ElfClass.ELF32) 42L else 54L
        )
        val programHeaderCount = reader.readUnsignedShort(
            if (elfClass == ElfClass.ELF32) 44L else 56L
        )
        val minimumProgramHeaderSize = if (elfClass == ElfClass.ELF32) {
            PROGRAM_HEADER_32_SIZE
        } else {
            PROGRAM_HEADER_64_SIZE
        }
        if (programHeaderCount == 0) return emptySet()
        if (programHeaderEntrySize < minimumProgramHeaderSize) {
            throw IOException("Invalid ELF program header size in ${file.name}")
        }

        val loadSegments = mutableListOf<ProgramSegment>()
        var dynamicSegment: ProgramSegment? = null
        repeat(programHeaderCount) { index ->
            val entryOffset = checkedOffset(
                base = programHeaderOffset,
                index = index,
                entrySize = programHeaderEntrySize,
                fileLength = input.length(),
                requiredSize = minimumProgramHeaderSize.toLong(),
                fileName = file.name,
            )
            val type = reader.readUnsignedInt(entryOffset)
            val segment = when (elfClass) {
                ElfClass.ELF32 -> ProgramSegment(
                    fileOffset = reader.readUnsignedInt(entryOffset + 4L),
                    virtualAddress = reader.readUnsignedInt(entryOffset + 8L),
                    fileSize = reader.readUnsignedInt(entryOffset + 16L),
                )
                ElfClass.ELF64 -> ProgramSegment(
                    fileOffset = reader.readLong(entryOffset + 8L),
                    virtualAddress = reader.readLong(entryOffset + 16L),
                    fileSize = reader.readLong(entryOffset + 32L),
                )
            }
            validateRange(segment.fileOffset, segment.fileSize, input.length(), file.name)
            when (type) {
                PT_LOAD -> loadSegments += segment
                PT_DYNAMIC -> if (dynamicSegment == null) dynamicSegment = segment
            }
        }

        val dynamic = dynamicSegment ?: return emptySet()
        val dynamicEntrySize = if (elfClass == ElfClass.ELF32) {
            DYNAMIC_ENTRY_32_SIZE
        } else {
            DYNAMIC_ENTRY_64_SIZE
        }
        val dynamicEntryCount = dynamic.fileSize / dynamicEntrySize
        val neededOffsets = mutableListOf<Long>()
        var stringTableAddress: Long? = null
        var stringTableSize: Long? = null

        for (index in 0 until dynamicEntryCount) {
            val entryOffset = dynamic.fileOffset + index * dynamicEntrySize
            val tag = when (elfClass) {
                ElfClass.ELF32 -> reader.readUnsignedInt(entryOffset)
                ElfClass.ELF64 -> reader.readLong(entryOffset)
            }
            val value = when (elfClass) {
                ElfClass.ELF32 -> reader.readUnsignedInt(entryOffset + 4L)
                ElfClass.ELF64 -> reader.readLong(entryOffset + 8L)
            }
            when (tag) {
                DT_NULL -> break
                DT_NEEDED -> neededOffsets += value
                DT_STRTAB -> stringTableAddress = value
                DT_STRSZ -> stringTableSize = value
            }
        }

        if (neededOffsets.isEmpty()) return emptySet()
        val stringTableVirtualAddress = stringTableAddress
            ?: throw IOException("ELF dynamic string table is missing in ${file.name}")
        val stringTableFileOffset = loadSegments.firstNotNullOfOrNull { segment ->
            segment.mapVirtualAddress(stringTableVirtualAddress)
        } ?: throw IOException("ELF dynamic string table is outside loadable segments in ${file.name}")
        val stringTableEnd = stringTableSize
            ?.let { size -> (stringTableFileOffset + size).coerceAtMost(input.length()) }
            ?: input.length()

        neededOffsets.mapTo(linkedSetOf()) { neededOffset ->
            val stringOffset = stringTableFileOffset + neededOffset
            if (stringOffset !in stringTableFileOffset until stringTableEnd) {
                throw IOException("Invalid DT_NEEDED string offset in ${file.name}")
            }
            readNullTerminatedString(input, stringOffset, stringTableEnd, file.name)
        }
    }

    private fun checkedOffset(
        base: Long,
        index: Int,
        entrySize: Int,
        fileLength: Long,
        requiredSize: Long,
        fileName: String,
    ): Long {
        if (base < 0L || entrySize <= 0) throw IOException("Invalid ELF program headers in $fileName")
        val indexOffset = index.toLong() * entrySize.toLong()
        val entryOffset = base + indexOffset
        validateRange(entryOffset, requiredSize, fileLength, fileName)
        return entryOffset
    }

    private fun validateRange(offset: Long, size: Long, fileLength: Long, fileName: String) {
        if (offset < 0L || size < 0L || offset > fileLength || size > fileLength - offset) {
            throw IOException("ELF range is outside $fileName")
        }
    }

    private fun readNullTerminatedString(
        input: RandomAccessFile,
        offset: Long,
        endOffset: Long,
        fileName: String,
    ): String {
        input.seek(offset)
        val output = ByteArrayOutputStream()
        while (input.filePointer < endOffset && output.size() < MAX_DYNAMIC_STRING_LENGTH) {
            val value = input.read()
            if (value <= 0) break
            output.write(value)
        }
        if (output.size() == MAX_DYNAMIC_STRING_LENGTH) {
            throw IOException("DT_NEEDED string is too long in $fileName")
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private enum class ElfClass {
        ELF32,
        ELF64,
    }

    private data class ProgramSegment(
        val fileOffset: Long,
        val virtualAddress: Long,
        val fileSize: Long,
    ) {
        fun mapVirtualAddress(address: Long): Long? {
            if (address < virtualAddress) return null
            val relativeOffset = address - virtualAddress
            if (relativeOffset >= fileSize) return null
            return fileOffset + relativeOffset
        }
    }

    private class EndianReader(
        private val input: RandomAccessFile,
        private val byteOrder: ByteOrder,
    ) {
        fun readUnsignedShort(offset: Long): Int = readBuffer(offset, 2).short.toInt() and 0xFFFF

        fun readUnsignedInt(offset: Long): Long = readBuffer(offset, 4).int.toLong() and 0xFFFF_FFFFL

        fun readLong(offset: Long): Long = readBuffer(offset, 8).long.also { value ->
            if (value < 0L) throw IOException("ELF offset exceeds supported range")
        }

        private fun readBuffer(offset: Long, size: Int): ByteBuffer {
            val bytes = ByteArray(size)
            input.seek(offset)
            input.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(byteOrder)
        }
    }
}
