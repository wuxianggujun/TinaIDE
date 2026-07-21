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
 * Query/match helpers used by hex analysis dialogs.
 */

internal fun ElfSectionFilter.matches(section: HexElfSection): Boolean = when (this) {
    ElfSectionFilter.ALL -> true
    ElfSectionFilter.ALLOCATED -> section.flags.hasElfFlag(ELF_SECTION_FLAG_ALLOC)
    ElfSectionFilter.EXECUTABLE -> section.flags.hasElfFlag(ELF_SECTION_FLAG_EXECINSTR)
    ElfSectionFilter.WRITABLE -> section.flags.hasElfFlag(ELF_SECTION_FLAG_WRITE)
    ElfSectionFilter.STRING_TABLE -> section.type == ELF_SECTION_TYPE_STRING_TABLE.toLong()
    ElfSectionFilter.SYMBOL_TABLE -> section.type == ELF_SECTION_TYPE_SYMBOL_TABLE.toLong() ||
        section.type == ELF_SECTION_TYPE_DYNAMIC_SYMBOLS.toLong()
}

internal fun HexElfSection.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return name.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        type.toString().contains(query) ||
        flags.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        fileOffset.matchesQuery(query, normalizedHexQuery) ||
        virtualAddress.matchesQuery(query, normalizedHexQuery) ||
        size.matchesQuery(query, normalizedHexQuery)
}

internal fun ElfProgramHeaderFilter.matches(programHeader: HexElfProgramHeader): Boolean = when (this) {
    ElfProgramHeaderFilter.ALL -> true
    ElfProgramHeaderFilter.LOAD -> programHeader.isLoad
    ElfProgramHeaderFilter.EXECUTABLE -> programHeader.isExecutable
    ElfProgramHeaderFilter.WRITABLE -> programHeader.isWritable
    ElfProgramHeaderFilter.DYNAMIC -> programHeader.type == ELF_PROGRAM_TYPE_DYNAMIC
    ElfProgramHeaderFilter.HARDENING ->
        programHeader.type == ELF_PROGRAM_TYPE_GNU_STACK ||
            programHeader.type == ELF_PROGRAM_TYPE_GNU_RELRO
}

internal fun HexElfProgramHeader.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return typeName.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        type.toString().contains(query) ||
        programHeaderFileOffset.matchesQuery(query, normalizedHexQuery) ||
        fileOffset.matchesQuery(query, normalizedHexQuery) ||
        virtualAddress.matchesQuery(query, normalizedHexQuery) ||
        physicalAddress.matchesQuery(query, normalizedHexQuery) ||
        fileSize.matchesQuery(query, normalizedHexQuery) ||
        memorySize.matchesQuery(query, normalizedHexQuery) ||
        align.matchesQuery(query, normalizedHexQuery) ||
        programFlagsQueryName().contains(query, ignoreCase = true)
}

internal fun HexElfProgramHeader.programFlagsQueryName(): String = buildString {
    if (flags.hasElfProgramFlag(ELF_PROGRAM_FLAG_READ)) append('R')
    if (flags.hasElfProgramFlag(ELF_PROGRAM_FLAG_WRITE)) append('W')
    if (flags.hasElfProgramFlag(ELF_PROGRAM_FLAG_EXECUTE)) append('X')
}

internal fun ElfSectionSegmentFilter.matches(mapping: HexElfSectionSegmentMapping): Boolean = when (this) {
    ElfSectionSegmentFilter.ALL -> true
    ElfSectionSegmentFilter.EXECUTABLE -> mapping.isExecutable
    ElfSectionSegmentFilter.WRITABLE -> mapping.isWritable
    ElfSectionSegmentFilter.READABLE -> mapping.isReadable
}

internal fun HexElfSectionSegmentMapping.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return sectionName.contains(query, ignoreCase = true) ||
        segmentTypeName.contains(query, ignoreCase = true) ||
        sectionIndex.toString().contains(query) ||
        segmentIndex.toString().contains(query) ||
        sectionFileOffset.matchesQuery(query, normalizedHexQuery) ||
        sectionVirtualAddress.matchesQuery(query, normalizedHexQuery) ||
        sectionSize.matchesQuery(query, normalizedHexQuery) ||
        segmentFileOffset.matchesQuery(query, normalizedHexQuery) ||
        segmentVirtualAddress.matchesQuery(query, normalizedHexQuery) ||
        segmentFileSize.matchesQuery(query, normalizedHexQuery) ||
        segmentMemorySize.matchesQuery(query, normalizedHexQuery) ||
        segmentFlags.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        segmentFlagsQueryName().contains(query, ignoreCase = true)
}

internal fun HexElfSectionSegmentMapping.segmentFlagsQueryName(): String = buildString {
    if (isReadable) append('R')
    if (isWritable) append('W')
    if (isExecutable) append('X')
}

internal fun HexElfSectionEntropyEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    val entropyLabel = "%.2f".format(entropy)
    return sectionName.contains(query, ignoreCase = true) ||
        level.name.contains(query, ignoreCase = true) ||
        entropyLabel.contains(query) ||
        sectionFlagsQueryName().contains(query, ignoreCase = true) ||
        sectionIndex.toString().contains(query) ||
        fileOffset.matchesQuery(query, normalizedHexQuery) ||
        virtualAddress.matchesQuery(query, normalizedHexQuery) ||
        size.matchesQuery(query, normalizedHexQuery) ||
        sampleSize.matchesQuery(query, normalizedHexQuery)
}

internal fun HexElfSectionEntropyEntry.sectionFlagsQueryName(): String = buildString {
    if (isAllocated) append('A')
    if (isWritable) append('W')
    if (isExecutable) append('X')
}

internal fun ElfSymbolFilter.matches(symbol: HexElfSymbol): Boolean = when (this) {
    ElfSymbolFilter.ALL -> true
    ElfSymbolFilter.IMPORTED -> symbol.isImported
    ElfSymbolFilter.EXPORTED -> symbol.isExported
    ElfSymbolFilter.JNI -> symbol.isJni
}

internal fun ElfDynamicEntryFilter.matches(entry: HexElfDynamicStringEntry): Boolean = when (this) {
    ElfDynamicEntryFilter.ALL -> true
    ElfDynamicEntryFilter.NEEDED -> entry.type == HexElfDynamicStringType.NEEDED
    ElfDynamicEntryFilter.SONAME -> entry.type == HexElfDynamicStringType.SONAME
    ElfDynamicEntryFilter.RPATH -> entry.type == HexElfDynamicStringType.RPATH
    ElfDynamicEntryFilter.RUNPATH -> entry.type == HexElfDynamicStringType.RUNPATH
}

