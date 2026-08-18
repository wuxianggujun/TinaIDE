package com.wuxianggujun.tinaide.core.compile

/** Default libc++ linkage for Android native outputs produced by TinaIDE. */
internal object AndroidCppRuntimeLinkage {
    const val STATIC_EXECUTABLE_FLAG: String = "-static-libstdc++"

    fun flagsForOutput(isCpp: Boolean, outputIsSharedLibrary: Boolean): List<String> {
        return if (isCpp && !outputIsSharedLibrary) {
            listOf(STATIC_EXECUTABLE_FLAG)
        } else {
            emptyList()
        }
    }

    fun appendToLinkerFlags(
        linkerFlags: String,
        isCpp: Boolean,
        outputIsSharedLibrary: Boolean,
    ): String {
        val normalizedFlags = linkerFlags.trim()
        val requiredFlags = flagsForOutput(isCpp, outputIsSharedLibrary)
        if (requiredFlags.isEmpty() || containsFlag(normalizedFlags, STATIC_EXECUTABLE_FLAG)) {
            return normalizedFlags
        }
        return sequenceOf(normalizedFlags, STATIC_EXECUTABLE_FLAG)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun containsFlag(flags: String, expected: String): Boolean = flags
        .splitToSequence(Regex("""\s+"""))
        .any { it == expected }
}
