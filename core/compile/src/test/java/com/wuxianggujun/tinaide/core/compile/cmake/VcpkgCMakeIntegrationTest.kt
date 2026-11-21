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
        File(project, "vcpkg.json").writeText("{\"dependencies\":[\"fmt\"]}")
        val config = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        val build = File(project, "build")
        val installed = temporary.newFolder("private-workspace-install")
        val plan = checkNotNull(VcpkgCMakeIntegration.prepare(
            project, build, config.applyTo(emptyList()), "arm64-v8a",
            listOf("-DCMAKE_C_COMPILER=/system/bin/sh", "-DCMAKE_C_COMPILER_ARG1=/private/tool chain/clang", "-DCMAKE_SYSTEM_VERSION=28",
                "-DCMAKE_MAKE_PROGRAM=/project/make", "-DCMAKE_PREFIX_PATH=/other/registry"),
            managedInstallationRoot = installed,
            runtimeRoot = root,
        ))
        assertThat(plan.installCommand).contains("--triplet=arm64-tina-android")
        assertThat(plan.installCommand).contains("--x-manifest-root=${project.canonicalPath}")
        assertThat(plan.installCommand).contains("--x-install-root=${installed.canonicalPath}")
        assertThat(plan.installCommand).contains("--x-wait-for-lock")
        assertThat(plan.arguments).contains("-DVCPKG_MANIFEST_INSTALL=OFF")
        assertThat(plan.environment["VCPKG_FORCE_SYSTEM_BINARIES"]).isEqualTo("1")
        val nativeToolchain = toolchain(plan)
        assertThat(nativeToolchain.readText())
            .contains("set(CMAKE_C_COMPILER_ARG1 [[/private/tool chain/clang]] CACHE STRING \"\" FORCE)")
        assertThat(nativeToolchain.readText()).doesNotContain("CMAKE_MAKE_PROGRAM")
        assertThat(nativeToolchain.readText()).doesNotContain("CMAKE_PREFIX_PATH")
        val triplet = File(nativeToolchain.parentFile, "arm64-tina-android.cmake").readText(Charsets.UTF_8)
        assertThat(triplet).contains("TINA_GITHUB_MIRROR X_VCPKG_ASSET_SOURCES")
        assertThat(triplet).contains("http_proxy https_proxy HTTP_PROXY HTTPS_PROXY")
        assertThat(triplet).contains("VCPKG_ENV_PASSTHROUGH_UNTRACKED")
    }

    @Test
    fun assemblyPortsKeepTheClangWrapperTargetAndSysrootFlagsForBothAbis() {
        val root = vcpkgRoot()
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.SHARED)
        for ((abi, triplet, target) in listOf(
            Triple("arm64-v8a", "arm64-android", "aarch64-linux-android28"),
            Triple("x86_64", "x64-android", "x86_64-linux-android28"),
        )) {
            val project = temporary.newFolder()
            val installed = File(root, "installed").apply { File(this, triplet).mkdirs() }
            val build = File(project, "build")
            VcpkgCMakeIntegration.prepare(
                project, build, settings.applyTo(emptyList()), abi,
                listOf(
                    "-DCMAKE_ASM_COMPILER=/system/bin/sh",
                    "-DCMAKE_ASM_COMPILER_ARG1=/private/tool chain/clang",
                    "-DCMAKE_ASM_COMPILER_TARGET=$target",
                    "-DCMAKE_ASM_FLAGS=--target=$target --sysroot=/private/sysroot -fintegrated-cc1",
                    "-DCMAKE_SYSROOT=/private/sysroot",
                    "-DCMAKE_ASM_NASM_COMPILER=/unrelated/nasm",
                ),
                runtimeRoot = root, managedInstallationRoot = installed,
            )
            val toolchain = File(build, "tina-vcpkg/native-toolchain.cmake").readText(Charsets.UTF_8)
            assertThat(toolchain).contains("set(CMAKE_ASM_COMPILER [[/system/bin/sh]] CACHE FILEPATH \"\" FORCE)")
            assertThat(toolchain).contains("set(CMAKE_ASM_COMPILER_ARG1 [[/private/tool chain/clang]] CACHE STRING \"\" FORCE)")
            assertThat(toolchain).contains("set(CMAKE_ASM_COMPILER_TARGET [[$target]] CACHE STRING \"\" FORCE)")
            assertThat(toolchain).contains("set(CMAKE_ASM_FLAGS [[--target=$target --sysroot=/private/sysroot -fintegrated-cc1]] CACHE STRING \"\" FORCE)")
            assertThat(toolchain).doesNotContain("ASM_NASM")
        }
    }

    @Test
    fun shared_shouldUseAndroidTripletAndNeverInvokeCli() {
        val project = temporary.newFolder()
        val root = vcpkgRoot()
        File(root, "installed/x64-android").mkdirs()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.SHARED)
        val plan = checkNotNull(VcpkgCMakeIntegration.prepare(project, File(project, "build"), config.applyTo(emptyList()), "x86_64", emptyList(),
            runtimeRoot = root, managedInstallationRoot = File(root, "installed")))
        assertThat(plan.installCommand).isEmpty()
        assertThat(plan.arguments).contains("-DVCPKG_TARGET_TRIPLET=x64-android")
    }

    @Test
    fun parallelJobsAreForwardedAndClampedToOne() {
        val project = temporary.newFolder()
        val root = vcpkgRoot()
        File(root, "installed/x64-android").mkdirs()
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.SHARED)
        val build = File(project, "build")
        for ((requested, expected) in listOf(2 to "2", 0 to "1", -1 to "1")) {
            val plan = checkNotNull(VcpkgCMakeIntegration.prepare(project, build, settings.applyTo(emptyList()),
                "x86_64", emptyList(), parallelJobs = requested, runtimeRoot = root, managedInstallationRoot = File(root, "installed")))
            assertThat(plan.environment["VCPKG_MAX_CONCURRENCY"]).isEqualTo(expected)
            assertThat(plan.environment["TINA_PREFIX"]).isEqualTo("/system")
        }
    }

    @Test
    fun longProjectPathsUseExistingAndroidShellWithoutWritingAliases() {
        val project = temporary.newFolder("project with spaces and a long name exceeding the native prefix limit")
        val root = vcpkgRoot()
        File(root, "installed/x64-android").mkdirs()
        val settings = VcpkgProjectSettings(VcpkgProjectSettings.SHARED)
        val build = File(project, "build")
        val shell = File(build, "tina-vcpkg/runtime/bin/sh").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("existing content")
        }
        assertThat(build.absolutePath.length).isGreaterThan(90)
        val plan = checkNotNull(VcpkgCMakeIntegration.prepare(project, build, settings.applyTo(emptyList()),
            "x86_64", emptyList(), runtimeRoot = root, managedInstallationRoot = File(root, "installed")))
        assertThat(plan.environment["TINA_PREFIX"]).isEqualTo("/system")
        assertThat(shell.readText()).isEqualTo("existing content")
    }

    @Test
    fun manifest_shouldRejectDesktopOrWrongAbiExecutable() {
        val project = temporary.newFolder()
        File(project, "vcpkg.json").writeText("{\"dependencies\":[\"fmt\"]}")
        val root = vcpkgRoot()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        listOf("MZ desktop executable".toByteArray(), ByteArray(20).apply {
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).copyInto(this)
            this[18] = 62
        }).forEach { executable ->
            File(root, "vcpkg").writeBytes(executable)
            assertThat(runCatching {
                VcpkgCMakeIntegration.prepare(project, File(project, "build"), config.applyTo(emptyList()), "arm64-v8a", emptyList(),
                    runtimeRoot = root, managedInstallationRoot = File(root, "installed"))
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun enabled_shouldRejectConflictingToolchainsAndMissingManifest() {
        val project = temporary.newFolder()
        val root = vcpkgRoot()
        val config = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST)
        for (args in listOf(emptyList(), listOf("-DCMAKE_TOOLCHAIN_FILE:FILEPATH=/another/toolchain.cmake"))) {
            assertThat(runCatching {
                VcpkgCMakeIntegration.prepare(project, File(project, "build"), config.applyTo(args), "arm64-v8a", emptyList(),
                    runtimeRoot = root, managedInstallationRoot = File(root, "installed"))
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun marketAndRunShareTheSameDependencyProfileAcrossBuildDirectories() {
        val project = temporary.newFolder()
        File(project, "vcpkg.json").writeText("{\"dependencies\":[\"fmt\"]}", Charsets.UTF_8)
        val workspace = temporary.newFolder()
        val installed = File(workspace, "vcpkg_installed")
        val root = vcpkgRoot()
        val arguments = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST).applyTo(emptyList())
        val nativeArguments = listOf("-DCMAKE_C_COMPILER=/system/bin/sh", "-DCMAKE_SYSTEM_VERSION=28")
        fun prepare(build: String, extra: List<String>) = checkNotNull(VcpkgCMakeIntegration.prepare(
            project, File(workspace, build), arguments, "arm64-v8a", nativeArguments.reversed() + extra,
            runtimeRoot = root, managedInstallationRoot = installed,
        ))
        val market = prepare("build/vcpkg-manager", listOf("-DCMAKE_BUILD_TYPE=Debug", "-DCMAKE_MAKE_PROGRAM=/market/ninja"))
        val marketToolchain = toolchain(market)
        val originalTimestamp = 1_700_000_000_000L
        assertThat(marketToolchain.setLastModified(originalTimestamp)).isTrue()
        val run = prepare("build/run/Debug", listOf("-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_MAKE_PROGRAM=/run/ninja"))

        assertThat(toolchain(run)).isEqualTo(marketToolchain)
        assertThat(run.installCommand).isEqualTo(market.installCommand)
        assertThat(marketToolchain.lastModified()).isEqualTo(originalTimestamp)
        assertThat(marketToolchain.canonicalPath).startsWith(workspace.canonicalPath + File.separator)
        assertThat(marketToolchain.canonicalPath).doesNotContain("vcpkg-manager")
    }

    @Test
    fun nativeConfigurationChangesKeepPreviousToolchainProfilesIntact() {
        val project = temporary.newFolder()
        File(project, "vcpkg.json").writeText("{\"dependencies\":[]}", Charsets.UTF_8)
        val root = vcpkgRoot()
        val installed = File(temporary.newFolder(), "vcpkg_installed")
        val arguments = VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST).applyTo(emptyList())
        fun prepare(api: Int) = checkNotNull(VcpkgCMakeIntegration.prepare(
            project, File(project, "build-$api"), arguments, "arm64-v8a",
            listOf("-DCMAKE_SYSTEM_VERSION=$api", "-DCMAKE_C_FLAGS=--target=aarch64-linux-android$api"),
            runtimeRoot = root, managedInstallationRoot = installed,
        ))
        val before = prepare(28)
        val original = toolchain(before).readText(Charsets.UTF_8)
        val after = prepare(29)

        assertThat(toolchain(after)).isNotEqualTo(toolchain(before))
        assertThat(toolchain(before).readText(Charsets.UTF_8)).isEqualTo(original)
        assertThat(toolchain(after).readText(Charsets.UTF_8)).contains("android29")
        assertThat(toolchain(prepare(28))).isEqualTo(toolchain(before))
    }

    private fun toolchain(plan: VcpkgCMakeIntegration.Plan): File = File(
        plan.arguments.single { it.startsWith("-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=") }.substringAfter('='),
    )

    private fun vcpkgRoot(): File = temporary.newFolder().apply {
        File(this, "scripts/buildsystems").mkdirs()
        File(this, "scripts/buildsystems/vcpkg.cmake").writeText("# toolchain fixture")
        File(this, "vcpkg").writeBytes(ByteArray(20).apply {
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).copyInto(this)
            this[18] = 183.toByte()
        })
    }
}
