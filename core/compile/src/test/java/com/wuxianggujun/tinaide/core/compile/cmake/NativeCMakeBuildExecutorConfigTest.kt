package com.wuxianggujun.tinaide.core.compile.cmake

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Test

class NativeCMakeBuildExecutorConfigTest {

    @Test
    fun `resolveConfiguredCompilerPath falls back for blank value`() {
        val resolved = NativeCMakeBuildExecutor.resolveConfiguredCompilerPath(
            configuredPath = "   ",
            fallbackPath = "/data/user/0/com.example/files/toolchains/builtin/bin/clang"
        )

        assertThat(resolved)
            .isEqualTo("/data/user/0/com.example/files/toolchains/builtin/bin/clang")
    }

    @Test
    fun `shouldPatchNinjaFile only reacts to cmake paths`() {
        val content = "command = /data/data/com.example/files/toolchains/builtin/bin/cmake -E rm -f libdemo.a"

        assertThat(
            NativeCMakeBuildExecutor.shouldPatchNinjaFile(
                content = content,
                cmakePaths = setOf("/data/data/com.example/files/toolchains/builtin/bin/cmake"),
                shimScriptPaths = emptySet()
            )
        ).isTrue()
        assertThat(
            NativeCMakeBuildExecutor.shouldPatchNinjaFile(
                content = content,
                cmakePaths = setOf("/data/data/com.example/files/toolchains/builtin/bin/ninja"),
                shimScriptPaths = emptySet()
            )
        ).isFalse()
    }

    @Test
    fun `resolveCMakeCompilerCommand prefers shim compiler when available`() {
        val command = NativeCMakeBuildExecutor.resolveCMakeCompilerCommand(
            realCompilerPath = "/data/user/0/com.example/files/toolchains/builtin/bin/clang++",
            shimCompilerPath = "/data/user/0/com.example/files/toolchain-shims/abcd/bin/clang++",
            preferLinker64 = true,
            linker64Path = "/system/bin/linker64"
        )

        assertThat(command.compiler)
            .isEqualTo("/system/bin/sh")
        assertThat(command.arg1)
            .isEqualTo("/data/user/0/com.example/files/toolchain-shims/abcd/bin/clang++")
    }

    @Test
    fun `resolveCMakeCompilerCommand keeps real compiler when linker64 is disabled`() {
        val command = NativeCMakeBuildExecutor.resolveCMakeCompilerCommand(
            realCompilerPath = "/data/user/0/com.example/files/toolchains/builtin/bin/clang",
            shimCompilerPath = "/data/user/0/com.example/files/toolchain-shims/abcd/bin/clang",
            preferLinker64 = false,
            linker64Path = "/system/bin/linker64"
        )

        assertThat(command.compiler)
            .isEqualTo("/data/user/0/com.example/files/toolchains/builtin/bin/clang")
        assertThat(command.arg1).isNull()
    }

    @Test
    fun `resolveCMakeCompilerCommand falls back to linker64 when shim is unavailable`() {
        val command = NativeCMakeBuildExecutor.resolveCMakeCompilerCommand(
            realCompilerPath = "/data/user/0/com.example/files/toolchains/builtin/bin/clang++",
            shimCompilerPath = null,
            preferLinker64 = true,
            linker64Path = "/system/bin/linker64"
        )

        assertThat(command.compiler).isEqualTo("/system/bin/linker64")
        assertThat(command.arg1)
            .isEqualTo("/data/user/0/com.example/files/toolchains/builtin/bin/clang++")
    }

    @Test
    fun `resolveCMakeCompilerCommand still prefers shim compiler when tina exec is enabled`() {
        val command = NativeCMakeBuildExecutor.resolveCMakeCompilerCommand(
            realCompilerPath = "/data/user/0/com.example/files/toolchains/builtin/bin/clang++",
            shimCompilerPath = "/data/user/0/com.example/files/toolchain-shims/abcd/bin/clang++",
            preferLinker64 = true,
            linker64Path = "/system/bin/linker64",
            useRecommendedTinaExec = true
        )

        assertThat(command.compiler)
            .isEqualTo("/system/bin/sh")
        assertThat(command.arg1)
            .isEqualTo("/data/user/0/com.example/files/toolchain-shims/abcd/bin/clang++")
    }

