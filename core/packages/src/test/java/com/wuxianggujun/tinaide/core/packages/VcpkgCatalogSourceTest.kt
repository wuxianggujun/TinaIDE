package com.wuxianggujun.tinaide.core.packages

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class VcpkgCatalogSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val revision = "a".repeat(40)
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    private fun client(reply: (String) -> Pair<Int, ByteArray>) = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        requests += url
        val (status, bytes) = reply(url)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(bytes.toResponseBody()).build()
    }.build()

    @Test fun changingMirrorAffectsNextOperationWithoutRecreatingSource() = runTest {
        var mirror = "https://first.example/"
        val http = client { 200 to """{"object":{"sha":"$revision"}}""".toByteArray() }
        val source = VcpkgCatalogSource.github({ http }, { mirror })
        assertThat(source.latestRevision()).isEqualTo(revision)
        mirror = "https://second.example/"
        assertThat(source.latestRevision()).isEqualTo(revision)
        assertThat(requests.map { it.substringBefore("/https:") })
            .containsExactly("https://first.example", "https://second.example").inOrder()
    }

    @Test fun invalidMirrorResponseFallsBackToOfficialRevision() = runTest {
        val http = client { url ->
            200 to (if (url.startsWith("https://mirror.example/")) """{"object":{"sha":"../invalid"}}"""
                else """{"object":{"sha":"$revision"}}""").toByteArray()
        }
        val source = VcpkgCatalogSource.github({ http }, { "https://mirror.example/" })
        assertThat(source.latestRevision()).isEqualTo(revision)
        assertThat(requests).hasSize(2)
        assertThat(requests.last()).startsWith("https://api.github.com/")
    }

    @Test fun htmlArchiveResponseFallsBackBeforePublishingFile() = runTest {
        val archive = javaClass.getResourceAsStream("/vcpkg/valid.tar.gz")!!.use { it.readBytes() }
        val http = client { url -> 200 to if (url.startsWith("https://mirror.example/")) "<html>unavailable</html>".toByteArray() else archive }
        val source = VcpkgCatalogSource.github({ http }, { "https://mirror.example/" })
        val target = temporary.newFile()
        source.download(revision, target) { _, _ -> }
        assertThat(target.readBytes()).isEqualTo(archive)
        assertThat(requests).hasSize(2)
        assertThat(requests.first()).startsWith("https://mirror.example/https://github.com/")
    }

    @Test fun failedEndpointsDoNotReplaceExistingCatalog() = runTest {
        val root = temporary.newFolder()
        val seed = VcpkgCatalogStore.encode(VcpkgCatalogSnapshot(revision, "0".repeat(64),
            listOf(VcpkgPort("fmt", "1", "", "")))).toByteArray()
        val http = client { 503 to "unavailable".toByteArray() }
        val store = VcpkgCatalogStore(root, { seed.inputStream() }, VcpkgCatalogSource.github({ http }, { "https://mirror.example/" }))
        val result = runCatching { store.update() }
        assertThat(result.exceptionOrNull()).isInstanceOf(VcpkgException::class.java)
        assertThat(store.readLocal().ports.single().name).isEqualTo("fmt")
        assertThat(requests).hasSize(2 + com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig.PUBLIC_GITHUB_PROXY_PREFIXES.size)
    }

    @Test fun truncatedGzipMirrorFallsBackBeforePublishingArchiveOrCatalog() = runTest {
        val nextRevision = "b".repeat(40)
        val archive = javaClass.getResourceAsStream("/vcpkg/valid.tar.gz")!!.use { it.readBytes() }
        val http = client { url ->
            when {
                url.contains("/git/ref/") -> 200 to """{"object":{"sha":"$nextRevision"}}""".toByteArray(Charsets.UTF_8)
                url.startsWith("https://mirror.example/") -> 200 to archive.copyOf(12)
                else -> 200 to archive
            }
        }
        val root = temporary.newFolder()
        val seed = VcpkgCatalogStore.encode(VcpkgCatalogSnapshot(revision, "0".repeat(64),
            listOf(VcpkgPort("fmt", "1", "", "")))).toByteArray(Charsets.UTF_8)
        val store = VcpkgCatalogStore(root, { seed.inputStream() },
            VcpkgCatalogSource.github({ http }, { "https://mirror.example/" }))

        assertThat(store.update().ports.single().version).isEqualTo("2")

        assertThat(store.readLocal().revision).isEqualTo(nextRevision)
        assertThat(File(root, "archives/$nextRevision.tar.gz").readBytes()).isEqualTo(archive)
        assertThat(requests.filter { it.endsWith(".tar.gz") }).containsExactly(
            "https://mirror.example/https://github.com/microsoft/vcpkg/archive/$nextRevision.tar.gz",
            "https://github.com/microsoft/vcpkg/archive/$nextRevision.tar.gz",
        ).inOrder()
        assertThat(root.listFiles()!!.none { it.name.startsWith(".catalog-") }).isTrue()
    }

    @Test fun checksumMismatchAtMirrorFallsBackToTrustedSourceArchive() = runTest {
        val sourceRevision = "b".repeat(40)
        val archive = javaClass.getResourceAsStream("/vcpkg/valid.tar.gz")!!.use { it.readBytes() }
        val checksum = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val http = client { url ->
            200 to if (url.startsWith("https://mirror.example/")) archive + byteArrayOf(0) else archive
        }
        val root = temporary.newFolder()
        val seed = VcpkgCatalogStore.encode(VcpkgCatalogSnapshot(sourceRevision, checksum,
            listOf(VcpkgPort("fmt", "2", "", "")))).toByteArray(Charsets.UTF_8)
        val store = VcpkgCatalogStore(root, { seed.inputStream() },
            VcpkgCatalogSource.github({ http }, { "https://mirror.example/" }))

        val sources = store.ensureSources()

        assertThat(File(sources, "scripts/buildsystems/vcpkg.cmake").isFile).isTrue()
        assertThat(File(root, "archives/$sourceRevision.tar.gz").readBytes()).isEqualTo(archive)
        assertThat(requests).containsExactly(
            "https://mirror.example/https://github.com/microsoft/vcpkg/archive/$sourceRevision.tar.gz",
            "https://github.com/microsoft/vcpkg/archive/$sourceRevision.tar.gz",
        ).inOrder()
        assertThat(root.listFiles()!!.none { it.name.startsWith(".sources-") }).isTrue()
    }

    @Test fun traversalArchiveAtMirrorFallsBackWithoutWritingOutsideManagedDirectory() = runTest {
        val nextRevision = "b".repeat(40)
        val valid = javaClass.getResourceAsStream("/vcpkg/valid.tar.gz")!!.use { it.readBytes() }
        val traversal = javaClass.getResourceAsStream("/vcpkg/traversal.tar.gz")!!.use { it.readBytes() }
        val http = client { url ->
            when {
                url.contains("/git/ref/") -> 200 to """{"object":{"sha":"$nextRevision"}}""".toByteArray(Charsets.UTF_8)
                url.startsWith("https://mirror.example/") -> 200 to traversal
                else -> 200 to valid
            }
        }
        val root = temporary.newFolder()
        val seed = VcpkgCatalogStore.encode(VcpkgCatalogSnapshot(revision, "0".repeat(64),
            listOf(VcpkgPort("fmt", "1", "", "")))).toByteArray(Charsets.UTF_8)
        val store = VcpkgCatalogStore(root, { seed.inputStream() },
            VcpkgCatalogSource.github({ http }, { "https://mirror.example/" }))

        assertThat(store.update().revision).isEqualTo(nextRevision)

        assertThat(File(root.parentFile, "escaped").exists()).isFalse()
        assertThat(File(root, "sources").exists()).isFalse()
        assertThat(File(root, "archives/$nextRevision.tar.gz").readBytes()).isEqualTo(valid)
        assertThat(requests.filter { it.endsWith(".tar.gz") }).hasSize(2)
    }

    @Test fun everyWrongChecksumPreservesTrustedCatalogAndDoesNotPublishSources() = runTest {
        val sourceRevision = "b".repeat(40)
        val archive = javaClass.getResourceAsStream("/vcpkg/valid.tar.gz")!!.use { it.readBytes() }
        val checksum = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val http = client { 200 to archive + byteArrayOf(0) }
        val root = temporary.newFolder()
        val seed = VcpkgCatalogStore.encode(VcpkgCatalogSnapshot(sourceRevision, checksum,
            listOf(VcpkgPort("fmt", "2", "", "")))).toByteArray(Charsets.UTF_8)
        val store = VcpkgCatalogStore(root, { seed.inputStream() },
            VcpkgCatalogSource.github({ http }, { "https://mirror.example/" }))

        assertThat(runCatching { store.ensureSources() }.exceptionOrNull()).isInstanceOf(VcpkgException::class.java)

        assertThat(store.readLocal().archiveSha256).isEqualTo(checksum)
        assertThat(File(root, "archives/$sourceRevision.tar.gz").exists()).isFalse()
        assertThat(File(root, "sources/$sourceRevision").exists()).isFalse()
        assertThat(root.listFiles()!!.none { it.name.startsWith(".sources-") }).isTrue()
        assertThat(requests).hasSize(2 + GitHubRegistryConfig.PUBLIC_GITHUB_PROXY_PREFIXES.size)
    }

    @Test fun cancelledDownloadDoesNotTryAnotherEndpointOrPublishArchive() = runTest {
        val nextRevision = "b".repeat(40)
        val archive = javaClass.getResourceAsStream("/vcpkg/valid.tar.gz")!!.use { it.readBytes() }
        val http = client { url ->
            200 to if (url.contains("/git/ref/")) """{"object":{"sha":"$nextRevision"}}""".toByteArray(Charsets.UTF_8) else archive
        }
        val root = temporary.newFolder()
        val seed = VcpkgCatalogStore.encode(VcpkgCatalogSnapshot(revision, "0".repeat(64),
            listOf(VcpkgPort("fmt", "1", "", "")))).toByteArray(Charsets.UTF_8)
        val store = VcpkgCatalogStore(root, { seed.inputStream() },
            VcpkgCatalogSource.github({ http }, { "https://mirror.example/" }))

        val result = runCatching { store.update { _, _ -> throw CancellationException("cancelled") } }

        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(store.readLocal().revision).isEqualTo(revision)
        assertThat(File(root, "catalog.json").exists()).isFalse()
        assertThat(File(root, "archives/$nextRevision.tar.gz").exists()).isFalse()
        assertThat(root.listFiles()!!.none { it.name.startsWith(".catalog-") }).isTrue()
        assertThat(requests).hasSize(2)
    }
}
