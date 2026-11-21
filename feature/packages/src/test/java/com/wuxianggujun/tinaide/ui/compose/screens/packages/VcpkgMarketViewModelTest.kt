package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.VcpkgProjectInstaller
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogSnapshot
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.core.packages.VcpkgException
import com.wuxianggujun.tinaide.core.packages.VcpkgProjectCatalog
import com.wuxianggujun.tinaide.core.packages.VcpkgRepository
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import io.mockk.coEvery
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VcpkgMarketViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val catalog = VcpkgProjectCatalog(
        VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST), emptyList(), setOf("7zip"), emptyMap(),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkConstructor(VcpkgRepository::class, VcpkgProjectInstaller::class, VcpkgCatalogStore::class)
        coEvery { anyConstructed<VcpkgRepository>().read(any(), any(), any()) } returns catalog
        coEvery { anyConstructed<VcpkgCatalogStore>().readLocal() } returns VcpkgCatalogSnapshot("fixture", "", emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun failedInstallationKeepsItsFinalErrorInTheBoundedCopyableLog() = runTest(dispatcher) {
        val failure = "7zip: configure failed"
        coEvery { anyConstructed<VcpkgProjectInstaller>().install(any(), any()) } coAnswers {
            val progress = secondArg<(String) -> Unit>()
            repeat(300) { progress("build line $it") }
            ConfigureResult.Error(failure)
        }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.install()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.error).isEqualTo(failure)
        assertThat(state.output).hasSize(250)
        assertThat(state.output.last()).isEqualTo(failure)
        assertThat(state.installing).isFalse()
    }

    @Test
    fun thrownInstallationFailureAppearsInTheCopyableLog() = runTest(dispatcher) {
        coEvery { anyConstructed<VcpkgProjectInstaller>().install(any(), any()) } throws
            VcpkgException(Strings.vcpkg_invalid_cli)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.install()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.error).isNotEmpty()
        assertThat(state.output.last()).isEqualTo(state.error)
        assertThat(state.installing).isFalse()
    }

    @Test
    fun statusRefreshFailureDoesNotDiscardTheOriginalInstallError() = runTest(dispatcher) {
        val failure = "7zip: build failed"
        coEvery { anyConstructed<VcpkgProjectInstaller>().install(any(), any()) } returns ConfigureResult.Error(failure)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { anyConstructed<VcpkgRepository>().read(any(), any(), any()) } throws
            VcpkgException(Strings.vcpkg_invalid_cli)

        viewModel.install()
        advanceUntilIdle()

        assertThat(viewModel.state.value.output).contains(failure)
        assertThat(viewModel.state.value.output.last()).isEqualTo(viewModel.state.value.error)
        assertThat(viewModel.state.value.installing).isFalse()
    }

    private suspend fun viewModel(): VcpkgMarketViewModel {
        val project = temporary.newFolder().apply { File(this, "CMakeLists.txt").writeText("# fixture", Charsets.UTF_8) }
        return VcpkgMarketViewModel(RuntimeEnvironment.getApplication(), SavedStateHandle()).apply {
            selectProject(project.absolutePath)
        }.also { it.state.first { state -> state.catalog != null && !state.loading } }
    }
}
