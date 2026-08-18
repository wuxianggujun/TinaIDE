package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.ndk.InstalledSysrootProfileConfig
import com.wuxianggujun.tinaide.core.ndk.InstalledToolchainConfig
import com.wuxianggujun.tinaide.core.ndk.SysrootProfileInfo
import com.wuxianggujun.tinaide.core.ndk.SysrootProfileType
import com.wuxianggujun.tinaide.core.ndk.ToolchainInfo
import com.wuxianggujun.tinaide.core.ndk.ToolchainType
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectCppStandardResolver
import com.wuxianggujun.tinaide.project.ProjectMetadata
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CompileDatabaseProviderRuntimeIdentityTest {

    private lateinit var context: Context
    private lateinit var provider: CompileDatabaseProvider
    private lateinit var projectRoot: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "toolchain-config.json").delete()
        File(context.filesDir, "toolchains").deleteRecursively()
        File(context.filesDir, "sysroot-profile-config.json").delete()
        File(context.filesDir, "android-sysroots").deleteRecursively()
        Prefs.initialize(context, ConfigManager(context))
        projectRoot = Files.createTempDirectory("compile-db-runtime-identity-").toFile().also { root ->
            File(root, "main.cpp").writeText("int main() { return 0; }\n")
            ProjectMetadataStore.write(
                root,
                ProjectMetadata(
                    id = "compile-db-runtime-identity",
                    displayName = "Compile DB Runtime Identity",
                    createdAt = 1L,
                    buildSystem = ProjectBuildSystem.CMAKE,
                    cppStandard = CppStandard.DEFAULT.name,
                    nativeApiLevel = 28,
                )
            )
        }
        provider = CompileDatabaseProvider(context)
    }

    @Test
    fun prepare_shouldRegenerateWhenActiveToolchainChanges() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
        )

        val before = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))
        assertThat(before.shouldGenerate).isFalse()

        configureToolchain("toolchain-b")

        val after = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))
        assertThat(after.toolchainId).isEqualTo("toolchain-b")
        assertThat(after.shouldGenerate).isTrue()
    }

    @Test
    fun prepare_shouldRegenerateWhenActiveSysrootProfileChanges() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
        )

        val before = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))
        assertThat(before.shouldGenerate).isFalse()

        configureSysroot("sysroot-b")

        val after = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))
        assertThat(after.sysrootProfileId).isEqualTo("sysroot-b")
        assertThat(after.shouldGenerate).isTrue()
    }

    @Test
    fun prepare_shouldRegenerateTinaFallbackWhenCmakeCxxStandardChanges() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        writeCMakeLists(CppStandard.CPP_17)
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
            cppStandardFlag = CppStandard.CPP_17.flag,
        )

        val before = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))
        assertThat(before.desiredCppStandardFlag).isEqualTo("c++17")
        assertThat(before.shouldGenerate).isFalse()

        writeCMakeLists(CppStandard.CPP_20)

        val after = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))
        assertThat(after.desiredCppStandardFlag).isEqualTo("c++20")
        assertThat(after.shouldGenerate).isTrue()
    }

    @Test
    fun prepare_shouldKeepExternalCmakeCompileDatabaseAuthoritative() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        writeCMakeLists(CppStandard.CPP_20)
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
            cppStandardFlag = CppStandard.CPP_17.flag,
            generatedBy = "external",
        )

        val prepared = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))

        assertThat(prepared.desiredCppStandardFlag).isEqualTo("c++20")
        assertThat(prepared.shouldGenerate).isFalse()
    }

    @Test
    fun prepare_shouldForceOnlyTinaFallbackRegenerationAfterBuildFileSave() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
        )

        val tinaFallback = requireNotNull(
            provider.prepare(
                file = File(projectRoot, "main.cpp"),
                projectRootPath = projectRoot.absolutePath,
                forceRegenerateFallback = true,
            )
        )
        assertThat(tinaFallback.shouldGenerate).isTrue()

        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
            generatedBy = "external",
        )
        val externalDatabase = requireNotNull(
            provider.prepare(
                file = File(projectRoot, "main.cpp"),
                projectRootPath = projectRoot.absolutePath,
                forceRegenerateFallback = true,
            )
        )
        assertThat(externalDatabase.shouldGenerate).isFalse()
    }

    @Test
    fun prepare_shouldTreatReplacedTinaFallbackAsExternalCmakeDatabase() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        writeCMakeLists(CppStandard.CPP_20)
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
            cppStandardFlag = "c++17",
        )
        File(projectRoot, "build/compile_commands.json").writeText(
            """
            [{"directory":"${projectRoot.absolutePath.replace("\\", "\\\\")}","arguments":["clang++","-std=gnu++23","main.cpp"],"file":"main.cpp"}]
            """.trimIndent()
        )

        val prepared = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))

        assertThat(prepared.desiredCppStandardFlag).isEqualTo("c++20")
        assertThat(prepared.shouldGenerate).isFalse()
    }

    @Test
    fun prepare_shouldUseExplicitFutureStandardOverrideForTinaFallback() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
            cppStandardFlag = "c++17",
        )

        val prepared = requireNotNull(
            provider.prepare(
                file = File(projectRoot, "main.cpp"),
                projectRootPath = projectRoot.absolutePath,
                cppStandardOverride = "c++26",
            )
        )

        assertThat(prepared.desiredCppStandardFlag).isEqualTo("c++26")
        assertThat(prepared.shouldGenerate).isTrue()
    }

    @Test
    fun prepare_shouldNotLetNativeCppFlagsOverrideCmakeStandard() {
        configureToolchain("toolchain-a")
        configureSysroot("sysroot-a")
        val metadata = requireNotNull(ProjectMetadataStore.read(projectRoot))
        ProjectMetadataStore.write(
            projectRoot,
            metadata.copy(nativeCppFlags = "-Wall -std=c++11"),
        )
        writeCMakeLists(CppStandard.CPP_20)
        writeCompileCommandsWithMetadata(
            toolchainId = "toolchain-a",
            sysrootProfileId = "sysroot-a",
            sysrootApiLevel = 28,
            cppStandardFlag = "c++20",
        )

        val prepared = requireNotNull(provider.prepare(File(projectRoot, "main.cpp"), projectRoot.absolutePath))

        assertThat(prepared.desiredCppStandardFlag).isEqualTo("c++20")
        assertThat(prepared.shouldGenerate).isFalse()
    }

    private fun configureToolchain(activeId: String) {
        val toolchainDir = File(context.filesDir, "toolchains/$activeId").apply {
            File(this, "bin").mkdirs()
        }
        File(toolchainDir, "bin/clang").writeText("clang")
        File(toolchainDir, "bin/clang++").writeText("clang++")
        File(context.filesDir, "toolchain-config.json").parentFile?.mkdirs()
        com.wuxianggujun.tinaide.core.ndk.ToolchainConfigManager(context).saveConfig(
            InstalledToolchainConfig(
                activeToolchain = activeId,
                toolchains = listOf(
                    ToolchainInfo(
                        id = activeId,
                        name = activeId,
                        version = "1",
                        type = ToolchainType.CUSTOM,
                        path = "toolchains/$activeId",
                        installedAt = 1L,
                    )
                )
            )
        )
    }

    private fun configureSysroot(activeId: String) {
        val arch = AndroidSysrootManager.Companion.Arch.ARM64
        val profileDir = File(context.filesDir, "android-sysroots/$activeId")
        File(profileDir, "usr/include/android").apply { mkdirs() }
            .resolve("api-level.h")
            .writeText("#define __ANDROID_API__ 28\n")
        val apiLibDir = File(profileDir, "usr/lib/${arch.triple}/28").apply { mkdirs() }
        File(apiLibDir, "libc++.a").writeText("INPUT(-lc++_static -lc++abi)\n")
        val libDir = File(profileDir, "usr/lib/${arch.triple}").apply { mkdirs() }
        File(libDir, "libc++_shared.so").writeText("runtime")
        File(libDir, "libc++_static.a").writeText("runtime")
        File(libDir, "libc++abi.a").writeText("runtime")
        com.wuxianggujun.tinaide.core.ndk.SysrootProfileConfigManager(context).saveConfig(
            InstalledSysrootProfileConfig(
                activeProfiles = mapOf(arch.name to activeId),
                profiles = listOf(
                    SysrootProfileInfo(
                        id = activeId,
                        name = activeId,
                        arch = arch.name,
                        type = SysrootProfileType.CUSTOM,
                        path = "android-sysroots/$activeId",
                        installedAt = 1L,
                        apiLevels = listOf(28),
                        toolchainTriple = arch.triple,
                    )
                )
            )
        )
    }

    private fun writeCompileCommandsWithMetadata(
        toolchainId: String,
        sysrootProfileId: String,
        sysrootApiLevel: Int,
        cppStandardFlag: String = ProjectCppStandardResolver.DEFAULT_FLAG,
        generatedBy: String = "tina-fallback",
    ) {
        val buildDir = File(projectRoot, "build").apply { mkdirs() }
        val compileCommandsFile = File(buildDir, "compile_commands.json")
        compileCommandsFile.writeText(
            """
            [{"directory":"${projectRoot.absolutePath.replace("\\", "\\\\")}","arguments":["clang++","-std=$cppStandardFlag","main.cpp"],"file":"main.cpp"}]
            """.trimIndent()
        )
        val props = Properties().apply {
            setProperty("cppStandard", cppStandardFlag)
            setProperty("contentSha256", sha256Hex(compileCommandsFile.readBytes()))
            setProperty("packageFingerprint", provider.computePackageFingerprint(projectRoot))
            setProperty("toolchainId", toolchainId)
            setProperty("sysrootProfileId", sysrootProfileId)
            setProperty("sysrootApiLevel", sysrootApiLevel.toString())
            setProperty("generatedBy", generatedBy)
        }
        File(buildDir, "compile_commands.tina.meta.properties").outputStream().use { output ->
            props.store(output, "test")
        }
    }

    private fun writeCMakeLists(cppStandard: CppStandard) {
        File(projectRoot, "CMakeLists.txt").writeText(
            "set(CMAKE_CXX_STANDARD ${cppStandard.cmakeValue})\n",
            Charsets.UTF_8,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(((byte.toInt() ushr 4) and 0xF).toString(16))
                append((byte.toInt() and 0xF).toString(16))
            }
        }
    }
}
