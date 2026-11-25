package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.runtime.saveable.listSaver
import java.io.File
import java.io.IOException
import java.nio.file.Files
import timber.log.Timber

/** The saved choice owns the attachment. Losing a cache entry must never broaden consent. */
internal sealed interface FeedbackDiagnostics {
    data object None : FeedbackDiagnostics
    data object Automatic : FeedbackDiagnostics
    data class Selected(val cacheKey: String, val displayName: String) : FeedbackDiagnostics {
        fun resolve(cache: File): File? {
            return ownedFile(cache)?.takeIf { it.isFile && it.length() in 1..LOG_ARCHIVE_MAX_BYTES }
        }

        fun remove(cache: File) {
            ownedFile(cache)?.let(::deleteTemporaryArchive)
        }

        private fun ownedFile(cache: File): File? {
            if (!IMPORTED_ARCHIVE_NAME.matches(cacheKey)) return null
            return try {
                File(cache, cacheKey).takeIf {
                    !Files.isSymbolicLink(cache.toPath()) && !Files.isSymbolicLink(it.toPath()) &&
                        it.canonicalFile.parentFile == cache.canonicalFile
                }
            } catch (error: IOException) {
                Timber.tag("FeedbackDialog").w(error, "Could not resolve cached attachment")
                null
            }
        }
    }

    companion object {
        fun save(choice: FeedbackDiagnostics): List<String> = when (choice) {
            None -> listOf("none")
            Automatic -> listOf("automatic")
            is Selected -> listOf("selected", choice.cacheKey, choice.displayName)
        }

        fun restore(fields: List<String>): FeedbackDiagnostics = when {
            fields == listOf("automatic") -> Automatic
            fields.size == 3 && fields.first() == "selected" -> Selected(fields[1], fields[2])
            else -> None
        }

        val Saver = listSaver<FeedbackDiagnostics, String>(save = { save(it) }, restore = ::restore)
    }
}
