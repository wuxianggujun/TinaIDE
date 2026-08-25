package com.wuxianggujun.tinaide.update

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.wuxianggujun.tinaide.core.common.AppVersionInfoReader
import com.wuxianggujun.tinaide.core.config.AppPreferences
import com.wuxianggujun.tinaide.core.network.readUtf8Limited
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateInfo(
    val tagName: String,
    val releaseName: String,
    val currentVersionName: String,
    val releaseNotes: String?,
    val releasePageUrl: String,
    val downloadUrl: String,
    val assetName: String?,
)

class AppUpdateChecker internal constructor(
    context: Context,
    private val client: OkHttpClient = GitHubRegistryHttpClientFactory.probe(context),
    private val currentVersionNameProvider: (Context) -> String = {
        AppVersionInfoReader.read(it).versionName
    },
    private val endpoints: List<AppUpdateEndpoint> = AppUpdateEndpoints.defaultsFor(context),
) {
    private val appContext = context.applicationContext
    private val prefs = AppPreferences.get(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val currentVersionName = currentVersionNameProvider(appContext)
            val latest = fetchLatestRelease()
            val release = latest.release
            AppUpdateEndpoints.requireTrustedRelease(release)

            if (release.draft || release.prerelease) return@runCatching null
            if (!AppUpdateVersioning.isRemoteNewer(currentVersionName, release.tagName)) {
                return@runCatching null
            }
            if (release.tagName == dismissedTagName()) return@runCatching null

            val preferredAsset = AppUpdateVersioning.selectPreferredApkAsset(
                assets = release.assets,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            val releasePageUrl = release.htmlUrl
            AppUpdateInfo(
                tagName = release.tagName,
                releaseName = release.name?.takeIf(String::isNotBlank) ?: release.tagName,
                currentVersionName = currentVersionName,
                releaseNotes = release.body?.takeIf(String::isNotBlank),
                releasePageUrl = releasePageUrl,
                downloadUrl = preferredAsset?.browserDownloadUrl ?: releasePageUrl,
                assetName = preferredAsset?.name,
            )
        }
    }

    fun markDismissed(tagName: String) {
        prefs.edit { putString(PREF_KEY_DISMISSED_TAG, tagName) }
    }

    private fun dismissedTagName(): String? = prefs.getString(PREF_KEY_DISMISSED_TAG, null)

    private fun fetchLatestRelease(): AppUpdateRelease {
        var lastFailure: Throwable? = null
        for (endpoint in endpoints) {
            val result = when (endpoint.kind) {
                AppUpdateEndpointKind.API -> fetchLatestReleaseFromApi(endpoint)
                AppUpdateEndpointKind.LATEST_REDIRECT -> fetchLatestReleaseFromRedirect(endpoint)
            }
            result
                .onSuccess { return it }
                .onFailure { lastFailure = it }
        }
        throw IllegalStateException(
            "All app update endpoints failed",
            lastFailure,
        )
    }

    private fun fetchLatestReleaseFromApi(endpoint: AppUpdateEndpoint): Result<AppUpdateRelease> = runCatching {
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("${endpoint.name} failed: HTTP ${response.code}")
            }
            val body = response.body?.readUtf8Limited(MAX_RELEASE_RESPONSE_BYTES)?.takeIf(String::isNotBlank)
                ?: error("${endpoint.name} returned empty body")
            AppUpdateRelease(
                release = json.decodeFromString<GitHubRelease>(body),
                endpoint = endpoint,
            )
        }
    }

    private fun fetchLatestReleaseFromRedirect(endpoint: AppUpdateEndpoint): Result<AppUpdateRelease> = runCatching {
        val request = Request.Builder()
            .url(endpoint.url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("${endpoint.name} failed: HTTP ${response.code}")
            }
            val finalUrl = endpoint.unwrapGitHubUrl(response.request.url.toString())
                ?: error("${endpoint.name} redirected outside the official TinaIDE repository")
            val tagName = AppUpdateEndpoints.extractReleaseTag(finalUrl)
                ?: error("${endpoint.name} did not expose a release tag")
            AppUpdateRelease(
                release = GitHubRelease(
                    tagName = tagName,
                    name = tagName,
                    body = null,
                    htmlUrl = finalUrl,
                    draft = false,
                    prerelease = false,
                    assets = emptyList(),
                ),
                endpoint = endpoint,
            )
        }
    }

    private companion object {
        private const val PREF_KEY_DISMISSED_TAG = "app_update_dismissed_tag"
        private const val MAX_RELEASE_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

internal enum class AppUpdateEndpointKind {
    API,
    LATEST_REDIRECT,
}

internal data class AppUpdateEndpoint(
    val name: String,
    val kind: AppUpdateEndpointKind,
    val url: String,
    val urlPrefix: String? = null,
) {
    fun unwrapGitHubUrl(url: String): String? {
        val normalized = url.trim()
        val unwrapped = urlPrefix
            ?.takeIf(normalized::startsWith)
            ?.let(normalized::removePrefix)
            ?: normalized
        return unwrapped.takeIf(GitHubRegistryConfig::isGitHubUrl)
    }
}

internal data class AppUpdateRelease(
    val release: GitHubRelease,
    val endpoint: AppUpdateEndpoint,
)

internal object AppUpdateEndpoints {
    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/wuxianggujun/TinaIDE/releases/latest"
    private const val GITHUB_RELEASES_LATEST_URL =
        "https://github.com/wuxianggujun/TinaIDE/releases/latest"

    val defaults: List<AppUpdateEndpoint> = buildDefaults()

    fun defaultsFor(context: Context): List<AppUpdateEndpoint> = buildDefaults(
        customProxyPrefix = GitHubRegistryProxyConfig.load(context.applicationContext).customMirrorPrefix,
    )

    fun buildDefaults(customProxyPrefix: String? = null): List<AppUpdateEndpoint> = buildList {
        add(
            AppUpdateEndpoint(
                name = "GitHub Releases API",
                kind = AppUpdateEndpointKind.API,
                url = GITHUB_RELEASES_API_URL,
            )
        )
        add(
            AppUpdateEndpoint(
                name = "GitHub latest release page",
                kind = AppUpdateEndpointKind.LATEST_REDIRECT,
                url = GITHUB_RELEASES_LATEST_URL,
            )
        )
        GitHubRegistryConfig.gitHubProxyPrefixes(customProxyPrefix).forEach { prefix ->
            add(
                AppUpdateEndpoint(
                    name = "GitHub proxy API ${prefix.removePrefix("https://").trimEnd('/')}",
                    kind = AppUpdateEndpointKind.API,
                    url = prefix + GITHUB_RELEASES_API_URL,
                    urlPrefix = prefix,
                )
            )
            add(
                AppUpdateEndpoint(
                    name = "GitHub proxy latest ${prefix.removePrefix("https://").trimEnd('/')}",
                    kind = AppUpdateEndpointKind.LATEST_REDIRECT,
                    url = prefix + GITHUB_RELEASES_LATEST_URL,
                    urlPrefix = prefix,
                )
            )
        }
    }

    fun extractReleaseTag(url: String): String? {
        val marker = "/releases/tag/"
        return url
            .substringAfter(marker, missingDelimiterValue = "")
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore('/')
            .takeIf(String::isNotBlank)
    }

    fun requireTrustedRelease(release: GitHubRelease) {
        require(release.tagName.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,99}$"))) {
            "Invalid TinaIDE release tag"
        }
        require(release.htmlUrl == "$GITHUB_REPOSITORY_URL/releases/tag/${release.tagName}") {
            "Release page is outside the official TinaIDE repository"
        }
        release.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .forEach { asset ->
                require(asset.name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,199}\\.apk$", RegexOption.IGNORE_CASE))) {
                    "Invalid TinaIDE release asset name"
                }
                require(
                    asset.browserDownloadUrl ==
                        "$GITHUB_REPOSITORY_URL/releases/download/${release.tagName}/${asset.name}"
                ) {
                    "Release asset is outside the official TinaIDE repository"
                }
            }
    }

    private const val GITHUB_REPOSITORY_URL = "https://github.com/wuxianggujun/TinaIDE"
}

