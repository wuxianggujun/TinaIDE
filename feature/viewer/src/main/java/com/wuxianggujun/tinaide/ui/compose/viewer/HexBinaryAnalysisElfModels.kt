package com.wuxianggujun.tinaide.ui.compose.viewer

/**
 * ELF analysis domain models.
 */

internal data class HexElfSummary(
    val is64Bit: Boolean,
    val endian: HexEndian,
    val type: Int,
    val machine: Int,
    val machineName: String,
    val entryPoint: Long,
    val programHeaderCount: Int,
    val sectionHeaderCount: Int,
    val sectionNames: List<String>,
    val sections: List<HexElfSection> = emptyList(),
    val noteEntries: List<HexElfNoteEntry> = emptyList(),
    val programHeaders: List<HexElfProgramHeader> = emptyList(),
    val loadSegments: List<HexElfLoadSegment> = emptyList(),
    val sectionSegmentMappings: List<HexElfSectionSegmentMapping> = emptyList(),
    val sectionEntropyEntries: List<HexElfSectionEntropyEntry> = emptyList(),
    val hardeningChecks: List<HexElfHardeningCheck> = emptyList(),
    val riskFindings: List<HexElfRiskFinding> = emptyList(),
    val dynamicSymbols: List<HexElfSymbol> = emptyList(),
    val dynamicStringEntries: List<HexElfDynamicStringEntry> = emptyList(),
    val dynamicFlagEntries: List<HexElfDynamicFlagEntry> = emptyList(),
    val initArrayEntries: List<HexElfInitArrayEntry> = emptyList(),
    val relocations: List<HexElfRelocationEntry> = emptyList(),
    val linkageEntries: List<HexElfLinkageEntry> = emptyList(),
    val dynamicLinkerSteps: List<HexElfDynamicLinkerStep> = emptyList(),
    val nativeApiHints: List<HexElfNativeApiHint> = emptyList(),
    val jniRegistrationHints: List<HexElfJniRegistrationHint> = emptyList()
) {
    val entryFileOffset: Long?
        get() = virtualAddressToFileOffset(entryPoint)

    val importedSymbols: List<HexElfSymbol>
        get() = dynamicSymbols.filter { it.isImported }

    val exportedSymbols: List<HexElfSymbol>
        get() = dynamicSymbols.filter { it.isExported }

    val jniSymbols: List<HexElfSymbol>
        get() = dynamicSymbols.filter { it.isJni }

    val neededLibraries: List<HexElfDynamicStringEntry>
        get() = dynamicStringEntries.filter { it.type == HexElfDynamicStringType.NEEDED }

    val soname: HexElfDynamicStringEntry?
        get() = dynamicStringEntries.firstOrNull { it.type == HexElfDynamicStringType.SONAME }

    val runtimeSearchPaths: List<HexElfDynamicStringEntry>
        get() = dynamicStringEntries.filter {
            it.type == HexElfDynamicStringType.RPATH || it.type == HexElfDynamicStringType.RUNPATH
        }

    val buildId: HexElfNoteEntry?
        get() = noteEntries.firstOrNull { it.isBuildId }

    val gnuPropertyNotes: List<HexElfNoteEntry>
        get() = noteEntries.filter { it.properties.isNotEmpty() }

    fun virtualAddressToFileOffset(virtualAddress: Long): Long? = loadSegments.firstNotNullOfOrNull { segment ->
        segment.virtualAddressToFileOffset(virtualAddress)
    }
}

internal data class HexElfNoteEntry(
    val index: Int,
    val sectionName: String,
    val name: String,
    val type: Long,
    val noteFileOffset: Long,
    val descriptionOffset: Long,
    val descriptionSize: Long,
    val descriptionHex: String,
    val descriptionText: String?,
    val isBuildId: Boolean,
    val properties: List<HexElfNotePropertyEntry> = emptyList()
)

internal data class HexElfNotePropertyEntry(
    val index: Int,
    val type: Long,
    val typeName: String,
    val value: Long,
    val valueHex: String,
    val propertyOffset: Long,
    val dataOffset: Long,
    val dataSize: Long,
    val features: List<HexElfNotePropertyFeature> = emptyList()
)

