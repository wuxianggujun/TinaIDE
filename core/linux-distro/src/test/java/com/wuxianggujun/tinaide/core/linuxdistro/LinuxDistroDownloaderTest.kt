package com.wuxianggujun.tinaide.core.linuxdistro

import com.google.common.truth.Truth.assertThat
import java.io.File
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

class LinuxDistroDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun download_resumesWhenContentRangeMatchesLocalFile() = runTest {
        val target = temporaryFolder.newFile("rootfs.tar.gz").apply { writeText("hello ") }
        val downloader = OkHttpLinuxDistroDownloader(
            partialClient("world".toByteArray(), "bytes 6-10/11"),
        )

        val result = downloader.download(
            DistroDownloadRequest(
                url = "https://distro.test/rootfs.tar.gz",
                targetFile = target,
                expectedSizeBytes = 11L,
            )
        )

        assertThat(result).isEqualTo(target)
        assertThat(target.readText()).isEqualTo("hello world")
    }

    @Test
    fun download_rejectsMismatchedContentRangeAndDeletesPartialFile() = runTest {
        val target = temporaryFolder.newFile("invalid-range-rootfs.tar.gz").apply { writeText("hello ") }
        val downloader = OkHttpLinuxDistroDownloader(
            partialClient("world".toByteArray(), "bytes 0-4/11"),
        )

        val result = runCatching {
            downloader.download(
                DistroDownloadRequest(
                    url = "https://distro.test/rootfs.tar.gz",
                    targetFile = target,
                    expectedSizeBytes = 11L,
                )
            )
        }

        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("invalid Content-Range")
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun download_rejectsTruncatedUnknownLengthContentRange() = runTest {
        val target = temporaryFolder.newFile("truncated-range-rootfs.tar.gz").apply { writeText("hello ") }
        val downloader = OkHttpLinuxDistroDownloader(
            partialClient("wor".toByteArray(), "bytes 6-10/*", unknownLength = true),
        )

        val result = runCatching {
            downloader.download(
                DistroDownloadRequest(
                    url = "https://distro.test/rootfs.tar.gz",
                    targetFile = target,
                )
            )
        }

        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("incomplete Content-Range")
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun download_rejectsRedirectToHttpBeforeWritingResponse() = runTest {
        val target = File(temporaryFolder.root, "redirect-rootfs.tar.gz")
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request().newBuilder().url("http://mirror.test/rootfs.tar.gz").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(byteArrayOf(1).toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            )
            .build()
        val downloader = OkHttpLinuxDistroDownloader(client)

        val result = runCatching {
            downloader.download(
                DistroDownloadRequest(
                    url = "https://distro.test/rootfs.tar.gz",
                    targetFile = target,
                )
            )
        }

        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("unsafe URL")
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun download_httpFailureDoesNotExposeFullSourceUrl() = runTest {
        val target = File(temporaryFolder.root, "failed-rootfs.tar.gz")
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Unavailable")
                        .body(byteArrayOf().toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            )
            .build()
        val downloader = OkHttpLinuxDistroDownloader(client)

        val error = runCatching {
            downloader.download(
                DistroDownloadRequest(
                    url = "https://distro.test/rootfs.tar.gz?token=private-value",
                    targetFile = target,
                )
            )
        }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("distro.test")
        assertThat(error).hasMessageThat().contains("HTTP 503")
        assertThat(error).hasMessageThat().doesNotContain("private-value")
    }

    private fun partialClient(
        payload: ByteArray,
        contentRange: String,
        unknownLength: Boolean = false,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", contentRange)
                    .body(
                        if (unknownLength) {
                            unknownLengthBody(payload)
                        } else {
                            payload.toResponseBody("application/octet-stream".toMediaType())
                        }
                    )
                    .build()
            }
        )
        .build()

    private fun unknownLengthBody(payload: ByteArray): ResponseBody = object : ResponseBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = Buffer().write(payload)
    }
}
