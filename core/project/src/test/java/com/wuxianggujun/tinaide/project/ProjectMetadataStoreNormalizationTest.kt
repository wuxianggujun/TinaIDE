package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ProjectMetadataStoreNormalizationTest {

    @Test
    fun `read normalizes current metadata values`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 2,
                  "id": "meta-1",
                  "displayName": "Demo",
                  "createdAt": 1700000000000,
                  "apkExportType": "SDL3",
                  "cppStandard": "c++20",
                  "nativeApiLevel": 99,
                  "nativeIncludeDirs": ["  third_party/SDL3/include ", "", "third_party/SDL3/include"],
                  "nativeCFlags": "-O2\n\n -DDEBUG ",
                  "defaultRunTargetName": "  demo_test  ",
                  "defaultSdlTargetName": "  demo  "
                }
                """.trimIndent()
            )

            val metadata = ProjectMetadataStore.read(projectRoot)
            requireNotNull(metadata)

            assertThat(metadata.schemaVersion).isEqualTo(5)
            assertThat(metadata.sdlVersion).isEqualTo(ProjectSdlVersion.SDL3)
            assertThat(metadata.cppStandard).isEqualTo("CPP_20")
            assertThat(metadata.nativeApiLevel).isNull()
            assertThat(metadata.nativeIncludeDirs).containsExactly("third_party/SDL3/include")
            assertThat(metadata.nativeCFlags).isEqualTo("-O2 -DDEBUG")
            assertThat(metadata.defaultRunTargetName).isEqualTo("demo_test")
            assertThat(metadata.defaultSdlTargetName).isEqualTo("demo")

            val persisted = readProjectMetadata(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 5")
            assertThat(persisted).contains("\"sdlVersion\": \"SDL3\"")
            assertThat(persisted).contains("\"cppStandard\": \"CPP_20\"")
            assertThat(persisted).contains("\"defaultRunTargetName\": \"demo_test\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `write normalizes current metadata and keeps unknown cpp standard`() {
        val projectRoot = createTempProjectRoot()
        try {
            val metadata = ProjectMetadata(
                schemaVersion = 2,
                id = "meta-2",
                displayName = "Demo",
                createdAt = 1700000000000,
                cppStandard = "gnu++2b"
            )

            val wrote = ProjectMetadataStore.write(projectRoot, metadata)
            assertThat(wrote).isTrue()

            val persisted = readProjectMetadata(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 5")
            assertThat(persisted).contains("\"cppStandard\": \"gnu++2b\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read replaces path-like project identity and persists the replacement`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 5,
                  "id": "../../outside-workspace",
                  "displayName": "Unsafe identity",
                  "createdAt": 1700000000000
                }
                """.trimIndent()
            )

            val metadata = requireNotNull(ProjectMetadataStore.read(projectRoot))

            assertThat(ProjectIdentity.isValid(metadata.id)).isTrue()
            assertThat(metadata.id).isNotEqualTo("../../outside-workspace")
            assertThat(readProjectMetadata(projectRoot)).contains("\"id\": \"${metadata.id}\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read rejects oversized metadata before decoding`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeProjectMetadata(projectRoot, "x".repeat(300 * 1024))

            assertThat(ProjectMetadataStore.read(projectRoot)).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `write truncates display name without leaving an unpaired surrogate`() {
        val projectRoot = createTempProjectRoot()
        try {
            val metadata = ProjectMetadata(
                schemaVersion = 5,
                id = "surrogate-boundary",
                displayName = "x".repeat(255) + "\uD83D\uDE00",
                createdAt = 1700000000000,
            )

            assertThat(ProjectMetadataStore.write(projectRoot, metadata)).isTrue()

            val persisted = requireNotNull(ProjectMetadataStore.read(projectRoot))
            assertThat(persisted.displayName).isEqualTo("x".repeat(255))
            assertThat(File(projectRoot, ".tinaide").listFiles().orEmpty().map(File::getName))
                .containsExactly("project.json")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `oversized write preserves existing metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            val baseline = ProjectMetadata(
                schemaVersion = 5,
                id = "atomic-baseline",
                displayName = "Baseline",
                createdAt = 1700000000000,
            )
            assertThat(ProjectMetadataStore.write(projectRoot, baseline)).isTrue()
            val oversizedPaths = (0 until 256).map { index ->
                "include/$index/" + "x".repeat(4_080)
            }

            val wroteOversized = ProjectMetadataStore.write(
                projectRoot,
                baseline.copy(displayName = "Replacement", nativeIncludeDirs = oversizedPaths),
            )

            assertThat(wroteOversized).isFalse()
            assertThat(requireNotNull(ProjectMetadataStore.read(projectRoot)).displayName)
                .isEqualTo("Baseline")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read bounds untrusted metadata fields`() {
        val projectRoot = createTempProjectRoot()
        try {
            val excessivePaths = (0 until 300).joinToString(",") { index ->
                "\"include/$index\""
            }
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 5,
                  "id": "bounded-metadata",
                  "displayName": "${"x".repeat(300)}",
                  "createdAt": 1700000000000,
                  "createdByIdeVersion": "version\u0000hidden",
                  "nativeIncludeDirs": [$excessivePaths],
                  "nativeCFlags": "${"-DVALUE ".repeat(3000)}",
                  "defaultRunTargetName": "${"target".repeat(100)}"
                }
                """.trimIndent(),
            )

            val metadata = requireNotNull(ProjectMetadataStore.read(projectRoot))

            assertThat(metadata.displayName).hasLength(256)
            assertThat(metadata.createdByIdeVersion).isEqualTo("version hidden")
            assertThat(metadata.nativeIncludeDirs).hasSize(256)
            assertThat(metadata.nativeCFlags).isEmpty()
            assertThat(metadata.defaultRunTargetName).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read removes incompatible SDL3 APK export from SDL2 metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "id": "meta-sdl2-conflict",
                  "displayName": "SDL2 Demo",
                  "createdAt": 1700000000000,
                  "apkExportType": "SDL3",
                  "sdlVersion": "SDL2"
                }
                """.trimIndent()
            )

            val metadata = ProjectMetadataStore.read(projectRoot)

            assertThat(metadata?.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(metadata?.apkExportType).isNull()
            assertThat(readProjectMetadata(projectRoot)).contains("\"apkExportType\": null")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read repairs legacy SDL3 export metadata when source uses SDL2`() {
        val projectRoot = createTempProjectRoot()
        try {
            File(projectRoot, "CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                find_package(SDL2 REQUIRED CONFIG)
                target_link_libraries(main PRIVATE SDL2::SDL2)
                """.trimIndent()
            )
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 3,
                  "id": "legacy-sdl2-misclassified-as-sdl3",
                  "displayName": "Legacy SDL2 Demo",
                  "createdAt": 1700000000000,
                  "apkExportType": "SDL3"
                }
                """.trimIndent()
            )

            val metadata = ProjectMetadataStore.read(projectRoot)

            assertThat(metadata?.schemaVersion).isEqualTo(5)
            assertThat(metadata?.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(metadata?.apkExportType).isNull()
            val persisted = readProjectMetadata(projectRoot)
            assertThat(persisted).contains("\"sdlVersion\": \"SDL2\"")
            assertThat(persisted).contains("\"apkExportType\": null")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read repairs legacy SDL3 export metadata when source uses raylib`() {
        val projectRoot = createTempProjectRoot()
        try {
            File(projectRoot, "CMakeLists.txt").writeText(
                """
                find_package(raylib CONFIG REQUIRED)
                add_library(main SHARED src/main.c)
                target_link_libraries(main PRIVATE raylib::raylib)
                """.trimIndent()
            )
            File(projectRoot, "src").mkdirs()
            File(projectRoot, "src/main.c").writeText(
                """
                #include <raylib.h>
                int main(void) { return 0; }
                """.trimIndent()
            )
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 3,
                  "id": "legacy-raylib-misclassified-as-sdl3",
                  "displayName": "Legacy Raylib Demo",
                  "createdAt": 1700000000000,
                  "apkExportType": "SDL3"
                }
                """.trimIndent()
            )

            val metadata = ProjectMetadataStore.read(projectRoot)

            assertThat(metadata?.schemaVersion).isEqualTo(5)
            assertThat(metadata?.sdlVersion).isNull()
            assertThat(metadata?.apkExportType).isEqualTo(ProjectApkExportType.NATIVE_ACTIVITY)
            val persisted = readProjectMetadata(projectRoot)
            assertThat(persisted).contains("\"sdlVersion\": null")
            assertThat(persisted).contains("\"apkExportType\": \"NATIVE_ACTIVITY\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read repairs legacy raylib metadata that already persisted SDL3 version`() {
        val projectRoot = createTempProjectRoot()
        try {
            File(projectRoot, "CMakeLists.txt").writeText(
                """
                find_package(raylib CONFIG REQUIRED)
                add_library(main SHARED src/main.c)
                target_link_libraries(main PRIVATE raylib)
                """.trimIndent()
            )
            File(projectRoot, "src").mkdirs()
            File(projectRoot, "src/main.c").writeText(
                """
                #include <raylib.h>
                int main(void) { return 0; }
                """.trimIndent()
            )
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "id": "legacy-raylib-with-sdl3-version",
                  "displayName": "Legacy Raylib Demo",
                  "createdAt": 1700000000000,
                  "apkExportType": "SDL3",
                  "sdlVersion": "SDL3"
                }
                """.trimIndent()
            )

            val metadata = ProjectMetadataStore.read(projectRoot)

            assertThat(metadata?.schemaVersion).isEqualTo(5)
            assertThat(metadata?.sdlVersion).isNull()
            assertThat(metadata?.apkExportType).isEqualTo(ProjectApkExportType.NATIVE_ACTIVITY)
            val persisted = readProjectMetadata(projectRoot)
            assertThat(persisted).contains("\"sdlVersion\": null")
            assertThat(persisted).contains("\"apkExportType\": \"NATIVE_ACTIVITY\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `read removes incompatible native activity export from SDL2 metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 4,
                  "id": "meta-sdl2-native-activity-conflict",
                  "displayName": "SDL2 NativeActivity Conflict",
                  "createdAt": 1700000000000,
                  "apkExportType": "NATIVE_ACTIVITY",
                  "sdlVersion": "SDL2"
                }
                """.trimIndent()
            )

            val metadata = ProjectMetadataStore.read(projectRoot)

            assertThat(metadata?.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(metadata?.apkExportType).isNull()
            assertThat(readProjectMetadata(projectRoot)).contains("\"apkExportType\": null")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun createTempProjectRoot(): File = Files.createTempDirectory("project-meta-normalization-test").toFile()

    private fun writeProjectMetadata(projectRoot: File, content: String) {
        val file = File(projectRoot, ".tinaide/project.json")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun readProjectMetadata(projectRoot: File): String =
        File(projectRoot, ".tinaide/project.json").readText(Charsets.UTF_8)
}
