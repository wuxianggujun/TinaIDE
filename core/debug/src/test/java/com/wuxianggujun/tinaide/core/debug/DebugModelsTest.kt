package com.wuxianggujun.tinaide.core.debug

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DebugModelsTest {

    @Test
    fun debugSessionStore_shouldPublishAndClearDescriptor() {
        val store = DebugSessionStore()
        val descriptor = DebugSessionScaffold.Descriptor(
            sessionId = "dbg-1",
            descriptorPath = "/tmp/dbg-1.txt",
            instructions = listOf("created"),
        )

        store.update(descriptor)
        assertThat(store.descriptor.value).isEqualTo(descriptor)

        store.clear()
        assertThat(store.descriptor.value).isNull()
    }

    @Test
    fun sourceLocation_shouldCarryFileLineAndOptionalFunction() {
        val location = SourceLocation(
            file = "/project/main.cpp",
            line = 12,
            function = "main",
            address = 0x1000,
        )

        assertThat(location.file).isEqualTo("/project/main.cpp")
        assertThat(location.line).isEqualTo(12)
        assertThat(location.function).isEqualTo("main")
        assertThat(location.address).isEqualTo(0x1000)
    }
}
