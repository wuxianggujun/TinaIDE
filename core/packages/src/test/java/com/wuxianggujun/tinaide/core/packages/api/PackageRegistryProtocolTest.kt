package com.wuxianggujun.tinaide.core.packages.api

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxySettings
import com.wuxianggujun.tinaide.core.network.registry.RegistryEndpoint
import com.wuxianggujun.tinaide.core.network.registry.RegistryUrl
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.PackageArtifactType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class PackageRegistryProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun catalogEntry_shouldDeserializeLightweightV2IndexItem() {
        val catalog = json.decodeFromString<PackageRegistryCatalog>(
            """
            {
              "schema_version": 2,
              "generated_at": "2026-06-06T00:00:00Z",
              "categories": [
                { "id": "runtime", "name": "Runtime", "sort_order": 0 }
              ],
              "packages": [
                {
                  "id": "sdl3",
                  "name": "SDL3",
                  "description": "SDL runtime",
                  "category": "runtime",
                  "detail_url": "packages/sdl3/package.json",
                  "android": {
                    "version": "3.2.0",
                    "artifact_type": "shared",
                    "install_type": "download",
                    "is_latest": true
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val entry = catalog.packages.single()
        val pkg = entry.toPackage()

        assertThat(catalog.schemaVersion).isEqualTo(2)
        assertThat(catalog.categories.single().id).isEqualTo("runtime")
        assertThat(entry.detailUrl).isEqualTo("packages/sdl3/package.json")
        assertThat(pkg.id).isEqualTo("sdl3")
        assertThat(pkg.android?.artifactType).isEqualTo(PackageArtifactType.SHARED)
        assertThat(pkg.android?.installType).isEqualTo(InstallType.DOWNLOAD)
    }

    @Test
    fun detail_shouldDeserializeVersionsAndDownloads() {
        val detail = json.decodeFromString<PackageRegistryDetail>(
            """
            {
              "package": {
                "id": "sdl3",
                "name": "SDL3",
                "category": "runtime"
              },
              "versions": {
                "android": [
                  {
                    "id": 2,
                    "package_id": "sdl3",
                    "platform": "android",
                    "version": "3.2.0",
                    "artifact_type": "shared",
                    "install_type": "download",
                    "download_size": 1024,
                    "download_url": "packages/sdl3/3.2.0/sdl3.tar.xz",
                    "checksum": "sha256:abc",
                    "is_latest": true
                  }
                ]
              },
              "downloads": {
                "sdl3:2": {
                  "package_id": "sdl3",
                  "version": "3.2.0",
                  "platform": "android",
                  "install_type": "download",
                  "size": 1024,
                  "checksum": "sha256:abc",
                  "sources": [
                    {
                      "id": 1,
                      "name": "GitHub",
                      "url": "packages/sdl3/3.2.0/sdl3-arm64-v8a.tar.xz",
                      "priority": 100,
                      "abi": "arm64-v8a",
                      "size": 768,
                      "checksum": "sha256:arm64"
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val version = detail.versions?.android?.single()
        val download = detail.downloads["sdl3:2"]

        assertThat(detail.pkg.id).isEqualTo("sdl3")
        assertThat(version?.platform).isEqualTo(Platform.ANDROID)
        assertThat(version?.downloadSize).isEqualTo(1024)
        val source = download?.sources?.single()
        assertThat(source?.supportsRange).isTrue()
        assertThat(source?.abi).isEqualTo("arm64-v8a")
        assertThat(source?.size).isEqualTo(768)
        assertThat(source?.checksum).isEqualTo("sha256:arm64")
    }

    @Test
    fun api_shouldLoadV2CatalogAndFetchPackageDetailOnDemand(): Unit = runBlocking {
        val baseUrl = "https://registry.test"
        val v2IndexUrl = registryUrl(baseUrl, "packages/index.v2.json")
        val detailUrl = "$baseUrl/packages/sdl3/package.json"
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                v2IndexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 2,
                      "categories": [
                        { "id": "runtime", "name": "Runtime", "sort_order": 0 }
                      ],
                      "packages": [
                        {
                          "id": "sdl3",
                          "name": "SDL3",
                          "category": "runtime",
                          "detail_url": "packages/sdl3/package.json",
                          "android": {
                            "version": "3.2.0",
                            "artifact_type": "shared",
                            "install_type": "download",
                            "is_latest": true
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                detailUrl to RegistryResponse(
                    body = """
                    {
                      "package": {
                        "id": "sdl3",
                        "name": "SDL3",
                        "category": "runtime",
                        "android": {
                          "version": "3.2.0",
                          "artifact_type": "shared",
                          "install_type": "download",
                          "download_url": "packages/sdl3/3.2.0/sdl3.tar.xz",
                          "is_latest": true
                        }
                      },
                      "versions": {
                        "android": [
                          {
                            "id": 2,
                            "package_id": "sdl3",
                            "platform": "android",
                            "version": "3.2.0",
                            "artifact_type": "shared",
                            "install_type": "download",
                            "download_size": 1024,
                            "download_url": "packages/sdl3/3.2.0/sdl3.tar.xz",
                            "is_latest": true
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                ),
            )
        )
        val api = packageApi(v2IndexUrl, interceptor.client())

        val versionsResult = api.getPackageVersions("sdl3")
        val downloadResult = api.getDownloadInfo("sdl3", 2)

        assertThat(versionsResult).isInstanceOf(ApiResult.Success::class.java)
        assertThat((versionsResult as ApiResult.Success).data.android?.single()?.version).isEqualTo("3.2.0")
        assertThat(downloadResult).isInstanceOf(ApiResult.Success::class.java)
        val downloadInfo = (downloadResult as ApiResult.Success).data
        assertThat(downloadInfo.sources.first().url).isEqualTo("$baseUrl/packages/sdl3/3.2.0/sdl3.tar.xz")
        assertThat(interceptor.requestedUrls)
            .containsExactly(v2IndexUrl.url, detailUrl)
            .inOrder()
    }

    @Test
    fun api_shouldFailWithoutRequestingV1IndexWhenV2CatalogUnavailable(): Unit = runBlocking {
        val baseUrl = "https://registry.test"
        val v2IndexUrl = registryUrl(baseUrl, "packages/index.v2.json")
        val v1IndexUrl = registryUrl(baseUrl, "packages/index.json")
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                v1IndexUrl.url to RegistryResponse(
                    body = """
                    {
                      "packages": [
                        {
                          "id": "sdl3",
                          "name": "SDL3",
                          "category": "runtime",
                          "android": {
                            "version": "3.2.0",
                            "artifact_type": "shared",
                            "install_type": "download",
                            "download_url": "packages/sdl3/3.2.0/sdl3.tar.xz",
                            "is_latest": true
                          }
                        }
                      ],
                      "categories": [
                        { "id": "runtime", "name": "Runtime", "sort_order": 0 }
                      ]
                    }
                    """.trimIndent()
                ),
                v2IndexUrl.url to RegistryResponse(code = 404),
            )
        )
        val api = packageApi(v2IndexUrl, interceptor.client())

        val result = api.getPackageDetail("sdl3")

        assertThat(result).isInstanceOf(ApiResult.Error::class.java)
        assertThat(interceptor.requestedUrls)
            .containsExactly(v2IndexUrl.url)
            .inOrder()
    }

    @Test
    fun api_shouldFallbackToPublicProxyAndReturnMirroredDownloadSources(): Unit = runBlocking {
        val rawBaseUrl = GitHubRegistryConfig.GITHUB_RAW_BASE_URL
        val proxyPrefix = GitHubRegistryConfig.PUBLIC_GITHUB_PROXY_PREFIXES.first()
        val officialIndexUrl = registryUrl("GitHub Raw", rawBaseUrl, "packages/index.v2.json")
        val proxyIndexUrl = registryUrl(
            name = "proxy",
            baseUrl = proxyPrefix + rawBaseUrl,
            path = "packages/index.v2.json",
            urlPrefix = proxyPrefix,
        )
        val proxyDetailUrl = proxyPrefix + "$rawBaseUrl/packages/sdl3/package.json"
        val githubDownloadUrl = "https://github.com/wuxianggujun/TinaIDE-Registry/releases/download/" +
            "packages/sdl3.tar.xz"
        val interceptor = FakeRegistryInterceptor(
            mapOf(
                officialIndexUrl.url to RegistryResponse(code = 503),
                proxyIndexUrl.url to RegistryResponse(
                    body = """
                    {
                      "schema_version": 2,
                      "categories": [
                        { "id": "runtime", "name": "Runtime", "sort_order": 0 }
                      ],
                      "packages": [
                        {
                          "id": "sdl3",
                          "name": "SDL3",
                          "category": "runtime",
                          "detail_url": "packages/sdl3/package.json",
                          "android": {
                            "version": "3.2.0",
                            "artifact_type": "shared",
                            "install_type": "download",
                            "is_latest": true
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                proxyDetailUrl to RegistryResponse(
                    body = """
                    {
                      "package": {
                        "id": "sdl3",
                        "name": "SDL3",
                        "category": "runtime"
                      },
                      "versions": {
                        "android": [
                          {
                            "id": 2,
                            "package_id": "sdl3",
                            "platform": "android",
                            "version": "3.2.0",
                            "artifact_type": "shared",
                            "install_type": "download",
                            "download_size": 1024,
                            "download_url": "$githubDownloadUrl",
                            "is_latest": true
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                ),
            )
        )
        val api = packageApi(listOf(officialIndexUrl, proxyIndexUrl), interceptor.client())

        val result = api.getDownloadInfo("sdl3", 2)

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val downloadInfo = (result as ApiResult.Success).data
        assertThat(downloadInfo.sources.map { it.url })
            .containsAtLeast(proxyPrefix + githubDownloadUrl, githubDownloadUrl)
            .inOrder()
        assertThat(interceptor.requestedUrls)
            .containsExactly(officialIndexUrl.url, proxyIndexUrl.url, proxyDetailUrl)
            .inOrder()
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

    private fun packageApi(
        v2IndexUrl: RegistryUrl,
        client: OkHttpClient,
    ): PackageApiClient = packageApi(listOf(v2IndexUrl), client)

    private fun packageApi(
        indexUrls: List<RegistryUrl>,
        client: OkHttpClient,
    ): PackageApiClient {
        val constructor = PackageApiClient::class.java.getDeclaredConstructor(
            List::class.java,
            OkHttpClient::class.java,
            GitHubRegistryProxySettings::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            indexUrls,
            client,
            GitHubRegistryProxySettings(),
        ) as PackageApiClient
    }

    private data class RegistryResponse(
        val code: Int = 200,
        val body: String = "",
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
                .body(registryResponse.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
