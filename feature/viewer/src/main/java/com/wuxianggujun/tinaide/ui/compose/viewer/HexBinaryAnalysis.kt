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

internal data class HexObfuscationEvidence(
    val value: String,
    val normalizedValue: String,
    val offset: Long? = null
)

internal suspend fun analyzeHexBinaryFile(file: File): HexBinaryAnalysis = withContext(Dispatchers.IO) {
    if (!file.exists() || !file.isFile) {
        return@withContext HexBinaryAnalysis(fileKind = HexFileKind.UNKNOWN, fileSize = 0L)
    }

    RandomAccessFile(file, "r").use { randomAccessFile ->
        val fileSize = randomAccessFile.length().coerceAtLeast(0L)
        val fileScanSummary = scanFileSummary(file)
        val header = randomAccessFile.readAt(offset = 0L, byteCount = minOf(ELF_HEADER_READ_LIMIT.toLong(), fileSize).toInt())
        val fileKind = detectFileKind(file, header)
        val rawElf = if (fileKind == HexFileKind.ELF) parseElfSummary(randomAccessFile, header) else null
        val dex = if (fileKind == HexFileKind.DEX) parseDexSummary(randomAccessFile, fileSize, header) else null
        val archive = if (fileKind == HexFileKind.APK || fileKind == HexFileKind.ZIP) {
            parseArchiveSummary(file, randomAccessFile, fileSize)
        } else {
            null
        }
        val strings = extractBinaryStrings(randomAccessFile, fileSize)
        val elf = rawElf?.copy(
            jniRegistrationHints = buildElfJniRegistrationHints(rawElf, strings)
        )
        val entropy = calculateEntropyBuckets(randomAccessFile, fileSize)
        val entropyVisualBuckets = entropy.toVisualBuckets()
        val obfuscationFindings = detectObfuscationFindings(
            fileKind = fileKind,
            fileSize = fileSize,
            elf = elf,
            strings = strings,
            entropy = entropy
        )
        val signals = buildAnalysisSignals(fileKind, elf, dex, archive, entropy, obfuscationFindings)

        HexBinaryAnalysis(
            fileKind = fileKind,
            fileSize = fileSize,
            fingerprint = fileScanSummary.fingerprint,
            byteFrequency = fileScanSummary.byteFrequency,
            repeatedByteRuns = fileScanSummary.repeatedByteRuns,
            magicSignatures = fileScanSummary.magicSignatures,
            elf = elf,
            dex = dex,
            archive = archive,
            strings = strings,
            entropy = entropy,
            entropyVisualBuckets = entropyVisualBuckets,
            obfuscationFindings = obfuscationFindings,
            signals = signals
        )
    }
}

internal fun detectFileKind(file: File, header: ByteArray): HexFileKind = when {
    header.startsWith(0x7F, 'E'.code, 'L'.code, 'F'.code) -> HexFileKind.ELF
    header.startsWith('d'.code, 'e'.code, 'x'.code, '\n'.code) -> HexFileKind.DEX
    header.startsWith('P'.code, 'K'.code, 0x03, 0x04) && file.extension.equals("apk", ignoreCase = true) -> HexFileKind.APK
    header.startsWith('P'.code, 'K'.code, 0x03, 0x04) -> HexFileKind.ZIP
    header.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) -> HexFileKind.PNG
    header.startsWith(0xFF, 0xD8, 0xFF) -> HexFileKind.JPEG
    else -> HexFileKind.UNKNOWN
}

