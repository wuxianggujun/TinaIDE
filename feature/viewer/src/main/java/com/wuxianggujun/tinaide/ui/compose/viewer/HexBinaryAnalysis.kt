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

internal fun ByteArray.regionMatches(offset: Int, expected: ByteArray): Boolean {
    if (offset < 0 || offset + expected.size > size) return false
    return expected.indices.all { index -> this[offset + index] == expected[index] }
}

internal fun ByteArray.shannonEntropy(): Double {
    if (isEmpty()) return 0.0
    val counts = IntArray(256)
    forEach { counts[it.toInt() and 0xFF]++ }
    return counts.asSequence()
        .filter { it > 0 }
        .sumOf { count ->
            val probability = count.toDouble() / size.toDouble()
            -probability * (ln(probability) / ln(2.0))
        }
}

internal fun RandomAccessFile.readAt(offset: Long, byteCount: Int): ByteArray {
    if (byteCount <= 0 || offset < 0L || offset >= length()) return ByteArray(0)
    val safeByteCount = minOf(byteCount.toLong(), length() - offset).toInt()
    val buffer = ByteArray(safeByteCount)
    seek(offset)
    val bytesRead = read(buffer)
    return if (bytesRead <= 0) ByteArray(0) else buffer.copyOf(bytesRead)
}

internal fun ByteArray.readAt(offset: Long, byteCount: Int): ByteArray {
    if (byteCount <= 0 || offset < 0L || offset >= size) return ByteArray(0)
    val startIndex = offset.toInt()
    val endIndex = (offset + byteCount).coerceAtMost(size.toLong()).toInt()
    return copyOfRange(startIndex, endIndex)
}

internal fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    if (maxBytes <= 0) return ByteArray(0)
    val buffer = ByteArray(maxBytes)
    var totalBytesRead = 0
    while (totalBytesRead < maxBytes) {
        val bytesRead = read(buffer, totalBytesRead, maxBytes - totalBytesRead)
        if (bytesRead <= 0) break
        totalBytesRead += bytesRead
    }
    return buffer.copyOf(totalBytesRead)
}

internal fun ByteArray.startsWith(vararg values: Int): Boolean {
    if (size < values.size) return false
    return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
}

internal data class DexUleb128Value(
    val value: Long,
    val nextOffset: Int
)

internal fun ByteArray.readDexUleb128(offset: Int): DexUleb128Value? {
    var cursor = offset
    var result = 0L
    var shift = 0
    repeat(5) {
        if (cursor !in indices) return null
        val byte = this[cursor].toInt() and 0xFF
        result = result or ((byte and 0x7F).toLong() shl shift)
        cursor++
        if ((byte and 0x80) == 0) return DexUleb128Value(result, cursor)
        shift += 7
    }
    return null
}

internal fun ByteArray.dexUleb128Size(): Int? = readDexUleb128(0)?.nextOffset

internal fun ByteArray.findLastZipSignature(signature: Long): Int? {
    if (size < 4) return null
    for (index in size - 4 downTo 0) {
        if (u32(index, HexEndian.LITTLE) == signature) return index
    }
    return null
}

internal fun ByteArray.u16(offset: Int, endian: HexEndian): Int {
    if (offset + 2 > size) return 0
    val b0 = this[offset].toInt() and 0xFF
    val b1 = this[offset + 1].toInt() and 0xFF
    return if (endian == HexEndian.LITTLE) b0 or (b1 shl 8) else (b0 shl 8) or b1
}

internal fun ByteArray.u32(offset: Int, endian: HexEndian): Long {
    if (offset + 4 > size) return 0L
    val values = IntArray(4) { index -> this[offset + index].toInt() and 0xFF }
    return if (endian == HexEndian.LITTLE) {
        values[0].toLong() or
            (values[1].toLong() shl 8) or
            (values[2].toLong() shl 16) or
            (values[3].toLong() shl 24)
    } else {
        (values[0].toLong() shl 24) or
            (values[1].toLong() shl 16) or
            (values[2].toLong() shl 8) or
            values[3].toLong()
    }
}

internal fun ByteArray.u64(offset: Int, endian: HexEndian): Long {
    if (offset + 8 > size) return 0L
    val values = LongArray(8) { index -> this[offset + index].toLong() and 0xFFL }
    return if (endian == HexEndian.LITTLE) {
        values.indices.fold(0L) { result, index -> result or (values[index] shl (index * 8)) }
    } else {
        values.indices.fold(0L) { result, index -> result or (values[index] shl ((7 - index) * 8)) }
    }
}

