package com.wuxianggujun.tinaide.ui.compose.viewer

/**
 * Filter helpers for hex analysis panel dialogs.
 */

internal fun filterStringEntries(
    entries: List<HexStringEntry>,
    query: String,
    encodingFilter: StringEntryEncodingFilter = StringEntryEncodingFilter.ALL,
    limit: Int = MAX_STRING_RESULTS
): List<HexStringEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> encodingFilter.matches(entry.encoding) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .sortedWith(compareBy<HexStringEntry> { it.offset }.thenBy { it.encoding.ordinal }.thenBy { it.value })
        .take(limit)
        .toList()
}

internal fun formatStringEntriesExport(entries: List<HexStringEntry>): String = entries.joinToString(separator = "\n") { entry ->
    "0x%08X\t%s\t%s".format(
        entry.offset,
        entry.encoding.exportLabel,
        entry.value.escapeForTabSeparatedExport()
    )
}

internal fun filterElfSections(
    sections: List<HexElfSection>,
    query: String,
    sectionFilter: ElfSectionFilter = ElfSectionFilter.ALL,
    limit: Int = MAX_ELF_SECTION_HEADERS
): List<HexElfSection> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return sections.asSequence()
        .filter { section -> sectionFilter.matches(section) }
        .filter { section -> section.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfProgramHeaders(
    programHeaders: List<HexElfProgramHeader>,
    query: String,
    programHeaderFilter: ElfProgramHeaderFilter = ElfProgramHeaderFilter.ALL,
    limit: Int = MAX_ELF_PROGRAM_HEADERS
): List<HexElfProgramHeader> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return programHeaders.asSequence()
        .filter { programHeader -> programHeaderFilter.matches(programHeader) }
        .filter { programHeader -> programHeader.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfSectionSegmentMappings(
    mappings: List<HexElfSectionSegmentMapping>,
    query: String,
    sectionSegmentFilter: ElfSectionSegmentFilter = ElfSectionSegmentFilter.ALL,
    limit: Int = MAX_ELF_SECTION_SEGMENT_MAPPINGS
): List<HexElfSectionSegmentMapping> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return mappings.asSequence()
        .filter { mapping -> sectionSegmentFilter.matches(mapping) }
        .filter { mapping -> mapping.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfSectionEntropyEntries(
    entries: List<HexElfSectionEntropyEntry>,
    query: String,
    entropyFilter: EntropyBucketFilter = EntropyBucketFilter.ALL,
    limit: Int = MAX_ELF_SECTION_ENTROPY_ENTRIES
): List<HexElfSectionEntropyEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entropyFilter.matches(entry.level) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfSymbols(
    symbols: List<HexElfSymbol>,
    query: String,
    symbolFilter: ElfSymbolFilter = ElfSymbolFilter.ALL,
    limit: Int = MAX_ELF_SYMBOLS
): List<HexElfSymbol> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return symbols.asSequence()
        .filter { symbol -> symbolFilter.matches(symbol) }
        .filter { symbol -> symbol.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfDynamicEntries(
    entries: List<HexElfDynamicStringEntry>,
    query: String,
    dynamicEntryFilter: ElfDynamicEntryFilter = ElfDynamicEntryFilter.ALL,
    limit: Int = MAX_ELF_DYNAMIC_ENTRIES
): List<HexElfDynamicStringEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> dynamicEntryFilter.matches(entry) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfDynamicFlags(
    entries: List<HexElfDynamicFlagEntry>,
    query: String,
    dynamicFlagFilter: ElfDynamicFlagFilter = ElfDynamicFlagFilter.ALL,
    limit: Int = MAX_ELF_DYNAMIC_ENTRIES
): List<HexElfDynamicFlagEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> dynamicFlagFilter.matches(entry) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfNotes(
    notes: List<HexElfNoteEntry>,
    query: String,
    noteFilter: ElfNoteFilter = ElfNoteFilter.ALL,
    limit: Int = MAX_ELF_NOTES
): List<HexElfNoteEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return notes.asSequence()
        .filter { note -> noteFilter.matches(note) }
        .filter { note -> note.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfRelocations(
    relocations: List<HexElfRelocationEntry>,
    query: String,
    relocationFilter: ElfRelocationFilter = ElfRelocationFilter.ALL,
    limit: Int = MAX_ELF_RELOCATIONS
): List<HexElfRelocationEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return relocations.asSequence()
        .filter { relocation -> relocationFilter.matches(relocation) }
        .filter { relocation -> relocation.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfLinkageEntries(
    entries: List<HexElfLinkageEntry>,
    query: String,
    linkageFilter: ElfLinkageFilter = ElfLinkageFilter.ALL,
    limit: Int = MAX_ELF_LINKAGE_ENTRIES
): List<HexElfLinkageEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> linkageFilter.matches(entry) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfDynamicLinkerSteps(
    steps: List<HexElfDynamicLinkerStep>,
    query: String,
    stepFilter: ElfDynamicLinkerStepFilter = ElfDynamicLinkerStepFilter.ALL,
    limit: Int = MAX_ELF_DYNAMIC_LINKER_STEPS
): List<HexElfDynamicLinkerStep> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return steps.asSequence()
        .filter { step -> stepFilter.matches(step) }
        .filter { step -> step.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfRiskFindings(
    findings: List<HexElfRiskFinding>,
    query: String,
    riskFilter: ElfRiskFilter = ElfRiskFilter.ALL,
    limit: Int = MAX_ELF_RISK_FINDINGS
): List<HexElfRiskFinding> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return findings.asSequence()
        .filter { finding -> riskFilter.matches(finding) }
        .filter { finding -> finding.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfJniRegistrationHints(
    hints: List<HexElfJniRegistrationHint>,
    query: String,
    hintFilter: ElfJniHintFilter = ElfJniHintFilter.ALL,
    limit: Int = MAX_ELF_JNI_HINTS
): List<HexElfJniRegistrationHint> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return hints.asSequence()
        .filter { hint -> hintFilter.matches(hint) }
        .filter { hint -> hint.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterElfNativeApiHints(
    hints: List<HexElfNativeApiHint>,
    query: String,
    apiFilter: ElfNativeApiFilter = ElfNativeApiFilter.ALL,
    limit: Int = MAX_ELF_NATIVE_API_HINTS
): List<HexElfNativeApiHint> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return hints.asSequence()
        .filter { hint -> apiFilter.matches(hint) }
        .filter { hint -> hint.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexStringEntries(
    entries: List<HexDexStringEntry>,
    query: String,
    limit: Int = MAX_DEX_STRING_ENTRIES
): List<HexDexStringEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexTypeEntries(
    entries: List<HexDexTypeEntry>,
    query: String,
    limit: Int = MAX_DEX_TYPE_ENTRIES
): List<HexDexTypeEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexProtoEntries(
    entries: List<HexDexProtoEntry>,
    query: String,
    limit: Int = MAX_DEX_PROTO_ENTRIES
): List<HexDexProtoEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexFieldEntries(
    entries: List<HexDexFieldEntry>,
    query: String,
    limit: Int = MAX_DEX_FIELD_ENTRIES
): List<HexDexFieldEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexMethodEntries(
    entries: List<HexDexMethodEntry>,
    query: String,
    limit: Int = MAX_DEX_METHOD_ENTRIES
): List<HexDexMethodEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexClassDefEntries(
    entries: List<HexDexClassDefEntry>,
    query: String,
    limit: Int = MAX_DEX_CLASS_DEF_ENTRIES
): List<HexDexClassDefEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexClassDataMethodEntries(
    entries: List<HexDexClassDataMethodEntry>,
    query: String,
    limit: Int = MAX_DEX_CLASS_DATA_METHOD_ENTRIES
): List<HexDexClassDataMethodEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexCodeItemEntries(
    entries: List<HexDexCodeItemEntry>,
    query: String,
    limit: Int = MAX_DEX_CODE_ITEM_ENTRIES
): List<HexDexCodeItemEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexCallReferenceEntries(
    entries: List<HexDexCallReferenceEntry>,
    query: String,
    limit: Int = MAX_DEX_CALL_REFERENCE_ENTRIES
): List<HexDexCallReferenceEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexStringReferenceEntries(
    entries: List<HexDexStringReferenceEntry>,
    query: String,
    limit: Int = MAX_DEX_STRING_REFERENCE_ENTRIES
): List<HexDexStringReferenceEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexFieldReferenceEntries(
    entries: List<HexDexFieldReferenceEntry>,
    query: String,
    limit: Int = MAX_DEX_FIELD_REFERENCE_ENTRIES
): List<HexDexFieldReferenceEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterDexMapEntries(
    entries: List<HexDexMapEntry>,
    query: String,
    mapFilter: DexMapEntryFilter = DexMapEntryFilter.ALL,
    limit: Int = MAX_DEX_MAP_ENTRIES
): List<HexDexMapEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> mapFilter.matches(entry) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterArchiveEntries(
    entries: List<HexArchiveEntry>,
    query: String,
    archiveFilter: ArchiveEntryFilter = ArchiveEntryFilter.ALL,
    limit: Int = MAX_ARCHIVE_ENTRIES
): List<HexArchiveEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> archiveFilter.matches(entry) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterArchiveDexSummaries(
    entries: List<HexArchiveDexSummary>,
    query: String,
    limit: Int = MAX_ARCHIVE_DEX_SUMMARIES
): List<HexArchiveDexSummary> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterArchiveNativeLibrarySummaries(
    entries: List<HexArchiveNativeLibrarySummary>,
    query: String,
    loadModeFilter: ArchiveNativeLibraryLoadModeFilter = ArchiveNativeLibraryLoadModeFilter.ALL,
    limit: Int = MAX_ARCHIVE_NATIVE_LIBRARY_SUMMARIES
): List<HexArchiveNativeLibrarySummary> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> loadModeFilter.matches(entry) }
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterArchiveSigningBlockEntries(
    entries: List<HexArchiveSigningBlockEntry>,
    query: String,
    limit: Int = MAX_ARCHIVE_SIGNING_BLOCK_ENTRIES
): List<HexArchiveSigningBlockEntry> {
    if (limit <= 0) return emptyList()
    val trimmedQuery = query.trim()
    return entries.asSequence()
        .filter { entry -> entry.matchesQuery(trimmedQuery) }
        .take(limit)
        .toList()
}

internal fun filterEntropyVisualBuckets(
    buckets: List<HexEntropyVisualBucket>,
    filter: EntropyBucketFilter = EntropyBucketFilter.ALL,
    limit: Int = ENTROPY_BUCKET_COUNT
): List<HexEntropyVisualBucket> {
    if (limit <= 0) return emptyList()
    return buckets.asSequence()
        .filter { bucket -> filter.matches(bucket.level) }
        .take(limit)
        .toList()
}

