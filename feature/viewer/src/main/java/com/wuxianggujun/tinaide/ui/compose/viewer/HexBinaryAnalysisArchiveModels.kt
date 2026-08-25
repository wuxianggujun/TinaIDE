package com.wuxianggujun.tinaide.ui.compose.viewer

/**
 * Archive/APK/ZIP analysis domain models.
 */

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
