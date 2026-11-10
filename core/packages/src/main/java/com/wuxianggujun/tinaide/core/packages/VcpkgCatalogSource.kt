package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.executeCancellable
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.json.JSONObject

internal interface VcpkgCatalogSource {
    suspend fun latestRevision(): String
    suspend fun download(revision: String, target: File, progress: (Long, Long) -> Unit)

    companion object {
        fun github(context: Context): VcpkgCatalogSource = object : VcpkgCatalogSource {
            private val client get() = GitHubRegistryHttpClientFactory.download(context)

            override suspend fun latestRevision(): String {
                val request = Request.Builder().url("https://api.github.com/repos/microsoft/vcpkg/git/ref/heads/master")
                    .header("Accept", "application/vnd.github+json").build()
                client.newCall(request).executeCancellable().use { response ->
                    if (!response.isSuccessful) throw VcpkgException(Strings.vcpkg_update_failed)
                    val bytes = response.body?.byteStream()?.readBytesBounded(64 * 1024)
                        ?: throw VcpkgException(Strings.vcpkg_download_invalid)
                    return JSONObject(PackageMetadataFiles.decodeUtf8(bytes)).getJSONObject("object").getString("sha")
                }
            }

            override suspend fun download(revision: String, target: File, progress: (Long, Long) -> Unit) {
                VcpkgCatalogStore.requireRevision(revision)
                val operation = currentCoroutineContext()
                val request = Request.Builder().url("https://codeload.github.com/microsoft/vcpkg/tar.gz/$revision").build()
                client.newCall(request).executeCancellable().use { response ->
                    if (!response.isSuccessful) throw VcpkgException(Strings.vcpkg_update_failed)
                    val body = response.body ?: throw VcpkgException(Strings.vcpkg_download_invalid)
                    val limit = VcpkgCatalogStore.SOURCE_LIMITS.maxArchiveBytes
                    if (body.contentLength() > limit) throw VcpkgException(Strings.vcpkg_download_invalid)
                    body.byteStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastProgress = 0L
                        while (true) {
                            operation.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > limit) throw VcpkgException(Strings.vcpkg_download_invalid)
                            output.write(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgress > 200_000_000L) {
                                progress(downloaded, body.contentLength())
                                lastProgress = now
                            }
                        }
                        if (body.contentLength() >= 0 && downloaded != body.contentLength()) throw VcpkgException(Strings.vcpkg_download_invalid)
                        progress(downloaded, downloaded)
                    } }
                }
            }
        }
    }
}
