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

internal fun parseDexSummary(
    randomAccessFile: RandomAccessFile,
    fileSize: Long,
    header: ByteArray
): HexDexSummary? = parseDexSummary(
    fileSize = fileSize,
    header = header,
    readAt = { offset, byteCount -> randomAccessFile.readAt(offset, byteCount) }
)

internal fun parseDexSummary(
    bytes: ByteArray
): HexDexSummary? = parseDexSummary(
    fileSize = bytes.size.toLong(),
    header = bytes.take(DEX_HEADER_SIZE).toByteArray(),
    readAt = { offset, byteCount -> bytes.readAt(offset, byteCount) }
)

internal fun parseDexSummary(
    fileSize: Long,
    header: ByteArray,
    readAt: (Long, Int) -> ByteArray
): HexDexSummary? {
    val fullHeader = if (header.size >= DEX_HEADER_SIZE) header else readAt(0L, DEX_HEADER_SIZE)
    if (fullHeader.size < DEX_HEADER_SIZE || !fullHeader.startsWith('d'.code, 'e'.code, 'x'.code, '\n'.code)) {
        return null
    }

    val version = fullHeader.copyOfRange(4, 7).toString(Charsets.US_ASCII)
    val dexSummary = HexDexSummary(
        version = version,
        checksum = fullHeader.u32(8, HexEndian.LITTLE),
        signatureHex = fullHeader.copyOfRange(12, 32).toLowerHexString(),
        fileSizeFromHeader = fullHeader.u32(32, HexEndian.LITTLE),
        headerSize = fullHeader.u32(36, HexEndian.LITTLE),
        endianTag = fullHeader.u32(40, HexEndian.LITTLE),
        mapOffset = fullHeader.u32(52, HexEndian.LITTLE),
        stringIdsSize = fullHeader.u32(56, HexEndian.LITTLE).coerceToInt(),
        stringIdsOffset = fullHeader.u32(60, HexEndian.LITTLE),
        typeIdsSize = fullHeader.u32(64, HexEndian.LITTLE).coerceToInt(),
        typeIdsOffset = fullHeader.u32(68, HexEndian.LITTLE),
        protoIdsSize = fullHeader.u32(72, HexEndian.LITTLE).coerceToInt(),
        protoIdsOffset = fullHeader.u32(76, HexEndian.LITTLE),
        fieldIdsSize = fullHeader.u32(80, HexEndian.LITTLE).coerceToInt(),
        fieldIdsOffset = fullHeader.u32(84, HexEndian.LITTLE),
        methodIdsSize = fullHeader.u32(88, HexEndian.LITTLE).coerceToInt(),
        methodIdsOffset = fullHeader.u32(92, HexEndian.LITTLE),
        classDefsSize = fullHeader.u32(96, HexEndian.LITTLE).coerceToInt(),
        classDefsOffset = fullHeader.u32(100, HexEndian.LITTLE),
        dataSize = fullHeader.u32(104, HexEndian.LITTLE),
        dataOffset = fullHeader.u32(108, HexEndian.LITTLE)
    )

    val stringEntries = readDexStringEntries(readAt, fileSize, dexSummary)
    val typeEntries = readDexTypeEntries(readAt, fileSize, dexSummary, stringEntries)
    val protoEntries = readDexProtoEntries(readAt, fileSize, dexSummary, stringEntries, typeEntries)
    val fieldEntries = readDexFieldEntries(readAt, fileSize, dexSummary, stringEntries, typeEntries)
    val methodEntries = readDexMethodEntries(readAt, fileSize, dexSummary, stringEntries, typeEntries, protoEntries)
    val classDefEntries = readDexClassDefEntries(readAt, fileSize, dexSummary, stringEntries, typeEntries)
    val classDataMethodEntries = readDexClassDataMethodEntries(readAt, fileSize, classDefEntries, methodEntries)
    val codeItemEntries = readDexCodeItemEntries(readAt, fileSize, classDataMethodEntries)
    val dataReferenceEntries = readDexDataReferenceEntries(
        readAt = readAt,
        fileSize = fileSize,
        classDataMethodEntries = classDataMethodEntries,
        stringEntries = stringEntries,
        fieldEntries = fieldEntries
    )

    return dexSummary.copy(
        stringEntries = stringEntries,
        typeEntries = typeEntries,
        protoEntries = protoEntries,
        fieldEntries = fieldEntries,
        methodEntries = methodEntries,
        classDefEntries = classDefEntries,
        classDataMethodEntries = classDataMethodEntries,
        codeItemEntries = codeItemEntries,
        callReferenceEntries = readDexCallReferenceEntries(
            readAt = readAt,
            fileSize = fileSize,
            classDataMethodEntries = classDataMethodEntries,
            methodEntries = methodEntries
        ),
        stringReferenceEntries = dataReferenceEntries.stringReferenceEntries,
        fieldReferenceEntries = dataReferenceEntries.fieldReferenceEntries,
        mapEntries = readDexMapEntries(readAt, fileSize, dexSummary.mapOffset)
    )
}

internal fun readDexStringEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    dex: HexDexSummary
): List<HexDexStringEntry> {
    if (dex.stringIdsSize <= 0 || dex.stringIdsOffset <= 0L || dex.stringIdsOffset >= fileSize) return emptyList()
    return (0 until minOf(dex.stringIdsSize, MAX_DEX_STRING_ENTRIES)).mapNotNull { index ->
        val stringIdOffset = dex.stringIdsOffset + index * DEX_STRING_ID_ENTRY_SIZE
        val stringIdBytes = readAt(stringIdOffset, DEX_STRING_ID_ENTRY_SIZE)
        if (stringIdBytes.size < DEX_STRING_ID_ENTRY_SIZE) return@mapNotNull null

        val dataOffset = stringIdBytes.u32(0, HexEndian.LITTLE)
        if (dataOffset <= 0L || dataOffset >= fileSize) return@mapNotNull null

        HexDexStringEntry(
            index = index,
            stringIdOffset = stringIdOffset,
            dataOffset = dataOffset,
            value = readDexStringValue(readAt, dataOffset, fileSize)
        )
    }
}

internal fun readDexStringValue(
    readAt: (Long, Int) -> ByteArray,
    dataOffset: Long,
    fileSize: Long
): String {
    val bytesToRead = minOf(MAX_DEX_STRING_DATA_BYTES.toLong(), fileSize - dataOffset).toInt()
    val bytes = readAt(dataOffset, bytesToRead)
    val valueStart = bytes.dexUleb128Size() ?: return ""
    val valueEnd = generateSequence(valueStart) { index -> index + 1 }
        .takeWhile { index -> index < bytes.size && bytes[index] != 0.toByte() }
        .lastOrNull()
        ?.plus(1)
        ?: valueStart
    if (valueEnd <= valueStart) return ""
    return bytes.copyOfRange(valueStart, valueEnd)
        .toString(Charsets.UTF_8)
        .filter { char -> !char.isISOControl() || char == '\t' }
}

internal fun readDexTypeEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    dex: HexDexSummary,
    stringEntries: List<HexDexStringEntry>
): List<HexDexTypeEntry> {
    if (dex.typeIdsSize <= 0 || dex.typeIdsOffset <= 0L || dex.typeIdsOffset >= fileSize) return emptyList()
    val stringsByIndex = stringEntries.associateBy { entry -> entry.index }
    return (0 until minOf(dex.typeIdsSize, MAX_DEX_TYPE_ENTRIES)).mapNotNull { index ->
        val typeIdOffset = dex.typeIdsOffset + index * DEX_TYPE_ID_ENTRY_SIZE
        val typeIdBytes = readAt(typeIdOffset, DEX_TYPE_ID_ENTRY_SIZE)
        if (typeIdBytes.size < DEX_TYPE_ID_ENTRY_SIZE) return@mapNotNull null

        val descriptorStringIndex = typeIdBytes.u32(0, HexEndian.LITTLE)
        HexDexTypeEntry(
            index = index,
            typeIdOffset = typeIdOffset,
            descriptorStringIndex = descriptorStringIndex,
            descriptor = stringsByIndex[descriptorStringIndex.coerceToInt()]?.value
                ?: dexIndexFallback(descriptorStringIndex)
        )
    }
}

internal fun readDexProtoEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    dex: HexDexSummary,
    stringEntries: List<HexDexStringEntry>,
    typeEntries: List<HexDexTypeEntry>
): List<HexDexProtoEntry> {
    if (dex.protoIdsSize <= 0 || dex.protoIdsOffset <= 0L || dex.protoIdsOffset >= fileSize) return emptyList()
    val stringsByIndex = stringEntries.associateBy { entry -> entry.index }
    val typesByIndex = typeEntries.associateBy { entry -> entry.index }
    return (0 until minOf(dex.protoIdsSize, MAX_DEX_PROTO_ENTRIES)).mapNotNull { index ->
        val protoIdOffset = dex.protoIdsOffset + index * DEX_PROTO_ID_ENTRY_SIZE
        val protoIdBytes = readAt(protoIdOffset, DEX_PROTO_ID_ENTRY_SIZE)
        if (protoIdBytes.size < DEX_PROTO_ID_ENTRY_SIZE) return@mapNotNull null

        val shortyStringIndex = protoIdBytes.u32(0, HexEndian.LITTLE)
        val returnTypeIndex = protoIdBytes.u32(4, HexEndian.LITTLE)
        val parametersOffset = protoIdBytes.u32(8, HexEndian.LITTLE)
        val parameterTypeDescriptors = readDexProtoParameterTypes(
            readAt = readAt,
            fileSize = fileSize,
            parametersOffset = parametersOffset,
            typesByIndex = typesByIndex
        )
        val returnTypeDescriptor = typesByIndex[returnTypeIndex.coerceToInt()]?.descriptor
            ?: dexIndexFallback(returnTypeIndex)
        HexDexProtoEntry(
            index = index,
            protoIdOffset = protoIdOffset,
            shortyStringIndex = shortyStringIndex,
            shorty = stringsByIndex[shortyStringIndex.coerceToInt()]?.value ?: dexIndexFallback(shortyStringIndex),
            returnTypeIndex = returnTypeIndex,
            returnTypeDescriptor = returnTypeDescriptor,
            parametersOffset = parametersOffset,
            parameterTypeDescriptors = parameterTypeDescriptors,
            signature = parameterTypeDescriptors.joinToString(
                separator = "",
                prefix = "(",
                postfix = ")$returnTypeDescriptor"
            )
        )
    }
}

internal fun readDexProtoParameterTypes(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    parametersOffset: Long,
    typesByIndex: Map<Int, HexDexTypeEntry>
): List<String> {
    if (parametersOffset <= 0L || parametersOffset >= fileSize) return emptyList()
    val sizeBytes = readAt(parametersOffset, 4)
    if (sizeBytes.size < 4) return emptyList()

    val parameterCount = sizeBytes.u32(0, HexEndian.LITTLE).coerceToInt()
    if (parameterCount <= 0) return emptyList()
    val visibleParameterCount = minOf(parameterCount, MAX_DEX_PROTO_PARAMETERS)
    val parametersBytes = readAt(
        parametersOffset + 4L,
        visibleParameterCount * DEX_TYPE_ITEM_ENTRY_SIZE
    )
    return (0 until visibleParameterCount).mapNotNull { index ->
        val entryOffset = index * DEX_TYPE_ITEM_ENTRY_SIZE
        if (entryOffset + DEX_TYPE_ITEM_ENTRY_SIZE > parametersBytes.size) return@mapNotNull null
        val typeIndex = parametersBytes.u16(entryOffset, HexEndian.LITTLE)
        typesByIndex[typeIndex]?.descriptor ?: dexIndexFallback(typeIndex.toLong())
    }
}

internal fun readDexFieldEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    dex: HexDexSummary,
    stringEntries: List<HexDexStringEntry>,
    typeEntries: List<HexDexTypeEntry>
): List<HexDexFieldEntry> {
    if (dex.fieldIdsSize <= 0 || dex.fieldIdsOffset <= 0L || dex.fieldIdsOffset >= fileSize) return emptyList()
    val stringsByIndex = stringEntries.associateBy { entry -> entry.index }
    val typesByIndex = typeEntries.associateBy { entry -> entry.index }
    return (0 until minOf(dex.fieldIdsSize, MAX_DEX_FIELD_ENTRIES)).mapNotNull { index ->
        val fieldIdOffset = dex.fieldIdsOffset + index * DEX_FIELD_ID_ENTRY_SIZE
        val fieldIdBytes = readAt(fieldIdOffset, DEX_FIELD_ID_ENTRY_SIZE)
        if (fieldIdBytes.size < DEX_FIELD_ID_ENTRY_SIZE) return@mapNotNull null

        val classIndex = fieldIdBytes.u16(0, HexEndian.LITTLE)
        val typeIndex = fieldIdBytes.u16(2, HexEndian.LITTLE)
        val nameStringIndex = fieldIdBytes.u32(4, HexEndian.LITTLE)
        HexDexFieldEntry(
            index = index,
            fieldIdOffset = fieldIdOffset,
            classIndex = classIndex,
            classDescriptor = typesByIndex[classIndex]?.descriptor ?: dexIndexFallback(classIndex.toLong()),
            typeIndex = typeIndex,
            typeDescriptor = typesByIndex[typeIndex]?.descriptor ?: dexIndexFallback(typeIndex.toLong()),
            nameStringIndex = nameStringIndex,
            name = stringsByIndex[nameStringIndex.coerceToInt()]?.value ?: dexIndexFallback(nameStringIndex)
        )
    }
}

