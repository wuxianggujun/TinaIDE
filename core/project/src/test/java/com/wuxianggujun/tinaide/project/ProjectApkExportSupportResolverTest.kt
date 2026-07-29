package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ProjectApkExportSupportResolverTest {

    @Test
    fun `detect returns sdl3 when project outputs libmain and links SDL3`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                cmake_minimum_required(VERSION 3.22)
                project(Demo)
                add_library(Demo SHARED src/main.cpp)
                set_target_properties(Demo PROPERTIES OUTPUT_NAME "main")
                target_link_libraries(Demo PRIVATE SDL3::SDL3)
                """.trimIndent()
            )
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText("""#include <SDL3/SDL_main.h>""")

            val detected = ProjectApkExportSupportResolver.detect(projectRoot)

            assertThat(detected).isEqualTo(ProjectApkExportType.SDL3)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect records SDL2 runtime without enabling SDL3 APK export`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                cmake_minimum_required(VERSION 3.22)
                project(Demo)
                find_package(SDL2 CONFIG REQUIRED)
                add_library(Demo SHARED src/main.cpp)
                set_target_properties(Demo PROPERTIES OUTPUT_NAME "main")
                target_link_libraries(Demo PRIVATE SDL2::SDL2)
                """.trimIndent()
            )
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText("""#include <SDL2/SDL.h>""")

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect accepts bare SDL2 CMake target`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                target_link_libraries(main
                    PRIVATE
                    SDL2
                )
                """.trimIndent()
            )

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect scans SDL2 markers from included cmake modules`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                include(cmake/Dependencies.cmake)
                """.trimIndent()
            )
            projectRoot.resolve("cmake").mkdirs()
            projectRoot.resolve("cmake/Dependencies.cmake").writeText(
                "target_link_libraries(main PRIVATE SDL2::SDL2)"
            )

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect accepts SDL2 config command from GNUmakefile`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("GNUmakefile").writeText(
                """
                SDL_CFLAGS := ${'$'}(shell sdl2-config --cflags)
                SDL_LIBS := ${'$'}(shell sdl2-config --libs)
                """.trimIndent()
            )

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect does not classify SDL2 project as native activity`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                target_link_libraries(main PRIVATE SDL2::SDL2)
                """.trimIndent()
            )
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText(
                """
                #include <android_native_app_glue.h>
                #include <SDL2/SDL.h>
                """.trimIndent()
            )

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect accepts versioned SDL2 soname markers`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("Android.mk").writeText(
                """
                LOCAL_MODULE := main
                LOCAL_LDLIBS += -Llibs -lSDL2
                # Runtime SONAME: libSDL2-2.0.so.0
                """.trimIndent()
            )

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect does not treat shared SDLActivity marker as SDL3`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                """.trimIndent()
            )
            projectRoot.resolve("AndroidManifest.xml").writeText(
                """<activity android:name="org.libsdl.app.SDLActivity" />"""
            )

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isNull()
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect leaves conflicting SDL2 and SDL3 markers unresolved`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                target_link_libraries(main PRIVATE SDL2::SDL2 SDL3::SDL3)
                """.trimIndent()
            )

            val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)

            assertThat(detected.sdlVersion).isNull()
            assertThat(detected.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect returns null when project only outputs libmain`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                cmake_minimum_required(VERSION 3.22)
                project(Demo)
                add_library(main SHARED src/main.cpp)
                """.trimIndent()
            )
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText("int main() { return 0; }")

            val detected = ProjectApkExportSupportResolver.detect(projectRoot)

            assertThat(detected).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect returns terminal when project has main entry without libmain markers`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("Makefile").writeText(
                """
                app: src/main.cpp
                """.trimIndent()
            )
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText(
                """
                #include <stdio.h>

                int main() {
                    puts("hello");
                    return 0;
                }
                """.trimIndent()
            )

            val detected = ProjectApkExportSupportResolver.detect(projectRoot)

            assertThat(detected).isEqualTo(ProjectApkExportType.TERMINAL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `detect returns terminal when build directory contains runnable elf`() {
        val projectRoot = createTempProjectRoot()
        val buildDir = projectRoot.resolve("build").apply { mkdirs() }
        try {
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText("// no markers")
            buildDir.resolve("demo").writeBytes(
                byteArrayOf(
                    0x7F,
                    'E'.code.toByte(),
                    'L'.code.toByte(),
                    'F'.code.toByte(),
                    0x02,
                    0x01
                )
            )

            val detected = ProjectApkExportSupportResolver.detect(projectRoot, buildDir)

            assertThat(detected).isEqualTo(ProjectApkExportType.TERMINAL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `ensureDetected writes native activity export type for native activity project`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                cmake_minimum_required(VERSION 3.22)
                project(Demo)
                add_library(Demo SHARED src/main.cpp)
                set_target_properties(Demo PROPERTIES OUTPUT_NAME "main")
                """.trimIndent()
            )
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText(
                """
                #include <android_native_app_glue.h>

                void android_main(struct android_app* app) {}
                """.trimIndent()
            )
            projectRoot.resolve("AndroidManifest.xml").writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <activity android:name="android.app.NativeActivity">
                            <meta-data android:name="android.app.lib_name" android:value="main" />
                        </activity>
                    </application>
                </manifest>
                """.trimIndent()
            )
            ProjectMetadataStore.ensure(projectRoot, displayNameFallback = "Demo", buildSystem = ProjectBuildSystem.CMAKE)

            val detected = ProjectApkExportSupportResolver.ensureDetected(projectRoot)

            assertThat(detected).isEqualTo(ProjectApkExportType.NATIVE_ACTIVITY)
            assertThat(ProjectMetadataStore.read(projectRoot)?.apkExportType)
                .isEqualTo(ProjectApkExportType.NATIVE_ACTIVITY)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `ensureDetected persists SDL2 runtime without APK export type`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                target_link_libraries(main PRIVATE SDL2::SDL2)
                """.trimIndent()
            )
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = "Demo",
                buildSystem = ProjectBuildSystem.CMAKE,
            )

            val detected = ProjectApkExportSupportResolver.ensureDetected(projectRoot)
            val metadata = ProjectMetadataStore.read(projectRoot)

            assertThat(detected).isNull()
            assertThat(metadata?.apkExportType).isNull()
            assertThat(metadata?.sdlVersion).isEqualTo(ProjectSdlVersion.SDL2)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `ensureDetected does not replace known SDL2 with native activity export`() {
        val projectRoot = createTempProjectRoot()
        try {
            projectRoot.resolve("CMakeLists.txt").writeText(
                """
                add_library(main SHARED src/main.cpp)
                """.trimIndent()
            )
            projectRoot.resolve("src").mkdirs()
            projectRoot.resolve("src/main.cpp").writeText(
                """
                #include <android_native_app_glue.h>
                void android_main(struct android_app* app) {}
                """.trimIndent()
            )
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = "SDL2 Demo",
                sdlVersion = ProjectSdlVersion.SDL2,
            )

            val detected = ProjectApkExportSupportResolver.ensureDetected(projectRoot)

            assertThat(detected).isNull()
            assertThat(ProjectMetadataStore.read(projectRoot)?.sdlVersion)
                .isEqualTo(ProjectSdlVersion.SDL2)
            assertThat(ProjectMetadataStore.read(projectRoot)?.apkExportType).isNull()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `ensureDetected respects disabled export type from metadata`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = "Demo",
                buildSystem = ProjectBuildSystem.CMAKE,
                apkExportType = ProjectApkExportType.DISABLED
            )

            val detected = ProjectApkExportSupportResolver.ensureDetected(projectRoot)

            assertThat(detected).isEqualTo(ProjectApkExportType.DISABLED)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun createTempProjectRoot(): File = Files.createTempDirectory("project-apk-export-support-test").toFile()
}
