package com.wuxianggujun.tinaide.ui.compose.viewer

/**
 * Hex binary analysis domain models and filter enums.
 * Extracted from HexBinaryAnalysis.
 */

internal data class HexBinaryAnalysis(
    val fileKind: HexFileKind,
    val fileSize: Long,
    val fingerprint: HexFileFingerprint? = null,
    val byteFrequency: HexByteFrequencySummary? = null,
    val repeatedByteRuns: List<HexRepeatedByteRun> = emptyList(),
    val magicSignatures: List<HexMagicSignatureMatch> = emptyList(),
    val elf: HexElfSummary? = null,
    val dex: HexDexSummary? = null,
    val archive: HexArchiveSummary? = null,
    val strings: List<HexStringEntry> = emptyList(),
    val entropy: List<HexEntropyBucket> = emptyList(),
    val entropyVisualBuckets: List<HexEntropyVisualBucket> = emptyList(),
    val obfuscationFindings: List<HexObfuscationFinding> = emptyList(),
    val signals: List<HexAnalysisSignal> = emptyList()
)

internal data class HexFileFingerprint(
    val sha256: String,
    val sha1: String,
    val md5: String,
    val crc32: Long,
    val byteCount: Long
)

internal data class HexByteFrequencySummary(
    val totalBytes: Long,
    val uniqueByteValues: Int,
    val zeroBytes: Long,
    val ffBytes: Long,
    val printableAsciiBytes: Long,
    val controlBytes: Long,
    val topBytes: List<HexByteFrequencyEntry>
)

internal data class HexByteFrequencyEntry(
    val byteValue: Int,
    val count: Long,
    val ratio: Double
)

internal data class HexRepeatedByteRun(
    val byteValue: Int,
    val startOffset: Long,
    val length: Long
)

internal data class HexMagicSignatureMatch(
    val kind: HexMagicSignatureKind,
    val offset: Long,
    val signatureLength: Int
)

internal enum class HexMagicSignatureKind {
    ELF,
    DEX,
    ZIP_LOCAL_FILE,
    ZIP_CENTRAL_DIRECTORY,
    ZIP_EOCD,
    PNG,
    JPEG,
    ANDROID_RESOURCES,
    SQLITE
}

internal data class HexFileScanSummary(
    val fingerprint: HexFileFingerprint,
    val byteFrequency: HexByteFrequencySummary,
    val repeatedByteRuns: List<HexRepeatedByteRun>,
    val magicSignatures: List<HexMagicSignatureMatch>
)

internal data class HexMagicSignatureDefinition(
    val kind: HexMagicSignatureKind,
    val bytes: IntArray
)

internal enum class HexFileKind {
    ELF,
    DEX,
    APK,
    ZIP,
    PNG,
    JPEG,
    UNKNOWN
}

internal data class HexDexSummary(
    val version: String,
    val checksum: Long,
    val signatureHex: String,
    val fileSizeFromHeader: Long,
    val headerSize: Long,
    val endianTag: Long,
    val mapOffset: Long,
    val stringIdsSize: Int,
    val stringIdsOffset: Long,
    val typeIdsSize: Int,
    val typeIdsOffset: Long,
    val protoIdsSize: Int,
    val protoIdsOffset: Long,
    val fieldIdsSize: Int,
    val fieldIdsOffset: Long,
    val methodIdsSize: Int,
    val methodIdsOffset: Long,
    val classDefsSize: Int,
    val classDefsOffset: Long,
    val dataSize: Long,
    val dataOffset: Long,
    val stringEntries: List<HexDexStringEntry> = emptyList(),
    val typeEntries: List<HexDexTypeEntry> = emptyList(),
    val protoEntries: List<HexDexProtoEntry> = emptyList(),
    val fieldEntries: List<HexDexFieldEntry> = emptyList(),
    val methodEntries: List<HexDexMethodEntry> = emptyList(),
    val classDefEntries: List<HexDexClassDefEntry> = emptyList(),
    val classDataMethodEntries: List<HexDexClassDataMethodEntry> = emptyList(),
    val codeItemEntries: List<HexDexCodeItemEntry> = emptyList(),
    val callReferenceEntries: List<HexDexCallReferenceEntry> = emptyList(),
    val stringReferenceEntries: List<HexDexStringReferenceEntry> = emptyList(),
    val fieldReferenceEntries: List<HexDexFieldReferenceEntry> = emptyList(),
    val mapEntries: List<HexDexMapEntry> = emptyList()
) {
    val nativeMethodCount: Int
        get() = classDataMethodEntries.count { entry ->
            entry.executionKind == HexDexClassDataMethodExecutionKind.NATIVE
        }
}