internal fun scanFileSummary(file: File): HexFileScanSummary {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val sha1 = MessageDigest.getInstance("SHA-1")
    val md5 = MessageDigest.getInstance("MD5")
    val crc32 = CRC32()
    val byteCounts = LongArray(BYTE_VALUE_COUNT)
    val buffer = ByteArray(FINGERPRINT_BUFFER_BYTES)
    val repeatedByteRuns = mutableListOf<HexRepeatedByteRun>()
    val magicSignatures = mutableListOf<HexMagicSignatureMatch>()
    val magicWindow = IntArray(MAX_MAGIC_SIGNATURE_LENGTH) { -1 }
    var byteCount = 0L
    var printableAsciiBytes = 0L
    var controlBytes = 0L
    var currentRunByteValue = -1
    var currentRunStartOffset = 0L
    var currentRunLength = 0L
    var magicWindowSize = 0

    fun recordCurrentRun() {
        if (currentRunLength >= MIN_REPEATED_BYTE_RUN_LENGTH) {
            repeatedByteRuns += HexRepeatedByteRun(
                byteValue = currentRunByteValue,
                startOffset = currentRunStartOffset,
                length = currentRunLength
            )
            if (repeatedByteRuns.size > MAX_REPEATED_BYTE_RUN_CANDIDATES) {
                repeatedByteRuns.trimToLongestRepeatedByteRuns()
            }
        }
    }

    fun appendMagicWindowByte(byteValue: Int) {
        if (magicWindowSize < MAX_MAGIC_SIGNATURE_LENGTH) {
            magicWindow[magicWindowSize] = byteValue
            magicWindowSize++
        } else {
            System.arraycopy(magicWindow, 1, magicWindow, 0, MAX_MAGIC_SIGNATURE_LENGTH - 1)
            magicWindow[MAX_MAGIC_SIGNATURE_LENGTH - 1] = byteValue
        }
    }

    fun magicWindowEndsWith(signature: IntArray): Boolean {
        if (magicWindowSize < signature.size) return false
        val startIndex = magicWindowSize - signature.size
        for (signatureIndex in signature.indices) {
            if (magicWindow[startIndex + signatureIndex] != signature[signatureIndex]) return false
        }
        return true
    }

    fun detectMagicSignaturesEndingAt(absoluteOffset: Long) {
        if (magicSignatures.size >= MAX_MAGIC_SIGNATURE_MATCHES) return
        MAGIC_SIGNATURE_DEFINITIONS.forEach { definition ->
            if (magicWindowEndsWith(definition.bytes)) {
                magicSignatures += HexMagicSignatureMatch(
                    kind = definition.kind,
                    offset = absoluteOffset - definition.bytes.size + 1L,
                    signatureLength = definition.bytes.size
                )
                if (magicSignatures.size >= MAX_MAGIC_SIGNATURE_MATCHES) return
            }
        }
    }

    file.inputStream().use { inputStream ->
        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead <= 0) break
            sha256.update(buffer, 0, bytesRead)
            sha1.update(buffer, 0, bytesRead)
            md5.update(buffer, 0, bytesRead)
            crc32.update(buffer, 0, bytesRead)
            repeat(bytesRead) { index ->
                val byteValue = buffer[index].toInt() and 0xFF
                val absoluteOffset = byteCount + index.toLong()
                byteCounts[byteValue]++
                if (byteValue in PRINTABLE_ASCII_RANGE) {
                    printableAsciiBytes++
                }
                if (byteValue < ASCII_SPACE || byteValue == ASCII_DELETE) {
                    controlBytes++
                }
                appendMagicWindowByte(byteValue)
                detectMagicSignaturesEndingAt(absoluteOffset)
                if (currentRunLength == 0L) {
                    currentRunByteValue = byteValue
                    currentRunStartOffset = absoluteOffset
                    currentRunLength = 1L
                } else if (byteValue == currentRunByteValue) {
                    currentRunLength++
                } else {
                    recordCurrentRun()
                    currentRunByteValue = byteValue
                    currentRunStartOffset = absoluteOffset
                    currentRunLength = 1L
                }
            }
            byteCount += bytesRead.toLong()
        }
    }
    recordCurrentRun()
    repeatedByteRuns.trimToLongestRepeatedByteRuns()

    val frequencyEntries = mutableListOf<HexByteFrequencyEntry>()
    byteCounts.forEachIndexed { byteValue, count ->
        if (count > 0L) {
            frequencyEntries += HexByteFrequencyEntry(
                byteValue = byteValue,
                count = count,
                ratio = if (byteCount == 0L) 0.0 else count.toDouble() / byteCount.toDouble()
            )
        }
    }
    val topBytes = frequencyEntries
        .sortedWith(compareByDescending<HexByteFrequencyEntry> { it.count }.thenBy { it.byteValue })
        .take(MAX_BYTE_FREQUENCY_ENTRIES)

    return HexFileScanSummary(
        fingerprint = HexFileFingerprint(
            sha256 = sha256.digest().toLowerHexString(),
            sha1 = sha1.digest().toLowerHexString(),
            md5 = md5.digest().toLowerHexString(),
            crc32 = crc32.value,
            byteCount = byteCount
        ),
        byteFrequency = HexByteFrequencySummary(
            totalBytes = byteCount,
            uniqueByteValues = byteCounts.count { it > 0L },
            zeroBytes = byteCounts[0x00],
            ffBytes = byteCounts[0xFF],
            printableAsciiBytes = printableAsciiBytes,
            controlBytes = controlBytes,
            topBytes = topBytes
        ),
        repeatedByteRuns = repeatedByteRuns.toList(),
        magicSignatures = magicSignatures.toList()
    )
}

