package com.wuxianggujun.tinaide.ui.compose.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * Hex analysis panel/dialog label helpers.
 * Extracted from HexViewerScreen to reduce file size.
 */
@Composable
internal fun hexFileKindLabel(fileKind: HexFileKind): String = stringResource(
    when (fileKind) {
        HexFileKind.ELF -> Strings.hex_file_kind_elf
        HexFileKind.DEX -> Strings.hex_file_kind_dex
        HexFileKind.APK -> Strings.hex_file_kind_apk
        HexFileKind.ZIP -> Strings.hex_file_kind_zip
        HexFileKind.PNG -> Strings.hex_file_kind_png
        HexFileKind.JPEG -> Strings.hex_file_kind_jpeg
        HexFileKind.UNKNOWN -> Strings.hex_file_kind_unknown
    }
)

@Composable
internal fun hexEndianLabel(endian: HexEndian): String = stringResource(
    when (endian) {
        HexEndian.LITTLE -> Strings.hex_endian_little
        HexEndian.BIG -> Strings.hex_endian_big
    }
)

@Composable
internal fun hexSignalLabel(signalType: HexAnalysisSignalType): String = stringResource(
    when (signalType) {
        HexAnalysisSignalType.HIGH_ENTROPY_REGION -> Strings.hex_signal_high_entropy
        HexAnalysisSignalType.ELF_PROGRAM_HEADERS -> Strings.hex_signal_elf_program_headers
        HexAnalysisSignalType.ELF_SECTION_SEGMENTS -> Strings.hex_signal_elf_section_segments
        HexAnalysisSignalType.ELF_SECTION_ENTROPY -> Strings.hex_signal_elf_section_entropy
        HexAnalysisSignalType.ELF_HARDENING_WARNING -> Strings.hex_signal_elf_hardening_warning
        HexAnalysisSignalType.ELF_GNU_PROPERTY -> Strings.hex_signal_elf_gnu_property
        HexAnalysisSignalType.ELF_INIT_ARRAY -> Strings.hex_signal_elf_init_array
        HexAnalysisSignalType.ELF_DYNAMIC_SYMBOLS -> Strings.hex_signal_elf_dynamic_symbols
        HexAnalysisSignalType.ELF_DYNAMIC_DEPENDENCIES -> Strings.hex_signal_elf_dynamic_dependencies
        HexAnalysisSignalType.ELF_NOTES -> Strings.hex_signal_elf_notes
        HexAnalysisSignalType.ELF_BUILD_ID -> Strings.hex_signal_elf_build_id
        HexAnalysisSignalType.ELF_RELOCATIONS -> Strings.hex_signal_elf_relocations
        HexAnalysisSignalType.ELF_LINKAGE -> Strings.hex_signal_elf_linkage
        HexAnalysisSignalType.ELF_DYNAMIC_LINKER_STEPS -> Strings.hex_signal_elf_dynamic_linker_steps
        HexAnalysisSignalType.ELF_RISK_FINDINGS -> Strings.hex_signal_elf_risk_findings
        HexAnalysisSignalType.ELF_NATIVE_API_HINTS -> Strings.hex_signal_elf_native_api_hints
        HexAnalysisSignalType.ELF_JNI_REGISTRATION_HINTS -> Strings.hex_signal_elf_jni_registration_hints
        HexAnalysisSignalType.ELF_JNI_SYMBOLS -> Strings.hex_signal_elf_jni_symbols
        HexAnalysisSignalType.ELF_RODATA -> Strings.hex_signal_elf_rodata
        HexAnalysisSignalType.OBFUSCATION_RISK -> Strings.hex_signal_obfuscation_risk
        HexAnalysisSignalType.DEX_FILE -> Strings.hex_signal_dex_file
        HexAnalysisSignalType.DEX_HEADER -> Strings.hex_signal_dex_header
        HexAnalysisSignalType.DEX_TYPE_IDS -> Strings.hex_signal_dex_type_ids
        HexAnalysisSignalType.DEX_PROTO_IDS -> Strings.hex_signal_dex_proto_ids
        HexAnalysisSignalType.DEX_FIELD_IDS -> Strings.hex_signal_dex_field_ids
        HexAnalysisSignalType.DEX_METHOD_IDS -> Strings.hex_signal_dex_method_ids
        HexAnalysisSignalType.DEX_CLASS_DEFS -> Strings.hex_signal_dex_class_defs
        HexAnalysisSignalType.DEX_CLASS_DATA -> Strings.hex_signal_dex_class_data
        HexAnalysisSignalType.DEX_NATIVE_METHODS -> Strings.hex_signal_dex_native_methods
        HexAnalysisSignalType.DEX_CODE_ITEMS -> Strings.hex_signal_dex_code_items
        HexAnalysisSignalType.DEX_CALL_REFERENCES -> Strings.hex_signal_dex_call_references
        HexAnalysisSignalType.DEX_STRING_REFERENCES -> Strings.hex_signal_dex_string_references
        HexAnalysisSignalType.DEX_FIELD_REFERENCES -> Strings.hex_signal_dex_field_references
        HexAnalysisSignalType.DEX_MAP_LIST -> Strings.hex_signal_dex_map_list
        HexAnalysisSignalType.APK_FILE -> Strings.hex_signal_apk_file
        HexAnalysisSignalType.APK_MANIFEST -> Strings.hex_signal_apk_manifest
        HexAnalysisSignalType.APK_DEX_FILES -> Strings.hex_signal_apk_dex_files
        HexAnalysisSignalType.APK_EMBEDDED_DEX_SUMMARIES -> Strings.hex_signal_apk_embedded_dex_summaries
        HexAnalysisSignalType.APK_NATIVE_LIBRARIES -> Strings.hex_signal_apk_native_libraries
        HexAnalysisSignalType.APK_ZIP_STRUCTURE -> Strings.hex_signal_apk_zip_structure
        HexAnalysisSignalType.APK_SIGNING_BLOCK -> Strings.hex_signal_apk_signing_block
    }
)

