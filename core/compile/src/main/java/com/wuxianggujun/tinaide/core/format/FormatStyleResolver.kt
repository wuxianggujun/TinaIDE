package com.wuxianggujun.tinaide.core.format

import com.wuxianggujun.tinaide.core.config.Prefs
import java.io.File
import timber.log.Timber

/** Resolves the effective clang-format style from project files and user preferences. */
internal class FormatStyleResolver(
    private val userStyleNameProvider: () -> String = { Prefs.codeFormatStyle },
) {
    fun resolve(sourceFile: File): FormatStyle {
        val directory = if (sourceFile.isDirectory) sourceFile else sourceFile.parentFile
        return if (hasClangFormatFile(directory)) {
            Timber.tag(TAG).d("Using project .clang-format for: ${sourceFile.absolutePath}")
            FormatStyle.FILE
        } else {
            getUserDefaultStyle().also { style ->
                Timber.tag(TAG).d("Using user default style ($style) for: ${sourceFile.absolutePath}")
            }
        }
    }

    fun getUserDefaultStyle(): FormatStyle = FormatStyle.fromString(userStyleNameProvider())

    fun hasClangFormatFile(directory: File?, maxDepth: Int = DEFAULT_SEARCH_DEPTH): Boolean {
        var currentDir = directory
        var depth = 0

        while (currentDir != null && depth < maxDepth) {
            val configFile = PROJECT_CONFIG_NAMES
                .asSequence()
                .map { name -> File(currentDir, name) }
                .firstOrNull { file -> file.isFile }
            if (configFile != null) {
                Timber.tag(TAG).d("Found clang-format config at: ${configFile.absolutePath}")
                return true
            }

            currentDir = currentDir.parentFile
            depth++
        }

        return false
    }

    private companion object {
        private const val TAG = "FormatStyleResolver"
        private const val DEFAULT_SEARCH_DEPTH = 10
        private val PROJECT_CONFIG_NAMES = listOf(".clang-format", "_clang-format")
    }
}

internal fun FormatStyle.toClangFormatArgument(): String = when (this) {
    FormatStyle.FILE -> "--style=file"
    is FormatStyle.Custom -> "--style=$config"
    FormatStyle.LLVM -> "--style=LLVM"
    FormatStyle.GOOGLE -> "--style=Google"
    FormatStyle.CHROMIUM -> "--style=Chromium"
    FormatStyle.MOZILLA -> "--style=Mozilla"
    FormatStyle.WEBKIT -> "--style=WebKit"
    FormatStyle.MICROSOFT -> "--style=Microsoft"
    FormatStyle.GNU -> "--style=GNU"
}