internal fun MutableList<HexRepeatedByteRun>.trimToLongestRepeatedByteRuns() {
    if (size <= MAX_REPEATED_BYTE_RUN_ENTRIES) return
    val longestRuns = sortedWith(
        compareByDescending<HexRepeatedByteRun> { it.length }
            .thenBy { it.startOffset }
            .thenBy { it.byteValue }
    ).take(MAX_REPEATED_BYTE_RUN_ENTRIES)
    clear()
    addAll(longestRuns)
}

internal fun extractBinaryStrings(randomAccessFile: RandomAccessFile, fileSize: Long): List<HexStringEntry> {
    if (fileSize <= 0L) return emptyList()
    val scanSize = minOf(fileSize, MAX_STRING_SCAN_BYTES.toLong()).toInt()
    val bytes = randomAccessFile.readAt(0L, scanSize)
    return (
        extractPrintableAsciiStrings(bytes) +
            extractUtf8Strings(bytes) +
            extractUtf16Strings(bytes, littleEndian = true) +
            extractUtf16Strings(bytes, littleEndian = false)
        )
        .sortedWith(compareBy<HexStringEntry> { it.offset }.thenBy { it.encoding.ordinal })
        .take(MAX_STRING_RESULTS)
}

internal fun extractPrintableAsciiStrings(bytes: ByteArray): List<HexStringEntry> {
    val strings = mutableListOf<HexStringEntry>()
    var startIndex = -1

    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 0xFF
        if (value in PRINTABLE_ASCII_RANGE) {
            if (startIndex < 0) startIndex = index
        } else if (startIndex >= 0) {
            appendAsciiStringEntry(bytes, startIndex, index, strings)
            startIndex = -1
            if (strings.size >= MAX_STRING_RESULTS) return strings
        }
    }

    if (startIndex >= 0 && strings.size < MAX_STRING_RESULTS) {
        appendAsciiStringEntry(bytes, startIndex, bytes.size, strings)
    }
    return strings
}

internal fun appendAsciiStringEntry(
    bytes: ByteArray,
    startIndex: Int,
    endIndex: Int,
    strings: MutableList<HexStringEntry>
) {
    val length = endIndex - startIndex
    if (length < MIN_STRING_LENGTH) return
    strings += HexStringEntry(
        offset = startIndex.toLong(),
        value = bytes.copyOfRange(startIndex, endIndex).toString(Charsets.US_ASCII),
        encoding = HexStringEncoding.ASCII
    )
}