internal fun ByteArray.readNullTerminatedAscii(offset: Int): String {
    if (offset !in indices) return ""
    var endOffset = offset
    while (endOffset < size && this[endOffset] != 0.toByte()) {
        endOffset++
    }
    return copyOfRange(offset, endOffset).toString(Charsets.US_ASCII)
}

internal fun ByteArray.readElfNoteName(offset: Int, byteCount: Int): String {
    if (byteCount <= 0 || offset !in indices) return ""
    val endLimit = (offset + byteCount).coerceAtMost(size)
    var endOffset = offset
    while (endOffset < endLimit && this[endOffset] != 0.toByte()) {
        endOffset++
    }
    return copyOfRange(offset, endOffset).toString(Charsets.US_ASCII)
}

internal fun ByteArray.readElfNoteDescription(offset: Int, byteCount: Int): ByteArray {
    if (byteCount <= 0 || offset !in indices) return ByteArray(0)
    val safeByteCount = byteCount.coerceAtMost(MAX_ELF_NOTE_DESCRIPTION_BYTES)
    val endOffset = (offset + safeByteCount).coerceAtMost(size)
    return copyOfRange(offset, endOffset)
}

internal fun readElfGnuPropertyEntries(
    noteFileOffset: Long,
    descriptionOffset: Long,
    descriptionBytes: ByteArray,
    endian: HexEndian,
    machine: Int
): List<HexElfNotePropertyEntry> {
    if (descriptionBytes.size < ELF_GNU_PROPERTY_HEADER_SIZE) return emptyList()
    val entries = mutableListOf<HexElfNotePropertyEntry>()
    var cursor = 0
    while (cursor + ELF_GNU_PROPERTY_HEADER_SIZE <= descriptionBytes.size && entries.size < MAX_ELF_NOTE_PROPERTIES) {
        val propertyType = descriptionBytes.u32(cursor, endian)
        val propertyDataSize = descriptionBytes.u32(cursor + 4, endian)
        val propertyDataStart = cursor + ELF_GNU_PROPERTY_HEADER_SIZE
        val propertyDataEnd = propertyDataStart + propertyDataSize.coerceToInt()
        if (propertyDataEnd > descriptionBytes.size) break

        val propertyBytes = descriptionBytes.readAt(propertyDataStart.toLong(), propertyDataSize.coerceToInt())
        val features = elfGnuPropertyFeatures(
            machine = machine,
            propertyType = propertyType,
            propertyBytes = propertyBytes,
            endian = endian
        )
        entries += HexElfNotePropertyEntry(
            index = entries.size,
            type = propertyType,
            typeName = elfGnuPropertyTypeName(propertyType),
            value = propertyBytes.readUnsignedLong(endian),
            valueHex = propertyBytes.readUnsignedLong(endian)
                .toString(16)
                .padStart((propertyDataSize.coerceAtMost(8L) * 2).toInt(), '0'),
            propertyOffset = noteFileOffset + cursor.toLong(),
            dataOffset = descriptionOffset + propertyDataStart.toLong(),
            dataSize = propertyDataSize,
            features = features
        )
        val nextCursor = propertyDataEnd.toLong().alignElfPropertyFieldSize()
        if (nextCursor <= cursor.toLong()) break
        cursor = nextCursor.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return entries
}

internal fun elfGnuPropertyTypeName(type: Long): String = when (type) {
    ELF_GNU_PROPERTY_X86_FEATURE_1_AND -> "X86_FEATURE_1_AND"
    ELF_GNU_PROPERTY_AARCH64_FEATURE_1_AND -> "AARCH64_FEATURE_1_AND"
    else -> "0x%X".format(type)
}

internal fun elfGnuPropertyFeatures(
    machine: Int,
    propertyType: Long,
    propertyBytes: ByteArray,
    endian: HexEndian
): List<HexElfNotePropertyFeature> {
    if (propertyBytes.isEmpty()) return emptyList()
    val value = propertyBytes.readUnsignedLong(endian)
    return when (propertyType) {
        ELF_GNU_PROPERTY_X86_FEATURE_1_AND -> if (machine == ELF_MACHINE_X86_64) {
            buildList {
                if (value and ELF_GNU_PROPERTY_X86_FEATURE_1_IBT != 0L) {
                    add(HexElfNotePropertyFeature.X86_IBT)
                }
                if (value and ELF_GNU_PROPERTY_X86_FEATURE_1_SHSTK != 0L) {
                    add(HexElfNotePropertyFeature.X86_SHSTK)
                }
            }
        } else {
            emptyList()
        }
        ELF_GNU_PROPERTY_AARCH64_FEATURE_1_AND -> if (machine == ELF_MACHINE_AARCH64) {
            buildList {
                if (value and ELF_GNU_PROPERTY_AARCH64_FEATURE_1_BTI != 0L) {
                    add(HexElfNotePropertyFeature.AARCH64_BTI)
                }
                if (value and ELF_GNU_PROPERTY_AARCH64_FEATURE_1_PAC != 0L) {
                    add(HexElfNotePropertyFeature.AARCH64_PAC)
                }
            }
        } else {
            emptyList()
        }
        else -> emptyList()
    }
}

internal fun ByteArray.toLowerHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

internal fun ByteArray.toUpperHexByteString(): String = joinToString(separator = " ") { byte ->
    "%02X".format(byte.toInt() and 0xFF)
}

internal fun ByteArray.toPrintableAsciiStringOrNull(): String? {
    if (isEmpty()) return null
    if (!all { byte -> (byte.toInt() and 0xFF) in PRINTABLE_ASCII_RANGE }) return null
    return toString(Charsets.US_ASCII)
}

internal fun Long.alignElfNoteFieldSize(): Long {
    if (this <= 0L) return 0L
    return ((this + ELF_NOTE_ALIGNMENT - 1) / ELF_NOTE_ALIGNMENT) * ELF_NOTE_ALIGNMENT
}

internal fun Long.alignElfPropertyFieldSize(): Long {
    if (this <= 0L) return 0L
    return ((this + ELF_GNU_PROPERTY_ALIGNMENT - 1) / ELF_GNU_PROPERTY_ALIGNMENT) * ELF_GNU_PROPERTY_ALIGNMENT
}

internal fun ByteArray.readUnsignedLong(endian: HexEndian): Long = when {
    isEmpty() -> 0L
    size >= Long.SIZE_BYTES -> u64(0, endian)
    size >= Int.SIZE_BYTES -> u32(0, endian)
    size >= Short.SIZE_BYTES -> u16(0, endian).toLong()
    else -> first().toLong() and 0xFFL
}

internal fun isElfBuildIdNote(sectionName: String, noteName: String, type: Long): Boolean = sectionName.contains(
    "build-id",
    ignoreCase = true,
) ||
    (noteName == ELF_NOTE_NAME_GNU && type == ELF_NOTE_TYPE_GNU_BUILD_ID)

internal fun isElfGnuPropertyNote(noteName: String, type: Long): Boolean = noteName == ELF_NOTE_NAME_GNU && type == ELF_NOTE_TYPE_GNU_PROPERTY

internal fun elfMachineName(machine: Int): String = when (machine) {
    ELF_MACHINE_386 -> "x86"
    ELF_MACHINE_ARM -> "ARM"
    ELF_MACHINE_X86_64 -> "x86_64"
    ELF_MACHINE_AARCH64 -> "AArch64"
    ELF_MACHINE_RISCV -> "RISC-V"
    else -> "0x%X".format(machine)
}

internal val PRINTABLE_ASCII_RANGE = 0x20..0x7E
internal val DEX_MAP_ID_TYPES = setOf(
    DEX_MAP_TYPE_STRING_ID_ITEM,
    DEX_MAP_TYPE_TYPE_ID_ITEM,
    DEX_MAP_TYPE_PROTO_ID_ITEM,
    DEX_MAP_TYPE_FIELD_ID_ITEM,
    DEX_MAP_TYPE_METHOD_ID_ITEM,
    DEX_MAP_TYPE_CLASS_DEF_ITEM
)
internal val NATIVE_DYNAMIC_LOADING_SYMBOLS = setOf("dlopen", "android_dlopen_ext", "dlsym", "dlclose", "dlerror")
internal val NATIVE_MEMORY_PROTECTION_SYMBOLS = setOf("mmap", "mmap64", "mprotect", "munmap", "mremap")
internal val NATIVE_PROCESS_CONTROL_SYMBOLS = setOf("ptrace", "prctl", "fork", "vfork", "execve", "kill", "tgkill", "syscall")
internal val NATIVE_FILE_IO_SYMBOLS = setOf(
    "open",
    "openat",
    "fopen",
    "fopen64",
    "read",
    "write",
    "pread",
    "pwrite",
    "access",
    "stat",
    "stat64",
    "fstat",
    "lstat",
    "unlink",
    "remove",
    "rename",
    "opendir",
    "readdir"
)
internal val NATIVE_NETWORK_SYMBOLS = setOf(
    "socket",
    "connect",
    "bind",
    "listen",
    "accept",
    "send",
    "sendto",
    "recv",
    "recvfrom",
    "getaddrinfo",
    "inet_addr"
)
internal val NATIVE_THREADING_SYMBOLS = setOf(
    "pthread_create",
    "pthread_join",
    "pthread_mutex_lock",
    "pthread_mutex_unlock",
    "pthread_once",
    "clone"
)
internal val NATIVE_LOGGING_SYMBOLS = setOf(
    "__android_log_print",
    "android_log_print",
    "printf",
    "fprintf",
    "snprintf",
    "puts"
)
internal val NATIVE_CRYPTO_SYMBOL_PREFIXES = listOf(
    "AES_",
    "RSA_",
    "EVP_",
    "SHA",
    "MD5",
    "HMAC",
    "SSL_",
    "TLS_",
    "CRYPTO_"
)
internal val ANDROID_PROTECTOR_PACKER_KEYWORDS = arrayOf(
    "360jiagu",
    "jiagu",
    "libjiagu",
    "bangcle",
    "ijiami",
    "secneo",
    "legu",
    "dexprotector",
    "apkprotect",
    "libshell",
    "libshella",
    "libprotect",
    "libdexhelper",
    "upx",
    "vmprotect",
    "arxan"
)
internal const val ELF_MACHINE_386 = 0x03
internal const val ELF_MACHINE_ARM = 0x28
internal const val ELF_MACHINE_X86_64 = 0x3E
internal const val ELF_MACHINE_AARCH64 = 0xB7
internal const val ELF_MACHINE_RISCV = 0xF3
internal const val ELF_AARCH64_PLT_RESOLVER_STUB_SIZE = 32
internal const val ELF_AARCH64_PLT_ENTRY_SIZE = 16
internal const val ELF_X86_64_PLT_RESOLVER_STUB_SIZE = 16
internal const val ELF_X86_64_PLT_ENTRY_SIZE = 16
internal const val AARCH64_ADRP_X16_MASK = 0x9F00001FL
internal const val AARCH64_ADRP_X16_VALUE = 0x90000010L
internal const val AARCH64_LDR_X17_FROM_X16_MASK = 0xFFC003FFL
internal const val AARCH64_LDR_X17_FROM_X16_VALUE = 0xF9400211L
internal const val AARCH64_ADD_X16_FROM_X16_MASK = 0xFFC003FFL
internal const val AARCH64_ADD_X16_FROM_X16_VALUE = 0x91000210L
internal const val AARCH64_BR_X17_VALUE = 0xD61F0220L
internal const val UTF8_PRINTABLE_NON_ASCII_MIN = 0xA0
internal const val ELF_IDENT_SIZE = 16
internal const val ELF_CLASS_OFFSET = 4
internal const val ELF_DATA_OFFSET = 5
internal const val ELF_CLASS_32 = 1
internal const val ELF_CLASS_64 = 2
internal const val ELF_DATA_LITTLE = 1
internal const val ELF_DATA_BIG = 2
internal const val ELF_TYPE_DYN = 3
internal const val ELF32_HEADER_SIZE = 52
internal const val ELF64_HEADER_SIZE = 64
internal const val ELF_PROGRAM_TYPE_NULL = 0L
internal const val ELF_PROGRAM_TYPE_LOAD = 1
internal const val ELF_PROGRAM_TYPE_DYNAMIC = 2L
internal const val ELF_PROGRAM_TYPE_INTERP = 3L
internal const val ELF_PROGRAM_TYPE_NOTE = 4L
internal const val ELF_PROGRAM_TYPE_PHDR = 6L
internal const val ELF_PROGRAM_TYPE_TLS = 7L
internal const val ELF_PROGRAM_TYPE_GNU_EH_FRAME = 0x6474E550L
internal const val ELF_PROGRAM_TYPE_GNU_STACK = 0x6474E551L
internal const val ELF_PROGRAM_TYPE_GNU_RELRO = 0x6474E552L
internal const val ELF_PROGRAM_FLAG_EXECUTE = 0x1
internal const val ELF_PROGRAM_FLAG_WRITE = 0x2
internal const val ELF_PROGRAM_FLAG_READ = 0x4
internal const val ELF_SECTION_FLAG_WRITE = 0x1L
internal const val ELF_SECTION_FLAG_ALLOC = 0x2L
internal const val ELF_SECTION_FLAG_EXECINSTR = 0x4L
internal const val ELF_SECTION_TYPE_SYMBOL_TABLE = 2
internal const val ELF_SECTION_TYPE_STRING_TABLE = 3
internal const val ELF_SECTION_TYPE_RELOCATION_WITH_ADDEND = 4
internal const val ELF_SECTION_TYPE_DYNAMIC = 6
internal const val ELF_SECTION_TYPE_NOTE = 7
internal const val ELF_SECTION_TYPE_NOBITS = 8
internal const val ELF_SECTION_TYPE_RELOCATION = 9
internal const val ELF_SECTION_TYPE_DYNAMIC_SYMBOLS = 11
internal const val ELF_SECTION_TYPE_INIT_ARRAY = 14
internal const val ELF_NOTE_ALIGNMENT = 4L
internal const val ELF_GNU_PROPERTY_ALIGNMENT = 8L
internal const val ELF_GNU_PROPERTY_HEADER_SIZE = 8
internal const val ELF_NOTE_HEADER_SIZE = 12
internal const val ELF_NOTE_TYPE_GNU_PROPERTY = 5L
internal const val ELF_NOTE_TYPE_GNU_BUILD_ID = 3L
internal const val ELF_NOTE_NAME_GNU = "GNU"
internal const val ELF_GNU_PROPERTY_X86_FEATURE_1_AND = 0xC0000002L
internal const val ELF_GNU_PROPERTY_X86_FEATURE_1_IBT = 0x1L
internal const val ELF_GNU_PROPERTY_X86_FEATURE_1_SHSTK = 0x2L
internal const val ELF_GNU_PROPERTY_AARCH64_FEATURE_1_AND = 0xC0000000L
internal const val ELF_GNU_PROPERTY_AARCH64_FEATURE_1_BTI = 0x1L
internal const val ELF_GNU_PROPERTY_AARCH64_FEATURE_1_PAC = 0x2L
internal const val MAX_ELF_NOTE_PROPERTIES = 64
internal const val ELF_DYNAMIC_TAG_NULL = 0L
internal const val ELF_DYNAMIC_TAG_NEEDED = 1L
internal const val ELF_DYNAMIC_TAG_SONAME = 14L
internal const val ELF_DYNAMIC_TAG_RPATH = 15L
internal const val ELF_DYNAMIC_TAG_BIND_NOW = 24L
internal const val ELF_DYNAMIC_TAG_RUNPATH = 29L
internal const val ELF_DYNAMIC_TAG_FLAGS = 30L
internal const val ELF_DYNAMIC_TAG_FLAGS_1 = 0x6FFFFFFBL
internal const val ELF_DYNAMIC_FLAG_BIND_NOW = 0x8L
internal const val ELF_DYNAMIC_FLAG_1_NOW = 0x1L
internal const val ELF_SYMBOL_SECTION_UNDEFINED = 0
internal const val ELF_SYMBOL_BIND_LOCAL = 0
internal const val ELF_SYMBOL_BIND_GLOBAL = 1
internal const val ELF_SYMBOL_BIND_WEAK = 2
internal const val ELF_SYMBOL_TYPE_NOTYPE = 0
internal const val ELF_SYMBOL_TYPE_OBJECT = 1
internal const val ELF_SYMBOL_TYPE_FUNC = 2
internal const val ELF_SYMBOL_TYPE_SECTION = 3
internal const val ELF_SYMBOL_TYPE_FILE = 4
internal const val ELF_SYMBOL_TYPE_TLS = 6
internal const val ELF32_SYMBOL_ENTRY_SIZE = 16
internal const val ELF64_SYMBOL_ENTRY_SIZE = 24
internal const val ELF32_DYNAMIC_ENTRY_SIZE = 8
internal const val ELF64_DYNAMIC_ENTRY_SIZE = 16
internal const val ELF32_RELOCATION_ENTRY_SIZE = 8
internal const val ELF64_RELOCATION_ENTRY_SIZE = 16
internal const val ELF32_RELOCATION_ADDEND_ENTRY_SIZE = 12
internal const val ELF64_RELOCATION_ADDEND_ENTRY_SIZE = 24
internal const val ELF32_RELOCATION_SYMBOL_SHIFT = 8
internal const val ELF64_RELOCATION_SYMBOL_SHIFT = 32
internal const val ELF32_RELOCATION_TYPE_MASK = 0xFFL
internal const val ELF64_RELOCATION_TYPE_MASK = 0xFFFFFFFFL
internal const val ELF_HEADER_READ_LIMIT = 512
internal const val FINGERPRINT_BUFFER_BYTES = 64 * 1024
internal const val BYTE_VALUE_COUNT = 256
internal const val MAX_BYTE_FREQUENCY_ENTRIES = 12
internal const val MIN_REPEATED_BYTE_RUN_LENGTH = 16L
internal const val MAX_REPEATED_BYTE_RUN_ENTRIES = 16
internal const val MAX_REPEATED_BYTE_RUN_CANDIDATES = 64
internal const val MAX_MAGIC_SIGNATURE_MATCHES = 64
internal const val ASCII_SPACE = 0x20
internal const val ASCII_DELETE = 0x7F
internal const val MAX_ELF_PROGRAM_HEADERS = 128
internal const val MAX_ELF_SECTION_HEADERS = 256
internal const val MAX_ELF_SECTION_SEGMENT_MAPPINGS = 256
internal const val MAX_ELF_SECTION_ENTROPY_ENTRIES = 256
internal const val MAX_ELF_SYMBOLS = 512
internal const val MAX_ELF_DYNAMIC_ENTRIES = 128
internal const val MAX_ELF_INIT_ARRAY_ENTRIES = 128
internal const val MAX_ELF_NOTES = 128
internal const val MAX_ELF_NOTE_SECTION_BYTES = 256 * 1024
internal const val MAX_ELF_NOTE_DESCRIPTION_BYTES = 64
internal const val MAX_ELF_RELOCATIONS = 512
internal const val MAX_ELF_LINKAGE_ENTRIES = 512
internal const val MAX_ELF_DYNAMIC_LINKER_STEPS = 32
internal const val MAX_ELF_RISK_FINDINGS = 128
internal const val MAX_ELF_NATIVE_API_HINTS = 128
internal const val MAX_ELF_JNI_HINTS = 128
internal const val DYNAMIC_LINKER_STEP_DETAIL_LIMIT = 3
internal const val MAX_ELF_STRING_TABLE_BYTES = 64 * 1024
internal const val DEX_HEADER_SIZE = 0x70
internal const val DEX_STRING_ID_ENTRY_SIZE = 4
internal const val DEX_TYPE_ID_ENTRY_SIZE = 4
internal const val DEX_PROTO_ID_ENTRY_SIZE = 12
internal const val DEX_FIELD_ID_ENTRY_SIZE = 8
internal const val DEX_METHOD_ID_ENTRY_SIZE = 8
internal const val DEX_CLASS_DEF_ENTRY_SIZE = 32
internal const val DEX_TYPE_ITEM_ENTRY_SIZE = 2
internal const val DEX_MAP_ENTRY_SIZE = 12
internal const val DEX_CODE_ITEM_HEADER_SIZE = 16
internal const val DEX_CODE_UNIT_SIZE = 2
internal const val MAX_DEX_STRING_ENTRIES = 128
internal const val MAX_DEX_TYPE_ENTRIES = 128
internal const val MAX_DEX_PROTO_ENTRIES = 128
internal const val MAX_DEX_FIELD_ENTRIES = 128
internal const val MAX_DEX_METHOD_ENTRIES = 128
internal const val MAX_DEX_CLASS_DEF_ENTRIES = 128
internal const val MAX_DEX_CLASS_DATA_METHOD_ENTRIES = 256
internal const val MAX_DEX_CLASS_DATA_METHODS_PER_CLASS = 128
internal const val MAX_DEX_CLASS_DATA_FIELDS_TO_SKIP = 256L
internal const val MAX_DEX_CLASS_DATA_BYTES = 8 * 1024
internal const val MAX_DEX_CODE_ITEM_ENTRIES = 256
internal const val MAX_DEX_CODE_ITEM_PREVIEW_UNITS = 8
internal const val MAX_DEX_CALL_REFERENCE_ENTRIES = 512
internal const val MAX_DEX_CALL_SCAN_CODE_UNITS = 4096
internal const val MAX_DEX_STRING_REFERENCE_ENTRIES = 512
internal const val MAX_DEX_FIELD_REFERENCE_ENTRIES = 512
internal const val MAX_DEX_DATA_REFERENCE_SCAN_CODE_UNITS = 4096
internal const val MAX_DEX_PROTO_PARAMETERS = 32
internal const val MAX_DEX_STRING_DATA_BYTES = 256
internal const val MAX_DEX_MAP_ENTRIES = 128
internal const val DEX_NO_INDEX = 0xFFFFFFFFL
internal const val DEX_MAP_TYPE_HEADER_ITEM = 0x0000
internal const val DEX_MAP_TYPE_STRING_ID_ITEM = 0x0001
internal const val DEX_MAP_TYPE_TYPE_ID_ITEM = 0x0002
internal const val DEX_MAP_TYPE_PROTO_ID_ITEM = 0x0003
internal const val DEX_MAP_TYPE_FIELD_ID_ITEM = 0x0004
internal const val DEX_MAP_TYPE_METHOD_ID_ITEM = 0x0005
internal const val DEX_MAP_TYPE_CLASS_DEF_ITEM = 0x0006
internal const val DEX_MAP_TYPE_MAP_LIST = 0x1000
internal const val DEX_MAP_TYPE_TYPE_LIST = 0x1001
internal const val DEX_MAP_TYPE_ANNOTATION_SET_REF_LIST = 0x1002
internal const val DEX_MAP_TYPE_ANNOTATION_SET_ITEM = 0x1003
internal const val DEX_MAP_TYPE_CLASS_DATA_ITEM = 0x2000
internal const val DEX_MAP_TYPE_CODE_ITEM = 0x2001
internal const val DEX_MAP_TYPE_STRING_DATA_ITEM = 0x2002
internal const val DEX_MAP_TYPE_DEBUG_INFO_ITEM = 0x2003
internal const val DEX_MAP_TYPE_ANNOTATION_ITEM = 0x2004
internal const val DEX_MAP_TYPE_ENCODED_ARRAY_ITEM = 0x2005
internal const val DEX_MAP_TYPE_ANNOTATIONS_DIRECTORY_ITEM = 0x2006
internal const val DEX_ACCESS_FLAG_NATIVE = 0x0100L
internal const val DEX_ACCESS_FLAG_ABSTRACT = 0x0400L
internal const val ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50L
internal const val ZIP_LOCAL_FILE_HEADER_SIGNATURE = 0x04034B50L
internal const val ZIP_CENTRAL_DIRECTORY_SIGNATURE = 0x02014B50L
internal const val ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064B50L
internal const val ZIP_END_OF_CENTRAL_DIRECTORY_SIZE = 22
internal const val ZIP_LOCAL_FILE_HEADER_SIZE = 30
internal const val ZIP_CENTRAL_DIRECTORY_HEADER_SIZE = 46
internal const val ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE = 20
internal const val ZIP_MAX_EOCD_SCAN_BYTES = 65_557
internal const val ZIP_GENERAL_PURPOSE_DATA_DESCRIPTOR_FLAG = 0x0008
internal const val ZIP_COMPRESSION_METHOD_STORED = 0
internal const val ZIP_COMPRESSION_METHOD_DEFLATED = 8
internal const val APK_NATIVE_LIBRARY_PAGE_ALIGNMENT = 4096L
internal const val ANDROID_RES_STRING_POOL_TYPE = 0x0001
internal const val ANDROID_RES_TABLE_TYPE = 0x0002
internal const val ANDROID_RES_XML_TYPE = 0x0003
internal const val ANDROID_RES_TABLE_PACKAGE_TYPE = 0x0200
internal const val ANDROID_RES_TABLE_TYPE_TYPE = 0x0201
internal const val ANDROID_RES_TABLE_TYPE_SPEC_TYPE = 0x0202
internal const val ANDROID_RES_XML_START_ELEMENT_TYPE = 0x0102
internal const val ANDROID_CHUNK_HEADER_SIZE = 8
internal const val ANDROID_RESOURCE_TABLE_HEADER_SIZE = 12
internal const val ANDROID_STRING_POOL_HEADER_SIZE = 28
internal const val ANDROID_STRING_POOL_UTF8_FLAG = 0x00000100L
internal const val ANDROID_RESOURCE_PACKAGE_HEADER_SIZE = 288
internal const val ANDROID_RESOURCE_PACKAGE_NAME_CHARS = 128
internal const val ANDROID_XML_START_ELEMENT_HEADER_SIZE = 36
internal const val ANDROID_XML_ATTRIBUTE_EXTENSION_OFFSET = 16
internal const val ANDROID_XML_ATTRIBUTE_SIZE = 20
internal const val ANDROID_TYPED_VALUE_STRING = 0x03
internal const val ANDROID_NO_INDEX = 0xFFFFFFFFL
internal const val ANDROID_MANIFEST_PACKAGE_ATTRIBUTE = "package"
internal const val ANDROID_MANIFEST_NAME_ATTRIBUTE = "name"
internal const val APK_SIGNING_BLOCK_SIZE_FIELD_SIZE = 8
internal const val APK_SIGNING_BLOCK_ID_SIZE = 4
internal const val APK_SIGNING_BLOCK_PAIR_HEADER_SIZE = APK_SIGNING_BLOCK_SIZE_FIELD_SIZE + APK_SIGNING_BLOCK_ID_SIZE
internal const val APK_SIGNING_BLOCK_FOOTER_SIZE = 24
internal const val APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871AL
internal const val APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xF05368C0L
internal const val APK_SIGNATURE_VERITY_PADDING_BLOCK_ID = 0x42726577L
internal const val MAX_APK_SIGNING_BLOCK_BYTES = 16 * 1024 * 1024L
internal const val MAX_ARCHIVE_ENTRIES = 512
internal const val MAX_ARCHIVE_DEX_SUMMARIES = 8
internal const val MAX_ARCHIVE_NATIVE_LIBRARY_SUMMARIES = 16
internal const val MAX_ARCHIVE_SIGNING_BLOCK_ENTRIES = 32
internal const val MAX_ARCHIVE_DEX_ANALYSIS_BYTES = 2 * 1024 * 1024
internal const val MAX_ARCHIVE_NATIVE_ANALYSIS_BYTES = 512 * 1024
internal const val MAX_ARCHIVE_MANIFEST_ANALYSIS_BYTES = 512 * 1024
internal const val MAX_ARCHIVE_RESOURCES_ANALYSIS_BYTES = 2 * 1024 * 1024
internal const val MAX_ARCHIVE_NATIVE_OBFUSCATION_MARKERS = 8
internal const val MAX_ARCHIVE_MANIFEST_STRINGS = 512
internal const val MAX_ARCHIVE_MANIFEST_PERMISSIONS = 64
internal const val MAX_STRING_SCAN_BYTES = 8 * 1024 * 1024
internal const val MAX_STRING_RESULTS = 200
internal const val MIN_STRING_LENGTH = 4
internal const val ENTROPY_BUCKET_COUNT = 32
internal const val ENTROPY_SAMPLE_BYTES = 64 * 1024
internal const val MAX_SHANNON_ENTROPY = 8.0
internal const val HIGH_ENTROPY_THRESHOLD = 7.5
internal const val MEDIUM_ENTROPY_THRESHOLD = 5.0
internal const val MIN_ENTROPY_BAR_HEIGHT = 0.12
internal const val LOW_STRING_COUNT_THRESHOLD = 3
internal const val MIN_OBFUSCATION_HEURISTIC_FILE_SIZE = 4096
internal const val MAX_OBFUSCATION_FINDINGS = 8

internal val MAGIC_SIGNATURE_DEFINITIONS = listOf(
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.ELF,
        bytes = intArrayOf(0x7F, 'E'.code, 'L'.code, 'F'.code)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.DEX,
        bytes = intArrayOf('d'.code, 'e'.code, 'x'.code, '\n'.code)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.ZIP_LOCAL_FILE,
        bytes = intArrayOf('P'.code, 'K'.code, 0x03, 0x04)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.ZIP_CENTRAL_DIRECTORY,
        bytes = intArrayOf('P'.code, 'K'.code, 0x01, 0x02)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.ZIP_EOCD,
        bytes = intArrayOf('P'.code, 'K'.code, 0x05, 0x06)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.PNG,
        bytes = intArrayOf(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.JPEG,
        bytes = intArrayOf(0xFF, 0xD8, 0xFF)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.ANDROID_RESOURCES,
        bytes = intArrayOf(0x02, 0x00, 0x0C, 0x00)
    ),
    HexMagicSignatureDefinition(
        kind = HexMagicSignatureKind.SQLITE,
        bytes = "SQLite format 3\u0000"
            .toByteArray(Charsets.US_ASCII)
            .map { it.toInt() and 0xFF }
            .toIntArray()
    )
)

internal val MAX_MAGIC_SIGNATURE_LENGTH = MAGIC_SIGNATURE_DEFINITIONS.maxOf { it.bytes.size }
internal val APK_SIGNING_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
internal val ANDROID_MANIFEST_PERMISSION_ELEMENTS = setOf("uses-permission", "uses-permission-sdk-23")
