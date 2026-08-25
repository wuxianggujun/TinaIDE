package com.wuxianggujun.tinaide.plugin.script.api

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class PluginEventBusTest {
    @After
    fun tearDown() {
        PluginEventBus.clear()
    }

    @Test
    fun `duplicate subscription is dispatched once`() = runBlocking {
        var invocations = 0
        PluginEventBus.setCallbackInvoker { _, _, _ ->
            invocations += 1
            PluginExecutionResult.Success(null)
        }
        PluginEventBus.subscribe("plugin.test", PluginEvent.CUSTOM.id, "on_custom")
        PluginEventBus.subscribe("plugin.test", PluginEvent.CUSTOM.id, "on_custom")

        PluginEventBus.emit(PluginEvent.CUSTOM.id, targetPluginId = "plugin.test")

        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun `unknown event cannot be subscribed or emitted`() = runBlocking {
        val subscribeError = runCatching {
            PluginEventBus.subscribe("plugin.test", "host.private", "callback")
        }.exceptionOrNull()
        val emitError = runCatching {
            PluginEventBus.emit("host.private")
        }.exceptionOrNull()

        assertThat(subscribeError).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(emitError).isInstanceOf(IllegalArgumentException::class.java)
    }
}
