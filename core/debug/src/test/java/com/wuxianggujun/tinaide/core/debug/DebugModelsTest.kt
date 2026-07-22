package com.wuxianggujun.tinaide.core.debug

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DebugModelsTest {

    @Test
    fun sourceLocation_shouldDefaultColumnAndAddress() {
        val location = SourceLocation(
            file = "/project/main.cpp",
            line = 12,
            function = "main",
        )

        assertThat(location.file).isEqualTo("/project/main.cpp")
        assertThat(location.line).isEqualTo(12)
        assertThat(location.column).isEqualTo(0)
        assertThat(location.function).isEqualTo("main")
        assertThat(location.address).isEqualTo(0L)
    }

    @Test
    fun sourceLocation_shouldCarryExplicitAddress() {
        val location = SourceLocation(
            file = "/project/main.cpp",
            line = 12,
            function = "main",
            address = 0x1000,
        )

        assertThat(location.address).isEqualTo(0x1000)
    }

    @Test
    fun pausedState_shouldCarryBreakpointReasonAndLocation() {
        val location = SourceLocation(
            file = "/project/main.cpp",
            line = 12,
            function = "main",
            address = 0x1000,
        )

        val state = DebugState.Paused(
            sessionId = "dbg-1",
            reason = PauseReason.BREAKPOINT,
            location = location,
        )

        assertThat(state.sessionId).isEqualTo("dbg-1")
        assertThat(state.reason).isEqualTo(PauseReason.BREAKPOINT)
        assertThat(state.location).isEqualTo(location)
        assertThat(state.threadId).isEqualTo(0)
    }

    @Test
    fun debugState_shouldCarryTerminationDetails() {
        val state = DebugState.Terminated(
            sessionId = "dbg-1",
            reason = TerminateReason.CRASH,
            exitCode = 139,
            message = "SIGSEGV",
        )

        assertThat(state.sessionId).isEqualTo("dbg-1")
        assertThat(state.reason).isEqualTo(TerminateReason.CRASH)
        assertThat(state.exitCode).isEqualTo(139)
        assertThat(state.message).isEqualTo("SIGSEGV")
    }
}
