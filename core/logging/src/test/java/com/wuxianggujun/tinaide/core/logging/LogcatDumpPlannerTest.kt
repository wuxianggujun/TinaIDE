package com.wuxianggujun.tinaide.core.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogcatDumpPlannerTest {
    @Test
    fun `normalizeTargets keeps tina processes and merges duplicate pids`() {
        val packageName = "com.wuxianggujun.tinaide"
        val targets = LogcatDumpPlanner.normalizeTargets(
            packageName = packageName,
            targets = listOf(
                LogcatDumpTarget(
                    pid = 10,
                    processName = packageName,
                    source = "current"
                ),
                LogcatDumpTarget(
                    pid = 10,
                    processName = packageName,
                    source = "running"
                ),
                LogcatDumpTarget(
                    pid = 20,
                    processName = "$packageName:sdl",
                    source = "recent-runtime"
                ),
                LogcatDumpTarget(
                    pid = 30,
                    processName = "com.other.app",
                    source = "recent-runtime"
                ),
                LogcatDumpTarget(
                    pid = 40,
                    processName = packageName,
                    source = "recent-runtime"
                )
            )
        )

        assertThat(targets.map { it.pid }).containsExactly(10, 20).inOrder()
        assertThat(targets.first { it.pid == 10 }.source).isEqualTo("current+running")
        assertThat(targets.first { it.pid == 20 }.isUserRuntime).isTrue()
    }

    @Test
    fun `buildCommands filters runtime process tags without global logcat`() {
        val commands = LogcatDumpPlanner.buildCommands(
            targets = listOf(
                LogcatDumpTarget(
                    pid = 10,
                    processName = "com.wuxianggujun.tinaide",
                    source = "current"
                ),
                LogcatDumpTarget(
                    pid = 20,
                    processName = "com.wuxianggujun.tinaide:sdl",
                    source = "recent-runtime",
                    isUserRuntime = true
                )
            ),
            tailLines = 250
        )

        assertThat(commands).hasSize(2)
        assertThat(commands[0].command).containsExactly(
            "logcat",
            "-d",
            "--pid=10",
            "-t",
            "250",
            "-v",
            "threadtime"
        ).inOrder()
        assertThat(commands[1].command).containsAtLeast(
            "--pid=20",
            "-s",
            "TINA_USER_OUTPUT:*",
            "SDL:*",
            "TINA_SDL_HOST:*"
        )
    }
}
