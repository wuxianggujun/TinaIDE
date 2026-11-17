package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectCreationVcpkgTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun enabledCmakeCreatesEmptyManifestAndManagedSettingsOffline() {
        val project = createProject(useVcpkg = true)
        assertThat(File(project, "vcpkg.json").readText(Charsets.UTF_8)).isEqualTo("{\n  \"dependencies\": []\n}\n")
        val settings = VcpkgProjectSettings.from(ProjectMetadataStore.readMetadata(project)!!.normalizedNativeCMakeArgs())
        assertThat(settings).isEqualTo(VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST))
        assertThat(File(project, "vcpkg_installed").exists()).isFalse()
        assertThat(File(project, "main.cpp").readText(Charsets.UTF_8)).contains("int main()")
    }

    @Test fun uncheckedOptionKeepsOrdinaryCmakeProjectWithoutManifestOrManagedFlags() {
        val project = createProject(useVcpkg = false)
        assertThat(File(project, "vcpkg.json").exists()).isFalse()
        assertThat(VcpkgProjectSettings.from(ProjectMetadataStore.readMetadata(project)!!.normalizedNativeCMakeArgs()).enabled).isFalse()
    }

    @Test fun existingManifestIsPreservedByteForByteIncludingFeaturesAndBaseline() {
        val manifest = """{ "description": "中文😀", "builtin-baseline": "${"a".repeat(40)}", "dependencies": [{"name":"fmt","features":["header-only"]}], "overrides": [{"name":"fmt","version":"12.2.1"}] }""" + "\r\n"
        val project = createProject(useVcpkg = true, entries = cmakeEntries + ("vcpkg.json" to manifest))
        assertThat(File(project, "vcpkg.json").readBytes()).isEqualTo(manifest.toByteArray(Charsets.UTF_8))
    }

    @Test fun managedSelectionClearsTemplatePathsButPreservesOtherCmakeArguments() {
        val templateMetadata = """{"id":"fixture-id","displayName":"Fixture","createdAt":1,"nativeCMakeArgs":["-DKEEP=1","-DTINA_VCPKG_MODE=prebuilt","-DTINA_VCPKG_ROOT=/old/root","-DTINA_VCPKG_INSTALLED_DIR=/old/install"]}"""
        val project = createProject(useVcpkg = true, entries = cmakeEntries + (".tinaide/project.json" to templateMetadata))
        val arguments = ProjectMetadataStore.readMetadata(project)!!.normalizedNativeCMakeArgs()
        assertThat(arguments).contains("-DKEEP=1")
        assertThat(VcpkgProjectSettings.from(arguments)).isEqualTo(VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST))
    }

    @Test fun uncheckedOptionPreservesTemplateManifest() {
        val manifest = """{"dependencies":["zlib"]}"""
        val project = createProject(useVcpkg = false, entries = cmakeEntries + ("vcpkg.json" to manifest))
        assertThat(File(project, "vcpkg.json").readText(Charsets.UTF_8)).isEqualTo(manifest)
    }

    @Test fun nonCmakeBuildSystemsRejectVcpkgWithoutCreatingProjectDirectories() {
        for (system in ProjectBuildSystem.entries.filter { it != ProjectBuildSystem.CMAKE }) {
            val root = File(temporary.root, system.name)
            val result = ProjectCreationService.createProject(root, "Demo", template(cmakeEntries, system), useVcpkg = true)
            assertThat((result as ProjectCreationResult.Failure).reason).isEqualTo(ProjectCreationFailure.VCPKG_SETUP_FAILED)
            assertThat(root.exists()).isFalse()
        }
    }

    @Test fun missingCmakeListsRollsBackOnlyNewProject() {
        assertSetupFailure(mapOf("main.cpp" to "int main() {}"))
    }

    @Test fun directoryAtManifestPathRollsBackWithoutOverwritingIt() {
        assertSetupFailure(cmakeEntries + ("vcpkg.json/keep.txt" to "invalid manifest location"))
    }

    private fun assertSetupFailure(entries: Map<String, String>) {
        val root = temporary.newFolder()
        val sibling = File(root, "existing-project").apply { mkdirs() }
        val keep = File(sibling, "keep.txt").apply { writeText("keep", Charsets.UTF_8) }
        val result = ProjectCreationService.createProject(root, "Demo", template(entries), useVcpkg = true)
        assertThat((result as ProjectCreationResult.Failure).reason).isEqualTo(ProjectCreationFailure.VCPKG_SETUP_FAILED)
        assertThat(File(root, "Demo").exists()).isFalse()
        assertThat(root.listFiles()!!.toList()).containsExactly(sibling)
        assertThat(keep.readText(Charsets.UTF_8)).isEqualTo("keep")
    }

    private fun createProject(useVcpkg: Boolean, entries: Map<String, String> = cmakeEntries): File {
        val result = ProjectCreationService.createProject(temporary.newFolder(), "Demo_Project", template(entries), useVcpkg = useVcpkg)
        assertThat(result).isInstanceOf(ProjectCreationResult.Success::class.java)
        return (result as ProjectCreationResult.Success).projectDir
    }

    private fun template(entries: Map<String, String>, system: ProjectBuildSystem = ProjectBuildSystem.CMAKE): ProjectTemplateSpec.Zip {
        val zip = temporary.newFile()
        ZipOutputStream(zip.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return ProjectTemplateSpec.Zip("test:cmake", zip, system)
    }

    private val cmakeEntries = mapOf("CMakeLists.txt" to "project({{PROJECT_NAME}} LANGUAGES CXX)", "main.cpp" to "int main() {}")
}
