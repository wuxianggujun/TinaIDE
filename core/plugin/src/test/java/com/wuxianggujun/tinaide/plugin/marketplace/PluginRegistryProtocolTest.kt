package com.wuxianggujun.tinaide.plugin.marketplace

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.RegistryEndpoint
import com.wuxianggujun.tinaide.core.network.registry.RegistryUrl
import com.wuxianggujun.tinaide.plugin.ZipUtils
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class PluginRegistryProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun catalogEntry_shouldDeserializeLightweightV3IndexItem() {
        val catalog = json.decodeFromString<PluginRegistryCatalog>(
            """
            {
              "schema_version": 3,
              "generated_at": "2026-06-06T00:00:00Z",
              "plugins": [
                {
                  "id": "tinaide.plugin.example",
                  "plugin_id": "tinaide.plugin.example",
                  "name": "Example",
                  "description": "Small summary",
                  "category": "tool",
                  "tags": ["tool"],
                  "publisher": {
                    "id": "tinaide",
                    "display_name": "TinaIDE"
                  },
                  "latest_version": "1.0.0",
                  "detail_url": "plugins/tinaide.plugin.example/plugin.v3.json",
                  "created_at": "2026-06-01T00:00:00Z",
                  "updated_at": "2026-06-06T00:00:00Z"
                }
              ]
            }
            """.trimIndent()
        )

        val entry = catalog.plugins.single()
        val summary = entry.toSummary()

        assertThat(catalog.schemaVersion).isEqualTo(3)
        assertThat(entry.detailUrl).isEqualTo("plugins/tinaide.plugin.example/plugin.v3.json")
        assertThat(summary.pluginId).isEqualTo("tinaide.plugin.example")
        assertThat(summary.latestVersion).isEqualTo("1.0.0")
        assertThat(summary.publisher.displayName).isEqualTo("TinaIDE")
    }

    @Test
    fun api_shouldLoadV3CatalogAndFetchDetailOnDemand(): Unit = runBlocking {
        val baseUrl = "https://registry.test"
        val v3IndexUrl = registryUrl(baseUrl, "plugins/index.v3.json")
        val detailUrl = "$baseUrl/plugins/tinaide.plugin.example/plugin.v3.json"
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                v3IndexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 3,
                      "plugins": [
                        {
                          "id": "tinaide.plugin.example",
                          "plugin_id": "tinaide.plugin.example",
                          "name": "Example",
                          "publisher": {
                            "id": "tinaide",
                            "display_name": "TinaIDE"
                          },
                          "latest_version": "1.1.0",
                          "detail_url": "plugins/tinaide.plugin.example/plugin.v3.json",
                          "created_at": "2026-06-01T00:00:00Z",
                          "updated_at": "2026-06-06T00:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                detailUrl to RegistryResponse(
                    body = """
                    {
                      "id": "tinaide.plugin.example",
                      "plugin_id": "tinaide.plugin.example",
                      "name": "Example",
                      "publisher": {
                        "id": "tinaide",
                        "display_name": "TinaIDE"
                      },
                      "versions": [
                        {
                          "version": "1.1.0",
                          "version_code": 2,
                          "file_size": 256,
                          "download_url": "plugins/tinaide.plugin.example/1.1.0/example.tinaplug",
                          "created_at": "2026-06-06T00:00:00Z"
                        }
                      ],
                      "created_at": "2026-06-01T00:00:00Z",
                      "updated_at": "2026-06-06T00:00:00Z"
                    }
                    """.trimIndent()
                ),
            )
        )
        val api = pluginApi(v3IndexUrl, interceptor.client())

        val result = api.getPluginDetail("tinaide.plugin.example")

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val detail = (result as ApiResult.Success).data
        assertThat(detail.latestVersionEntry()?.version).isEqualTo("1.1.0")
        assertThat(interceptor.requestedUrls)
            .containsExactly(v3IndexUrl.url, detailUrl)
            .inOrder()
    }

    @Test
    fun api_shouldFailWithoutRequestingOlderIndexesWhenV3CatalogUnavailable(): Unit = runBlocking {
        val baseUrl = "https://registry.test"
        val v3IndexUrl = registryUrl(baseUrl, "plugins/index.v3.json")
        val v1IndexUrl = registryUrl(baseUrl, "plugins/index.json")
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                v1IndexUrl.url to RegistryResponse(
                    body = """
                    {
                      "plugins": [
                        {
                          "id": "tinaide.plugin.example",
                          "plugin_id": "tinaide.plugin.example",
                          "name": "Example",
                          "publisher": {
                            "id": "tinaide",
                            "display_name": "TinaIDE"
                          },
                          "versions": [
                            {
                              "version": "1.0.0",
                              "version_code": 1,
                              "file_size": 128,
                              "created_at": "2026-06-01T00:00:00Z"
                            }
                          ],
                          "created_at": "2026-06-01T00:00:00Z",
                          "updated_at": "2026-06-06T00:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                v3IndexUrl.url to RegistryResponse(code = 404),
            )
        )
        val api = pluginApi(v3IndexUrl, interceptor.client())

        val result = api.getPluginDetail("tinaide.plugin.example")

        assertThat(result).isInstanceOf(ApiResult.Error::class.java)
        assertThat(interceptor.requestedUrls)
            .containsExactly(v3IndexUrl.url)
            .inOrder()
    }

    @Test
    fun api_shouldRejectUnexpectedPluginRegistrySchema(): Unit = runBlocking {
        val indexUrl = registryUrl("https://registry.schema.test", "plugins/index.v3.json")
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                indexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 2,
                      "plugins": []
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val api = pluginApi(indexUrl, interceptor.client())

        val result = api.listPlugins()

        assertThat(result).isInstanceOf(ApiResult.Error::class.java)
        assertThat(interceptor.requestedUrls).containsExactly(indexUrl.url)
    }

    @Test
    fun api_shouldSurfaceDetailFailureInsteadOfTreatingItAsIncompatibility(): Unit = runBlocking {
        val baseUrl = "https://registry.detail-failure.test"
        val indexUrl = registryUrl(baseUrl, "plugins/index.v3.json")
        val detailUrl = "$baseUrl/plugins/tinaide.plugin.example/plugin.v3.json"
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                indexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 3,
                      "plugins": [
                        {
                          "id": "tinaide.plugin.example",
                          "plugin_id": "tinaide.plugin.example",
                          "name": "Example",
                          "publisher": { "id": "tinaide", "display_name": "TinaIDE" },
                          "latest_version": "1.0.0",
                          "detail_url": "plugins/tinaide.plugin.example/plugin.v3.json"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
                detailUrl to RegistryResponse(code = 503),
            ),
        )
        val api = pluginApi(indexUrl, interceptor.client(), hostVersion = "0.18.11")

        val result = api.listPlugins()

        assertThat(result).isInstanceOf(ApiResult.Error::class.java)
        assertThat(interceptor.requestedUrls).containsAtLeast(indexUrl.url, detailUrl).inOrder()
    }

    @Test
    fun api_shouldFallbackToPublicProxyForIndexDetailAndDownload(): Unit = runBlocking {
        val rawBaseUrl = GitHubRegistryConfig.GITHUB_RAW_BASE_URL
        val proxyPrefix = GitHubRegistryConfig.PUBLIC_GITHUB_PROXY_PREFIXES.first()
        val officialIndexUrl = registryUrl("GitHub Raw", rawBaseUrl, "plugins/index.v3.json")
        val proxyIndexUrl = registryUrl(
            name = "proxy",
            baseUrl = proxyPrefix + rawBaseUrl,
            path = "plugins/index.v3.json",
            urlPrefix = proxyPrefix,
        )
        val proxyDetailUrl = proxyPrefix + "$rawBaseUrl/plugins/tinaide.plugin.example/plugin.v3.json"
        val githubDownloadUrl = "https://github.com/wuxianggujun/TinaIDE-Registry/releases/download/" +
            "plugins/example.tinaplug"
        val proxyDownloadUrl = proxyPrefix + githubDownloadUrl
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                officialIndexUrl.url to RegistryResponse(code = 503),
                proxyIndexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 3,
                      "plugins": [
                        {
                          "id": "tinaide.plugin.example",
                          "plugin_id": "tinaide.plugin.example",
                          "name": "Example",
                          "publisher": {
                            "id": "tinaide",
                            "display_name": "TinaIDE"
                          },
                          "latest_version": "1.1.0",
                          "detail_url": "plugins/tinaide.plugin.example/plugin.v3.json",
                          "created_at": "2026-06-01T00:00:00Z",
                          "updated_at": "2026-06-06T00:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                proxyDetailUrl to RegistryResponse(
                    body = """
                    {
                      "id": "tinaide.plugin.example",
                      "plugin_id": "tinaide.plugin.example",
                      "name": "Example",
                      "publisher": {
                        "id": "tinaide",
                        "display_name": "TinaIDE"
                      },
                      "versions": [
                        {
                          "version": "1.1.0",
                          "version_code": 2,
                          "file_size": 7,
                          "file_hash": "sha256:ed7002b439e9ac845f22357d822bac1444730fbdb6016d3ec9432297b9ec9f73",
                          "download_url": "$githubDownloadUrl",
                          "created_at": "2026-06-06T00:00:00Z"
                        }
                      ],
                      "created_at": "2026-06-01T00:00:00Z",
                      "updated_at": "2026-06-06T00:00:00Z"
                    }
                    """.trimIndent()
                ),
                proxyDownloadUrl to RegistryResponse(
                    body = "content",
                    contentType = "application/octet-stream",
                ),
            )
        )
        val api = pluginApi(listOf(officialIndexUrl, proxyIndexUrl), interceptor.client())
        val tempDir = Files.createTempDirectory("plugin-registry-proxy-test").toFile()

        try {
            val result = api.downloadPlugin(
                pluginId = "tinaide.plugin.example",
                targetFile = tempDir.resolve("example.tinaplug"),
            )

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            assertThat((result as ApiResult.Success).data.readText()).isEqualTo("content")
            assertThat(interceptor.requestedUrls)
                .containsExactly(officialIndexUrl.url, proxyIndexUrl.url, proxyDetailUrl, proxyDownloadUrl)
                .inOrder()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun api_shouldUseHighestCompatibleVersionAcrossMarketplaceOperations(): Unit = runBlocking {
        val baseUrl = "https://registry.compatibility.test"
        val indexUrl = registryUrl(baseUrl, "plugins/index.v3.json")
        val detailUrl = "$baseUrl/plugins/tinaide.plugin.example/plugin.v3.json"
        val compatibleDownloadUrl = "$baseUrl/plugins/tinaide.plugin.example/1.5.0/example.tinaplug"
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                indexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 3,
                      "plugins": [
                        {
                          "id": "tinaide.plugin.example",
                          "plugin_id": "tinaide.plugin.example",
                          "name": "Example",
                          "publisher": { "id": "tinaide", "display_name": "TinaIDE" },
                          "latest_version": "2.0.0",
                          "detail_url": "plugins/tinaide.plugin.example/plugin.v3.json",
                          "created_at": "2026-06-01T00:00:00Z",
                          "updated_at": "2026-07-15T00:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
                detailUrl to RegistryResponse(
                    body = """
                    {
                      "id": "tinaide.plugin.example",
                      "plugin_id": "tinaide.plugin.example",
                      "name": "Example",
                      "publisher": { "id": "tinaide", "display_name": "TinaIDE" },
                      "versions": [
                        {
                          "version": "2.0.0",
                          "version_code": 20000,
                          "file_size": 7,
                          "download_url": "plugins/tinaide.plugin.example/2.0.0/example.tinaplug",
                          "api_version": 1,
                          "min_app_version": "0.20.0",
                          "created_at": "2026-07-15T00:00:00Z"
                        },
                        {
                          "version": "1.6.0",
                          "version_code": 10600,
                          "file_size": 7,
                          "download_url": "plugins/tinaide.plugin.example/1.6.0/example.tinaplug",
                          "api_version": 2,
                          "created_at": "2026-07-14T00:00:00Z"
                        },
                        {
                          "version": "1.5.0",
                          "version_code": 10500,
                          "file_size": 10,
                          "file_hash": "sha256:a1613be9e3df6e9a2d9f0562bc73df744d6e440e5d3a244e1c647eb6489073dc",
                          "download_url": "plugins/tinaide.plugin.example/1.5.0/example.tinaplug",
                          "api_version": 1,
                          "min_app_version": "0.18.0",
                          "created_at": "2026-07-13T00:00:00Z"
                        },
                        {
                          "version": "1.0.0",
                          "version_code": 10000,
                          "file_size": 7,
                          "download_url": "plugins/tinaide.plugin.example/1.0.0/example.tinaplug",
                          "created_at": "2026-06-01T00:00:00Z"
                        }
                      ],
                      "created_at": "2026-06-01T00:00:00Z",
                      "updated_at": "2026-07-15T00:00:00Z"
                    }
                    """.trimIndent(),
                ),
                compatibleDownloadUrl to RegistryResponse(
                    body = "compatible",
                    contentType = "application/octet-stream",
                ),
            ),
        )
        val api = pluginApi(indexUrl, interceptor.client(), hostVersion = "0.18.11")
        val tempDir = Files.createTempDirectory("plugin-compatible-version-test").toFile()

        try {
            val list = api.listPlugins()
            val detail = api.getPluginDetail("tinaide.plugin.example")
            val updates = api.checkUpdates(
                listOf(CheckUpdateItem(pluginId = "tinaide.plugin.example", version = "1.0.0")),
            )
            val download = api.downloadPlugin(
                pluginId = "tinaide.plugin.example",
                targetFile = tempDir.resolve("example.tinaplug"),
            )

            assertThat((list as ApiResult.Success).data.plugins.single().latestVersion).isEqualTo("1.5.0")
            assertThat((detail as ApiResult.Success).data.versions.map { it.version })
                .containsExactly("1.5.0", "1.0.0")
                .inOrder()
            assertThat((updates as ApiResult.Success).data.updates.single().latestVersion).isEqualTo("1.5.0")
            assertThat((download as ApiResult.Success).data.readText()).isEqualTo("compatible")
            assertThat(interceptor.requestedUrls)
                .containsExactly(indexUrl.url, detailUrl, compatibleDownloadUrl)
                .inOrder()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun api_shouldHidePluginWhenNoPublishedVersionSupportsCurrentHost(): Unit = runBlocking {
        val baseUrl = "https://registry.incompatible.test"
        val indexUrl = registryUrl(baseUrl, "plugins/index.v3.json")
        val detailUrl = "$baseUrl/plugins/tinaide.plugin.future/plugin.v3.json"
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                indexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 3,
                      "plugins": [
                        {
                          "id": "tinaide.plugin.future",
                          "plugin_id": "tinaide.plugin.future",
                          "name": "Future",
                          "publisher": { "id": "tinaide", "display_name": "TinaIDE" },
                          "latest_version": "2.0.0",
                          "detail_url": "plugins/tinaide.plugin.future/plugin.v3.json",
                          "created_at": "2026-07-15T00:00:00Z",
                          "updated_at": "2026-07-15T00:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
                detailUrl to RegistryResponse(
                    body = """
                    {
                      "id": "tinaide.plugin.future",
                      "plugin_id": "tinaide.plugin.future",
                      "name": "Future",
                      "publisher": { "id": "tinaide", "display_name": "TinaIDE" },
                      "versions": [
                        {
                          "version": "2.0.0",
                          "version_code": 20000,
                          "file_size": 7,
                          "download_url": "plugins/tinaide.plugin.future/2.0.0/future.tinaplug",
                          "api_version": 1,
                          "min_app_version": "0.20.0",
                          "created_at": "2026-07-15T00:00:00Z"
                        },
                        {
                          "version": "1.0.0",
                          "version_code": 10000,
                          "file_size": 7,
                          "download_url": "plugins/tinaide.plugin.future/1.0.0/future.tinaplug",
                          "api_version": 2,
                          "created_at": "2026-07-01T00:00:00Z"
                        }
                      ],
                      "created_at": "2026-07-01T00:00:00Z",
                      "updated_at": "2026-07-15T00:00:00Z"
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val api = pluginApi(indexUrl, interceptor.client(), hostVersion = "0.18.11")

        val list = api.listPlugins()
        val updates = api.checkUpdates(
            listOf(CheckUpdateItem(pluginId = "tinaide.plugin.future", version = "0.9.0")),
        )
        val detail = api.getPluginDetail("tinaide.plugin.future")

        assertThat((list as ApiResult.Success).data.plugins).isEmpty()
        assertThat((updates as ApiResult.Success).data.updates).isEmpty()
        assertThat((detail as ApiResult.Success).data.versions).isEmpty()
        assertThat(interceptor.requestedUrls).containsExactly(indexUrl.url, detailUrl).inOrder()
    }

    @Test
    fun downloadPlugin_shouldRejectAndDeleteOversizedPartialBeforeNetworkTransfer(): Unit = runBlocking {
        val baseUrl = "https://registry.example"
        val indexUrl = registryUrl(baseUrl, "plugins/index.v3.json")
        val detailUrl = "$baseUrl/plugins/example/plugin.v3.json"
        val downloadUrl = "$baseUrl/plugins/example/example.tinaplug"
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                indexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 3,
                      "generated_at": "2026-06-06T00:00:00Z",
                      "plugins": [
                        {
                          "id": "tinaide.plugin.example",
                          "plugin_id": "tinaide.plugin.example",
                          "name": "Example",
                          "description": "Example",
                          "category": "tool",
                          "tags": [],
                          "publisher": { "id": "tinaide", "display_name": "TinaIDE" },
                          "latest_version": "1.0.0",
                          "detail_url": "$detailUrl",
                          "created_at": "2026-06-01T00:00:00Z",
                          "updated_at": "2026-06-06T00:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
                detailUrl to RegistryResponse(
                    body = """
                    {
                      "id": "tinaide.plugin.example",
                      "plugin_id": "tinaide.plugin.example",
                      "name": "Example",
                      "publisher": { "id": "tinaide", "display_name": "TinaIDE" },
                      "versions": [
                        {
                          "version": "1.0.0",
                          "version_code": 1,
                          "file_size": 1,
                          "file_hash": "sha256:2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881",
                          "download_url": "$downloadUrl",
                          "created_at": "2026-06-06T00:00:00Z"
                        }
                      ],
                      "created_at": "2026-06-01T00:00:00Z",
                      "updated_at": "2026-06-06T00:00:00Z"
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val api = pluginApi(indexUrl, interceptor.client())
        val tempDir = Files.createTempDirectory("plugin-size-limit-test").toFile()
        val targetFile = tempDir.resolve("example.tinaplug")
        RandomAccessFile(targetFile, "rw").use { file ->
            file.setLength(ZipUtils.MAX_PACKAGE_BYTES + 1)
        }

        try {
            val result = api.downloadPlugin(
                pluginId = "tinaide.plugin.example",
                targetFile = targetFile,
            )

            assertThat(result).isNotInstanceOf(ApiResult.Success::class.java)
            assertThat(targetFile.exists()).isFalse()
            assertThat(interceptor.requestedUrls).doesNotContain(downloadUrl)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun registryUrl(
        baseUrl: String,
        path: String,
    ): RegistryUrl = registryUrl("test", baseUrl, path)

    private fun registryUrl(
        name: String,
        baseUrl: String,
        path: String,
        urlPrefix: String? = null,
    ): RegistryUrl {
        val endpoint = RegistryEndpoint(name = name, baseUrl = baseUrl, urlPrefix = urlPrefix)
        return RegistryUrl(endpoint = endpoint, url = "$baseUrl/$path")
    }

    private fun pluginApi(
        v3IndexUrl: RegistryUrl,
        client: OkHttpClient,
        hostVersion: String? = null,
    ): PluginMarketplaceApi = pluginApi(listOf(v3IndexUrl), client, hostVersion)

    private fun pluginApi(
        indexUrls: List<RegistryUrl>,
        client: OkHttpClient,
        hostVersion: String? = null,
    ): PluginMarketplaceApi {
        val constructor = PluginMarketplaceApi::class.java.getDeclaredConstructor(
            List::class.java,
            OkHttpClient::class.java,
            OkHttpClient::class.java,
            String::class.java,
        )
        constructor.isAccessible = true
        return (constructor.newInstance(
            indexUrls,
            client,
            client,
            null,
        ) as PluginMarketplaceApi).also { api ->
            val hostVersionField = PluginMarketplaceApi::class.java.getDeclaredField("currentHostVersion")
            hostVersionField.isAccessible = true
            hostVersionField.set(api, hostVersion)
        }
    }

    private data class RegistryResponse(
        val code: Int = 200,
        val body: String = "",
        val contentType: String = "application/json",
    )

    private class FakeRegistryInterceptor(
        private val responses: Map<String, RegistryResponse>,
    ) : Interceptor {
        val requestedUrls = mutableListOf<String>()

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(this)
            .build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            requestedUrls += url
            val registryResponse = responses[url] ?: RegistryResponse(code = 404)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(registryResponse.code)
                .message(if (registryResponse.code in 200..299) "OK" else "Error")
                .body(registryResponse.body.toResponseBody(registryResponse.contentType.toMediaType()))
                .build()
        }
    }
}