internal fun ElfDynamicFlagFilter.matches(entry: HexElfDynamicFlagEntry): Boolean = when (this) {
    ElfDynamicFlagFilter.ALL -> true
    ElfDynamicFlagFilter.BIND_NOW -> entry.isBindNow
    ElfDynamicFlagFilter.FLAGS -> entry.type == HexElfDynamicFlagType.FLAGS
    ElfDynamicFlagFilter.FLAGS_1 -> entry.type == HexElfDynamicFlagType.FLAGS_1
}

internal fun ElfNoteFilter.matches(note: HexElfNoteEntry): Boolean = when (this) {
    ElfNoteFilter.ALL -> true
    ElfNoteFilter.BUILD_ID -> note.isBuildId
    ElfNoteFilter.GNU -> note.name.equals("GNU", ignoreCase = true)
    ElfNoteFilter.ANDROID -> note.name.equals("Android", ignoreCase = true) ||
        note.sectionName.contains("android", ignoreCase = true)
}

internal fun ElfRelocationFilter.matches(relocation: HexElfRelocationEntry): Boolean = when (this) {
    ElfRelocationFilter.ALL -> true
    ElfRelocationFilter.PLT -> relocation.sectionName.contains(".plt", ignoreCase = true)
    ElfRelocationFilter.DYNAMIC -> !relocation.sectionName.contains(".plt", ignoreCase = true)
}

internal fun ElfLinkageFilter.matches(entry: HexElfLinkageEntry): Boolean = when (this) {
    ElfLinkageFilter.ALL -> true
    ElfLinkageFilter.IMPORTS -> entry.isImported
    ElfLinkageFilter.PLT -> entry.entryKind == HexElfLinkageEntryKind.PLT
    ElfLinkageFilter.GOT ->
        entry.entryKind == HexElfLinkageEntryKind.GOT ||
            entry.slotSectionName?.contains("got", ignoreCase = true) == true
    ElfLinkageFilter.JNI -> entry.isJni
    ElfLinkageFilter.NOW ->
        entry.bindingMode == HexElfLinkageBindingMode.NOW ||
            entry.bindingMode == HexElfLinkageBindingMode.LOAD_TIME
    ElfLinkageFilter.LAZY -> entry.bindingMode == HexElfLinkageBindingMode.LAZY
}

internal fun ElfDynamicLinkerStepFilter.matches(step: HexElfDynamicLinkerStep): Boolean = when (this) {
    ElfDynamicLinkerStepFilter.ALL -> true
    ElfDynamicLinkerStepFilter.LOADING ->
        step.type == HexElfDynamicLinkerStepType.MAP_LOAD_SEGMENTS ||
            step.type == HexElfDynamicLinkerStepType.LOAD_NEEDED_LIBRARIES
    ElfDynamicLinkerStepFilter.RELOCATIONS -> step.type == HexElfDynamicLinkerStepType.APPLY_RELOCATIONS
    ElfDynamicLinkerStepFilter.BINDING ->
        step.type == HexElfDynamicLinkerStepType.RESOLVE_NOW_BINDINGS ||
            step.type == HexElfDynamicLinkerStepType.ENABLE_LAZY_PLT
    ElfDynamicLinkerStepFilter.HARDENING -> step.type == HexElfDynamicLinkerStepType.PROTECT_RELRO
    ElfDynamicLinkerStepFilter.ENTRYPOINTS ->
        step.type == HexElfDynamicLinkerStepType.CALL_INIT_ARRAY ||
            step.type == HexElfDynamicLinkerStepType.EXPOSE_JNI_ENTRYPOINTS
}

internal fun ElfRiskFilter.matches(finding: HexElfRiskFinding): Boolean = when (this) {
    ElfRiskFilter.ALL -> true
    ElfRiskFilter.HIGH -> finding.severity == HexElfRiskSeverity.HIGH
    ElfRiskFilter.WARNING -> finding.severity == HexElfRiskSeverity.WARNING
    ElfRiskFilter.HARDENING ->
        finding.type == HexElfRiskFindingType.EXECUTABLE_STACK ||
            finding.type == HexElfRiskFindingType.MISSING_RELRO ||
            finding.type == HexElfRiskFindingType.MISSING_BIND_NOW
    ElfRiskFilter.SEGMENTS ->
        finding.type == HexElfRiskFindingType.RWX_LOAD_SEGMENT ||
            finding.type == HexElfRiskFindingType.WRITABLE_EXECUTABLE_SECTION
    ElfRiskFilter.PATHS ->
        finding.type == HexElfRiskFindingType.LEGACY_RPATH ||
            finding.type == HexElfRiskFindingType.RUNPATH_PRESENT
    ElfRiskFilter.METADATA -> finding.type == HexElfRiskFindingType.MISSING_SONAME
}

internal fun ElfJniHintFilter.matches(hint: HexElfJniRegistrationHint): Boolean = when (this) {
    ElfJniHintFilter.ALL -> true
    ElfJniHintFilter.REGISTER_NATIVES ->
        hint.type == HexElfJniRegistrationHintType.REGISTER_NATIVES_SYMBOL ||
            hint.type == HexElfJniRegistrationHintType.REGISTER_NATIVES_STRING
    ElfJniHintFilter.ENTRYPOINTS ->
        hint.type == HexElfJniRegistrationHintType.JNI_ONLOAD_ENTRY ||
            hint.type == HexElfJniRegistrationHintType.JNI_ONUNLOAD_ENTRY
    ElfJniHintFilter.STATIC_EXPORTS -> hint.type == HexElfJniRegistrationHintType.STATIC_JNI_EXPORT
    ElfJniHintFilter.DESCRIPTORS ->
        hint.type == HexElfJniRegistrationHintType.JAVA_CLASS_DESCRIPTOR ||
            hint.type == HexElfJniRegistrationHintType.JNI_METHOD_SIGNATURE
}

