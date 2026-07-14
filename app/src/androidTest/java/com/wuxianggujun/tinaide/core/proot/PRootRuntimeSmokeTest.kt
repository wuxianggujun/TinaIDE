package com.wuxianggujun.tinaide.core.proot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class PRootRuntimeSmokeTest {

    @Test
    fun proot_shell_canRunAndSpawnChildProcesses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = requireReadyEnvironment(context)

        val result = env.executeGuest(
            command = listOf(
                "/bin/sh",
                "-lc",
                "/bin/ls / >/dev/null && /bin/cat /etc/os-release >/dev/null && printf 'OK_CHILD_CHAIN\\n'"
            ),
            workDir = "/",
            timeout = 30_000L
        )

        assertEquals(
            "proot shell child-chain exitCode; output=${result.combinedOutput}",
            0,
            result.exitCode,
        )
        assertTrue(
            "proot child-chain output should contain OK_CHILD_CHAIN",
            result.combinedOutput.contains("OK_CHILD_CHAIN")
        )
    }

    @Test
    fun proot_guestClang_canRunIfInstalled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = requireReadyEnvironment(context)
        val clangPath = ToolchainPathResolver(context).resolve().clang

        assumeTrue("guest clang not installed: $clangPath", env.isCommandAvailable(clangPath))

        val result = env.executeGuest(
            command = listOf(
                "/bin/sh",
                "-lc",
                "${shellEscape(clangPath)} --version >/dev/null && printf 'OK_GUEST_CLANG\\n'"
            ),
            workDir = "/",
            timeout = 30_000L
        )

        assertEquals("guest clang smoke exitCode", 0, result.exitCode)
        assertTrue(
            "guest clang smoke output should contain OK_GUEST_CLANG",
            result.combinedOutput.contains("OK_GUEST_CLANG")
        )
    }

    @Test
    fun proot_guestCmake_canRunIfInstalled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = requireReadyEnvironment(context)

        assumeTrue("guest cmake not installed", env.isCommandAvailable("cmake"))

        val result = env.executeGuest(
            command = listOf(
                "/bin/sh",
                "-lc",
                "cmake --version >/dev/null && printf 'OK_GUEST_CMAKE\\n'"
            ),
            workDir = "/",
            timeout = 30_000L
        )

        assertEquals("guest cmake smoke exitCode", 0, result.exitCode)
        assertTrue(
            "guest cmake smoke output should contain OK_GUEST_CMAKE",
            result.combinedOutput.contains("OK_GUEST_CMAKE")
        )
    }

    private fun requireReadyEnvironment(context: android.content.Context): PRootEnvironment {
        assumeTrue("proot environment not ready", PRootBootstrap.isEnvironmentReady(context))
        val env = PRootEnvironment(context)
        assumeTrue("proot environment not available", env.isAvailable())
        return env
    }

    private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