internal fun readDexMethodEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    dex: HexDexSummary,
    stringEntries: List<HexDexStringEntry>,
    typeEntries: List<HexDexTypeEntry>,
    protoEntries: List<HexDexProtoEntry>
): List<HexDexMethodEntry> {
    if (dex.methodIdsSize <= 0 || dex.methodIdsOffset <= 0L || dex.methodIdsOffset >= fileSize) return emptyList()
    val stringsByIndex = stringEntries.associateBy { entry -> entry.index }
    val typesByIndex = typeEntries.associateBy { entry -> entry.index }
    val protosByIndex = protoEntries.associateBy { entry -> entry.index }
    return (0 until minOf(dex.methodIdsSize, MAX_DEX_METHOD_ENTRIES)).mapNotNull { index ->
        val methodIdOffset = dex.methodIdsOffset + index * DEX_METHOD_ID_ENTRY_SIZE
        val methodIdBytes = readAt(methodIdOffset, DEX_METHOD_ID_ENTRY_SIZE)
        if (methodIdBytes.size < DEX_METHOD_ID_ENTRY_SIZE) return@mapNotNull null

        val classIndex = methodIdBytes.u16(0, HexEndian.LITTLE)
        val protoIndex = methodIdBytes.u16(2, HexEndian.LITTLE)
        val nameStringIndex = methodIdBytes.u32(4, HexEndian.LITTLE)
        val proto = protosByIndex[protoIndex]
        HexDexMethodEntry(
            index = index,
            methodIdOffset = methodIdOffset,
            classIndex = classIndex,
            classDescriptor = typesByIndex[classIndex]?.descriptor ?: dexIndexFallback(classIndex.toLong()),
            protoIndex = protoIndex,
            protoShorty = proto?.shorty ?: dexIndexFallback(protoIndex.toLong()),
            protoSignature = proto?.signature ?: dexIndexFallback(protoIndex.toLong()),
            nameStringIndex = nameStringIndex,
            name = stringsByIndex[nameStringIndex.coerceToInt()]?.value ?: dexIndexFallback(nameStringIndex)
        )
    }
}

internal fun readDexClassDefEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    dex: HexDexSummary,
    stringEntries: List<HexDexStringEntry>,
    typeEntries: List<HexDexTypeEntry>
): List<HexDexClassDefEntry> {
    if (dex.classDefsSize <= 0 || dex.classDefsOffset <= 0L || dex.classDefsOffset >= fileSize) return emptyList()
    val stringsByIndex = stringEntries.associateBy { entry -> entry.index }
    val typesByIndex = typeEntries.associateBy { entry -> entry.index }
    return (0 until minOf(dex.classDefsSize, MAX_DEX_CLASS_DEF_ENTRIES)).mapNotNull { index ->
        val classDefOffset = dex.classDefsOffset + index * DEX_CLASS_DEF_ENTRY_SIZE
        val classDefBytes = readAt(classDefOffset, DEX_CLASS_DEF_ENTRY_SIZE)
        if (classDefBytes.size < DEX_CLASS_DEF_ENTRY_SIZE) return@mapNotNull null

        val classIndex = classDefBytes.u32(0, HexEndian.LITTLE)
        val superclassIndex = classDefBytes.u32(8, HexEndian.LITTLE).dexOptionalIndex()
        val sourceFileIndex = classDefBytes.u32(16, HexEndian.LITTLE).dexOptionalIndex()
        HexDexClassDefEntry(
            index = index,
            classDefOffset = classDefOffset,
            classIndex = classIndex,
            classDescriptor = typesByIndex[classIndex.coerceToInt()]?.descriptor ?: dexIndexFallback(classIndex),
            accessFlags = classDefBytes.u32(4, HexEndian.LITTLE),
            superclassIndex = superclassIndex,
            superclassDescriptor = superclassIndex?.let { typeIndex ->
                typesByIndex[typeIndex.coerceToInt()]?.descriptor ?: dexIndexFallback(typeIndex)
            },
            interfacesOffset = classDefBytes.u32(12, HexEndian.LITTLE),
            sourceFileIndex = sourceFileIndex,
            sourceFile = sourceFileIndex?.let { stringIndex ->
                stringsByIndex[stringIndex.coerceToInt()]?.value ?: dexIndexFallback(stringIndex)
            },
            annotationsOffset = classDefBytes.u32(20, HexEndian.LITTLE),
            classDataOffset = classDefBytes.u32(24, HexEndian.LITTLE),
            staticValuesOffset = classDefBytes.u32(28, HexEndian.LITTLE)
        )
    }
}

internal fun readDexClassDataMethodEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    classDefEntries: List<HexDexClassDefEntry>,
    methodEntries: List<HexDexMethodEntry>
): List<HexDexClassDataMethodEntry> {
    if (classDefEntries.isEmpty() || methodEntries.isEmpty()) return emptyList()
    val methodsByIndex = methodEntries.associateBy { entry -> entry.index.toLong() }
    val parsedEntries = mutableListOf<HexDexClassDataMethodEntry>()
    for (classDef in classDefEntries) {
        if (parsedEntries.size >= MAX_DEX_CLASS_DATA_METHOD_ENTRIES) break
        if (classDef.classDataOffset <= 0L || classDef.classDataOffset >= fileSize) continue

        val bytesToRead = minOf(MAX_DEX_CLASS_DATA_BYTES.toLong(), fileSize - classDef.classDataOffset).toInt()
        val classDataBytes = readAt(classDef.classDataOffset, bytesToRead)
        val parsedClassData = readDexClassDataMethodsForClass(
            classDataBytes = classDataBytes,
            classDef = classDef,
            methodsByIndex = methodsByIndex,
            nextIndex = parsedEntries.size,
            remainingLimit = MAX_DEX_CLASS_DATA_METHOD_ENTRIES - parsedEntries.size
        )
        parsedEntries += parsedClassData
    }
    return parsedEntries
}

internal fun readDexClassDataMethodsForClass(
    classDataBytes: ByteArray,
    classDef: HexDexClassDefEntry,
    methodsByIndex: Map<Long, HexDexMethodEntry>,
    nextIndex: Int,
    remainingLimit: Int
): List<HexDexClassDataMethodEntry> {
    if (remainingLimit <= 0) return emptyList()
    var cursor = 0
    val staticFieldsSize = classDataBytes.readDexUleb128(cursor) ?: return emptyList()
    cursor = staticFieldsSize.nextOffset
    val instanceFieldsSize = classDataBytes.readDexUleb128(cursor) ?: return emptyList()
    cursor = instanceFieldsSize.nextOffset
    val directMethodsSize = classDataBytes.readDexUleb128(cursor) ?: return emptyList()
    cursor = directMethodsSize.nextOffset
    val virtualMethodsSize = classDataBytes.readDexUleb128(cursor) ?: return emptyList()
    cursor = virtualMethodsSize.nextOffset

    if (staticFieldsSize.value > MAX_DEX_CLASS_DATA_FIELDS_TO_SKIP ||
        instanceFieldsSize.value > MAX_DEX_CLASS_DATA_FIELDS_TO_SKIP ||
        directMethodsSize.value > MAX_DEX_CLASS_DATA_METHODS_PER_CLASS ||
        virtualMethodsSize.value > MAX_DEX_CLASS_DATA_METHODS_PER_CLASS
    ) {
        return emptyList()
    }

    repeat(staticFieldsSize.value.coerceToInt()) {
        cursor = classDataBytes.skipDexEncodedField(cursor) ?: return emptyList()
    }
    repeat(instanceFieldsSize.value.coerceToInt()) {
        cursor = classDataBytes.skipDexEncodedField(cursor) ?: return emptyList()
    }

    val entries = mutableListOf<HexDexClassDataMethodEntry>()
    cursor = classDataBytes.readDexEncodedMethods(
        cursor = cursor,
        methodCount = directMethodsSize.value,
        kind = HexDexClassDataMethodKind.DIRECT,
        classDef = classDef,
        methodsByIndex = methodsByIndex,
        nextIndex = nextIndex,
        remainingLimit = remainingLimit,
        entries = entries
    ) ?: return entries

    classDataBytes.readDexEncodedMethods(
        cursor = cursor,
        methodCount = virtualMethodsSize.value,
        kind = HexDexClassDataMethodKind.VIRTUAL,
        classDef = classDef,
        methodsByIndex = methodsByIndex,
        nextIndex = nextIndex + entries.size,
        remainingLimit = remainingLimit - entries.size,
        entries = entries
    )
    return entries
}

internal fun ByteArray.readDexEncodedMethods(
    cursor: Int,
    methodCount: Long,
    kind: HexDexClassDataMethodKind,
    classDef: HexDexClassDefEntry,
    methodsByIndex: Map<Long, HexDexMethodEntry>,
    nextIndex: Int,
    remainingLimit: Int,
    entries: MutableList<HexDexClassDataMethodEntry>
): Int? {
    var currentCursor = cursor
    var previousMethodIndex = 0L
    val visibleMethodCount = minOf(methodCount, remainingLimit.toLong(), MAX_DEX_CLASS_DATA_METHODS_PER_CLASS.toLong())
    repeat(visibleMethodCount.coerceToInt()) {
        val entryOffset = classDef.classDataOffset + currentCursor
        val methodIndexDiff = readDexUleb128(currentCursor) ?: return null
        currentCursor = methodIndexDiff.nextOffset
        val accessFlags = readDexUleb128(currentCursor) ?: return null
        currentCursor = accessFlags.nextOffset
        val codeOffset = readDexUleb128(currentCursor) ?: return null
        currentCursor = codeOffset.nextOffset

        val methodIndex = previousMethodIndex + methodIndexDiff.value
        previousMethodIndex = methodIndex
        val method = methodsByIndex[methodIndex]
        entries += HexDexClassDataMethodEntry(
            index = nextIndex + entries.size,
            classDefIndex = classDef.index,
            classDescriptor = classDef.classDescriptor,
            kind = kind,
            methodIndex = methodIndex,
            methodName = method?.name ?: dexIndexFallback(methodIndex),
            methodClassDescriptor = method?.classDescriptor ?: classDef.classDescriptor,
            protoSignature = method?.protoSignature ?: dexIndexFallback(methodIndex),
            accessFlags = accessFlags.value,
            classDataOffset = classDef.classDataOffset,
            entryOffset = entryOffset,
            codeOffset = codeOffset.value
        )
    }
    return currentCursor
}

internal fun ByteArray.skipDexEncodedField(cursor: Int): Int? {
    val fieldIndexDiff = readDexUleb128(cursor) ?: return null
    val accessFlags = readDexUleb128(fieldIndexDiff.nextOffset) ?: return null
    return accessFlags.nextOffset
}

internal fun readDexCodeItemEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    classDataMethodEntries: List<HexDexClassDataMethodEntry>
): List<HexDexCodeItemEntry> {
    if (classDataMethodEntries.isEmpty()) return emptyList()
    val entries = mutableListOf<HexDexCodeItemEntry>()
    val seenCodeOffsets = mutableSetOf<Long>()
    for (method in classDataMethodEntries) {
        if (entries.size >= MAX_DEX_CODE_ITEM_ENTRIES) break
        if (method.codeOffset <= 0L || method.codeOffset + DEX_CODE_ITEM_HEADER_SIZE > fileSize) continue
        if (!seenCodeOffsets.add(method.codeOffset)) continue

        val headerBytes = readAt(method.codeOffset, DEX_CODE_ITEM_HEADER_SIZE)
        if (headerBytes.size < DEX_CODE_ITEM_HEADER_SIZE) continue
        val insnsSize = headerBytes.u32(12, HexEndian.LITTLE)
        val previewCodeUnits = readDexCodeItemPreviewCodeUnits(
            readAt = readAt,
            fileSize = fileSize,
            codeOffset = method.codeOffset,
            insnsSize = insnsSize
        )
        val firstCodeUnit = previewCodeUnits.firstOrNull() ?: 0
        val firstOpcode = firstCodeUnit and 0xFF
        entries += HexDexCodeItemEntry(
            index = entries.size,
            methodIndex = method.methodIndex,
            methodName = method.methodName,
            methodClassDescriptor = method.methodClassDescriptor,
            protoSignature = method.protoSignature,
            codeOffset = method.codeOffset,
            registersSize = headerBytes.u16(0, HexEndian.LITTLE),
            insSize = headerBytes.u16(2, HexEndian.LITTLE),
            outsSize = headerBytes.u16(4, HexEndian.LITTLE),
            triesSize = headerBytes.u16(6, HexEndian.LITTLE),
            debugInfoOffset = headerBytes.u32(8, HexEndian.LITTLE),
            insnsSize = insnsSize,
            firstOpcode = firstOpcode,
            firstOpcodeName = dexOpcodeName(firstOpcode),
            previewCodeUnitsHex = previewCodeUnits.joinToString(separator = " ") { codeUnit ->
                "%04X".format(codeUnit)
            }
        )
    }
    return entries
}

internal fun readDexCodeItemPreviewCodeUnits(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    codeOffset: Long,
    insnsSize: Long
): List<Int> {
    if (insnsSize <= 0L) return emptyList()
    val insnsOffset = codeOffset + DEX_CODE_ITEM_HEADER_SIZE
    if (insnsOffset >= fileSize) return emptyList()
    val availableCodeUnits = ((fileSize - insnsOffset) / 2L).coerceToInt()
    val previewCodeUnits = minOf(
        insnsSize.coerceToInt(),
        availableCodeUnits,
        MAX_DEX_CODE_ITEM_PREVIEW_UNITS
    )
    if (previewCodeUnits <= 0) return emptyList()
    val previewBytes = readAt(insnsOffset, previewCodeUnits * DEX_CODE_UNIT_SIZE)
    return (0 until previewCodeUnits).mapNotNull { index ->
        val unitOffset = index * DEX_CODE_UNIT_SIZE
        if (unitOffset + DEX_CODE_UNIT_SIZE > previewBytes.size) return@mapNotNull null
        previewBytes.u16(unitOffset, HexEndian.LITTLE)
    }
}