internal fun ElfNativeApiFilter.matches(hint: HexElfNativeApiHint): Boolean = when (this) {
    ElfNativeApiFilter.ALL -> true
    ElfNativeApiFilter.DYNAMIC_LOADING -> hint.category == HexElfNativeApiCategory.DYNAMIC_LOADING
    ElfNativeApiFilter.MEMORY -> hint.category == HexElfNativeApiCategory.MEMORY_PROTECTION
    ElfNativeApiFilter.PROCESS -> hint.category == HexElfNativeApiCategory.PROCESS_CONTROL
    ElfNativeApiFilter.FILE -> hint.category == HexElfNativeApiCategory.FILE_IO
    ElfNativeApiFilter.NETWORK -> hint.category == HexElfNativeApiCategory.NETWORK
    ElfNativeApiFilter.CRYPTO -> hint.category == HexElfNativeApiCategory.CRYPTO
    ElfNativeApiFilter.THREADING -> hint.category == HexElfNativeApiCategory.THREADING
    ElfNativeApiFilter.LOGGING -> hint.category == HexElfNativeApiCategory.LOGGING
}

internal fun EntropyBucketFilter.matches(level: HexEntropyLevel): Boolean = when (this) {
    EntropyBucketFilter.ALL -> true
    EntropyBucketFilter.LOW -> level == HexEntropyLevel.LOW
    EntropyBucketFilter.MEDIUM -> level == HexEntropyLevel.MEDIUM
    EntropyBucketFilter.HIGH -> level == HexEntropyLevel.HIGH
}

internal fun HexElfDynamicStringEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return value.contains(query, ignoreCase = true) ||
        type.name.contains(query, ignoreCase = true) ||
        semantic.name.contains(query, ignoreCase = true) ||
        semantic.queryName().contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        loadOrder?.toString()?.contains(query) == true ||
        entryFileOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexElfDynamicStringSemantic.queryName(): String = when (this) {
    HexElfDynamicStringSemantic.NEEDED_LIBRARY_LOAD ->
        "needed dependency declaration order load order direct library"
    HexElfDynamicStringSemantic.SONAME_IDENTITY ->
        "soname shared object identity"
    HexElfDynamicStringSemantic.LEGACY_RPATH_SEARCH ->
        "rpath legacy dependency search transitive search path"
    HexElfDynamicStringSemantic.RUNPATH_SEARCH ->
        "runpath dependency search path direct dependency"
    HexElfDynamicStringSemantic.UNKNOWN ->
        "unknown dynamic string"
}

internal fun HexElfDynamicFlagEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return type.name.contains(query, ignoreCase = true) ||
        dynamicFlagQueryName().contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        value.matchesQuery(query, normalizedHexQuery) ||
        entryFileOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexElfDynamicFlagEntry.dynamicFlagQueryName(): String = if (isBindNow) "BIND_NOW NOW" else ""

internal fun HexElfNoteEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return sectionName.contains(query, ignoreCase = true) ||
        name.contains(query, ignoreCase = true) ||
        noteRoleQueryName().contains(query, ignoreCase = true) ||
        descriptionHex.contains(normalizedHexQuery, ignoreCase = true) ||
        descriptionText?.contains(query, ignoreCase = true) == true ||
        properties.any { property -> property.matchesQuery(query) } ||
        index.toString().contains(query) ||
        type.toString().contains(query) ||
        noteFileOffset.matchesQuery(query, normalizedHexQuery) ||
        descriptionOffset.matchesQuery(query, normalizedHexQuery) ||
        descriptionSize.matchesQuery(query, normalizedHexQuery)
}

internal fun HexElfNoteEntry.noteRoleQueryName(): String = buildString {
    if (isBuildId) append("build-id build id ")
    if (name.equals("GNU", ignoreCase = true)) append("gnu ")
    if (properties.isNotEmpty()) append("gnu property cet ")
    if (name.equals("Android", ignoreCase = true) || sectionName.contains("android", ignoreCase = true)) {
        append("android ")
    }
}

internal fun HexElfNotePropertyEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return typeName.contains(query, ignoreCase = true) ||
        propertyFeatureQueryName().contains(query, ignoreCase = true) ||
        features.any { feature -> feature.queryName().contains(query, ignoreCase = true) } ||
        index.toString().contains(query) ||
        type.toString().contains(query) ||
        type.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        value.toString().contains(query) ||
        valueHex.contains(normalizedHexQuery, ignoreCase = true) ||
        propertyOffset.matchesQuery(query, normalizedHexQuery) ||
        dataOffset.matchesQuery(query, normalizedHexQuery) ||
        dataSize.matchesQuery(query, normalizedHexQuery)
}

internal fun HexElfNotePropertyEntry.propertyFeatureQueryName(): String = features.joinToString(" ") { feature ->
    feature.queryName()
}

internal fun HexElfNotePropertyFeature.queryName(): String = when (this) {
    HexElfNotePropertyFeature.X86_IBT -> "ibt indirect branch tracking branch target"
    HexElfNotePropertyFeature.X86_SHSTK -> "shstk shadow stack"
    HexElfNotePropertyFeature.AARCH64_BTI -> "bti branch target"
    HexElfNotePropertyFeature.AARCH64_PAC -> "pac pointer authentication"
}

internal fun HexElfRelocationEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return sectionName.contains(query, ignoreCase = true) ||
        targetSectionName?.contains(query, ignoreCase = true) == true ||
        symbolName?.contains(query, ignoreCase = true) == true ||
        symbolBinding?.name?.contains(query, ignoreCase = true) == true ||
        symbolType?.name?.contains(query, ignoreCase = true) == true ||
        symbolRoleQueryName().contains(query, ignoreCase = true) ||
        typeName?.contains(query, ignoreCase = true) == true ||
        semantic.name.contains(query, ignoreCase = true) ||
        semantic.queryName().contains(query, ignoreCase = true) ||
        symbolIndex.toString().contains(query) ||
        type.toString().contains(query) ||
        relocationFileOffset.matchesQuery(query, normalizedHexQuery) ||
        offsetAddress.matchesQuery(query, normalizedHexQuery) ||
        offsetFileOffset?.matchesQuery(query, normalizedHexQuery) == true ||
        addend?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexElfRelocationSemantic.queryName(): String = when (this) {
    HexElfRelocationSemantic.JUMP_SLOT_BINDING ->
        "jump slot plt call binding resolver"
    HexElfRelocationSemantic.GLOB_DAT_ADDRESS ->
        "glob dat got symbol address load time write"
    HexElfRelocationSemantic.RELATIVE_REBASE ->
        "relative rebase load bias local address"
    HexElfRelocationSemantic.COPY_RELOCATION ->
        "copy relocation executable data copy"
    HexElfRelocationSemantic.ABSOLUTE_ADDRESS ->
        "absolute symbol address fixup"
    HexElfRelocationSemantic.PC_RELATIVE_ADDRESS ->
        "pc relative address fixup"
    HexElfRelocationSemantic.OTHER ->
        "other relocation"
}

