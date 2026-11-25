package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.executeCancellable
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

internal interface VcpkgCatalogSource {
    suspend fun latestRevision(): String
    suspend fun download(revision: String, target: File, progress: (Long, Long) -> Unit)

    suspend fun download(revision: String, target: File, progress: (Long, Long) -> Unit, validate: suspend (File) -> Unit) {
        download(revision, target, progress)
        validate(target)
    }

    companion object {
        fun github(context: Context): VcpkgCatalogSource = github(
            client = { GitHubRegistryHttpClientFactory.download(context) },
            mirror = { GitHubRegistryProxyConfig.load(context).customMirrorPrefix },
            probe = { GitHubRegistryHttpClientFactory.probe(context) },
        )

        // Resolve settings for each operation so an open market can retry after a proxy change.
        fun github(client: () -> OkHttpClient, mirror: () -> String?, probe: () -> OkHttpClient = client): VcpkgCatalogSource = object : VcpkgCatalogSource {
            private suspend fun <T> fetch(url: String, http: OkHttpClient = client(), block: suspend (OkHttpClient, String) -> T): T {
                var lastFailure: Exception? = null
                for (candidate in GitHubRegistryConfig.gitHubUrlCandidates(url, mirror())) {
                    currentCoroutineContext().ensureActive()
                    try {
                        return block(http, candidate)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        lastFailure = error
                        Timber.tag("VcpkgCatalog").w("Catalog endpoint failed: %s", error.javaClass.simpleName)
                    }
                }
                throw lastFailure as? VcpkgException ?: VcpkgException(Strings.vcpkg_update_failed, lastFailure)
            }

            override suspend fun latestRevision(): String = fetch(
                "https://api.github.com/repos/microsoft/vcpkg/git/ref/heads/master",
                http = probe(),
            ) { http, url ->
                val request = Request.Builder().url(url)
                    .header("Accept", "application/vnd.github+json").build()
                http.newCall(request).executeCancellable().use { response ->
                    if (!response.isSuccessful) throw VcpkgException(Strings.vcpkg_catalog_http_failed,
                        messageArguments = listOf(response.code))
                    val bytes = response.body?.byteStream()?.readBytesBounded(64 * 1024)
                        ?: throw VcpkgException(Strings.vcpkg_download_invalid)
                    JSONObject(PackageMetadataFiles.decodeUtf8(bytes)).getJSONObject("object").getString("sha")
                        .also(VcpkgCatalogStore::requireRevision)
                }
            }

            override suspend fun download(revision: String, target: File, progress: (Long, Long) -> Unit) =
                download(revision, target, progress) { }

            override suspend fun download(revision: String, target: File, progress: (Long, Long) -> Unit, validate: suspend (File) -> Unit) {
                VcpkgCatalogStore.requireRevision(revision)
                val operation = currentCoroutineContext()
                // github.com archive URLs are accepted by more mirrors than codeload URLs.
                fetch("https://github.com/microsoft/vcpkg/archive/$revision.tar.gz") { http, url ->
                    val request = Request.Builder().url(url).build()
                    http.newCall(request).executeCancellable().use { response ->
                        if (!response.isSuccessful) throw VcpkgException(Strings.vcpkg_catalog_http_failed,
                            messageArguments = listOf(response.code))
                        val body = response.body ?: throw VcpkgException(Strings.vcpkg_download_invalid)
                        val limit = VcpkgCatalogStore.SOURCE_LIMITS.maxArchiveBytes
                        if (body.contentLength() > limit) throw VcpkgException(Strings.vcpkg_download_invalid)
                        body.byteStream().buffered().use { input ->
                            input.mark(2)
                            if (input.read() != 0x1f || input.read() != 0x8b) throw VcpkgException(Strings.vcpkg_download_invalid)
                            input.reset()
                            target.outputStream().use { output ->
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
                            }
                        }
                    }
                    // A valid gzip header is not enough: checksum and archive validation
                    // must succeed before this endpoint wins over the remaining candidates.
                    validate(target)
                }
            }
        }
    }
}