internal fun extractUtf8Strings(bytes: ByteArray): List<HexStringEntry> {
    val strings = mutableListOf<HexStringEntry>()
    var startIndex = -1
    var hasNonAscii = false
    val chars = StringBuilder()
    var index = 0

    while (index < bytes.size) {
        val codePoint = bytes.decodeUtf8CodePoint(index)
        if (codePoint != null && codePoint.value.isPrintableStringCodePoint()) {
            if (startIndex < 0) startIndex = index
            if (codePoint.value > PRINTABLE_ASCII_RANGE.last) hasNonAscii = true
            chars.appendCodePoint(codePoint.value)
            index += codePoint.byteCount
        } else {
            appendUtf8StringEntry(startIndex, chars, hasNonAscii, strings)
            startIndex = -1
            hasNonAscii = false
            chars.clear()
            index++
            if (strings.size >= MAX_STRING_RESULTS) return strings
        }
    }

    appendUtf8StringEntry(startIndex, chars, hasNonAscii, strings)
    return strings
}

internal fun appendUtf8StringEntry(
    startIndex: Int,
    chars: StringBuilder,
    hasNonAscii: Boolean,
    strings: MutableList<HexStringEntry>
) {
    if (startIndex < 0 || chars.length < MIN_STRING_LENGTH || !hasNonAscii) return
    strings += HexStringEntry(
        offset = startIndex.toLong(),
        value = chars.toString(),
        encoding = HexStringEncoding.UTF_8
    )
}

internal fun extractUtf16Strings(bytes: ByteArray, littleEndian: Boolean): List<HexStringEntry> = extractUtf16Strings(bytes, littleEndian, startAlignment = 0) +
    extractUtf16Strings(bytes, littleEndian, startAlignment = 1)

internal fun extractUtf16Strings(
    bytes: ByteArray,
    littleEndian: Boolean,
    startAlignment: Int
): List<HexStringEntry> {
    val strings = mutableListOf<HexStringEntry>()
    var startIndex = -1
    val chars = StringBuilder()
    var index = startAlignment

    while (index + 1 < bytes.size) {
        val charCode = bytes.utf16CodeUnit(index, littleEndian)
        if (charCode in PRINTABLE_ASCII_RANGE) {
            if (startIndex < 0) startIndex = index
            chars.append(charCode.toChar())
        } else {
            appendUtf16StringEntry(startIndex, chars, littleEndian, strings)
            startIndex = -1
            chars.clear()
            if (strings.size >= MAX_STRING_RESULTS) return strings
        }
        index += 2
    }

    appendUtf16StringEntry(startIndex, chars, littleEndian, strings)
    return strings
}

internal fun appendUtf16StringEntry(
    startIndex: Int,
    chars: StringBuilder,
    littleEndian: Boolean,
    strings: MutableList<HexStringEntry>
) {
    if (startIndex < 0 || chars.length < MIN_STRING_LENGTH) return
    strings += HexStringEntry(
        offset = startIndex.toLong(),
        value = chars.toString(),
        encoding = if (littleEndian) HexStringEncoding.UTF_16LE else HexStringEncoding.UTF_16BE
    )
}

internal fun ByteArray.utf16CodeUnit(offset: Int, littleEndian: Boolean): Int {
    val first = this[offset].toInt() and 0xFF
    val second = this[offset + 1].toInt() and 0xFF
    return if (littleEndian) first or (second shl 8) else (first shl 8) or second
}

internal data class Utf8CodePoint(
    val value: Int,
    val byteCount: Int
)

internal fun ByteArray.decodeUtf8CodePoint(offset: Int): Utf8CodePoint? {
    if (offset !in indices) return null
    val first = this[offset].toInt() and 0xFF
    return when {
        first <= 0x7F -> Utf8CodePoint(first, 1)
        first in 0xC2..0xDF -> decodeUtf8CodePoint(offset, first, byteCount = 2, minimumValue = 0x80)
        first in 0xE0..0xEF -> decodeUtf8CodePoint(offset, first, byteCount = 3, minimumValue = 0x800)
        first in 0xF0..0xF4 -> decodeUtf8CodePoint(offset, first, byteCount = 4, minimumValue = 0x10000)
        else -> null
    }
}

