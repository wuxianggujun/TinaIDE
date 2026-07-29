package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectSdlVersion
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ProjectRunConfigBootstrapperTest {

    @Test
    fun `initializeIfMissing writes explicit sdl config for sdl3 project`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isTrue()
            assertThat(runConfigFile(projectRoot).exists()).isTrue()

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)
            assertThat(manager.selectedConfig.name).isEqualTo("Debug")
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
            assertThat(runConfigFile(projectRoot).readText()).contains("\"outputMode\": \"SDL\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing writes explicit sdl config for sdl2 project`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                sdlVersion = ProjectSdlVersion.SDL2,
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isTrue()
            val manager = RunConfigurationManager.load(projectRoot.absolutePath)
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing detects sdl2 project before creating config`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                target_link_libraries(main PRIVATE SDL2)
                """.trimIndent()
            )
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isTrue()
            assertThat(ProjectMetadataStore.read(projectRoot)?.sdlVersion)
                .isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(RunConfigurationManager.load(projectRoot.absolutePath).selectedConfig.outputMode)
                .isEqualTo(OutputMode.SDL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing does not overwrite existing config`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3
            )
            val existingManager = RunConfigurationManager(
                configurations = listOf(RunConfiguration(id = "cfg-existing", outputMode = OutputMode.TERMINAL)),
                selectedId = "cfg-existing"
            )
            assertThat(RunConfigurationManager.save(projectRoot.absolutePath, existingManager)).isTrue()

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isFalse()
            val manager = RunConfigurationManager.load(projectRoot.absolutePath)
            assertThat(manager.selectedId).isEqualTo("cfg-existing")
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.TERMINAL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing writes explicit terminal target from metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.TERMINAL,
                defaultRunTargetName = "demo_test",
                defaultSdlTargetName = "demo"
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isTrue()
            assertThat(runConfigFile(projectRoot).exists()).isTrue()

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.TERMINAL)
            assertThat(manager.selectedConfig.targetName).isEqualTo("demo_test")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing writes sdl target when sdl3 metadata provides one`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3,
                defaultRunTargetName = "demo_test",
                defaultSdlTargetName = "demo"
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isTrue()
            val manager = RunConfigurationManager.load(projectRoot.absolutePath)
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
            assertThat(manager.selectedConfig.targetName).isEqualTo("demo")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing skips project without graphical metadata or target`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.DISABLED
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isFalse()
            assertThat(runConfigFile(projectRoot).exists()).isFalse()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun createTempProjectRoot(): File = Files.createTempDirectory("project-run-config-bootstrapper-test").toFile()

    private fun runConfigFile(projectRoot: File): File = File(projectRoot, ".tinaide/run_configs.json")
}