internal data class HexDexStringEntry(
    val index: Int,
    val stringIdOffset: Long,
    val dataOffset: Long,
    val value: String
)

internal data class HexDexTypeEntry(
    val index: Int,
    val typeIdOffset: Long,
    val descriptorStringIndex: Long,
    val descriptor: String
)

internal data class HexDexProtoEntry(
    val index: Int,
    val protoIdOffset: Long,
    val shortyStringIndex: Long,
    val shorty: String,
    val returnTypeIndex: Long,
    val returnTypeDescriptor: String,
    val parametersOffset: Long,
    val parameterTypeDescriptors: List<String>,
    val signature: String
)

internal data class HexDexFieldEntry(
    val index: Int,
    val fieldIdOffset: Long,
    val classIndex: Int,
    val classDescriptor: String,
    val typeIndex: Int,
    val typeDescriptor: String,
    val nameStringIndex: Long,
    val name: String
)

internal data class HexDexMethodEntry(
    val index: Int,
    val methodIdOffset: Long,
    val classIndex: Int,
    val classDescriptor: String,
    val protoIndex: Int,
    val protoShorty: String,
    val protoSignature: String,
    val nameStringIndex: Long,
    val name: String
)

internal data class HexDexClassDefEntry(
    val index: Int,
    val classDefOffset: Long,
    val classIndex: Long,
    val classDescriptor: String,
    val accessFlags: Long,
    val superclassIndex: Long?,
    val superclassDescriptor: String?,
    val interfacesOffset: Long,
    val sourceFileIndex: Long?,
    val sourceFile: String?,
    val annotationsOffset: Long,
    val classDataOffset: Long,
    val staticValuesOffset: Long
)

internal data class HexDexClassDataMethodEntry(
    val index: Int,
    val classDefIndex: Int,
    val classDescriptor: String,
    val kind: HexDexClassDataMethodKind,
    val methodIndex: Long,
    val methodName: String,
    val methodClassDescriptor: String,
    val protoSignature: String,
    val accessFlags: Long,
    val classDataOffset: Long,
    val entryOffset: Long,
    val codeOffset: Long,
    val executionKind: HexDexClassDataMethodExecutionKind = dexClassDataMethodExecutionKind(
        accessFlags = accessFlags,
        codeOffset = codeOffset
    )
)

internal enum class HexDexClassDataMethodKind {
    DIRECT,
    VIRTUAL
}

internal enum class HexDexClassDataMethodExecutionKind {
    CODE,
    NATIVE,
    ABSTRACT,
    NO_CODE
}

internal data class HexDexCodeItemEntry(
    val index: Int,
    val methodIndex: Long,
    val methodName: String,
    val methodClassDescriptor: String,
    val protoSignature: String,
    val codeOffset: Long,
    val registersSize: Int,
    val insSize: Int,
    val outsSize: Int,
    val triesSize: Int,
    val debugInfoOffset: Long,
    val insnsSize: Long,
    val firstOpcode: Int,
    val firstOpcodeName: String,
    val previewCodeUnitsHex: String
)

internal data class HexDexCallReferenceEntry(
    val index: Int,
    val callerMethodIndex: Long,
    val callerClassDescriptor: String,
    val callerMethodName: String,
    val callerProtoSignature: String,
    val targetMethodIndex: Long,
    val targetClassDescriptor: String,
    val targetMethodName: String,
    val targetProtoSignature: String,
    val opcode: Int,
    val opcodeName: String,
    val instructionOffset: Long,
    val codeOffset: Long,
    val targetMethodIdOffset: Long?
)

