package com.wuxianggujun.tinaide.core.packages.download

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
            checksum = null,
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
        assertThat(File(downloadDir, "${targetFile.name}.tmp").exists()).isFalse()
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
            checksum = null,
            expectedSize = payload.size.toLong(),
            supportsRange = false,
            progress = { _, _, _ -> },
        )

        assertThat(result).isEqualTo(DownloadResult.Success(targetFile))
        assertThat(targetFile.readBytes()).isEqualTo(payload)
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
}