internal fun ByteArray.decodeUtf8CodePoint(
    offset: Int,
    first: Int,
    byteCount: Int,
    minimumValue: Int
): Utf8CodePoint? {
    if (offset + byteCount > size) return null
    var value = first and (0x7F ushr byteCount)
    for (byteIndex in 1 until byteCount) {
        val next = this[offset + byteIndex].toInt() and 0xFF
        if ((next and 0xC0) != 0x80) return null
        value = (value shl 6) or (next and 0x3F)
    }
    if (value < minimumValue || value in 0xD800..0xDFFF || value > 0x10FFFF) return null
    return Utf8CodePoint(value, byteCount)
}

internal fun Int.isPrintableStringCodePoint(): Boolean = this in PRINTABLE_ASCII_RANGE ||
    (this >= UTF8_PRINTABLE_NON_ASCII_MIN && Character.isDefined(this) && !Character.isISOControl(this))

internal fun calculateEntropyBuckets(randomAccessFile: RandomAccessFile, fileSize: Long): List<HexEntropyBucket> {
    if (fileSize <= 0L) return emptyList()
    val bucketCount = if (fileSize < ENTROPY_BUCKET_COUNT) fileSize.toInt() else ENTROPY_BUCKET_COUNT
    val bucketSize = ((fileSize + bucketCount - 1) / bucketCount).coerceAtLeast(1L)
    val buckets = mutableListOf<HexEntropyBucket>()

    for (bucketIndex in 0 until bucketCount) {
        val startOffset = bucketIndex * bucketSize
        if (startOffset >= fileSize) break
        val endOffset = minOf(fileSize - 1, startOffset + bucketSize - 1)
        val bytesToRead = minOf(ENTROPY_SAMPLE_BYTES.toLong(), endOffset - startOffset + 1).toInt()
        val bytes = randomAccessFile.readAt(startOffset, bytesToRead)
        if (bytes.isNotEmpty()) {
            buckets += HexEntropyBucket(
                startOffset = startOffset,
                endOffset = endOffset,
                entropy = bytes.shannonEntropy()
            )
        }
    }
    return buckets
}

internal fun List<HexEntropyBucket>.toVisualBuckets(): List<HexEntropyVisualBucket> = map { bucket ->
    HexEntropyVisualBucket(
        startOffset = bucket.startOffset,
        endOffset = bucket.endOffset,
        entropy = bucket.entropy,
        normalizedHeight = (bucket.entropy / MAX_SHANNON_ENTROPY).coerceIn(MIN_ENTROPY_BAR_HEIGHT, 1.0).toFloat(),
        level = entropyLevel(bucket.entropy)
    )
}

internal fun entropyLevel(entropy: Double): HexEntropyLevel = when {
    entropy >= HIGH_ENTROPY_THRESHOLD -> HexEntropyLevel.HIGH
    entropy >= MEDIUM_ENTROPY_THRESHOLD -> HexEntropyLevel.MEDIUM
    else -> HexEntropyLevel.LOW
}

