package com.wuxianggujun.tinaide.core.logging

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/** One export owns one file, even when manual feedback and automatic crash uploads overlap. */
internal suspend fun writeLogArchive(
    directory: File,
    displayName: String,
    writeBundle: (ZipOutputStream) -> Unit,
): File {
    var created: File? = null
    var delivered = false
    try {
        val result = withContext(Dispatchers.IO) {
            check(!Files.isSymbolicLink(directory.toPath()))
            Files.createDirectories(directory.toPath())
            check(!Files.isSymbolicLink(directory.toPath()))
            val prefix = LogArchiveDetector.safeFileName(displayName).removeSuffix(".zip") + "-"
            val file = File.createTempFile(prefix, ".zip", directory)
            created = file
            ZipOutputStream(file.outputStream()).use(writeBundle)
            currentCoroutineContext().ensureActive()
            file
        }
        delivered = true
        return result
    } finally {
        // Cancellation may discard the completed IO result before the caller acquires ownership.
        if (!delivered) withContext(NonCancellable + Dispatchers.IO) {
            created?.let { file ->
                try {
                    if (file.exists() && !file.delete()) Timber.tag("LogExportUtils").w("Could not remove unfinished log archive")
                } catch (error: Exception) {
                    Timber.tag("LogExportUtils").w(error, "Could not remove unfinished log archive")
                }
            }
        }
    }
}
