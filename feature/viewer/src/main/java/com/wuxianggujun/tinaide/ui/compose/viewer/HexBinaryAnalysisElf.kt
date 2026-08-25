package com.wuxianggujun.tinaide.ui.compose.viewer

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ELF summary parsing, linkage, hardening, and risk builders.
 */

internal fun parseElfSummary(randomAccessFile: RandomAccessFile, header: ByteArray): HexElfSummary? {
    if (header.size < ELF_IDENT_SIZE || !header.startsWith(0x7F, 'E'.code, 'L'.code, 'F'.code)) return null

    val is64Bit = when (header[ELF_CLASS_OFFSET].toInt() and 0xFF) {
        ELF_CLASS_32 -> false
        ELF_CLASS_64 -> true
        else -> return null
    }
    val endian = when (header[ELF_DATA_OFFSET].toInt() and 0xFF) {
        ELF_DATA_LITTLE -> HexEndian.LITTLE
        ELF_DATA_BIG -> HexEndian.BIG
        else -> return null
    }

    val requiredHeaderSize = if (is64Bit) ELF64_HEADER_SIZE else ELF32_HEADER_SIZE
    val fullHeader = if (header.size >= requiredHeaderSize) {
        header
    } else {
        randomAccessFile.readAt(0L, requiredHeaderSize)
    }
    if (fullHeader.size < requiredHeaderSize) return null

    val type = fullHeader.u16(16, endian)
    val machine = fullHeader.u16(18, endian)
    val entryPoint = if (is64Bit) fullHeader.u64(24, endian) else fullHeader.u32(24, endian)
    val programHeaderOffset = if (is64Bit) fullHeader.u64(32, endian) else fullHeader.u32(28, endian)
    val sectionHeaderOffset = if (is64Bit) fullHeader.u64(40, endian) else fullHeader.u32(32, endian)
    val programHeaderEntrySize = fullHeader.u16(if (is64Bit) 54 else 42, endian)
    val programHeaderCount = fullHeader.u16(if (is64Bit) 56 else 44, endian)
    val sectionHeaderEntrySize = fullHeader.u16(if (is64Bit) 58 else 46, endian)
    val sectionHeaderCount = fullHeader.u16(if (is64Bit) 60 else 48, endian)
    val sectionNameTableIndex = fullHeader.u16(if (is64Bit) 62 else 50, endian)

    val programHeaders = readElfProgramHeaders(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        programHeaderOffset = programHeaderOffset,
        programHeaderEntrySize = programHeaderEntrySize,
        programHeaderCount = programHeaderCount
    )
    val loadSegments = programHeaders.mapNotNull { programHeader -> programHeader.toLoadSegment() }
    val sections = readElfSectionHeaders(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sectionHeaderOffset = sectionHeaderOffset,
        sectionHeaderEntrySize = sectionHeaderEntrySize,
        sectionHeaderCount = sectionHeaderCount,
        sectionNameTableIndex = sectionNameTableIndex
    )
    val sectionSegmentMappings = buildElfSectionSegmentMappings(
        sections = sections,
        programHeaders = programHeaders
    )
    val sectionEntropyEntries = readElfSectionEntropyEntries(
        randomAccessFile = randomAccessFile,
        sections = sections
    )
    val sectionNames = sections.mapNotNull { section -> section.name.takeIf { it.isNotBlank() } }
    val noteEntries = readElfNoteEntries(
        randomAccessFile = randomAccessFile,
        endian = endian,
        machine = machine,
        sections = sections
    )
    val dynamicSymbols = readElfDynamicSymbols(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections,
        loadSegments = loadSegments
    )
    val nativeApiHints = buildElfNativeApiHints(dynamicSymbols)
    val dynamicStringEntries = readElfDynamicStringEntries(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections
    )
    val dynamicFlagEntries = readElfDynamicFlagEntries(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections
    )
    val initArrayEntries = readElfInitArrayEntries(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections,
        loadSegments = loadSegments
    )
    val relocations = readElfRelocations(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        machine = machine,
        sections = sections,
        loadSegments = loadSegments
    )
    val linkageEntries = buildElfLinkageEntries(
        randomAccessFile = randomAccessFile,
        machine = machine,
        endian = endian,
        sections = sections,
        relocations = relocations,
        bindNow = dynamicFlagEntries.any { entry -> entry.isBindNow }
    )
    val hardeningChecks = buildElfHardeningChecks(
        elfType = type,
        programHeaders = programHeaders,
        dynamicFlagEntries = dynamicFlagEntries,
        noteEntries = noteEntries
    )
    val riskFindings = buildElfRiskFindings(
        programHeaders = programHeaders,
        sections = sections,
        hardeningChecks = hardeningChecks,
        dynamicStringEntries = dynamicStringEntries
    )
    val dynamicLinkerSteps = buildElfDynamicLinkerSteps(
        programHeaders = programHeaders,
        dynamicStringEntries = dynamicStringEntries,
        hardeningChecks = hardeningChecks,
        initArrayEntries = initArrayEntries,
        linkageEntries = linkageEntries,
        dynamicSymbols = dynamicSymbols
    )

    return HexElfSummary(
        is64Bit = is64Bit,
        endian = endian,
        type = type,
        machine = machine,
        machineName = elfMachineName(machine),
        entryPoint = entryPoint,
        programHeaderCount = if (programHeaderEntrySize > 0) programHeaderCount else 0,
        sectionHeaderCount = if (sectionHeaderEntrySize > 0) sectionHeaderCount else 0,
        sectionNames = sectionNames,
        sections = sections,
        noteEntries = noteEntries,
        programHeaders = programHeaders,
        loadSegments = loadSegments,
        sectionSegmentMappings = sectionSegmentMappings,
        sectionEntropyEntries = sectionEntropyEntries,
        hardeningChecks = hardeningChecks,
        riskFindings = riskFindings,
        dynamicSymbols = dynamicSymbols,
        dynamicStringEntries = dynamicStringEntries,
        dynamicFlagEntries = dynamicFlagEntries,
        initArrayEntries = initArrayEntries,
        relocations = relocations,
        linkageEntries = linkageEntries,
        dynamicLinkerSteps = dynamicLinkerSteps,
        nativeApiHints = nativeApiHints
    )
}

