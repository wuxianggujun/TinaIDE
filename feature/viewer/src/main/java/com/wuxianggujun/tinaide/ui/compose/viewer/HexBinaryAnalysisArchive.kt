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
 * Archive/APK/ZIP parsing and Android resource/manifest helpers.
 */

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

