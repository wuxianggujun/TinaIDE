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
 * ELF relocations, PLT/linkage, hardening, risk and native API builders.
 */

internal fun readElfRelocations(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    machine: Int,
    sections: List<HexElfSection>,
    loadSegments: List<HexElfLoadSegment>
): List<HexElfRelocationEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfRelocationEntry>()
    val relocationSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_RELOCATION_WITH_ADDEND.toLong() ||
            section.type == ELF_SECTION_TYPE_RELOCATION.toLong() ||
            section.name.startsWith(".rela.") ||
            section.name.startsWith(".rel.")
    }

    for (section in relocationSections) {
        if (entries.size >= MAX_ELF_RELOCATIONS) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val hasAddend = section.type == ELF_SECTION_TYPE_RELOCATION_WITH_ADDEND.toLong() ||
            section.name.startsWith(".rela.")
        val defaultEntrySize = when {
            hasAddend && is64Bit -> ELF64_RELOCATION_ADDEND_ENTRY_SIZE
            hasAddend -> ELF32_RELOCATION_ADDEND_ENTRY_SIZE
            is64Bit -> ELF64_RELOCATION_ENTRY_SIZE
            else -> ELF32_RELOCATION_ENTRY_SIZE
        }
        val entrySize = section.entrySize.takeIf { it > 0L } ?: defaultEntrySize.toLong()
        if (entrySize <= 0L) continue

        val symbolSection = sections.getOrNull(section.link)
            ?.takeIf { symbolSection ->
                symbolSection.type == ELF_SECTION_TYPE_SYMBOL_TABLE.toLong() ||
                    symbolSection.type == ELF_SECTION_TYPE_DYNAMIC_SYMBOLS.toLong()
            }
            ?: sections.firstOrNull { it.type == ELF_SECTION_TYPE_DYNAMIC_SYMBOLS.toLong() || it.name == ".dynsym" }
        val symbolReader = symbolSection?.let { linkedSymbolSection ->
            ElfRelocationSymbolReader(
                randomAccessFile = randomAccessFile,
                is64Bit = is64Bit,
                endian = endian,
                sections = sections,
                symbolSection = linkedSymbolSection
            )
        }

        val relocationCount = minOf(
            section.size / entrySize,
            (MAX_ELF_RELOCATIONS - entries.size).toLong()
        ).toInt()
        for (entryIndex in 0 until relocationCount) {
            val relocationFileOffset = section.fileOffset + entryIndex.toLong() * entrySize
            val relocationBytes = randomAccessFile.readAt(
                offset = relocationFileOffset,
                byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            if (relocationBytes.size < defaultEntrySize) break

            val offsetAddress = if (is64Bit) relocationBytes.u64(0, endian) else relocationBytes.u32(0, endian)
            val relocationInfo = if (is64Bit) relocationBytes.u64(8, endian) else relocationBytes.u32(4, endian)
            val symbolIndex = if (is64Bit) {
                relocationInfo ushr ELF64_RELOCATION_SYMBOL_SHIFT
            } else {
                relocationInfo ushr ELF32_RELOCATION_SYMBOL_SHIFT
            }
            val type = if (is64Bit) {
                relocationInfo and ELF64_RELOCATION_TYPE_MASK
            } else {
                relocationInfo and ELF32_RELOCATION_TYPE_MASK
            }
            val addend = if (hasAddend) {
                if (is64Bit) relocationBytes.u64(16, endian) else relocationBytes.u32(8, endian)
            } else {
                null
            }
            val symbolReference = symbolReader?.readReference(symbolIndex)
            val typeName = elfRelocationTypeName(machine, type)

            entries += HexElfRelocationEntry(
                index = entryIndex,
                sectionName = section.name,
                relocationFileOffset = relocationFileOffset,
                offsetAddress = offsetAddress,
                offsetFileOffset = loadSegments.virtualAddressToFileOffset(offsetAddress),
                targetSectionName = sections.sectionContainingVirtualAddress(offsetAddress)?.name?.takeIf { it.isNotBlank() },
                symbolName = symbolReference?.name,
                symbolBinding = symbolReference?.binding,
                symbolType = symbolReference?.type,
                isSymbolImported = symbolReference?.isImported == true,
                isSymbolExported = symbolReference?.isExported == true,
                isSymbolJni = symbolReference?.isJni == true,
                symbolIndex = symbolIndex,
                type = type,
                typeName = typeName,
                semantic = elfRelocationSemantic(typeName),
                addend = addend
            )
        }
    }
    return entries
}

