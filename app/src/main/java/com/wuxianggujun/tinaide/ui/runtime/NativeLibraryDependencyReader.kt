package com.wuxianggujun.tinaide.ui.runtime

import com.wuxianggujun.tinaide.core.compile.elf.ElfNeededLibraryReader
import java.io.File
import java.io.IOException

object NativeLibraryDependencyReader {
    private const val ASCII_TOKEN_LIMIT = 4096
    private const val ASCII_TAIL_LIMIT = 256

    private val sharedLibraryNamePattern =
        Regex("""lib[0-9A-Za-z_+\-.]+\.so(?:\.[0-9A-Za-z_+\-.]+)?""")

    @Throws(IOException::class)
    fun readNeededLibraryNames(library: File): Set<String> {
        ElfNeededLibraryReader.read(library)?.let { return it }
        return scanAsciiLibraryNames(library)
    }

    private fun scanAsciiLibraryNames(library: File): Set<String> {
        val results = linkedSetOf<String>()
        val tokenBuilder = StringBuilder()
        library.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                for (index in 0 until read) {
                    val byteValue = buffer[index].toInt() and 0xFF
                    if (byteValue in 32..126) {
                        tokenBuilder.append(byteValue.toChar())
                        if (tokenBuilder.length > ASCII_TOKEN_LIMIT) {
                            collectLibraryNames(tokenBuilder, results)
                            val tailStart = (tokenBuilder.length - ASCII_TAIL_LIMIT).coerceAtLeast(0)
                            val tail = tokenBuilder.substring(tailStart)
                            tokenBuilder.setLength(0)
                            tokenBuilder.append(tail)
                        }
                    } else {
                        collectLibraryNames(tokenBuilder, results)
                        tokenBuilder.setLength(0)
                    }
                }
            }
        }
        collectLibraryNames(tokenBuilder, results)
        return results
    }

    private fun collectLibraryNames(tokenBuilder: StringBuilder, output: MutableSet<String>) {
        if (tokenBuilder.isEmpty()) return
        val token = tokenBuilder.toString()
        if (!token.contains(".so")) return

        sharedLibraryNamePattern.findAll(token).forEach { match ->
            output += match.value
        }
    }
}