internal fun buildAnalysisSignals(
    fileKind: HexFileKind,
    elf: HexElfSummary?,
    dex: HexDexSummary?,
    archive: HexArchiveSummary?,
    entropy: List<HexEntropyBucket>,
    obfuscationFindings: List<HexObfuscationFinding>
): List<HexAnalysisSignal> {
    val signals = mutableListOf<HexAnalysisSignal>()
    entropy.firstOrNull { it.entropy >= HIGH_ENTROPY_THRESHOLD }?.let {
        signals += HexAnalysisSignal(HexAnalysisSignalType.HIGH_ENTROPY_REGION, it.startOffset)
    }
    when (fileKind) {
        HexFileKind.DEX -> {
            signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_FILE)
            if (dex != null) signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_HEADER, 0L)
            if (!dex?.typeEntries.isNullOrEmpty()) {
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_TYPE_IDS, dex?.typeIdsOffset)
            }
            if (!dex?.protoEntries.isNullOrEmpty()) {
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_PROTO_IDS, dex?.protoIdsOffset)
            }
            if (!dex?.fieldEntries.isNullOrEmpty()) {
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_FIELD_IDS, dex?.fieldIdsOffset)
            }
            if (!dex?.methodEntries.isNullOrEmpty()) {
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_METHOD_IDS, dex?.methodIdsOffset)
            }
            if (!dex?.classDefEntries.isNullOrEmpty()) {
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_CLASS_DEFS, dex?.classDefsOffset)
            }
            dex?.classDataMethodEntries?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_CLASS_DATA, entry.classDataOffset)
            }
            dex?.classDataMethodEntries
                ?.firstOrNull { entry -> entry.executionKind == HexDexClassDataMethodExecutionKind.NATIVE }
                ?.let { entry ->
                    signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_NATIVE_METHODS, entry.entryOffset)
                }
            dex?.codeItemEntries?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_CODE_ITEMS, entry.codeOffset)
            }
            dex?.callReferenceEntries?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_CALL_REFERENCES, entry.instructionOffset)
            }
            dex?.stringReferenceEntries?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_STRING_REFERENCES, entry.instructionOffset)
            }
            dex?.fieldReferenceEntries?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_FIELD_REFERENCES, entry.instructionOffset)
            }
            if (!dex?.mapEntries.isNullOrEmpty()) {
                signals += HexAnalysisSignal(HexAnalysisSignalType.DEX_MAP_LIST, dex?.mapOffset)
            }
        }
        HexFileKind.APK -> {
            signals += HexAnalysisSignal(HexAnalysisSignalType.APK_FILE)
            archive?.manifest?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.APK_MANIFEST, entry.localHeaderOffset)
            }
            archive?.dexFiles?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.APK_DEX_FILES, entry.localHeaderOffset)
            }
            archive?.embeddedDexFiles?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.APK_EMBEDDED_DEX_SUMMARIES, entry.localHeaderOffset)
            }
            archive?.nativeLibraries?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.APK_NATIVE_LIBRARIES, entry.localHeaderOffset)
            }
            archive?.zipStructure?.let { structure ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.APK_ZIP_STRUCTURE, structure.eocdOffset)
            }
            archive?.signingBlockEntries?.firstOrNull()?.let { entry ->
                signals += HexAnalysisSignal(HexAnalysisSignalType.APK_SIGNING_BLOCK, entry.blockOffset)
            }
        }
        else -> Unit
    }
    val sectionNames = elf?.sectionNames.orEmpty().toSet()
    if (!elf?.programHeaders.isNullOrEmpty()) signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_PROGRAM_HEADERS)
    elf?.sectionSegmentMappings?.firstOrNull()?.let { mapping ->
        signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_SECTION_SEGMENTS, mapping.sectionFileOffset)
    }
    elf?.sectionEntropyEntries?.let { entries ->
        val evidence = entries.firstOrNull { entry -> entry.level == HexEntropyLevel.HIGH } ?: entries.firstOrNull()
        evidence?.let { entry ->
            signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_SECTION_ENTROPY, entry.fileOffset)
        }
    }
    elf?.hardeningChecks
        ?.firstOrNull { check -> !check.enabled }
        ?.let { check ->
            signals += HexAnalysisSignal(
                type = HexAnalysisSignalType.ELF_HARDENING_WARNING,
                offset = check.evidenceFileOffset
            )
        }
    if (!elf?.gnuPropertyNotes.isNullOrEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_GNU_PROPERTY,
            offset = elf?.gnuPropertyNotes?.firstOrNull()?.noteFileOffset
        )
    }
    if (".init_array" in sectionNames) signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_INIT_ARRAY)
    if (".dynsym" in sectionNames || !elf?.dynamicSymbols.isNullOrEmpty()) {
        signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_DYNAMIC_SYMBOLS)
    }
    if (!elf?.dynamicStringEntries.isNullOrEmpty()) {
        signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_DYNAMIC_DEPENDENCIES)
    }
    if (!elf?.noteEntries.isNullOrEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_NOTES,
            offset = elf?.noteEntries?.firstOrNull()?.noteFileOffset
        )
    }
    elf?.buildId?.let { buildId ->
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_BUILD_ID,
            offset = buildId.descriptionOffset
        )
    }
    if (!elf?.relocations.isNullOrEmpty()) signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_RELOCATIONS)
    if (!elf?.linkageEntries.isNullOrEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_LINKAGE,
            offset = elf?.linkageEntries?.firstOrNull()?.slotFileOffset
                ?: elf?.linkageEntries?.firstOrNull()?.relocationFileOffset
        )
    }
    if (!elf?.dynamicLinkerSteps.isNullOrEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_DYNAMIC_LINKER_STEPS,
            offset = elf?.dynamicLinkerSteps?.firstOrNull()?.evidenceFileOffset
        )
    }
    if (!elf?.riskFindings.isNullOrEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_RISK_FINDINGS,
            offset = elf?.riskFindings?.firstNotNullOfOrNull { finding -> finding.evidenceFileOffset }
        )
    }
    if (!elf?.nativeApiHints.isNullOrEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_NATIVE_API_HINTS,
            offset = elf?.nativeApiHints?.firstNotNullOfOrNull { hint -> hint.evidenceFileOffset }
        )
    }
    if (!elf?.jniRegistrationHints.isNullOrEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.ELF_JNI_REGISTRATION_HINTS,
            offset = elf?.jniRegistrationHints?.firstNotNullOfOrNull { hint -> hint.evidenceFileOffset }
        )
    }
    if (!elf?.jniSymbols.isNullOrEmpty()) signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_JNI_SYMBOLS)
    if (".rodata" in sectionNames) signals += HexAnalysisSignal(HexAnalysisSignalType.ELF_RODATA)
    if (obfuscationFindings.isNotEmpty()) {
        signals += HexAnalysisSignal(
            type = HexAnalysisSignalType.OBFUSCATION_RISK,
            offset = obfuscationFindings.firstNotNullOfOrNull { it.offset }
        )
    }
    return signals
}

