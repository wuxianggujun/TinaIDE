package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ProjectCppStandardResolverTest {

    @Test
    fun normalizeFlag_shouldAcceptSettingsCompilerAndFutureForms() {
        val cases = mapOf(
            "CPP_20" to "c++20",
            "20" to "c++20",
            "c++20" to "c++20",
            "GNU++20" to "gnu++20",
            "-std=c++20" to "c++20",
            "CPP_26" to "c++26",
            "26" to "c++26",
            "c++26" to "c++26",
            "gnu++26" to "gnu++26",
        )

        cases.forEach { (input, expected) ->
            assertThat(ProjectCppStandardResolver.normalizeFlag(input)).isEqualTo(expected)
        }
        assertThat(ProjectCppStandardResolver.normalizeFlag("-std=invalid")).isNull()
    }

    @Test
    fun resolveFlag_shouldUseOverrideBeforeBuildFilesAndMetadata() {
        withProject("CPP_14") { projectRoot ->
            File(projectRoot, "CMakeLists.txt").writeText("set(CMAKE_CXX_STANDARD 17)\n", Charsets.UTF_8)
            File(projectRoot, "Makefile").writeText("CXXFLAGS += -std=c++20\n", Charsets.UTF_8)

            assertThat(
                ProjectCppStandardResolver.resolveFlag(
                    projectRoot,
                    "-Wall -std=gnu++26 -Wextra",
                )
            ).isEqualTo("gnu++26")
        }
    }

    @Test
    fun resolveFlag_shouldPreferLastStaticCmakeStandardOverMakefileAndMetadata() {
        withProject("CPP_14") { projectRoot ->
            File(projectRoot, "CMakeLists.txt").writeText(
                """
                cmake_minimum_required(VERSION 3.20)
                set(CMAKE_CXX_STANDARD 17)
                SET ( CMAKE_CXX_STANDARD "26" CACHE STRING "C++ standard" )
                """.trimIndent(),
                Charsets.UTF_8,
            )
            File(projectRoot, "Makefile").writeText("CXXFLAGS += -std=c++20\n", Charsets.UTF_8)

            assertThat(ProjectCppStandardResolver.resolveFlag(projectRoot)).isEqualTo("c++26")
        }
    }

    @Test
    fun resolveFlag_shouldIgnoreCmakeCommentsAndFallbackAfterDynamicValue() {
        withProject("CPP_23") { projectRoot ->
            File(projectRoot, "CMakeLists.txt").writeText(
                """
                # set(CMAKE_CXX_STANDARD 20)
                #[[
                set(CMAKE_CXX_STANDARD 26)
                ]]
                set(cmake_cxx_standard 17)
                set(CMAKE_CXX_STANDARD "${'$'}{PROJECT_CXX_STANDARD}")
                """.trimIndent(),
                Charsets.UTF_8,
            )

            assertThat(ProjectCppStandardResolver.resolveFlag(projectRoot)).isEqualTo("c++23")
        }
    }

    @Test
    fun resolveFlag_shouldFallbackAfterCmakeStandardIsUnset() {
        withProject("CPP_14") { projectRoot ->
            File(projectRoot, "CMakeLists.txt").writeText(
                """
                set(CMAKE_CXX_STANDARD 20)
                unset(CMAKE_CXX_STANDARD)
                """.trimIndent(),
                Charsets.UTF_8,
            )

            assertThat(ProjectCppStandardResolver.resolveFlag(projectRoot)).isEqualTo("c++14")
        }
    }

    @Test
    fun resolveFlag_shouldUseLastValidMakefileStandardAndIgnoreComments() {
        listOf("Makefile", "makefile", "GNUmakefile").forEach { makefileName ->
            withProject("CPP_14") { projectRoot ->
                File(projectRoot, makefileName).writeText(
                    """
                    CXXFLAGS := -std=c++17 -Wall
                    # CXXFLAGS := -std=c++26
                    CXXFLAGS += -std=gnu++20 # -std=c++26
                    CXXFLAGS += -std=not-a-standard
                    """.trimIndent(),
                    Charsets.UTF_8,
                )

                assertThat(ProjectCppStandardResolver.resolveFlag(projectRoot)).isEqualTo("gnu++20")
            }
        }
    }

    @Test
    fun resolveFlag_shouldPreserveFutureMetadataStandardAndDefaultToCpp17() {
        withProject("c++26") { projectRoot ->
            assertThat(ProjectCppStandardResolver.resolveFlag(projectRoot)).isEqualTo("c++26")
        }

        val projectRoot = Files.createTempDirectory("project-cpp-standard-default-").toFile()
        try {
            assertThat(ProjectCppStandardResolver.resolveFlag(projectRoot)).isEqualTo("c++17")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun withProject(
        metadataStandard: String,
        block: (File) -> Unit,
    ) {
        val projectRoot = Files.createTempDirectory("project-cpp-standard-").toFile()
        try {
            ProjectMetadataStore.write(
                projectRoot,
                ProjectMetadata(
                    id = "project-cpp-standard",
                    displayName = "Project C++ Standard",
                    createdAt = 1L,
                    buildSystem = ProjectBuildSystem.CMAKE,
                    cppStandard = metadataStandard,
                ),
            )
            block(projectRoot)
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
