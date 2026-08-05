package com.wuxianggujun.tinaide.core.packages.download

import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.network.executeCancellable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    ): DownloadResult = withContext(Dispatchers.IO) {
        val tempFile = File(downloadDir, "${targetFile.name}.tmp")

        try {
            val startPosition = if (supportsRange && tempFile.exists()) {
                tempFile.length()
            } else {
                if (tempFile.exists()) tempFile.delete()
                0L
            }

            Timber.tag(TAG).d("Starting download from $url, resume from $startPosition")

            val requestBuilder = Request.Builder().url(url)
            if (startPosition > 0 && supportsRange) {
                requestBuilder.header("Range", "bytes=$startPosition-")
            }

            val response = client.newCall(requestBuilder.build()).executeCancellable()

            if (!response.isSuccessful) {
                return@withContext DownloadResult.Failed(
                    DownloadError.HttpError(response.code, response.message)
                )
            }

            val isPartialContent = response.code == 206
            val contentLength = response.body?.contentLength() ?: -1L
            val totalSize = if (isPartialContent && startPosition > 0) {
                startPosition + contentLength
            } else {
                contentLength
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

            response.body?.byteStream()?.use { input ->
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

            val actualSize = tempFile.length()
            if (expectedSize != null && actualSize != expectedSize) {
                tempFile.delete()
                return@withContext DownloadResult.Failed(
                    DownloadError.SizeMismatch(expectedSize, actualSize)
                )
            }

            if (checksum != null) {
                Timber.tag(TAG).d("Verifying checksum...")
                val actualChecksum = calculateChecksum(tempFile, checksum)
                val expectedHash = checksum.substringAfter(":")

                if (!actualChecksum.equals(expectedHash, ignoreCase = true)) {
                    tempFile.delete()
                    return@withContext DownloadResult.Failed(
                        DownloadError.ChecksumMismatch(expectedHash, actualChecksum)
                    )
                }
                Timber.tag(TAG).d("Checksum verified")
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Timber.tag(TAG).d("Download completed: ${targetFile.absolutePath}")
            DownloadResult.Success(targetFile)
        } catch (e: CancellationException) {
            // 用户取消：保留临时文件以便下次断点续传，向上抛出由协程框架处理。
            Timber.tag(TAG).d("Download cancelled by user")
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download failed")
            DownloadResult.Failed(DownloadError.IOError(e.message ?: "Unknown error"))
        }
    }

    private fun calculateChecksum(file: File, checksumSpec: String): String {
        val algorithm = when {
            checksumSpec.startsWith("sha256:", ignoreCase = true) -> "SHA-256"
            checksumSpec.startsWith("sha1:", ignoreCase = true) -> "SHA-1"
            checksumSpec.startsWith("md5:", ignoreCase = true) -> "MD5"
            else -> "SHA-256"
        }

        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun clearTempFiles() {
        downloadDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }
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
    data class IOError(val message: String) : DownloadError()

    fun toDisplayMessage(): String = when (this) {
        is HttpError -> "HTTP $code: $message"
        is SizeMismatch -> "Size mismatch: expected $expected bytes, got $actual bytes"
        is ChecksumMismatch -> "Checksum mismatch: expected $expected, got $actual"
        is IOError -> message
    }
}
