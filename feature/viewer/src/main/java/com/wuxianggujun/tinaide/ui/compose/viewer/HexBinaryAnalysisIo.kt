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
 * Shared binary IO helpers and analysis constants.
 */

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
