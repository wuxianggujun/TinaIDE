package com.wuxianggujun.tinaide.core.packages

import java.io.File
import timber.log.Timber

/** One supported Android raylib contract. Old binaries must be replaced, never adapted at runtime. */
object RaylibRuntimePolicy {
    const val MINIMUM_PACKAGE_REVISION = 3
    const val LIFECYCLE_MARKER = "PLATFORM: ANDROID: Initialized successfully (TinaIDE lifecycle r3)"
    private val supportedAbis = setOf("arm64-v8a", "x86_64")
    private val raylibName = Regex("""^libraylib\.so(?:\..+)?$""")

    fun isSupportedRuntimeLibrary(library: File): Boolean {
        if (!raylibName.matches(library.name)) return true
        return runCatching { hasLifecycleMarker(library) }
            .onFailure { Timber.tag("RaylibRuntimePolicy").w(it, "Unable to inspect %s", library.name) }
            .getOrDefault(false)
    }

    fun isSupportedPackage(directory: File, metadata: InstalledPackageMetadata): Boolean {
        if (metadata.id != "raylib") return true
        if ((metadata.packageRevision ?: 0) < MINIMUM_PACKAGE_REVISION) return false
        val abis = metadata.abis.orEmpty()
        return abis.isNotEmpty() && abis.all { abi ->
            abi in supportedAbis && isSupportedRuntimeLibrary(File(directory, "lib/$abi/libraylib.so"))
        }
    }

    private fun hasLifecycleMarker(library: File): Boolean = library.inputStream().buffered().use { input ->
        val magic = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        if (magic.any { input.read() != it.toInt() }) return@use false
        val buffer = ByteArray(16 * 1024)
        var tail = ""
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return@use false
            val text = tail + String(buffer, 0, read, Charsets.ISO_8859_1)
            if (text.contains(LIFECYCLE_MARKER)) return@use true
            tail = text.takeLast(LIFECYCLE_MARKER.length - 1)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
}