internal enum class HexElfNotePropertyFeature {
    X86_IBT,
    X86_SHSTK,
    AARCH64_BTI,
    AARCH64_PAC
}

internal data class HexElfProgramHeader(
    val index: Int,
    val type: Long,
    val typeName: String,
    val programHeaderFileOffset: Long,
    val fileOffset: Long,
    val virtualAddress: Long,
    val physicalAddress: Long,
    val fileSize: Long,
    val memorySize: Long,
    val flags: Int,
    val align: Long
) {
    val isLoad: Boolean
        get() = type == ELF_PROGRAM_TYPE_LOAD.toLong()

    val isExecutable: Boolean
        get() = flags.hasElfProgramFlag(ELF_PROGRAM_FLAG_EXECUTE)

    val isWritable: Boolean
        get() = flags.hasElfProgramFlag(ELF_PROGRAM_FLAG_WRITE)

    val isReadable: Boolean
        get() = flags.hasElfProgramFlag(ELF_PROGRAM_FLAG_READ)

    fun toLoadSegment(): HexElfLoadSegment? {
        if (!isLoad) return null
        return HexElfLoadSegment(
            fileOffset = fileOffset,
            virtualAddress = virtualAddress,
            fileSize = fileSize,
            memorySize = memorySize,
            flags = flags
        )
    }
}

internal data class HexElfHardeningCheck(
    val type: HexElfHardeningType,
    val enabled: Boolean,
    val evidenceFileOffset: Long?
)

internal enum class HexElfHardeningType {
    PIE,
    NX,
    RELRO,
    BIND_NOW,
    IBT,
    SHSTK,
    BTI,
    PAC
}

internal data class HexElfRiskFinding(
    val index: Int,
    val type: HexElfRiskFindingType,
    val severity: HexElfRiskSeverity,
    val evidenceFileOffset: Long?,
    val detailValue: String? = null
)

internal enum class HexElfRiskSeverity {
    INFO,
    WARNING,
    HIGH
}

internal enum class HexElfRiskFindingType {
    RWX_LOAD_SEGMENT,
    WRITABLE_EXECUTABLE_SECTION,
    EXECUTABLE_STACK,
    MISSING_RELRO,
    MISSING_BIND_NOW,
    LEGACY_RPATH,
    RUNPATH_PRESENT,
    MISSING_SONAME
}

internal data class HexElfDynamicStringEntry(
    val index: Int,
    val type: HexElfDynamicStringType,
    val value: String,
    val entryFileOffset: Long,
    val loadOrder: Int? = null,
    val semantic: HexElfDynamicStringSemantic = HexElfDynamicStringSemantic.UNKNOWN
)

internal enum class HexElfDynamicStringType {
    NEEDED,
    SONAME,
    RPATH,
    RUNPATH
}

internal enum class HexElfDynamicStringSemantic {
    NEEDED_LIBRARY_LOAD,
    SONAME_IDENTITY,
    LEGACY_RPATH_SEARCH,
    RUNPATH_SEARCH,
    UNKNOWN
}

internal data class HexElfDynamicFlagEntry(
    val index: Int,
    val type: HexElfDynamicFlagType,
    val value: Long,
    val entryFileOffset: Long,
    val isBindNow: Boolean
)

internal enum class HexElfDynamicFlagType {
    BIND_NOW,
    FLAGS,
    FLAGS_1
}

internal data class HexElfInitArrayEntry(
    val index: Int,
    val pointerFileOffset: Long,
    val functionAddress: Long,
    val functionFileOffset: Long?
)

internal data class HexElfRelocationEntry(
    val index: Int,
    val sectionName: String,
    val relocationFileOffset: Long,
    val offsetAddress: Long,
    val offsetFileOffset: Long?,
    val targetSectionName: String?,
    val symbolName: String?,
    val symbolBinding: HexElfSymbolBinding?,
    val symbolType: HexElfSymbolType?,
    val isSymbolImported: Boolean,
    val isSymbolExported: Boolean,
    val isSymbolJni: Boolean,
    val symbolIndex: Long,
    val type: Long,
    val typeName: String?,
    val semantic: HexElfRelocationSemantic = HexElfRelocationSemantic.OTHER,
    val addend: Long?
)

