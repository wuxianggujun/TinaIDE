package com.wuxianggujun.tinaide.core.packages.download

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun download_rejectsUnexpectedSizeWithoutReplacingExistingTarget() = runTest {
        val payload = "new archive".toByteArray()
        val downloadDir = temporaryFolder.newFolder("downloads")
        val targetFile = temporaryFolder.newFile("package.tar.xz").apply {
            writeText("existing archive")
        }
        val downloader = ResumableDownloader(downloadDir, clientReturning(payload))

        val result = downloader.download(
            url = "https://registry.test/package.tar.xz",
            targetFile = targetFile,
            checksum = sha256(payload),
            expectedSize = payload.size + 1L,
            supportsRange = false,
            progress = { _, _, _ -> },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        val error = (result as DownloadResult.Failed).error
        assertThat(error).isEqualTo(
            DownloadError.SizeMismatch(
                expected = payload.size + 1L,
                actual = payload.size.toLong(),
            )
        )
        assertThat(targetFile.readText()).isEqualTo("existing archive")
        assertThat(downloader.tempFileFor(targetFile).exists()).isFalse()
    }

    @Test
    fun download_publishesTargetWhenExpectedSizeMatches() = runTest {
        val payload = "complete archive".toByteArray()
        val downloadDir = temporaryFolder.newFolder("downloads")
        val targetFile = File(temporaryFolder.root, "package.tar.xz")
        val downloader = ResumableDownloader(downloadDir, clientReturning(payload))

        val result = downloader.download(
            url = "https://registry.test/package.tar.xz",
            targetFile = targetFile,
            checksum = sha256(payload),
            expectedSize = payload.size.toLong(),
            supportsRange = false,
            progress = { _, _, _ -> },
        )

        assertThat(result).isEqualTo(DownloadResult.Success(targetFile))
        assertThat(targetFile.readBytes()).isEqualTo(payload)
    }

    @Test
    fun download_publishesAlreadyCompletePartialWithoutAnotherRequest() = runTest {
        val payload = "already complete".toByteArray()
        val downloadDir = temporaryFolder.newFolder("completed-partial-downloads")
        val targetFile = File(temporaryFolder.root, "completed-partial.tar.xz")
        val downloader = ResumableDownloader(downloadDir, clientFailingIfCalled())
        downloader.tempFileFor(targetFile).writeBytes(payload)

        val result = downloader.download(
            url = "https://registry.test/package.tar.xz",
            targetFile = targetFile,
            checksum = sha256(payload),
            expectedSize = payload.size.toLong(),
            progress = { _, _, _ -> },
        )

        assertThat(result).isEqualTo(DownloadResult.Success(targetFile))
        assertThat(targetFile.readBytes()).isEqualTo(payload)
    }

    @Test
    fun download_recoversAndCleansInterruptedPublicationBackupBeforeReplacement() = runTest {
        val oldPayload = "old verified archive".toByteArray()
        val newPayload = "new verified archive".toByteArray()
        val downloadDir = temporaryFolder.newFolder("publication-recovery-downloads")
        val targetFile = File(temporaryFolder.root, "recover-package.tar.xz")
        val staleBackup = File(targetFile.parentFile, ".${targetFile.name}.stale.backup").apply {
            writeBytes(oldPayload)
        }
        val stalePublish = File(targetFile.parentFile, ".${targetFile.name}.stale.publishing").apply {
            writeText("partial")
        }
        val downloader = ResumableDownloader(downloadDir, clientReturning(newPayload))

        val result = downloader.download(
            url = "https://registry.test/package.tar.xz",
            targetFile = targetFile,
            checksum = sha256(newPayload),
            expectedSize = newPayload.size.toLong(),
            supportsRange = false,
            progress = { _, _, _ -> },
        )

        assertThat(result).isEqualTo(DownloadResult.Success(targetFile))
        assertThat(targetFile.readBytes()).isEqualTo(newPayload)
        assertThat(staleBackup.exists()).isFalse()
        assertThat(stalePublish.exists()).isFalse()
    }

    @Test
    fun download_resumesOnlyWhenContentRangeMatchesLocalFile() = runTest {
        val prefix = "hello ".toByteArray()
        val suffix = "world".toByteArray()
        val payload = prefix + suffix
        val downloadDir = temporaryFolder.newFolder("resume-downloads")
        val targetFile = File(temporaryFolder.root, "resume-package.tar.xz")
        val downloader = ResumableDownloader(
            downloadDir,
            clientReturningPartial(suffix, "bytes ${prefix.size}-${payload.lastIndex}/${payload.size}"),
        )
        downloader.tempFileFor(targetFile).writeBytes(prefix)

        val result = downloader.download(
            url = "https://registry.test/package.tar.xz",
            targetFile = targetFile,
            checksum = sha256(payload),
            expectedSize = payload.size.toLong(),
            progress = { _, _, _ -> },
        )

        assertThat(result).isEqualTo(DownloadResult.Success(targetFile))
        assertThat(targetFile.readBytes()).isEqualTo(payload)
    }

    @Test
    fun download_rejectsMismatchedContentRangeAndDeletesPartialFile() = runTest {
        val prefix = "hello ".toByteArray()
        val suffix = "world".toByteArray()
        val payload = prefix + suffix
        val downloadDir = temporaryFolder.newFolder("invalid-range-downloads")
        val targetFile = File(temporaryFolder.root, "invalid-range-package.tar.xz")
        val downloader = ResumableDownloader(
            downloadDir,
            clientReturningPartial(suffix, "bytes 0-${suffix.lastIndex}/${payload.size}"),
        )
        val tempFile = downloader.tempFileFor(targetFile).apply { writeBytes(prefix) }

        val result = downloader.download(
            url = "https://registry.test/package.tar.xz",
            targetFile = targetFile,
            checksum = sha256(payload),
            expectedSize = payload.size.toLong(),
            progress = { _, _, _ -> },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).error)
            .isEqualTo(DownloadError.IOError("Package server returned an invalid Content-Range"))
        assertThat(tempFile.exists()).isFalse()
        assertThat(targetFile.exists()).isFalse()
    }

    @Test
    fun download_rejectsTruncatedUnknownLengthContentRange() = runTest {
        val prefix = "hello ".toByteArray()
        val completePayload = prefix + "world".toByteArray()
        val downloadDir = temporaryFolder.newFolder("truncated-range-downloads")
        val targetFile = File(temporaryFolder.root, "truncated-range-package.tar.xz")
        val downloader = ResumableDownloader(
            downloadDir,
            clientReturningUnknownLengthPartial("wor".toByteArray(), "bytes 6-10/*"),
        )
        val tempFile = downloader.tempFileFor(targetFile).apply { writeBytes(prefix) }

        val result = downloader.download(
            url = "https://registry.test/package.tar.xz",
            targetFile = targetFile,
            checksum = sha256(completePayload),
            expectedSize = completePayload.size.toLong(),
            progress = { _, _, _ -> },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).error)
            .isEqualTo(DownloadError.IOError("Package server returned an incomplete Content-Range"))
        assertThat(tempFile.exists()).isFalse()
        assertThat(targetFile.exists()).isFalse()
    }

    @Test
    fun tempFileFor_isolatesTargetsWithSameFileName() {
        val downloadDir = temporaryFolder.newFolder("isolated-downloads")
        val firstTarget = File(temporaryFolder.newFolder("first-target"), "package.tar.xz")
        val secondTarget = File(temporaryFolder.newFolder("second-target"), "package.tar.xz")
        val downloader = ResumableDownloader(downloadDir, clientReturning(byteArrayOf(1)))

        assertThat(downloader.tempFileFor(firstTarget))
            .isNotEqualTo(downloader.tempFileFor(secondTarget))
    }

    @Test
    fun download_rejectsHttpsUrlWithoutHost() = runTest {
        val downloadDir = temporaryFolder.newFolder("invalid-url-downloads")
        val targetFile = File(temporaryFolder.root, "invalid-url-package.tar.xz")
        val downloader = ResumableDownloader(downloadDir, clientReturning(byteArrayOf(1)))

        val result = downloader.download(
            url = "https:///package.tar.xz",
            targetFile = targetFile,
            checksum = "sha256:${"00".repeat(32)}",
            progress = { _, _, _ -> },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).error)
            .isEqualTo(DownloadError.IOError("Package downloads require a valid HTTPS URL"))
    }

    private fun clientReturning(payload: ByteArray): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
        )
        .build()

    private fun clientFailingIfCalled(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor {
                throw AssertionError("Network request was not expected")
            }
        )
        .build()

    private fun clientReturningPartial(payload: ByteArray, contentRange: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", contentRange)
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
        )
        .build()

    private fun clientReturningUnknownLengthPartial(
        payload: ByteArray,
        contentRange: String,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", contentRange)
                    .body(unknownLengthBody(payload))
                    .build()
            }
        )
        .build()

    private fun unknownLengthBody(payload: ByteArray): ResponseBody = object : ResponseBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = Buffer().write(payload)
    }

    private fun sha256(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