internal data class HexDexStringReferenceEntry(
    val index: Int,
    val callerMethodIndex: Long,
    val callerClassDescriptor: String,
    val callerMethodName: String,
    val callerProtoSignature: String,
    val stringIndex: Long,
    val value: String,
    val opcode: Int,
    val opcodeName: String,
    val instructionOffset: Long,
    val codeOffset: Long,
    val stringIdOffset: Long?,
    val stringDataOffset: Long?
)

internal data class HexDexFieldReferenceEntry(
    val index: Int,
    val callerMethodIndex: Long,
    val callerClassDescriptor: String,
    val callerMethodName: String,
    val callerProtoSignature: String,
    val fieldIndex: Long,
    val fieldClassDescriptor: String,
    val fieldName: String,
    val fieldTypeDescriptor: String,
    val opcode: Int,
    val opcodeName: String,
    val instructionOffset: Long,
    val codeOffset: Long,
    val fieldIdOffset: Long?
)

internal data class HexDexMapEntry(
    val index: Int,
    val type: Int,
    val typeName: String,
    val size: Long,
    val offset: Long,
    val entryFileOffset: Long
)

internal enum class DexMapEntryFilter {
    ALL,
    IDS,
    CLASS_DATA,
    CODE,
    DATA
}

internal data class HexArchiveSummary(
    val entries: List<HexArchiveEntry>,
    val embeddedDexFiles: List<HexArchiveDexSummary> = emptyList(),
    val nativeLibrarySummaries: List<HexArchiveNativeLibrarySummary> = emptyList(),
    val signingBlockEntries: List<HexArchiveSigningBlockEntry> = emptyList(),
    val manifestSummary: HexArchiveManifestSummary? = null,
    val resourcesSummary: HexArchiveResourcesSummary? = null,
    val zipStructure: HexArchiveZipStructure? = null
) {
    val dexFiles: List<HexArchiveEntry>
        get() = entries.filter { entry -> entry.name.endsWith(".dex", ignoreCase = true) }

    val nativeLibraries: List<HexArchiveEntry>
        get() = entries.filter { entry ->
            entry.name.startsWith("lib/", ignoreCase = true) && entry.name.endsWith(".so", ignoreCase = true)
        }

    val manifest: HexArchiveEntry?
        get() = entries.firstOrNull { entry -> entry.name.equals("AndroidManifest.xml", ignoreCase = true) }

    val resources: List<HexArchiveEntry>
        get() = entries.filter { entry ->
            entry.name.equals("resources.arsc", ignoreCase = true) || entry.name.startsWith("res/", ignoreCase = true)
        }

    val signatureFiles: List<HexArchiveEntry>
        get() = entries.filter { entry -> entry.name.startsWith("META-INF/", ignoreCase = true) }
}

internal data class HexArchiveZipStructure(
    val eocdOffset: Long,
    val centralDirectoryOffset: Long,
    val centralDirectorySize: Long,
    val entryCount: Int,
    val commentLength: Int,
    val zip64LocatorOffset: Long? = null
)

internal data class HexArchiveEntry(
    val index: Int,
    val name: String,
    val generalPurposeBitFlag: Int,
    val compressionMethod: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val centralDirectoryOffset: Long,
    val dataOffset: Long? = null,
    val dataEndOffset: Long? = null,
    val dataRangeStatus: HexArchiveEntryDataRangeStatus = HexArchiveEntryDataRangeStatus.UNKNOWN,
    val localHeaderName: String? = null,
    val localHeaderGeneralPurposeBitFlag: Int? = null,
    val localHeaderCompressionMethod: Int? = null,
    val localHeaderConsistency: HexArchiveEntryLocalHeaderConsistency = HexArchiveEntryLocalHeaderConsistency.UNKNOWN,
    val nameRisks: Set<HexArchiveEntryNameRisk> = emptySet()
) {
    val usesDataDescriptor: Boolean
        get() = (generalPurposeBitFlag and ZIP_GENERAL_PURPOSE_DATA_DESCRIPTOR_FLAG) != 0
}

