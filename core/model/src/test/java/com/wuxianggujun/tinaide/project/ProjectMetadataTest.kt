package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectMetadataTest {

    @Test
    fun metadata_shouldNormalizeNativeSettings() {
        val metadata = metadata(
            cppStandard = " c++20 ",
            primaryLanguage = "kotlin",
            nativeApiLevel = 36,
            nativeIncludeDirs = listOf(" include ", "", "include", "third_party/include"),
            nativeCFlags = "\n -DDEBUG \n -Wall ",
            nativeCMakeArgs = listOf(
                " -GNinja ",
                "",
                "-GNinja",
                "-DOPT=ON",
                "-DCMAKE_BUILD_TYPE=Release",
            )
        )

        assertThat(metadata.getCppStandard()).isEqualTo(CppStandard.CPP_20)
        assertThat(metadata.normalizedCppStandardValue()).isEqualTo("CPP_20")
        assertThat(metadata.getPrimaryLanguage()).isEqualTo(ProjectLanguage.KOTLIN)
        assertThat(metadata.getNativeApiLevelOrNull()).isNull()
        assertThat(metadata.normalizedNativeIncludeDirs())
            .containsExactly("include", "third_party/include")
            .inOrder()
        assertThat(metadata.normalizedNativeCFlags()).isEqualTo("-DDEBUG -Wall")
        assertThat(metadata.normalizedNativeCMakeArgs())
            .containsExactly("-GNinja", "-DOPT=ON")
            .inOrder()
    }

    @Test
    fun metadata_shouldRejectRunConfigurationManagedCMakeBuildTypeArguments() {
        val arguments = listOf(
            "-DCMAKE_BUILD_TYPE=Release",
            "-D CMAKE_BUILD_TYPE:STRING=MinSizeRel",
            "-Dcmake_build_type = Debug",
        )

        assertThat(ProjectCMakeArgumentPolicy.containsManagedBuildType(arguments)).isTrue()
        assertThat(ProjectCMakeArgumentPolicy.sanitize(arguments)).isEmpty()
        assertThat(ProjectCMakeArgumentPolicy.sanitize(listOf("-DOTHER_BUILD_TYPE=Release")))
            .containsExactly("-DOTHER_BUILD_TYPE=Release")
    }

    @Test
    fun metadata_shouldFallbackUnknownLanguageAndCppStandard() {
        val metadata = metadata(cppStandard = "gnu++26", primaryLanguage = "brainfuck", nativeApiLevel = 28)

        assertThat(metadata.getCppStandard()).isEqualTo(CppStandard.DEFAULT)
        assertThat(metadata.normalizedCppStandardValue()).isEqualTo("gnu++26")
        assertThat(metadata.getPrimaryLanguage()).isEqualTo(ProjectLanguage.UNKNOWN)
        assertThat(metadata.getNativeApiLevelOrNull()).isEqualTo(28)
    }

    private fun metadata(
        cppStandard: String? = null,
        primaryLanguage: String? = null,
        nativeApiLevel: Int? = null,
        nativeIncludeDirs: List<String> = emptyList(),
        nativeCFlags: String = "",
        nativeCMakeArgs: List<String> = emptyList()
    ): ProjectMetadata = ProjectMetadata(
        id = "project-id",
        displayName = "Demo",
        createdAt = 1L,
        cppStandard = cppStandard,
        primaryLanguage = primaryLanguage,
        nativeApiLevel = nativeApiLevel,
        nativeIncludeDirs = nativeIncludeDirs,
        nativeCFlags = nativeCFlags,
        nativeCMakeArgs = nativeCMakeArgs
    )
}
