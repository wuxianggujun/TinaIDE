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
