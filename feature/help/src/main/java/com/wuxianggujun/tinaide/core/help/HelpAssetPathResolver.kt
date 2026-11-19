package com.wuxianggujun.tinaide.core.help

import java.util.Locale

/** Resolves localized help assets while keeping the existing Chinese files as fallback. */
internal object HelpAssetPathResolver {
    private const val DEFAULT_DIRECTORY = "help"

    fun candidatePaths(
        fileName: String,
        languageTag: String,
        directory: String = DEFAULT_DIRECTORY,
    ): List<String> {
        val language = languageTag
            .substringBefore('-')
            .substringBefore('_')
            .lowercase(Locale.ROOT)
        val fallbackPath = "$directory/$fileName"
        return if (language == "en") {
            listOf("$directory/en/$fileName", fallbackPath)
        } else {
            listOf(fallbackPath)
        }
    }
}