internal data class HexElfLinkageEntry(
    val index: Int,
    val symbolName: String?,
    val symbolIndex: Long,
    val relocationSectionName: String,
    val relocationTypeName: String?,
    val relocationFileOffset: Long,
    val slotAddress: Long,
    val slotFileOffset: Long?,
    val slotSectionName: String?,
    val symbolBinding: HexElfSymbolBinding?,
    val symbolType: HexElfSymbolType?,
    val isImported: Boolean,
    val isExported: Boolean,
    val isJni: Boolean,
    val entryKind: HexElfLinkageEntryKind,
    val bindingMode: HexElfLinkageBindingMode,
    val resolutionSemantic: HexElfLinkageResolutionSemantic = HexElfLinkageResolutionSemantic.LOCAL_RELOCATION,
    val pltStub: HexElfPltStub? = null
)

internal data class HexElfPltStub(
    val fileOffset: Long,
    val virtualAddress: Long,
    val byteCount: Int,
    val instructionBytes: String,
    val architecture: HexElfPltStubArchitecture,
    val semantic: HexElfPltStubSemantic,
    val slotFileOffset: Long?,
    val slotAddress: Long?
)

internal enum class HexElfPltStubArchitecture {
    AARCH64,
    X86_64
}

internal enum class HexElfPltStubSemantic {
    LOAD_GOT_SLOT_AND_BRANCH,
    UNKNOWN
}

internal enum class HexElfRelocationSemantic {
    JUMP_SLOT_BINDING,
    GLOB_DAT_ADDRESS,
    RELATIVE_REBASE,
    COPY_RELOCATION,
    ABSOLUTE_ADDRESS,
    PC_RELATIVE_ADDRESS,
    OTHER
}

internal data class HexElfDynamicLinkerStep(
    val index: Int,
    val type: HexElfDynamicLinkerStepType,
    val evidenceFileOffset: Long?,
    val relatedCount: Int,
    val detailValue: String?
)

internal enum class HexElfLinkageEntryKind {
    PLT,
    GOT,
    RELATIVE,
    OTHER
}

internal enum class HexElfLinkageBindingMode {
    NOW,
    LAZY,
    LOAD_TIME,
    LOCAL
}

internal enum class HexElfLinkageResolutionSemantic {
    EAGER_PLT_BINDING,
    LAZY_PLT_CALL,
    LOAD_TIME_GOT_WRITE,
    RELATIVE_REBASE,
    LOCAL_RELOCATION
}

internal enum class HexElfDynamicLinkerStepType {
    MAP_LOAD_SEGMENTS,
    LOAD_NEEDED_LIBRARIES,
    APPLY_RELOCATIONS,
    RESOLVE_NOW_BINDINGS,
    ENABLE_LAZY_PLT,
    PROTECT_RELRO,
    CALL_INIT_ARRAY,
    EXPOSE_JNI_ENTRYPOINTS
}

internal data class HexElfNativeApiHint(
    val index: Int,
    val category: HexElfNativeApiCategory,
    val symbolName: String,
    val evidenceFileOffset: Long?
)

internal enum class HexElfNativeApiCategory {
    DYNAMIC_LOADING,
    MEMORY_PROTECTION,
    PROCESS_CONTROL,
    FILE_IO,
    NETWORK,
    CRYPTO,
    THREADING,
    LOGGING
}

internal data class HexElfJniRegistrationHint(
    val index: Int,
    val type: HexElfJniRegistrationHintType,
    val evidenceFileOffset: Long?,
    val symbolName: String? = null,
    val stringValue: String? = null
)

internal enum class HexElfJniRegistrationHintType {
    REGISTER_NATIVES_SYMBOL,
    REGISTER_NATIVES_STRING,
    JNI_ONLOAD_ENTRY,
    JNI_ONUNLOAD_ENTRY,
    STATIC_JNI_EXPORT,
    JAVA_CLASS_DESCRIPTOR,
    JNI_METHOD_SIGNATURE
}

