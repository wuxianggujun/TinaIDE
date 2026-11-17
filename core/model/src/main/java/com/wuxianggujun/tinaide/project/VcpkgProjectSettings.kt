package com.wuxianggujun.tinaide.project

/** Stored in the existing project CMake arguments, without a second preferences store. */
data class VcpkgProjectSettings(
    val mode: String = DISABLED,
) {
    val enabled: Boolean get() = mode in setOf(MANIFEST, SHARED)

    fun targetTriplet(androidAbi: String): String {
        val arch = when (androidAbi) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x64"
            else -> error("Unsupported vcpkg Android ABI: $androidAbi")
        }
        return "$arch-${if (mode == MANIFEST) "tina-android" else "android"}"
    }

    fun applyTo(arguments: List<String>): List<String> = stripManagedArguments(arguments) + if (enabled) {
        listOf("-D$MODE=$mode")
    } else {
        emptyList()
    }

    companion object {
        const val DISABLED = "disabled"
        const val MANIFEST = "manifest"
        const val SHARED = "shared"
        private const val MODE = "TINA_VCPKG_MODE"
        private const val ROOT = "TINA_VCPKG_ROOT"
        private const val INSTALLED = "TINA_VCPKG_INSTALLED_DIR"
        private val keys = setOf(MODE, ROOT, INSTALLED)

        private fun key(argument: String): String = argument.trim().removePrefix("-D")
            .substringBefore('=').substringBefore(':')

        fun from(arguments: List<String>): VcpkgProjectSettings {
            val values = arguments.filter { it.trim().startsWith("-D") && '=' in it }
                .associate { key(it) to it.substringAfter('=').trim() }
            return VcpkgProjectSettings(values[MODE]?.takeIf { it in setOf(DISABLED, MANIFEST, SHARED) } ?: DISABLED)
        }

        fun stripManagedArguments(arguments: List<String>): List<String> =
            arguments.filterNot { it.trim().startsWith("-D") && key(it) in keys }
    }
}
