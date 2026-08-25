package com.wuxianggujun.tinaide.plugin.script.api

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Test

class PluginCommandRegistryTest {

    @Before
    fun setUp() {
        PluginCommandRegistry.clear()
    }

    @After
    fun tearDown() {
        PluginCommandRegistry.clear()
    }

    @Test
    fun `register rejects duplicate command ids and clears conflict after owner removal`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
        ).getOrThrow()

        val duplicate = PluginCommandRegistry.register(
            pluginId = "plugin.two",
            pluginName = "Plugin Two",
            commandId = "plugin.sayHello",
            callbackName = "handleHelloAgain",
        )

        assertThat(duplicate.isFailure).isTrue()
        assertThat(
            PluginCommandRegistry.registrationIssue("plugin.sayHello", "plugin.two")
                ?.conflictingPluginId,
        ).isEqualTo("plugin.one")

        PluginCommandRegistry.unregisterAll("plugin.one")

        assertThat(
            PluginCommandRegistry.registrationIssue("plugin.sayHello", "plugin.two"),
        ).isNull()
        assertThat(
            PluginCommandRegistry.register(
                pluginId = "plugin.two",
                pluginName = "Plugin Two",
                commandId = "plugin.sayHello",
                callbackName = "handleHelloAgain",
            ).isSuccess,
        ).isTrue()
    }

    @Test
    fun `dispatch invokes isolated runtime callback with bounded host payload`() {
        val invocationRef = AtomicReference<Map<String, Any?>>()
        PluginCommandRegistry.setRuntimeAccess(
            callbackInvoker = { _, _, payload ->
                invocationRef.set(payload)
                PluginExecutionResult.Success(Unit)
            },
            permissionChecker = { _, permission ->
                PluginCommandAvailability(permission == PluginPermission.COMMAND_EXECUTE)
            },
        )
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
        ).getOrThrow()
        val target = File("workspace/src/Main.kt")

        assertThat(
            PluginCommandRegistry.dispatch(
                commandId = "plugin.sayHello",
                invocation = HostCommandInvocation(file = target, isDirectory = false, isDirty = true),
            ),
        ).isTrue()

        waitUntil { invocationRef.get() != null }
        assertThat(invocationRef.get()["commandId"]).isEqualTo("plugin.sayHello")
        assertThat(invocationRef.get()["filePath"]).isEqualTo(target.absolutePath)
        assertThat(invocationRef.get()["fileName"]).isEqualTo(target.name)
        assertThat(invocationRef.get()["isDirectory"]).isEqualTo(false)
        assertThat(invocationRef.get()["isDirty"]).isEqualTo(true)
    }

    @Test
    fun `dispatch records failures and clears issue after callback recovers`() {
        val nextResult = AtomicReference<PluginExecutionResult>(PluginExecutionResult.Error("Boom"))
        PluginCommandRegistry.setRuntimeAccess(
            callbackInvoker = { _, _, _ -> nextResult.get() },
            permissionChecker = { _, _ -> PluginCommandAvailability(true) },
        )
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
        ).getOrThrow()

        assertThat(PluginCommandRegistry.dispatch("plugin.sayHello")).isTrue()
        waitUntil {
            PluginCommandRegistry.executionIssue("plugin.sayHello", "plugin.one")?.message == "Boom"
        }

        nextResult.set(PluginExecutionResult.Success(Unit))
        assertThat(PluginCommandRegistry.dispatch("plugin.sayHello")).isTrue()
        waitUntil { PluginCommandRegistry.executionIssue("plugin.sayHello", "plugin.one") == null }
    }

    @Test
    fun `dispatch rejects disabled or unauthorized plugin before callback`() {
        var callbackInvoked = false
        PluginCommandRegistry.setRuntimeAccess(
            callbackInvoker = { _, _, _ ->
                callbackInvoked = true
                PluginExecutionResult.Success(Unit)
            },
            permissionChecker = { _, _ -> PluginCommandAvailability(false, "Permission denied") },
        )
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
        ).getOrThrow()

        val result = PluginCommandRegistry.dispatchWithResult("plugin.sayHello")

        assertThat(result.handled).isFalse()
        assertThat(result.errorMessage).isEqualTo("Permission denied")
        assertThat(callbackInvoked).isFalse()
    }

    @Test
    fun `unregisterAll removes every command owned by plugin`() {
        listOf("hello", "bye").forEach { suffix ->
            PluginCommandRegistry.register(
                pluginId = "plugin.one",
                pluginName = "Plugin One",
                commandId = "plugin.$suffix",
                callbackName = "handle$suffix",
            ).getOrThrow()
        }

        PluginCommandRegistry.unregisterAll("plugin.one")

        assertThat(PluginCommandRegistry.isRegistered("plugin.hello")).isFalse()
        assertThat(PluginCommandRegistry.isRegistered("plugin.bye")).isFalse()
    }

    private fun waitUntil(
        timeoutMillis: Long = 1_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertThat(condition()).isTrue()
    }
}