internal fun HexElfRelocationEntry.symbolRoleQueryName(): String = when {
    isSymbolJni -> "jni"
    isSymbolImported -> "imported"
    isSymbolExported -> "exported"
    symbolBinding != null -> "local"
    else -> ""
}

internal fun HexElfLinkageEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return symbolName?.contains(query, ignoreCase = true) == true ||
        relocationSectionName.contains(query, ignoreCase = true) ||
        relocationTypeName?.contains(query, ignoreCase = true) == true ||
        slotSectionName?.contains(query, ignoreCase = true) == true ||
        entryKind.name.contains(query, ignoreCase = true) ||
        bindingMode.name.contains(query, ignoreCase = true) ||
        resolutionSemantic.name.contains(query, ignoreCase = true) ||
        resolutionSemantic.queryName().contains(query, ignoreCase = true) ||
        symbolBinding?.name?.contains(query, ignoreCase = true) == true ||
        symbolType?.name?.contains(query, ignoreCase = true) == true ||
        symbolRoleQueryName().contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        symbolIndex.toString().contains(query) ||
        relocationFileOffset.matchesQuery(query, normalizedHexQuery) ||
        slotAddress.matchesQuery(query, normalizedHexQuery) ||
        slotFileOffset?.matchesQuery(query, normalizedHexQuery) == true ||
        pltStub?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexElfPltStub.matchesQuery(
    query: String,
    normalizedHexQuery: String
): Boolean = architecture.name.contains(query, ignoreCase = true) ||
    semantic.name.contains(query, ignoreCase = true) ||
    pltStubQueryName().contains(query, ignoreCase = true) ||
    instructionBytes.contains(query, ignoreCase = true) ||
    fileOffset.matchesQuery(query, normalizedHexQuery) ||
    virtualAddress.matchesQuery(query, normalizedHexQuery) ||
    slotFileOffset?.matchesQuery(query, normalizedHexQuery) == true ||
    slotAddress?.matchesQuery(query, normalizedHexQuery) == true

internal fun HexElfPltStub.pltStubQueryName(): String = when {
    semantic == HexElfPltStubSemantic.LOAD_GOT_SLOT_AND_BRANCH -> "load got slot branch plt stub jmp push"
    else -> "stub plt"
}

internal fun HexElfLinkageResolutionSemantic.queryName(): String = when (this) {
    HexElfLinkageResolutionSemantic.EAGER_PLT_BINDING ->
        "bind_now now eager plt got startup import resolver"
    HexElfLinkageResolutionSemantic.LAZY_PLT_CALL ->
        "lazy plt first call resolver got patch"
    HexElfLinkageResolutionSemantic.LOAD_TIME_GOT_WRITE ->
        "load time got write import resolve"
    HexElfLinkageResolutionSemantic.RELATIVE_REBASE ->
        "relative rebase load bias local address"
    HexElfLinkageResolutionSemantic.LOCAL_RELOCATION ->
        "local relocation fixup"
}

internal fun HexElfLinkageEntry.symbolRoleQueryName(): String = when {
    isJni -> "jni"
    isImported -> "imported import"
    isExported -> "exported export"
    symbolBinding != null -> "local"
    else -> ""
}

internal fun HexElfDynamicLinkerStep.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return type.name.contains(query, ignoreCase = true) ||
        dynamicLinkerStepQueryName().contains(query, ignoreCase = true) ||
        detailValue?.contains(query, ignoreCase = true) == true ||
        index.toString().contains(query) ||
        relatedCount.toString().contains(query) ||
        evidenceFileOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexElfDynamicLinkerStep.dynamicLinkerStepQueryName(): String = when (type) {
    HexElfDynamicLinkerStepType.MAP_LOAD_SEGMENTS -> "map load segment loading"
    HexElfDynamicLinkerStepType.LOAD_NEEDED_LIBRARIES -> "needed library dependency loading"
    HexElfDynamicLinkerStepType.APPLY_RELOCATIONS -> "relocation apply"
    HexElfDynamicLinkerStepType.RESOLVE_NOW_BINDINGS -> "bind_now now resolve import"
    HexElfDynamicLinkerStepType.ENABLE_LAZY_PLT -> "lazy plt"
    HexElfDynamicLinkerStepType.PROTECT_RELRO -> "relro hardening"
    HexElfDynamicLinkerStepType.CALL_INIT_ARRAY -> "init_array constructor"
    HexElfDynamicLinkerStepType.EXPOSE_JNI_ENTRYPOINTS -> "jni entrypoint"
}

internal fun HexElfRiskFinding.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return type.name.contains(query, ignoreCase = true) ||
        severity.name.contains(query, ignoreCase = true) ||
        riskFindingQueryName().contains(query, ignoreCase = true) ||
        detailValue?.contains(query, ignoreCase = true) == true ||
        index.toString().contains(query) ||
        evidenceFileOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexElfRiskFinding.riskFindingQueryName(): String = when (type) {
    HexElfRiskFindingType.RWX_LOAD_SEGMENT -> "rwx load segment writable executable"
    HexElfRiskFindingType.WRITABLE_EXECUTABLE_SECTION -> "wx section writable executable"
    HexElfRiskFindingType.EXECUTABLE_STACK -> "nx stack gnu_stack executable"
    HexElfRiskFindingType.MISSING_RELRO -> "relro gnu_relro hardening missing"
    HexElfRiskFindingType.MISSING_BIND_NOW -> "bind_now now hardening missing"
    HexElfRiskFindingType.LEGACY_RPATH -> "rpath legacy search path"
    HexElfRiskFindingType.RUNPATH_PRESENT -> "runpath search path"
    HexElfRiskFindingType.MISSING_SONAME -> "soname metadata missing"
}

internal fun HexElfJniRegistrationHint.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return type.name.contains(query, ignoreCase = true) ||
        jniHintQueryName().contains(query, ignoreCase = true) ||
        symbolName?.contains(query, ignoreCase = true) == true ||
        stringValue?.contains(query, ignoreCase = true) == true ||
        index.toString().contains(query) ||
        evidenceFileOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexElfJniRegistrationHint.jniHintQueryName(): String = when (type) {
    HexElfJniRegistrationHintType.REGISTER_NATIVES_SYMBOL -> "register natives symbol dynamic"
    HexElfJniRegistrationHintType.REGISTER_NATIVES_STRING -> "register natives string dynamic registration"
    HexElfJniRegistrationHintType.JNI_ONLOAD_ENTRY -> "jni onload entrypoint"
    HexElfJniRegistrationHintType.JNI_ONUNLOAD_ENTRY -> "jni onunload entrypoint"
    HexElfJniRegistrationHintType.STATIC_JNI_EXPORT -> "static jni export java"
    HexElfJniRegistrationHintType.JAVA_CLASS_DESCRIPTOR -> "java class descriptor"
    HexElfJniRegistrationHintType.JNI_METHOD_SIGNATURE -> "jni method signature descriptor"
}