internal fun readDexCallReferenceEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    classDataMethodEntries: List<HexDexClassDataMethodEntry>,
    methodEntries: List<HexDexMethodEntry>
): List<HexDexCallReferenceEntry> {
    if (classDataMethodEntries.isEmpty() || methodEntries.isEmpty()) return emptyList()
    val methodsByIndex = methodEntries.associateBy { entry -> entry.index.toLong() }
    val entries = mutableListOf<HexDexCallReferenceEntry>()

    for (caller in classDataMethodEntries) {
        if (entries.size >= MAX_DEX_CALL_REFERENCE_ENTRIES) break
        if (caller.codeOffset <= 0L || caller.codeOffset + DEX_CODE_ITEM_HEADER_SIZE > fileSize) continue

        val headerBytes = readAt(caller.codeOffset, DEX_CODE_ITEM_HEADER_SIZE)
        if (headerBytes.size < DEX_CODE_ITEM_HEADER_SIZE) continue
        val insnsSize = headerBytes.u32(12, HexEndian.LITTLE)
        val insnsOffset = caller.codeOffset + DEX_CODE_ITEM_HEADER_SIZE
        if (insnsSize <= 0L || insnsOffset >= fileSize) continue

        val availableCodeUnits = ((fileSize - insnsOffset) / DEX_CODE_UNIT_SIZE).coerceToInt()
        val scanCodeUnits = minOf(
            insnsSize.coerceToInt(),
            availableCodeUnits,
            MAX_DEX_CALL_SCAN_CODE_UNITS
        )
        if (scanCodeUnits <= 0) continue

        val codeBytes = readAt(insnsOffset, scanCodeUnits * DEX_CODE_UNIT_SIZE)
        val codeUnits = codeBytes.toDexCodeUnits(scanCodeUnits)
        var cursor = 0
        while (cursor < codeUnits.size && entries.size < MAX_DEX_CALL_REFERENCE_ENTRIES) {
            val firstCodeUnit = codeUnits[cursor]
            val opcode = firstCodeUnit and 0xFF
            val methodIndex = dexInvokeMethodIndex(codeUnits, cursor, opcode)
            if (methodIndex != null) {
                val targetMethod = methodsByIndex[methodIndex]
                entries += HexDexCallReferenceEntry(
                    index = entries.size,
                    callerMethodIndex = caller.methodIndex,
                    callerClassDescriptor = caller.methodClassDescriptor,
                    callerMethodName = caller.methodName,
                    callerProtoSignature = caller.protoSignature,
                    targetMethodIndex = methodIndex,
                    targetClassDescriptor = targetMethod?.classDescriptor ?: dexIndexFallback(methodIndex),
                    targetMethodName = targetMethod?.name ?: dexIndexFallback(methodIndex),
                    targetProtoSignature = targetMethod?.protoSignature ?: dexIndexFallback(methodIndex),
                    opcode = opcode,
                    opcodeName = dexOpcodeName(opcode),
                    instructionOffset = insnsOffset + cursor * DEX_CODE_UNIT_SIZE,
                    codeOffset = caller.codeOffset,
                    targetMethodIdOffset = targetMethod?.methodIdOffset
                )
            }
            cursor += dexInstructionCodeUnits(opcode, firstCodeUnit).coerceAtLeast(1)
        }
    }

    return entries
}

internal data class DexDataReferenceEntries(
    val stringReferenceEntries: List<HexDexStringReferenceEntry>,
    val fieldReferenceEntries: List<HexDexFieldReferenceEntry>
)

internal fun readDexDataReferenceEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    classDataMethodEntries: List<HexDexClassDataMethodEntry>,
    stringEntries: List<HexDexStringEntry>,
    fieldEntries: List<HexDexFieldEntry>
): DexDataReferenceEntries {
    if (classDataMethodEntries.isEmpty()) {
        return DexDataReferenceEntries(
            stringReferenceEntries = emptyList(),
            fieldReferenceEntries = emptyList()
        )
    }
    val stringsByIndex = stringEntries.associateBy { entry -> entry.index.toLong() }
    val fieldsByIndex = fieldEntries.associateBy { entry -> entry.index.toLong() }
    val stringReferences = mutableListOf<HexDexStringReferenceEntry>()
    val fieldReferences = mutableListOf<HexDexFieldReferenceEntry>()

    for (caller in classDataMethodEntries) {
        if (stringReferences.size >= MAX_DEX_STRING_REFERENCE_ENTRIES &&
            fieldReferences.size >= MAX_DEX_FIELD_REFERENCE_ENTRIES
        ) {
            break
        }
        if (caller.codeOffset <= 0L || caller.codeOffset + DEX_CODE_ITEM_HEADER_SIZE > fileSize) continue

        val headerBytes = readAt(caller.codeOffset, DEX_CODE_ITEM_HEADER_SIZE)
        if (headerBytes.size < DEX_CODE_ITEM_HEADER_SIZE) continue
        val insnsSize = headerBytes.u32(12, HexEndian.LITTLE)
        val insnsOffset = caller.codeOffset + DEX_CODE_ITEM_HEADER_SIZE
        if (insnsSize <= 0L || insnsOffset >= fileSize) continue

        val availableCodeUnits = ((fileSize - insnsOffset) / DEX_CODE_UNIT_SIZE).coerceToInt()
        val scanCodeUnits = minOf(
            insnsSize.coerceToInt(),
            availableCodeUnits,
            MAX_DEX_DATA_REFERENCE_SCAN_CODE_UNITS
        )
        if (scanCodeUnits <= 0) continue

        val codeBytes = readAt(insnsOffset, scanCodeUnits * DEX_CODE_UNIT_SIZE)
        val codeUnits = codeBytes.toDexCodeUnits(scanCodeUnits)
        var cursor = 0
        while (cursor < codeUnits.size) {
            val firstCodeUnit = codeUnits[cursor]
            val opcode = firstCodeUnit and 0xFF
            val instructionOffset = insnsOffset + cursor * DEX_CODE_UNIT_SIZE

            val stringIndex = dexStringReferenceIndex(codeUnits, cursor, opcode)
            if (stringIndex != null && stringReferences.size < MAX_DEX_STRING_REFERENCE_ENTRIES) {
                val stringEntry = stringsByIndex[stringIndex]
                stringReferences += HexDexStringReferenceEntry(
                    index = stringReferences.size,
                    callerMethodIndex = caller.methodIndex,
                    callerClassDescriptor = caller.methodClassDescriptor,
                    callerMethodName = caller.methodName,
                    callerProtoSignature = caller.protoSignature,
                    stringIndex = stringIndex,
                    value = stringEntry?.value ?: dexIndexFallback(stringIndex),
                    opcode = opcode,
                    opcodeName = dexOpcodeName(opcode),
                    instructionOffset = instructionOffset,
                    codeOffset = caller.codeOffset,
                    stringIdOffset = stringEntry?.stringIdOffset,
                    stringDataOffset = stringEntry?.dataOffset
                )
            }

            val fieldIndex = dexFieldReferenceIndex(codeUnits, cursor, opcode)
            if (fieldIndex != null && fieldReferences.size < MAX_DEX_FIELD_REFERENCE_ENTRIES) {
                val fieldEntry = fieldsByIndex[fieldIndex]
                fieldReferences += HexDexFieldReferenceEntry(
                    index = fieldReferences.size,
                    callerMethodIndex = caller.methodIndex,
                    callerClassDescriptor = caller.methodClassDescriptor,
                    callerMethodName = caller.methodName,
                    callerProtoSignature = caller.protoSignature,
                    fieldIndex = fieldIndex,
                    fieldClassDescriptor = fieldEntry?.classDescriptor ?: dexIndexFallback(fieldIndex),
                    fieldName = fieldEntry?.name ?: dexIndexFallback(fieldIndex),
                    fieldTypeDescriptor = fieldEntry?.typeDescriptor ?: dexIndexFallback(fieldIndex),
                    opcode = opcode,
                    opcodeName = dexOpcodeName(opcode),
                    instructionOffset = instructionOffset,
                    codeOffset = caller.codeOffset,
                    fieldIdOffset = fieldEntry?.fieldIdOffset
                )
            }

            cursor += dexInstructionCodeUnits(opcode, firstCodeUnit).coerceAtLeast(1)
        }
    }

    return DexDataReferenceEntries(
        stringReferenceEntries = stringReferences,
        fieldReferenceEntries = fieldReferences
    )
}

internal fun ByteArray.toDexCodeUnits(limit: Int): List<Int> {
    val codeUnitCount = minOf(limit, size / DEX_CODE_UNIT_SIZE)
    return (0 until codeUnitCount).map { index ->
        u16(index * DEX_CODE_UNIT_SIZE, HexEndian.LITTLE)
    }
}

internal fun dexInvokeMethodIndex(
    codeUnits: List<Int>,
    cursor: Int,
    opcode: Int
): Long? = when (opcode) {
    in 0x6E..0x72,
    in 0x74..0x78 -> codeUnits.getOrNull(cursor + 1)?.toLong()
    else -> null
}

internal fun dexStringReferenceIndex(
    codeUnits: List<Int>,
    cursor: Int,
    opcode: Int
): Long? = when (opcode) {
    0x1A -> codeUnits.getOrNull(cursor + 1)?.toLong()
    0x1B -> {
        val low = codeUnits.getOrNull(cursor + 1)
        val high = codeUnits.getOrNull(cursor + 2)
        if (low != null && high != null) {
            low.toLong() or (high.toLong() shl 16)
        } else {
            null
        }
    }
    else -> null
}

internal fun dexFieldReferenceIndex(
    codeUnits: List<Int>,
    cursor: Int,
    opcode: Int
): Long? = when (opcode) {
    in 0x52..0x5F,
    in 0x60..0x6D -> codeUnits.getOrNull(cursor + 1)?.toLong()
    else -> null
}

internal fun dexInstructionCodeUnits(opcode: Int, firstCodeUnit: Int): Int = when (opcode) {
    0x00 -> if (firstCodeUnit == 0) 1 else 2
    0x01,
    0x04,
    0x07,
    0x0A,
    in 0x0B..0x11,
    0x12,
    in 0x1D..0x1F,
    in 0x27..0x28,
    in 0x2D..0x31,
    in 0x7B..0x8F,
    in 0xB0..0xCF -> 1
    0x02,
    0x05,
    0x08,
    0x13,
    0x15,
    0x16,
    0x19,
    0x1A,
    0x20,
    0x21,
    0x22,
    0x23,
    0x26,
    0x29,
    in 0x32..0x3D,
    in 0x44..0x6D,
    in 0x90..0xAF,
    in 0xD0..0xE2,
    0xFE,
    0xFF -> 2
    0x03,
    0x06,
    0x09,
    0x14,
    0x17,
    0x1B,
    0x1C,
    0x24,
    0x25,
    0x2A,
    in 0x6E..0x72,
    in 0x74..0x78 -> 3
    0xFA,
    0xFB,
    0xFC,
    0xFD -> 4
    0x18 -> 5
    else -> 1
}

internal fun readDexMapEntries(
    readAt: (Long, Int) -> ByteArray,
    fileSize: Long,
    mapOffset: Long
): List<HexDexMapEntry> {
    if (mapOffset <= 0L || mapOffset >= fileSize) return emptyList()
    val sizeBytes = readAt(mapOffset, 4)
    if (sizeBytes.size < 4) return emptyList()

    val mapSize = sizeBytes.u32(0, HexEndian.LITTLE).coerceToInt()
    return (0 until minOf(mapSize, MAX_DEX_MAP_ENTRIES)).mapNotNull { index ->
        val entryFileOffset = mapOffset + 4L + index * DEX_MAP_ENTRY_SIZE
        val entryBytes = readAt(entryFileOffset, DEX_MAP_ENTRY_SIZE)
        if (entryBytes.size < DEX_MAP_ENTRY_SIZE) return@mapNotNull null

        val type = entryBytes.u16(0, HexEndian.LITTLE)
        HexDexMapEntry(
            index = index,
            type = type,
            typeName = dexMapTypeName(type),
            size = entryBytes.u32(4, HexEndian.LITTLE),
            offset = entryBytes.u32(8, HexEndian.LITTLE),
            entryFileOffset = entryFileOffset
        )
    }
}