internal object AppUpdateVersioning {
    fun isRemoteNewer(currentVersionName: String, remoteTagName: String): Boolean {
        val current = versionSegments(currentVersionName)
        val remote = versionSegments(remoteTagName)
        if (current.isEmpty() || remote.isEmpty()) return false

        val maxSize = maxOf(current.size, remote.size)
        for (index in 0 until maxSize) {
            val currentPart = current.getOrElse(index) { 0 }
            val remotePart = remote.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    internal fun selectPreferredApkAsset(
        assets: List<GitHubReleaseAsset>,
        supportedAbis: List<String>,
    ): GitHubReleaseAsset? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.browserDownloadUrl.isNotBlank()
        }
        if (apkAssets.isEmpty()) return null

        val normalizedAbis = supportedAbis
            .map(String::lowercase)
            .flatMap { abi -> listOf(abi, abi.replace("-", "_")) }
        return apkAssets.firstOrNull { asset ->
            val assetName = asset.name.lowercase()
            normalizedAbis.any(assetName::contains)
        } ?: apkAssets.first()
    }

    private fun versionSegments(value: String): List<Int> {
        val match = VERSION_PATTERN.find(value.trim().removePrefix("v").removePrefix("V"))
            ?: return emptyList()
        return match.value.split('.').mapNotNull(String::toIntOrNull)
    }

    private val VERSION_PATTERN = Regex("""\d+(?:\.\d+){0,3}""")
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url")
    val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
internal data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
)
