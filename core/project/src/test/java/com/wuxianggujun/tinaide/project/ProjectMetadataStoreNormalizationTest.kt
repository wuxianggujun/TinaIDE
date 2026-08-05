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
        file.writeText(content)
    }

    private fun readProjectMetadata(projectRoot: File): String = File(projectRoot, ".tinaide/project.json").readText()
}
