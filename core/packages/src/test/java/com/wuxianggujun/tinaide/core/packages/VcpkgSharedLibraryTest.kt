package com.wuxianggujun.tinaide.core.packages

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class VcpkgSharedLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportPublishesTheWorkspaceTripletUnderThePrebuiltName() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val project = temporary.newFolder()
        File(project, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.22)")
        ProjectMetadataStore.ensure(project)
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val source = File(VcpkgManagedPaths.installed(context, project, settings), settings.targetTriplet("arm64-v8a"))
        File(source, "lib/libfmt.a").apply { parentFile!!.mkdirs() }.writeText("static", Charsets.UTF_8)
        File(source, "share/fmt/fmt-config.cmake").apply { parentFile!!.mkdirs() }.writeText("config", Charsets.UTF_8)

        val shared = VcpkgSharedLibrary.export(context, project, settings, "arm64-v8a")

        assertThat(shared).isEqualTo(VcpkgSharedLibrary.root(context))
        val exported = File(shared, VcpkgSharedLibrary.triplet("arm64-v8a"))
        assertThat(exported.name).isEqualTo("arm64-android")
        assertThat(File(exported, "lib/libfmt.a").readText(Charsets.UTF_8)).isEqualTo("static")
        assertThat(File(exported, "share/fmt/fmt-config.cmake").readText(Charsets.UTF_8)).isEqualTo("config")
        assertThat(File(shared, "arm64-tina-android").exists()).isFalse()
    }

    @Test fun exportRequiresAnInstalledTree() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val project = temporary.newFolder()
        File(project, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.22)")
        ProjectMetadataStore.ensure(project)
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)

        val error = runCatching {
            VcpkgSharedLibrary.export(context, project, settings, "arm64-v8a")
        }.exceptionOrNull()

        assertThat((error as? VcpkgException)?.messageResource).isEqualTo(Strings.vcpkg_shared_source_missing)
        assertThat(VcpkgSharedLibrary.root(context).exists()).isFalse()
    }

    @Test fun sharedModeUsesOnlyApplicationManagedPaths() {
        val context = RuntimeEnvironment.getApplication()
        val shared = VcpkgProjectSettings(VcpkgProjectSettings.SHARED)
        assertThat(VcpkgSharedLibrary.usesSharedLibrary(context, shared)).isTrue()
        assertThat(VcpkgSharedLibrary.usesSharedLibrary(context, VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST))).isFalse()
        assertThat(shared.applyTo(listOf("-DTINA_VCPKG_ROOT=/old", "-DTINA_VCPKG_INSTALLED_DIR=/old/installed")))
            .containsExactly("-DTINA_VCPKG_MODE=shared")
    }

    @Test fun cancelledExportPreservesThePreviouslyPublishedTriplet() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val project = temporary.newFolder()
        ProjectMetadataStore.ensure(project)
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val source = File(VcpkgManagedPaths.installed(context, project, settings), settings.targetTriplet("arm64-v8a"))
        File(source, "lib/libfmt.a").apply { parentFile!!.mkdirs() }.writeText("new-library", Charsets.UTF_8)
        File(source, "share/fmt/fmt-config.cmake").apply { parentFile!!.mkdirs() }.writeText("new-config", Charsets.UTF_8)
        val target = File(VcpkgSharedLibrary.root(context), VcpkgSharedLibrary.triplet("arm64-v8a"))
        val previousLibrary = File(target, "lib/libfmt.a").apply { parentFile!!.mkdirs() }
        previousLibrary.writeText("published-library", Charsets.UTF_8)

        val error = runCatching {
            VcpkgSharedLibrary.export(context, project, settings, "arm64-v8a") {
                throw CancellationException("Cancel during export")
            }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(previousLibrary.readText(Charsets.UTF_8)).isEqualTo("published-library")
        assertThat(File(target, "share/fmt/fmt-config.cmake").exists()).isFalse()
        val abandonedTransactions = VcpkgSharedLibrary.root(context).listFiles().orEmpty().filter {
            it.name.startsWith(".shared-staging-") || it.name.startsWith(".shared-backup-")
        }
        assertThat(abandonedTransactions).isEmpty()
    }

    @Test fun cancelledFirstExportDoesNotExposeAnIncompleteTriplet() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val project = temporary.newFolder()
        ProjectMetadataStore.ensure(project)
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val source = File(VcpkgManagedPaths.installed(context, project, settings), settings.targetTriplet("x86_64"))
        File(source, "lib/libfmt.a").apply { parentFile!!.mkdirs() }.writeText("library", Charsets.UTF_8)
        val target = File(VcpkgSharedLibrary.root(context), VcpkgSharedLibrary.triplet("x86_64"))

        val error = runCatching {
            VcpkgSharedLibrary.export(context, project, settings, "x86_64") {
                throw CancellationException("Cancel first export")
            }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(target.exists()).isFalse()
    }

    @Test fun exportPreservesOtherLibrariesAndOtherAbis() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val project = temporary.newFolder()
        ProjectMetadataStore.ensure(project)
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val source = File(VcpkgManagedPaths.installed(context, project, settings), settings.targetTriplet("arm64-v8a"))
        File(source, "lib/libfmt.a").apply { parentFile!!.mkdirs() }.writeText("new-fmt", Charsets.UTF_8)
        val shared = VcpkgSharedLibrary.root(context)
        val otherLibrary = File(shared, "arm64-android/lib/libother.a").apply { parentFile!!.mkdirs() }
        otherLibrary.writeText("other-library", Charsets.UTF_8)
        val otherAbi = File(shared, "x64-android/lib/libfmt.a").apply { parentFile!!.mkdirs() }
        otherAbi.writeText("x64-fmt", Charsets.UTF_8)

        VcpkgSharedLibrary.export(context, project, settings, "arm64-v8a")

        assertThat(File(shared, "arm64-android/lib/libfmt.a").readText(Charsets.UTF_8)).isEqualTo("new-fmt")
        assertThat(otherLibrary.readText(Charsets.UTF_8)).isEqualTo("other-library")
        assertThat(otherAbi.readText(Charsets.UTF_8)).isEqualTo("x64-fmt")
    }

    @Test fun copyFailurePreservesPublishedFiles() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val project = temporary.newFolder()
        ProjectMetadataStore.ensure(project)
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val source = File(VcpkgManagedPaths.installed(context, project, settings), settings.targetTriplet("arm64-v8a"))
        File(source, "lib/libfmt.a").apply { parentFile!!.mkdirs() }.writeText("new-library", Charsets.UTF_8)
        File(source, "share/fmt/fmt-config.cmake").apply { parentFile!!.mkdirs() }.writeText("new-config", Charsets.UTF_8)
        val target = File(VcpkgSharedLibrary.root(context), VcpkgSharedLibrary.triplet("arm64-v8a"))
        val previousLibrary = File(target, "lib/libfmt.a").apply { parentFile!!.mkdirs() }
        previousLibrary.writeText("published-library", Charsets.UTF_8)
        File(target, "share").writeText("existing-file-not-a-directory", Charsets.UTF_8)

        val error = runCatching { VcpkgSharedLibrary.export(context, project, settings, "arm64-v8a") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(previousLibrary.readText(Charsets.UTF_8)).isEqualTo("published-library")
        assertThat(File(target, "share").readText(Charsets.UTF_8)).isEqualTo("existing-file-not-a-directory")
    }

    @Test fun concurrentExportsMergeWithoutLosingEitherProject() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val projects = listOf("first", "second").associateWith { library ->
            val project = temporary.newFolder()
            ProjectMetadataStore.ensure(project)
            val source = File(VcpkgManagedPaths.installed(context, project, settings), settings.targetTriplet("arm64-v8a"))
            File(source, "lib/lib$library.a").apply { parentFile!!.mkdirs() }.writeText(library, Charsets.UTF_8)
            project
        }
        val start = CompletableDeferred<Unit>()
        val exports = projects.values.map { project ->
            async {
                start.await()
                VcpkgSharedLibrary.export(context, project, settings, "arm64-v8a")
            }
        }
        start.complete(Unit)
        exports.awaitAll()

        val shared = File(VcpkgSharedLibrary.root(context), VcpkgSharedLibrary.triplet("arm64-v8a"))
        projects.keys.forEach { library ->
            assertThat(File(shared, "lib/lib$library.a").readText(Charsets.UTF_8)).isEqualTo(library)
        }
    }
}