internal fun readElfProgramHeaders(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    programHeaderOffset: Long,
    programHeaderEntrySize: Int,
    programHeaderCount: Int
): List<HexElfProgramHeader> {
    if (programHeaderOffset <= 0L || programHeaderEntrySize <= 0 || programHeaderCount <= 0) return emptyList()
    if (programHeaderOffset >= randomAccessFile.length()) return emptyList()

    val safeProgramHeaderCount = programHeaderCount.coerceAtMost(MAX_ELF_PROGRAM_HEADERS)
    val programHeaders = mutableListOf<HexElfProgramHeader>()
    for (programHeaderIndex in 0 until safeProgramHeaderCount) {
        val programHeaderFileOffset = programHeaderOffset + programHeaderIndex.toLong() * programHeaderEntrySize
        val programHeader = randomAccessFile.readAt(
            offset = programHeaderFileOffset,
            byteCount = programHeaderEntrySize
        )
        if (programHeader.size < programHeaderEntrySize) break

        val type = programHeader.u32(0, endian)
        val flags = if (is64Bit) {
            programHeader.u32(4, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            programHeader.u32(24, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        programHeaders += HexElfProgramHeader(
            index = programHeaderIndex,
            type = type,
            typeName = elfProgramHeaderTypeName(type),
            programHeaderFileOffset = programHeaderFileOffset,
            fileOffset = if (is64Bit) programHeader.u64(8, endian) else programHeader.u32(4, endian),
            virtualAddress = if (is64Bit) programHeader.u64(16, endian) else programHeader.u32(8, endian),
            physicalAddress = if (is64Bit) programHeader.u64(24, endian) else programHeader.u32(12, endian),
            fileSize = if (is64Bit) programHeader.u64(32, endian) else programHeader.u32(16, endian),
            memorySize = if (is64Bit) programHeader.u64(40, endian) else programHeader.u32(20, endian),
            flags = flags,
            align = if (is64Bit) programHeader.u64(48, endian) else programHeader.u32(28, endian)
        )
    }
    return programHeaders
}

internal fun readElfSectionHeaders(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sectionHeaderOffset: Long,
    sectionHeaderEntrySize: Int,
    sectionHeaderCount: Int,
    sectionNameTableIndex: Int
): List<HexElfSection> {
    if (sectionHeaderOffset <= 0L || sectionHeaderEntrySize <= 0 || sectionHeaderCount <= 0) return emptyList()
    if (sectionNameTableIndex !in 0 until sectionHeaderCount) return emptyList()
    if (sectionHeaderOffset >= randomAccessFile.length()) return emptyList()

    val safeSectionCount = sectionHeaderCount.coerceAtMost(MAX_ELF_SECTION_HEADERS)
    val nameTableHeaderOffset = sectionHeaderOffset + sectionNameTableIndex.toLong() * sectionHeaderEntrySize
    val nameTableHeader = randomAccessFile.readAt(nameTableHeaderOffset, sectionHeaderEntrySize)
    if (nameTableHeader.size < sectionHeaderEntrySize) return emptyList()

    val nameTableOffset = if (is64Bit) nameTableHeader.u64(24, endian) else nameTableHeader.u32(16, endian)
    val nameTableSize = if (is64Bit) nameTableHeader.u64(32, endian) else nameTableHeader.u32(20, endian)
    if (nameTableOffset <= 0L || nameTableSize <= 0L || nameTableOffset >= randomAccessFile.length()) return emptyList()

    val safeNameTableSize = minOf(nameTableSize, randomAccessFile.length() - nameTableOffset, MAX_ELF_STRING_TABLE_BYTES.toLong()).toInt()
    val nameTable = randomAccessFile.readAt(nameTableOffset, safeNameTableSize)
    if (nameTable.isEmpty()) return emptyList()

    val sections = mutableListOf<HexElfSection>()
    for (sectionIndex in 0 until safeSectionCount) {
        val sectionHeader = randomAccessFile.readAt(
            offset = sectionHeaderOffset + sectionIndex.toLong() * sectionHeaderEntrySize,
            byteCount = sectionHeaderEntrySize
        )
        if (sectionHeader.size < sectionHeaderEntrySize) break

        val nameOffset = sectionHeader.u32(0, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        sections += HexElfSection(
            index = sectionIndex,
            name = nameTable.readNullTerminatedAscii(nameOffset),
            type = sectionHeader.u32(4, endian),
            flags = if (is64Bit) sectionHeader.u64(8, endian) else sectionHeader.u32(8, endian),
            virtualAddress = if (is64Bit) sectionHeader.u64(16, endian) else sectionHeader.u32(12, endian),
            fileOffset = if (is64Bit) sectionHeader.u64(24, endian) else sectionHeader.u32(16, endian),
            size = if (is64Bit) sectionHeader.u64(32, endian) else sectionHeader.u32(20, endian),
            link = sectionHeader.u32(if (is64Bit) 40 else 24, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            entrySize = if (is64Bit) sectionHeader.u64(56, endian) else sectionHeader.u32(36, endian)
        )
    }
    return sections
}

internal fun buildElfSectionSegmentMappings(
    sections: List<HexElfSection>,
    programHeaders: List<HexElfProgramHeader>
): List<HexElfSectionSegmentMapping> {
    if (sections.isEmpty() || programHeaders.isEmpty()) return emptyList()
    val loadProgramHeaders = programHeaders.filter { programHeader ->
        programHeader.isLoad && programHeader.fileSize > 0L
    }
    if (loadProgramHeaders.isEmpty()) return emptyList()

    val mappings = mutableListOf<HexElfSectionSegmentMapping>()
    sections.asSequence()
        .filter { section -> section.size > 0L && section.type != ELF_SECTION_TYPE_NOBITS.toLong() }
        .forEach { section ->
            val loadProgramHeader = loadProgramHeaders.firstOrNull { programHeader ->
                programHeader.containsFileRange(section.fileOffset, section.size)
            } ?: return@forEach
            mappings += HexElfSectionSegmentMapping(
                index = mappings.size,
                sectionIndex = section.index,
                sectionName = section.name,
                sectionFileOffset = section.fileOffset,
                sectionSize = section.size,
                sectionVirtualAddress = section.virtualAddress,
                segmentIndex = loadProgramHeader.index,
                segmentTypeName = loadProgramHeader.typeName,
                segmentFileOffset = loadProgramHeader.fileOffset,
                segmentFileSize = loadProgramHeader.fileSize,
                segmentVirtualAddress = loadProgramHeader.virtualAddress,
                segmentMemorySize = loadProgramHeader.memorySize,
                segmentFlags = loadProgramHeader.flags,
                isExecutable = loadProgramHeader.isExecutable,
                isWritable = loadProgramHeader.isWritable,
                isReadable = loadProgramHeader.isReadable
            )
        }
    return mappings
}

internal fun readElfSectionEntropyEntries(
    randomAccessFile: RandomAccessFile,
    sections: List<HexElfSection>
): List<HexElfSectionEntropyEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfSectionEntropyEntry>()
    sections.asSequence()
        .filter { section -> section.size > 0L && section.type != ELF_SECTION_TYPE_NOBITS.toLong() }
        .filter { section -> section.fileOffset >= 0L && section.fileOffset < randomAccessFile.length() }
        .take(MAX_ELF_SECTION_ENTROPY_ENTRIES)
        .forEach { section ->
            val sampleSize = minOf(
                section.size,
                randomAccessFile.length() - section.fileOffset,
                ENTROPY_SAMPLE_BYTES.toLong()
            ).coerceAtLeast(0L)
            if (sampleSize <= 0L) return@forEach
            val bytes = randomAccessFile.readAt(section.fileOffset, sampleSize.coerceToInt())
            if (bytes.isEmpty()) return@forEach
            val entropy = bytes.shannonEntropy()
            entries += HexElfSectionEntropyEntry(
                index = entries.size,
                sectionIndex = section.index,
                sectionName = section.name,
                fileOffset = section.fileOffset,
                size = section.size,
                virtualAddress = section.virtualAddress,
                sampleSize = bytes.size.toLong(),
                entropy = entropy,
                level = entropyLevel(entropy),
                isAllocated = section.flags.hasElfFlag(ELF_SECTION_FLAG_ALLOC),
                isExecutable = section.flags.hasElfFlag(ELF_SECTION_FLAG_EXECINSTR),
                isWritable = section.flags.hasElfFlag(ELF_SECTION_FLAG_WRITE)
            )
        }
    return entries
}

internal fun readElfNoteEntries(
    randomAccessFile: RandomAccessFile,
    endian: HexEndian,
    machine: Int,
    sections: List<HexElfSection>
): List<HexElfNoteEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfNoteEntry>()
    val noteSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_NOTE.toLong() || section.name.startsWith(".note")
    }

    for (section in noteSections) {
        if (entries.size >= MAX_ELF_NOTES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val safeSize = minOf(
            section.size,
            randomAccessFile.length() - section.fileOffset,
            MAX_ELF_NOTE_SECTION_BYTES.toLong()
        ).toInt()
        val sectionBytes = randomAccessFile.readAt(section.fileOffset, safeSize)
        if (sectionBytes.size < ELF_NOTE_HEADER_SIZE) continue

        var noteOffset = 0
        while (noteOffset + ELF_NOTE_HEADER_SIZE <= sectionBytes.size && entries.size < MAX_ELF_NOTES) {
            val nameSize = sectionBytes.u32(noteOffset, endian)
            val descriptionSize = sectionBytes.u32(noteOffset + 4, endian)
            val type = sectionBytes.u32(noteOffset + 8, endian)
            if (nameSize == 0L && descriptionSize == 0L && type == 0L) break

            val alignedNameSize = nameSize.alignElfNoteFieldSize()
            val alignedDescriptionSize = descriptionSize.alignElfNoteFieldSize()
            val nameStartOffset = noteOffset + ELF_NOTE_HEADER_SIZE
            val descriptionStartOffset = noteOffset.toLong() + ELF_NOTE_HEADER_SIZE + alignedNameSize
            val nextNoteOffset = descriptionStartOffset + alignedDescriptionSize
            if (descriptionStartOffset > sectionBytes.size || nextNoteOffset > sectionBytes.size) break

            val name = sectionBytes.readElfNoteName(
                offset = nameStartOffset,
                byteCount = nameSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val descriptionBytes = sectionBytes.readElfNoteDescription(
                offset = descriptionStartOffset.toInt(),
                byteCount = descriptionSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val properties = if (isElfGnuPropertyNote(name, type)) {
                readElfGnuPropertyEntries(
                    noteFileOffset = section.fileOffset + noteOffset.toLong(),
                    descriptionOffset = section.fileOffset + descriptionStartOffset,
                    descriptionBytes = descriptionBytes,
                    endian = endian,
                    machine = machine
                )
            } else {
                emptyList()
            }

            entries += HexElfNoteEntry(
                index = entries.size,
                sectionName = section.name,
                name = name,
                type = type,
                noteFileOffset = section.fileOffset + noteOffset.toLong(),
                descriptionOffset = section.fileOffset + descriptionStartOffset,
                descriptionSize = descriptionSize,
                descriptionHex = descriptionBytes.toLowerHexString(),
                descriptionText = descriptionBytes.toPrintableAsciiStringOrNull(),
                isBuildId = isElfBuildIdNote(section.name, name, type),
                properties = properties
            )

            if (nextNoteOffset <= noteOffset.toLong()) break
            noteOffset = nextNoteOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
    return entries
}

internal fun readElfDynamicSymbols(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>,
    loadSegments: List<HexElfLoadSegment>
): List<HexElfSymbol> {
    if (sections.isEmpty()) return emptyList()
    val symbols = mutableListOf<HexElfSymbol>()
    val defaultEntrySize = if (is64Bit) ELF64_SYMBOL_ENTRY_SIZE else ELF32_SYMBOL_ENTRY_SIZE
    val dynamicSymbolSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_DYNAMIC_SYMBOLS.toLong() || section.name == ".dynsym"
    }

    for (symbolSection in dynamicSymbolSections) {
        if (symbols.size >= MAX_ELF_SYMBOLS) break
        val entrySize = symbolSection.entrySize.takeIf { it > 0L } ?: defaultEntrySize.toLong()
        if (entrySize <= 0L || symbolSection.fileOffset <= 0L || symbolSection.size <= 0L) continue
        if (symbolSection.fileOffset >= randomAccessFile.length()) continue

        val stringTableSection = sections.getOrNull(symbolSection.link)
            ?.takeIf { it.type == ELF_SECTION_TYPE_STRING_TABLE.toLong() || it.name.endsWith("str") }
            ?: sections.firstOrNull { it.name == ".dynstr" }
            ?: continue
        val stringTable = readElfStringTable(randomAccessFile, stringTableSection)
        if (stringTable.isEmpty()) continue

        val symbolCount = minOf(symbolSection.size / entrySize, (MAX_ELF_SYMBOLS - symbols.size).toLong()).toInt()
        for (symbolIndex in 0 until symbolCount) {
            val symbolBytes = randomAccessFile.readAt(
                offset = symbolSection.fileOffset + symbolIndex.toLong() * entrySize,
                byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            if (symbolBytes.size < defaultEntrySize) break

            val nameOffset = symbolBytes.u32(0, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val name = stringTable.readNullTerminatedAscii(nameOffset)
            if (name.isBlank()) continue

            val info = symbolBytes[if (is64Bit) 4 else 12].toInt() and 0xFF
            val binding = elfSymbolBinding(info ushr 4)
            val type = elfSymbolType(info and 0x0F)
            val sectionIndex = symbolBytes.u16(if (is64Bit) 6 else 14, endian)
            val value = if (is64Bit) symbolBytes.u64(8, endian) else symbolBytes.u32(4, endian)
            val size = if (is64Bit) symbolBytes.u64(16, endian) else symbolBytes.u32(8, endian)
            val isImported = sectionIndex == ELF_SYMBOL_SECTION_UNDEFINED
            val isExportBinding = binding == HexElfSymbolBinding.GLOBAL || binding == HexElfSymbolBinding.WEAK
            val isExportType = type == HexElfSymbolType.FUNC ||
                type == HexElfSymbolType.OBJECT ||
                type == HexElfSymbolType.NOTYPE
            val isExported = !isImported && isExportBinding && isExportType
            val fileOffset = loadSegments.virtualAddressToFileOffset(value)
            val resolvedSection = resolveElfSymbolSection(
                sections = sections,
                sectionIndex = sectionIndex,
                fileOffset = fileOffset
            )

            symbols += HexElfSymbol(
                name = name,
                value = value,
                fileOffset = fileOffset,
                size = size,
                binding = binding,
                type = type,
                sectionIndex = sectionIndex,
                isImported = isImported,
                isExported = isExported,
                isJni = name == "JNI_OnLoad" || name == "JNI_OnUnload" || name.startsWith("Java_"),
                sectionName = resolvedSection?.name?.takeIf { sectionName -> sectionName.isNotBlank() },
                sectionFileOffset = resolvedSection?.fileOffset,
                sectionSize = resolvedSection?.size
            )
        }
    }
    return symbols
}

internal fun resolveElfSymbolSection(
    sections: List<HexElfSection>,
    sectionIndex: Int,
    fileOffset: Long?
): HexElfSection? {
    if (sectionIndex != ELF_SYMBOL_SECTION_UNDEFINED) {
        sections.getOrNull(sectionIndex)
            ?.takeIf { section -> section.index == sectionIndex }
            ?.takeIf { section -> section.type != ELF_SECTION_TYPE_NOBITS.toLong() }
            ?.let { section -> return section }
    }
    val resolvedFileOffset = fileOffset ?: return null
    return sections.firstOrNull { section ->
        section.type != ELF_SECTION_TYPE_NOBITS.toLong() &&
            section.size > 0L &&
            resolvedFileOffset >= section.fileOffset &&
            resolvedFileOffset - section.fileOffset < section.size
    }
}

internal fun readElfDynamicStringEntries(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>
): List<HexElfDynamicStringEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfDynamicStringEntry>()
    val dynamicSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_DYNAMIC.toLong() || section.name == ".dynamic"
    }

    for (section in dynamicSections) {
        if (entries.size >= MAX_ELF_DYNAMIC_ENTRIES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val entrySize = section.entrySize.takeIf { it > 0L }
            ?: if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE.toLong() else ELF32_DYNAMIC_ENTRY_SIZE.toLong()
        if (entrySize <= 0L) continue

        val stringTableSection = sections.getOrNull(section.link)
            ?.takeIf { it.type == ELF_SECTION_TYPE_STRING_TABLE.toLong() || it.name.endsWith("str") }
            ?: sections.firstOrNull { it.name == ".dynstr" }
            ?: continue
        val stringTable = readElfStringTable(randomAccessFile, stringTableSection)
        if (stringTable.isEmpty()) continue

        val dynamicEntryCount = minOf(
            section.size / entrySize,
            (MAX_ELF_DYNAMIC_ENTRIES - entries.size).toLong()
        ).toInt()
        for (entryIndex in 0 until dynamicEntryCount) {
            val entryFileOffset = section.fileOffset + entryIndex.toLong() * entrySize
            val entryBytes = randomAccessFile.readAt(
                offset = entryFileOffset,
                byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val minimumEntrySize = if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE else ELF32_DYNAMIC_ENTRY_SIZE
            if (entryBytes.size < minimumEntrySize) break

            val tag = if (is64Bit) entryBytes.u64(0, endian) else entryBytes.u32(0, endian)
            if (tag == ELF_DYNAMIC_TAG_NULL) break
            val type = elfDynamicStringType(tag) ?: continue
            val rawValueOffset = if (is64Bit) entryBytes.u64(8, endian) else entryBytes.u32(4, endian)
            val valueOffset = rawValueOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val value = stringTable.readNullTerminatedAscii(valueOffset).takeIf { it.isNotBlank() } ?: continue

            entries += HexElfDynamicStringEntry(
                index = entryIndex,
                type = type,
                value = value,
                entryFileOffset = entryFileOffset
            )
        }
    }
    return entries.withDynamicStringSemantics()
}

internal fun List<HexElfDynamicStringEntry>.withDynamicStringSemantics(): List<HexElfDynamicStringEntry> {
    var neededLoadOrder = 0
    return map { entry ->
        when (entry.type) {
            HexElfDynamicStringType.NEEDED -> entry.copy(
                loadOrder = ++neededLoadOrder,
                semantic = HexElfDynamicStringSemantic.NEEDED_LIBRARY_LOAD
            )
            HexElfDynamicStringType.SONAME -> entry.copy(semantic = HexElfDynamicStringSemantic.SONAME_IDENTITY)
            HexElfDynamicStringType.RPATH -> entry.copy(semantic = HexElfDynamicStringSemantic.LEGACY_RPATH_SEARCH)
            HexElfDynamicStringType.RUNPATH -> entry.copy(semantic = HexElfDynamicStringSemantic.RUNPATH_SEARCH)
        }
    }
}

internal fun readElfDynamicFlagEntries(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>
): List<HexElfDynamicFlagEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfDynamicFlagEntry>()
    val dynamicSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_DYNAMIC.toLong() || section.name == ".dynamic"
    }

    for (section in dynamicSections) {
        if (entries.size >= MAX_ELF_DYNAMIC_ENTRIES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val entrySize = section.entrySize.takeIf { it > 0L }
            ?: if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE.toLong() else ELF32_DYNAMIC_ENTRY_SIZE.toLong()
        if (entrySize <= 0L) continue

        val dynamicEntryCount = minOf(
            section.size / entrySize,
            (MAX_ELF_DYNAMIC_ENTRIES - entries.size).toLong()
        ).toInt()
        for (entryIndex in 0 until dynamicEntryCount) {
            val entryFileOffset = section.fileOffset + entryIndex.toLong() * entrySize
            val entryBytes = randomAccessFile.readAt(
                offset = entryFileOffset,
                byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val minimumEntrySize = if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE else ELF32_DYNAMIC_ENTRY_SIZE
            if (entryBytes.size < minimumEntrySize) break

            val tag = if (is64Bit) entryBytes.u64(0, endian) else entryBytes.u32(0, endian)
            if (tag == ELF_DYNAMIC_TAG_NULL) break
            val type = elfDynamicFlagType(tag) ?: continue
            val value = if (is64Bit) entryBytes.u64(8, endian) else entryBytes.u32(4, endian)
            entries += HexElfDynamicFlagEntry(
                index = entryIndex,
                type = type,
                value = value,
                entryFileOffset = entryFileOffset,
                isBindNow = isElfBindNowDynamicFlag(type, value)
            )
        }
    }
    return entries
}

internal fun List<HexElfLoadSegment>.virtualAddressToFileOffset(virtualAddress: Long): Long? = firstNotNullOfOrNull { segment -> segment.virtualAddressToFileOffset(virtualAddress) }

internal fun HexElfProgramHeader.containsFileRange(fileOffset: Long, size: Long): Boolean {
    if (size <= 0L || fileSize <= 0L || fileOffset < this.fileOffset) return false
    val relativeStart = fileOffset - this.fileOffset
    return relativeStart <= fileSize && size <= fileSize - relativeStart
}

internal fun readElfInitArrayEntries(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>,
    loadSegments: List<HexElfLoadSegment>
): List<HexElfInitArrayEntry> {
    if (sections.isEmpty()) return emptyList()
    val pointerSize = if (is64Bit) Long.SIZE_BYTES else Int.SIZE_BYTES
    val entries = mutableListOf<HexElfInitArrayEntry>()
    val initArraySections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_INIT_ARRAY.toLong() || section.name == ".init_array"
    }

    for (section in initArraySections) {
        if (entries.size >= MAX_ELF_INIT_ARRAY_ENTRIES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val entryCount = minOf(
            section.size / pointerSize.toLong(),
            (MAX_ELF_INIT_ARRAY_ENTRIES - entries.size).toLong()
        ).toInt()
        for (entryIndex in 0 until entryCount) {
            val pointerFileOffset = section.fileOffset + entryIndex.toLong() * pointerSize
            val pointerBytes = randomAccessFile.readAt(pointerFileOffset, pointerSize)
            if (pointerBytes.size < pointerSize) break
            val functionAddress = if (is64Bit) pointerBytes.u64(0, endian) else pointerBytes.u32(0, endian)
            if (functionAddress == 0L) continue
            entries += HexElfInitArrayEntry(
                index = entryIndex,
                pointerFileOffset = pointerFileOffset,
                functionAddress = functionAddress,
                functionFileOffset = loadSegments.virtualAddressToFileOffset(functionAddress)
            )
        }
    }
    return entries
}