internal data class HexElfSection(
    val index: Int,
    val name: String,
    val type: Long,
    val flags: Long,
    val virtualAddress: Long,
    val fileOffset: Long,
    val size: Long,
    val link: Int,
    val entrySize: Long
)

internal data class HexElfLoadSegment(
    val fileOffset: Long,
    val virtualAddress: Long,
    val fileSize: Long,
    val memorySize: Long,
    val flags: Int
) {
    fun virtualAddressToFileOffset(address: Long): Long? {
        if (fileSize <= 0L || address < virtualAddress) return null
        val relativeOffset = address - virtualAddress
        if (relativeOffset !in 0 until fileSize) return null
        return fileOffset + relativeOffset
    }
}

internal data class HexElfSectionSegmentMapping(
    val index: Int,
    val sectionIndex: Int,
    val sectionName: String,
    val sectionFileOffset: Long,
    val sectionSize: Long,
    val sectionVirtualAddress: Long,
    val segmentIndex: Int,
    val segmentTypeName: String,
    val segmentFileOffset: Long,
    val segmentFileSize: Long,
    val segmentVirtualAddress: Long,
    val segmentMemorySize: Long,
    val segmentFlags: Int,
    val isExecutable: Boolean,
    val isWritable: Boolean,
    val isReadable: Boolean
)

internal data class HexElfSectionEntropyEntry(
    val index: Int,
    val sectionIndex: Int,
    val sectionName: String,
    val fileOffset: Long,
    val size: Long,
    val virtualAddress: Long,
    val sampleSize: Long,
    val entropy: Double,
    val level: HexEntropyLevel,
    val isAllocated: Boolean,
    val isExecutable: Boolean,
    val isWritable: Boolean
)

internal data class HexElfSymbol(
    val name: String,
    val value: Long,
    val fileOffset: Long?,
    val size: Long,
    val binding: HexElfSymbolBinding,
    val type: HexElfSymbolType,
    val sectionIndex: Int,
    val isImported: Boolean,
    val isExported: Boolean,
    val isJni: Boolean,
    val sectionName: String? = null,
    val sectionFileOffset: Long? = null,
    val sectionSize: Long? = null
)

internal enum class HexElfSymbolBinding {
    LOCAL,
    GLOBAL,
    WEAK,
    OTHER
}

internal enum class HexElfSymbolType {
    NOTYPE,
    OBJECT,
    FUNC,
    SECTION,
    FILE,
    TLS,
    OTHER
}

internal enum class ElfSectionFilter {
    ALL,
    ALLOCATED,
    EXECUTABLE,
    WRITABLE,
    STRING_TABLE,
    SYMBOL_TABLE
}

internal enum class ElfProgramHeaderFilter {
    ALL,
    LOAD,
    EXECUTABLE,
    WRITABLE,
    DYNAMIC,
    HARDENING
}

internal enum class ElfSectionSegmentFilter {
    ALL,
    EXECUTABLE,
    WRITABLE,
    READABLE
}

internal enum class ElfSymbolFilter {
    ALL,
    IMPORTED,
    EXPORTED,
    JNI
}

internal enum class ElfDynamicEntryFilter {
    ALL,
    NEEDED,
    SONAME,
    RPATH,
    RUNPATH
}

internal enum class ElfDynamicFlagFilter {
    ALL,
    BIND_NOW,
    FLAGS,
    FLAGS_1
}

internal enum class ElfNoteFilter {
    ALL,
    BUILD_ID,
    GNU,
    ANDROID
}

internal enum class ElfRelocationFilter {
    ALL,
    PLT,
    DYNAMIC
}

internal enum class ElfLinkageFilter {
    ALL,
    IMPORTS,
    PLT,
    GOT,
    JNI,
    NOW,
    LAZY
}

internal enum class ElfDynamicLinkerStepFilter {
    ALL,
    LOADING,
    RELOCATIONS,
    BINDING,
    HARDENING,
    ENTRYPOINTS
}

internal enum class ElfRiskFilter {
    ALL,
    HIGH,
    WARNING,
    HARDENING,
    SEGMENTS,
    PATHS,
    METADATA
}