internal fun buildElfLinkageEntries(
    randomAccessFile: RandomAccessFile,
    machine: Int,
    endian: HexEndian,
    sections: List<HexElfSection>,
    relocations: List<HexElfRelocationEntry>,
    bindNow: Boolean
): List<HexElfLinkageEntry> {
    if (relocations.isEmpty()) return emptyList()
    val pltStubResolver = ElfPltStubResolver(
        randomAccessFile = randomAccessFile,
        machine = machine,
        endian = endian,
        sections = sections
    )
    var pltEntryIndex = 0
    return relocations.asSequence()
        .filter { relocation ->
            relocation.symbolName != null ||
                relocation.offsetFileOffset != null ||
                relocation.targetSectionName != null
        }
        .take(MAX_ELF_LINKAGE_ENTRIES)
        .mapIndexed { index, relocation ->
            val entryKind = relocation.linkageEntryKind()
            val bindingMode = relocation.linkageBindingMode(entryKind, bindNow)
            val pltStub = if (entryKind == HexElfLinkageEntryKind.PLT) {
                pltStubResolver.readStub(
                    pltEntryIndex = pltEntryIndex++,
                    relocation = relocation
                )
            } else {
                null
            }
            HexElfLinkageEntry(
                index = index,
                symbolName = relocation.symbolName,
                symbolIndex = relocation.symbolIndex,
                relocationSectionName = relocation.sectionName,
                relocationTypeName = relocation.typeName,
                relocationFileOffset = relocation.relocationFileOffset,
                slotAddress = relocation.offsetAddress,
                slotFileOffset = relocation.offsetFileOffset,
                slotSectionName = relocation.targetSectionName,
                symbolBinding = relocation.symbolBinding,
                symbolType = relocation.symbolType,
                isImported = relocation.isSymbolImported,
                isExported = relocation.isSymbolExported,
                isJni = relocation.isSymbolJni,
                entryKind = entryKind,
                bindingMode = bindingMode,
                resolutionSemantic = relocation.linkageResolutionSemantic(entryKind, bindingMode),
                pltStub = pltStub
            )
        }
        .toList()
}

internal class ElfPltStubResolver(
    private val randomAccessFile: RandomAccessFile,
    private val machine: Int,
    private val endian: HexEndian,
    sections: List<HexElfSection>
) {
    private val pltSection: HexElfSection? = sections.firstOrNull { section ->
        section.name == ".plt"
    } ?: sections.firstOrNull { section -> section.name == ".plt.sec" }

    fun readStub(pltEntryIndex: Int, relocation: HexElfRelocationEntry): HexElfPltStub? {
        val section = pltSection ?: return null
        val layout = machine.pltLayout() ?: return null
        if (pltEntryIndex < 0 || section.fileOffset <= 0L || section.size <= 0L) return null
        val entryStartOffset = section.pltEntryStartOffset(machine) ?: return null
        val stubFileOffset = section.fileOffset +
            entryStartOffset +
            pltEntryIndex.toLong() * layout.entrySize.toLong()
        if (!section.containsFileRange(stubFileOffset, layout.entrySize)) return null

        val stubBytes = randomAccessFile.readAt(stubFileOffset, layout.entrySize)
        if (stubBytes.size < layout.minimumBytes) return null
        val architecture = machine.pltStubArchitecture() ?: return null
        val semantic = classifyPltStubSemantic(
            machine = machine,
            endian = endian,
            stubBytes = stubBytes
        )
        return HexElfPltStub(
            fileOffset = stubFileOffset,
            virtualAddress = section.virtualAddress + (stubFileOffset - section.fileOffset),
            byteCount = stubBytes.size,
            instructionBytes = stubBytes.toUpperHexByteString(),
            architecture = architecture,
            semantic = semantic,
            slotFileOffset = relocation.offsetFileOffset,
            slotAddress = relocation.offsetAddress
        )
    }

    internal fun HexElfSection.containsFileRange(fileOffset: Long, byteCount: Int): Boolean {
        if (byteCount <= 0 || fileOffset < this.fileOffset) return false
        val relativeStart = fileOffset - this.fileOffset
        return relativeStart <= size && byteCount.toLong() <= size - relativeStart
    }
}

internal fun HexElfSection.pltEntryStartOffset(machine: Int): Long? = when (machine) {
    ELF_MACHINE_AARCH64 -> when (name) {
        ".plt" -> ELF_AARCH64_PLT_RESOLVER_STUB_SIZE.toLong()
        ".plt.sec" -> 0L
        else -> null
    }
    ELF_MACHINE_X86_64 -> when (name) {
        ".plt" -> ELF_X86_64_PLT_RESOLVER_STUB_SIZE.toLong()
        ".plt.sec" -> 0L
        else -> null
    }
    else -> null
}

internal data class ElfPltLayout(
    val resolverStubSize: Int,
    val entrySize: Int,
    val minimumBytes: Int
)

internal fun Int.pltLayout(): ElfPltLayout? = when (this) {
    ELF_MACHINE_AARCH64 -> ElfPltLayout(
        resolverStubSize = ELF_AARCH64_PLT_RESOLVER_STUB_SIZE,
        entrySize = ELF_AARCH64_PLT_ENTRY_SIZE,
        minimumBytes = ELF_AARCH64_PLT_ENTRY_SIZE
    )
    ELF_MACHINE_X86_64 -> ElfPltLayout(
        resolverStubSize = ELF_X86_64_PLT_RESOLVER_STUB_SIZE,
        entrySize = ELF_X86_64_PLT_ENTRY_SIZE,
        minimumBytes = ELF_X86_64_PLT_ENTRY_SIZE
    )
    else -> null
}

internal fun Int.pltStubArchitecture(): HexElfPltStubArchitecture? = when (this) {
    ELF_MACHINE_AARCH64 -> HexElfPltStubArchitecture.AARCH64
    ELF_MACHINE_X86_64 -> HexElfPltStubArchitecture.X86_64
    else -> null
}

internal fun classifyPltStubSemantic(
    machine: Int,
    endian: HexEndian,
    stubBytes: ByteArray
): HexElfPltStubSemantic = when {
    machine == ELF_MACHINE_AARCH64 && stubBytes.hasAarch64GotBranchPltStub(endian) ->
        HexElfPltStubSemantic.LOAD_GOT_SLOT_AND_BRANCH
    machine == ELF_MACHINE_X86_64 && stubBytes.hasX86_64GotBranchPltStub() ->
        HexElfPltStubSemantic.LOAD_GOT_SLOT_AND_BRANCH
    else -> HexElfPltStubSemantic.UNKNOWN
}

