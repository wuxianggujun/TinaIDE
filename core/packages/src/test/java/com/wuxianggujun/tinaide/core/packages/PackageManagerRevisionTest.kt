package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.cache.PackageCacheManager
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformPackage
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageManagerRevisionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `same upstream version with a new revision updates state and cached detail`() = runTest {
        writeInstalledRevision(2)
        val api = mockk<PackageApiClient>(relaxed = true)
        val cache = mockk<PackageCacheManager>(relaxed = true)
        val store = mockk<LocalInstallStateStore>(relaxed = true)
        val latest = raylib(3)
        every { store.getAllInstalledPackages() } returns listOf(
            InstalledPackageInfo("raylib", "raylib", Platform.ANDROID, "6.0", 0L, InstallType.DOWNLOAD),
        )
        coEvery { api.getPackageDetail("raylib") } returns ApiResult.Success(latest)
        val manager = PackageManagerImpl(context(), api, store, cache, ioDispatcher = StandardTestDispatcher(testScheduler))

        val updates = manager.checkForUpdates()

        assertThat(updates.keys).containsExactly("raylib")
        coVerify(exactly = 1) { api.invalidateCache() }
        verify { cache.savePackageDetail(latest) }
        verify { store.setUpdateAvailable("raylib", Platform.ANDROID, "6.0", "6.0") }
        writeInstalledRevision(3)
        assertThat(manager.checkForUpdates()).isEmpty()
    }

    @Test
    fun `old installed raylib does not satisfy dependency planning`() = runTest {
        writeInstalledRevision(2)
        val api = mockk<PackageApiClient>(relaxed = true)
        val cache = mockk<PackageCacheManager>(relaxed = true)
        val store = mockk<LocalInstallStateStore>(relaxed = true)
        every { cache.getPackageDetail(any()) } returns null
        every { store.isInstalled(any(), Platform.ANDROID) } answers { firstArg<String>() == "raylib" }
        coEvery { api.getPackageDetail("raylib") } returns ApiResult.Success(raylib(3))
        coEvery { api.getPackageDetail("demo") } returns ApiResult.Success(
            GUIPackage("demo", "Demo", android = PlatformPackage(
                version = "1.0", installType = InstallType.DOWNLOAD, dependencies = listOf("raylib"),
            )),
        )
        val manager = PackageManagerImpl(context(), api, store, cache, ioDispatcher = StandardTestDispatcher(testScheduler))

        val oldPlan = manager.previewInstallPlan("demo", Platform.ANDROID).getOrThrow()
        assertThat(oldPlan.packages.map { it.packageId }).containsExactly("raylib", "demo").inOrder()
        assertThat(oldPlan.packages.first().isAlreadyInstalled).isFalse()
        writeInstalledRevision(3)
        val newPlan = manager.previewInstallPlan("demo", Platform.ANDROID).getOrThrow()
        assertThat(newPlan.packages.map { it.packageId }).containsExactly("demo")
    }

    @Test
    fun `market refresh invalidates both live and disk metadata caches`() = runTest {
        val api = mockk<PackageApiClient>(relaxed = true)
        val cache = mockk<PackageCacheManager>(relaxed = true)
        val manager = PackageManagerImpl(context(), api, mockk(relaxed = true), cache, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.refreshCache()
        coVerify(exactly = 1) { api.invalidateCache() }
        verify(exactly = 1) { cache.clearCache() }
    }

    private fun context(): Context = mockk<Context>(relaxed = true).also {
        every { it.filesDir } returns temporary.root
    }

    private fun raylib(revision: Int) = GUIPackage("raylib", "raylib", android = PlatformPackage(
        version = "6.0", packageRevision = revision, installType = InstallType.DOWNLOAD, dependencies = emptyList(),
    ))

    private fun writeInstalledRevision(revision: Int) {
        val file = File(temporary.root, "installed-packages/raylib/package.json")
        file.parentFile!!.mkdirs()
        file.writeText("""{"id":"raylib","name":"raylib","version":"6.0","packageRevision":$revision}""", Charsets.UTF_8)
    }
}
