package com.wuxianggujun.tinaide.core.packages

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded reads shared by installed package metadata and vcpkg manifests/status. */
internal object PackageMetadataFiles {
    fun readBytes(file: File, maxBytes: Int, ensureActive: () -> Unit = {}): ByteArray {
        require(maxBytes > 0) { "Metadata size limit must be positive" }
        ensureActive()
        if (!file.isFile) throw IOException("Package metadata is not a regular file")
        if (file.length() > maxBytes) throw IOException("Package metadata exceeds its size limit")
        return file.inputStream().use { input ->
            ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    // The file may grow after length() was read. Check before allocating/writing.
                    if (count > maxBytes - output.size()) {
                        throw IOException("Package metadata exceeds its size limit")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
