package com.wuxianggujun.tinaide.ui.compose.components

import com.wuxianggujun.tinaide.ui.runtime.graphicalRuntimeLogcatCommand

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphicalRuntimeLogcatCommandTest {
    @Test
    fun captureDefaultsToAllUserTagsWhileFilteringKnownHostNoise() {
        val command = graphicalRuntimeLogcatCommand(12345)
        assertThat(command).contains("--pid=12345")
        assertThat(command).contains("*:V")
        assertThat(command).containsAtLeast("TINA_SDL_HOST:W", "NativeStdStreamRedirect:W", "ExternalNativeActivity:W")
        assertThat(command).containsAtLeast("ViewRootImpl:S", "InputMethodManager:S", "Choreographer:S")
        assertThat(command).doesNotContain("*:S")
        assertThat(command).doesNotContain("SDL:I")
        assertThat(command).doesNotContain("SDL:W")
        assertThat(command).doesNotContain("raylib:W")
    }

    @Test
    fun optingInIncludesCustomTagsAndHostDiagnosticsButKeepsTheProcessBoundary() {
        val command = graphicalRuntimeLogcatCommand(12345, showAllProcessLogs = true)
        assertThat(command).contains("--pid=12345")
        assertThat(command).contains("*:V")
        assertThat(command).doesNotContain("ViewRootImpl:S")
        assertThat(command).doesNotContain("TINA_SDL_HOST:W")
        assertThat(command).doesNotContain("-s")
        assertThat(command).doesNotContain("*:S")
    }
}
