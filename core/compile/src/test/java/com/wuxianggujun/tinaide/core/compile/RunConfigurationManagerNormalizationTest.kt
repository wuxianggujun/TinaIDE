package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectSdlVersion
import java.io.File
import java.nio.file.Files
import org.junit.Test

class RunConfigurationManagerNormalizationTest {

    @Test
    fun `normalized clears stale SDL version outside SDL output mode`() {
        val config = RunConfiguration(
            outputMode = OutputMode.NATIVE_ACTIVITY,
            sdlVersion = ProjectSdlVersion.SDL3,
        )

        assertThat(config.normalized().sdlVersion).isNull()
    }

    @Test
    fun `load normalizes current schema values and selected id`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 3,
                  "configurations": [
                    {
                      "id": "cfg-current",
                      "name": "Debug",
                      "singleFileCppStandard": "c++20",
                      "customCCompiler": "",
                      "customCppCompiler": "   "
                    }
                  ],
                  "selectedId": "missing-id"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.schemaVersion).isEqualTo(8)
            assertThat(manager.selectedId).isEqualTo("cfg-current")
            assertThat(manager.selectedConfig.buildType).isEqualTo(BuildType.DEBUG)
            assertThat(manager.selectedConfig.singleFileCppStandard).isEqualTo("CPP_20")
            assertThat(manager.selectedConfig.customCCompiler).isNull()
            assertThat(manager.selectedConfig.customCppCompiler).isNull()

            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 8")
            assertThat(persisted).contains("\"selectedId\": \"cfg-current\"")
            assertThat(persisted).contains("\"singleFileCppStandard\": \"CPP_20\"")
            assertThat(persisted).contains("\"customCCompiler\": null")
            assertThat(persisted).contains("\"customCppCompiler\": null")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load current schema defaults missing build type to debug`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 3,
                  "configurations": [
                    {
                      "id": "cfg-build-type",
                      "name": "Debug"
                    }
                  ],
                  "selectedId": "cfg-build-type"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.buildType).isEqualTo(BuildType.DEBUG)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load defaults missing linker warning option to hidden`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "configurations": [
                    {
                      "id": "cfg-linker-warning-default",
                      "name": "Debug"
                    }
                  ],
                  "selectedId": "cfg-linker-warning-default"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.showLinkerWarnings).isFalse()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load preserves explicitly enabled linker warnings`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "configurations": [
                    {
                      "id": "cfg-linker-warning-enabled",
                      "name": "Debug",
                      "showLinkerWarnings": true
                    }
                  ],
                  "selectedId": "cfg-linker-warning-enabled"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.showLinkerWarnings).isTrue()
            assertThat(readRunConfig(projectRoot)).contains("\"showLinkerWarnings\": true")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load migrates legacy global cmake build type into every run configuration`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 7,
                  "configurations": [
                    {
                      "id": "cfg-debug",
                      "name": "Debug"
                    },
                    {
                      "id": "cfg-release",
                      "name": "Release",
                      "buildType": "RELEASE"
                    }
                  ],
                  "selectedId": "cfg-release"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(
                projectPath = projectRoot.absolutePath,
                legacyCMakeBuildType = CMakeBuildTypeOption.REL_WITH_DEB_INFO,
            )

            assertThat(manager.configurations.map { it.cmakeBuildType })
                .containsExactly(
                    CMakeBuildTypeOption.REL_WITH_DEB_INFO,
                    CMakeBuildTypeOption.REL_WITH_DEB_INFO,
                )
                .inOrder()
            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 8")
            assertThat(persisted).contains("\"cmakeBuildType\": \"REL_WITH_DEB_INFO\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load preserves explicit cmake build type override`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 8,
                  "configurations": [
                    {
                      "id": "cfg-cmake-release",
                      "name": "Release",
                      "buildType": "RELEASE",
                      "cmakeBuildType": "RELEASE"
                    }
                  ],
                  "selectedId": "cfg-cmake-release"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.buildType).isEqualTo(BuildType.RELEASE)
            assertThat(manager.selectedConfig.cmakeBuildType)
                .isEqualTo(CMakeBuildTypeOption.RELEASE)
            assertThat(readRunConfig(projectRoot)).contains("\"cmakeBuildType\": \"RELEASE\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load preserves SDL2 run configuration override`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 5,
                  "configurations": [
                    {
                      "id": "cfg-sdl2",
                      "name": "SDL2 Debug",
                      "outputMode": "SDL",
                      "sdlVersion": "SDL2"
                    }
                  ],
                  "selectedId": "cfg-sdl2"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.schemaVersion).isEqualTo(8)
            assertThat(manager.selectedConfig.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 8")
            assertThat(persisted).contains("\"sdlVersion\": \"SDL2\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load defaults sdl3 project to sdl output when config file is missing`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.name).isEqualTo("Debug")
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load defaults sdl2 project to sdl output when config file is missing`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                sdlVersion = ProjectSdlVersion.SDL2,
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.name).isEqualTo("Debug")
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load migrates legacy raylib sdl mode to native activity`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.NATIVE_ACTIVITY,
                defaultRunTargetName = "main",
            )
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 6,
                  "configurations": [
                    {
                      "id": "cfg-raylib",
                      "name": "Raylib Debug",
                      "outputMode": "SDL",
                      "targetName": "main"
                    }
                  ],
                  "selectedId": "cfg-raylib"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.schemaVersion).isEqualTo(8)
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.NATIVE_ACTIVITY)
            assertThat(readRunConfig(projectRoot)).contains("\"outputMode\": \"NATIVE_ACTIVITY\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load keeps schema 7 native project sdl choice explicit`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.NATIVE_ACTIVITY,
            )
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 7,
                  "configurations": [
                    {
                      "id": "cfg-explicit-sdl",
                      "name": "Explicit SDL",
                      "outputMode": "SDL"
                    }
                  ],
                  "selectedId": "cfg-explicit-sdl"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load defaults target from project metadata when config file is missing`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.TERMINAL,
                defaultRunTargetName = "demo_test",
                defaultSdlTargetName = "demo"
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.TERMINAL)
            assertThat(manager.selectedConfig.targetName).isEqualTo("demo_test")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load repairs blank terminal target from project metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.TERMINAL,
                defaultRunTargetName = "demo_test",
                defaultSdlTargetName = "demo"
            )
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "configurations": [
                    {
                      "id": "cfg-terminal",
                      "name": "Debug",
                      "outputMode": "TERMINAL",
                      "targetName": ""
                    }
                  ],
                  "selectedId": "cfg-terminal"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.targetName).isEqualTo("demo_test")
            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"targetName\": \"demo_test\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load repairs blank sdl target from project metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3,
                defaultRunTargetName = "demo_test",
                defaultSdlTargetName = "demo"
            )
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "configurations": [
                    {
                      "id": "cfg-sdl",
                      "name": "Debug",
                      "outputMode": "SDL",
                      "targetName": ""
                    }
                  ],
                  "selectedId": "cfg-sdl"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.targetName).isEqualTo("demo")
            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"targetName\": \"demo\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load does not overwrite non blank target from project metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3,
                defaultRunTargetName = "demo_test",
                defaultSdlTargetName = "demo"
            )
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "configurations": [
                    {
                      "id": "cfg-custom",
                      "name": "Debug",
                      "outputMode": "SDL",
                      "targetName": "custom_target"
                    }
                  ],
                  "selectedId": "cfg-custom"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.targetName).isEqualTo("custom_target")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun createTempProjectRoot(): File = Files.createTempDirectory("run-config-normalization-test").toFile()

    private fun writeRunConfig(projectRoot: File, content: String) {
        val file = File(projectRoot, ".tinaide/run_configs.json")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun readRunConfig(projectRoot: File): String = File(projectRoot, ".tinaide/run_configs.json").readText()
}
