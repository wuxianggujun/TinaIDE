package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class DistroDownloadRequest(
    val url: String,
    val targetFile: File,
    val resume: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val expectedSizeBytes: Long? = null,
    val maxSizeBytes: Long = DEFAULT_MAX_DISTRO_ARCHIVE_BYTES,
)

const val DEFAULT_MAX_DISTRO_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L

data class DistroDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float? = totalBytes?.takeIf { it > 0L }?.let { total ->
        (downloadedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}

interface LinuxDistroDownloader {
    suspend fun download(
        request: DistroDownloadRequest,
        progress: (DistroDownloadProgress) -> Unit = {},
    ): File
}

class OkHttpLinuxDistroDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) : LinuxDistroDownloader {

    override suspend fun download(
        request: DistroDownloadRequest,
        progress: (DistroDownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(request.url.isStrictHttpsUrl()) { "Linux distro downloads require a valid HTTPS URL" }
        require(request.maxSizeBytes > 0L) { "Download size limit must be positive" }
        require(request.expectedSizeBytes == null || request.expectedSizeBytes in 1..request.maxSizeBytes) {
            "Declared linux distro archive size exceeds the allowed limit"
        }
        request.targetFile.parentFile?.mkdirs()
        val startByte = if (request.resume && request.targetFile.isFile) request.targetFile.length() else 0L
        if (startByte > request.maxSizeBytes) {
            request.targetFile.delete()
            error("Linux distro archive exceeds the allowed download size")
        }
        val httpRequest = Request.Builder()
            .url(request.url)
            .apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                if (startByte > 0L) {
                    header("Range", "bytes=$startByte-")
                }
            }
            .build()

        val call = client.newCall(httpRequest)
        val coroutineContext = currentCoroutineContext()
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                call.cancel()
            }
        }
        try {
            runInterruptible { call.execute() }.use { response ->
                check(response.request.url.toString().isStrictHttpsUrl()) {
                    "Linux distro download redirected to an unsafe URL"
                }
                if (!response.isSuccessful) {
                    error("Failed to download linux distro archive from ${response.request.url.host}: HTTP ${response.code}")
                }
                val body = response.body
                    ?: error("Empty linux distro archive response from ${response.request.url.host}")
                val isResumed = response.code == 206 && startByte > 0L
                val contentLength = body.contentLength().takeIf { it >= 0L }
                val contentRange = if (response.code == 206) {
                    parseContentRange(response.header("Content-Range"))
                        ?.takeIf { range ->
                            range.start == startByte &&
                                (contentLength == null || range.byteCount == contentLength) &&
                                (request.expectedSizeBytes == null ||
                                    range.total == null ||
                                    range.total == request.expectedSizeBytes)
                        }
                        ?: run {
                            request.targetFile.delete()
                            error("Linux distro server returned an invalid Content-Range")
                        }
                } else {
                    null
                }
                val contentRangeEndPosition = contentRange?.end?.plus(1L)
                val totalBytes = when {
                    contentRange?.total != null -> contentRange.total
                    response.code == 206 && contentLength != null -> safeAdd(startByte, contentLength)
                        ?: error("Linux distro server returned an invalid Content-Range")
                    response.code == 200 && contentLength != null -> contentLength
                    else -> null
                }
                if (totalBytes != null && totalBytes > request.maxSizeBytes) {
                    request.targetFile.delete()
                    error("Linux distro archive exceeds the allowed download size")
                }

                RandomAccessFile(request.targetFile, "rw").use { output ->
                    if (isResumed) {
                        output.seek(startByte)
                    } else {
                        output.setLength(0L)
                    }

                    var downloaded = if (isResumed) startByte else 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = runInterruptible { input.read(buffer) }
                            coroutineContext.ensureActive()
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read.toLong()
                            if (downloaded > request.maxSizeBytes) {
                                request.targetFile.delete()
                                error("Linux distro archive exceeds the allowed download size")
                            }
                            progress(DistroDownloadProgress(downloaded, totalBytes))
                        }
                    }
                }
                if (contentRangeEndPosition != null && request.targetFile.length() != contentRangeEndPosition) {
                    request.targetFile.delete()
                    error("Linux distro server returned an incomplete Content-Range")
                }
                request.expectedSizeBytes?.let { expected ->
                    val actual = request.targetFile.length()
                    if (actual != expected) {
                        request.targetFile.delete()
                        error("Linux distro archive size mismatch: expected $expected, got $actual")
                    }
                }
                if (totalBytes != null && request.targetFile.length() != totalBytes) {
                    val actual = request.targetFile.length()
                    request.targetFile.delete()
                    error("Linux distro archive size mismatch: expected $totalBytes, got $actual")
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } finally {
            cancelHandle.dispose()
        }
        request.targetFile
    }

    private companion object {
        private const val BUFFER_SIZE = 8192
        private val CONTENT_RANGE_REGEX = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

        private fun parseContentRange(value: String?): ContentRange? {
            val match = value?.trim()?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
            if (end < start || end == Long.MAX_VALUE || total != null && (total <= end || total <= 0L)) return null
            return ContentRange(start, end, total, byteCount = end - start + 1L)
        }

        private fun safeAdd(left: Long, right: Long): Long? {
            if (left < 0L || right < 0L || right > Long.MAX_VALUE - left) return null
            return left + right
        }
    }

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
        val byteCount: Long,
    )
}
