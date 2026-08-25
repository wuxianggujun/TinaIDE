package com.wuxianggujun.tinaide.update

import android.app.Application
import androidx.core.content.edit
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.AppPreferences
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class AppUpdateCheckerTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AppPreferences.get(context).edit { clear() }
    }

    @Test
    fun checkForUpdate_shouldFallbackToProxyApiAndKeepOfficialReleaseUrls() = runTest {
        val officialUrl = "https://api.github.test/releases/latest"
        val proxyPrefix = "https://proxy.test/"
        val proxyUrl = proxyPrefix + officialUrl
        val releasePageUrl = "https://github.com/wuxianggujun/TinaIDE/releases/tag/v0.18.2"
        val assetUrl = "https://github.com/wuxianggujun/TinaIDE/releases/download/v0.18.2/" +
            "TinaIDE-arm64-v8a-release.apk"
        val client = RecordingInterceptor { request ->
            when (request.url.toString()) {
                officialUrl -> jsonResponse(request, """{"message":"blocked"}""", code = 503)
                proxyUrl -> jsonResponse(request, latestReleaseJson("v0.18.2"))
                else -> jsonResponse(request, """{"message":"unexpected"}""", code = 404)
            }
        }
        val checker = checker(
            client = client.client(),
            currentVersionName = "0.17.4",
            endpoints = listOf(
                AppUpdateEndpoint(
                    name = "official",
                    kind = AppUpdateEndpointKind.API,
                    url = officialUrl,
                ),
                AppUpdateEndpoint(
                    name = "proxy",
                    kind = AppUpdateEndpointKind.API,
                    url = proxyUrl,
                    urlPrefix = proxyPrefix,
                ),
            ),
        )

        val update = checker.checkForUpdate().getOrThrow()

        assertThat(client.requestedUrls).containsExactly(officialUrl, proxyUrl).inOrder()
        assertThat(update?.tagName).isEqualTo("v0.18.2")
        assertThat(update?.assetName).isEqualTo("TinaIDE-arm64-v8a-release.apk")
        assertThat(update?.releasePageUrl).isEqualTo(releasePageUrl)
        assertThat(update?.downloadUrl).isEqualTo(assetUrl)
    }

    @Test
    fun checkForUpdate_shouldFallbackToProxyLatestRedirectWhenApiEndpointsFail() = runTest {
        val officialApiUrl = "https://api.github.test/releases/latest"
        val proxyPrefix = "https://proxy.test/"
        val latestUrl = "https://github.com/wuxianggujun/TinaIDE/releases/latest"
        val finalReleaseUrl = "https://github.com/wuxianggujun/TinaIDE/releases/tag/v0.18.3"
        val proxyLatestUrl = proxyPrefix + latestUrl
        val finalProxyReleaseUrl = proxyPrefix + finalReleaseUrl
        val client = RecordingInterceptor { request ->
            when (request.url.toString()) {
                officialApiUrl -> jsonResponse(request, """{"message":"blocked"}""", code = 503)
                proxyLatestUrl -> textResponse(request, "", responseRequestUrl = finalProxyReleaseUrl)
                else -> jsonResponse(request, """{"message":"unexpected"}""", code = 404)
            }
        }
        val checker = checker(
            client = client.client(),
            currentVersionName = "0.18.2",
            endpoints = listOf(
                AppUpdateEndpoint(
                    name = "official-api",
                    kind = AppUpdateEndpointKind.API,
                    url = officialApiUrl,
                ),
                AppUpdateEndpoint(
                    name = "proxy-latest",
                    kind = AppUpdateEndpointKind.LATEST_REDIRECT,
                    url = proxyLatestUrl,
                    urlPrefix = proxyPrefix,
                ),
            ),
        )

        val update = checker.checkForUpdate().getOrThrow()

        assertThat(client.requestedUrls).containsExactly(officialApiUrl, proxyLatestUrl).inOrder()
        assertThat(update?.tagName).isEqualTo("v0.18.3")
        assertThat(update?.releasePageUrl).isEqualTo(finalReleaseUrl)
        assertThat(update?.downloadUrl).isEqualTo(finalReleaseUrl)
        assertThat(update?.assetName).isNull()
    }

    @Test
    fun appUpdateEndpoints_shouldProvideOfficialAndPublicProxyFallbacks() {
        val endpoints = AppUpdateEndpoints.defaults

        assertThat(endpoints.first().kind).isEqualTo(AppUpdateEndpointKind.API)
        assertThat(endpoints.first().url)
            .isEqualTo("https://api.github.com/repos/wuxianggujun/TinaIDE/releases/latest")
        assertThat(endpoints.any { it.urlPrefix != null && it.kind == AppUpdateEndpointKind.API })
            .isTrue()
        assertThat(endpoints.any { it.urlPrefix != null && it.kind == AppUpdateEndpointKind.LATEST_REDIRECT })
            .isTrue()
        val proxiedTaggedRelease = "https://proxy.test/https://github.com/wuxianggujun/" +
            "TinaIDE/releases/tag/v0.18.2?from=latest"
        assertThat(
            AppUpdateEndpoints.extractReleaseTag(proxiedTaggedRelease)
        ).isEqualTo("v0.18.2")
    }

    @Test
    fun appUpdateEndpoints_shouldPreferCustomProxyBeforePublicProxyFallbacks() {
        val endpoints = AppUpdateEndpoints.buildDefaults("mirror.example.com")

        assertThat(endpoints[2].url)
            .isEqualTo("https://mirror.example.com/https://api.github.com/repos/wuxianggujun/TinaIDE/releases/latest")
        assertThat(endpoints[2].urlPrefix).isEqualTo("https://mirror.example.com/")
        assertThat(endpoints[3].url)
            .isEqualTo("https://mirror.example.com/https://github.com/wuxianggujun/TinaIDE/releases/latest")
    }

    @Test
    fun appUpdateEndpoints_shouldIgnoreNonApkAssetsWhenValidatingReleaseDownloads() {
        val tag = "v0.18.4"
        val release = GitHubRelease(
            tagName = tag,
            htmlUrl = "https://github.com/wuxianggujun/TinaIDE/releases/tag/$tag",
            assets = listOf(
                GitHubReleaseAsset(
                    name = "checksums.txt",
                    browserDownloadUrl = "https://downloads.example.com/checksums.txt",
                ),
                GitHubReleaseAsset(
                    name = "TinaIDE-arm64-v8a-release.apk",
                    browserDownloadUrl = "https://github.com/wuxianggujun/TinaIDE/releases/download/$tag/" +
                        "TinaIDE-arm64-v8a-release.apk",
                ),
            ),
        )

        AppUpdateEndpoints.requireTrustedRelease(release)
    }

    @Test
    fun appUpdateEndpoints_shouldRejectApkOutsideOfficialRelease() {
        val tag = "v0.18.4"
        val release = GitHubRelease(
            tagName = tag,
            htmlUrl = "https://github.com/wuxianggujun/TinaIDE/releases/tag/$tag",
            assets = listOf(
                GitHubReleaseAsset(
                    name = "TinaIDE-arm64-v8a-release.apk",
                    browserDownloadUrl = "https://downloads.example.com/TinaIDE.apk",
                )
            ),
        )

        assertThat(runCatching { AppUpdateEndpoints.requireTrustedRelease(release) }.isFailure).isTrue()
    }

    private fun checker(
        client: OkHttpClient,
        currentVersionName: String,
        endpoints: List<AppUpdateEndpoint>,
    ): AppUpdateChecker = AppUpdateChecker(
        context = context,
        client = client,
        currentVersionNameProvider = { currentVersionName },
        endpoints = endpoints,
    )

    private fun latestReleaseJson(tagName: String): String {
        val releasePageUrl = "https://github.com/wuxianggujun/TinaIDE/releases/tag/$tagName"
        val assetUrl = "https://github.com/wuxianggujun/TinaIDE/releases/download/$tagName/" +
            "TinaIDE-arm64-v8a-release.apk"
        return """
        {
          "tag_name": "$tagName",
          "name": "TinaIDE $tagName",
          "body": "Release notes",
          "html_url": "$releasePageUrl",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "TinaIDE-arm64-v8a-release.apk",
              "browser_download_url": "$assetUrl"
            }
          ]
        }
        """.trimIndent()
    }

    private class RecordingInterceptor(
        private val handler: (Request) -> Response,
    ) : Interceptor {
        val requestedUrls = mutableListOf<String>()

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(this)
            .build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestedUrls += request.url.toString()
            return handler(request)
        }
    }

    private fun jsonResponse(
        request: Request,
        body: String,
        code: Int = 200,
    ): Response = response(
        request = request,
        body = body,
        code = code,
        contentType = "application/json",
    )

    private fun textResponse(
        request: Request,
        body: String,
        code: Int = 200,
        responseRequestUrl: String = request.url.toString(),
    ): Response = response(
        request = Request.Builder().url(responseRequestUrl).build(),
        body = body,
        code = code,
        contentType = "text/plain",
    )

    private fun response(
        request: Request,
        body: String,
        code: Int,
        contentType: String,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()
}
