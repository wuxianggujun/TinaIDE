package com.wuxianggujun.tinaide.core.apkbuilder

import java.io.File
import java.io.IOException

object NativeLibraryAbiDetector {
    private const val ELF_HEADER_PREFIX_SIZE = 20
    private const val ELF_CLASS_32 = 1
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LITTLE_ENDIAN = 1
    private const val ELF_DATA_BIG_ENDIAN = 2

    @Throws(IOException::class)
    fun detect(library: File): String? {
        val header = ByteArray(ELF_HEADER_PREFIX_SIZE)
        val bytesRead = library.inputStream().buffered().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }
        if (bytesRead < header.size || !hasElfMagic(header)) return null

        val elfClass = header[4].toInt() and 0xFF
        val machine = when (header[5].toInt() and 0xFF) {
            ELF_DATA_LITTLE_ENDIAN ->
                (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            ELF_DATA_BIG_ENDIAN ->
                ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
            else -> return null
        }

        return when (elfClass to machine) {
            ELF_CLASS_32 to 3 -> "x86"
            ELF_CLASS_32 to 40 -> "armeabi-v7a"
            ELF_CLASS_64 to 62 -> "x86_64"
            ELF_CLASS_64 to 183 -> "arm64-v8a"
            ELF_CLASS_64 to 243 -> "riscv64"
            else -> null
        }
    }

    private fun hasElfMagic(header: ByteArray): Boolean =
        header[0] == 0x7F.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
}
