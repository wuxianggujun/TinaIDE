package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linuxdistro.DistroArchitecture
import com.wuxianggujun.tinaide.core.linuxdistro.DistroArchiveFormat
import com.wuxianggujun.tinaide.core.linuxdistro.DistroArtifact
import com.wuxianggujun.tinaide.core.linuxdistro.DistroChecksum
import com.wuxianggujun.tinaide.core.linuxdistro.DistroChecksumAlgorithm
import com.wuxianggujun.tinaide.core.linuxdistro.DistroDefinition
import com.wuxianggujun.tinaide.core.linuxdistro.DistroFamily
import com.wuxianggujun.tinaide.core.linuxdistro.DistroPackageManager
import com.wuxianggujun.tinaide.core.linuxdistro.DistroRelease
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifest
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifestParser
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifestSource
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class LinuxDistroCatalogRepositoryTest {

    @Test
    fun loadBundledCatalog_shouldOnlyUseBundledSource() {
        val bundledSource = RecordingManifestSource(manifest("bundled"))
        val cachedSource = RecordingManifestSource(manifest("cached"))
        val remoteSource = RecordingManifestSource(manifest("remote"))
        val repository = LinuxDistroCatalogRepository(
            bundledSource = bundledSource,
            cachedSource = cachedSource,
            remoteSource = remoteSource,
        )

        val catalog = repository.loadBundledCatalog()

        assertThat(catalog.listDistros().single().id).isEqualTo("bundled")
        assertThat(bundledSource.loadCount).isEqualTo(1)
        assertThat(cachedSource.loadCount).isEqualTo(0)
        assertThat(remoteSource.loadCount).isEqualTo(0)
    }

    @Test
    fun loadCachedOrBundledCatalog_shouldNotUseRemoteSource() {
        val bundledSource = RecordingManifestSource(manifest("bundled"))
        val cachedSource = RecordingManifestSource(manifest("cached"))
        val remoteSource = RecordingManifestSource(manifest("remote"))
        val repository = LinuxDistroCatalogRepository(
            bundledSource = bundledSource,
            cachedSource = cachedSource,
            remoteSource = remoteSource,
        )

        val catalog = repository.loadCachedOrBundledCatalog()

        assertThat(catalog.listDistros().single().id).isEqualTo("cached")
        assertThat(bundledSource.loadCount).isEqualTo(0)
        assertThat(cachedSource.loadCount).isEqualTo(1)
        assertThat(remoteSource.loadCount).isEqualTo(0)
    }

    @Test
    fun loadRemoteOrCachedCatalog_shouldUseRemoteSource() {
        val bundledSource = RecordingManifestSource(manifest("bundled"))
        val cachedSource = RecordingManifestSource(manifest("cached"))
        val remoteSource = RecordingManifestSource(manifest("remote"))
        val repository = LinuxDistroCatalogRepository(
            bundledSource = bundledSource,
            cachedSource = cachedSource,
            remoteSource = remoteSource,
        )

        val catalog = repository.loadRemoteOrCachedCatalog()

        assertThat(catalog.listDistros().single().id).isEqualTo("remote")
        assertThat(bundledSource.loadCount).isEqualTo(0)
        assertThat(cachedSource.loadCount).isEqualTo(0)
        assertThat(remoteSource.loadCount).isEqualTo(1)
    }

    @Test
    fun cachedManifestSource_shouldLoadCacheWithoutFallback() {
        val cacheFile = createTempDirectory("linux-distro-cache").resolve("manifest.cache.json").toFile()
        cacheFile.writeText(LinuxDistroManifestParser.encode(manifest("cached")), Charsets.UTF_8)
        val fallbackSource = RecordingManifestSource(manifest("bundled"))
        val source = CachedLinuxDistroManifestSource(
            cacheFile = cacheFile,
            fallback = fallbackSource,
        )

        val loaded = source.loadManifest()

        assertThat(loaded.distros.single().id).isEqualTo("cached")
        assertThat(fallbackSource.loadCount).isEqualTo(0)
    }

    @Test
    fun cachedManifestSource_shouldFallbackWhenCacheIsMissingOrBroken() {
        val cacheFile = createTempDirectory("linux-distro-cache").resolve("manifest.cache.json").toFile()
        cacheFile.writeText("{ broken json", Charsets.UTF_8)
        val fallbackSource = RecordingManifestSource(manifest("bundled"))
        val source = CachedLinuxDistroManifestSource(
            cacheFile = cacheFile,
            fallback = fallbackSource,
        )

        val loaded = source.loadManifest()

        assertThat(loaded.distros.single().id).isEqualTo("bundled")
        assertThat(fallbackSource.loadCount).isEqualTo(1)
    }

    @Test
    fun remoteManifestSource_shouldAtomicallyReplaceCacheWithValidatedManifest() {
        val cacheDirectory = createTempDirectory("remote-linux-distro-cache").toFile()
        val cacheFile = cacheDirectory.resolve("manifest.cache.json")
        val remoteManifest = manifest("remote")
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            LinuxDistroManifestParser.encode(remoteManifest)
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            )
            .build()
        val fallbackSource = RecordingManifestSource(manifest("bundled"))
        val source = RemoteLinuxDistroManifestSource(
            context = mockk(relaxed = true),
            fallback = fallbackSource,
            client = client,
            cacheFile = cacheFile,
            manifestUrls = listOf("https://registry.test/linux-distros.json"),
        )

        val loaded = source.loadManifest()

        assertThat(loaded.distros.single().id).isEqualTo("remote")
        assertThat(LinuxDistroManifestParser.decode(cacheFile.readText(Charsets.UTF_8)))
            .isEqualTo(remoteManifest)
        assertThat(cacheDirectory.listFiles().orEmpty().map { it.name })
            .containsExactly(cacheFile.name)
        assertThat(fallbackSource.loadCount).isEqualTo(0)
    }

    private class RecordingManifestSource(
        private val manifest: LinuxDistroManifest,
    ) : LinuxDistroManifestSource {
        var loadCount: Int = 0
            private set

        override fun loadManifest(): LinuxDistroManifest {
            loadCount += 1
            return manifest
        }
    }

    private fun manifest(id: String): LinuxDistroManifest = LinuxDistroManifest(
        schemaVersion = LinuxDistroManifest.CURRENT_SCHEMA_VERSION,
        distros = listOf(distro(id)),
    )

    private fun distro(id: String): DistroDefinition = DistroDefinition(
        id = id,
        family = DistroFamily.ALPINE,
        displayName = id.replaceFirstChar { char -> char.uppercase() },
        packageManager = DistroPackageManager.APK,
        defaultReleaseId = "3.20",
        releases = listOf(
            DistroRelease(
                id = "3.20",
                version = "3.20",
                displayName = "Alpine 3.20",
                artifacts = listOf(
                    DistroArtifact(
                        architecture = DistroArchitecture.AARCH64,
                        url = "https://example.test/$id/rootfs.tar.gz",
                        format = DistroArchiveFormat.TAR_GZ,
                        checksum = DistroChecksum(DistroChecksumAlgorithm.SHA256, "ab".repeat(32)),
                    )
                ),
            )
        ),
    )
}