    @Test
    fun `resolveCMakeToolPath prefers shim path for archive tools in linker64 mode`() {
        val toolchainBinDir = createTempDirectory(prefix = "cmake-tools-").toFile()
        try {
            File(toolchainBinDir, "llvm-ar").writeText("")
            val shimSet = com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager.ShimSet(
                rootDir = File("/tmp/shims"),
                shimDir = File("/tmp/shims/bin"),
                toolMap = mapOf("llvm-ar" to File("/tmp/shims/bin/llvm-ar"))
            )

            val toolPath = NativeCMakeBuildExecutor.resolveCMakeToolPath(
                toolchainBinDir = toolchainBinDir,
                shimSet = shimSet,
                candidates = listOf("llvm-ar", "ar"),
                preferLinker64 = true
            )

            assertThat(toolPath).isEqualTo(shimSet.shimPath("llvm-ar"))
        } finally {
            toolchainBinDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveCMakeToolPath keeps real archive tool when linker64 is disabled`() {
        val toolchainBinDir = createTempDirectory(prefix = "cmake-tools-").toFile()
        try {
            val realAr = File(toolchainBinDir, "llvm-ar").apply { writeText("") }
            val shimSet = com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager.ShimSet(
                rootDir = File("/tmp/shims"),
                shimDir = File("/tmp/shims/bin"),
                toolMap = mapOf("llvm-ar" to File("/tmp/shims/bin/llvm-ar"))
            )

            val toolPath = NativeCMakeBuildExecutor.resolveCMakeToolPath(
                toolchainBinDir = toolchainBinDir,
                shimSet = shimSet,
                candidates = listOf("llvm-ar", "ar"),
                preferLinker64 = false
            )

            assertThat(toolPath).isEqualTo(realAr.absolutePath)
        } finally {
            toolchainBinDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveBuildToolProgram keeps real ninja for CMake make program in linker64 mode`() {
        val toolchainBinDir = createTempDirectory(prefix = "cmake-build-tool-").toFile()
        val shimRoot = createTempDirectory(prefix = "cmake-build-tool-shim-").toFile()
        try {
            val realNinja = File(toolchainBinDir, "ninja").apply { writeText("real ninja") }
            val shimFile = File(shimRoot, "bin/ninja").apply {
                parentFile?.mkdirs()
                writeText("#!/system/bin/sh\nexit 0\n")
                setExecutable(true, true)
            }
            val shimSet = com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager.ShimSet(
                rootDir = shimRoot,
                shimDir = shimFile.parentFile!!,
                toolMap = mapOf("ninja" to shimFile)
            )

            val program = NativeCMakeBuildExecutor.resolveBuildToolProgram(
                toolName = "ninja",
                realBinary = realNinja,
                shimSet = shimSet,
                preferLinker64 = true
            )

            assertThat(program?.absolutePath).isEqualTo(realNinja.absolutePath)
        } finally {
            toolchainBinDir.deleteRecursively()
            shimRoot.deleteRecursively()
        }
    }

    @Test
    fun `resolveBuildToolProgram prefers shell shim when linker64 is disabled`() {
        val toolchainBinDir = createTempDirectory(prefix = "cmake-build-tool-").toFile()
        val shimRoot = createTempDirectory(prefix = "cmake-build-tool-shim-").toFile()
        try {
            val realNinja = File(toolchainBinDir, "ninja").apply { writeText("real ninja") }
            val shimFile = File(shimRoot, "bin/ninja").apply {
                parentFile?.mkdirs()
                writeText("#!/system/bin/sh\nexit 0\n")
                setExecutable(true, true)
            }
            val shimSet = com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager.ShimSet(
                rootDir = shimRoot,
                shimDir = shimFile.parentFile!!,
                toolMap = mapOf("ninja" to shimFile)
            )

            val program = NativeCMakeBuildExecutor.resolveBuildToolProgram(
                toolName = "ninja",
                realBinary = realNinja,
                shimSet = shimSet,
                preferLinker64 = false
            )

            assertThat(program?.absolutePath).isEqualTo(shimFile.absolutePath)
        } finally {
            toolchainBinDir.deleteRecursively()
            shimRoot.deleteRecursively()
        }
    }

    @Test
    fun `buildCMakeBuildCommand uses direct ninja for ninja generator`() {
        val toolchainBinDir = createTempDirectory(prefix = "cmake-build-command-toolchain-").toFile()
        val buildDir = createTempDirectory(prefix = "cmake-build-command-build-").toFile()
        try {
            val command = NativeCMakeBuildExecutor.buildCMakeBuildCommand(
                generator = NativeCMakeBuildExecutor.CMakeGenerator.NINJA,
                toolchainBinDir = toolchainBinDir,
                buildDir = buildDir,
                target = "demo",
                parallelJobs = 2
            )

            assertThat(command).containsExactly(
                File(toolchainBinDir, "ninja").absolutePath,
                "-C",
                buildDir.absolutePath,
                "-j",
                "2",
                "demo"
            ).inOrder()
        } finally {
            toolchainBinDir.deleteRecursively()
            buildDir.deleteRecursively()
        }
    }

    @Test
    fun `buildCMakeBuildCommand keeps cmake build for makefiles`() {
        val toolchainBinDir = createTempDirectory(prefix = "cmake-build-command-toolchain-").toFile()
        val buildDir = createTempDirectory(prefix = "cmake-build-command-build-").toFile()
        try {
            val command = NativeCMakeBuildExecutor.buildCMakeBuildCommand(
                generator = NativeCMakeBuildExecutor.CMakeGenerator.UNIX_MAKEFILES,
                toolchainBinDir = toolchainBinDir,
                buildDir = buildDir,
                target = "   ",
                parallelJobs = 0
            )

            assertThat(command).containsExactly(
                File(toolchainBinDir, "cmake").absolutePath,
                "--build",
                buildDir.absolutePath,
                "--parallel",
                "1"
            ).inOrder()
        } finally {
            toolchainBinDir.deleteRecursively()
            buildDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveBuildToolProgramSource reports shim and real cases`() {
        val toolchainBinDir = createTempDirectory(prefix = "cmake-build-tool-source-").toFile()
        val shimRoot = createTempDirectory(prefix = "cmake-build-tool-source-shim-").toFile()
        try {
            val realNinja = File(toolchainBinDir, "ninja").apply { writeText("real ninja") }
            val shimFile = File(shimRoot, "bin/ninja").apply {
                parentFile?.mkdirs()
                writeText("#!/system/bin/sh\nexit 0\n")
                setExecutable(true, true)
            }
            val shimSet = com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager.ShimSet(
                rootDir = shimRoot,
                shimDir = shimFile.parentFile!!,
                toolMap = mapOf("ninja" to shimFile)
            )

            assertThat(
                NativeCMakeBuildExecutor.resolveBuildToolProgramSource(
                    toolName = "ninja",
                    realBinary = realNinja,
                    selectedProgram = shimFile,
                    shimSet = shimSet,
                    preferLinker64 = true
                )
            ).isEqualTo("shim")

            assertThat(
                NativeCMakeBuildExecutor.resolveBuildToolProgramSource(
                    toolName = "ninja",
                    realBinary = realNinja,
                    selectedProgram = realNinja,
                    shimSet = shimSet,
                    preferLinker64 = true
                )
            ).isEqualTo("real")

            assertThat(
                NativeCMakeBuildExecutor.resolveBuildToolProgramSource(
                    toolName = "ninja",
                    realBinary = realNinja,
                    selectedProgram = realNinja,
                    shimSet = shimSet,
                    preferLinker64 = false
                )
            ).isEqualTo("real")
        } finally {
            toolchainBinDir.deleteRecursively()
            shimRoot.deleteRecursively()
        }
    }

    @Test
    fun `addBinaryCommandMapping does not let shared canonical path overwrite archive tool`() {
        val mappings = linkedMapOf<String, String>()
        val arCommand = "/system/bin/sh /tmp/shims/bin/llvm-ar"
        val ranlibCommand = "/system/bin/sh /tmp/shims/bin/llvm-ranlib"
        val arPath = "/data/user/0/com.example/files/toolchains/builtin/bin/llvm-ar"
        val ranlibPath = "/data/user/0/com.example/files/toolchains/builtin/bin/llvm-ranlib"
        val sharedCanonicalPath = "/data/data/com.example/files/toolchains/builtin/bin/llvm-ar"

        NativeCMakeBuildExecutor.addBinaryCommandMapping(
            mappings = mappings,
            tool = "llvm-ar",
            realBinaryPath = arPath,
            commandString = arCommand,
            canonicalPath = sharedCanonicalPath
        )
        NativeCMakeBuildExecutor.addBinaryCommandMapping(
            mappings = mappings,
            tool = "llvm-ranlib",
            realBinaryPath = ranlibPath,
            commandString = ranlibCommand,
            canonicalPath = sharedCanonicalPath
        )

        assertThat(mappings[arPath]).isEqualTo(arCommand)
        assertThat(mappings[sharedCanonicalPath]).isEqualTo(arCommand)
        assertThat(mappings[ranlibPath]).isEqualTo(ranlibCommand)
        assertThat(
            mappings["/data/data/com.example/files/toolchains/builtin/bin/llvm-ranlib"]
        ).isEqualTo(ranlibCommand)
    }

    @Test
    fun `addBinaryCommandMapping keeps same-name canonical alias for exact tool`() {
        val mappings = linkedMapOf<String, String>()
        val command = "/system/bin/sh /tmp/shims/bin/llvm-ar"
        val realBinaryPath = "/data/user/0/com.example/files/toolchains/builtin/bin/llvm-ar"
        val canonicalPath = "/apex/com.android.runtime/bin/llvm-ar"

        NativeCMakeBuildExecutor.addBinaryCommandMapping(
            mappings = mappings,
            tool = "llvm-ar",
            realBinaryPath = realBinaryPath,
            commandString = command,
            canonicalPath = canonicalPath
        )

        assertThat(mappings[realBinaryPath]).isEqualTo(command)
        assertThat(mappings[canonicalPath]).isEqualTo(command)
    }

    @Test
    fun `buildCompilerExecutionFlags enables integrated cc1 for clang in linker64 mode`() {
        assertThat(
            NativeCMakeBuildExecutor.buildCompilerExecutionFlags(
                compilerType = CompilerType.CLANG,
                preferLinker64 = true
            )
        ).containsExactly("-fintegrated-cc1")

        assertThat(
            NativeCMakeBuildExecutor.buildCompilerExecutionFlags(
                compilerType = CompilerType.CLANG,
                preferLinker64 = false
            )
        ).isEmpty()
    }

    @Test
    fun `buildAndroidCompilerCacheHints seeds abi and pointer size for arm64`() {
        val hints = NativeCMakeBuildExecutor.buildAndroidCompilerCacheHints(
            AndroidSysrootManager.Companion.Arch.ARM64
        )

        assertThat(hints["CMAKE_CXX_ABI_COMPILED"]).isEqualTo("TRUE")
        assertThat(hints["CMAKE_CXX_COMPILER_WORKS"]).isEqualTo("TRUE")
        assertThat(hints["CMAKE_CXX_COMPILER_ABI"]).isEqualTo("ELF")
        assertThat(hints["CMAKE_CXX_BYTE_ORDER"]).isEqualTo("LITTLE_ENDIAN")
        assertThat(hints["CMAKE_CXX_SIZEOF_DATA_PTR"]).isEqualTo("8")
        assertThat(hints["CMAKE_CXX_LIBRARY_ARCHITECTURE"]).isEqualTo("aarch64-linux-android")
        assertThat(hints["CMAKE_TRY_COMPILE_TARGET_TYPE"]).isEqualTo("STATIC_LIBRARY")
    }

    @Test
    fun `buildCompilePackageEnvironment excludes runtime library path`() {
        val env = NativeCMakeBuildExecutor.buildCompilePackageEnvironment(
            InstalledPackagePathResolver.PackagePaths(
                includeDirs = listOf(File("/pkg/include")),
                libDirs = listOf(File("/pkg/lib")),
                prefixDirs = emptyList(),
                pkgConfigDirs = listOf(File("/pkg/lib/pkgconfig")),
                linkLibraries = listOf("SDL3"),
                runtimeLibDirs = listOf(File("/pkg/runtime"))
            )
        )

        assertThat(env["CPATH"]).contains("pkg")
        assertThat(env["CPATH"]).contains("include")
        assertThat(env["LIBRARY_PATH"]).contains("pkg")
        assertThat(env["LIBRARY_PATH"]).contains("lib")
        assertThat(env["PKG_CONFIG_PATH"]).contains("pkg")
        assertThat(env["PKG_CONFIG_PATH"]).contains("pkgconfig")
        assertThat(env).doesNotContainKey("LD_LIBRARY_PATH")
    }

    @Test
    fun `buildCMakePackageRootArguments mirrors package prefixes into find root path`() {
        val sdlPrefix = File("/pkg/sdl3")
        val box2dPrefix = File("/pkg/box2d")
        val packagePrefixPath = listOf(sdlPrefix, box2dPrefix).joinToString(";") { it.absolutePath }

        val args = NativeCMakeBuildExecutor.buildCMakePackageRootArguments(
            InstalledPackagePathResolver.PackagePaths(
                includeDirs = emptyList(),
                libDirs = emptyList(),
                prefixDirs = listOf(sdlPrefix, box2dPrefix),
                pkgConfigDirs = emptyList(),
                linkLibraries = emptyList(),
                runtimeLibDirs = emptyList()
            )
        )

        assertThat(args).containsExactly(
            "-DCMAKE_PREFIX_PATH=$packagePrefixPath",
            "-DCMAKE_FIND_ROOT_PATH=$packagePrefixPath"
        ).inOrder()

        assertThat(
            NativeCMakeBuildExecutor.buildCMakePackageRootArguments(
                InstalledPackagePathResolver.PackagePaths(
                    includeDirs = emptyList(),
                    libDirs = emptyList(),
                    prefixDirs = emptyList(),
                    pkgConfigDirs = emptyList(),
                    linkLibraries = emptyList(),
                    runtimeLibDirs = emptyList()
                )
            )
        ).isEmpty()
    }

    @Test
    fun `buildAndroidAbiCMakeArgument exposes package ABI to CMake config files`() {
        assertThat(
            NativeCMakeBuildExecutor.buildAndroidAbiCMakeArgument(" arm64-v8a ")
        ).isEqualTo("-DANDROID_ABI=arm64-v8a")

        assertThat(
            NativeCMakeBuildExecutor.buildAndroidAbiCMakeArgument("x86_64")
        ).isEqualTo("-DANDROID_ABI=x86_64")
    }

    @Test
    fun `buildCMakeExtraEnvironment adds shim trace flag when enabled`() {
        val env = NativeCMakeBuildExecutor.buildCMakeExtraEnvironment(
            packageEnvironment = mapOf("CPATH" to "/pkg/include"),
            traceToolchainShim = true
        )

        assertThat(env["CPATH"]).isEqualTo("/pkg/include")
        assertThat(env[NativeCMakeBuildExecutor.ENV_TOOLCHAIN_SHIM_TRACE]).isEqualTo("1")
    }

    @Test
    fun `shouldDisableTinaExecForCMakeBuild disables linker64 ninja shell shim path`() {
        assertThat(
            NativeCMakeBuildExecutor.shouldDisableTinaExecForCMakeBuild(
                generator = NativeCMakeBuildExecutor.CMakeGenerator.NINJA,
                preferLinker64 = true,
                useRecommendedTinaExec = true
            )
        ).isTrue()

        assertThat(
            NativeCMakeBuildExecutor.shouldDisableTinaExecForCMakeBuild(
                generator = NativeCMakeBuildExecutor.CMakeGenerator.UNIX_MAKEFILES,
                preferLinker64 = true,
                useRecommendedTinaExec = true
            )
        ).isFalse()

        assertThat(
            NativeCMakeBuildExecutor.shouldDisableTinaExecForCMakeBuild(
                generator = NativeCMakeBuildExecutor.CMakeGenerator.NINJA,
                preferLinker64 = false,
                useRecommendedTinaExec = true
            )
        ).isFalse()
    }

    @Test
    fun `shouldRetryWithoutTinaExecAfterShimFailure detects silent shim compiler failure`() {
        val output = """
            [1/3] Building CXX object CMakeFiles/demo.dir/src/main.cpp.o
            FAILED: CMakeFiles/demo.dir/src/main.cpp.o
            /system/bin/sh /data/user/0/com.example/files/toolchain-shims/abcd/bin/clang++ --target=aarch64-linux-android28 -c src/main.cpp
            ninja: build stopped: subcommand failed.
        """.trimIndent()

        assertThat(
            NativeCMakeBuildExecutor.shouldRetryWithoutTinaExecAfterShimFailure(
                output = output,
                useRecommendedTinaExec = true
            )
        ).isTrue()
    }

    @Test
    fun `shouldRetryWithoutTinaExecAfterShimFailure skips concrete compiler diagnostics`() {
        val output = """
            FAILED: CMakeFiles/demo.dir/src/main.cpp.o
            /system/bin/sh /data/user/0/com.example/files/toolchain-shims/abcd/bin/clang++ -c src/main.cpp
            src/main.cpp:1:10: fatal error: 'missing.h' file not found
            ninja: build stopped: subcommand failed.
        """.trimIndent()

        assertThat(
            NativeCMakeBuildExecutor.shouldRetryWithoutTinaExecAfterShimFailure(
                output = output,
                useRecommendedTinaExec = true
            )
        ).isFalse()
    }

    @Test
    fun `clearTinaExecEnvironment removes inherited tina exec variables only`() {
        val env = linkedMapOf(
            "LD_PRELOAD" to "/data/app/libtina_exec_linker_ld_preload.so",
            "TINA_EXEC__SYSTEM_LINKER_EXEC__MODE" to "ENABLE",
            "TINA_EXEC__PROC_SELF_EXE" to "/apex/com.android.runtime/bin/linker64",
            "TINA_APP__DATA_DIR" to "/data/user/0/com.example",
            "TINA_APP__LEGACY_DATA_DIR" to "/data/data/com.example",
            "TINA_ROOTFS" to "/data/user/0/com.example/rootfs",
            "TINA_PREFIX" to "/data/user/0/com.example/usr",
            "PATH" to "/system/bin"
        )

        NativeCMakeBuildExecutor.clearTinaExecEnvironment(env)

        assertThat(env).doesNotContainKey("LD_PRELOAD")
        assertThat(env).doesNotContainKey("TINA_EXEC__SYSTEM_LINKER_EXEC__MODE")
        assertThat(env).doesNotContainKey("TINA_EXEC__PROC_SELF_EXE")
        assertThat(env).doesNotContainKey("TINA_APP__DATA_DIR")
        assertThat(env).doesNotContainKey("TINA_APP__LEGACY_DATA_DIR")
        assertThat(env).doesNotContainKey("TINA_ROOTFS")
        assertThat(env).doesNotContainKey("TINA_PREFIX")
        assertThat(env["PATH"]).isEqualTo("/system/bin")
    }

    @Test
    fun `appendCommandOutputLine forwards native cmake progress line to callback`() {
        val output = StringBuilder()
        val progressLines = mutableListOf<String>()

        NativeCMakeBuildExecutor.appendCommandOutputLine(
            output = output,
            line = "[1/9] Building CXX object CMakeFiles/imgui.dir/src/imgui_impl_android.cpp.o",
            onOutputLine = progressLines::add
        )

        assertThat(progressLines).containsExactly(
            "[1/9] Building CXX object CMakeFiles/imgui.dir/src/imgui_impl_android.cpp.o"
        )
        assertThat(output.toString()).contains(
            "[1/9] Building CXX object CMakeFiles/imgui.dir/src/imgui_impl_android.cpp.o"
        )
    }

    @Test
    fun `waitForProcessExit returns process exit code when command finishes in time`() {
        val process = FakeProcess(
            exitCode = 7,
            waitResults = ArrayDeque(listOf(true))
        )

        val result = NativeCMakeBuildExecutor.waitForProcessExit(
            process = process,
            timeoutMs = 100
        )

        assertThat(result.finished).isTrue()
        assertThat(result.exitCode).isEqualTo(7)
        assertThat(process.destroyCalled).isFalse()
        assertThat(process.destroyForciblyCalled).isFalse()
    }

    @Test
    fun `waitForProcessExit destroys forcibly when process ignores graceful timeout`() {
        val process = FakeProcess(
            exitCode = 0,
            waitResults = ArrayDeque(listOf(false, false, true))
        )

        val result = NativeCMakeBuildExecutor.waitForProcessExit(
            process = process,
            timeoutMs = 100,
            forceKillGraceMs = 10
        )

        assertThat(result.finished).isFalse()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(process.destroyCalled).isTrue()
        assertThat(process.destroyForciblyCalled).isTrue()
    }

    @Test
    fun `buildConfigureFailureMessage appends cmake diagnostic files`() {
        val buildDir = createTempDirectory(prefix = "cmake-config-failure-").toFile()
        try {
            val cmakeFilesDir = File(buildDir, "CMakeFiles").apply { mkdirs() }
            File(cmakeFilesDir, "CMakeError.log").writeText("clang++: error: missing header")
            File(cmakeFilesDir, "CMakeOutput.log").writeText("Detecting CXX compiler ABI info")

            val message = NativeCMakeBuildExecutor.buildConfigureFailureMessage(
                primaryOutput = "ninja: build stopped",
                buildDir = buildDir,
                exitCode = 1
            )

            assertThat(message).contains("CMake configure failed with exitCode=1")
            assertThat(message).contains("ninja: build stopped")
            assertThat(message).contains("===== CMakeError.log =====")
            assertThat(message).contains("clang++: error: missing header")
            assertThat(message).contains("===== CMakeOutput.log =====")
        } finally {
            buildDir.deleteRecursively()
        }
    }

    @Test
    fun `buildConfigureFailureSummary captures build tool scratch and helper failure`() {
        val buildDir = createTempDirectory(prefix = "cmake-config-summary-").toFile()
        try {
            val cmakeFilesDir = File(buildDir, "CMakeFiles").apply { mkdirs() }
            val scratchDir = File(cmakeFilesDir, "CMakeScratch/TryCompile-demo").apply { mkdirs() }
            File(cmakeFilesDir, "CMakeConfigureLog.yaml").writeText(
                """
                CMake Error:
                  The detected version of Ninja (This is /system/bin/linker64, the helper
                  program for dynamic executables.) is less than the version of Ninja required by CMake (1.3).
                """.trimIndent()
            )
            File(scratchDir, "build.ninja").writeText("rule CXX_COMPILER__test")
            val realBuildTool = File("/toolchain/bin/ninja")
            val selectedBuildTool = File("/toolchain-shims/bin/ninja")

            val summary = NativeCMakeBuildExecutor.buildConfigureFailureSummary(
                primaryOutput = "ninja: build stopped: subcommand failed.",
                buildDir = buildDir,
                exitCode = 1,
                generator = "Ninja",
                buildToolName = "ninja",
                buildToolSource = "shim",
                buildToolReal = realBuildTool,
                buildToolSelected = selectedBuildTool,
                buildToolShim = "/toolchain-shims/bin/ninja",
                preferLinker64 = true
            )

            assertThat(summary).contains("generator=Ninja")
            assertThat(summary).contains("buildTool=ninja")
            assertThat(summary).contains("buildToolSource=shim")
            assertThat(summary).contains("preferLinker64=true")
            assertThat(summary).contains("buildToolReal=${realBuildTool.absolutePath}")
            assertThat(summary).contains("buildToolSelected=${selectedBuildTool.absolutePath}")
            assertThat(summary).contains("buildToolShim=/toolchain-shims/bin/ninja")
            assertThat(summary).contains("latestScratch=${scratchDir.absolutePath}")
            assertThat(summary).contains("firstFailure=The detected version of Ninja")
        } finally {
            buildDir.deleteRecursively()
        }
    }

    @Test
    fun `buildNativeCommandResultSummary captures command result fields`() {
        val summary = NativeCMakeBuildExecutor.buildNativeCommandResultSummary(
            executable = "/data/user/0/com.example/files/toolchains/builtin/bin/cmake",
            fullCommand = listOf(
                "/system/bin/linker64",
                "/data/user/0/com.example/files/toolchains/builtin/bin/cmake",
                "--version"
            ),
            workingDir = File("/workspace/project"),
            timeoutMs = 60_000,
            durationMs = 123,
            finished = false,
            exitCode = -1,
            output = "\nCMake Error: failed to run ninja\nmore output"
        )

        assertThat(summary).contains("exitCode=-1")
        assertThat(summary).contains("finished=false")
        assertThat(summary).contains("timedOut=true")
        assertThat(summary).contains("durationMs=123")
        assertThat(summary).contains("timeoutMs=60000")
        assertThat(summary).contains("launchMode=linker64")
        assertThat(summary).contains("outputFirstLine=CMake Error: failed to run ninja")
        assertThat(summary).contains("fullCommand=/system/bin/linker64")
    }

    @Test
    fun `build shim prepare summary captures generated and missing tools`() {
        val summary =
            com.wuxianggujun.tinaide.core.compile.toolchain.ToolchainLinker64ShimManager.buildPrepareSummary(
                toolchainBinDir = File("/toolchain/bin"),
                rootDir = File("/files/toolchain-shims/demo"),
                shimDir = File("/files/toolchain-shims/demo/bin"),
                linker64 = "/system/bin/linker64",
                requestedTools = setOf("ninja", "make", "clang"),
                generatedTools = setOf("ninja", "clang")
            )

        assertThat(summary).contains("Toolchain shim prepare:")
        assertThat(summary).contains("linker64=/system/bin/linker64")
        assertThat(summary).contains("requestedTools=clang,make,ninja")
        assertThat(summary).contains("generatedTools=clang,ninja")
        assertThat(summary).contains("missingTools=make")
    }

    private class FakeProcess(
        private val exitCode: Int,
        private val waitResults: ArrayDeque<Boolean>
    ) : Process() {
        var destroyCalled: Boolean = false
            private set
        var destroyForciblyCalled: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = exitCode

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = waitResults.removeFirstOrNull() ?: true

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyCalled = true
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            return this
        }
    }
}