internal fun HexElfNativeApiHint.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return symbolName.contains(query, ignoreCase = true) ||
        category.name.contains(query, ignoreCase = true) ||
        nativeApiQueryName().contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        evidenceFileOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexElfNativeApiHint.nativeApiQueryName(): String = when (category) {
    HexElfNativeApiCategory.DYNAMIC_LOADING -> "dynamic loading dlopen dlsym loader"
    HexElfNativeApiCategory.MEMORY_PROTECTION -> "memory protection mmap mprotect executable"
    HexElfNativeApiCategory.PROCESS_CONTROL -> "process control anti debug ptrace prctl syscall"
    HexElfNativeApiCategory.FILE_IO -> "file io filesystem read write open"
    HexElfNativeApiCategory.NETWORK -> "network socket connect send recv"
    HexElfNativeApiCategory.CRYPTO -> "crypto openssl ssl aes rsa sha md5"
    HexElfNativeApiCategory.THREADING -> "threading pthread thread mutex"
    HexElfNativeApiCategory.LOGGING -> "logging log print printf"
}

internal fun DexMapEntryFilter.matches(entry: HexDexMapEntry): Boolean = when (this) {
    DexMapEntryFilter.ALL -> true
    DexMapEntryFilter.IDS -> entry.type in DEX_MAP_ID_TYPES
    DexMapEntryFilter.CLASS_DATA -> entry.type == DEX_MAP_TYPE_CLASS_DATA_ITEM
    DexMapEntryFilter.CODE -> entry.type == DEX_MAP_TYPE_CODE_ITEM
    DexMapEntryFilter.DATA ->
        entry.type !in DEX_MAP_ID_TYPES &&
            entry.type != DEX_MAP_TYPE_CLASS_DATA_ITEM &&
            entry.type != DEX_MAP_TYPE_CODE_ITEM
}

internal fun HexDexStringEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return value.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        stringIdOffset.matchesQuery(query, normalizedHexQuery) ||
        dataOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexTypeEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return descriptor.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        descriptorStringIndex.toString().contains(query) ||
        typeIdOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexProtoEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return shorty.contains(query, ignoreCase = true) ||
        returnTypeDescriptor.contains(query, ignoreCase = true) ||
        signature.contains(query, ignoreCase = true) ||
        parameterTypeDescriptors.any { descriptor -> descriptor.contains(query, ignoreCase = true) } ||
        index.toString().contains(query) ||
        shortyStringIndex.toString().contains(query) ||
        returnTypeIndex.toString().contains(query) ||
        protoIdOffset.matchesQuery(query, normalizedHexQuery) ||
        parametersOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexFieldEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return name.contains(query, ignoreCase = true) ||
        classDescriptor.contains(query, ignoreCase = true) ||
        typeDescriptor.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        classIndex.toString().contains(query) ||
        typeIndex.toString().contains(query) ||
        nameStringIndex.toString().contains(query) ||
        fieldIdOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexMethodEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return name.contains(query, ignoreCase = true) ||
        classDescriptor.contains(query, ignoreCase = true) ||
        protoShorty.contains(query, ignoreCase = true) ||
        protoSignature.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        classIndex.toString().contains(query) ||
        protoIndex.toString().contains(query) ||
        nameStringIndex.toString().contains(query) ||
        methodIdOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexClassDefEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return classDescriptor.contains(query, ignoreCase = true) ||
        superclassDescriptor?.contains(query, ignoreCase = true) == true ||
        sourceFile?.contains(query, ignoreCase = true) == true ||
        index.toString().contains(query) ||
        classIndex.toString().contains(query) ||
        accessFlags.toString().contains(query) ||
        accessFlags.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        classDefOffset.matchesQuery(query, normalizedHexQuery) ||
        classDataOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexClassDataMethodEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return classDescriptor.contains(query, ignoreCase = true) ||
        methodClassDescriptor.contains(query, ignoreCase = true) ||
        methodName.contains(query, ignoreCase = true) ||
        protoSignature.contains(query, ignoreCase = true) ||
        kind.name.contains(query, ignoreCase = true) ||
        executionKind.dexClassDataMethodExecutionQueryName().contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        classDefIndex.toString().contains(query) ||
        methodIndex.toString().contains(query) ||
        accessFlags.toString().contains(query) ||
        accessFlags.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        classDataOffset.matchesQuery(query, normalizedHexQuery) ||
        entryOffset.matchesQuery(query, normalizedHexQuery) ||
        codeOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexClassDataMethodExecutionKind.dexClassDataMethodExecutionQueryName(): String = when (this) {
    HexDexClassDataMethodExecutionKind.CODE -> "code method has code code item bytecode"
    HexDexClassDataMethodExecutionKind.NATIVE -> "native method jni no code acc_native"
    HexDexClassDataMethodExecutionKind.ABSTRACT -> "abstract method no code acc_abstract"
    HexDexClassDataMethodExecutionKind.NO_CODE -> "no code method missing code offset"
}