internal fun parseArchiveSummary(
    file: File,
    randomAccessFile: RandomAccessFile,
    fileSize: Long
): HexArchiveSummary? {
    if (fileSize < ZIP_END_OF_CENTRAL_DIRECTORY_SIZE) return null
    val scanSize = minOf(fileSize, ZIP_MAX_EOCD_SCAN_BYTES.toLong()).toInt()
    val scanOffset = fileSize - scanSize
    val scanBytes = randomAccessFile.readAt(scanOffset, scanSize)
    val eocdIndex = scanBytes.findLastZipSignature(ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE) ?: return null
    val eocdOffset = scanOffset + eocdIndex
    if (eocdIndex + ZIP_END_OF_CENTRAL_DIRECTORY_SIZE > scanBytes.size) return null

    val entryCount = scanBytes.u16(eocdIndex + 10, HexEndian.LITTLE)
    val centralDirectorySize = scanBytes.u32(eocdIndex + 12, HexEndian.LITTLE)
    val centralDirectoryOffset = scanBytes.u32(eocdIndex + 16, HexEndian.LITTLE)
    val archiveCommentLength = scanBytes.u16(eocdIndex + 20, HexEndian.LITTLE)
    if (centralDirectoryOffset <= 0L || centralDirectoryOffset >= fileSize || eocdOffset < centralDirectoryOffset) {
        return null
    }
    val zipStructure = HexArchiveZipStructure(
        eocdOffset = eocdOffset,
        centralDirectoryOffset = centralDirectoryOffset,
        centralDirectorySize = centralDirectorySize,
        entryCount = entryCount,
        commentLength = archiveCommentLength,
        zip64LocatorOffset = findZip64LocatorOffset(
            randomAccessFile = randomAccessFile,
            eocdOffset = eocdOffset
        )
    )

    val entries = mutableListOf<HexArchiveEntry>()
    var cursor = centralDirectoryOffset
    repeat(minOf(entryCount, MAX_ARCHIVE_ENTRIES)) { index ->
        if (cursor + ZIP_CENTRAL_DIRECTORY_HEADER_SIZE > fileSize) return@repeat
        val header = randomAccessFile.readAt(cursor, ZIP_CENTRAL_DIRECTORY_HEADER_SIZE)
        if (header.size < ZIP_CENTRAL_DIRECTORY_HEADER_SIZE ||
            header.u32(0, HexEndian.LITTLE) != ZIP_CENTRAL_DIRECTORY_SIGNATURE
        ) {
            return@repeat
        }

        val nameLength = header.u16(28, HexEndian.LITTLE)
        val extraLength = header.u16(30, HexEndian.LITTLE)
        val commentLength = header.u16(32, HexEndian.LITTLE)
        val fullEntrySize = ZIP_CENTRAL_DIRECTORY_HEADER_SIZE + nameLength + extraLength + commentLength
        val nameBytes = randomAccessFile.readAt(cursor + ZIP_CENTRAL_DIRECTORY_HEADER_SIZE, nameLength)
        val name = nameBytes.toString(Charsets.UTF_8)
        val generalPurposeBitFlag = header.u16(8, HexEndian.LITTLE)
        val compressionMethod = header.u16(10, HexEndian.LITTLE)
        val localHeaderOffset = header.u32(42, HexEndian.LITTLE)
        val compressedSize = header.u32(20, HexEndian.LITTLE)
        val localHeader = readZipEntryLocalHeader(
            randomAccessFile = randomAccessFile,
            localHeaderOffset = localHeaderOffset,
            fileSize = fileSize
        )
        val dataOffset = localHeader?.dataOffset
        val dataEndOffset = archiveEntryDataEndOffset(dataOffset, compressedSize)
        entries += HexArchiveEntry(
            index = index,
            name = name,
            generalPurposeBitFlag = generalPurposeBitFlag,
            compressionMethod = compressionMethod,
            crc32 = header.u32(16, HexEndian.LITTLE),
            compressedSize = compressedSize,
            uncompressedSize = header.u32(24, HexEndian.LITTLE),
            localHeaderOffset = localHeaderOffset,
            centralDirectoryOffset = cursor,
            dataOffset = dataOffset,
            dataEndOffset = dataEndOffset,
            dataRangeStatus = archiveEntryDataRangeStatus(
                dataOffset = dataOffset,
                dataEndOffset = dataEndOffset,
                centralDirectoryOffset = centralDirectoryOffset,
                fileSize = fileSize
            ),
            localHeaderName = localHeader?.name,
            localHeaderGeneralPurposeBitFlag = localHeader?.generalPurposeBitFlag,
            localHeaderCompressionMethod = localHeader?.compressionMethod,
            localHeaderConsistency = archiveEntryLocalHeaderConsistency(
                centralName = name,
                centralGeneralPurposeBitFlag = generalPurposeBitFlag,
                centralCompressionMethod = compressionMethod,
                localName = localHeader?.name,
                localGeneralPurposeBitFlag = localHeader?.generalPurposeBitFlag,
                localCompressionMethod = localHeader?.compressionMethod
            )
        )
        cursor += fullEntrySize
    }

    val entriesWithNameRisks = entries.withArchiveEntryNameRisks()
    return HexArchiveSummary(
        entries = entriesWithNameRisks,
        embeddedDexFiles = readArchiveDexSummaries(file, entriesWithNameRisks),
        nativeLibrarySummaries = readArchiveNativeLibrarySummaries(file, entriesWithNameRisks),
        signingBlockEntries = readApkSigningBlockEntries(
            randomAccessFile = randomAccessFile,
            centralDirectoryOffset = centralDirectoryOffset
        ),
        manifestSummary = readArchiveManifestSummary(file, entriesWithNameRisks),
        resourcesSummary = readArchiveResourcesSummary(file, entriesWithNameRisks),
        zipStructure = zipStructure
    )
}

internal fun readZipEntryLocalHeader(
    randomAccessFile: RandomAccessFile,
    localHeaderOffset: Long,
    fileSize: Long
): ZipEntryLocalHeader? {
    if (localHeaderOffset < 0L || localHeaderOffset + ZIP_LOCAL_FILE_HEADER_SIZE > fileSize) return null
    val localHeader = randomAccessFile.readAt(localHeaderOffset, ZIP_LOCAL_FILE_HEADER_SIZE)
    if (localHeader.size < ZIP_LOCAL_FILE_HEADER_SIZE ||
        localHeader.u32(0, HexEndian.LITTLE) != ZIP_LOCAL_FILE_HEADER_SIGNATURE
    ) {
        return null
    }
    val nameLength = localHeader.u16(26, HexEndian.LITTLE)
    val extraLength = localHeader.u16(28, HexEndian.LITTLE)
    val dataOffset = localHeaderOffset + ZIP_LOCAL_FILE_HEADER_SIZE + nameLength + extraLength
    if (dataOffset > fileSize) return null
    val nameBytes = randomAccessFile.readAt(localHeaderOffset + ZIP_LOCAL_FILE_HEADER_SIZE, nameLength)
    if (nameBytes.size < nameLength) return null
    return ZipEntryLocalHeader(
        name = nameBytes.toString(Charsets.UTF_8),
        generalPurposeBitFlag = localHeader.u16(6, HexEndian.LITTLE),
        compressionMethod = localHeader.u16(8, HexEndian.LITTLE),
        dataOffset = dataOffset
    )
}

internal fun archiveEntryDataEndOffset(
    dataOffset: Long?,
    compressedSize: Long
): Long? {
    if (dataOffset == null || compressedSize < 0L || Long.MAX_VALUE - dataOffset < compressedSize) return null
    return dataOffset + compressedSize
}

internal fun archiveEntryDataRangeStatus(
    dataOffset: Long?,
    dataEndOffset: Long?,
    centralDirectoryOffset: Long,
    fileSize: Long
): HexArchiveEntryDataRangeStatus = when {
    dataOffset == null || dataEndOffset == null -> HexArchiveEntryDataRangeStatus.UNKNOWN
    dataOffset < 0L || dataEndOffset < dataOffset || dataEndOffset > fileSize -> HexArchiveEntryDataRangeStatus.OUT_OF_FILE
    dataEndOffset > centralDirectoryOffset -> HexArchiveEntryDataRangeStatus.OVERLAPS_CENTRAL_DIRECTORY
    else -> HexArchiveEntryDataRangeStatus.OK
}

internal fun archiveEntryLocalHeaderConsistency(
    centralName: String,
    centralGeneralPurposeBitFlag: Int,
    centralCompressionMethod: Int,
    localName: String?,
    localGeneralPurposeBitFlag: Int?,
    localCompressionMethod: Int?
): HexArchiveEntryLocalHeaderConsistency {
    if (localName == null || localGeneralPurposeBitFlag == null || localCompressionMethod == null) {
        return HexArchiveEntryLocalHeaderConsistency.UNKNOWN
    }
    val nameMismatch = centralName != localName
    val metadataMismatch = centralGeneralPurposeBitFlag != localGeneralPurposeBitFlag ||
        centralCompressionMethod != localCompressionMethod
    return when {
        nameMismatch && metadataMismatch -> HexArchiveEntryLocalHeaderConsistency.MULTIPLE_MISMATCHES
        nameMismatch -> HexArchiveEntryLocalHeaderConsistency.NAME_MISMATCH
        metadataMismatch -> HexArchiveEntryLocalHeaderConsistency.METADATA_MISMATCH
        else -> HexArchiveEntryLocalHeaderConsistency.OK
    }
}

internal fun List<HexArchiveEntry>.withArchiveEntryNameRisks(): List<HexArchiveEntry> {
    if (isEmpty()) return this
    val nameCounts = groupingBy { entry -> entry.name }.eachCount()
    return map { entry ->
        entry.copy(
            nameRisks = archiveEntryNameRisks(
                name = entry.name,
                occurrenceCount = nameCounts[entry.name] ?: 1
            )
        )
    }
}

internal fun archiveEntryNameRisks(
    name: String,
    occurrenceCount: Int = 1
): Set<HexArchiveEntryNameRisk> {
    val risks = mutableSetOf<HexArchiveEntryNameRisk>()
    if (name.isEmpty()) risks += HexArchiveEntryNameRisk.EMPTY_NAME
    if (occurrenceCount > 1) risks += HexArchiveEntryNameRisk.DUPLICATE_NAME
    if (name.startsWith("/") || name.startsWith("\\")) risks += HexArchiveEntryNameRisk.ABSOLUTE_PATH
    if (name.length >= 3 && name[1] == ':' && isArchivePathSeparator(name[2])) {
        risks += HexArchiveEntryNameRisk.WINDOWS_DRIVE_PATH
    }
    if ('\\' in name) risks += HexArchiveEntryNameRisk.BACKSLASH_SEPARATOR
    val normalizedSegments = name.replace('\\', '/').split('/')
    if (normalizedSegments.any { segment -> segment == ".." }) {
        risks += HexArchiveEntryNameRisk.PATH_TRAVERSAL
    }
    return risks
}

internal fun isArchivePathSeparator(value: Char): Boolean = value == '/' || value == '\\'

internal fun dexClassDataMethodExecutionKind(
    accessFlags: Long,
    codeOffset: Long
): HexDexClassDataMethodExecutionKind = when {
    (accessFlags and DEX_ACCESS_FLAG_NATIVE) != 0L -> HexDexClassDataMethodExecutionKind.NATIVE
    (accessFlags and DEX_ACCESS_FLAG_ABSTRACT) != 0L -> HexDexClassDataMethodExecutionKind.ABSTRACT
    codeOffset > 0L -> HexDexClassDataMethodExecutionKind.CODE
    else -> HexDexClassDataMethodExecutionKind.NO_CODE
}

internal fun findZip64LocatorOffset(
    randomAccessFile: RandomAccessFile,
    eocdOffset: Long
): Long? {
    val locatorOffset = eocdOffset - ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE
    if (locatorOffset < 0L) return null
    val locatorBytes = randomAccessFile.readAt(locatorOffset, ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE)
    if (locatorBytes.size < ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE) return null
    return locatorOffset.takeIf {
        locatorBytes.u32(0, HexEndian.LITTLE) == ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE
    }
}

internal fun readApkSigningBlockEntries(
    randomAccessFile: RandomAccessFile,
    centralDirectoryOffset: Long
): List<HexArchiveSigningBlockEntry> {
    if (centralDirectoryOffset < APK_SIGNING_BLOCK_FOOTER_SIZE) return emptyList()
    val footerOffset = centralDirectoryOffset - APK_SIGNING_BLOCK_FOOTER_SIZE
    val footerBytes = randomAccessFile.readAt(footerOffset, APK_SIGNING_BLOCK_FOOTER_SIZE)
    if (footerBytes.size < APK_SIGNING_BLOCK_FOOTER_SIZE) return emptyList()
    val magicOffset = APK_SIGNING_BLOCK_SIZE_FIELD_SIZE
    if (!footerBytes.regionMatches(magicOffset, APK_SIGNING_BLOCK_MAGIC)) return emptyList()

    val blockSize = footerBytes.u64(0, HexEndian.LITTLE)
    val totalBlockSize = blockSize + APK_SIGNING_BLOCK_SIZE_FIELD_SIZE
    if (blockSize < APK_SIGNING_BLOCK_FOOTER_SIZE ||
        totalBlockSize > centralDirectoryOffset ||
        totalBlockSize > MAX_APK_SIGNING_BLOCK_BYTES
    ) {
        return emptyList()
    }

    val blockOffset = centralDirectoryOffset - totalBlockSize
    val firstSizeBytes = randomAccessFile.readAt(blockOffset, APK_SIGNING_BLOCK_SIZE_FIELD_SIZE)
    if (firstSizeBytes.size < APK_SIGNING_BLOCK_SIZE_FIELD_SIZE ||
        firstSizeBytes.u64(0, HexEndian.LITTLE) != blockSize
    ) {
        return emptyList()
    }

    val pairsSize = blockSize - APK_SIGNING_BLOCK_FOOTER_SIZE
    if (pairsSize <= 0L || pairsSize > Int.MAX_VALUE.toLong()) return emptyList()
    val pairsOffset = blockOffset + APK_SIGNING_BLOCK_SIZE_FIELD_SIZE
    val pairsBytes = randomAccessFile.readAt(pairsOffset, pairsSize.coerceToInt())
    if (pairsBytes.size < pairsSize) return emptyList()

    val entries = mutableListOf<HexArchiveSigningBlockEntry>()
    var cursor = 0
    while (cursor + APK_SIGNING_BLOCK_PAIR_HEADER_SIZE <= pairsBytes.size &&
        entries.size < MAX_ARCHIVE_SIGNING_BLOCK_ENTRIES
    ) {
        val pairSize = pairsBytes.u64(cursor, HexEndian.LITTLE)
        if (pairSize < APK_SIGNING_BLOCK_ID_SIZE ||
            pairSize > (pairsBytes.size - cursor - APK_SIGNING_BLOCK_SIZE_FIELD_SIZE).toLong()
        ) {
            break
        }

        val idOffset = cursor + APK_SIGNING_BLOCK_SIZE_FIELD_SIZE
        val valueOffset = idOffset + APK_SIGNING_BLOCK_ID_SIZE
        val valueSize = pairSize - APK_SIGNING_BLOCK_ID_SIZE
        val id = pairsBytes.u32(idOffset, HexEndian.LITTLE)
        entries += HexArchiveSigningBlockEntry(
            index = entries.size,
            id = id,
            idName = apkSigningBlockIdName(id),
            valueSize = valueSize,
            blockOffset = blockOffset,
            blockSize = totalBlockSize,
            pairOffset = pairsOffset + cursor,
            valueOffset = pairsOffset + valueOffset
        )
        cursor += APK_SIGNING_BLOCK_SIZE_FIELD_SIZE + pairSize.coerceToInt()
    }
    return entries
}

internal fun readArchiveManifestSummary(
    file: File,
    entries: List<HexArchiveEntry>
): HexArchiveManifestSummary? {
    val manifestEntry = entries.firstOrNull { entry ->
        entry.name.equals("AndroidManifest.xml", ignoreCase = true)
    } ?: return null

    return runCatching {
        ZipFile(file).use { zipFile ->
            val zipEntry = zipFile.getEntry(manifestEntry.name) ?: return@use null
            val maxBytes = minOf(
                manifestEntry.uncompressedSize.takeIf { size -> size > 0L } ?: Long.MAX_VALUE,
                MAX_ARCHIVE_MANIFEST_ANALYSIS_BYTES.toLong()
            ).coerceToInt()
            val manifestBytes = zipFile.getInputStream(zipEntry).use { input ->
                input.readAtMost(maxBytes)
            }
            val binaryXml = parseAndroidBinaryManifest(manifestBytes) ?: return@use null
            HexArchiveManifestSummary(
                entryName = manifestEntry.name,
                localHeaderOffset = manifestEntry.localHeaderOffset,
                analyzedBytes = manifestBytes.size.toLong(),
                truncated = zipEntry.size > manifestBytes.size && zipEntry.size >= 0L,
                stringCount = binaryXml.stringCount,
                elementCount = binaryXml.elementCount,
                rootElementName = binaryXml.rootElementName,
                packageName = binaryXml.packageName,
                permissions = binaryXml.permissions
            )
        }
    }.getOrNull()
}

