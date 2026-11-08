package com.wuxianggujun.tinaide.core.packages

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.File
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class VcpkgRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun catalog_shouldSkipBrokenPortsAndUseActualInstalledTriplet() = runTest {
        val project = temporary.newFolder()
        val root = temporary.newFolder()
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST, root.absolutePath)
        val metadata = ProjectMetadataStore.ensure(project)
        assertThat(ProjectMetadataStore.write(project, metadata.copy(nativeCMakeArgs = settings.applyTo(emptyList())))).isTrue()
        File(root, "ports/fmt").mkdirs()
        File(root, "ports/fmt/vcpkg.json").writeText("""{"name":"fmt","version":"11.0.0","description":["Format","library"]}""")
        File(root, "ports/broken").mkdirs()
        File(root, "ports/broken/vcpkg.json").writeText("{broken")
        File(project, "vcpkg.json").writeText("""{"dependencies":["fmt","zlib"]}""")
        val status = File(project, "vcpkg_installed/vcpkg/status")
        status.parentFile.mkdirs()
        status.writeText("""
            Package: fmt
            Architecture: arm64-tina-android
            Version: 11.0.0
            Status: install ok installed

            Package: zlib
            Architecture: x64-tina-android
            Version: 1.3.1
            Status: install ok installed
        """.trimIndent())
        val catalog = VcpkgRepository().read(project, "arm64-v8a")
        assertThat(catalog.skippedPorts).isEqualTo(1)
        assertThat(catalog.installed).containsExactly("fmt", "11.0.0")
        assertThat(catalog.ports.map { it.name }).containsExactly("fmt", "zlib")
        assertThat(catalog.declared).containsExactly("fmt", "zlib")
    }

    @Test
    fun editManifest_shouldPreserveFeaturesVersionsAndOtherFields() = runTest {
        val project = temporary.newFolder()
        val manifest = File(project, "vcpkg.json")
        manifest.writeText("""{"builtin-baseline":"pinned","dependencies":[{"name":"fmt","features":["header-only"]}],"overrides":[{"name":"fmt","version":"11.0.0"}]}""")
        val repository = VcpkgRepository()
        repository.setDependency(project, "zlib", true)
        repository.setDependency(project, "zlib", true)
        val added = JSONObject(manifest.readText())
        assertThat(added.getString("builtin-baseline")).isEqualTo("pinned")
        assertThat(added.getJSONArray("dependencies").length()).isEqualTo(2)
        assertThat(added.getJSONArray("dependencies").getJSONObject(0).getJSONArray("features").getString(0)).isEqualTo("header-only")
        repository.setDependency(project, "zlib", false)
        assertThat(JSONObject(manifest.readText()).getJSONArray("dependencies").length()).isEqualTo(1)
        assertThat(JSONObject(manifest.readText()).getJSONArray("overrides").length()).isEqualTo(1)
    }

    @Test
    fun editManifest_shouldNotOverwriteMalformedJsonOrAcceptPathNames() = runTest {
        val project = temporary.newFolder()
        val manifest = File(project, "vcpkg.json").apply { writeText("{broken") }
        val repository = VcpkgRepository()
        assertThat(runCatching { repository.setDependency(project, "fmt", true) }.isFailure).isTrue()
        assertThat(manifest.readText()).isEqualTo("{broken")
        assertThat(runCatching { repository.setDependency(project, "../other", true) }.isFailure).isTrue()
        assertThat(manifest.readText()).isEqualTo("{broken")
    }
}