@Composable
internal fun hexBinaryFindingKindLabel(kind: HexBinaryFindingKind): String = stringResource(
    when (kind) {
        HexBinaryFindingKind.ELF_RISK -> Strings.hex_workbench_finding_kind_elf_risk
        HexBinaryFindingKind.JNI_REGISTRATION -> Strings.hex_workbench_finding_kind_jni_registration
        HexBinaryFindingKind.NATIVE_API -> Strings.hex_workbench_finding_kind_native_api
        HexBinaryFindingKind.DEX_NATIVE_METHOD -> Strings.hex_workbench_finding_kind_dex_native_method
        HexBinaryFindingKind.OBFUSCATION -> Strings.hex_workbench_finding_kind_obfuscation
        HexBinaryFindingKind.APK_NATIVE_LIBRARY -> Strings.hex_workbench_finding_kind_apk_native_library
        HexBinaryFindingKind.APK_ENTRY_RISK -> Strings.hex_workbench_finding_kind_apk_entry_risk
        HexBinaryFindingKind.SIGNAL -> Strings.hex_workbench_finding_kind_signal
    }
)

@Composable
internal fun hexBinaryFindingSeverityLabel(severity: HexBinaryFindingSeverity): String = stringResource(
    when (severity) {
        HexBinaryFindingSeverity.HIGH -> Strings.hex_workbench_finding_severity_high
        HexBinaryFindingSeverity.WARNING -> Strings.hex_workbench_finding_severity_warning
        HexBinaryFindingSeverity.INFO -> Strings.hex_workbench_finding_severity_info
    }
)