internal fun HexDexCodeItemEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return methodClassDescriptor.contains(query, ignoreCase = true) ||
        methodName.contains(query, ignoreCase = true) ||
        protoSignature.contains(query, ignoreCase = true) ||
        firstOpcodeName.contains(query, ignoreCase = true) ||
        previewCodeUnitsHex.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        methodIndex.toString().contains(query) ||
        registersSize.toString().contains(query) ||
        insSize.toString().contains(query) ||
        outsSize.toString().contains(query) ||
        triesSize.toString().contains(query) ||
        debugInfoOffset.matchesQuery(query, normalizedHexQuery) ||
        insnsSize.toString().contains(query) ||
        firstOpcode.toString().contains(query) ||
        firstOpcode.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        codeOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexDexCallReferenceEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return callerClassDescriptor.contains(query, ignoreCase = true) ||
        callerMethodName.contains(query, ignoreCase = true) ||
        callerProtoSignature.contains(query, ignoreCase = true) ||
        targetClassDescriptor.contains(query, ignoreCase = true) ||
        targetMethodName.contains(query, ignoreCase = true) ||
        targetProtoSignature.contains(query, ignoreCase = true) ||
        opcodeName.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        callerMethodIndex.toString().contains(query) ||
        targetMethodIndex.toString().contains(query) ||
        opcode.toString().contains(query) ||
        opcode.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        instructionOffset.matchesQuery(query, normalizedHexQuery) ||
        codeOffset.matchesQuery(query, normalizedHexQuery) ||
        targetMethodIdOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexDexStringReferenceEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return callerClassDescriptor.contains(query, ignoreCase = true) ||
        callerMethodName.contains(query, ignoreCase = true) ||
        callerProtoSignature.contains(query, ignoreCase = true) ||
        value.contains(query, ignoreCase = true) ||
        opcodeName.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        callerMethodIndex.toString().contains(query) ||
        stringIndex.toString().contains(query) ||
        opcode.toString().contains(query) ||
        opcode.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        instructionOffset.matchesQuery(query, normalizedHexQuery) ||
        codeOffset.matchesQuery(query, normalizedHexQuery) ||
        stringIdOffset?.matchesQuery(query, normalizedHexQuery) == true ||
        stringDataOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexDexFieldReferenceEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return callerClassDescriptor.contains(query, ignoreCase = true) ||
        callerMethodName.contains(query, ignoreCase = true) ||
        callerProtoSignature.contains(query, ignoreCase = true) ||
        fieldClassDescriptor.contains(query, ignoreCase = true) ||
        fieldName.contains(query, ignoreCase = true) ||
        fieldTypeDescriptor.contains(query, ignoreCase = true) ||
        opcodeName.contains(query, ignoreCase = true) ||
        index.toString().contains(query) ||
        callerMethodIndex.toString().contains(query) ||
        fieldIndex.toString().contains(query) ||
        opcode.toString().contains(query) ||
        opcode.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        instructionOffset.matchesQuery(query, normalizedHexQuery) ||
        codeOffset.matchesQuery(query, normalizedHexQuery) ||
        fieldIdOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexDexMapEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return typeName.contains(query, ignoreCase = true) ||
        type.toString().contains(query) ||
        type.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        size.toString().contains(query) ||
        offset.matchesQuery(query, normalizedHexQuery) ||
        entryFileOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun ArchiveEntryFilter.matches(entry: HexArchiveEntry): Boolean = when (this) {
    ArchiveEntryFilter.ALL -> true
    ArchiveEntryFilter.DEX -> entry.name.endsWith(".dex", ignoreCase = true)
    ArchiveEntryFilter.NATIVE_LIBRARIES -> entry.name.startsWith("lib/", ignoreCase = true) &&
        entry.name.endsWith(".so", ignoreCase = true)
    ArchiveEntryFilter.MANIFEST -> entry.name.equals("AndroidManifest.xml", ignoreCase = true)
    ArchiveEntryFilter.RESOURCES -> entry.name.equals("resources.arsc", ignoreCase = true) ||
        entry.name.startsWith("res/", ignoreCase = true)
    ArchiveEntryFilter.SIGNATURE -> entry.name.startsWith("META-INF/", ignoreCase = true)
}

internal fun ArchiveNativeLibraryLoadModeFilter.matches(entry: HexArchiveNativeLibrarySummary): Boolean = when (this) {
    ArchiveNativeLibraryLoadModeFilter.ALL -> true
    ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY -> entry.loadMode == HexArchiveNativeLoadMode.DIRECT_MMAP_READY
    ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED -> entry.loadMode == HexArchiveNativeLoadMode.STORED_UNALIGNED
    ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION -> entry.loadMode == HexArchiveNativeLoadMode.NEEDS_DECOMPRESSION
    ArchiveNativeLibraryLoadModeFilter.UNKNOWN -> entry.loadMode == HexArchiveNativeLoadMode.UNKNOWN
}

internal fun HexArchiveEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return name.contains(query, ignoreCase = true) ||
        localHeaderName?.contains(query, ignoreCase = true) == true ||
        archiveEntryCompressionQueryName(compressionMethod).contains(query, ignoreCase = true) ||
        archiveEntryNativeLoadModeQueryName(this).contains(query, ignoreCase = true) ||
        dataRangeStatus.archiveEntryDataRangeStatusQueryName().contains(query, ignoreCase = true) ||
        localHeaderConsistency.archiveEntryLocalHeaderConsistencyQueryName().contains(query, ignoreCase = true) ||
        nameRisks.archiveEntryNameRiskQueryName().contains(query, ignoreCase = true) ||
        generalPurposeBitFlag.toString().contains(query) ||
        localHeaderGeneralPurposeBitFlag?.toString()?.contains(query) == true ||
        compressionMethod.toString().contains(query) ||
        localHeaderCompressionMethod?.toString()?.contains(query) == true ||
        crc32.toString().contains(query) ||
        crc32.matchesQuery(query, normalizedHexQuery) ||
        compressedSize.toString().contains(query) ||
        uncompressedSize.toString().contains(query) ||
        localHeaderOffset.matchesQuery(query, normalizedHexQuery) ||
        centralDirectoryOffset.matchesQuery(query, normalizedHexQuery) ||
        dataOffset?.matchesQuery(query, normalizedHexQuery) == true ||
        dataEndOffset?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun HexArchiveNativeLibrarySummary.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return entryName.contains(query, ignoreCase = true) ||
        abi.contains(query, ignoreCase = true) ||
        fileName.contains(query, ignoreCase = true) ||
        machineName?.contains(query, ignoreCase = true) == true ||
        archiveEntryCompressionQueryName(compressionMethod).contains(query, ignoreCase = true) ||
        loadMode.archiveNativeLoadModeQueryName().contains(query, ignoreCase = true) ||
        localHeaderOffset.matchesQuery(query, normalizedHexQuery) ||
        dataOffset?.matchesQuery(query, normalizedHexQuery) == true ||
        pageAlignmentRemainder?.toString()?.contains(query) == true ||
        crc32.matchesQuery(query, normalizedHexQuery) ||
        compressedSize.toString().contains(query) ||
        uncompressedSize.toString().contains(query) ||
        obfuscationMarkers.any { marker ->
            marker.evidence.contains(query, ignoreCase = true) ||
                marker.type.name.contains(query, ignoreCase = true) ||
                marker.relativeOffset?.matchesQuery(query, normalizedHexQuery) == true
        }
}

internal fun archiveEntryCompressionQueryName(compressionMethod: Int): String = when (compressionMethod) {
    ZIP_COMPRESSION_METHOD_STORED -> "stored uncompressed no compression method 0"
    ZIP_COMPRESSION_METHOD_DEFLATED -> "deflated compressed zip compression method 8"
    else -> "compressed zip compression method $compressionMethod"
}

