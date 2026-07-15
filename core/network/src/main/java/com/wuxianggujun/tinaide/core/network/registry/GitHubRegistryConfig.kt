package com.wuxianggujun.tinaide.core.network.registry

import java.net.URI

object GitHubRegistryConfig {
    const val OWNER = "wuxianggujun"
    const val REPOSITORY = "TinaIDE-Registry"
    const val BRANCH = "main"
    const val REGISTRY_SCHEMA_VERSION = 3
    const val REGISTRY_V2_INTRODUCED_APP_VERSION = "0.17.11"
    const val REGISTRY_V3_INTRODUCED_APP_VERSION = "0.18.11"
    const val REGISTRY_V1_FALLBACK_REMOVED_APP_VERSION = "0.18.0"

    const val GITHUB_RAW_BASE_URL = "https://raw.githubusercontent.com/$OWNER/$REPOSITORY/$BRANCH"
    const val JSDELIVR_BASE_URL = "https://cdn.jsdelivr.net/gh/$OWNER/$REPOSITORY@$BRANCH"

    const val RAW_BASE_URL = GITHUB_RAW_BASE_URL
    const val PRIMARY_BASE_URL = GITHUB_RAW_BASE_URL

    const val PLUGINS_INDEX_V2_PATH = "plugins/index.v2.json"
    const val PLUGINS_INDEX_V3_PATH = "plugins/index.v3.json"
    const val PACKAGES_INDEX_V2_PATH = "packages/index.v2.json"
    const val LINUX_DISTRO_MANIFEST_PATH = "linux-distro/manifest.v1.json"

    val PUBLIC_GITHUB_PROXY_PREFIXES: List<String> = listOf(
        "https://gh.llkk.cc/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )

    val REGISTRY_ENDPOINTS: List<RegistryEndpoint> = registryEndpoints()

    fun registryEndpoints(customProxyPrefix: String? = null): List<RegistryEndpoint> = buildList {
        add(RegistryEndpoint(name = "GitHub Raw", baseUrl = GITHUB_RAW_BASE_URL))
        add(RegistryEndpoint(name = "jsDelivr CDN", baseUrl = JSDELIVR_BASE_URL))
        gitHubProxyPrefixes(customProxyPrefix).forEach { prefix ->
            add(
                RegistryEndpoint(
                    name = "GitHub Raw proxy ${prefix.removePrefix("https://").trimEnd('/')}",
                    baseUrl = prefix + GITHUB_RAW_BASE_URL,
                    urlPrefix = prefix,
                )
            )
        }
    }

    fun pluginIndexV2Urls(customProxyPrefix: String? = null): List<RegistryUrl> = indexUrls(
        path = PLUGINS_INDEX_V2_PATH,
        customProxyPrefix = customProxyPrefix,
    )

    fun pluginIndexV3Urls(customProxyPrefix: String? = null): List<RegistryUrl> = indexUrls(
        path = PLUGINS_INDEX_V3_PATH,
        customProxyPrefix = customProxyPrefix,
    )

    fun packageIndexV2Urls(customProxyPrefix: String? = null): List<RegistryUrl> = indexUrls(
        path = PACKAGES_INDEX_V2_PATH,
        customProxyPrefix = customProxyPrefix,
    )

    fun linuxDistroManifestUrls(customProxyPrefix: String? = null): List<RegistryUrl> = indexUrls(
        path = LINUX_DISTRO_MANIFEST_PATH,
        customProxyPrefix = customProxyPrefix,
    )

    fun resolveRawUrl(urlOrPath: String, baseUrl: String = PRIMARY_BASE_URL): String {
        val value = urlOrPath.trim()
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "${baseUrl.trimEnd('/')}/${value.removePrefix("/")}"
        }
    }

    fun resolveRawUrl(urlOrPath: String, endpoint: RegistryEndpoint): String {
        val resolved = resolveRawUrl(urlOrPath, endpoint.baseUrl)
        return endpoint.rewriteGitHubUrl(resolved)
    }

    fun registryResourceUrlCandidates(
        urlOrPath: String,
        endpoint: RegistryEndpoint,
        customProxyPrefix: String? = null,
    ): List<String> {
        val value = urlOrPath.trim()
        if (value.isBlank()) return emptyList()
        val primaryUrl = resolveRawUrl(value, endpoint)
        val rawGitHubUrl = if (value.startsWith("http://") || value.startsWith("https://")) {
            unproxiedGitHubUrl(value, customProxyPrefix) ?: value
        } else {
            resolveRawUrl(value, GITHUB_RAW_BASE_URL)
        }
        return (listOf(primaryUrl) + gitHubUrlCandidates(rawGitHubUrl, customProxyPrefix)).distinct()
    }

    fun rewriteGitHubUrl(url: String, proxyPrefix: String?): String {
        val prefix = proxyPrefix ?: return url
        val normalizedUrl = url.trim()
        if (!isGitHubUrl(normalizedUrl)) return url
        if (normalizedUrl.startsWith(prefix)) return normalizedUrl
        return prefix + normalizedUrl
    }

    fun gitHubUrlCandidates(
        url: String,
        customProxyPrefix: String? = null,
    ): List<String> {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return emptyList()
        val originalGitHubUrl = unproxiedGitHubUrl(normalizedUrl, customProxyPrefix) ?: normalizedUrl
        if (!isGitHubUrl(originalGitHubUrl)) return listOf(normalizedUrl)

        return buildList {
            add(normalizedUrl)
            if (normalizedUrl != originalGitHubUrl) add(originalGitHubUrl)
            gitHubProxyPrefixes(customProxyPrefix).forEach { prefix ->
                add(prefix + originalGitHubUrl)
            }
        }.distinct()
    }

    fun gitHubProxyPrefixes(customProxyPrefix: String? = null): List<String> {
        val customPrefix = normalizeGitHubProxyPrefix(customProxyPrefix)
        return (listOfNotNull(customPrefix) + PUBLIC_GITHUB_PROXY_PREFIXES).distinct()
    }

    fun normalizeGitHubProxyPrefix(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val withoutQuery = withScheme.substringBefore('?').substringBefore('#')
        val uri = runCatching { URI(withoutQuery) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        return withoutQuery.trimEnd('/') + "/"
    }

    fun isGitHubUrl(url: String): Boolean = url.startsWith("https://github.com/") ||
        url.startsWith("https://api.github.com/") ||
        url.startsWith("https://raw.githubusercontent.com/") ||
        url.startsWith("https://objects.githubusercontent.com/")

    private fun unproxiedGitHubUrl(
        url: String,
        customProxyPrefix: String? = null,
    ): String? {
        gitHubProxyPrefixes(customProxyPrefix).forEach { prefix ->
            if (url.startsWith(prefix)) {
                return url.removePrefix(prefix).takeIf(::isGitHubUrl)
            }
        }
        return null
    }

    private fun indexUrls(
        path: String,
        customProxyPrefix: String? = null,
    ): List<RegistryUrl> = registryEndpoints(customProxyPrefix).map { endpoint ->
        RegistryUrl(
            endpoint = endpoint,
            url = resolveRawUrl(path, endpoint),
        )
    }
}

data class RegistryEndpoint(
    val name: String,
    val baseUrl: String,
    val urlPrefix: String? = null,
) {
    fun rewriteGitHubUrl(url: String): String = GitHubRegistryConfig.rewriteGitHubUrl(url, urlPrefix)
}

data class RegistryUrl(
    val endpoint: RegistryEndpoint,
    val url: String,
)
