package com.wuxianggujun.tinaide.core.packages.download

import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.network.executeCancellable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class ResumableDownloader(
    private val downloadDir: File,
    private val client: OkHttpClient = OkHttpClientProvider.download
) {
    companion object {
        private const val TAG = "ResumableDownloader"
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_INTERVAL_MS = 300L
        private const val MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L
        private val CONTENT_RANGE_REGEX = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
        private val DOWNLOAD_MUTEX = Mutex()
    }

    init {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
    }

    suspend fun download(
        url: String,
        targetFile: File,
        checksum: String?,
        expectedSize: Long? = null,
        supportsRange: Boolean = true,
        progress: (downloaded: Long, total: Long, speed: Long) -> Unit
    ): DownloadResult = DOWNLOAD_MUTEX.withLock {
        downloadLocked(url, targetFile, checksum, expectedSize, supportsRange, progress)
    }

    private suspend fun downloadLocked(
        url: String,
        targetFile: File,
        checksum: String?,
        expectedSize: Long?,
        supportsRange: Boolean,
        progress: (downloaded: Long, total: Long, speed: Long) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val tempFile = tempFileFor(targetFile)

        try {
            require(url.isStrictHttpsUrl()) { "Package downloads require a valid HTTPS URL" }
            val expectedChecksum = checksum
                ?.takeIf { it.matches(Regex("(?i)^sha256:[0-9a-f]{64}$")) }
                ?: throw IllegalArgumentException("Package downloads require a valid SHA-256 checksum")
            val maximumBytes = expectedSize ?: MAX_DOWNLOAD_BYTES
            require(maximumBytes in 1..MAX_DOWNLOAD_BYTES) { "Package download size exceeds the allowed limit" }
            var startPosition = if (supportsRange && tempFile.exists()) {
                tempFile.length()
            } else {
                if (tempFile.exists()) tempFile.delete()
                0L
            }
            if (startPosition > maximumBytes) {
                tempFile.delete()
                return@withContext DownloadResult.Failed(
                    DownloadError.SizeLimitExceeded(maximumBytes, startPosition)
                )
            }
            if (expectedSize != null && startPosition == expectedSize) {
                val expectedHash = expectedChecksum.substringAfter(":")
                if (calculateChecksum(tempFile).equals(expectedHash, ignoreCase = true)) {
                    publishVerifiedFile(tempFile, targetFile)
                    Timber.tag(TAG).d("Published previously completed package download")
                    return@withContext DownloadResult.Success(targetFile)
                }
                tempFile.delete()
                startPosition = 0L
            }

            Timber.tag(TAG).d("Starting package download, resume from $startPosition")

            val requestBuilder = Request.Builder().url(url)
            if (startPosition > 0 && supportsRange) {
                requestBuilder.header("Range", "bytes=$startPosition-")
            }

            var contentRangeEndPosition: Long? = null
            client.newCall(requestBuilder.build()).executeCancellable().use { response ->
                if (!response.request.url.toString().isStrictHttpsUrl()) {
                    return@withContext DownloadResult.Failed(
                        DownloadError.IOError("Package download redirected to an unsafe URL")
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext DownloadResult.Failed(
                        DownloadError.HttpError(response.code, response.message)
                    )
                }

                val body = response.body
                    ?: return@withContext DownloadResult.Failed(DownloadError.IOError("Empty response body"))
                val isPartialContent = response.code == 206
                val contentLength = body.contentLength()
                val contentRange = if (isPartialContent) {
                    parseContentRange(response.header("Content-Range"))
                        ?.takeIf { range ->
                            range.start == startPosition &&
                                (contentLength < 0L || range.byteCount == contentLength) &&
                                (expectedSize == null || range.total == null || range.total == expectedSize)
                        }
                        ?: run {
                            tempFile.delete()
                            return@withContext DownloadResult.Failed(
                                DownloadError.IOError("Package server returned an invalid Content-Range")
                            )
                        }
                } else {
                    null
                }
                contentRangeEndPosition = contentRange?.let { range -> safeAdd(range.end, 1L) }
                val totalSize = when {
                    contentRange?.total != null -> contentRange.total
                    isPartialContent && contentLength >= 0L -> safeAdd(startPosition, contentLength)
                        ?: Long.MAX_VALUE
                    else -> contentLength
                }
                if (totalSize > maximumBytes) {
                    tempFile.delete()
                    return@withContext DownloadResult.Failed(
                        DownloadError.SizeLimitExceeded(maximumBytes, totalSize)
                    )
                }

                val actualStartPosition = if (isPartialContent) startPosition else 0L
                if (!isPartialContent && tempFile.exists()) {
                    tempFile.delete()
                }

                val outputStream = if (actualStartPosition > 0 && tempFile.exists()) {
                    RandomAccessFile(tempFile, "rw").apply {
                        seek(actualStartPosition)
                    }.let { raf ->
                        object : java.io.OutputStream() {
                            override fun write(b: Int) = raf.write(b)
                            override fun write(b: ByteArray) = raf.write(b)
                            override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
                            override fun close() = raf.close()
                        }
                    }
                } else {
                    FileOutputStream(tempFile)
                }

                body.byteStream().use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = actualStartPosition
                        var lastProgressTime = System.currentTimeMillis()
                        var lastProgressBytes = downloaded

                        while (true) {
                            coroutineContext.ensureActive()
                            val bytes = input.read(buffer)
                            if (bytes == -1) break

                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (downloaded > maximumBytes) {
                                throw DownloadSizeLimitException(maximumBytes, downloaded)
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastProgressTime >= PROGRESS_INTERVAL_MS) {
                                val elapsed = now - lastProgressTime
                                val speed = if (elapsed > 0) {
                                    (downloaded - lastProgressBytes) * 1000 / elapsed
                                } else {
                                    0L
                                }

                                progress(downloaded, totalSize, speed)

                                lastProgressTime = now
                                lastProgressBytes = downloaded
                            }
                        }

                        progress(downloaded, totalSize, 0)
                    }
                }
            }

            val actualSize = tempFile.length()
            if (contentRangeEndPosition != null && actualSize != contentRangeEndPosition) {
                tempFile.delete()
                return@withContext DownloadResult.Failed(
                    DownloadError.IOError("Package server returned an incomplete Content-Range")
                )
            }
            if (actualSize > maximumBytes) {
                tempFile.delete()
                return@withContext DownloadResult.Failed(
                    DownloadError.SizeLimitExceeded(maximumBytes, actualSize)
                )
            }
            if (expectedSize != null && actualSize != expectedSize) {
                tempFile.delete()
                return@withContext DownloadResult.Failed(
                    DownloadError.SizeMismatch(expectedSize, actualSize)
                )
            }

            Timber.tag(TAG).d("Verifying checksum...")
            val actualChecksum = calculateChecksum(tempFile)
            val expectedHash = expectedChecksum.substringAfter(":")

            if (!actualChecksum.equals(expectedHash, ignoreCase = true)) {
                tempFile.delete()
                return@withContext DownloadResult.Failed(
                    DownloadError.ChecksumMismatch(expectedHash, actualChecksum)
                )
            }
            Timber.tag(TAG).d("Checksum verified")

            publishVerifiedFile(tempFile, targetFile)

            Timber.tag(TAG).d("Package download completed")
            DownloadResult.Success(targetFile)
        } catch (e: DownloadSizeLimitException) {
            tempFile.delete()
            DownloadResult.Failed(DownloadError.SizeLimitExceeded(e.limit, e.actual))
        } catch (e: CancellationException) {
            // 用户取消：保留临时文件以便下次断点续传，向上抛出由协程框架处理。
            Timber.tag(TAG).d("Download cancelled by user")
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e("Package download failed: %s", e.javaClass.simpleName)
            DownloadResult.Failed(DownloadError.IOError(e.message ?: "Unknown error"))
        }
    }

    private fun calculateChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun tempFileFor(targetFile: File): File {
        val targetIdentity = targetFile.toPath().toAbsolutePath().normalize().toString()
        val identityHash = MessageDigest.getInstance("SHA-256")
            .digest(targetIdentity.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        val safeTargetName = targetFile.name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
            .ifBlank { "package" }
        return File(downloadDir, "$safeTargetName.$identityHash.tmp")
    }

    private fun publishVerifiedFile(tempFile: File, targetFile: File) {
        val targetParent = targetFile.parentFile
            ?: throw IllegalArgumentException("Target file must have a parent directory")
        if ((!targetParent.exists() && !targetParent.mkdirs()) || !targetParent.isDirectory) {
            error("Failed to create package target directory")
        }
        recoverInterruptedPublication(targetFile)

        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // Fall through to a same-directory staged replacement.
        } catch (_: java.io.IOException) {
            // Cross-filesystem and device-specific move failures use the staged path below.
        }

        val publishFile = File(targetParent, ".${targetFile.name}.${UUID.randomUUID()}.publishing")
        try {
            tempFile.inputStream().use { input ->
                FileOutputStream(publishFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            check(publishFile.length() == tempFile.length()) { "Failed to stage verified package download" }
            replaceFromSameDirectory(publishFile, targetFile)
            tempFile.delete()
        } finally {
            publishFile.delete()
        }
    }

    private fun replaceFromSameDirectory(publishFile: File, targetFile: File) {
        try {
            Files.move(
                publishFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // Use a reversible rename sequence when atomic replacement is unavailable.
        } catch (_: java.io.IOException) {
            // Some Android filesystem providers reject atomic replacement despite same-directory paths.
        }

        val backupFile = File(targetFile.parentFile, ".${targetFile.name}.${UUID.randomUUID()}.backup")
        val hadTarget = targetFile.exists()
        if (hadTarget && !targetFile.renameTo(backupFile)) {
            error("Failed to preserve existing package before replacement")
        }
        if (!publishFile.renameTo(targetFile)) {
            if (hadTarget && !backupFile.renameTo(targetFile)) {
                error("Failed to publish package and restore existing target")
            }
            error("Failed to publish verified package download")
        }
        if (backupFile.exists() && !backupFile.delete()) {
            Timber.tag(TAG).w("Failed to delete stale package publication backup")
        }
    }

    private fun recoverInterruptedPublication(targetFile: File) {
        val targetParent = checkNotNull(targetFile.parentFile)
        val artifactPrefix = ".${targetFile.name}."
        val artifacts = targetParent.listFiles().orEmpty()
            .filter { file -> file.name.startsWith(artifactPrefix) }
        artifacts.filter { it.name.endsWith(".publishing") }.forEach { stalePublish ->
            if (!stalePublish.delete()) {
                Timber.tag(TAG).w("Failed to delete stale package publication file")
            }
        }

        val backups = artifacts.filter { it.name.endsWith(".backup") }
            .sortedByDescending(File::lastModified)
        val backupToRestore = backups.firstOrNull()
        if (!targetFile.exists() && backupToRestore != null && !backupToRestore.renameTo(targetFile)) {
            error("Failed to recover interrupted package publication")
        }
        backups.filter(File::exists).forEach { staleBackup ->
            if (!staleBackup.delete()) {
                Timber.tag(TAG).w("Failed to delete stale package publication backup")
            }
        }
    }

    private fun String.isStrictHttpsUrl(): Boolean = runCatching {
        val uri = URI(this)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (end < start || end == Long.MAX_VALUE || total != null && (total <= end || total <= 0L)) return null
        return ContentRange(start, end, total, byteCount = end - start + 1L)
    }

    suspend fun clearTempFiles() = DOWNLOAD_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            downloadDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
    }

    private fun safeAdd(left: Long, right: Long): Long? {
        if (left < 0L || right < 0L || right > Long.MAX_VALUE - left) return null
        return left + right
    }

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
        val byteCount: Long,
    )
}

sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data class Failed(val error: DownloadError) : DownloadResult()
    object Cancelled : DownloadResult()
}

sealed class DownloadError {
    data class HttpError(val code: Int, val message: String) : DownloadError()
    data class SizeMismatch(val expected: Long, val actual: Long) : DownloadError()
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadError()
    data class SizeLimitExceeded(val limit: Long, val actual: Long) : DownloadError()
    data class IOError(val message: String) : DownloadError()

    fun toDisplayMessage(): String = when (this) {
        is HttpError -> "HTTP $code: $message"
        is SizeMismatch -> "Size mismatch: expected $expected bytes, got $actual bytes"
        is ChecksumMismatch -> "Checksum mismatch: expected $expected, got $actual"
        is SizeLimitExceeded -> "Download exceeds size limit: limit $limit bytes, got at least $actual bytes"
        is IOError -> message
    }
}

private class DownloadSizeLimitException(val limit: Long, val actual: Long) : Exception()