internal data class AndroidBinaryManifestSummary(
    val stringCount: Int,
    val elementCount: Int,
    val rootElementName: String?,
    val packageName: String?,
    val permissions: List<String>
)

internal data class AndroidXmlStartElement(
    val name: String?,
    val attributes: Map<String, String>
)

internal data class AndroidStringLength(
    val value: Int,
    val bytesRead: Int
)

internal fun readArchiveResourcesSummary(
    file: File,
    entries: List<HexArchiveEntry>
): HexArchiveResourcesSummary? {
    val resourcesEntry = entries.firstOrNull { entry ->
        entry.name.equals("resources.arsc", ignoreCase = true)
    } ?: return null

    return runCatching {
        ZipFile(file).use { zipFile ->
            val zipEntry = zipFile.getEntry(resourcesEntry.name) ?: return@use null
            val maxBytes = minOf(
                resourcesEntry.uncompressedSize.takeIf { size -> size > 0L } ?: Long.MAX_VALUE,
                MAX_ARCHIVE_RESOURCES_ANALYSIS_BYTES.toLong()
            ).coerceToInt()
            val resourcesBytes = zipFile.getInputStream(zipEntry).use { input ->
                input.readAtMost(maxBytes)
            }
            val table = parseAndroidResourcesTable(resourcesBytes) ?: return@use null
            HexArchiveResourcesSummary(
                entryName = resourcesEntry.name,
                localHeaderOffset = resourcesEntry.localHeaderOffset,
                analyzedBytes = resourcesBytes.size.toLong(),
                truncated = zipEntry.size > resourcesBytes.size && zipEntry.size >= 0L,
                packageCountFromHeader = table.packageCountFromHeader,
                globalStringCount = table.globalStringCount,
                typeSpecCount = table.typeSpecCount,
                typeChunkCount = table.typeChunkCount,
                packages = table.packages
            )
        }
    }.getOrNull()
}

internal data class AndroidResourcesTableSummary(
    val packageCountFromHeader: Int,
    val globalStringCount: Int,
    val typeSpecCount: Int,
    val typeChunkCount: Int,
    val packages: List<HexArchiveResourcePackage>
)

