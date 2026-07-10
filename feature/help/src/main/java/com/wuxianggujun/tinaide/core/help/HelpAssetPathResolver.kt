package com.wuxianggujun.tinaide.core.help

import java.util.Locale

/** Resolves localized help assets while keeping the existing Chinese files as fallback. */
internal object HelpAssetPathResolver {
    private const val DEFAULT_DIRECTORY = "help"
    private const val ENGLISH_DIRECTORY = "$DEFAULT_DIRECTORY/en"

    fun candidatePaths(
        fileName: String,
        languageTag: String,
    ): List<String> {
        val language = languageTag
            .substringBefore('-')
            .substringBefore('_')
            .lowercase(Locale.ROOT)
        val fallbackPath = "$DEFAULT_DIRECTORY/$fileName"
        return if (language == "en") {
            listOf("$ENGLISH_DIRECTORY/$fileName", fallbackPath)
        } else {
            listOf(fallbackPath)
        }
    }
}
