package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlanItem
import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.PackagePlatformKey
import com.wuxianggujun.tinaide.core.packages.UpdateInfo
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.InstallProgressEvent
import com.wuxianggujun.tinaide.core.packages.model.InstallResult
import com.wuxianggujun.tinaide.core.packages.model.InstallError
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.PackageCategory
import com.wuxianggujun.tinaide.core.packages.model.PackageInstallState
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformPackage
import com.wuxianggujun.tinaide.core.packages.model.UninstallResult
import com.wuxianggujun.tinaide.core.packages.model.PlatformInstallState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class PackageManagerViewModelTest {
    @Test
    fun `catalog search finds the last entry beyond multiple pages`() = runTest {
        val manager = FakePackageManager((1..151).map { guiPackage("package-$it") })
        val viewModel = PackageManagerViewModel(testStringContext(), manager)
        viewModel.updateSearchQuery("package-151")
        assertThat(viewModel.uiState.value.filteredPackages.map { it.id }).containsExactly("package-151")
        assertThat(manager.catalogRequests).isEqualTo(2)
    }

    @Test
    fun `batch failure reports actual counts and keeps failed updates for retry`() = runTest {
        val keys = listOf("good", "bad").map { PackagePlatformKey(it, Platform.ANDROID) }
        val updates = keys.associateWith { UpdateInfo(it.packageId, it.packageId, it.platform, "1.0", "2.0") }
        val manager = FakePackageManager(keys.map { guiPackage(it.packageId) }, updates = updates, failingPackages = setOf("bad"))
        val viewModel = PackageManagerViewModel(testStringContext(), manager)
        viewModel.checkForUpdates()
        viewModel.updateAllPackages()
        val result = (viewModel.dialogState.value as PackageDialogState.BatchComplete).result
        assertThat(result.succeeded).isEqualTo(1)
        assertThat(result.failures.map { it.key.packageId }).containsExactly("bad")
        assertThat(result.unfinished).isEqualTo(0)
        assertThat(viewModel.uiState.value.availableUpdates.keys).containsExactly(keys.last())
    }

    @Test
    fun `batch cancellation preserves successes and does not start remaining packages`() = runTest {
        val manager = FakePackageManager(listOf("a", "b", "c").map(::guiPackage), blockingPackage = "b")
        val viewModel = PackageManagerViewModel(testStringContext(), manager)
        viewModel.selectAll()
        viewModel.batchInstall(Platform.ANDROID)
        viewModel.cancelInstall()
        advanceUntilIdle()
        val result = (viewModel.dialogState.value as PackageDialogState.BatchComplete).result
        assertThat(result.cancelled).isTrue()
        assertThat(result.succeeded).isEqualTo(1)
        assertThat(result.unfinished).isEqualTo(2)
        assertThat(manager.installCalls.map { it.first }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `my packages does not request the remote catalog`() = runTest {
        val manager = FakePackageManager(availablePackages = listOf(guiPackage("not-installed")))
        val viewModel = PackageManagerViewModel(testStringContext(), manager, installedOnly = true)
        assertThat(manager.catalogRequests).isEqualTo(0)
        assertThat(viewModel.uiState.value.packages).isEmpty()
        viewModel.refreshPackages()
        assertThat(manager.catalogRequests).isEqualTo(0)
    }

    @Test
    fun `my packages keeps both installed platforms and removes the last uninstalled entry`() = runTest {
        val records = listOf(Platform.ANDROID, Platform.LINUX).map { platform ->
            InstalledPackageInfo("fmt", "fmt", platform, "11.0.0", 1L, InstallType.DOWNLOAD)
        }
        val manager = FakePackageManager(listOf(guiPackage("not-installed")), installedPackages = records)
        val viewModel = PackageManagerViewModel(testStringContext(), manager, installedOnly = true)

        assertThat(viewModel.uiState.value.filteredPackages.map { it.id }).containsExactly("fmt")
        assertThat(viewModel.uiState.value.filteredPackages.single().android).isNotNull()
        assertThat(viewModel.uiState.value.filteredPackages.single().linux).isNotNull()
        viewModel.updateSearchQuery("not-installed")
        assertThat(viewModel.uiState.value.filteredPackages).isEmpty()
        viewModel.updateSearchQuery("")
        viewModel.confirmUninstall("fmt", Platform.ANDROID)
        assertThat(viewModel.uiState.value.filteredPackages.map { it.id }).containsExactly("fmt")
        viewModel.confirmUninstall("fmt", Platform.LINUX)
        assertThat(viewModel.uiState.value.filteredPackages).isEmpty()
        assertThat(manager.catalogRequests).isEqualTo(0)
    }

    @get:Rule
    val mainDispatcherRule = PackageManagerMainDispatcherRule()

    @Test
    fun `batchInstall previews dependencies before installing selected packages`() = runTest {
        val packageManager = FakePackageManager(
            availablePackages = listOf(
                guiPackage("demo"),
                guiPackage("sdl3")
            ),
            previewPlans = mapOf(
                "demo" to installPlan("demo", dependencyId = "sdl3")
            )
        )
        val viewModel = PackageManagerViewModel(testStringContext(), packageManager)

        viewModel.toggleSelectionMode()
        viewModel.togglePackageSelection("demo")
        viewModel.batchInstall(Platform.ANDROID)

        val dialog = viewModel.dialogState.value as PackageDialogState.BatchInstallConfirm
        assertThat(dialog.packageIds).containsExactly("demo")
        assertThat(dialog.plans.single().packages.map { it.packageId })
            .containsExactly("sdl3", "demo")
            .inOrder()
        assertThat(packageManager.installCalls).isEmpty()

        viewModel.confirmBatchInstall()

        assertThat(packageManager.installCalls).containsExactly("demo" to Platform.ANDROID)
    }

    @Test
    fun `updateAllPackages previews dependencies before installing updates`() = runTest {
        val update = UpdateInfo(
            packageId = "demo",
            packageName = "Demo",
            platform = Platform.ANDROID,
            currentVersion = "1.0.0",
            newVersion = "1.1.0"
        )
        val packageManager = FakePackageManager(
            availablePackages = listOf(
                guiPackage("demo"),
                guiPackage("sdl3")
            ),
            updates = mapOf(PackagePlatformKey("demo", Platform.ANDROID) to update),
            previewPlans = mapOf(
                "demo" to installPlan("demo", dependencyId = "sdl3")
            )
        )
        val viewModel = PackageManagerViewModel(testStringContext(), packageManager)

        viewModel.checkForUpdates()
        viewModel.updateAllPackages()

        val dialog = viewModel.dialogState.value as PackageDialogState.BatchUpdateConfirm
        assertThat(dialog.updates).containsExactly(update)
        assertThat(dialog.plans.single().packages.map { it.packageId })
            .containsExactly("sdl3", "demo")
            .inOrder()
        assertThat(packageManager.installCalls).isEmpty()

        viewModel.confirmBatchUpdate()

        assertThat(packageManager.installCalls).containsExactly("demo" to Platform.ANDROID)
    }

    private fun guiPackage(packageId: String): GUIPackage = GUIPackage(
        id = packageId,
        name = packageId.replaceFirstChar { it.uppercase() },
        android = PlatformPackage(
            version = "1.0.0",
            installType = InstallType.DOWNLOAD,
            dependencies = emptyList()
        )
    )

    private fun installPlan(
        packageId: String,
        dependencyId: String
    ): PackageInstallPlan = PackageInstallPlan(
        packageId = packageId,
        packageName = packageId,
        platform = Platform.ANDROID,
        packages = listOf(
            PackageInstallPlanItem(
                packageId = dependencyId,
                packageName = dependencyId,
                version = "1.0.0",
                isRoot = false,
                isAlreadyInstalled = false
            ),
            PackageInstallPlanItem(
                packageId = packageId,
                packageName = packageId,
                version = "1.0.0",
                isRoot = true,
                isAlreadyInstalled = false
            )
        )
    )

    private fun testStringContext(): Context {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        every { context.getString(any<Int>()) } answers { "res:${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg()) } answers {
            "res:${firstArg<Int>()}"
        }
        return context
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PackageManagerMainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class FakePackageManager(
    private val availablePackages: List<GUIPackage>,
    private val updates: Map<PackagePlatformKey, UpdateInfo> = emptyMap(),
    private val previewPlans: Map<String, PackageInstallPlan> = emptyMap(),
    installedPackages: List<InstalledPackageInfo> = emptyList(),
    private val failingPackages: Set<String> = emptySet(),
    private val blockingPackage: String? = null,
) : PackageManager {
    private val installed = installedPackages.toMutableList()
    private val pendingUpdates = updates.toMutableMap()
    val installCalls = mutableListOf<Pair<String, Platform>>()
    var catalogRequests = 0

    override suspend fun getAvailablePackages(
        page: Int,
        pageSize: Int,
        category: String?,
        platform: Platform?,
        search: String?
    ): Result<List<GUIPackage>> {
        catalogRequests++
        return Result.success(availablePackages.drop((page - 1) * pageSize).take(pageSize))
    }

    override suspend fun getCategories(): Result<List<PackageCategory>> = Result.success(emptyList())

    override suspend fun getPackageDetail(packageId: String): Result<GUIPackage> = Result.success(availablePackages.first { it.id == packageId })

    override suspend fun getInstallState(packageId: String): PackageInstallState {
        fun state(platform: Platform): PlatformInstallState = pendingUpdates[PackagePlatformKey(packageId, platform)]?.let {
            PlatformInstallState.UpdateAvailable(it.currentVersion, it.newVersion)
        } ?: installed.firstOrNull { it.packageId == packageId && it.platform == platform }
            ?.let { PlatformInstallState.Installed(it.version, it.installedAt) } ?: PlatformInstallState.NotInstalled
        return PackageInstallState(android = state(Platform.ANDROID), linux = state(Platform.LINUX))
    }

    override suspend fun previewInstallPlan(
        packageId: String,
        platform: Platform
    ): Result<PackageInstallPlan> = Result.success(
        previewPlans[packageId] ?: PackageInstallPlan(
            packageId = packageId,
            packageName = packageId,
            platform = platform,
            packages = listOf(
                PackageInstallPlanItem(
                    packageId = packageId,
                    packageName = packageId,
                    version = "1.0.0",
                    isRoot = true,
                    isAlreadyInstalled = false
                )
            )
        )
    )

    override suspend fun install(
        packageId: String,
        platform: Platform,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        installCalls += packageId to platform
        if (packageId == blockingPackage) awaitCancellation()
        if (packageId in failingPackages) return InstallResult.Failure(packageId, InstallError.NetworkError("offline"))
        pendingUpdates.remove(PackagePlatformKey(packageId, platform))
        return InstallResult.Success(
            packageId = packageId,
            version = "1.0.0",
            platform = platform
        )
    }

    override suspend fun uninstall(
        packageId: String,
        platform: Platform
    ): UninstallResult {
        installed.removeAll { it.packageId == packageId && it.platform == platform }
        pendingUpdates.remove(PackagePlatformKey(packageId, platform))
        return UninstallResult.Success(packageId, platform)
    }

    override suspend fun getInstalledPackages(): List<InstalledPackageInfo> = installed.toList()

    override suspend fun getDependentPackages(packageId: String, platform: Platform): List<String> = emptyList()

    override suspend fun refreshCache() = Unit

    override suspend fun clearCache() = Unit

    override suspend fun checkForUpdates(): Map<PackagePlatformKey, UpdateInfo> = pendingUpdates.toMap()
}