@Composable
internal fun hexBinaryFindingSeverityColor(severity: HexBinaryFindingSeverity): Color = when (severity) {
    HexBinaryFindingSeverity.HIGH -> MaterialTheme.colorScheme.error
    HexBinaryFindingSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    HexBinaryFindingSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun hexObfuscationFindingLabel(findingType: HexObfuscationFindingType): String = stringResource(
    when (findingType) {
        HexObfuscationFindingType.OLLVM_MARKER -> Strings.hex_obfuscation_ollvm_marker
        HexObfuscationFindingType.CONTROL_FLOW_FLATTENING_MARKER ->
            Strings.hex_obfuscation_control_flow_flattening
        HexObfuscationFindingType.BOGUS_CONTROL_FLOW_MARKER -> Strings.hex_obfuscation_bogus_control_flow
        HexObfuscationFindingType.INSTRUCTION_SUBSTITUTION_MARKER ->
            Strings.hex_obfuscation_instruction_substitution
        HexObfuscationFindingType.ANTI_DEBUG_HEURISTIC ->
            Strings.hex_obfuscation_anti_debug
        HexObfuscationFindingType.ANTI_INSTRUMENTATION_HEURISTIC ->
            Strings.hex_obfuscation_anti_instrumentation
        HexObfuscationFindingType.PROTECTOR_PACKER_MARKER ->
            Strings.hex_obfuscation_protector_packer
        HexObfuscationFindingType.STRING_OBFUSCATION_HEURISTIC ->
            Strings.hex_obfuscation_string_heuristic
        HexObfuscationFindingType.STRIPPED_SYMBOLS_HEURISTIC ->
            Strings.hex_obfuscation_stripped_symbols
    }
)

@Composable
internal fun hexFindingConfidenceLabel(confidence: HexFindingConfidence): String = stringResource(
    when (confidence) {
        HexFindingConfidence.LOW -> Strings.hex_obfuscation_confidence_low
        HexFindingConfidence.MEDIUM -> Strings.hex_obfuscation_confidence_medium
        HexFindingConfidence.HIGH -> Strings.hex_obfuscation_confidence_high
    }
)

@Composable
internal fun elfHardeningTypeLabel(type: HexElfHardeningType): String = stringResource(
    when (type) {
        HexElfHardeningType.PIE -> Strings.hex_elf_hardening_pie
        HexElfHardeningType.NX -> Strings.hex_elf_hardening_nx
        HexElfHardeningType.RELRO -> Strings.hex_elf_hardening_relro
        HexElfHardeningType.BIND_NOW -> Strings.hex_elf_hardening_bind_now
        HexElfHardeningType.IBT -> Strings.hex_elf_hardening_ibt
        HexElfHardeningType.SHSTK -> Strings.hex_elf_hardening_shstk
        HexElfHardeningType.BTI -> Strings.hex_elf_hardening_bti
        HexElfHardeningType.PAC -> Strings.hex_elf_hardening_pac
    }
)

@Composable
internal fun hexElfNotePropertyFeatureLabel(feature: HexElfNotePropertyFeature): String = stringResource(
    when (feature) {
        HexElfNotePropertyFeature.X86_IBT -> Strings.hex_elf_note_property_feature_ibt
        HexElfNotePropertyFeature.X86_SHSTK -> Strings.hex_elf_note_property_feature_shstk
        HexElfNotePropertyFeature.AARCH64_BTI -> Strings.hex_elf_note_property_feature_bti
        HexElfNotePropertyFeature.AARCH64_PAC -> Strings.hex_elf_note_property_feature_pac
    }
)

@Composable
internal fun hexElfNotePropertyFeatureLabels(features: List<HexElfNotePropertyFeature>): String {
    if (features.isEmpty()) return ""
    val labels = ArrayList<String>(features.size)
    for (feature in features) {
        labels += hexElfNotePropertyFeatureLabel(feature)
    }
    return labels.joinToString(", ")
}

@Composable
internal fun elfHardeningStatusLabel(enabled: Boolean): String = stringResource(
    if (enabled) {
        Strings.hex_elf_hardening_enabled
    } else {
        Strings.hex_elf_hardening_missing
    }
)

internal fun List<HexElfSymbol>.symbolNamesPreview(): String = take(MAX_ANALYSIS_PANEL_ITEMS)
    .joinToString(", ") { symbol -> symbol.name.compactForAnalysisPanel() }

internal fun List<HexElfDynamicStringEntry>.dynamicValuesPreview(): String = take(MAX_ANALYSIS_PANEL_ITEMS)
    .joinToString(", ") { entry -> entry.value.compactForAnalysisPanel() }

internal fun List<HexArchiveEntry>.archiveEntryNamesPreview(): String = take(MAX_ANALYSIS_PANEL_ITEMS)
    .joinToString(", ") { entry -> entry.name.compactForAnalysisPanel() }

internal fun List<HexArchiveNativeLibrarySummary>.nativeLibraryAbiPreview(): String = map { entry ->
    entry.abi.ifBlank { entry.fileName }
}
    .distinct()
    .take(MAX_ANALYSIS_PANEL_ITEMS)
    .joinToString(", ") { value -> value.compactForAnalysisPanel() }

internal fun List<HexArchiveSigningBlockEntry>.signingBlockNamesPreview(): String = take(MAX_ANALYSIS_PANEL_ITEMS)
    .joinToString(", ") { entry -> entry.idName }

internal fun List<HexDexStringEntry>.dexStringValuesPreview(): String = take(MAX_ANALYSIS_PANEL_ITEMS)
    .joinToString(", ") { entry -> entry.value.compactForAnalysisPanel() }

@Composable
internal fun archiveDexTruncatedLabel(truncated: Boolean): String = stringResource(
    if (truncated) {
        Strings.hex_archive_dex_truncated
    } else {
        Strings.hex_archive_dex_complete
    }
)

@Composable
internal fun stringEncodingLabel(encoding: HexStringEncoding): String = stringResource(
    when (encoding) {
        HexStringEncoding.ASCII -> Strings.hex_string_encoding_ascii
        HexStringEncoding.UTF_8 -> Strings.hex_string_encoding_utf8
        HexStringEncoding.UTF_16LE -> Strings.hex_string_encoding_utf16le
        HexStringEncoding.UTF_16BE -> Strings.hex_string_encoding_utf16be
    }
)

@Composable
internal fun stringEncodingFilterLabel(filter: StringEntryEncodingFilter): String = stringResource(
    when (filter) {
        StringEntryEncodingFilter.ALL -> Strings.hex_string_encoding_all
        StringEntryEncodingFilter.ASCII -> Strings.hex_string_encoding_ascii
        StringEntryEncodingFilter.UTF_8 -> Strings.hex_string_encoding_utf8
        StringEntryEncodingFilter.UTF_16LE -> Strings.hex_string_encoding_utf16le
        StringEntryEncodingFilter.UTF_16BE -> Strings.hex_string_encoding_utf16be
    }
)

@Composable
internal fun entropyBucketFilterLabel(filter: EntropyBucketFilter): String = stringResource(
    when (filter) {
        EntropyBucketFilter.ALL -> Strings.hex_entropy_filter_all
        EntropyBucketFilter.LOW -> Strings.hex_entropy_filter_low
        EntropyBucketFilter.MEDIUM -> Strings.hex_entropy_filter_medium
        EntropyBucketFilter.HIGH -> Strings.hex_entropy_filter_high
    }
)

@Composable
internal fun entropyLevelLabel(level: HexEntropyLevel): String = stringResource(
    when (level) {
        HexEntropyLevel.LOW -> Strings.hex_entropy_filter_low
        HexEntropyLevel.MEDIUM -> Strings.hex_entropy_filter_medium
        HexEntropyLevel.HIGH -> Strings.hex_entropy_filter_high
    }
)

@Composable
internal fun elfSectionFilterLabel(filter: ElfSectionFilter): String = stringResource(
    when (filter) {
        ElfSectionFilter.ALL -> Strings.hex_elf_section_filter_all
        ElfSectionFilter.ALLOCATED -> Strings.hex_elf_section_filter_allocated
        ElfSectionFilter.EXECUTABLE -> Strings.hex_elf_section_filter_executable
        ElfSectionFilter.WRITABLE -> Strings.hex_elf_section_filter_writable
        ElfSectionFilter.STRING_TABLE -> Strings.hex_elf_section_filter_string_table
        ElfSectionFilter.SYMBOL_TABLE -> Strings.hex_elf_section_filter_symbol_table
    }
)

@Composable
internal fun elfSectionTypeLabel(type: Long): String = stringResource(
    when (type) {
        0L -> Strings.hex_elf_section_type_null
        1L -> Strings.hex_elf_section_type_progbits
        2L -> Strings.hex_elf_section_type_symtab
        3L -> Strings.hex_elf_section_type_strtab
        4L -> Strings.hex_elf_section_type_rela
        5L -> Strings.hex_elf_section_type_hash
        6L -> Strings.hex_elf_section_type_dynamic
        7L -> Strings.hex_elf_section_type_note
        8L -> Strings.hex_elf_section_type_nobits
        9L -> Strings.hex_elf_section_type_rel
        11L -> Strings.hex_elf_section_type_dynsym
        14L -> Strings.hex_elf_section_type_init_array
        15L -> Strings.hex_elf_section_type_fini_array
        16L -> Strings.hex_elf_section_type_preinit_array
        else -> Strings.hex_elf_section_type_other
    }
)

@Composable
internal fun elfSectionFlagsLabel(flags: Long): String {
    if (flags == 0L) return stringResource(Strings.hex_elf_section_flags_none)
    val allocFlag = stringResource(Strings.hex_elf_section_flag_alloc)
    val writeFlag = stringResource(Strings.hex_elf_section_flag_write)
    val execFlag = stringResource(Strings.hex_elf_section_flag_exec)
    val flagLabel = buildString {
        if ((flags and 0x2L) != 0L) append(allocFlag)
        if ((flags and 0x1L) != 0L) append(writeFlag)
        if ((flags and 0x4L) != 0L) append(execFlag)
    }
    return if (flagLabel.isBlank()) {
        stringResource(Strings.hex_elf_section_flags_raw, flags)
    } else {
        flagLabel
    }
}

@Composable
internal fun elfSectionSegmentFilterLabel(filter: ElfSectionSegmentFilter): String = stringResource(
    when (filter) {
        ElfSectionSegmentFilter.ALL -> Strings.hex_elf_section_segment_filter_all
        ElfSectionSegmentFilter.EXECUTABLE -> Strings.hex_elf_section_segment_filter_executable
        ElfSectionSegmentFilter.WRITABLE -> Strings.hex_elf_section_segment_filter_writable
        ElfSectionSegmentFilter.READABLE -> Strings.hex_elf_section_segment_filter_readable
    }
)

@Composable
internal fun elfSymbolFilterLabel(filter: ElfSymbolFilter): String = stringResource(
    when (filter) {
        ElfSymbolFilter.ALL -> Strings.hex_elf_symbol_filter_all
        ElfSymbolFilter.IMPORTED -> Strings.hex_elf_symbol_filter_imported
        ElfSymbolFilter.EXPORTED -> Strings.hex_elf_symbol_filter_exported
        ElfSymbolFilter.JNI -> Strings.hex_elf_symbol_filter_jni
    }
)

@Composable
internal fun dexClassDataMethodKindLabel(kind: HexDexClassDataMethodKind): String = stringResource(
    when (kind) {
        HexDexClassDataMethodKind.DIRECT -> Strings.hex_dex_class_data_method_kind_direct
        HexDexClassDataMethodKind.VIRTUAL -> Strings.hex_dex_class_data_method_kind_virtual
    }
)

@Composable
internal fun dexClassDataMethodExecutionKindLabel(kind: HexDexClassDataMethodExecutionKind): String = stringResource(
    when (kind) {
        HexDexClassDataMethodExecutionKind.CODE -> Strings.hex_dex_class_data_method_execution_code
        HexDexClassDataMethodExecutionKind.NATIVE -> Strings.hex_dex_class_data_method_execution_native
        HexDexClassDataMethodExecutionKind.ABSTRACT -> Strings.hex_dex_class_data_method_execution_abstract
        HexDexClassDataMethodExecutionKind.NO_CODE -> Strings.hex_dex_class_data_method_execution_no_code
    }
)

@Composable
internal fun elfProgramHeaderFilterLabel(filter: ElfProgramHeaderFilter): String = stringResource(
    when (filter) {
        ElfProgramHeaderFilter.ALL -> Strings.hex_elf_program_header_filter_all
        ElfProgramHeaderFilter.LOAD -> Strings.hex_elf_program_header_filter_load
        ElfProgramHeaderFilter.EXECUTABLE -> Strings.hex_elf_program_header_filter_executable
        ElfProgramHeaderFilter.WRITABLE -> Strings.hex_elf_program_header_filter_writable
        ElfProgramHeaderFilter.DYNAMIC -> Strings.hex_elf_program_header_filter_dynamic
        ElfProgramHeaderFilter.HARDENING -> Strings.hex_elf_program_header_filter_hardening
    }
)

@Composable
internal fun elfProgramHeaderFlagsLabel(flags: Int): String {
    if (flags == 0) return stringResource(Strings.hex_elf_program_header_flags_none)
    val readFlag = stringResource(Strings.hex_elf_program_header_flag_read)
    val writeFlag = stringResource(Strings.hex_elf_program_header_flag_write)
    val executeFlag = stringResource(Strings.hex_elf_program_header_flag_execute)
    val flagLabel = buildString {
        if ((flags and 0x4) != 0) append(readFlag)
        if ((flags and 0x2) != 0) append(writeFlag)
        if ((flags and 0x1) != 0) append(executeFlag)
    }
    return if (flagLabel.isBlank()) {
        stringResource(Strings.hex_elf_program_header_flags_raw, flags)
    } else {
        flagLabel
    }
}

@Composable
internal fun elfDynamicEntryFilterLabel(filter: ElfDynamicEntryFilter): String = stringResource(
    when (filter) {
        ElfDynamicEntryFilter.ALL -> Strings.hex_elf_dynamic_filter_all
        ElfDynamicEntryFilter.NEEDED -> Strings.hex_elf_dynamic_filter_needed
        ElfDynamicEntryFilter.SONAME -> Strings.hex_elf_dynamic_filter_soname
        ElfDynamicEntryFilter.RPATH -> Strings.hex_elf_dynamic_filter_rpath
        ElfDynamicEntryFilter.RUNPATH -> Strings.hex_elf_dynamic_filter_runpath
    }
)

@Composable
internal fun elfDynamicFlagFilterLabel(filter: ElfDynamicFlagFilter): String = stringResource(
    when (filter) {
        ElfDynamicFlagFilter.ALL -> Strings.hex_elf_dynamic_flag_filter_all
        ElfDynamicFlagFilter.BIND_NOW -> Strings.hex_elf_dynamic_flag_filter_bind_now
        ElfDynamicFlagFilter.FLAGS -> Strings.hex_elf_dynamic_flag_filter_flags
        ElfDynamicFlagFilter.FLAGS_1 -> Strings.hex_elf_dynamic_flag_filter_flags_1
    }
)

@Composable
internal fun elfNoteFilterLabel(filter: ElfNoteFilter): String = stringResource(
    when (filter) {
        ElfNoteFilter.ALL -> Strings.hex_elf_note_filter_all
        ElfNoteFilter.BUILD_ID -> Strings.hex_elf_note_filter_build_id
        ElfNoteFilter.GNU -> Strings.hex_elf_note_filter_gnu
        ElfNoteFilter.ANDROID -> Strings.hex_elf_note_filter_android
    }
)

@Composable
internal fun elfRelocationFilterLabel(filter: ElfRelocationFilter): String = stringResource(
    when (filter) {
        ElfRelocationFilter.ALL -> Strings.hex_elf_relocation_filter_all
        ElfRelocationFilter.PLT -> Strings.hex_elf_relocation_filter_plt
        ElfRelocationFilter.DYNAMIC -> Strings.hex_elf_relocation_filter_dynamic
    }
)

@Composable
internal fun elfLinkageFilterLabel(filter: ElfLinkageFilter): String = stringResource(
    when (filter) {
        ElfLinkageFilter.ALL -> Strings.hex_elf_linkage_filter_all
        ElfLinkageFilter.IMPORTS -> Strings.hex_elf_linkage_filter_imports
        ElfLinkageFilter.PLT -> Strings.hex_elf_linkage_filter_plt
        ElfLinkageFilter.GOT -> Strings.hex_elf_linkage_filter_got
        ElfLinkageFilter.JNI -> Strings.hex_elf_linkage_filter_jni
        ElfLinkageFilter.NOW -> Strings.hex_elf_linkage_filter_now
        ElfLinkageFilter.LAZY -> Strings.hex_elf_linkage_filter_lazy
    }
)

@Composable
internal fun elfLinkageKindLabel(kind: HexElfLinkageEntryKind): String = stringResource(
    when (kind) {
        HexElfLinkageEntryKind.PLT -> Strings.hex_elf_linkage_kind_plt
        HexElfLinkageEntryKind.GOT -> Strings.hex_elf_linkage_kind_got
        HexElfLinkageEntryKind.RELATIVE -> Strings.hex_elf_linkage_kind_relative
        HexElfLinkageEntryKind.OTHER -> Strings.hex_elf_linkage_kind_other
    }
)

@Composable
internal fun elfLinkageBindingModeLabel(mode: HexElfLinkageBindingMode): String = stringResource(
    when (mode) {
        HexElfLinkageBindingMode.NOW -> Strings.hex_elf_linkage_mode_now
        HexElfLinkageBindingMode.LAZY -> Strings.hex_elf_linkage_mode_lazy
        HexElfLinkageBindingMode.LOAD_TIME -> Strings.hex_elf_linkage_mode_load_time
        HexElfLinkageBindingMode.LOCAL -> Strings.hex_elf_linkage_mode_local
    }
)

@Composable
internal fun elfDynamicLinkerStepFilterLabel(filter: ElfDynamicLinkerStepFilter): String = stringResource(
    when (filter) {
        ElfDynamicLinkerStepFilter.ALL -> Strings.hex_elf_loader_step_filter_all
        ElfDynamicLinkerStepFilter.LOADING -> Strings.hex_elf_loader_step_filter_loading
        ElfDynamicLinkerStepFilter.RELOCATIONS -> Strings.hex_elf_loader_step_filter_relocations
        ElfDynamicLinkerStepFilter.BINDING -> Strings.hex_elf_loader_step_filter_binding
        ElfDynamicLinkerStepFilter.HARDENING -> Strings.hex_elf_loader_step_filter_hardening
        ElfDynamicLinkerStepFilter.ENTRYPOINTS -> Strings.hex_elf_loader_step_filter_entrypoints
    }
)

@Composable
internal fun elfDynamicLinkerStepTypeLabel(type: HexElfDynamicLinkerStepType): String = stringResource(
    when (type) {
        HexElfDynamicLinkerStepType.MAP_LOAD_SEGMENTS -> Strings.hex_elf_loader_step_map_load_segments
        HexElfDynamicLinkerStepType.LOAD_NEEDED_LIBRARIES -> Strings.hex_elf_loader_step_load_needed
        HexElfDynamicLinkerStepType.APPLY_RELOCATIONS -> Strings.hex_elf_loader_step_apply_relocations
        HexElfDynamicLinkerStepType.RESOLVE_NOW_BINDINGS -> Strings.hex_elf_loader_step_resolve_now
        HexElfDynamicLinkerStepType.ENABLE_LAZY_PLT -> Strings.hex_elf_loader_step_enable_lazy
        HexElfDynamicLinkerStepType.PROTECT_RELRO -> Strings.hex_elf_loader_step_protect_relro
        HexElfDynamicLinkerStepType.CALL_INIT_ARRAY -> Strings.hex_elf_loader_step_call_init_array
        HexElfDynamicLinkerStepType.EXPOSE_JNI_ENTRYPOINTS -> Strings.hex_elf_loader_step_expose_jni
    }
)

@Composable
internal fun elfRiskFilterLabel(filter: ElfRiskFilter): String = stringResource(
    when (filter) {
        ElfRiskFilter.ALL -> Strings.hex_elf_risk_filter_all
        ElfRiskFilter.HIGH -> Strings.hex_elf_risk_filter_high
        ElfRiskFilter.WARNING -> Strings.hex_elf_risk_filter_warning
        ElfRiskFilter.HARDENING -> Strings.hex_elf_risk_filter_hardening
        ElfRiskFilter.SEGMENTS -> Strings.hex_elf_risk_filter_segments
        ElfRiskFilter.PATHS -> Strings.hex_elf_risk_filter_paths
        ElfRiskFilter.METADATA -> Strings.hex_elf_risk_filter_metadata
    }
)

@Composable
internal fun elfRiskSeverityLabel(severity: HexElfRiskSeverity): String = stringResource(
    when (severity) {
        HexElfRiskSeverity.HIGH -> Strings.hex_elf_risk_severity_high
        HexElfRiskSeverity.WARNING -> Strings.hex_elf_risk_severity_warning
        HexElfRiskSeverity.INFO -> Strings.hex_elf_risk_severity_info
    }
)

@Composable
internal fun elfRiskTypeLabel(type: HexElfRiskFindingType): String = stringResource(
    when (type) {
        HexElfRiskFindingType.RWX_LOAD_SEGMENT -> Strings.hex_elf_risk_type_rwx_load_segment
        HexElfRiskFindingType.WRITABLE_EXECUTABLE_SECTION ->
            Strings.hex_elf_risk_type_writable_executable_section
        HexElfRiskFindingType.EXECUTABLE_STACK -> Strings.hex_elf_risk_type_executable_stack
        HexElfRiskFindingType.MISSING_RELRO -> Strings.hex_elf_risk_type_missing_relro
        HexElfRiskFindingType.MISSING_BIND_NOW -> Strings.hex_elf_risk_type_missing_bind_now
        HexElfRiskFindingType.LEGACY_RPATH -> Strings.hex_elf_risk_type_legacy_rpath
        HexElfRiskFindingType.RUNPATH_PRESENT -> Strings.hex_elf_risk_type_runpath_present
        HexElfRiskFindingType.MISSING_SONAME -> Strings.hex_elf_risk_type_missing_soname
    }
)

@Composable
internal fun dexMapFilterLabel(filter: DexMapEntryFilter): String = stringResource(
    when (filter) {
        DexMapEntryFilter.ALL -> Strings.hex_dex_map_filter_all
        DexMapEntryFilter.IDS -> Strings.hex_dex_map_filter_ids
        DexMapEntryFilter.CLASS_DATA -> Strings.hex_dex_map_filter_class_data
        DexMapEntryFilter.CODE -> Strings.hex_dex_map_filter_code
        DexMapEntryFilter.DATA -> Strings.hex_dex_map_filter_data
    }
)

@Composable
internal fun archiveEntryFilterLabel(filter: ArchiveEntryFilter): String = stringResource(
    when (filter) {
        ArchiveEntryFilter.ALL -> Strings.hex_archive_entry_filter_all
        ArchiveEntryFilter.DEX -> Strings.hex_archive_entry_filter_dex
        ArchiveEntryFilter.NATIVE_LIBRARIES -> Strings.hex_archive_entry_filter_native_libraries
        ArchiveEntryFilter.MANIFEST -> Strings.hex_archive_entry_filter_manifest
        ArchiveEntryFilter.RESOURCES -> Strings.hex_archive_entry_filter_resources
        ArchiveEntryFilter.SIGNATURE -> Strings.hex_archive_entry_filter_signature
    }
)

@Composable
internal fun archiveEntryFlagsLabel(entry: HexArchiveEntry): String = stringResource(
    if (entry.usesDataDescriptor) {
        Strings.hex_archive_entry_flags_data_descriptor
    } else {
        Strings.hex_archive_entry_flags
    },
    entry.generalPurposeBitFlag
)

@Composable
internal fun archiveEntryDataOffsetLabel(entry: HexArchiveEntry): String = entry.dataOffset?.let { offset ->
    stringResource(Strings.hex_archive_entry_data_offset, offset)
} ?: stringResource(Strings.hex_archive_entry_data_offset_unknown)

@Composable
internal fun archiveEntryDataRangeLabel(entry: HexArchiveEntry): String {
    val dataOffset = entry.dataOffset
    val dataEndOffset = entry.dataEndOffset
    return if (dataOffset != null && dataEndOffset != null) {
        stringResource(Strings.hex_archive_entry_data_range_value, dataOffset, dataEndOffset)
    } else {
        stringResource(Strings.hex_archive_entry_data_range_unknown)
    }
}

@Composable
internal fun archiveEntryDataRangeStatusLabel(status: HexArchiveEntryDataRangeStatus): String = stringResource(
    when (status) {
        HexArchiveEntryDataRangeStatus.OK -> Strings.hex_archive_entry_data_range_status_ok
        HexArchiveEntryDataRangeStatus.UNKNOWN -> Strings.hex_archive_entry_data_range_status_unknown
        HexArchiveEntryDataRangeStatus.OUT_OF_FILE -> Strings.hex_archive_entry_data_range_status_out_of_file
        HexArchiveEntryDataRangeStatus.OVERLAPS_CENTRAL_DIRECTORY ->
            Strings.hex_archive_entry_data_range_status_overlaps_central
    }
)

@Composable
internal fun archiveEntryDataRangeStatusColor(status: HexArchiveEntryDataRangeStatus) = when (status) {
    HexArchiveEntryDataRangeStatus.OK,
    HexArchiveEntryDataRangeStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    HexArchiveEntryDataRangeStatus.OUT_OF_FILE,
    HexArchiveEntryDataRangeStatus.OVERLAPS_CENTRAL_DIRECTORY -> MaterialTheme.colorScheme.error
}

@Composable
internal fun archiveEntryLocalHeaderLabel(entry: HexArchiveEntry): String {
    val localName = entry.localHeaderName
    val localMethod = entry.localHeaderCompressionMethod
    val localFlags = entry.localHeaderGeneralPurposeBitFlag
    return if (localName != null && localMethod != null && localFlags != null) {
        stringResource(Strings.hex_archive_entry_local_header_value, localName, localMethod, localFlags)
    } else {
        stringResource(Strings.hex_archive_entry_local_header_unknown)
    }
}

@Composable
internal fun archiveEntryLocalHeaderStatusLabel(
    status: HexArchiveEntryLocalHeaderConsistency
): String = stringResource(
    when (status) {
        HexArchiveEntryLocalHeaderConsistency.OK -> Strings.hex_archive_entry_local_header_status_ok
        HexArchiveEntryLocalHeaderConsistency.UNKNOWN -> Strings.hex_archive_entry_local_header_status_unknown
        HexArchiveEntryLocalHeaderConsistency.NAME_MISMATCH ->
            Strings.hex_archive_entry_local_header_status_name_mismatch
        HexArchiveEntryLocalHeaderConsistency.METADATA_MISMATCH ->
            Strings.hex_archive_entry_local_header_status_metadata_mismatch
        HexArchiveEntryLocalHeaderConsistency.MULTIPLE_MISMATCHES ->
            Strings.hex_archive_entry_local_header_status_multiple_mismatches
    }
)

@Composable
internal fun archiveEntryLocalHeaderStatusColor(status: HexArchiveEntryLocalHeaderConsistency) = when (status) {
    HexArchiveEntryLocalHeaderConsistency.OK,
    HexArchiveEntryLocalHeaderConsistency.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    HexArchiveEntryLocalHeaderConsistency.NAME_MISMATCH,
    HexArchiveEntryLocalHeaderConsistency.METADATA_MISMATCH,
    HexArchiveEntryLocalHeaderConsistency.MULTIPLE_MISMATCHES -> MaterialTheme.colorScheme.error
}

@Composable
internal fun archiveEntryNameRiskLabel(risks: Set<HexArchiveEntryNameRisk>): String {
    if (risks.isEmpty()) return stringResource(Strings.hex_archive_entry_name_risk_ok)
    val labels = mutableListOf<String>()
    for (risk in risks.sortedBy { item -> item.ordinal }) {
        labels += archiveEntryNameRiskItemLabel(risk)
    }
    val riskLabels = labels.joinToString(separator = ", ")
    return stringResource(Strings.hex_archive_entry_name_risk, riskLabels)
}

@Composable
internal fun archiveEntryNameRiskItemLabel(risk: HexArchiveEntryNameRisk): String = stringResource(
    when (risk) {
        HexArchiveEntryNameRisk.EMPTY_NAME -> Strings.hex_archive_entry_name_risk_empty
        HexArchiveEntryNameRisk.DUPLICATE_NAME -> Strings.hex_archive_entry_name_risk_duplicate
        HexArchiveEntryNameRisk.ABSOLUTE_PATH -> Strings.hex_archive_entry_name_risk_absolute_path
        HexArchiveEntryNameRisk.WINDOWS_DRIVE_PATH -> Strings.hex_archive_entry_name_risk_windows_drive
        HexArchiveEntryNameRisk.PATH_TRAVERSAL -> Strings.hex_archive_entry_name_risk_path_traversal
        HexArchiveEntryNameRisk.BACKSLASH_SEPARATOR -> Strings.hex_archive_entry_name_risk_backslash
    }
)

@Composable
internal fun archiveEntryNameRiskColor(risks: Set<HexArchiveEntryNameRisk>) = if (risks.isEmpty()) {
    MaterialTheme.colorScheme.onSurfaceVariant
} else {
    MaterialTheme.colorScheme.error
}

@Composable
internal fun elfNativeApiFilterLabel(filter: ElfNativeApiFilter): String = stringResource(
    when (filter) {
        ElfNativeApiFilter.ALL -> Strings.hex_elf_native_api_filter_all
        ElfNativeApiFilter.DYNAMIC_LOADING -> Strings.hex_elf_native_api_filter_dynamic_loading
        ElfNativeApiFilter.MEMORY -> Strings.hex_elf_native_api_filter_memory
        ElfNativeApiFilter.PROCESS -> Strings.hex_elf_native_api_filter_process
        ElfNativeApiFilter.FILE -> Strings.hex_elf_native_api_filter_file
        ElfNativeApiFilter.NETWORK -> Strings.hex_elf_native_api_filter_network
        ElfNativeApiFilter.CRYPTO -> Strings.hex_elf_native_api_filter_crypto
        ElfNativeApiFilter.THREADING -> Strings.hex_elf_native_api_filter_threading
        ElfNativeApiFilter.LOGGING -> Strings.hex_elf_native_api_filter_logging
    }
)

@Composable
internal fun elfNativeApiCategoryLabel(category: HexElfNativeApiCategory): String = stringResource(
    when (category) {
        HexElfNativeApiCategory.DYNAMIC_LOADING -> Strings.hex_elf_native_api_category_dynamic_loading
        HexElfNativeApiCategory.MEMORY_PROTECTION -> Strings.hex_elf_native_api_category_memory
        HexElfNativeApiCategory.PROCESS_CONTROL -> Strings.hex_elf_native_api_category_process
        HexElfNativeApiCategory.FILE_IO -> Strings.hex_elf_native_api_category_file
        HexElfNativeApiCategory.NETWORK -> Strings.hex_elf_native_api_category_network
        HexElfNativeApiCategory.CRYPTO -> Strings.hex_elf_native_api_category_crypto
        HexElfNativeApiCategory.THREADING -> Strings.hex_elf_native_api_category_threading
        HexElfNativeApiCategory.LOGGING -> Strings.hex_elf_native_api_category_logging
    }
)

@Composable
internal fun elfJniHintFilterLabel(filter: ElfJniHintFilter): String = stringResource(
    when (filter) {
        ElfJniHintFilter.ALL -> Strings.hex_elf_jni_hint_filter_all
        ElfJniHintFilter.REGISTER_NATIVES -> Strings.hex_elf_jni_hint_filter_register_natives
        ElfJniHintFilter.ENTRYPOINTS -> Strings.hex_elf_jni_hint_filter_entrypoints
        ElfJniHintFilter.STATIC_EXPORTS -> Strings.hex_elf_jni_hint_filter_static_exports
        ElfJniHintFilter.DESCRIPTORS -> Strings.hex_elf_jni_hint_filter_descriptors
    }
)

@Composable
internal fun elfJniHintTypeLabel(type: HexElfJniRegistrationHintType): String = stringResource(
    when (type) {
        HexElfJniRegistrationHintType.REGISTER_NATIVES_SYMBOL ->
            Strings.hex_elf_jni_hint_type_register_natives_symbol
        HexElfJniRegistrationHintType.REGISTER_NATIVES_STRING ->
            Strings.hex_elf_jni_hint_type_register_natives_string
        HexElfJniRegistrationHintType.JNI_ONLOAD_ENTRY -> Strings.hex_elf_jni_hint_type_jni_onload
        HexElfJniRegistrationHintType.JNI_ONUNLOAD_ENTRY -> Strings.hex_elf_jni_hint_type_jni_onunload
        HexElfJniRegistrationHintType.STATIC_JNI_EXPORT -> Strings.hex_elf_jni_hint_type_static_export
        HexElfJniRegistrationHintType.JAVA_CLASS_DESCRIPTOR ->
            Strings.hex_elf_jni_hint_type_java_class_descriptor
        HexElfJniRegistrationHintType.JNI_METHOD_SIGNATURE ->
            Strings.hex_elf_jni_hint_type_jni_method_signature
    }
)

@Composable
internal fun elfDynamicFlagTypeLabel(type: HexElfDynamicFlagType): String = stringResource(
    when (type) {
        HexElfDynamicFlagType.BIND_NOW -> Strings.hex_elf_dynamic_flag_type_bind_now
        HexElfDynamicFlagType.FLAGS -> Strings.hex_elf_dynamic_flag_type_flags
        HexElfDynamicFlagType.FLAGS_1 -> Strings.hex_elf_dynamic_flag_type_flags_1
    }
)

@Composable
internal fun elfDynamicFlagBindNowLabel(bindNow: Boolean): String = stringResource(
    if (bindNow) {
        Strings.hex_elf_dynamic_flag_bind_now_enabled
    } else {
        Strings.hex_elf_dynamic_flag_bind_now_missing
    }
)

@Composable
internal fun elfDynamicStringTypeLabel(type: HexElfDynamicStringType): String = stringResource(
    when (type) {
        HexElfDynamicStringType.NEEDED -> Strings.hex_elf_dynamic_type_needed
        HexElfDynamicStringType.SONAME -> Strings.hex_elf_dynamic_type_soname
        HexElfDynamicStringType.RPATH -> Strings.hex_elf_dynamic_type_rpath
        HexElfDynamicStringType.RUNPATH -> Strings.hex_elf_dynamic_type_runpath
    }
)

@Composable
internal fun elfDynamicStringSemanticLabel(semantic: HexElfDynamicStringSemantic): String = stringResource(
    when (semantic) {
        HexElfDynamicStringSemantic.NEEDED_LIBRARY_LOAD -> Strings.hex_elf_dynamic_semantic_needed_load
        HexElfDynamicStringSemantic.SONAME_IDENTITY -> Strings.hex_elf_dynamic_semantic_soname_identity
        HexElfDynamicStringSemantic.LEGACY_RPATH_SEARCH -> Strings.hex_elf_dynamic_semantic_legacy_rpath
        HexElfDynamicStringSemantic.RUNPATH_SEARCH -> Strings.hex_elf_dynamic_semantic_runpath
        HexElfDynamicStringSemantic.UNKNOWN -> Strings.hex_elf_dynamic_semantic_unknown
    }
)

@Composable
internal fun elfSymbolRoleLabel(symbol: HexElfSymbol): String = stringResource(
    when {
        symbol.isJni -> Strings.hex_elf_symbol_role_jni
        symbol.isImported -> Strings.hex_elf_symbol_role_imported
        symbol.isExported -> Strings.hex_elf_symbol_role_exported
        else -> Strings.hex_elf_symbol_role_local
    }
)

@Composable
internal fun elfSymbolTypeLabel(type: HexElfSymbolType): String = stringResource(
    when (type) {
        HexElfSymbolType.NOTYPE -> Strings.hex_elf_symbol_type_notype
        HexElfSymbolType.OBJECT -> Strings.hex_elf_symbol_type_object
        HexElfSymbolType.FUNC -> Strings.hex_elf_symbol_type_func
        HexElfSymbolType.SECTION -> Strings.hex_elf_symbol_type_section
        HexElfSymbolType.FILE -> Strings.hex_elf_symbol_type_file
        HexElfSymbolType.TLS -> Strings.hex_elf_symbol_type_tls
        HexElfSymbolType.OTHER -> Strings.hex_elf_symbol_type_other
    }
)
