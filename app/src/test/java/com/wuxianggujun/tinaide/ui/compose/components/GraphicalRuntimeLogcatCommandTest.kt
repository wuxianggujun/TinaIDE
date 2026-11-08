package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphicalRuntimeLogcatCommandTest {
    @Test
    fun capture_shouldIncludeEveryEngineTagButOnlyTheCurrentRuntimeProcess() {
        val command = graphicalRuntimeLogcatCommand(12345)
        assertThat(command).contains("--pid=12345")
        assertThat(command).contains("*:V")
        assertThat(command).doesNotContain("-s")
        assertThat(command).doesNotContain("*:S")
    }
}