internal fun ByteArray.hasAarch64GotBranchPltStub(endian: HexEndian): Boolean {
    if (size < ELF_AARCH64_PLT_ENTRY_SIZE) return false
    val adrp = u32(0, endian)
    val ldr = u32(4, endian)
    val add = u32(8, endian)
    val br = u32(12, endian)
    return (adrp and AARCH64_ADRP_X16_MASK) == AARCH64_ADRP_X16_VALUE &&
        (ldr and AARCH64_LDR_X17_FROM_X16_MASK) == AARCH64_LDR_X17_FROM_X16_VALUE &&
        (add and AARCH64_ADD_X16_FROM_X16_MASK) == AARCH64_ADD_X16_FROM_X16_VALUE &&
        br == AARCH64_BR_X17_VALUE
}

internal fun ByteArray.hasX86_64GotBranchPltStub(): Boolean {
    if (size < ELF_X86_64_PLT_ENTRY_SIZE) return false
    return this[0] == 0xFF.toByte() &&
        this[1] == 0x25.toByte() &&
        this[6] == 0x68.toByte() &&
        this[11] == 0xE9.toByte()
}

internal fun HexElfRelocationEntry.linkageEntryKind(): HexElfLinkageEntryKind {
    val relocationTypeName = typeName.orEmpty()
    return when {
        relocationTypeName.contains("JUMP_SLOT", ignoreCase = true) -> HexElfLinkageEntryKind.PLT
        relocationTypeName.contains("GLOB_DAT", ignoreCase = true) -> HexElfLinkageEntryKind.GOT
        relocationTypeName.contains("RELATIVE", ignoreCase = true) -> HexElfLinkageEntryKind.RELATIVE
        targetSectionName?.contains("got", ignoreCase = true) == true -> HexElfLinkageEntryKind.GOT
        sectionName.contains(".plt", ignoreCase = true) -> HexElfLinkageEntryKind.PLT
        else -> HexElfLinkageEntryKind.OTHER
    }
}

internal fun HexElfRelocationEntry.linkageBindingMode(
    entryKind: HexElfLinkageEntryKind,
    bindNow: Boolean
): HexElfLinkageBindingMode = when {
    entryKind == HexElfLinkageEntryKind.PLT -> {
        if (bindNow) HexElfLinkageBindingMode.NOW else HexElfLinkageBindingMode.LAZY
    }
    entryKind == HexElfLinkageEntryKind.GOT || isSymbolImported -> HexElfLinkageBindingMode.LOAD_TIME
    else -> HexElfLinkageBindingMode.LOCAL
}

internal fun HexElfRelocationEntry.linkageResolutionSemantic(
    entryKind: HexElfLinkageEntryKind,
    bindingMode: HexElfLinkageBindingMode
): HexElfLinkageResolutionSemantic = when {
    entryKind == HexElfLinkageEntryKind.PLT && bindingMode == HexElfLinkageBindingMode.LAZY ->
        HexElfLinkageResolutionSemantic.LAZY_PLT_CALL
    entryKind == HexElfLinkageEntryKind.PLT -> HexElfLinkageResolutionSemantic.EAGER_PLT_BINDING
    entryKind == HexElfLinkageEntryKind.GOT || bindingMode == HexElfLinkageBindingMode.LOAD_TIME ->
        HexElfLinkageResolutionSemantic.LOAD_TIME_GOT_WRITE
    entryKind == HexElfLinkageEntryKind.RELATIVE ||
        typeName?.contains("RELATIVE", ignoreCase = true) == true -> HexElfLinkageResolutionSemantic.RELATIVE_REBASE
    else -> HexElfLinkageResolutionSemantic.LOCAL_RELOCATION
}

