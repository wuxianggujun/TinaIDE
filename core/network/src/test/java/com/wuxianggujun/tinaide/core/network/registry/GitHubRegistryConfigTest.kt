package com.wuxianggujun.tinaide.core.network.registry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubRegistryConfigTest {

    @Test
    fun gitHubProxyPrefixes_shouldNormalizeAndPreferCustomMirror() {
        val prefixes = GitHubRegistryConfig.gitHubProxyPrefixes(" mirror.example.com/path ")

        assertThat(prefixes.first()).isEqualTo("https://mirror.example.com/path/")
        assertThat(prefixes).containsAtLeastElementsIn(GitHubRegistryConfig.PUBLIC_GITHUB_PROXY_PREFIXES)
    }

    @Test
    fun gitHubUrlCandidates_shouldTryCustomMirrorBeforePublicMirrors() {
        val githubUrl = "https://github.com/wuxianggujun/TinaIDE/releases/latest"
        val candidates = GitHubRegistryConfig.gitHubUrlCandidates(
            url = githubUrl,
            customProxyPrefix = "https://mirror.example.com/",
        )

        assertThat(candidates[0]).isEqualTo("https://mirror.example.com/$githubUrl")
        assertThat(candidates[1]).isEqualTo(githubUrl)
        assertThat(candidates).contains("https://gh.llkk.cc/$githubUrl")
    }

    @Test
    fun registryEndpoints_shouldInsertCustomMirrorBeforePublicMirrors() {
        val endpoints = GitHubRegistryConfig.registryEndpoints("mirror.example.com")

        assertThat(endpoints.map { it.name }).containsAtLeast(
            "GitHub Raw proxy mirror.example.com",
            "GitHub Raw",
            "jsDelivr CDN",
        ).inOrder()
        assertThat(endpoints[0].baseUrl).isEqualTo(
            "https://mirror.example.com/${GitHubRegistryConfig.GITHUB_RAW_BASE_URL}"
        )
    }

    @Test
    fun registryResourceUrlCandidates_shouldRejectCleartextAndInvalidAbsoluteUrls() {
        val endpoint = RegistryEndpoint("test", "https://registry.example.com")

        assertThat(
            GitHubRegistryConfig.registryResourceUrlCandidates(
                urlOrPath = "http://registry.example.com/package.json",
                endpoint = endpoint,
            )
        ).isEmpty()
        assertThat(
            GitHubRegistryConfig.registryResourceUrlCandidates(
                urlOrPath = "https:///missing-host/package.json",
                endpoint = endpoint,
            )
        ).isEmpty()
        assertThat(
            GitHubRegistryConfig.registryResourceUrlCandidates(
                urlOrPath = "https://token@registry.example.com/package.json",
                endpoint = endpoint,
            )
        ).isEmpty()
        assertThat(GitHubRegistryConfig.normalizeGitHubProxyPrefix("https://token@mirror.example.com"))
            .isNull()
    }

    @Test
    fun registryResourceUrlCandidates_shouldResolveRelativePathOverHttps() {
        val endpoint = RegistryEndpoint("test", "https://registry.example.com")

        assertThat(
            GitHubRegistryConfig.registryResourceUrlCandidates(
                urlOrPath = "packages/sdl2/package.json",
                endpoint = endpoint,
            )
        ).containsExactly("https://registry.example.com/packages/sdl2/package.json")
    }

    @Test
    fun selectedMirrorIsFirstForMetadataAndArchivesWithoutDoubleWrapping() {
        val mirror = "https://ghproxy.net/"
        val archive = "https://codeload.github.com/microsoft/vcpkg/tar.gz/abc"
        assertThat(GitHubRegistryConfig.gitHubUrlCandidates(archive, mirror).first()).isEqualTo(mirror + archive)
        assertThat(GitHubRegistryConfig.gitHubUrlCandidates(mirror + archive, mirror))
            .containsNoDuplicates()
        assertThat(GitHubRegistryConfig.registryResourceUrlCandidates("packages/fmt/package.json",
            RegistryEndpoint("raw", GitHubRegistryConfig.GITHUB_RAW_BASE_URL), mirror).first())
            .isEqualTo(mirror + GitHubRegistryConfig.GITHUB_RAW_BASE_URL + "/packages/fmt/package.json")
        assertThat(GitHubRegistryConfig.registryEndpoints(mirror).map { it.baseUrl }).containsNoDuplicates()
    }

    @Test
    fun mirrorPrefixRejectsMalformedEndpointsAndEmbeddedDownloadUrls() {
        listOf("http://mirror.example", "https://mirror.example:99999", "https://mirror.example?token=x",
            "https://mirror.example/#fragment", "https://mirror.example/https://github.com/org/repo").forEach {
            assertThat(GitHubRegistryConfig.normalizeGitHubProxyPrefix(it)).isNull()
        }
        assertThat(GitHubRegistryConfig.normalizeGitHubProxyPrefix(" HTTPS://Mirror.Example/proxy/ "))
            .isEqualTo("https://mirror.example/proxy/")
        assertThat(GitHubRegistryConfig.gitHubUrlCandidates("https://github.com.evil.example/file", "https://mirror.example/"))
            .containsExactly("https://github.com.evil.example/file")
    }
}
