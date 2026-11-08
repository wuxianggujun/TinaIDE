package com.wuxianggujun.tinaide.core.compile.cmake

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VcpkgCMakeIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun disabled_shouldNotRequireToolsOrChangeProject() {
        val project = temporary.newFolder()
        assertThat(VcpkgCMakeIntegration.prepare(project, File(project, "build"), emptyList(), "arm64-v8a", emptyList())).isNull()
        assertThat(File(project, "build").exists()).isFalse()
    }

    @Test
    fun manifest_shouldUseActiveAbiAndNativeCompilerWithoutShellInterpolation() {
        val project = temporary.newFolder("project with spaces")
        val root = vcpkgRoot()
        File(root, "vcpkg").writeBytes(ByteArray(20).apply {
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).copyInto(this)
            this[18] = 183.toByte()
        })
        File(project, "vcpkg.json").writeText("{\"dependencies\":[\"fmt\"]}")
        val config = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST, root.path)
        val build = File(project, "build")
        val plan = checkNotNull(VcpkgCMakeIntegration.prepare(
            project, build, config.applyTo(emptyList()), "arm64-v8a",
            listOf("-DCMAKE_C_COMPILER=/system/bin/sh", "-DCMAKE_C_COMPILER_ARG1=/private/tool chain/clang", "-DCMAKE_SYSTEM_VERSION=28",
                "-DCMAKE_MAKE_PROGRAM=/project/make", "-DCMAKE_PREFIX_PATH=/other/registry"),
        ))
        assertThat(plan.installCommand).contains("--triplet=arm64-tina-android")
        assertThat(plan.installCommand).contains("--x-manifest-root=${project.canonicalPath}")
        assertThat(plan.arguments).contains("-DVCPKG_MANIFEST_INSTALL=OFF")
        assertThat(plan.environment["VCPKG_FORCE_SYSTEM_BINARIES"]).isEqualTo("1")
        assertThat(File(build, "tina-vcpkg/native-toolchain.cmake").readText())
            .contains("set(CMAKE_C_COMPILER_ARG1 [[/private/tool chain/clang]] CACHE STRING \"\" FORCE)")
        assertThat(File(build, "tina-vcpkg/native-toolchain.cmake").readText()).doesNotContain("CMAKE_MAKE_PROGRAM")
        assertThat(File(build, "tina-vcpkg/native-toolchain.cmake").readText()).doesNotContain("CMAKE_PREFIX_PATH")
    }

    @Test
    fun prebuilt_shouldUseAndroidTripletAndNeverInvokeCli() {
        val project = temporary.newFolder()
        val root = vcpkgRoot()
        File(root, "installed/x64-android").mkdirs()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.PREBUILT, root.path)
        val plan = checkNotNull(VcpkgCMakeIntegration.prepare(project, File(project, "build"), config.applyTo(emptyList()), "x86_64", emptyList()))
        assertThat(plan.installCommand).isEmpty()
        assertThat(plan.arguments).contains("-DVCPKG_TARGET_TRIPLET=x64-android")
    }

    @Test
    fun manifest_shouldRejectDesktopOrWrongAbiExecutable() {
        val project = temporary.newFolder()
        File(project, "vcpkg.json").writeText("{\"dependencies\":[\"fmt\"]}")
        val root = vcpkgRoot()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST, root.path)
        listOf("MZ desktop executable".toByteArray(), ByteArray(20).apply {
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).copyInto(this)
            this[18] = 62
        }).forEach { executable ->
            File(root, "vcpkg").writeBytes(executable)
            assertThat(runCatching {
                VcpkgCMakeIntegration.prepare(project, File(project, "build"), config.applyTo(emptyList()), "arm64-v8a", emptyList())
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun enabled_shouldRejectConflictingToolchainsAndMissingManifest() {
        val project = temporary.newFolder()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST, vcpkgRoot().path)
        for (args in listOf(emptyList(), listOf("-DCMAKE_TOOLCHAIN_FILE:FILEPATH=/another/toolchain.cmake"))) {
            assertThat(runCatching {
                VcpkgCMakeIntegration.prepare(project, File(project, "build"), config.applyTo(args), "arm64-v8a", emptyList())
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun vcpkgRoot(): File = temporary.newFolder().apply {
        File(this, "scripts/buildsystems").mkdirs()
        File(this, "scripts/buildsystems/vcpkg.cmake").writeText("# toolchain fixture")
    }
}