internal fun detectObfuscationFindings(
    fileKind: HexFileKind,
    fileSize: Long,
    elf: HexElfSummary?,
    strings: List<HexStringEntry>,
    entropy: List<HexEntropyBucket>
): List<HexObfuscationFinding> {
    if (fileKind != HexFileKind.ELF || elf == null) return emptyList()

    val evidence = buildObfuscationEvidence(elf, strings)
    val findings = mutableListOf<HexObfuscationFinding>()

    fun addMarkerFinding(
        type: HexObfuscationFindingType,
        confidence: HexFindingConfidence,
        vararg keywords: String
    ) {
        val matchedEvidence = evidence.firstOrNull { item ->
            keywords.any { keyword -> item.normalizedValue.contains(keyword) }
        } ?: return
        if (findings.none { it.type == type }) {
            findings += HexObfuscationFinding(
                type = type,
                confidence = confidence,
                evidence = matchedEvidence.value,
                offset = matchedEvidence.offset
            )
        }
    }

    addMarkerFinding(
        HexObfuscationFindingType.OLLVM_MARKER,
        HexFindingConfidence.HIGH,
        "ollvm",
        "obfuscator-llvm",
        "obfuscator llvm"
    )
    addMarkerFinding(
        HexObfuscationFindingType.CONTROL_FLOW_FLATTENING_MARKER,
        HexFindingConfidence.HIGH,
        "ollvm-fla",
        "control flow flattening",
        "control-flow-flattening"
    )
    addMarkerFinding(
        HexObfuscationFindingType.BOGUS_CONTROL_FLOW_MARKER,
        HexFindingConfidence.HIGH,
        "ollvm-bcf",
        "bogus control flow",
        "bogus-control-flow"
    )
    addMarkerFinding(
        HexObfuscationFindingType.INSTRUCTION_SUBSTITUTION_MARKER,
        HexFindingConfidence.HIGH,
        "ollvm-sub",
        "instruction substitution",
        "substitution pass"
    )
    addMarkerFinding(
        HexObfuscationFindingType.ANTI_DEBUG_HEURISTIC,
        HexFindingConfidence.MEDIUM,
        "ptrace",
        "tracerpid",
        "/proc/self/status",
        "/proc/self/task",
        "pr_set_dumpable"
    )
    addMarkerFinding(
        HexObfuscationFindingType.ANTI_INSTRUMENTATION_HEURISTIC,
        HexFindingConfidence.MEDIUM,
        "frida",
        "gum-js-loop",
        "linjector",
        "xposed",
        "substrate"
    )
    addMarkerFinding(
        HexObfuscationFindingType.PROTECTOR_PACKER_MARKER,
        HexFindingConfidence.MEDIUM,
        *ANDROID_PROTECTOR_PACKER_KEYWORDS
    )

    entropy.firstOrNull { it.entropy >= HIGH_ENTROPY_THRESHOLD }?.let { highEntropyBucket ->
        if (fileSize >= MIN_OBFUSCATION_HEURISTIC_FILE_SIZE && strings.size <= LOW_STRING_COUNT_THRESHOLD) {
            findings += HexObfuscationFinding(
                type = HexObfuscationFindingType.STRING_OBFUSCATION_HEURISTIC,
                confidence = HexFindingConfidence.MEDIUM,
                evidence = "0x%08X / %.2f".format(highEntropyBucket.startOffset, highEntropyBucket.entropy),
                offset = highEntropyBucket.startOffset
            )
        }
    }

    if (elf.dynamicSymbols.isEmpty() && ".dynsym" !in elf.sectionNames && ".symtab" !in elf.sectionNames) {
        findings += HexObfuscationFinding(
            type = HexObfuscationFindingType.STRIPPED_SYMBOLS_HEURISTIC,
            confidence = HexFindingConfidence.LOW,
            evidence = elf.machineName
        )
    }

    return findings.take(MAX_OBFUSCATION_FINDINGS)
}

