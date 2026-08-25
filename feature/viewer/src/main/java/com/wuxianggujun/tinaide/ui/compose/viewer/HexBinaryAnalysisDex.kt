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
 * DEX summary and instruction-table parsing helpers.
 */

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