internal fun buildElfDynamicLinkerSteps(
    programHeaders: List<HexElfProgramHeader>,
    dynamicStringEntries: List<HexElfDynamicStringEntry>,
    hardeningChecks: List<HexElfHardeningCheck>,
    initArrayEntries: List<HexElfInitArrayEntry>,
    linkageEntries: List<HexElfLinkageEntry>,
    dynamicSymbols: List<HexElfSymbol>
): List<HexElfDynamicLinkerStep> {
    val steps = mutableListOf<HexElfDynamicLinkerStep>()

    fun addStep(
        type: HexElfDynamicLinkerStepType,
        evidenceFileOffset: Long?,
        relatedCount: Int,
        detailValue: String? = null
    ) {
        steps += HexElfDynamicLinkerStep(
            index = steps.size,
            type = type,
            evidenceFileOffset = evidenceFileOffset,
            relatedCount = relatedCount,
            detailValue = detailValue?.takeIf { it.isNotBlank() }
        )
    }

    val loadProgramHeaders = programHeaders.filter { programHeader -> programHeader.isLoad }
    if (loadProgramHeaders.isNotEmpty()) {
        addStep(
            type = HexElfDynamicLinkerStepType.MAP_LOAD_SEGMENTS,
            evidenceFileOffset = loadProgramHeaders.first().programHeaderFileOffset,
            relatedCount = loadProgramHeaders.size
        )
    }

    val neededLibraries = dynamicStringEntries.filter { entry -> entry.type == HexElfDynamicStringType.NEEDED }
    if (neededLibraries.isNotEmpty()) {
        addStep(
            type = HexElfDynamicLinkerStepType.LOAD_NEEDED_LIBRARIES,
            evidenceFileOffset = neededLibraries.first().entryFileOffset,
            relatedCount = neededLibraries.size,
            detailValue = buildNeededLibraryLoadDetail(
                neededLibraries = neededLibraries,
                searchPaths = dynamicStringEntries.filter { entry ->
                    entry.type == HexElfDynamicStringType.RPATH || entry.type == HexElfDynamicStringType.RUNPATH
                }
            )
        )
    }

    if (linkageEntries.isNotEmpty()) {
        addStep(
            type = HexElfDynamicLinkerStepType.APPLY_RELOCATIONS,
            evidenceFileOffset = linkageEntries.first().relocationFileOffset,
            relatedCount = linkageEntries.size,
            detailValue = linkageEntries.joinToString(limit = DYNAMIC_LINKER_STEP_DETAIL_LIMIT) { entry ->
                entry.symbolName ?: "#${entry.symbolIndex}"
            }
        )
    }

    val nowBindings = linkageEntries.filter { entry ->
        entry.bindingMode == HexElfLinkageBindingMode.NOW ||
            entry.bindingMode == HexElfLinkageBindingMode.LOAD_TIME
    }
    if (nowBindings.isNotEmpty()) {
        addStep(
            type = HexElfDynamicLinkerStepType.RESOLVE_NOW_BINDINGS,
            evidenceFileOffset = nowBindings.first().slotFileOffset ?: nowBindings.first().relocationFileOffset,
            relatedCount = nowBindings.size,
            detailValue = nowBindings.joinToString(limit = DYNAMIC_LINKER_STEP_DETAIL_LIMIT) { entry ->
                entry.symbolName ?: "#${entry.symbolIndex}"
            }
        )
    }

    val lazyBindings = linkageEntries.filter { entry -> entry.bindingMode == HexElfLinkageBindingMode.LAZY }
    if (lazyBindings.isNotEmpty()) {
        addStep(
            type = HexElfDynamicLinkerStepType.ENABLE_LAZY_PLT,
            evidenceFileOffset = lazyBindings.first().slotFileOffset ?: lazyBindings.first().relocationFileOffset,
            relatedCount = lazyBindings.size,
            detailValue = lazyBindings.joinToString(limit = DYNAMIC_LINKER_STEP_DETAIL_LIMIT) { entry ->
                entry.symbolName ?: "#${entry.symbolIndex}"
            }
        )
    }

    hardeningChecks.firstOrNull { check -> check.type == HexElfHardeningType.RELRO && check.enabled }?.let { relro ->
        addStep(
            type = HexElfDynamicLinkerStepType.PROTECT_RELRO,
            evidenceFileOffset = relro.evidenceFileOffset,
            relatedCount = 1
        )
    }

    if (initArrayEntries.isNotEmpty()) {
        addStep(
            type = HexElfDynamicLinkerStepType.CALL_INIT_ARRAY,
            evidenceFileOffset = initArrayEntries.first().pointerFileOffset,
            relatedCount = initArrayEntries.size
        )
    }

    val jniSymbols = dynamicSymbols.filter { symbol -> symbol.isJni }
    if (jniSymbols.isNotEmpty()) {
        addStep(
            type = HexElfDynamicLinkerStepType.EXPOSE_JNI_ENTRYPOINTS,
            evidenceFileOffset = jniSymbols.firstNotNullOfOrNull { symbol -> symbol.fileOffset },
            relatedCount = jniSymbols.size,
            detailValue = jniSymbols.joinToString(limit = DYNAMIC_LINKER_STEP_DETAIL_LIMIT) { symbol -> symbol.name }
        )
    }

    return steps.take(MAX_ELF_DYNAMIC_LINKER_STEPS)
}

internal fun buildNeededLibraryLoadDetail(
    neededLibraries: List<HexElfDynamicStringEntry>,
    searchPaths: List<HexElfDynamicStringEntry>
): String {
    val neededDetail = neededLibraries.joinToString(limit = DYNAMIC_LINKER_STEP_DETAIL_LIMIT) { entry ->
        entry.loadOrder?.let { loadOrder -> "#$loadOrder ${entry.value}" } ?: entry.value
    }
    val searchPathDetail = searchPaths.joinToString(limit = DYNAMIC_LINKER_STEP_DETAIL_LIMIT) { entry ->
        "${entry.type.name} ${entry.value}"
    }
    return listOf(neededDetail, searchPathDetail)
        .filter { detail -> detail.isNotBlank() }
        .joinToString("; ")
}

internal fun List<HexElfSection>.sectionContainingVirtualAddress(address: Long): HexElfSection? = firstOrNull { section ->
    if (section.virtualAddress <= 0L || section.size <= 0L || address < section.virtualAddress) {
        false
    } else {
        address - section.virtualAddress in 0 until section.size
    }
}

internal data class ElfRelocationSymbolReference(
    val name: String?,
    val binding: HexElfSymbolBinding,
    val type: HexElfSymbolType,
    val sectionIndex: Int,
    val isImported: Boolean,
    val isExported: Boolean,
    val isJni: Boolean
)