internal fun archiveEntryNativeLoadModeQueryName(entry: HexArchiveEntry): String {
    if (!entry.name.startsWith("lib/", ignoreCase = true) || !entry.name.endsWith(".so", ignoreCase = true)) {
        return ""
    }
    return archiveNativeLoadMode(
        compressionMethod = entry.compressionMethod,
        dataOffset = entry.dataOffset
    ).archiveNativeLoadModeQueryName()
}

internal fun HexArchiveNativeLoadMode.archiveNativeLoadModeQueryName(): String = when (this) {
    HexArchiveNativeLoadMode.DIRECT_MMAP_READY -> "direct mmap ready stored uncompressed page aligned 4096"
    HexArchiveNativeLoadMode.STORED_UNALIGNED -> "stored uncompressed page unaligned needs extraction"
    HexArchiveNativeLoadMode.NEEDS_DECOMPRESSION -> "compressed deflated needs decompression extraction"
    HexArchiveNativeLoadMode.UNKNOWN -> "unknown native load mode"
}

internal fun HexArchiveEntryDataRangeStatus.archiveEntryDataRangeStatusQueryName(): String = when (this) {
    HexArchiveEntryDataRangeStatus.OK -> "valid data range ok"
    HexArchiveEntryDataRangeStatus.UNKNOWN -> "unknown data range"
    HexArchiveEntryDataRangeStatus.OUT_OF_FILE -> "out of file truncated invalid data range"
    HexArchiveEntryDataRangeStatus.OVERLAPS_CENTRAL_DIRECTORY -> "overlaps central directory invalid data range"
}

internal fun HexArchiveEntryLocalHeaderConsistency.archiveEntryLocalHeaderConsistencyQueryName(): String = when (this) {
    HexArchiveEntryLocalHeaderConsistency.OK -> "local header consistent ok matches central directory"
    HexArchiveEntryLocalHeaderConsistency.UNKNOWN -> "local header unknown unreadable missing"
    HexArchiveEntryLocalHeaderConsistency.NAME_MISMATCH ->
        "local mismatch local header name mismatch differs central directory"
    HexArchiveEntryLocalHeaderConsistency.METADATA_MISMATCH ->
        "local mismatch local header method flags mismatch differs central directory"
    HexArchiveEntryLocalHeaderConsistency.MULTIPLE_MISMATCHES ->
        "local mismatch local header multiple mismatches name method flags central directory"
}

internal fun Set<HexArchiveEntryNameRisk>.archiveEntryNameRiskQueryName(): String {
    if (isEmpty()) return "entry name ok safe"
    return joinToString(separator = " ") { risk -> risk.archiveEntryNameRiskQueryName() }
}

internal fun HexArchiveEntryNameRisk.archiveEntryNameRiskQueryName(): String = when (this) {
    HexArchiveEntryNameRisk.EMPTY_NAME -> "name risk empty entry name"
    HexArchiveEntryNameRisk.DUPLICATE_NAME -> "name risk duplicate entry duplicate name"
    HexArchiveEntryNameRisk.ABSOLUTE_PATH -> "name risk absolute path rooted path"
    HexArchiveEntryNameRisk.WINDOWS_DRIVE_PATH -> "name risk windows drive path absolute path"
    HexArchiveEntryNameRisk.PATH_TRAVERSAL -> "name risk path traversal dot dot parent directory zip slip"
    HexArchiveEntryNameRisk.BACKSLASH_SEPARATOR -> "name risk backslash separator windows separator"
}

internal fun HexArchiveDexSummary.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return entryName.contains(query, ignoreCase = true) ||
        dex.version.contains(query, ignoreCase = true) ||
        dex.stringIdsSize.toString().contains(query) ||
        dex.protoIdsSize.toString().contains(query) ||
        dex.fieldIdsSize.toString().contains(query) ||
        dex.methodIdsSize.toString().contains(query) ||
        dex.classDefsSize.toString().contains(query) ||
        localHeaderOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexArchiveSigningBlockEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return idName.contains(query, ignoreCase = true) ||
        id.toString().contains(query) ||
        id.toString(16).contains(normalizedHexQuery, ignoreCase = true) ||
        valueSize.toString().contains(query) ||
        blockOffset.matchesQuery(query, normalizedHexQuery) ||
        blockSize.toString().contains(query) ||
        pairOffset.matchesQuery(query, normalizedHexQuery) ||
        valueOffset.matchesQuery(query, normalizedHexQuery)
}

internal fun HexElfSymbol.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return name.contains(query, ignoreCase = true) ||
        sectionName?.contains(query, ignoreCase = true) == true ||
        value.matchesQuery(query, normalizedHexQuery) ||
        fileOffset?.matchesQuery(query, normalizedHexQuery) == true ||
        sectionFileOffset?.matchesQuery(query, normalizedHexQuery) == true ||
        sectionSize?.matchesQuery(query, normalizedHexQuery) == true
}

internal fun StringEntryEncodingFilter.matches(encoding: HexStringEncoding): Boolean = when (this) {
    StringEntryEncodingFilter.ALL -> true
    StringEntryEncodingFilter.ASCII -> encoding == HexStringEncoding.ASCII
    StringEntryEncodingFilter.UTF_8 -> encoding == HexStringEncoding.UTF_8
    StringEntryEncodingFilter.UTF_16LE -> encoding == HexStringEncoding.UTF_16LE
    StringEntryEncodingFilter.UTF_16BE -> encoding == HexStringEncoding.UTF_16BE
}

internal fun HexStringEntry.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val normalizedHexQuery = query.removePrefix("0x").removePrefix("0X")
    return value.contains(query, ignoreCase = true) ||
        offset.toString().contains(query) ||
        offset.toString(16).contains(normalizedHexQuery, ignoreCase = true)
}

internal val HexStringEncoding.exportLabel: String
    get() = when (this) {
        HexStringEncoding.ASCII -> "ASCII"
        HexStringEncoding.UTF_8 -> "UTF-8"
        HexStringEncoding.UTF_16LE -> "UTF-16LE"
        HexStringEncoding.UTF_16BE -> "UTF-16BE"
    }

internal fun String.escapeForTabSeparatedExport(): String = replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace("\r", "\\r")
    .replace("\n", "\\n")

