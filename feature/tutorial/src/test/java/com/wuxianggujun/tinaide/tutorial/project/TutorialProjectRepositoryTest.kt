package com.wuxianggujun.tinaide.tutorial.project

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectSdlVersion
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TutorialProjectRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val projectsRoot get() = File(temporary.root, "projects").canonicalFile
    private val cacheRoot get() = File(temporary.root, "cache")

    @Test
    fun createsNineRealProjectsWithCompleteFilesAndCorrectMetadataOffline() = runTest {
        val repository = repository()
        for (stepId in TutorialProjectBlueprints.stepIds) {
            val project = repository.prepare(stepId, "en")
            val blueprint = TutorialProjectBlueprints.load(stepId) { readBundledLesson(it, "en") }
            assertThat(project.name).isEqualTo(blueprint.directoryName)
            for ((name, contents) in blueprint.files) {
                assertThat(File(project, name).readText(Charsets.UTF_8)).isEqualTo(contents)
            }
            val metadata = requireNotNull(ProjectMetadataStore.read(project))
            assertThat(metadata.buildSystem).isEqualTo(ProjectBuildSystem.CMAKE)
            assertThat(metadata.defaultRunTargetName).isEqualTo("native_lab")
            assertThat(metadata.sdlVersion).isEqualTo(ProjectSdlVersion.SDL3.takeIf { blueprint.isSdl3 })
            assertThat(VcpkgProjectSettings.from(metadata.normalizedNativeCMakeArgs()).enabled).isEqualTo(blueprint.useVcpkg)
            assertThat(File(project, "vcpkg.json").exists()).isEqualTo(blueprint.useVcpkg)
            assertThat(File(project, "vcpkg_installed").exists()).isFalse()
            assertThat(File(project, TutorialProjectRepository.MARKER_FILE).isFile).isTrue()
        }
        assertThat(projectsRoot.listFiles()!!.toList()).hasSize(9)
        assertThat(cacheRoot.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun reopeningPreservesUserEditsDependenciesAndModificationTimesAcrossLocales() = runTest {
        val project = repository().prepare("vcpkg_guide", "zh")
        val source = File(project, "main.cpp").apply { appendText("// 我的修改 😀\n", Charsets.UTF_8) }
        val manifest = File(project, "vcpkg.json").apply {
            writeText("{\"description\":\"用户练习😀\",\"dependencies\":[\"fmt\"]}\r\n", Charsets.UTF_8)
        }
        val before = listOf(source, manifest, File(project, TutorialProjectRepository.MARKER_FILE), ProjectMetadataStore.getMetaFile(project))
            .associateWith { it.readBytes() to it.lastModified() }
        val resumed = repository().prepare("vcpkg_guide", "en")
        assertThat(resumed).isEqualTo(project)
        before.forEach { (file, snapshot) ->
            assertThat(file.readBytes()).isEqualTo(snapshot.first)
            assertThat(file.lastModified()).isEqualTo(snapshot.second)
        }
        assertThat(projectsRoot.listFiles()!!.toList()).containsExactly(project)
    }

    @Test
    fun unownedDirectoryAndFileConflictsArePreserved() = runTest {
        projectsRoot.mkdirs()
        val baseName = TutorialProjectBlueprints.load("ide_workflow") { readBundledLesson(it, "zh") }.directoryName
        val unowned = File(projectsRoot, baseName).apply { mkdirs() }
        val keep = File(unowned, "main.cpp").apply { writeText("user source", Charsets.UTF_8) }
        val otherFile = File(projectsRoot, "${baseName}_2").apply { writeText("user file", Charsets.UTF_8) }
        val created = repository().prepare("ide_workflow", "zh")
        assertThat(created.name).isEqualTo("${baseName}_3")
        assertThat(keep.readText(Charsets.UTF_8)).isEqualTo("user source")
        assertThat(otherFile.readText(Charsets.UTF_8)).isEqualTo("user file")
        assertThat(repository().prepare("ide_workflow", "en")).isEqualTo(created)
    }

    @Test
    fun renamedOwnedProjectIsFoundWithoutCreatingAnotherProject() = runTest {
        val project = repository().prepare("cpp_functions", "zh")
        val renamed = File(projectsRoot, "my_function_practice")
        assertThat(project.renameTo(renamed)).isTrue()
        assertThat(repository().prepare("cpp_functions", "en")).isEqualTo(renamed)
        assertThat(projectsRoot.listFiles()!!.toList()).containsExactly(renamed)
    }

    @Test
    fun corruptOversizedAndMismatchedMarkersNeverClaimUserDirectories() = runTest {
        for (markerKind in listOf("corrupt", "oversized", "different-id", "unsupported-schema")) {
            val root = File(temporary.root, markerKind)
            val repository = TutorialProjectRepository(root, cacheRoot, ::readBundledLesson)
            val original = repository.prepare("ide_workflow", "zh")
            val marker = File(original, TutorialProjectRepository.MARKER_FILE)
            val validText = marker.readText(Charsets.UTF_8)
            marker.writeText(when (markerKind) {
                "corrupt" -> "not json"
                "oversized" -> " ".repeat(2049)
                "different-id" -> validText.replace(requireNotNull(ProjectMetadataStore.read(original)).id, "unrelated-id")
                else -> validText.replace("\"schemaVersion\":1", "\"schemaVersion\":99")
            }, Charsets.UTF_8)
            val before = File(original, "main.cpp").readBytes()
            val created = repository.prepare("ide_workflow", "en")
            assertThat(created).isNotEqualTo(original)
            assertThat(File(original, "main.cpp").readBytes()).isEqualTo(before)
            assertThat(created.name).endsWith("_2")
        }
    }

    @Test
    fun concurrentRequestsFromDifferentInstancesCreateOnlyOneProject() = runTest {
        val projects = listOf(async { repository().prepare("cpp_basics", "zh") }, async { repository().prepare("cpp_basics", "en") }).awaitAll()
        assertThat(projects[0]).isEqualTo(projects[1])
        assertThat(projectsRoot.listFiles()!!.toList()).hasSize(1)
        assertThat(cacheRoot.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun missingAssetsAndUnavailableStorageLeaveOtherProjectsUnchanged() = runTest {
        projectsRoot.mkdirs()
        val keep = File(projectsRoot, "keep.txt").apply { writeText("keep", Charsets.UTF_8) }
        val broken = TutorialProjectRepository(projectsRoot, cacheRoot) { _, _ -> "# Missing files" }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { broken.prepare("ide_workflow", "zh") } }
        assertThat(projectsRoot.listFiles()!!.toList()).containsExactly(keep)
        val invalidRoot = temporary.newFile("not-a-directory")
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { TutorialProjectRepository(invalidRoot, cacheRoot, ::readBundledLesson).prepare("ide_workflow", "en") }
        }
        assertThat(keep.readText(Charsets.UTF_8)).isEqualTo("keep")
    }

    private fun repository() = TutorialProjectRepository(projectsRoot, cacheRoot, ::readBundledLesson)
}