internal class ElfRelocationSymbolReader(
    private val randomAccessFile: RandomAccessFile,
    private val is64Bit: Boolean,
    private val endian: HexEndian,
    private val sections: List<HexElfSection>,
    private val symbolSection: HexElfSection
) {
    private val entrySize: Long = symbolSection.entrySize.takeIf { it > 0L }
        ?: if (is64Bit) ELF64_SYMBOL_ENTRY_SIZE.toLong() else ELF32_SYMBOL_ENTRY_SIZE.toLong()
    private val stringTable: ByteArray = sections.getOrNull(symbolSection.link)
        ?.takeIf { section -> section.type == ELF_SECTION_TYPE_STRING_TABLE.toLong() || section.name.endsWith("str") }
        ?.let { stringTableSection -> readElfStringTable(randomAccessFile, stringTableSection) }
        ?: ByteArray(0)

    fun readReference(symbolIndex: Long): ElfRelocationSymbolReference? {
        if (symbolIndex <= 0L || entrySize <= 0L) return null
        val symbolOffset = symbolSection.fileOffset + symbolIndex * entrySize
        val symbolBytes = randomAccessFile.readAt(
            offset = symbolOffset,
            byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
        val minimumEntrySize = if (is64Bit) ELF64_SYMBOL_ENTRY_SIZE else ELF32_SYMBOL_ENTRY_SIZE
        if (symbolBytes.size < minimumEntrySize) return null
        val nameOffset = symbolBytes.u32(0, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val name = stringTable.readNullTerminatedAscii(nameOffset).takeIf { it.isNotBlank() }
        val info = symbolBytes[if (is64Bit) 4 else 12].toInt() and 0xFF
        val binding = elfSymbolBinding(info ushr 4)
        val type = elfSymbolType(info and 0x0F)
        val sectionIndex = symbolBytes.u16(if (is64Bit) 6 else 14, endian)
        val isImported = sectionIndex == ELF_SYMBOL_SECTION_UNDEFINED
        val isExportBinding = binding == HexElfSymbolBinding.GLOBAL || binding == HexElfSymbolBinding.WEAK
        val isExportType = type == HexElfSymbolType.FUNC ||
            type == HexElfSymbolType.OBJECT ||
            type == HexElfSymbolType.NOTYPE
        val isExported = !isImported && isExportBinding && isExportType
        return ElfRelocationSymbolReference(
            name = name,
            binding = binding,
            type = type,
            sectionIndex = sectionIndex,
            isImported = isImported,
            isExported = isExported,
            isJni = name == "JNI_OnLoad" || name == "JNI_OnUnload" || name?.startsWith("Java_") == true
        )
    }
}

internal fun readElfStringTable(randomAccessFile: RandomAccessFile, section: HexElfSection): ByteArray {
    if (section.fileOffset <= 0L || section.size <= 0L || section.fileOffset >= randomAccessFile.length()) {
        return ByteArray(0)
    }
    val safeSize = minOf(
        section.size,
        randomAccessFile.length() - section.fileOffset,
        MAX_ELF_STRING_TABLE_BYTES.toLong()
    ).toInt()
    return randomAccessFile.readAt(section.fileOffset, safeSize)
}

internal fun elfSymbolBinding(binding: Int): HexElfSymbolBinding = when (binding) {
    ELF_SYMBOL_BIND_LOCAL -> HexElfSymbolBinding.LOCAL
    ELF_SYMBOL_BIND_GLOBAL -> HexElfSymbolBinding.GLOBAL
    ELF_SYMBOL_BIND_WEAK -> HexElfSymbolBinding.WEAK
    else -> HexElfSymbolBinding.OTHER
}

internal fun elfSymbolType(type: Int): HexElfSymbolType = when (type) {
    ELF_SYMBOL_TYPE_NOTYPE -> HexElfSymbolType.NOTYPE
    ELF_SYMBOL_TYPE_OBJECT -> HexElfSymbolType.OBJECT
    ELF_SYMBOL_TYPE_FUNC -> HexElfSymbolType.FUNC
    ELF_SYMBOL_TYPE_SECTION -> HexElfSymbolType.SECTION
    ELF_SYMBOL_TYPE_FILE -> HexElfSymbolType.FILE
    ELF_SYMBOL_TYPE_TLS -> HexElfSymbolType.TLS
    else -> HexElfSymbolType.OTHER
}

internal fun elfProgramHeaderTypeName(type: Long): String = when (type) {
    ELF_PROGRAM_TYPE_NULL -> "NULL"
    ELF_PROGRAM_TYPE_LOAD.toLong() -> "LOAD"
    ELF_PROGRAM_TYPE_DYNAMIC -> "DYNAMIC"
    ELF_PROGRAM_TYPE_INTERP -> "INTERP"
    ELF_PROGRAM_TYPE_NOTE -> "NOTE"
    ELF_PROGRAM_TYPE_PHDR -> "PHDR"
    ELF_PROGRAM_TYPE_TLS -> "TLS"
    ELF_PROGRAM_TYPE_GNU_EH_FRAME -> "GNU_EH_FRAME"
    ELF_PROGRAM_TYPE_GNU_STACK -> "GNU_STACK"
    ELF_PROGRAM_TYPE_GNU_RELRO -> "GNU_RELRO"
    else -> "0x%X".format(type)
}

internal fun elfDynamicStringType(tag: Long): HexElfDynamicStringType? = when (tag) {
    ELF_DYNAMIC_TAG_NEEDED -> HexElfDynamicStringType.NEEDED
    ELF_DYNAMIC_TAG_SONAME -> HexElfDynamicStringType.SONAME
    ELF_DYNAMIC_TAG_RPATH -> HexElfDynamicStringType.RPATH
    ELF_DYNAMIC_TAG_RUNPATH -> HexElfDynamicStringType.RUNPATH
    else -> null
}

internal fun elfDynamicFlagType(tag: Long): HexElfDynamicFlagType? = when (tag) {
    ELF_DYNAMIC_TAG_BIND_NOW -> HexElfDynamicFlagType.BIND_NOW
    ELF_DYNAMIC_TAG_FLAGS -> HexElfDynamicFlagType.FLAGS
    ELF_DYNAMIC_TAG_FLAGS_1 -> HexElfDynamicFlagType.FLAGS_1
    else -> null
}

internal fun isElfBindNowDynamicFlag(type: HexElfDynamicFlagType, value: Long): Boolean = when (type) {
    HexElfDynamicFlagType.BIND_NOW -> true
    HexElfDynamicFlagType.FLAGS -> (value and ELF_DYNAMIC_FLAG_BIND_NOW) != 0L
    HexElfDynamicFlagType.FLAGS_1 -> (value and ELF_DYNAMIC_FLAG_1_NOW) != 0L
}

internal fun buildElfHardeningChecks(
    elfType: Int,
    programHeaders: List<HexElfProgramHeader>,
    dynamicFlagEntries: List<HexElfDynamicFlagEntry>,
    noteEntries: List<HexElfNoteEntry>
): List<HexElfHardeningCheck> {
    if (programHeaders.isEmpty()) return emptyList()

    val stackHeader = programHeaders.firstOrNull { it.type == ELF_PROGRAM_TYPE_GNU_STACK }
    val relroHeader = programHeaders.firstOrNull { it.type == ELF_PROGRAM_TYPE_GNU_RELRO }
    val bindNowEntry = dynamicFlagEntries.firstOrNull { it.isBindNow }
    val checks = mutableListOf(
        HexElfHardeningCheck(
            type = HexElfHardeningType.PIE,
            enabled = elfType == ELF_TYPE_DYN,
            evidenceFileOffset = null
        ),
        HexElfHardeningCheck(
            type = HexElfHardeningType.NX,
            enabled = stackHeader?.isExecutable != true,
            evidenceFileOffset = stackHeader?.programHeaderFileOffset
        ),
        HexElfHardeningCheck(
            type = HexElfHardeningType.RELRO,
            enabled = relroHeader != null,
            evidenceFileOffset = relroHeader?.programHeaderFileOffset
        ),
        HexElfHardeningCheck(
            type = HexElfHardeningType.BIND_NOW,
            enabled = bindNowEntry != null,
            evidenceFileOffset = bindNowEntry?.entryFileOffset
        )
    )

    val propertyEntries = noteEntries.asSequence()
        .flatMap { note -> note.properties.asSequence() }
        .toList()
    propertyEntries.firstOrNull { entry -> entry.features.contains(HexElfNotePropertyFeature.X86_IBT) }?.let { entry ->
        checks += HexElfHardeningCheck(
            type = HexElfHardeningType.IBT,
            enabled = true,
            evidenceFileOffset = entry.propertyOffset
        )
    }
    propertyEntries.firstOrNull { entry -> entry.features.contains(HexElfNotePropertyFeature.X86_SHSTK) }?.let { entry ->
        checks += HexElfHardeningCheck(
            type = HexElfHardeningType.SHSTK,
            enabled = true,
            evidenceFileOffset = entry.propertyOffset
        )
    }
    propertyEntries.firstOrNull { entry -> entry.features.contains(HexElfNotePropertyFeature.AARCH64_BTI) }?.let { entry ->
        checks += HexElfHardeningCheck(
            type = HexElfHardeningType.BTI,
            enabled = true,
            evidenceFileOffset = entry.propertyOffset
        )
    }
    propertyEntries.firstOrNull { entry -> entry.features.contains(HexElfNotePropertyFeature.AARCH64_PAC) }?.let { entry ->
        checks += HexElfHardeningCheck(
            type = HexElfHardeningType.PAC,
            enabled = true,
            evidenceFileOffset = entry.propertyOffset
        )
    }

    return checks
}

internal fun buildElfRiskFindings(
    programHeaders: List<HexElfProgramHeader>,
    sections: List<HexElfSection>,
    hardeningChecks: List<HexElfHardeningCheck>,
    dynamicStringEntries: List<HexElfDynamicStringEntry>
): List<HexElfRiskFinding> {
    val findings = mutableListOf<HexElfRiskFinding>()

    fun addFinding(
        type: HexElfRiskFindingType,
        severity: HexElfRiskSeverity,
        evidenceFileOffset: Long?,
        detailValue: String? = null
    ) {
        if (findings.size >= MAX_ELF_RISK_FINDINGS) return
        findings += HexElfRiskFinding(
            index = findings.size,
            type = type,
            severity = severity,
            evidenceFileOffset = evidenceFileOffset,
            detailValue = detailValue?.takeIf { it.isNotBlank() }
        )
    }

    programHeaders
        .asSequence()
        .filter { programHeader -> programHeader.isLoad && programHeader.isWritable && programHeader.isExecutable }
        .forEach { programHeader ->
            addFinding(
                type = HexElfRiskFindingType.RWX_LOAD_SEGMENT,
                severity = HexElfRiskSeverity.HIGH,
                evidenceFileOffset = programHeader.programHeaderFileOffset,
                detailValue = programHeader.typeName
            )
        }

    sections
        .asSequence()
        .filter { section ->
            section.flags.hasElfFlag(ELF_SECTION_FLAG_WRITE) &&
                section.flags.hasElfFlag(ELF_SECTION_FLAG_EXECINSTR)
        }
        .forEach { section ->
            addFinding(
                type = HexElfRiskFindingType.WRITABLE_EXECUTABLE_SECTION,
                severity = HexElfRiskSeverity.HIGH,
                evidenceFileOffset = section.fileOffset,
                detailValue = section.name.ifBlank { "#${section.index}" }
            )
        }

    hardeningChecks.firstOrNull { check -> check.type == HexElfHardeningType.NX && !check.enabled }?.let { check ->
        addFinding(
            type = HexElfRiskFindingType.EXECUTABLE_STACK,
            severity = HexElfRiskSeverity.HIGH,
            evidenceFileOffset = check.evidenceFileOffset,
            detailValue = "PT_GNU_STACK"
        )
    }

    hardeningChecks.firstOrNull { check -> check.type == HexElfHardeningType.RELRO && !check.enabled }?.let { check ->
        addFinding(
            type = HexElfRiskFindingType.MISSING_RELRO,
            severity = HexElfRiskSeverity.WARNING,
            evidenceFileOffset = check.evidenceFileOffset,
            detailValue = "PT_GNU_RELRO"
        )
    }

    hardeningChecks.firstOrNull { check -> check.type == HexElfHardeningType.BIND_NOW && !check.enabled }?.let { check ->
        addFinding(
            type = HexElfRiskFindingType.MISSING_BIND_NOW,
            severity = HexElfRiskSeverity.WARNING,
            evidenceFileOffset = check.evidenceFileOffset,
            detailValue = "BIND_NOW"
        )
    }

    dynamicStringEntries
        .asSequence()
        .filter { entry -> entry.type == HexElfDynamicStringType.RPATH }
        .forEach { entry ->
            addFinding(
                type = HexElfRiskFindingType.LEGACY_RPATH,
                severity = HexElfRiskSeverity.WARNING,
                evidenceFileOffset = entry.entryFileOffset,
                detailValue = entry.value
            )
        }

    dynamicStringEntries
        .asSequence()
        .filter { entry -> entry.type == HexElfDynamicStringType.RUNPATH }
        .forEach { entry ->
            addFinding(
                type = HexElfRiskFindingType.RUNPATH_PRESENT,
                severity = HexElfRiskSeverity.INFO,
                evidenceFileOffset = entry.entryFileOffset,
                detailValue = entry.value
            )
        }

    if (dynamicStringEntries.isNotEmpty() &&
        dynamicStringEntries.none { entry ->
            entry.type == HexElfDynamicStringType.SONAME
        }
    ) {
        addFinding(
            type = HexElfRiskFindingType.MISSING_SONAME,
            severity = HexElfRiskSeverity.INFO,
            evidenceFileOffset = null,
            detailValue = "DT_SONAME"
        )
    }

    return findings
}

internal fun buildElfJniRegistrationHints(
    elf: HexElfSummary,
    strings: List<HexStringEntry>
): List<HexElfJniRegistrationHint> {
    val hints = mutableListOf<HexElfJniRegistrationHint>()
    val seenKeys = mutableSetOf<String>()

    fun addHint(
        type: HexElfJniRegistrationHintType,
        evidenceFileOffset: Long?,
        symbolName: String? = null,
        stringValue: String? = null
    ) {
        if (hints.size >= MAX_ELF_JNI_HINTS) return
        val key = listOf(type.name, evidenceFileOffset?.toString().orEmpty(), symbolName.orEmpty(), stringValue.orEmpty())
            .joinToString("|")
        if (!seenKeys.add(key)) return
        hints += HexElfJniRegistrationHint(
            index = hints.size,
            type = type,
            evidenceFileOffset = evidenceFileOffset,
            symbolName = symbolName?.takeIf { it.isNotBlank() },
            stringValue = stringValue?.takeIf { it.isNotBlank() }
        )
    }

    elf.dynamicSymbols.forEach { symbol ->
        when {
            symbol.name == "RegisterNatives" || symbol.name.endsWith("_RegisterNatives") -> {
                addHint(
                    type = HexElfJniRegistrationHintType.REGISTER_NATIVES_SYMBOL,
                    evidenceFileOffset = symbol.fileOffset,
                    symbolName = symbol.name
                )
            }
            symbol.name == "JNI_OnLoad" -> {
                addHint(
                    type = HexElfJniRegistrationHintType.JNI_ONLOAD_ENTRY,
                    evidenceFileOffset = symbol.fileOffset,
                    symbolName = symbol.name
                )
            }
            symbol.name == "JNI_OnUnload" -> {
                addHint(
                    type = HexElfJniRegistrationHintType.JNI_ONUNLOAD_ENTRY,
                    evidenceFileOffset = symbol.fileOffset,
                    symbolName = symbol.name
                )
            }
            symbol.name.startsWith("Java_") -> {
                addHint(
                    type = HexElfJniRegistrationHintType.STATIC_JNI_EXPORT,
                    evidenceFileOffset = symbol.fileOffset,
                    symbolName = symbol.name
                )
            }
        }
    }

    strings.forEach { entry ->
        val value = entry.value.trim()
        when {
            value.contains("RegisterNatives", ignoreCase = true) -> {
                addHint(
                    type = HexElfJniRegistrationHintType.REGISTER_NATIVES_STRING,
                    evidenceFileOffset = entry.offset,
                    stringValue = value
                )
            }
            value.isLikelyJavaClassDescriptor() -> {
                addHint(
                    type = HexElfJniRegistrationHintType.JAVA_CLASS_DESCRIPTOR,
                    evidenceFileOffset = entry.offset,
                    stringValue = value
                )
            }
            value.isLikelyJniMethodSignature() -> {
                addHint(
                    type = HexElfJniRegistrationHintType.JNI_METHOD_SIGNATURE,
                    evidenceFileOffset = entry.offset,
                    stringValue = value
                )
            }
        }
    }

    return hints
}

internal fun buildElfNativeApiHints(symbols: List<HexElfSymbol>): List<HexElfNativeApiHint> {
    val hints = mutableListOf<HexElfNativeApiHint>()
    val seenSymbols = mutableSetOf<String>()
    symbols.asSequence()
        .filter { symbol -> symbol.isImported }
        .forEach { symbol ->
            val category = nativeApiCategory(symbol.name) ?: return@forEach
            if (!seenSymbols.add("${category.name}:${symbol.name}")) return@forEach
            if (hints.size >= MAX_ELF_NATIVE_API_HINTS) return hints
            hints += HexElfNativeApiHint(
                index = hints.size,
                category = category,
                symbolName = symbol.name,
                evidenceFileOffset = symbol.fileOffset
            )
        }
    return hints
}

internal fun nativeApiCategory(symbolName: String): HexElfNativeApiCategory? {
    val normalizedName = symbolName.removePrefix("__").substringBefore('@')
    return when {
        normalizedName in NATIVE_DYNAMIC_LOADING_SYMBOLS -> HexElfNativeApiCategory.DYNAMIC_LOADING
        normalizedName in NATIVE_MEMORY_PROTECTION_SYMBOLS -> HexElfNativeApiCategory.MEMORY_PROTECTION
        normalizedName in NATIVE_PROCESS_CONTROL_SYMBOLS -> HexElfNativeApiCategory.PROCESS_CONTROL
        normalizedName in NATIVE_FILE_IO_SYMBOLS -> HexElfNativeApiCategory.FILE_IO
        normalizedName in NATIVE_NETWORK_SYMBOLS -> HexElfNativeApiCategory.NETWORK
        normalizedName in NATIVE_THREADING_SYMBOLS -> HexElfNativeApiCategory.THREADING
        normalizedName in NATIVE_LOGGING_SYMBOLS -> HexElfNativeApiCategory.LOGGING
        NATIVE_CRYPTO_SYMBOL_PREFIXES.any { prefix -> normalizedName.startsWith(prefix) } ->
            HexElfNativeApiCategory.CRYPTO
        else -> null
    }
}

internal fun elfRelocationTypeName(machine: Int, type: Long): String? = when (machine) {
    ELF_MACHINE_386 -> when (type) {
        1L -> "I386_32"
        2L -> "I386_PC32"
        6L -> "I386_GLOB_DAT"
        7L -> "I386_JUMP_SLOT"
        8L -> "I386_RELATIVE"
        else -> null
    }
    ELF_MACHINE_ARM -> when (type) {
        2L -> "ARM_ABS32"
        21L -> "ARM_GLOB_DAT"
        22L -> "ARM_JUMP_SLOT"
        23L -> "ARM_RELATIVE"
        else -> null
    }
    ELF_MACHINE_X86_64 -> when (type) {
        1L -> "X86_64_64"
        2L -> "X86_64_PC32"
        6L -> "X86_64_GLOB_DAT"
        7L -> "X86_64_JUMP_SLOT"
        8L -> "X86_64_RELATIVE"
        else -> null
    }
    ELF_MACHINE_AARCH64 -> when (type) {
        257L -> "AARCH64_ABS64"
        258L -> "AARCH64_ABS32"
        1024L -> "AARCH64_COPY"
        1025L -> "AARCH64_GLOB_DAT"
        1026L -> "AARCH64_JUMP_SLOT"
        1027L -> "AARCH64_RELATIVE"
        else -> null
    }
    ELF_MACHINE_RISCV -> when (type) {
        2L -> "RISCV_64"
        3L -> "RISCV_RELATIVE"
        5L -> "RISCV_JUMP_SLOT"
        else -> null
    }
    else -> null
}

internal fun elfRelocationSemantic(typeName: String?): HexElfRelocationSemantic {
    val normalizedTypeName = typeName?.uppercase() ?: return HexElfRelocationSemantic.OTHER
    return when {
        "JUMP_SLOT" in normalizedTypeName -> HexElfRelocationSemantic.JUMP_SLOT_BINDING
        "GLOB_DAT" in normalizedTypeName -> HexElfRelocationSemantic.GLOB_DAT_ADDRESS
        "RELATIVE" in normalizedTypeName -> HexElfRelocationSemantic.RELATIVE_REBASE
        "COPY" in normalizedTypeName -> HexElfRelocationSemantic.COPY_RELOCATION
        "PC32" in normalizedTypeName -> HexElfRelocationSemantic.PC_RELATIVE_ADDRESS
        "ABS" in normalizedTypeName ||
            normalizedTypeName.endsWith("_32") ||
            normalizedTypeName.endsWith("_64") -> HexElfRelocationSemantic.ABSOLUTE_ADDRESS
        else -> HexElfRelocationSemantic.OTHER
    }
}

