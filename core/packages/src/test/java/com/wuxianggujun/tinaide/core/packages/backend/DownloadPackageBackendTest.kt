package com.wuxianggujun.tinaide.core.packages.backend

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.api.PackageVersionsResponse
import com.wuxianggujun.tinaide.core.packages.PackageManagerImpl
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadataReader
import com.wuxianggujun.tinaide.core.packages.BundledPackagesInstaller
import com.wuxianggujun.tinaide.core.packages.cache.PackageCacheManager
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.packages.model.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real archive download, extraction and publication; only HTTP and registry responses are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DownloadPackageBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val api = mockk<PackageApiClient>()
    private lateinit var files: File
    private lateinit var cache: File

    @Before fun setup() {
        AppStrings.initialize(RuntimeEnvironment.getApplication())
        files = temporary.newFolder("files")
        cache = temporary.newFolder("cache")
        every { context.filesDir } returns files
        every { context.cacheDir } returns cache
        every { context.applicationContext } returns context
        every { context.applicationInfo } returns ApplicationInfo().apply { nativeLibraryDir = "/lib/x86_64" }
        every { context.getSharedPreferences(any(), any()) } answers {
            RuntimeEnvironment.getApplication().getSharedPreferences(firstArg(), secondArg())
        }
        mockkObject(GitHubRegistryHttpClientFactory)
    }

    @After fun teardown() { unmockkObject(GitHubRegistryHttpClientFactory) }

    @Test fun cacheClearFromAnotherBackendWaitsUntilInstalledArchiveIsReleased() = runBlocking {
        val installer = backend(archive())
        val otherPage = DownloadPackageBackend(context, api)
        val archiveAvailable = CompletableDeferred<Unit>()
        val releaseArchive = CountDownLatch(1)
        val installation = async(Dispatchers.IO) {
            installer.install("demo", 1, "2.0") { event ->
                if (event is InstallProgressEvent.Verifying) {
                    archiveAvailable.complete(Unit)
                    check(releaseArchive.await(5, TimeUnit.SECONDS))
                }
            }
        }
        try {
            withTimeout(3_000) { archiveAvailable.await() }
            val clearing = async(Dispatchers.IO) { otherPage.clearDownloadCache(); true }
            assertThat(withTimeoutOrNull(300) { clearing.await() }).isNull()
            releaseArchive.countDown()
            assertThat(withTimeout(3_000) { installation.await() }).isInstanceOf(InstallResult.Success::class.java)
            withTimeout(3_000) { clearing.await() }
            assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("new payload")
            assertNoTransactionsOrArchives()
        } finally {
            releaseArchive.countDown()
            installation.await()
        }
    }

    @Test fun wrongMetadataPreservesExistingInstallation() = runBlocking {
        seedExistingInstallation()
        val installer = backend(archive(version = "unexpected"))
        val result = installer.install("demo", 1, "2.0") {}
        assertThat(result).isInstanceOf(InstallResult.Failure::class.java)
        assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("old payload")
        assertNoTransactionsOrArchives()
    }

    @Test fun cancelledExtractionPreservesExistingInstallationAndCleansArchive() = runBlocking {
        seedExistingInstallation()
        val installer = backend(archive(extraFiles = 12))
        var cancelled = false
        try {
            installer.install("demo", 1, "2.0") { event ->
                if (event is InstallProgressEvent.Extracting) throw CancellationException("User cancelled extraction")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertThat(cancelled).isTrue()
        assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("old payload")
        assertNoTransactionsOrArchives()
    }

    @Test fun cancellationAfterDownloadBeforeExtractionAlsoCleansTheVerifiedArchive() = runBlocking {
        seedExistingInstallation()
        val installer = backend(archive())
        var cancelled = false
        try {
            installer.install("demo", 1, "2.0") { event ->
                if (event is InstallProgressEvent.Verifying) throw CancellationException("Cancel before extraction")
            }
        } catch (_: CancellationException) { cancelled = true }
        assertThat(cancelled).isTrue()
        assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("old payload")
        assertNoTransactionsOrArchives()
    }

    @Test fun oversizedMetadataCannotReplaceAnInstalledPackage() = runBlocking {
        seedExistingInstallation()
        val installer = backend(archive(metadataPadding = InstalledPackageMetadataReader.MAX_METADATA_BYTES))
        val result = installer.install("demo", 1, "2.0") {}
        assertThat(result).isInstanceOf(InstallResult.Failure::class.java)
        assertThat((result as InstallResult.Failure).error)
            .isEqualTo(InstallError.ExtractionFailed(Strings.pkg_manager_error_invalid_metadata.str()))
        assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("old payload")
        assertNoTransactionsOrArchives()
    }

    @Test fun installCompletesOnceAfterRootAndDependenciesAreRecorded() = runBlocking<Unit> {
        val packages = listOf("dependency", "demo")
        val payloads = packages.associateWith { archive(packageId = it) }
        packages.forEach { packageId ->
            val dependencies = if (packageId == "demo") listOf("dependency") else emptyList()
            coEvery { api.getPackageDetail(packageId) } returns ApiResult.Success(GUIPackage(packageId, packageId,
                android = PlatformPackage(version = "2.0", installType = InstallType.DOWNLOAD, dependencies = dependencies)))
            coEvery { api.getPackageVersions(packageId) } returns ApiResult.Success(PackageVersionsResponse(android = listOf(
                PackageVersion(1, packageId, Platform.ANDROID, "2.0", installType = InstallType.DOWNLOAD, isLatest = true))))
            coEvery { api.getDownloadInfo(packageId, 1) } returns ApiResult.Success(downloadInfo(packageId, payloads.getValue(packageId)))
        }
        every { GitHubRegistryHttpClientFactory.download(context) } returns OkHttpClient.Builder().addInterceptor { chain ->
            val payload = payloads.getValue(chain.request().url.encodedPath.substringAfterLast('/').removeSuffix(".zip"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody()).build()
        }.build()
        val store = LocalInstallStateStore(context).apply { clear() }
        val metadataCache = mockk<PackageCacheManager>(relaxed = true)
        every { metadataCache.getPackageDetail(any()) } returns null
        val manager = PackageManagerImpl(context, api, store, metadataCache)
        val completed = mutableListOf<InstallProgressEvent.Completed>()
        val result = manager.install("demo", Platform.ANDROID) { event ->
            if (event is InstallProgressEvent.Completed) {
                assertThat(packages.all { store.isInstalled(it, Platform.ANDROID) }).isTrue()
                completed += event
            }
        }
        assertThat(result).isInstanceOf(InstallResult.Success::class.java)
        assertThat(completed).containsExactly(InstallProgressEvent.Completed(result as InstallResult.Success))
    }

    @Test fun uninstallUsesTheInstalledBackendWhenRegistryChangesItsInstallType() = runBlocking<Unit> {
        seedExistingInstallation()
        val store = LocalInstallStateStore(context).apply {
            clear()
            setInstalled("demo", Platform.ANDROID, "1.0", installType = InstallType.DOWNLOAD)
        }
        coEvery { api.getPackageDetail("demo") } returns ApiResult.Success(GUIPackage("demo", "Demo",
            android = PlatformPackage(version = "2.0", installType = InstallType.SCRIPT)))

        val result = manager(store).uninstall("demo", Platform.ANDROID)

        assertThat(result).isInstanceOf(UninstallResult.Success::class.java)
        assertThat(File(files, "installed-packages/demo").exists()).isFalse()
        assertThat(store.isInstalled("demo", Platform.ANDROID)).isFalse()
        coVerify(exactly = 0) { api.getPackageDetail(any()) }
    }

    @Test fun missingDownloadedFilesStillAllowRemovingOnlyThatPlatformsStaleRecord() = runBlocking<Unit> {
        val store = LocalInstallStateStore(context).apply {
            clear()
            setInstalled("demo", Platform.ANDROID, "1.0", installType = InstallType.DOWNLOAD)
            setInstalled("demo", Platform.LINUX, "1.0", installType = InstallType.APT)
            setUpdateAvailable("demo", Platform.ANDROID, "1.0", "2.0")
        }
        val sibling = File(files, "installed-packages/other/include/header.h").apply {
            parentFile!!.mkdirs()
            writeText("keep", Charsets.UTF_8)
        }

        val result = manager(store).uninstall("demo", Platform.ANDROID)

        assertThat(result).isEqualTo(UninstallResult.Success("demo", Platform.ANDROID, 0))
        assertThat(store.getInstallState("demo").android).isEqualTo(PlatformInstallState.NotInstalled)
        assertThat(store.isInstalled("demo", Platform.LINUX)).isTrue()
        assertThat(sibling.readText()).isEqualTo("keep")
        coVerify(exactly = 0) { api.getPackageDetail(any()) }
    }

    @Test fun uninstallFromAnotherPageWaitsForThePublishedInstallToBeRecorded() = runBlocking<Unit> {
        seedExistingInstallation()
        val state = LocalInstallStateStore(context).apply {
            clear()
            setInstalled("demo", Platform.ANDROID, "1.0", installType = InstallType.DOWNLOAD)
        }
        val delayedState = spyk(state)
        val published = CompletableDeferred<Unit>()
        val allowRecord = CountDownLatch(1)
        every { delayedState.setInstalled(any(), any(), any(), any(), any(), any(), any()) } answers {
            published.complete(Unit)
            check(allowRecord.await(5, TimeUnit.SECONDS))
            callOriginal()
        }
        prepareManagerDownload()
        val installer = manager(delayedState)
        val otherPage = manager(LocalInstallStateStore(context))
        val installation = async(Dispatchers.IO) { installer.install("demo", Platform.ANDROID) {} }
        try {
            withTimeout(3_000) { published.await() }
            assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("new payload")
            val uninstallStarted = CompletableDeferred<Unit>()
            val uninstall = async(Dispatchers.IO) {
                uninstallStarted.complete(Unit)
                otherPage.uninstall("demo", Platform.ANDROID)
            }
            withTimeout(3_000) { uninstallStarted.await() }
            assertThat(withTimeoutOrNull(300) { uninstall.await() }).isNull()
            allowRecord.countDown()
            assertThat(withTimeout(3_000) { installation.await() }).isInstanceOf(InstallResult.Success::class.java)
            assertThat(withTimeout(3_000) { uninstall.await() }).isInstanceOf(UninstallResult.Success::class.java)
            assertThat(File(files, "installed-packages/demo").exists()).isFalse()
            assertThat(state.isInstalled("demo", Platform.ANDROID)).isFalse()
        } finally {
            allowRecord.countDown()
            installation.await()
        }
    }

    @Test fun cancellingQueuedUninstallDoesNotDeleteTheActiveInstallation() = runBlocking<Unit> {
        seedExistingInstallation()
        val store = LocalInstallStateStore(context).apply {
            clear()
            setInstalled("demo", Platform.ANDROID, "1.0", installType = InstallType.DOWNLOAD)
        }
        prepareManagerDownload()
        val archiveAvailable = CompletableDeferred<Unit>()
        val allowExtraction = CountDownLatch(1)
        val installer = manager(store)
        val otherPage = manager(LocalInstallStateStore(context))
        val installation = async(Dispatchers.IO) {
            installer.install("demo", Platform.ANDROID) { event ->
                if (event is InstallProgressEvent.Verifying) {
                    archiveAvailable.complete(Unit)
                    check(allowExtraction.await(5, TimeUnit.SECONDS))
                }
            }
        }
        try {
            withTimeout(3_000) { archiveAvailable.await() }
            val uninstallStarted = CompletableDeferred<Unit>()
            val uninstall = async(Dispatchers.IO) {
                uninstallStarted.complete(Unit)
                otherPage.uninstall("demo", Platform.ANDROID)
            }
            withTimeout(3_000) { uninstallStarted.await() }
            assertThat(withTimeoutOrNull(300) { uninstall.await() }).isNull()
            withTimeout(3_000) { uninstall.cancelAndJoin() }
            allowExtraction.countDown()
            assertThat(withTimeout(3_000) { installation.await() }).isInstanceOf(InstallResult.Success::class.java)
            assertThat(store.getInstalledVersion("demo", Platform.ANDROID)).isEqualTo("2.0")
            assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("new payload")
            assertNoTransactionsOrArchives()
        } finally {
            allowExtraction.countDown()
            installation.await()
        }
    }

    @Test fun bundledInitializationWaitsUntilTheActiveInstallRecordIsWritten() = runBlocking<Unit> {
        seedExistingInstallation()
        val store = LocalInstallStateStore(context).apply {
            clear()
            setInstalled("demo", Platform.ANDROID, "1.0", installType = InstallType.DOWNLOAD)
        }
        val delayedState = spyk(store)
        val published = CompletableDeferred<Unit>()
        val allowRecord = CountDownLatch(1)
        every { delayedState.setInstalled(any(), any(), any(), any(), any(), any(), any()) } answers {
            published.complete(Unit)
            check(allowRecord.await(5, TimeUnit.SECONDS))
            callOriginal()
        }
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        var versionAtBundledScan: String? = null
        every { assets.list("bundled_packages") } answers {
            versionAtBundledScan = store.getInstalledVersion("demo", Platform.ANDROID)
            emptyArray()
        }
        prepareManagerDownload()
        val installer = manager(delayedState)
        val installation = async(Dispatchers.IO) { installer.install("demo", Platform.ANDROID) {} }
        try {
            withTimeout(3_000) { published.await() }
            val bundledStarted = CompletableDeferred<Unit>()
            val bundled = async(Dispatchers.IO) {
                bundledStarted.complete(Unit)
                BundledPackagesInstaller(context, LocalInstallStateStore(context)).installBundledPackages()
                true
            }
            withTimeout(3_000) { bundledStarted.await() }
            assertThat(withTimeoutOrNull(300) { bundled.await() }).isNull()
            allowRecord.countDown()
            assertThat(withTimeout(3_000) { installation.await() }).isInstanceOf(InstallResult.Success::class.java)
            assertThat(withTimeout(3_000) { bundled.await() }).isTrue()
            assertThat(versionAtBundledScan).isEqualTo("2.0")
        } finally {
            allowRecord.countDown()
            installation.await()
        }
    }

    @Test fun cancelledInstallationReleasesMutationLockForTheNextUninstall() = runBlocking<Unit> {
        seedExistingInstallation()
        val store = LocalInstallStateStore(context).apply {
            clear()
            setInstalled("demo", Platform.ANDROID, "1.0", installType = InstallType.DOWNLOAD)
        }
        prepareManagerDownload()
        var cancelled = false
        try {
            manager(store).install("demo", Platform.ANDROID) { event ->
                if (event is InstallProgressEvent.Verifying) throw CancellationException("Cancel before extraction")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertThat(cancelled).isTrue()
        assertThat(store.getInstalledVersion("demo", Platform.ANDROID)).isEqualTo("1.0")
        assertThat(File(files, "installed-packages/demo/include/demo.h").readText()).isEqualTo("old payload")
        assertNoTransactionsOrArchives()
        val result = withTimeout(3_000) { manager(LocalInstallStateStore(context)).uninstall("demo", Platform.ANDROID) }
        assertThat(result).isInstanceOf(UninstallResult.Success::class.java)
        assertThat(store.isInstalled("demo", Platform.ANDROID)).isFalse()
    }

    private fun prepareManagerDownload() {
        backend(archive())
        coEvery { api.getPackageDetail("demo") } returns ApiResult.Success(GUIPackage("demo", "Demo",
            android = PlatformPackage(version = "2.0", installType = InstallType.DOWNLOAD, dependencies = emptyList())))
        coEvery { api.getPackageVersions("demo") } returns ApiResult.Success(PackageVersionsResponse(android = listOf(
            PackageVersion(1, "demo", Platform.ANDROID, "2.0", installType = InstallType.DOWNLOAD, isLatest = true))))
    }

    private fun manager(store: LocalInstallStateStore): PackageManagerImpl {
        val metadataCache = mockk<PackageCacheManager>(relaxed = true)
        every { metadataCache.getPackageDetail(any()) } returns null
        return PackageManagerImpl(context, api, store, metadataCache)
    }

    private fun seedExistingInstallation() {
        File(files, "installed-packages/demo/include/demo.h").apply {
            parentFile!!.mkdirs()
            writeText("old payload", Charsets.UTF_8)
        }
    }

    private fun assertNoTransactionsOrArchives() {
        assertThat(File(cache, "package_downloads").listFiles().orEmpty()).isEmpty()
        assertThat(File(files, "installed-packages").listFiles().orEmpty().filter { it.name.startsWith(".download-") }).isEmpty()
    }

    private fun backend(payload: ByteArray): DownloadPackageBackend {
        coEvery { api.getDownloadInfo("demo", 1) } returns ApiResult.Success(downloadInfo("demo", payload))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody()).build()
        }.build()
        every { GitHubRegistryHttpClientFactory.download(context) } returns client
        return DownloadPackageBackend(context, api)
    }

    private fun downloadInfo(packageId: String, payload: ByteArray): DownloadInfo {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        return DownloadInfo(packageId, "2.0", Platform.ANDROID, InstallType.DOWNLOAD,
            sources = listOf(DownloadSource(1, "fixture", "https://registry.test/$packageId.zip", priority = 1,
                abi = "x86_64", size = payload.size.toLong(), checksum = "sha256:$digest")))
    }

    private fun archive(version: String = "2.0", extraFiles: Int = 0, packageId: String = "demo", metadataPadding: Int = 0): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            // Test the metadata bound itself, not the archive compression-ratio guard.
            if (metadataPadding > 0) zip.setLevel(0)
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("package.json", """{"id":"$packageId","name":"Demo","version":"$version","abis":["x86_64"]}""" + " ".repeat(metadataPadding))
            entry("include/demo.h", "new payload")
            repeat(extraFiles) { entry("include/extra$it.h", "header $it") }
        }
        bytes.toByteArray()
    }
}