internal enum class ElfJniHintFilter {
    ALL,
    REGISTER_NATIVES,
    ENTRYPOINTS,
    STATIC_EXPORTS,
    DESCRIPTORS
}

internal enum class ElfNativeApiFilter {
    ALL,
    DYNAMIC_LOADING,
    MEMORY,
    PROCESS,
    FILE,
    NETWORK,
    CRYPTO,
    THREADING,
    LOGGING
}

internal data class HexStringEntry(
    val offset: Long,
    val value: String,
    val encoding: HexStringEncoding = HexStringEncoding.ASCII
)

internal enum class HexStringEncoding {
    ASCII,
    UTF_8,
    UTF_16LE,
    UTF_16BE
}

internal enum class StringEntryEncodingFilter {
    ALL,
    ASCII,
    UTF_8,
    UTF_16LE,
    UTF_16BE
}

internal data class HexEntropyBucket(
    val startOffset: Long,
    val endOffset: Long,
    val entropy: Double
)

internal data class HexEntropyVisualBucket(
    val startOffset: Long,
    val endOffset: Long,
    val entropy: Double,
    val normalizedHeight: Float,
    val level: HexEntropyLevel
)

internal enum class HexEntropyLevel {
    LOW,
    MEDIUM,
    HIGH
}

internal enum class EntropyBucketFilter {
    ALL,
    LOW,
    MEDIUM,
    HIGH
}

internal data class HexObfuscationFinding(
    val type: HexObfuscationFindingType,
    val confidence: HexFindingConfidence,
    val evidence: String,
    val offset: Long? = null
)

internal enum class HexObfuscationFindingType {
    OLLVM_MARKER,
    CONTROL_FLOW_FLATTENING_MARKER,
    BOGUS_CONTROL_FLOW_MARKER,
    INSTRUCTION_SUBSTITUTION_MARKER,
    ANTI_DEBUG_HEURISTIC,
    ANTI_INSTRUMENTATION_HEURISTIC,
    PROTECTOR_PACKER_MARKER,
    STRING_OBFUSCATION_HEURISTIC,
    STRIPPED_SYMBOLS_HEURISTIC
}

internal enum class HexFindingConfidence {
    LOW,
    MEDIUM,
    HIGH
}

internal data class HexAnalysisSignal(
    val type: HexAnalysisSignalType,
    val offset: Long? = null
)

internal enum class HexAnalysisSignalType {
    HIGH_ENTROPY_REGION,
    ELF_PROGRAM_HEADERS,
    ELF_SECTION_SEGMENTS,
    ELF_SECTION_ENTROPY,
    ELF_HARDENING_WARNING,
    ELF_GNU_PROPERTY,
    ELF_INIT_ARRAY,
    ELF_DYNAMIC_SYMBOLS,
    ELF_DYNAMIC_DEPENDENCIES,
    ELF_NOTES,
    ELF_BUILD_ID,
    ELF_RELOCATIONS,
    ELF_LINKAGE,
    ELF_DYNAMIC_LINKER_STEPS,
    ELF_RISK_FINDINGS,
    ELF_NATIVE_API_HINTS,
    ELF_JNI_REGISTRATION_HINTS,
    ELF_JNI_SYMBOLS,
    ELF_RODATA,
    OBFUSCATION_RISK,
    DEX_FILE,
    DEX_HEADER,
    DEX_TYPE_IDS,
    DEX_PROTO_IDS,
    DEX_FIELD_IDS,
    DEX_METHOD_IDS,
    DEX_CLASS_DEFS,
    DEX_CLASS_DATA,
    DEX_NATIVE_METHODS,
    DEX_CODE_ITEMS,
    DEX_CALL_REFERENCES,
    DEX_STRING_REFERENCES,
    DEX_FIELD_REFERENCES,
    DEX_MAP_LIST,
    APK_FILE,
    APK_MANIFEST,
    APK_DEX_FILES,
    APK_EMBEDDED_DEX_SUMMARIES,
    APK_NATIVE_LIBRARIES,
    APK_ZIP_STRUCTURE,
    APK_SIGNING_BLOCK
}