internal enum class HexArchiveEntryDataRangeStatus {
    OK,
    UNKNOWN,
    OUT_OF_FILE,
    OVERLAPS_CENTRAL_DIRECTORY
}

internal enum class HexArchiveEntryLocalHeaderConsistency {
    OK,
    UNKNOWN,
    NAME_MISMATCH,
    METADATA_MISMATCH,
    MULTIPLE_MISMATCHES
}

internal enum class HexArchiveEntryNameRisk {
    EMPTY_NAME,
    DUPLICATE_NAME,
    ABSOLUTE_PATH,
    WINDOWS_DRIVE_PATH,
    PATH_TRAVERSAL,
    BACKSLASH_SEPARATOR
}

internal data class ZipEntryLocalHeader(
    val name: String,
    val generalPurposeBitFlag: Int,
    val compressionMethod: Int,
    val dataOffset: Long
)

internal data class HexArchiveNativeLibrarySummary(
    val entryName: String,
    val abi: String,
    val fileName: String,
    val localHeaderOffset: Long,
    val dataOffset: Long?,
    val compressionMethod: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val analyzedBytes: Long,
    val truncated: Boolean,
    val isElf: Boolean,
    val is64Bit: Boolean? = null,
    val endian: HexEndian? = null,
    val machineName: String? = null,
    val loadMode: HexArchiveNativeLoadMode = HexArchiveNativeLoadMode.UNKNOWN,
    val pageAlignmentRemainder: Long? = null,
    val obfuscationMarkers: List<HexArchiveNativeObfuscationMarker> = emptyList()
)

internal enum class HexArchiveNativeLoadMode {
    DIRECT_MMAP_READY,
    STORED_UNALIGNED,
    NEEDS_DECOMPRESSION,
    UNKNOWN
}

internal enum class ArchiveNativeLibraryLoadModeFilter {
    ALL,
    DIRECT_MMAP_READY,
    STORED_UNALIGNED,
    NEEDS_DECOMPRESSION,
    UNKNOWN
}

internal data class HexArchiveNativeObfuscationMarker(
    val type: HexObfuscationFindingType,
    val evidence: String,
    val relativeOffset: Long?
)

internal data class HexArchiveManifestSummary(
    val entryName: String,
    val localHeaderOffset: Long,
    val analyzedBytes: Long,
    val truncated: Boolean,
    val stringCount: Int,
    val elementCount: Int,
    val rootElementName: String?,
    val packageName: String?,
    val permissions: List<String>
)

internal data class HexArchiveResourcesSummary(
    val entryName: String,
    val localHeaderOffset: Long,
    val analyzedBytes: Long,
    val truncated: Boolean,
    val packageCountFromHeader: Int,
    val globalStringCount: Int,
    val typeSpecCount: Int,
    val typeChunkCount: Int,
    val packages: List<HexArchiveResourcePackage>
)

internal data class HexArchiveResourcePackage(
    val id: Int,
    val name: String,
    val typeStringCount: Int,
    val keyStringCount: Int,
    val typeSpecCount: Int,
    val typeChunkCount: Int
)

internal data class HexArchiveDexSummary(
    val entryName: String,
    val localHeaderOffset: Long,
    val analyzedBytes: Long,
    val truncated: Boolean,
    val dex: HexDexSummary
)

internal data class HexArchiveSigningBlockEntry(
    val index: Int,
    val id: Long,
    val idName: String,
    val valueSize: Long,
    val blockOffset: Long,
    val blockSize: Long,
    val pairOffset: Long,
    val valueOffset: Long
)

internal enum class ArchiveEntryFilter {
    ALL,
    DEX,
    NATIVE_LIBRARIES,
    MANIFEST,
    RESOURCES,
    SIGNATURE
}

internal enum class HexEndian {
    LITTLE,
    BIG
}

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

