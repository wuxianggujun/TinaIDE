package com.wuxianggujun.tinaide.core.ndk

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AndroidNativeToolchainSmokeTest {

    @Test
    fun toolchain_canRunAndCompileHello() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AndroidNativeToolchainManager(context)
        val sysrootManager = AndroidSysrootManager(context)

        val spec = manager.readAssetSpec()
        assumeTrue("custom toolchain assets not bundled for this variant", spec != null)

        if (!manager.isInstalledForCurrentAssets()) {
            manager.install().getOrThrow()
        }
        if (!sysrootManager.isInstalled()) {
            sysrootManager.install().getOrThrow()
        }
        assertTrue("toolchain should be installed", manager.isInstalledForCurrentAssets())
        assertTrue("sysroot should be installed", sysrootManager.isInstalled())

        val binDir = manager.getBinDir()
        val toolchainDir = manager.getInstallDir()
        assertTrue("bin dir should exist: ${binDir.absolutePath}", binDir.isDirectory)
        assertTrue("toolchain dir should exist: ${toolchainDir.absolutePath}", toolchainDir.isDirectory)

        val sysroot = sysrootManager.getSysrootDir()
        assertTrue("sysroot should exist: ${sysroot.absolutePath}", sysroot.isDirectory)

        val archTriple = AndroidSysrootManager.Companion.Arch.current().triple
        val sysrootLibDir = File(sysroot, "usr/lib/$archTriple")
        assertTrue("sysroot lib dir should exist: ${sysrootLibDir.absolutePath}", sysrootLibDir.isDirectory)

        val availableApiLevels = sysrootLibDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { it.name.toIntOrNull() }
            .sorted()
        assertTrue("sysroot should contain API level dirs under: ${sysrootLibDir.absolutePath}", availableApiLevels.isNotEmpty())
        val apiLevel = availableApiLevels.lastOrNull { it <= Build.VERSION.SDK_INT } ?: availableApiLevels.last()

        val env = mapOf(
            "PATH" to "${binDir.absolutePath}:${System.getenv("PATH").orEmpty()}",
            "LD_LIBRARY_PATH" to sysrootLibDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "HOME" to context.filesDir.absolutePath,
        )

        // AndroidElfExecutor 已统一注入 tina-exec，这里的 smoke test 同时覆盖
        // direct/linker64 启动与 clang 派生子进程的 exec 链路。
        val clang = File(binDir, "clang")
        val clangVersion = AndroidElfExecutor.exec(context, clang, listOf("--version"), env = env)
        assertEquals("clang --version exitCode", 0, clangVersion.exitCode)
        assertTrue("clang version output should contain 'clang version'", clangVersion.output.contains("clang version"))

        if (spec!!.toolsTarXz != null) {
            val cmake = File(binDir, "cmake")
            val cmakeVersion = AndroidElfExecutor.exec(context, cmake, listOf("--version"), env = env)
            assertEquals("cmake --version exitCode", 0, cmakeVersion.exitCode)
            assertTrue("cmake version output should contain 'cmake version'", cmakeVersion.output.contains("cmake version"))

            val ninja = File(binDir, "ninja")
            val ninjaVersion = AndroidElfExecutor.exec(context, ninja, listOf("--version"), env = env)
            assertEquals("ninja --version exitCode", 0, ninjaVersion.exitCode)

            val pkgConfig = File(binDir, "pkg-config")
            val pkgConfigVersion = AndroidElfExecutor.exec(context, pkgConfig, listOf("--version"), env = env)
            assertEquals("pkg-config --version exitCode", 0, pkgConfigVersion.exitCode)
        }

        val workDir = File(context.cacheDir, "toolchain-smoke").apply { mkdirs() }
        val helloC = File(workDir, "hello.c").apply {
            writeText(
                """
                #include <stdio.h>
                int main() { puts("OK"); return 0; }
                """.trimIndent(),
                Charsets.UTF_8
            )
        }
        val out = File(workDir, "hello")

        val compileArgs = listOf(
            "--target=${archTriple}$apiLevel",
            "--sysroot=${sysroot.absolutePath}",
            "-fuse-ld=lld",
            helloC.absolutePath,
            "-o",
            out.absolutePath
        )

        val compile = AndroidElfExecutor.exec(
            context = context,
            executable = clang,
            args = compileArgs,
            env = env,
            workDir = workDir,
            timeoutMs = 240_000L
        )
        assertEquals("hello.c compile exitCode", 0, compile.exitCode)
        assertTrue("output binary should exist: ${out.absolutePath}", out.isFile)

        val run = AndroidElfExecutor.exec(
            context = context,
            executable = out,
            args = emptyList(),
            env = env,
            workDir = workDir
        )
        assertEquals("hello run exitCode", 0, run.exitCode)
        assertTrue("hello output should contain OK", run.output.contains("OK"))

        val helloCpp = File(workDir, "hello.cpp").apply {
            writeText(
                """
                #include <iostream>
                int main() { std::cout << "OK_CPP" << std::endl; return 0; }
                """.trimIndent(),
                Charsets.UTF_8
            )
        }
        val outCpp = File(workDir, "hello_cpp")
        val clangxx = File(binDir, "clang++")
        val compileCpp = AndroidElfExecutor.exec(
            context = context,
            executable = clangxx,
            args = listOf(
                "--target=${archTriple}$apiLevel",
                "--sysroot=${sysroot.absolutePath}",
                "-fuse-ld=lld",
                helloCpp.absolutePath,
                "-o",
                outCpp.absolutePath
            ),
            env = env,
            workDir = workDir,
            timeoutMs = 240_000L
        )
        assertEquals("hello.cpp compile exitCode", 0, compileCpp.exitCode)
        assertTrue("output C++ binary should exist: ${outCpp.absolutePath}", outCpp.isFile)

        val runCpp = AndroidElfExecutor.exec(
            context = context,
            executable = outCpp,
            args = emptyList(),
            env = env,
            workDir = workDir
        )
        assertEquals("hello_cpp run exitCode", 0, runCpp.exitCode)
        assertTrue("hello_cpp output should contain OK_CPP", runCpp.output.contains("OK_CPP"))
    }
}
