package com.wuxianggujun.tinaide.core.packages

object PackageAbiCompatibility {

    private val ARM64_PATH_SEGMENT = Regex("(?:^|[/\\\\])(arm64-v8a|arm64|aarch64)(?:[/\\\\]|$)")
    private val X86_64_PATH_SEGMENT = Regex("(?:^|[/\\\\])x86_64(?:[/\\\\]|$)")

    fun isCompatible(requiredAbis: List<String>?, supportedAbis: Array<String>): Boolean {
        val required = normalize(requiredAbis)
        if (required.isEmpty()) return true

        val supported = supportedAbis
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
        if (supported.isEmpty()) return false

        return required.any { abi -> abi in supported }
    }

    fun currentAbiLabel(supportedAbis: Array<String>): String = supportedAbis.firstOrNull { it.isNotBlank() } ?: "unknown"

    /**
     * Resolve the ABI of the installed App variant, not only the first ABI advertised by the device.
     * This matters when an arm64 APK runs on an x86_64 device through a native bridge.
     */
    fun currentAppAbi(nativeLibraryDir: String?, supportedAbis: Array<String>): String {
        val normalizedNativeLibraryDir = nativeLibraryDir.orEmpty().trim().lowercase()
        return when {
            X86_64_PATH_SEGMENT.containsMatchIn(normalizedNativeLibraryDir) -> "x86_64"
            ARM64_PATH_SEGMENT.containsMatchIn(normalizedNativeLibraryDir) -> "arm64-v8a"
            else -> currentAbiLabel(supportedAbis)
        }
    }

    private fun normalize(abis: List<String>?): List<String> = abis.orEmpty()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.lowercase() }
        .distinct()
        .toList()
}