internal fun buildObfuscationEvidence(
    elf: HexElfSummary,
    strings: List<HexStringEntry>
): List<HexObfuscationEvidence> {
    val evidence = mutableListOf<HexObfuscationEvidence>()
    elf.sectionNames.forEach { sectionName ->
        evidence += HexObfuscationEvidence(
            value = sectionName,
            normalizedValue = sectionName.lowercase()
        )
    }
    elf.dynamicSymbols.forEach { symbol ->
        evidence += HexObfuscationEvidence(
            value = symbol.name,
            normalizedValue = symbol.name.lowercase()
        )
    }
    elf.dynamicStringEntries.forEach { entry ->
        evidence += HexObfuscationEvidence(
            value = entry.value,
            normalizedValue = entry.value.lowercase(),
            offset = entry.entryFileOffset
        )
    }
    elf.noteEntries.forEach { note ->
        if (note.name.isNotBlank()) {
            evidence += HexObfuscationEvidence(
                value = note.name,
                normalizedValue = note.name.lowercase(),
                offset = note.noteFileOffset
            )
        }
        note.descriptionText?.let { description ->
            evidence += HexObfuscationEvidence(
                value = description,
                normalizedValue = description.lowercase(),
                offset = note.descriptionOffset
            )
        }
    }
    strings.forEach { stringEntry ->
        evidence += HexObfuscationEvidence(
            value = stringEntry.value,
            normalizedValue = stringEntry.value.lowercase(),
            offset = stringEntry.offset
        )
    }
    return evidence
}

