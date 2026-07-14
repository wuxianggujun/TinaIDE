package com.wuxianggujun.tinaide.plugin.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginFaultStore
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class PluginRuntimeIsolationInstrumentedTest {

    @Test
    fun isolatedRuntime_recoversAfterProcessIsKilled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pluginManager = PluginManager(context)
        val testRoot = File(context.cacheDir, "plugin-runtime-kill-${UUID.randomUUID()}").apply { mkdirs() }
        val plugin = createPlugin(testRoot, "test.runtime.kill", "function ping() return 'pong' end")
        val client = PluginRuntimeClient(
            context = context,
            pluginManager = pluginManager,
            projectRootProvider = { null },
            isGenerationCurrent = { _, _ -> true },
        )
        val processDied = CompletableDeferred<Unit>()
        client.setDeathListener { processDied.complete(Unit) }

        try {
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(plugin, 31L, UUID.randomUUID().toString()),
            )
            val pid = requireNotNull(client.runtimeProcessId())
            check(client.requestRuntimeProcessTermination()) {
                "Plugin runtime process termination request was not delivered"
            }
            check(withTimeoutOrNull(5_000L) {
                processDied.await()
                true
            } == true) {
                "Plugin runtime death was not observed; pid=$pid"
            }

            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(plugin, 32L, UUID.randomUUID().toString()),
            )
            check(client.runtimeProcessId() != pid) { "Plugin runtime PID was not replaced after process death" }
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.invoke(
                    PluginRuntimeInvokeRequest(
                        pluginId = plugin.manifest.id,
                        generation = 32L,
                        callId = UUID.randomUUID().toString(),
                        functionName = "ping",
                    ),
                ),
            )
        } finally {
            client.shutdown()
            pluginManager.onDestroy()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun isolatedRuntime_recoversAfterMemoryLimitTerminatesProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pluginManager = PluginManager(context)
        val testRoot = File(context.cacheDir, "plugin-runtime-memory-${UUID.randomUUID()}").apply { mkdirs() }
        val plugin = createPlugin(
            testRoot,
            "test.runtime.memory",
            """
            function exhaust()
              local retained = {}
              for i = 1, 96 do
                retained[i] = string.rep('x', 1024 * 1024)
              end
              while true do end
            end

            function ping() return 'pong' end
            """.trimIndent(),
        )
        val client = PluginRuntimeClient(
            context = context,
            pluginManager = pluginManager,
            projectRootProvider = { null },
            isGenerationCurrent = { _, _ -> true },
        )
        val processDied = CompletableDeferred<Unit>()
        client.setDeathListener { processDied.complete(Unit) }

        try {
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(plugin, 41L, UUID.randomUUID().toString()),
            )
            val limited = client.invoke(
                PluginRuntimeInvokeRequest(
                    pluginId = plugin.manifest.id,
                    generation = 41L,
                    callId = UUID.randomUUID().toString(),
                    functionName = "exhaust",
                ),
            )
            assertStatus(PluginRuntimeResponseStatus.RESOURCE_LIMIT, limited)
            withTimeout(5_000L) { processDied.await() }

            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(plugin, 42L, UUID.randomUUID().toString()),
            )
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.invoke(
                    PluginRuntimeInvokeRequest(
                        pluginId = plugin.manifest.id,
                        generation = 42L,
                        callId = UUID.randomUUID().toString(),
                        functionName = "ping",
                    ),
                ),
            )
        } finally {
            client.shutdown()
            pluginManager.onDestroy()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun isolatedRuntime_recoversAfterWatchdogTerminatesInfiniteLoop() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pluginManager = PluginManager(context)
        val testRoot = File(context.cacheDir, "plugin-runtime-${UUID.randomUUID()}").apply { mkdirs() }
        val healthy = createPlugin(testRoot, "test.runtime.healthy", "function ping() return 'pong' end")
        val looping = createPlugin(testRoot, "test.runtime.loop", "function hang() while true do end end")
        val client = PluginRuntimeClient(
            context = context,
            pluginManager = pluginManager,
            projectRootProvider = { null },
            isGenerationCurrent = { _, _ -> true },
        )
        val processDied = CompletableDeferred<Unit>()
        client.setDeathListener { processDied.complete(Unit) }

        try {
            val healthyGeneration = 1L
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(healthy, healthyGeneration, UUID.randomUUID().toString()),
            )
            val ping = client.invoke(
                PluginRuntimeInvokeRequest(
                    pluginId = healthy.manifest.id,
                    generation = healthyGeneration,
                    callId = UUID.randomUUID().toString(),
                    functionName = "ping",
                ),
            )
            assertEquals(PluginRuntimeResponseStatus.SUCCESS, ping.status)
            assertEquals("\"pong\"", ping.values.single().toString())

            val loopGeneration = 2L
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(looping, loopGeneration, UUID.randomUUID().toString()),
            )
            val timeout = client.invoke(
                PluginRuntimeInvokeRequest(
                    pluginId = looping.manifest.id,
                    generation = loopGeneration,
                    callId = UUID.randomUUID().toString(),
                    functionName = "hang",
                ),
            )
            assertEquals(PluginRuntimeResponseStatus.TIMEOUT, timeout.status)
            withTimeout(5_000L) { processDied.await() }

            val recoveredGeneration = 3L
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(healthy, recoveredGeneration, UUID.randomUUID().toString()),
            )
            assertEquals(
                PluginRuntimeResponseStatus.SUCCESS,
                client.invoke(
                    PluginRuntimeInvokeRequest(
                        pluginId = healthy.manifest.id,
                        generation = recoveredGeneration,
                        callId = UUID.randomUUID().toString(),
                        functionName = "ping",
                    ),
                ).status,
            )
            assertNotNull(context.packageManager.getPackageInfo(context.packageName, 0))
        } finally {
            client.shutdown()
            pluginManager.onDestroy()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun isolatedRuntime_blocksDangerousLibrariesAndRestrictsRequireToPluginModules() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pluginManager = PluginManager(context)
        val pluginId = "test.runtime.sandbox.${UUID.randomUUID()}"
        val pluginDirectory = File(context.filesDir, "plugins/$pluginId")
        val client = PluginRuntimeClient(
            context = context,
            pluginManager = pluginManager,
            projectRootProvider = { null },
            isGenerationCurrent = { candidateId, generation -> candidateId == pluginId && generation == 11L },
        )

        try {
            pluginDirectory.mkdirs()
            File(pluginDirectory, "modules").mkdirs()
            File(pluginDirectory, "modules/helper.lua").writeText(
                "return { value = 'module-ok' }",
                Charsets.UTF_8,
            )
            File(pluginDirectory, "main.lua").writeText(
                """
                function sandboxStatus()
                  return table.concat({
                    tostring(io == nil),
                    tostring(debug == nil),
                    tostring(loadfile == nil),
                    tostring(dofile == nil),
                    tostring(java == nil),
                    tostring(luajava == nil),
                    tostring(package == nil),
                    tostring(os == nil or os.execute == nil)
                  }, "|")
                end

                function requireStatus()
                  local helper = require("modules.helper")
                  local escaped = pcall(require, "../escape")
                  return helper.value .. "|" .. tostring(escaped)
                end
                """.trimIndent(),
                Charsets.UTF_8,
            )
            File(pluginDirectory, "manifest.json").writeText(
                """
                {
                  "id": "$pluginId",
                  "name": "Sandbox Test",
                  "version": "1.0.0",
                  "apiVersion": 1,
                  "type": "script",
                  "main": "main.lua"
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )
            PluginFaultStore.getInstance(context).clearAllForUninstall(pluginId)
            pluginManager.refreshInstalledPlugins()
            val plugin = requireNotNull(pluginManager.getEnabledPlugin(pluginId))

            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(plugin, 11L, UUID.randomUUID().toString()),
            )
            val sandbox = client.invoke(
                PluginRuntimeInvokeRequest(pluginId, 11L, UUID.randomUUID().toString(), "sandboxStatus"),
            )
            val require = client.invoke(
                PluginRuntimeInvokeRequest(pluginId, 11L, UUID.randomUUID().toString(), "requireStatus"),
            )

            assertEquals(PluginRuntimeResponseStatus.SUCCESS, sandbox.status)
            assertEquals("\"true|true|true|true|true|true|true|true\"", sandbox.values.single().toString())
            assertEquals(PluginRuntimeResponseStatus.SUCCESS, require.status)
            assertEquals("\"module-ok|false\"", require.values.single().toString())
        } finally {
            client.shutdown()
            PluginFaultStore.getInstance(context).clearAllForUninstall(pluginId)
            pluginDirectory.deleteRecursively()
            pluginManager.onDestroy()
        }
    }

    @Test
    fun isolatedRuntime_rejectsStaleGenerationAndOversizedResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pluginManager = PluginManager(context)
        val testRoot = File(context.cacheDir, "plugin-runtime-limits-${UUID.randomUUID()}").apply { mkdirs() }
        val plugin = createPlugin(
            testRoot,
            "test.runtime.limits",
            "function huge() return string.rep('x', 300 * 1024) end",
        )
        val client = PluginRuntimeClient(
            context = context,
            pluginManager = pluginManager,
            projectRootProvider = { null },
            isGenerationCurrent = { _, _ -> true },
        )

        try {
            assertStatus(
                PluginRuntimeResponseStatus.SUCCESS,
                client.load(plugin, 21L, UUID.randomUUID().toString()),
            )
            val stale = client.invoke(
                PluginRuntimeInvokeRequest(
                    pluginId = plugin.manifest.id,
                    generation = 20L,
                    callId = UUID.randomUUID().toString(),
                    functionName = "huge",
                    args = JsonArray(emptyList()),
                ),
            )
            val oversized = client.invoke(
                PluginRuntimeInvokeRequest(
                    pluginId = plugin.manifest.id,
                    generation = 21L,
                    callId = UUID.randomUUID().toString(),
                    functionName = "huge",
                    args = JsonArray(emptyList()),
                ),
            )

            assertEquals(PluginRuntimeResponseStatus.STALE_GENERATION, stale.status)
            assertEquals(PluginRuntimeResponseStatus.RESOURCE_LIMIT, oversized.status)
            assertEquals("Plugin runtime response exceeds Binder limit", oversized.error)
        } finally {
            client.shutdown()
            pluginManager.onDestroy()
            testRoot.deleteRecursively()
        }
    }

    private fun createPlugin(root: File, id: String, source: String): InstalledPlugin {
        val directory = File(root, id).apply { mkdirs() }
        File(directory, "main.lua").writeText(source, Charsets.UTF_8)
        return InstalledPlugin(
            manifest = PluginManifest(
                id = id,
                name = id,
                version = "1.0.0",
                type = "script",
                main = "main.lua",
            ),
            directory = directory,
            enabled = true,
        )
    }

    private fun assertStatus(
        expected: PluginRuntimeResponseStatus,
        response: PluginRuntimeResponse,
    ) {
        val details = buildString {
            append("Plugin runtime response error: ")
            append(response.error ?: "<none>")
            response.stack?.takeIf { it.isNotBlank() }?.let {
                append('\n')
                append(it)
            }
        }
        assertEquals(details, expected, response.status)
    }
}
