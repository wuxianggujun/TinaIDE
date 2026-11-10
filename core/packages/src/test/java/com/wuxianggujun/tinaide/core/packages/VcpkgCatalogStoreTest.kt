package com.wuxianggujun.tinaide.core.packages

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class VcpkgCatalogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldRevision = "a".repeat(40)
    private val newRevision = "b".repeat(40)
    private val seed = VcpkgCatalogStore.encode(VcpkgCatalogSnapshot(oldRevision, "0".repeat(64),
        listOf(VcpkgPort("fmt", "1", "Format library", "")))).toByteArray(Charsets.UTF_8)

    private class Source(var revision: String) : VcpkgCatalogSource {
        var calls = 0
        var failure: Exception? = null
        var archive = "valid.tar.gz"
        override suspend fun latestRevision(): String { calls++; return revision }
        override suspend fun download(revision: String, target: File, progress: (Long, Long) -> Unit) {
            calls++
            failure?.let { throw it }
            javaClass.getResourceAsStream("/vcpkg/$archive")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    @Test fun firstOpenListsBundledPortsWithoutProjectOrNetwork() = runTest {
        val source = Source(newRevision)
        val store = VcpkgCatalogStore(temporary.newFolder(), { ByteArrayInputStream(seed) }, source)
        assertThat(store.readLocal().ports.single().name).isEqualTo("fmt")
        assertThat(source.calls).isEqualTo(0)
    }

    @Test fun updatePublishesMetadataAndReusesArchiveForDeferredSourcePreparation() = runTest {
        val root = temporary.newFolder()
        val source = Source(newRevision)
        val store = VcpkgCatalogStore(root, { ByteArrayInputStream(seed) }, source)
        assertThat(store.update().ports.single().version).isEqualTo("2")
        assertThat(store.readLocal().revision).isEqualTo(newRevision)
        assertThat(File(root, "sources").exists()).isFalse()
        assertThat(store.ensureSources()).isEqualTo(File(root, "sources/$newRevision"))
        assertThat(source.calls).isEqualTo(2)
        store.update()
        assertThat(source.calls).isEqualTo(3) // Only check the revision; do not redownload.
        assertThat(root.listFiles()!!.none { it.name.startsWith(".catalog-") }).isTrue()
    }

    @Test fun nextUpdateCleansAbandonedStagingWithoutDroppingTheCatalog() = runTest {
        val root = temporary.newFolder()
        val abandoned = File(root, ".catalog-interrupted/source/ports/fmt").apply { mkdirs() }
        File(abandoned, "partial").writeText("interrupted")
        val store = VcpkgCatalogStore(root, { ByteArrayInputStream(seed) }, Source(newRevision))
        store.update()
        assertThat(File(root, ".catalog-interrupted").exists()).isFalse()
        assertThat(store.readLocal().revision).isEqualTo(newRevision)
    }

    @Test fun failedOrCancelledUpdatePreservesPreviousCatalogAndRemovesStaging() = runTest {
        val root = temporary.newFolder()
        val source = Source(newRevision)
        val store = VcpkgCatalogStore(root, { ByteArrayInputStream(seed) }, source)
        store.update()
        val before = File(root, "catalog.json").readBytes()
        for (failure in listOf(java.io.IOException("offline"), CancellationException("cancelled"))) {
            source.revision = "c".repeat(40)
            source.failure = failure
            assertThat(runCatching { store.update() }.exceptionOrNull()).isInstanceOf(failure.javaClass)
            assertThat(File(root, "catalog.json").readBytes()).isEqualTo(before)
            assertThat(root.listFiles()!!.none { it.name.startsWith(".catalog-") }).isTrue()
        }
    }

    @Test fun invalidArchiveCannotPublishOrEscapeManagedDirectory() = runTest {
        val root = temporary.newFolder()
        val source = Source(newRevision).apply { archive = "traversal.tar.gz" }
        val store = VcpkgCatalogStore(root, { ByteArrayInputStream(seed) }, source)
        assertThat(runCatching { store.update() }.isFailure).isTrue()
        assertThat(store.readLocal().revision).isEqualTo(oldRevision)
        assertThat(File(root, "catalog.json").exists()).isFalse()
        assertThat(File(root.parentFile, "escaped").exists()).isFalse()
        assertThat(root.listFiles()!!.none { it.name.startsWith(".catalog-") }).isTrue()
    }

    @Test fun corruptCacheFallsBackToBundledCatalog() = runTest {
        val root = temporary.newFolder()
        File(root, "catalog.json").writeBytes(byteArrayOf(0x80.toByte()))
        val store = VcpkgCatalogStore(root, { ByteArrayInputStream(seed) }, Source(newRevision))
        assertThat(store.readLocal().ports.single().name).isEqualTo("fmt")
    }
}
