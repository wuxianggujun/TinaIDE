package com.wuxianggujun.tinaide.core.packages

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun managedProjectUsesFixedInstallationDirectoryAndSharedCatalog() = runTest {
        val project = temporary.newFolder()
        File(project, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.22)")
        val metadata = ProjectMetadataStore.ensure(project)
        val stale = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST, "/old/root", "/old/install")
        assertThat(ProjectMetadataStore.write(project, metadata.copy(nativeCMakeArgs = stale.applyTo(listOf("-DKEEP=1"))))).isTrue()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val repository = VcpkgRepository(context)
        repository.enableManagedManifest(project)
        repository.setDependency(project, "fmt", true)
        val installed = VcpkgManagedPaths.installed(context, project, stale)
        assertThat(installed.canonicalPath).startsWith(File(context.filesDir, "workspace").canonicalPath + File.separator)
        assertThat(installed.canonicalPath).doesNotContain(project.canonicalPath)
        val status = File(installed, "vcpkg/status")
        status.parentFile!!.mkdirs()
        status.writeText("Package: fmt\nArchitecture: arm64-tina-android\nVersion: 12\nStatus: install ok installed\n")
        val catalog = repository.read(project, "arm64-v8a", listOf(VcpkgPort("fmt", "12", "Format", "")))
        assertThat(catalog.settings.root).isEmpty()
        assertThat(catalog.settings.installedDir).isEmpty()
        assertThat(catalog.installed).containsExactly("fmt", "12")
        assertThat(catalog.ports.single().description).isEqualTo("Format")
        assertThat(ProjectMetadataStore.read(project)!!.normalizedNativeCMakeArgs()).contains("-DKEEP=1")
        val renamed = File(project.parentFile, project.name + "-renamed")
        check(project.renameTo(renamed))
        assertThat(VcpkgManagedPaths.installed(context, renamed, stale)).isEqualTo(installed)
        assertThat(VcpkgManagedPaths.installed(context, temporary.newFolder(), stale)).isNotEqualTo(installed)
    }

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
        checkNotNull(status.parentFile).mkdirs()
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

    @Test
    fun malformedUtf8MustNotBeRewrittenAsReplacementCharacters() = runTest {
        val project = temporary.newFolder()
        val manifest = File(project, "vcpkg.json")
        val original = """{"description":"""".toByteArray(Charsets.UTF_8) + byteArrayOf(0x80.toByte()) +
            """","dependencies":["fmt"]}""".toByteArray(Charsets.UTF_8)
        manifest.writeBytes(original)

        val result = runCatching { VcpkgRepository().setDependency(project, "zlib", true) }

        assertThat((result.exceptionOrNull() as? VcpkgException)?.messageResource)
            .isEqualTo(Strings.vcpkg_invalid_manifest)
        assertThat(manifest.readBytes()).isEqualTo(original)
        assertNoStagingFiles(project)
    }

    @Test
    fun removingAnAbsentDependencyDoesNotCreateAManifest() = runTest {
        val project = temporary.newFolder()
        VcpkgRepository().setDependency(project, "fmt", false)
        assertThat(File(project, "vcpkg.json").exists()).isFalse()
        assertNoStagingFiles(project)
    }

    @Test
    fun noOpDependencyChangesPreserveExactBytesAndModificationTime() = runTest {
        val project = temporary.newFolder()
        val manifest = File(project, "vcpkg.json")
        val original = """{ "description": "中文😀", "dependencies": [ { "name": "fmt", "features": ["header-only"] } ] }"""
            .toByteArray(Charsets.UTF_8)
        manifest.writeBytes(original)
        check(manifest.setLastModified(1_700_000_000_000L))
        val modified = manifest.lastModified()
        val repository = VcpkgRepository()

        repository.setDependency(project, "fmt", true)
        repository.setDependency(project, "missing", false)

        assertThat(manifest.readBytes()).isEqualTo(original)
        assertThat(manifest.lastModified()).isEqualTo(modified)
        assertNoStagingFiles(project)
    }

    @Test
    fun editsFromDifferentRepositoryInstancesDoNotLoseDependencies() = runBlocking<Unit> {
        val project = temporary.newFolder()
        val manifest = File(project, "vcpkg.json")
        manifest.writeText(JSONObject().put("description", "x".repeat(96 * 1024))
            .put("dependencies", org.json.JSONArray()).toString(), Charsets.UTF_8)
        val start = CompletableDeferred<Unit>()
        val names = (1..12).map { "library-$it" }
        val edits = names.map { name ->
            async(Dispatchers.IO) {
                start.await()
                runCatching { VcpkgRepository().setDependency(project, name, true) }
            }
        }
        start.complete(Unit)
        val results = withTimeout(10_000) { edits.awaitAll() }

        assertThat(results.mapNotNull { it.exceptionOrNull() }).isEmpty()
        val dependencies = JSONObject(manifest.readText(Charsets.UTF_8)).getJSONArray("dependencies")
        assertThat((0 until dependencies.length()).map { dependencies.getString(it) })
            .containsExactlyElementsIn(names)
        assertNoStagingFiles(project)
    }

    @Test
    fun editingMustNotCreateAManifestLargerThanTheAcceptedReadLimit() = runTest {
        val project = temporary.newFolder()
        val manifest = File(project, "vcpkg.json")
        val prefix = """{"description":""""
        val suffix = """","dependencies":[]}"""
        val original = (prefix + "x".repeat(1024 * 1024 - prefix.length - suffix.length) + suffix)
            .toByteArray(Charsets.UTF_8)
        manifest.writeBytes(original)

        val result = runCatching { VcpkgRepository().setDependency(project, "fmt", true) }

        assertThat((result.exceptionOrNull() as? VcpkgException)?.messageResource)
            .isEqualTo(Strings.vcpkg_invalid_manifest)
        assertThat(manifest.readBytes()).isEqualTo(original)
        assertNoStagingFiles(project)
    }

    @Test
    fun realEditPreservesChineseAndSupplementaryCharacters() = runTest {
        val project = temporary.newFolder()
        val manifest = File(project, "vcpkg.json")
        manifest.writeText("""{"description":"中文😀","dependencies":["fmt"]}""", Charsets.UTF_8)

        VcpkgRepository().setDependency(project, "zlib", true)

        val result = JSONObject(manifest.readText(Charsets.UTF_8))
        assertThat(result.getString("description")).isEqualTo("中文😀")
        assertThat(result.getJSONArray("dependencies").length()).isEqualTo(2)
    }

    @Test
    fun catalogRejectsMalformedUtf8ProjectManifest() = runTest {
        val project = temporary.newFolder()
        File(project, "vcpkg.json").writeBytes("""{"description":"""".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x80.toByte()) + """","dependencies":["fmt"]}""".toByteArray(Charsets.UTF_8))

        val result = runCatching { VcpkgRepository().read(project, "arm64-v8a") }

        assertThat((result.exceptionOrNull() as? VcpkgException)?.messageResource)
            .isEqualTo(Strings.vcpkg_invalid_manifest)
        assertThat(result.exceptionOrNull()?.cause).isNull()
    }

    @Test
    fun statusContinuationCannotOverrideArchitectureAndFeatureRecordsAreExcluded() = runTest {
        val project = configuredProject()
        val status = File(project, "vcpkg_installed/vcpkg/status")
        checkNotNull(status.parentFile).mkdirs()
        status.writeText("""
            Package: fmt
            Architecture: arm64-tina-android
            Version: 11.0.0
            Status: install ok installed
            Description: line one
             Architecture: x64-tina-android

            Package: fmt
            Architecture: arm64-tina-android
            Feature: header-only
            Status: install ok installed

            Package: ../invalid
            Architecture: arm64-tina-android
            Version: 1.0
            Status: install ok installed

            Package: zlib
            Architecture: arm64-tina-android
            Version: 1.3
            Status: install ok half-installed
        """.trimIndent().replace("\n", "\r\n"), Charsets.UTF_8)

        assertThat(VcpkgRepository().read(project, "arm64-v8a").installed).containsExactly("fmt", "11.0.0")
    }

    @Test
    fun invalidUtf8StatusFailsInsteadOfReportingAnInstalledPackage() = runTest {
        val project = configuredProject()
        val status = File(project, "vcpkg_installed/vcpkg/status")
        checkNotNull(status.parentFile).mkdirs()
        status.writeBytes("Package: fmt\nArchitecture: arm64-tina-android\nVersion: ".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x80.toByte()) + "\nStatus: install ok installed".toByteArray(Charsets.UTF_8))

        val result = runCatching { VcpkgRepository().read(project, "arm64-v8a") }

        assertThat((result.exceptionOrNull() as? VcpkgException)?.messageResource)
            .isEqualTo(Strings.vcpkg_invalid_manifest)
    }

    @Test
    fun invalidUtf8PortIsSkippedWithoutHidingValidPorts() = runTest {
        val project = configuredProject()
        val settings = VcpkgProjectSettings.from(checkNotNull(ProjectMetadataStore.read(project)).normalizedNativeCMakeArgs())
        val ports = File(settings.root, "ports")
        val valid = File(ports, "fmt/vcpkg.json").apply { checkNotNull(parentFile).mkdirs() }
        valid.writeText("""{"name":"fmt","version":"11.0","description":"中文😀"}""", Charsets.UTF_8)
        val invalid = File(ports, "broken/vcpkg.json").apply { checkNotNull(parentFile).mkdirs() }
        invalid.writeBytes("""{"description":"""".toByteArray(Charsets.UTF_8) + byteArrayOf(0x80.toByte()) +
            """"}""".toByteArray(Charsets.UTF_8))

        val result = VcpkgRepository().read(project, "arm64-v8a")

        assertThat(result.skippedPorts).isEqualTo(1)
        assertThat(result.ports.map { it.name }).containsExactly("fmt")
        assertThat(result.ports.single().description).isEqualTo("中文😀")
    }

    @Test
    fun directoryAtManifestPathIsRejectedRatherThanReportedAsNoDependencies() = runTest {
        val project = temporary.newFolder()
        File(project, "vcpkg.json").mkdir()
        val result = runCatching { VcpkgRepository().read(project, "arm64-v8a") }
        assertThat((result.exceptionOrNull() as? VcpkgException)?.messageResource)
            .isEqualTo(Strings.vcpkg_invalid_manifest)
    }

    private suspend fun configuredProject(): File {
        val project = temporary.newFolder()
        val root = temporary.newFolder()
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST, root.absolutePath)
        val metadata = ProjectMetadataStore.ensure(project)
        check(ProjectMetadataStore.write(project, metadata.copy(nativeCMakeArgs = settings.applyTo(emptyList()))))
        return project
    }

    private fun assertNoStagingFiles(project: File) {
        assertThat(project.listFiles().orEmpty().filter { it.name.startsWith(".vcpkg-manifest-") }).isEmpty()
    }
}