internal fun String.isLikelyJavaClassDescriptor(): Boolean {
    if (length !in 3..256 || any { it.isWhitespace() }) return false
    val className = if (startsWith("L") && endsWith(";")) {
        substring(1, length - 1)
    } else {
        this
    }
    if ('/' !in className || className.startsWith("/") || className.endsWith("/")) return false
    return className.split('/').all { part ->
        part.isNotBlank() &&
            part.all { char ->
                char.isLetterOrDigit() || char == '_' || char == '$'
            }
    }
}

internal fun String.isLikelyJniMethodSignature(): Boolean {
    if (length !in 4..256 || !startsWith("(")) return false
    val closeIndex = indexOf(')')
    if (closeIndex <= 0 || closeIndex == lastIndex) return false
    return all { char ->
        char.isLetterOrDigit() ||
            char == '(' ||
            char == ')' ||
            char == '[' ||
            char == '/' ||
            char == ';' ||
            char == '$' ||
            char == '_'
    }
}

internal fun dexMapTypeName(type: Int): String = when (type) {
    DEX_MAP_TYPE_HEADER_ITEM -> "header_item"
    DEX_MAP_TYPE_STRING_ID_ITEM -> "string_id_item"
    DEX_MAP_TYPE_TYPE_ID_ITEM -> "type_id_item"
    DEX_MAP_TYPE_PROTO_ID_ITEM -> "proto_id_item"
    DEX_MAP_TYPE_FIELD_ID_ITEM -> "field_id_item"
    DEX_MAP_TYPE_METHOD_ID_ITEM -> "method_id_item"
    DEX_MAP_TYPE_CLASS_DEF_ITEM -> "class_def_item"
    DEX_MAP_TYPE_MAP_LIST -> "map_list"
    DEX_MAP_TYPE_TYPE_LIST -> "type_list"
    DEX_MAP_TYPE_ANNOTATION_SET_REF_LIST -> "annotation_set_ref_list"
    DEX_MAP_TYPE_ANNOTATION_SET_ITEM -> "annotation_set_item"
    DEX_MAP_TYPE_CLASS_DATA_ITEM -> "class_data_item"
    DEX_MAP_TYPE_CODE_ITEM -> "code_item"
    DEX_MAP_TYPE_STRING_DATA_ITEM -> "string_data_item"
    DEX_MAP_TYPE_DEBUG_INFO_ITEM -> "debug_info_item"
    DEX_MAP_TYPE_ANNOTATION_ITEM -> "annotation_item"
    DEX_MAP_TYPE_ENCODED_ARRAY_ITEM -> "encoded_array_item"
    DEX_MAP_TYPE_ANNOTATIONS_DIRECTORY_ITEM -> "annotations_directory_item"
    else -> "type_0x%04X".format(type)
}

internal fun dexOpcodeName(opcode: Int): String = when (opcode) {
    0x00 -> "nop"
    0x01 -> "move"
    0x02 -> "move/from16"
    0x03 -> "move/16"
    0x04 -> "move-wide"
    0x05 -> "move-wide/from16"
    0x06 -> "move-wide/16"
    0x07 -> "move-object"
    0x08 -> "move-object/from16"
    0x09 -> "move-object/16"
    0x0A -> "move-result"
    0x0B -> "move-result-wide"
    0x0C -> "move-result-object"
    0x0D -> "move-exception"
    0x0E -> "return-void"
    0x0F -> "return"
    0x10 -> "return-wide"
    0x11 -> "return-object"
    0x12 -> "const/4"
    0x13 -> "const/16"
    0x14 -> "const"
    0x15 -> "const/high16"
    0x16 -> "const-wide/16"
    0x17 -> "const-wide/32"
    0x18 -> "const-wide"
    0x19 -> "const-wide/high16"
    0x1A -> "const-string"
    0x1B -> "const-string/jumbo"
    0x1C -> "const-class"
    0x1D -> "monitor-enter"
    0x1E -> "monitor-exit"
    0x1F -> "check-cast"
    0x20 -> "instance-of"
    0x21 -> "array-length"
    0x22 -> "new-instance"
    0x23 -> "new-array"
    0x24 -> "filled-new-array"
    0x25 -> "filled-new-array/range"
    0x26 -> "fill-array-data"
    0x27 -> "throw"
    0x28 -> "goto"
    0x29 -> "goto/16"
    0x2A -> "goto/32"
    0x2B -> "packed-switch"
    0x2C -> "sparse-switch"
    0x2D -> "cmpl-float"
    0x2E -> "cmpg-float"
    0x2F -> "cmpl-double"
    0x30 -> "cmpg-double"
    0x31 -> "cmp-long"
    in 0x32..0x3D -> "if-test"
    in 0x44..0x51 -> "arrayop"
    in 0x52..0x5F -> "instanceop"
    in 0x60..0x6D -> "staticop"
    in 0x6E..0x72 -> "invoke"
    in 0x74..0x78 -> "invoke/range"
    in 0x7B..0x8F -> "unop"
    in 0x90..0xAF -> "binop"
    in 0xB0..0xCF -> "binop/2addr"
    in 0xD0..0xD7 -> "binop/lit16"
    in 0xD8..0xE2 -> "binop/lit8"
    0xFA -> "invoke-polymorphic"
    0xFB -> "invoke-polymorphic/range"
    0xFC -> "invoke-custom"
    0xFD -> "invoke-custom/range"
    0xFE -> "const-method-handle"
    0xFF -> "const-method-type"
    else -> "opcode_0x%02X".format(opcode)
}

internal fun Long.hasElfFlag(flag: Long): Boolean = (this and flag) != 0L

internal fun Int.hasElfProgramFlag(flag: Int): Boolean = (this and flag) != 0

internal fun Long.matchesQuery(query: String, normalizedHexQuery: String): Boolean = toString().contains(query) || toString(16).contains(normalizedHexQuery, ignoreCase = true)

internal fun Long.floorMod(divisor: Long): Long {
    val remainder = this % divisor
    return if (remainder >= 0L) remainder else remainder + divisor
}

internal fun Long.coerceToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

internal fun Long.dexOptionalIndex(): Long? = takeUnless { value -> value == DEX_NO_INDEX }

internal fun dexIndexFallback(index: Long): String = "#$index"

internal fun apkSigningBlockIdName(id: Long): String = when (id) {
    APK_SIGNATURE_SCHEME_V2_BLOCK_ID -> "APK Signature Scheme v2"
    APK_SIGNATURE_SCHEME_V3_BLOCK_ID -> "APK Signature Scheme v3"
    APK_SIGNATURE_VERITY_PADDING_BLOCK_ID -> "APK verity padding"
    else -> "id_0x%08X".format(id)
}