internal fun parseAndroidResourcesTable(bytes: ByteArray): AndroidResourcesTableSummary? {
    if (bytes.size < ANDROID_RESOURCE_TABLE_HEADER_SIZE ||
        bytes.u16(0, HexEndian.LITTLE) != ANDROID_RES_TABLE_TYPE
    ) {
        return null
    }

    val headerSize = bytes.u16(2, HexEndian.LITTLE)
    if (headerSize < ANDROID_RESOURCE_TABLE_HEADER_SIZE || headerSize > bytes.size) return null
    val packageCount = bytes.u32(8, HexEndian.LITTLE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    var cursor = headerSize
    var globalStringCount = 0
    var typeSpecCount = 0
    var typeChunkCount = 0
    val packages = mutableListOf<HexArchiveResourcePackage>()

    while (cursor + ANDROID_CHUNK_HEADER_SIZE <= bytes.size) {
        val chunkType = bytes.u16(cursor, HexEndian.LITTLE)
        val chunkSize = bytes.u32(cursor + 4, HexEndian.LITTLE)
        if (chunkSize < ANDROID_CHUNK_HEADER_SIZE ||
            chunkSize > Int.MAX_VALUE.toLong() ||
            cursor + chunkSize > bytes.size
        ) {
            break
        }

        when (chunkType) {
            ANDROID_RES_STRING_POOL_TYPE -> {
                if (globalStringCount == 0) {
                    globalStringCount = readAndroidStringPoolCount(bytes, cursor, chunkSize.toInt())
                }
            }
            ANDROID_RES_TABLE_PACKAGE_TYPE -> {
                parseAndroidResourcePackage(bytes, cursor, chunkSize.toInt())?.let { resourcePackage ->
                    packages += resourcePackage
                    typeSpecCount += resourcePackage.typeSpecCount
                    typeChunkCount += resourcePackage.typeChunkCount
                }
            }
        }
        cursor += chunkSize.toInt()
    }

    if (packageCount == 0 && globalStringCount == 0 && packages.isEmpty()) return null
    return AndroidResourcesTableSummary(
        packageCountFromHeader = packageCount,
        globalStringCount = globalStringCount,
        typeSpecCount = typeSpecCount,
        typeChunkCount = typeChunkCount,
        packages = packages
    )
}

internal fun parseAndroidResourcePackage(
    bytes: ByteArray,
    packageOffset: Int,
    packageSize: Int
): HexArchiveResourcePackage? {
    if (packageSize < ANDROID_RESOURCE_PACKAGE_HEADER_SIZE ||
        packageOffset + ANDROID_RESOURCE_PACKAGE_HEADER_SIZE > bytes.size
    ) {
        return null
    }

    val headerSize = bytes.u16(packageOffset + 2, HexEndian.LITTLE)
    val packageEnd = packageOffset + packageSize
    val typeStringsOffset = bytes.u32(packageOffset + 268, HexEndian.LITTLE).toInt()
    val keyStringsOffset = bytes.u32(packageOffset + 276, HexEndian.LITTLE).toInt()
    val packageId = bytes.u32(packageOffset + 8, HexEndian.LITTLE).toInt()
    val packageName = readAndroidUtf16FixedString(
        bytes = bytes,
        offset = packageOffset + 12,
        maxChars = ANDROID_RESOURCE_PACKAGE_NAME_CHARS,
        limit = packageEnd
    )

    val typeStringCount = readAndroidPackageStringPoolCount(
        bytes = bytes,
        packageOffset = packageOffset,
        packageEnd = packageEnd,
        relativeOffset = typeStringsOffset
    )
    val keyStringCount = readAndroidPackageStringPoolCount(
        bytes = bytes,
        packageOffset = packageOffset,
        packageEnd = packageEnd,
        relativeOffset = keyStringsOffset
    )

    var cursor = packageOffset + headerSize
    var typeSpecCount = 0
    var typeChunkCount = 0
    while (cursor + ANDROID_CHUNK_HEADER_SIZE <= packageEnd && cursor + ANDROID_CHUNK_HEADER_SIZE <= bytes.size) {
        val chunkType = bytes.u16(cursor, HexEndian.LITTLE)
        val chunkSize = bytes.u32(cursor + 4, HexEndian.LITTLE)
        if (chunkSize < ANDROID_CHUNK_HEADER_SIZE ||
            chunkSize > Int.MAX_VALUE.toLong() ||
            cursor + chunkSize > packageEnd ||
            cursor + chunkSize > bytes.size
        ) {
            break
        }

        when (chunkType) {
            ANDROID_RES_TABLE_TYPE_SPEC_TYPE -> typeSpecCount++
            ANDROID_RES_TABLE_TYPE_TYPE -> typeChunkCount++
        }
        cursor += chunkSize.toInt()
    }

    return HexArchiveResourcePackage(
        id = packageId,
        name = packageName,
        typeStringCount = typeStringCount,
        keyStringCount = keyStringCount,
        typeSpecCount = typeSpecCount,
        typeChunkCount = typeChunkCount
    )
}

internal fun readAndroidPackageStringPoolCount(
    bytes: ByteArray,
    packageOffset: Int,
    packageEnd: Int,
    relativeOffset: Int
): Int {
    if (relativeOffset <= 0) return 0
    val stringPoolOffset = packageOffset + relativeOffset
    if (stringPoolOffset + ANDROID_CHUNK_HEADER_SIZE > packageEnd ||
        stringPoolOffset + ANDROID_CHUNK_HEADER_SIZE > bytes.size ||
        bytes.u16(stringPoolOffset, HexEndian.LITTLE) != ANDROID_RES_STRING_POOL_TYPE
    ) {
        return 0
    }
    val chunkSize = bytes.u32(stringPoolOffset + 4, HexEndian.LITTLE)
    if (chunkSize < ANDROID_STRING_POOL_HEADER_SIZE ||
        chunkSize > Int.MAX_VALUE.toLong() ||
        stringPoolOffset + chunkSize > packageEnd ||
        stringPoolOffset + chunkSize > bytes.size
    ) {
        return 0
    }
    return readAndroidStringPoolCount(bytes, stringPoolOffset, chunkSize.toInt())
}

internal fun readAndroidStringPoolCount(
    bytes: ByteArray,
    chunkOffset: Int,
    chunkSize: Int
): Int {
    if (chunkSize < ANDROID_STRING_POOL_HEADER_SIZE ||
        chunkOffset + ANDROID_STRING_POOL_HEADER_SIZE > bytes.size
    ) {
        return 0
    }
    return bytes.u32(chunkOffset + 8, HexEndian.LITTLE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun readAndroidUtf16FixedString(
    bytes: ByteArray,
    offset: Int,
    maxChars: Int,
    limit: Int
): String {
    val chars = StringBuilder()
    repeat(maxChars) { index ->
        val charOffset = offset + index * Short.SIZE_BYTES
        if (charOffset + Short.SIZE_BYTES > limit || charOffset + Short.SIZE_BYTES > bytes.size) {
            return@repeat
        }
        val codeUnit = bytes.u16(charOffset, HexEndian.LITTLE)
        if (codeUnit == 0) return chars.toString()
        chars.append(codeUnit.toChar())
    }
    return chars.toString()
}

internal fun parseAndroidBinaryManifest(bytes: ByteArray): AndroidBinaryManifestSummary? {
    if (bytes.size < ANDROID_CHUNK_HEADER_SIZE ||
        bytes.u16(0, HexEndian.LITTLE) != ANDROID_RES_XML_TYPE
    ) {
        return null
    }

    val fileHeaderSize = bytes.u16(2, HexEndian.LITTLE).coerceAtLeast(ANDROID_CHUNK_HEADER_SIZE)
    if (fileHeaderSize > bytes.size) return null
    var cursor = fileHeaderSize
    var stringPool = emptyList<String>()
    var elementCount = 0
    var rootElementName: String? = null
    var packageName: String? = null
    val permissions = mutableListOf<String>()

    while (cursor + ANDROID_CHUNK_HEADER_SIZE <= bytes.size) {
        val chunkType = bytes.u16(cursor, HexEndian.LITTLE)
        val chunkSize = bytes.u32(cursor + 4, HexEndian.LITTLE)
        if (chunkSize < ANDROID_CHUNK_HEADER_SIZE ||
            chunkSize > Int.MAX_VALUE.toLong() ||
            cursor + chunkSize > bytes.size
        ) {
            break
        }

        when (chunkType) {
            ANDROID_RES_STRING_POOL_TYPE -> {
                stringPool = parseAndroidStringPool(bytes, cursor, chunkSize.toInt())
            }
            ANDROID_RES_XML_START_ELEMENT_TYPE -> {
                val element = parseAndroidXmlStartElement(bytes, cursor, stringPool)
                elementCount++
                if (rootElementName == null) {
                    rootElementName = element?.name
                    packageName = element?.attributes?.get(ANDROID_MANIFEST_PACKAGE_ATTRIBUTE)
                }
                if (element != null && element.name in ANDROID_MANIFEST_PERMISSION_ELEMENTS) {
                    element.attributes[ANDROID_MANIFEST_NAME_ATTRIBUTE]?.let { permission ->
                        if (permissions.size < MAX_ARCHIVE_MANIFEST_PERMISSIONS) {
                            permissions += permission
                        }
                    }
                }
            }
        }
        cursor += chunkSize.toInt()
    }

    if (stringPool.isEmpty() && elementCount == 0) return null
    return AndroidBinaryManifestSummary(
        stringCount = stringPool.size,
        elementCount = elementCount,
        rootElementName = rootElementName,
        packageName = packageName,
        permissions = permissions
    )
}

internal fun parseAndroidStringPool(
    bytes: ByteArray,
    chunkOffset: Int,
    chunkSize: Int
): List<String> {
    if (chunkSize < ANDROID_STRING_POOL_HEADER_SIZE ||
        chunkOffset + ANDROID_STRING_POOL_HEADER_SIZE > bytes.size
    ) {
        return emptyList()
    }

    val headerSize = bytes.u16(chunkOffset + 2, HexEndian.LITTLE)
    val stringCount = bytes.u32(chunkOffset + 8, HexEndian.LITTLE).coerceAtMost(MAX_ARCHIVE_MANIFEST_STRINGS.toLong())
        .toInt()
    val flags = bytes.u32(chunkOffset + 16, HexEndian.LITTLE)
    val stringsStart = bytes.u32(chunkOffset + 20, HexEndian.LITTLE)
    val offsetsStart = chunkOffset + headerSize
    val stringsBase = chunkOffset + stringsStart.toInt()
    if (stringCount <= 0 ||
        offsetsStart < chunkOffset ||
        stringsBase < chunkOffset ||
        stringsBase >= chunkOffset + chunkSize
    ) {
        return emptyList()
    }

    val isUtf8 = (flags and ANDROID_STRING_POOL_UTF8_FLAG) != 0L
    return (0 until stringCount).mapNotNull { index ->
        val offsetIndex = offsetsStart + index * Int.SIZE_BYTES
        if (offsetIndex + Int.SIZE_BYTES > chunkOffset + chunkSize) return@mapNotNull null
        val stringOffset = bytes.u32(offsetIndex, HexEndian.LITTLE)
        val absoluteOffset = stringsBase + stringOffset.toInt()
        if (absoluteOffset !in chunkOffset until (chunkOffset + chunkSize)) return@mapNotNull null
        if (isUtf8) {
            readAndroidUtf8String(bytes, absoluteOffset, chunkOffset + chunkSize)
        } else {
            readAndroidUtf16String(bytes, absoluteOffset, chunkOffset + chunkSize)
        }
    }
}

internal fun parseAndroidXmlStartElement(
    bytes: ByteArray,
    chunkOffset: Int,
    stringPool: List<String>
): AndroidXmlStartElement? {
    if (chunkOffset + ANDROID_XML_START_ELEMENT_HEADER_SIZE > bytes.size) return null
    val elementName = stringPool.getOrNull(bytes.u32(chunkOffset + 20, HexEndian.LITTLE).toInt())
    val attributeStart = bytes.u16(chunkOffset + 24, HexEndian.LITTLE)
    val attributeSize = bytes.u16(chunkOffset + 26, HexEndian.LITTLE)
    val attributeCount = bytes.u16(chunkOffset + 28, HexEndian.LITTLE)
    if (attributeSize < ANDROID_XML_ATTRIBUTE_SIZE) return AndroidXmlStartElement(elementName, emptyMap())

    val attributesOffset = chunkOffset + ANDROID_XML_ATTRIBUTE_EXTENSION_OFFSET + attributeStart
    val attributes = linkedMapOf<String, String>()
    repeat(attributeCount) { index ->
        val attributeOffset = attributesOffset + index * attributeSize
        if (attributeOffset + ANDROID_XML_ATTRIBUTE_SIZE > bytes.size) return@repeat
        val name = stringPool.getOrNull(bytes.u32(attributeOffset + 4, HexEndian.LITTLE).toInt()) ?: return@repeat
        val value = readAndroidXmlAttributeValue(bytes, attributeOffset, stringPool) ?: return@repeat
        attributes[name] = value
    }
    return AndroidXmlStartElement(elementName, attributes)
}

internal fun readAndroidXmlAttributeValue(
    bytes: ByteArray,
    attributeOffset: Int,
    stringPool: List<String>
): String? {
    val rawValueIndex = bytes.u32(attributeOffset + 8, HexEndian.LITTLE)
    if (rawValueIndex != ANDROID_NO_INDEX) {
        return stringPool.getOrNull(rawValueIndex.toInt())
    }
    val dataTypeOffset = attributeOffset + 15
    val dataOffset = attributeOffset + 16
    if (dataOffset + Int.SIZE_BYTES > bytes.size) return null
    return when (bytes[dataTypeOffset].toInt() and 0xFF) {
        ANDROID_TYPED_VALUE_STRING -> stringPool.getOrNull(bytes.u32(dataOffset, HexEndian.LITTLE).toInt())
        else -> null
    }
}

internal fun readAndroidUtf8String(
    bytes: ByteArray,
    offset: Int,
    limit: Int
): String? {
    val utf16Length = readAndroidUtf8Length(bytes, offset, limit) ?: return null
    val utf8Length = readAndroidUtf8Length(bytes, offset + utf16Length.bytesRead, limit) ?: return null
    val stringOffset = offset + utf16Length.bytesRead + utf8Length.bytesRead
    val stringEnd = stringOffset + utf8Length.value
    if (stringEnd > limit || stringEnd >= bytes.size) return null
    return bytes.copyOfRange(stringOffset, stringEnd).toString(Charsets.UTF_8)
}

internal fun readAndroidUtf16String(
    bytes: ByteArray,
    offset: Int,
    limit: Int
): String? {
    val length = readAndroidUtf16Length(bytes, offset, limit) ?: return null
    val stringOffset = offset + length.bytesRead
    val stringEnd = stringOffset + length.value * Short.SIZE_BYTES
    if (stringEnd > limit || stringEnd > bytes.size) return null
    return bytes.copyOfRange(stringOffset, stringEnd).toString(Charsets.UTF_16LE)
}

internal fun readAndroidUtf8Length(
    bytes: ByteArray,
    offset: Int,
    limit: Int
): AndroidStringLength? {
    if (offset >= limit || offset >= bytes.size) return null
    val first = bytes[offset].toInt() and 0xFF
    return if ((first and 0x80) == 0) {
        AndroidStringLength(value = first, bytesRead = 1)
    } else {
        if (offset + 1 >= limit || offset + 1 >= bytes.size) return null
        val second = bytes[offset + 1].toInt() and 0xFF
        AndroidStringLength(value = ((first and 0x7F) shl 8) or second, bytesRead = 2)
    }
}

internal fun readAndroidUtf16Length(
    bytes: ByteArray,
    offset: Int,
    limit: Int
): AndroidStringLength? {
    if (offset + Short.SIZE_BYTES > limit || offset + Short.SIZE_BYTES > bytes.size) return null
    val first = bytes.u16(offset, HexEndian.LITTLE)
    return if ((first and 0x8000) == 0) {
        AndroidStringLength(value = first, bytesRead = Short.SIZE_BYTES)
    } else {
        if (offset + Int.SIZE_BYTES > limit || offset + Int.SIZE_BYTES > bytes.size) return null
        val second = bytes.u16(offset + Short.SIZE_BYTES, HexEndian.LITTLE)
        AndroidStringLength(value = ((first and 0x7FFF) shl 16) or second, bytesRead = Int.SIZE_BYTES)
    }
}

internal fun readArchiveDexSummaries(
    file: File,
    entries: List<HexArchiveEntry>
): List<HexArchiveDexSummary> {
    val dexEntries = entries
        .filter { entry -> entry.name.endsWith(".dex", ignoreCase = true) }
        .take(MAX_ARCHIVE_DEX_SUMMARIES)
    if (dexEntries.isEmpty()) return emptyList()

    return runCatching {
        ZipFile(file).use { zipFile ->
            dexEntries.mapNotNull { archiveEntry ->
                val zipEntry = zipFile.getEntry(archiveEntry.name) ?: return@mapNotNull null
                val maxBytes = minOf(
                    archiveEntry.uncompressedSize.takeIf { size -> size > 0L } ?: Long.MAX_VALUE,
                    MAX_ARCHIVE_DEX_ANALYSIS_BYTES.toLong()
                ).coerceToInt()
                val dexBytes = zipFile.getInputStream(zipEntry).use { input ->
                    input.readAtMost(maxBytes)
                }
                val dex = parseDexSummary(dexBytes) ?: return@mapNotNull null
                HexArchiveDexSummary(
                    entryName = archiveEntry.name,
                    localHeaderOffset = archiveEntry.localHeaderOffset,
                    analyzedBytes = dexBytes.size.toLong(),
                    truncated = zipEntry.size > dexBytes.size && zipEntry.size >= 0L,
                    dex = dex
                )
            }
        }
    }.getOrElse { emptyList() }
}

internal fun readArchiveNativeLibrarySummaries(
    file: File,
    entries: List<HexArchiveEntry>
): List<HexArchiveNativeLibrarySummary> {
    val nativeEntries = entries
        .filter { entry ->
            entry.name.startsWith("lib/", ignoreCase = true) && entry.name.endsWith(".so", ignoreCase = true)
        }
        .take(MAX_ARCHIVE_NATIVE_LIBRARY_SUMMARIES)
    if (nativeEntries.isEmpty()) return emptyList()

    return runCatching {
        ZipFile(file).use { zipFile ->
            nativeEntries.mapNotNull { archiveEntry ->
                val zipEntry = zipFile.getEntry(archiveEntry.name) ?: return@mapNotNull null
                val maxBytes = minOf(
                    archiveEntry.uncompressedSize.takeIf { size -> size > 0L } ?: Long.MAX_VALUE,
                    MAX_ARCHIVE_NATIVE_ANALYSIS_BYTES.toLong()
                ).coerceToInt()
                val nativeBytes = zipFile.getInputStream(zipEntry).use { input ->
                    input.readAtMost(maxBytes)
                }
                val elfHeader = parseArchiveNativeElfHeader(nativeBytes)
                HexArchiveNativeLibrarySummary(
                    entryName = archiveEntry.name,
                    abi = archiveNativeAbi(archiveEntry.name),
                    fileName = archiveNativeFileName(archiveEntry.name),
                    localHeaderOffset = archiveEntry.localHeaderOffset,
                    dataOffset = archiveEntry.dataOffset,
                    compressionMethod = archiveEntry.compressionMethod,
                    loadMode = archiveNativeLoadMode(
                        compressionMethod = archiveEntry.compressionMethod,
                        dataOffset = archiveEntry.dataOffset
                    ),
                    pageAlignmentRemainder = archiveNativePageAlignmentRemainder(archiveEntry.dataOffset),
                    crc32 = archiveEntry.crc32,
                    compressedSize = archiveEntry.compressedSize,
                    uncompressedSize = archiveEntry.uncompressedSize,
                    analyzedBytes = nativeBytes.size.toLong(),
                    truncated = zipEntry.size > nativeBytes.size && zipEntry.size >= 0L,
                    isElf = elfHeader != null,
                    is64Bit = elfHeader?.is64Bit,
                    endian = elfHeader?.endian,
                    machineName = elfHeader?.machineName,
                    obfuscationMarkers = scanArchiveNativeObfuscationMarkers(nativeBytes)
                )
            }
        }
    }.getOrElse { emptyList() }
}

internal fun archiveNativeLoadMode(
    compressionMethod: Int,
    dataOffset: Long?
): HexArchiveNativeLoadMode = when {
    compressionMethod != ZIP_COMPRESSION_METHOD_STORED -> HexArchiveNativeLoadMode.NEEDS_DECOMPRESSION
    dataOffset == null -> HexArchiveNativeLoadMode.UNKNOWN
    dataOffset % APK_NATIVE_LIBRARY_PAGE_ALIGNMENT == 0L -> HexArchiveNativeLoadMode.DIRECT_MMAP_READY
    else -> HexArchiveNativeLoadMode.STORED_UNALIGNED
}

internal fun archiveNativePageAlignmentRemainder(dataOffset: Long?): Long? = dataOffset?.floorMod(APK_NATIVE_LIBRARY_PAGE_ALIGNMENT)

internal data class ArchiveNativeElfHeader(
    val is64Bit: Boolean,
    val endian: HexEndian,
    val machineName: String
)

internal fun parseArchiveNativeElfHeader(bytes: ByteArray): ArchiveNativeElfHeader? {
    if (bytes.size < ELF_IDENT_SIZE || !bytes.startsWith(0x7F, 'E'.code, 'L'.code, 'F'.code)) return null

    val is64Bit = when (bytes[ELF_CLASS_OFFSET].toInt() and 0xFF) {
        ELF_CLASS_32 -> false
        ELF_CLASS_64 -> true
        else -> return null
    }
    val endian = when (bytes[ELF_DATA_OFFSET].toInt() and 0xFF) {
        ELF_DATA_LITTLE -> HexEndian.LITTLE
        ELF_DATA_BIG -> HexEndian.BIG
        else -> return null
    }
    if (bytes.size < 20) return null

    return ArchiveNativeElfHeader(
        is64Bit = is64Bit,
        endian = endian,
        machineName = elfMachineName(bytes.u16(18, endian))
    )
}

internal fun scanArchiveNativeObfuscationMarkers(bytes: ByteArray): List<HexArchiveNativeObfuscationMarker> {
    val strings = extractPrintableAsciiStrings(bytes)
    val markers = mutableListOf<HexArchiveNativeObfuscationMarker>()

    fun addMarker(
        type: HexObfuscationFindingType,
        vararg keywords: String
    ) {
        val match = strings.firstOrNull { entry ->
            val normalized = entry.value.lowercase()
            keywords.any { keyword -> normalized.contains(keyword) }
        } ?: return
        if (markers.none { marker -> marker.type == type }) {
            markers += HexArchiveNativeObfuscationMarker(
                type = type,
                evidence = match.value,
                relativeOffset = match.offset
            )
        }
    }

    addMarker(
        HexObfuscationFindingType.OLLVM_MARKER,
        "ollvm",
        "obfuscator-llvm",
        "obfuscator llvm"
    )
    addMarker(
        HexObfuscationFindingType.CONTROL_FLOW_FLATTENING_MARKER,
        "ollvm-fla",
        "control flow flattening",
        "control-flow-flattening"
    )
    addMarker(
        HexObfuscationFindingType.BOGUS_CONTROL_FLOW_MARKER,
        "ollvm-bcf",
        "bogus control flow",
        "bogus-control-flow"
    )
    addMarker(
        HexObfuscationFindingType.INSTRUCTION_SUBSTITUTION_MARKER,
        "ollvm-sub",
        "instruction substitution",
        "substitution pass"
    )
    addMarker(
        HexObfuscationFindingType.PROTECTOR_PACKER_MARKER,
        *ANDROID_PROTECTOR_PACKER_KEYWORDS
    )

    return markers.take(MAX_ARCHIVE_NATIVE_OBFUSCATION_MARKERS)
}

internal fun archiveNativeAbi(entryName: String): String = entryName
    .split('/')
    .getOrNull(1)
    .orEmpty()

internal fun archiveNativeFileName(entryName: String): String = entryName.substringAfterLast('/')

internal fun parseElfSummary(randomAccessFile: RandomAccessFile, header: ByteArray): HexElfSummary? {
    if (header.size < ELF_IDENT_SIZE || !header.startsWith(0x7F, 'E'.code, 'L'.code, 'F'.code)) return null

    val is64Bit = when (header[ELF_CLASS_OFFSET].toInt() and 0xFF) {
        ELF_CLASS_32 -> false
        ELF_CLASS_64 -> true
        else -> return null
    }
    val endian = when (header[ELF_DATA_OFFSET].toInt() and 0xFF) {
        ELF_DATA_LITTLE -> HexEndian.LITTLE
        ELF_DATA_BIG -> HexEndian.BIG
        else -> return null
    }

    val requiredHeaderSize = if (is64Bit) ELF64_HEADER_SIZE else ELF32_HEADER_SIZE
    val fullHeader = if (header.size >= requiredHeaderSize) {
        header
    } else {
        randomAccessFile.readAt(0L, requiredHeaderSize)
    }
    if (fullHeader.size < requiredHeaderSize) return null

    val type = fullHeader.u16(16, endian)
    val machine = fullHeader.u16(18, endian)
    val entryPoint = if (is64Bit) fullHeader.u64(24, endian) else fullHeader.u32(24, endian)
    val programHeaderOffset = if (is64Bit) fullHeader.u64(32, endian) else fullHeader.u32(28, endian)
    val sectionHeaderOffset = if (is64Bit) fullHeader.u64(40, endian) else fullHeader.u32(32, endian)
    val programHeaderEntrySize = fullHeader.u16(if (is64Bit) 54 else 42, endian)
    val programHeaderCount = fullHeader.u16(if (is64Bit) 56 else 44, endian)
    val sectionHeaderEntrySize = fullHeader.u16(if (is64Bit) 58 else 46, endian)
    val sectionHeaderCount = fullHeader.u16(if (is64Bit) 60 else 48, endian)
    val sectionNameTableIndex = fullHeader.u16(if (is64Bit) 62 else 50, endian)

    val programHeaders = readElfProgramHeaders(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        programHeaderOffset = programHeaderOffset,
        programHeaderEntrySize = programHeaderEntrySize,
        programHeaderCount = programHeaderCount
    )
    val loadSegments = programHeaders.mapNotNull { programHeader -> programHeader.toLoadSegment() }
    val sections = readElfSectionHeaders(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sectionHeaderOffset = sectionHeaderOffset,
        sectionHeaderEntrySize = sectionHeaderEntrySize,
        sectionHeaderCount = sectionHeaderCount,
        sectionNameTableIndex = sectionNameTableIndex
    )
    val sectionSegmentMappings = buildElfSectionSegmentMappings(
        sections = sections,
        programHeaders = programHeaders
    )
    val sectionEntropyEntries = readElfSectionEntropyEntries(
        randomAccessFile = randomAccessFile,
        sections = sections
    )
    val sectionNames = sections.mapNotNull { section -> section.name.takeIf { it.isNotBlank() } }
    val noteEntries = readElfNoteEntries(
        randomAccessFile = randomAccessFile,
        endian = endian,
        machine = machine,
        sections = sections
    )
    val dynamicSymbols = readElfDynamicSymbols(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections,
        loadSegments = loadSegments
    )
    val nativeApiHints = buildElfNativeApiHints(dynamicSymbols)
    val dynamicStringEntries = readElfDynamicStringEntries(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections
    )
    val dynamicFlagEntries = readElfDynamicFlagEntries(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections
    )
    val initArrayEntries = readElfInitArrayEntries(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        sections = sections,
        loadSegments = loadSegments
    )
    val relocations = readElfRelocations(
        randomAccessFile = randomAccessFile,
        is64Bit = is64Bit,
        endian = endian,
        machine = machine,
        sections = sections,
        loadSegments = loadSegments
    )
    val linkageEntries = buildElfLinkageEntries(
        randomAccessFile = randomAccessFile,
        machine = machine,
        endian = endian,
        sections = sections,
        relocations = relocations,
        bindNow = dynamicFlagEntries.any { entry -> entry.isBindNow }
    )
    val hardeningChecks = buildElfHardeningChecks(
        elfType = type,
        programHeaders = programHeaders,
        dynamicFlagEntries = dynamicFlagEntries,
        noteEntries = noteEntries
    )
    val riskFindings = buildElfRiskFindings(
        programHeaders = programHeaders,
        sections = sections,
        hardeningChecks = hardeningChecks,
        dynamicStringEntries = dynamicStringEntries
    )
    val dynamicLinkerSteps = buildElfDynamicLinkerSteps(
        programHeaders = programHeaders,
        dynamicStringEntries = dynamicStringEntries,
        hardeningChecks = hardeningChecks,
        initArrayEntries = initArrayEntries,
        linkageEntries = linkageEntries,
        dynamicSymbols = dynamicSymbols
    )

    return HexElfSummary(
        is64Bit = is64Bit,
        endian = endian,
        type = type,
        machine = machine,
        machineName = elfMachineName(machine),
        entryPoint = entryPoint,
        programHeaderCount = if (programHeaderEntrySize > 0) programHeaderCount else 0,
        sectionHeaderCount = if (sectionHeaderEntrySize > 0) sectionHeaderCount else 0,
        sectionNames = sectionNames,
        sections = sections,
        noteEntries = noteEntries,
        programHeaders = programHeaders,
        loadSegments = loadSegments,
        sectionSegmentMappings = sectionSegmentMappings,
        sectionEntropyEntries = sectionEntropyEntries,
        hardeningChecks = hardeningChecks,
        riskFindings = riskFindings,
        dynamicSymbols = dynamicSymbols,
        dynamicStringEntries = dynamicStringEntries,
        dynamicFlagEntries = dynamicFlagEntries,
        initArrayEntries = initArrayEntries,
        relocations = relocations,
        linkageEntries = linkageEntries,
        dynamicLinkerSteps = dynamicLinkerSteps,
        nativeApiHints = nativeApiHints
    )
}

internal fun readElfProgramHeaders(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    programHeaderOffset: Long,
    programHeaderEntrySize: Int,
    programHeaderCount: Int
): List<HexElfProgramHeader> {
    if (programHeaderOffset <= 0L || programHeaderEntrySize <= 0 || programHeaderCount <= 0) return emptyList()
    if (programHeaderOffset >= randomAccessFile.length()) return emptyList()

    val safeProgramHeaderCount = programHeaderCount.coerceAtMost(MAX_ELF_PROGRAM_HEADERS)
    val programHeaders = mutableListOf<HexElfProgramHeader>()
    for (programHeaderIndex in 0 until safeProgramHeaderCount) {
        val programHeaderFileOffset = programHeaderOffset + programHeaderIndex.toLong() * programHeaderEntrySize
        val programHeader = randomAccessFile.readAt(
            offset = programHeaderFileOffset,
            byteCount = programHeaderEntrySize
        )
        if (programHeader.size < programHeaderEntrySize) break

        val type = programHeader.u32(0, endian)
        val flags = if (is64Bit) {
            programHeader.u32(4, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            programHeader.u32(24, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        programHeaders += HexElfProgramHeader(
            index = programHeaderIndex,
            type = type,
            typeName = elfProgramHeaderTypeName(type),
            programHeaderFileOffset = programHeaderFileOffset,
            fileOffset = if (is64Bit) programHeader.u64(8, endian) else programHeader.u32(4, endian),
            virtualAddress = if (is64Bit) programHeader.u64(16, endian) else programHeader.u32(8, endian),
            physicalAddress = if (is64Bit) programHeader.u64(24, endian) else programHeader.u32(12, endian),
            fileSize = if (is64Bit) programHeader.u64(32, endian) else programHeader.u32(16, endian),
            memorySize = if (is64Bit) programHeader.u64(40, endian) else programHeader.u32(20, endian),
            flags = flags,
            align = if (is64Bit) programHeader.u64(48, endian) else programHeader.u32(28, endian)
        )
    }
    return programHeaders
}

internal fun readElfSectionHeaders(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sectionHeaderOffset: Long,
    sectionHeaderEntrySize: Int,
    sectionHeaderCount: Int,
    sectionNameTableIndex: Int
): List<HexElfSection> {
    if (sectionHeaderOffset <= 0L || sectionHeaderEntrySize <= 0 || sectionHeaderCount <= 0) return emptyList()
    if (sectionNameTableIndex !in 0 until sectionHeaderCount) return emptyList()
    if (sectionHeaderOffset >= randomAccessFile.length()) return emptyList()

    val safeSectionCount = sectionHeaderCount.coerceAtMost(MAX_ELF_SECTION_HEADERS)
    val nameTableHeaderOffset = sectionHeaderOffset + sectionNameTableIndex.toLong() * sectionHeaderEntrySize
    val nameTableHeader = randomAccessFile.readAt(nameTableHeaderOffset, sectionHeaderEntrySize)
    if (nameTableHeader.size < sectionHeaderEntrySize) return emptyList()

    val nameTableOffset = if (is64Bit) nameTableHeader.u64(24, endian) else nameTableHeader.u32(16, endian)
    val nameTableSize = if (is64Bit) nameTableHeader.u64(32, endian) else nameTableHeader.u32(20, endian)
    if (nameTableOffset <= 0L || nameTableSize <= 0L || nameTableOffset >= randomAccessFile.length()) return emptyList()

    val safeNameTableSize = minOf(nameTableSize, randomAccessFile.length() - nameTableOffset, MAX_ELF_STRING_TABLE_BYTES.toLong()).toInt()
    val nameTable = randomAccessFile.readAt(nameTableOffset, safeNameTableSize)
    if (nameTable.isEmpty()) return emptyList()

    val sections = mutableListOf<HexElfSection>()
    for (sectionIndex in 0 until safeSectionCount) {
        val sectionHeader = randomAccessFile.readAt(
            offset = sectionHeaderOffset + sectionIndex.toLong() * sectionHeaderEntrySize,
            byteCount = sectionHeaderEntrySize
        )
        if (sectionHeader.size < sectionHeaderEntrySize) break

        val nameOffset = sectionHeader.u32(0, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        sections += HexElfSection(
            index = sectionIndex,
            name = nameTable.readNullTerminatedAscii(nameOffset),
            type = sectionHeader.u32(4, endian),
            flags = if (is64Bit) sectionHeader.u64(8, endian) else sectionHeader.u32(8, endian),
            virtualAddress = if (is64Bit) sectionHeader.u64(16, endian) else sectionHeader.u32(12, endian),
            fileOffset = if (is64Bit) sectionHeader.u64(24, endian) else sectionHeader.u32(16, endian),
            size = if (is64Bit) sectionHeader.u64(32, endian) else sectionHeader.u32(20, endian),
            link = sectionHeader.u32(if (is64Bit) 40 else 24, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            entrySize = if (is64Bit) sectionHeader.u64(56, endian) else sectionHeader.u32(36, endian)
        )
    }
    return sections
}

internal fun buildElfSectionSegmentMappings(
    sections: List<HexElfSection>,
    programHeaders: List<HexElfProgramHeader>
): List<HexElfSectionSegmentMapping> {
    if (sections.isEmpty() || programHeaders.isEmpty()) return emptyList()
    val loadProgramHeaders = programHeaders.filter { programHeader ->
        programHeader.isLoad && programHeader.fileSize > 0L
    }
    if (loadProgramHeaders.isEmpty()) return emptyList()

    val mappings = mutableListOf<HexElfSectionSegmentMapping>()
    sections.asSequence()
        .filter { section -> section.size > 0L && section.type != ELF_SECTION_TYPE_NOBITS.toLong() }
        .forEach { section ->
            val loadProgramHeader = loadProgramHeaders.firstOrNull { programHeader ->
                programHeader.containsFileRange(section.fileOffset, section.size)
            } ?: return@forEach
            mappings += HexElfSectionSegmentMapping(
                index = mappings.size,
                sectionIndex = section.index,
                sectionName = section.name,
                sectionFileOffset = section.fileOffset,
                sectionSize = section.size,
                sectionVirtualAddress = section.virtualAddress,
                segmentIndex = loadProgramHeader.index,
                segmentTypeName = loadProgramHeader.typeName,
                segmentFileOffset = loadProgramHeader.fileOffset,
                segmentFileSize = loadProgramHeader.fileSize,
                segmentVirtualAddress = loadProgramHeader.virtualAddress,
                segmentMemorySize = loadProgramHeader.memorySize,
                segmentFlags = loadProgramHeader.flags,
                isExecutable = loadProgramHeader.isExecutable,
                isWritable = loadProgramHeader.isWritable,
                isReadable = loadProgramHeader.isReadable
            )
        }
    return mappings
}

internal fun readElfSectionEntropyEntries(
    randomAccessFile: RandomAccessFile,
    sections: List<HexElfSection>
): List<HexElfSectionEntropyEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfSectionEntropyEntry>()
    sections.asSequence()
        .filter { section -> section.size > 0L && section.type != ELF_SECTION_TYPE_NOBITS.toLong() }
        .filter { section -> section.fileOffset >= 0L && section.fileOffset < randomAccessFile.length() }
        .take(MAX_ELF_SECTION_ENTROPY_ENTRIES)
        .forEach { section ->
            val sampleSize = minOf(
                section.size,
                randomAccessFile.length() - section.fileOffset,
                ENTROPY_SAMPLE_BYTES.toLong()
            ).coerceAtLeast(0L)
            if (sampleSize <= 0L) return@forEach
            val bytes = randomAccessFile.readAt(section.fileOffset, sampleSize.coerceToInt())
            if (bytes.isEmpty()) return@forEach
            val entropy = bytes.shannonEntropy()
            entries += HexElfSectionEntropyEntry(
                index = entries.size,
                sectionIndex = section.index,
                sectionName = section.name,
                fileOffset = section.fileOffset,
                size = section.size,
                virtualAddress = section.virtualAddress,
                sampleSize = bytes.size.toLong(),
                entropy = entropy,
                level = entropyLevel(entropy),
                isAllocated = section.flags.hasElfFlag(ELF_SECTION_FLAG_ALLOC),
                isExecutable = section.flags.hasElfFlag(ELF_SECTION_FLAG_EXECINSTR),
                isWritable = section.flags.hasElfFlag(ELF_SECTION_FLAG_WRITE)
            )
        }
    return entries
}

internal fun readElfNoteEntries(
    randomAccessFile: RandomAccessFile,
    endian: HexEndian,
    machine: Int,
    sections: List<HexElfSection>
): List<HexElfNoteEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfNoteEntry>()
    val noteSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_NOTE.toLong() || section.name.startsWith(".note")
    }

    for (section in noteSections) {
        if (entries.size >= MAX_ELF_NOTES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val safeSize = minOf(
            section.size,
            randomAccessFile.length() - section.fileOffset,
            MAX_ELF_NOTE_SECTION_BYTES.toLong()
        ).toInt()
        val sectionBytes = randomAccessFile.readAt(section.fileOffset, safeSize)
        if (sectionBytes.size < ELF_NOTE_HEADER_SIZE) continue

        var noteOffset = 0
        while (noteOffset + ELF_NOTE_HEADER_SIZE <= sectionBytes.size && entries.size < MAX_ELF_NOTES) {
            val nameSize = sectionBytes.u32(noteOffset, endian)
            val descriptionSize = sectionBytes.u32(noteOffset + 4, endian)
            val type = sectionBytes.u32(noteOffset + 8, endian)
            if (nameSize == 0L && descriptionSize == 0L && type == 0L) break

            val alignedNameSize = nameSize.alignElfNoteFieldSize()
            val alignedDescriptionSize = descriptionSize.alignElfNoteFieldSize()
            val nameStartOffset = noteOffset + ELF_NOTE_HEADER_SIZE
            val descriptionStartOffset = noteOffset.toLong() + ELF_NOTE_HEADER_SIZE + alignedNameSize
            val nextNoteOffset = descriptionStartOffset + alignedDescriptionSize
            if (descriptionStartOffset > sectionBytes.size || nextNoteOffset > sectionBytes.size) break

            val name = sectionBytes.readElfNoteName(
                offset = nameStartOffset,
                byteCount = nameSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val descriptionBytes = sectionBytes.readElfNoteDescription(
                offset = descriptionStartOffset.toInt(),
                byteCount = descriptionSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val properties = if (isElfGnuPropertyNote(name, type)) {
                readElfGnuPropertyEntries(
                    noteFileOffset = section.fileOffset + noteOffset.toLong(),
                    descriptionOffset = section.fileOffset + descriptionStartOffset,
                    descriptionBytes = descriptionBytes,
                    endian = endian,
                    machine = machine
                )
            } else {
                emptyList()
            }

            entries += HexElfNoteEntry(
                index = entries.size,
                sectionName = section.name,
                name = name,
                type = type,
                noteFileOffset = section.fileOffset + noteOffset.toLong(),
                descriptionOffset = section.fileOffset + descriptionStartOffset,
                descriptionSize = descriptionSize,
                descriptionHex = descriptionBytes.toLowerHexString(),
                descriptionText = descriptionBytes.toPrintableAsciiStringOrNull(),
                isBuildId = isElfBuildIdNote(section.name, name, type),
                properties = properties
            )

            if (nextNoteOffset <= noteOffset.toLong()) break
            noteOffset = nextNoteOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
    return entries
}

internal fun readElfDynamicSymbols(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>,
    loadSegments: List<HexElfLoadSegment>
): List<HexElfSymbol> {
    if (sections.isEmpty()) return emptyList()
    val symbols = mutableListOf<HexElfSymbol>()
    val defaultEntrySize = if (is64Bit) ELF64_SYMBOL_ENTRY_SIZE else ELF32_SYMBOL_ENTRY_SIZE
    val dynamicSymbolSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_DYNAMIC_SYMBOLS.toLong() || section.name == ".dynsym"
    }

    for (symbolSection in dynamicSymbolSections) {
        if (symbols.size >= MAX_ELF_SYMBOLS) break
        val entrySize = symbolSection.entrySize.takeIf { it > 0L } ?: defaultEntrySize.toLong()
        if (entrySize <= 0L || symbolSection.fileOffset <= 0L || symbolSection.size <= 0L) continue
        if (symbolSection.fileOffset >= randomAccessFile.length()) continue

        val stringTableSection = sections.getOrNull(symbolSection.link)
            ?.takeIf { it.type == ELF_SECTION_TYPE_STRING_TABLE.toLong() || it.name.endsWith("str") }
            ?: sections.firstOrNull { it.name == ".dynstr" }
            ?: continue
        val stringTable = readElfStringTable(randomAccessFile, stringTableSection)
        if (stringTable.isEmpty()) continue

        val symbolCount = minOf(symbolSection.size / entrySize, (MAX_ELF_SYMBOLS - symbols.size).toLong()).toInt()
        for (symbolIndex in 0 until symbolCount) {
            val symbolBytes = randomAccessFile.readAt(
                offset = symbolSection.fileOffset + symbolIndex.toLong() * entrySize,
                byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            if (symbolBytes.size < defaultEntrySize) break

            val nameOffset = symbolBytes.u32(0, endian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val name = stringTable.readNullTerminatedAscii(nameOffset)
            if (name.isBlank()) continue

            val info = symbolBytes[if (is64Bit) 4 else 12].toInt() and 0xFF
            val binding = elfSymbolBinding(info ushr 4)
            val type = elfSymbolType(info and 0x0F)
            val sectionIndex = symbolBytes.u16(if (is64Bit) 6 else 14, endian)
            val value = if (is64Bit) symbolBytes.u64(8, endian) else symbolBytes.u32(4, endian)
            val size = if (is64Bit) symbolBytes.u64(16, endian) else symbolBytes.u32(8, endian)
            val isImported = sectionIndex == ELF_SYMBOL_SECTION_UNDEFINED
            val isExportBinding = binding == HexElfSymbolBinding.GLOBAL || binding == HexElfSymbolBinding.WEAK
            val isExportType = type == HexElfSymbolType.FUNC ||
                type == HexElfSymbolType.OBJECT ||
                type == HexElfSymbolType.NOTYPE
            val isExported = !isImported && isExportBinding && isExportType
            val fileOffset = loadSegments.virtualAddressToFileOffset(value)
            val resolvedSection = resolveElfSymbolSection(
                sections = sections,
                sectionIndex = sectionIndex,
                fileOffset = fileOffset
            )

            symbols += HexElfSymbol(
                name = name,
                value = value,
                fileOffset = fileOffset,
                size = size,
                binding = binding,
                type = type,
                sectionIndex = sectionIndex,
                isImported = isImported,
                isExported = isExported,
                isJni = name == "JNI_OnLoad" || name == "JNI_OnUnload" || name.startsWith("Java_"),
                sectionName = resolvedSection?.name?.takeIf { sectionName -> sectionName.isNotBlank() },
                sectionFileOffset = resolvedSection?.fileOffset,
                sectionSize = resolvedSection?.size
            )
        }
    }
    return symbols
}

internal fun resolveElfSymbolSection(
    sections: List<HexElfSection>,
    sectionIndex: Int,
    fileOffset: Long?
): HexElfSection? {
    if (sectionIndex != ELF_SYMBOL_SECTION_UNDEFINED) {
        sections.getOrNull(sectionIndex)
            ?.takeIf { section -> section.index == sectionIndex }
            ?.takeIf { section -> section.type != ELF_SECTION_TYPE_NOBITS.toLong() }
            ?.let { section -> return section }
    }
    val resolvedFileOffset = fileOffset ?: return null
    return sections.firstOrNull { section ->
        section.type != ELF_SECTION_TYPE_NOBITS.toLong() &&
            section.size > 0L &&
            resolvedFileOffset >= section.fileOffset &&
            resolvedFileOffset - section.fileOffset < section.size
    }
}

internal fun readElfDynamicStringEntries(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>
): List<HexElfDynamicStringEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfDynamicStringEntry>()
    val dynamicSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_DYNAMIC.toLong() || section.name == ".dynamic"
    }

    for (section in dynamicSections) {
        if (entries.size >= MAX_ELF_DYNAMIC_ENTRIES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val entrySize = section.entrySize.takeIf { it > 0L }
            ?: if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE.toLong() else ELF32_DYNAMIC_ENTRY_SIZE.toLong()
        if (entrySize <= 0L) continue

        val stringTableSection = sections.getOrNull(section.link)
            ?.takeIf { it.type == ELF_SECTION_TYPE_STRING_TABLE.toLong() || it.name.endsWith("str") }
            ?: sections.firstOrNull { it.name == ".dynstr" }
            ?: continue
        val stringTable = readElfStringTable(randomAccessFile, stringTableSection)
        if (stringTable.isEmpty()) continue

        val dynamicEntryCount = minOf(
            section.size / entrySize,
            (MAX_ELF_DYNAMIC_ENTRIES - entries.size).toLong()
        ).toInt()
        for (entryIndex in 0 until dynamicEntryCount) {
            val entryFileOffset = section.fileOffset + entryIndex.toLong() * entrySize
            val entryBytes = randomAccessFile.readAt(
                offset = entryFileOffset,
                byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val minimumEntrySize = if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE else ELF32_DYNAMIC_ENTRY_SIZE
            if (entryBytes.size < minimumEntrySize) break

            val tag = if (is64Bit) entryBytes.u64(0, endian) else entryBytes.u32(0, endian)
            if (tag == ELF_DYNAMIC_TAG_NULL) break
            val type = elfDynamicStringType(tag) ?: continue
            val rawValueOffset = if (is64Bit) entryBytes.u64(8, endian) else entryBytes.u32(4, endian)
            val valueOffset = rawValueOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val value = stringTable.readNullTerminatedAscii(valueOffset).takeIf { it.isNotBlank() } ?: continue

            entries += HexElfDynamicStringEntry(
                index = entryIndex,
                type = type,
                value = value,
                entryFileOffset = entryFileOffset
            )
        }
    }
    return entries.withDynamicStringSemantics()
}

internal fun List<HexElfDynamicStringEntry>.withDynamicStringSemantics(): List<HexElfDynamicStringEntry> {
    var neededLoadOrder = 0
    return map { entry ->
        when (entry.type) {
            HexElfDynamicStringType.NEEDED -> entry.copy(
                loadOrder = ++neededLoadOrder,
                semantic = HexElfDynamicStringSemantic.NEEDED_LIBRARY_LOAD
            )
            HexElfDynamicStringType.SONAME -> entry.copy(semantic = HexElfDynamicStringSemantic.SONAME_IDENTITY)
            HexElfDynamicStringType.RPATH -> entry.copy(semantic = HexElfDynamicStringSemantic.LEGACY_RPATH_SEARCH)
            HexElfDynamicStringType.RUNPATH -> entry.copy(semantic = HexElfDynamicStringSemantic.RUNPATH_SEARCH)
        }
    }
}

internal fun readElfDynamicFlagEntries(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>
): List<HexElfDynamicFlagEntry> {
    if (sections.isEmpty()) return emptyList()
    val entries = mutableListOf<HexElfDynamicFlagEntry>()
    val dynamicSections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_DYNAMIC.toLong() || section.name == ".dynamic"
    }

    for (section in dynamicSections) {
        if (entries.size >= MAX_ELF_DYNAMIC_ENTRIES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val entrySize = section.entrySize.takeIf { it > 0L }
            ?: if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE.toLong() else ELF32_DYNAMIC_ENTRY_SIZE.toLong()
        if (entrySize <= 0L) continue

        val dynamicEntryCount = minOf(
            section.size / entrySize,
            (MAX_ELF_DYNAMIC_ENTRIES - entries.size).toLong()
        ).toInt()
        for (entryIndex in 0 until dynamicEntryCount) {
            val entryFileOffset = section.fileOffset + entryIndex.toLong() * entrySize
            val entryBytes = randomAccessFile.readAt(
                offset = entryFileOffset,
                byteCount = entrySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            val minimumEntrySize = if (is64Bit) ELF64_DYNAMIC_ENTRY_SIZE else ELF32_DYNAMIC_ENTRY_SIZE
            if (entryBytes.size < minimumEntrySize) break

            val tag = if (is64Bit) entryBytes.u64(0, endian) else entryBytes.u32(0, endian)
            if (tag == ELF_DYNAMIC_TAG_NULL) break
            val type = elfDynamicFlagType(tag) ?: continue
            val value = if (is64Bit) entryBytes.u64(8, endian) else entryBytes.u32(4, endian)
            entries += HexElfDynamicFlagEntry(
                index = entryIndex,
                type = type,
                value = value,
                entryFileOffset = entryFileOffset,
                isBindNow = isElfBindNowDynamicFlag(type, value)
            )
        }
    }
    return entries
}

internal fun List<HexElfLoadSegment>.virtualAddressToFileOffset(virtualAddress: Long): Long? = firstNotNullOfOrNull { segment -> segment.virtualAddressToFileOffset(virtualAddress) }

internal fun HexElfProgramHeader.containsFileRange(fileOffset: Long, size: Long): Boolean {
    if (size <= 0L || fileSize <= 0L || fileOffset < this.fileOffset) return false
    val relativeStart = fileOffset - this.fileOffset
    return relativeStart <= fileSize && size <= fileSize - relativeStart
}

internal fun readElfInitArrayEntries(
    randomAccessFile: RandomAccessFile,
    is64Bit: Boolean,
    endian: HexEndian,
    sections: List<HexElfSection>,
    loadSegments: List<HexElfLoadSegment>
): List<HexElfInitArrayEntry> {
    if (sections.isEmpty()) return emptyList()
    val pointerSize = if (is64Bit) Long.SIZE_BYTES else Int.SIZE_BYTES
    val entries = mutableListOf<HexElfInitArrayEntry>()
    val initArraySections = sections.filter { section ->
        section.type == ELF_SECTION_TYPE_INIT_ARRAY.toLong() || section.name == ".init_array"
    }

    for (section in initArraySections) {
        if (entries.size >= MAX_ELF_INIT_ARRAY_ENTRIES) break
        if (section.fileOffset <= 0L || section.size <= 0L) continue
        if (section.fileOffset >= randomAccessFile.length()) continue

        val entryCount = minOf(
            section.size / pointerSize.toLong(),
            (MAX_ELF_INIT_ARRAY_ENTRIES - entries.size).toLong()
        ).toInt()
        for (entryIndex in 0 until entryCount) {
            val pointerFileOffset = section.fileOffset + entryIndex.toLong() * pointerSize
            val pointerBytes = randomAccessFile.readAt(pointerFileOffset, pointerSize)
            if (pointerBytes.size < pointerSize) break
            val functionAddress = if (is64Bit) pointerBytes.u64(0, endian) else pointerBytes.u32(0, endian)
            if (functionAddress == 0L) continue
            entries += HexElfInitArrayEntry(
                index = entryIndex,
                pointerFileOffset = pointerFileOffset,
                functionAddress = functionAddress,
                functionFileOffset = loadSegments.virtualAddressToFileOffset(functionAddress)
            )
        }
    }
    return entries
}

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

    private fun HexElfSection.containsFileRange(fileOffset: Long, byteCount: Int): Boolean {
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
