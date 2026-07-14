package com.wuxianggujun.tinaide.plugin.script

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.PluginFaultKind
import com.wuxianggujun.tinaide.plugin.PluginFaultPhase
import com.wuxianggujun.tinaide.plugin.PluginFaultStore
import com.wuxianggujun.tinaide.plugin.PluginEffectiveStatus
import com.wuxianggujun.tinaide.plugin.PluginInFlightRecord
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeInvokeRequest
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeResponse
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeResponseStatus
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeTransport
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeTransportFactory
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeUnavailableException
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeUnloadRequest
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.plugin.script.api.PluginEventBus
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class ScriptPluginManagerTest {

    private lateinit var context: Application
    private lateinit var pluginManager: PluginManager
    private lateinit var faultStore: PluginFaultStore
    private lateinit var transport: FakePluginRuntimeTransport
    private var manager: ScriptPluginManager? = null
    private val pluginIds = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "plugins").deleteRecursively()
        context.getSharedPreferences("tinaide_plugins", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tinaide_plugin_permissions", Context.MODE_PRIVATE).edit().clear().commit()
        PluginEventBus.clear()
        PluginCommandRegistry.clear()
        pluginManager = PluginManager(context)
        faultStore = PluginFaultStore.getInstance(context)
        transport = FakePluginRuntimeTransport()
    }

    @After
    fun tearDown() {
        manager?.shutdown()
        pluginManager.onDestroy()
        PluginEventBus.clear()
        PluginCommandRegistry.clear()
        pluginIds.forEach(faultStore::clearAllForUninstall)
        faultStore.getInFlight()?.let { faultStore.clearInFlight(it.executionId) }
        File(context.filesDir, "plugins").deleteRecursively()
    }

    @Test
    fun `startup exception quarantines plugin and persists downgrade-safe disabled state`() = runBlocking {
        val pluginId = installScriptPlugin("plugin.startup.failure")
        transport.loadHandler = { plugin, generation, callId ->
            PluginRuntimeResponse(
                pluginId = plugin.manifest.id,
                generation = generation,
                callId = callId,
                status = PluginRuntimeResponseStatus.PLUGIN_ERROR,
                error = "top-level failure",
            )
        }

        createManager()
        val state = awaitState(pluginId, ScriptPluginState.QUARANTINED)

        assertThat(state.fault?.kind).isEqualTo(PluginFaultKind.STARTUP_EXCEPTION)
        assertThat(pluginManager.isPluginEnabled(pluginId)).isFalse()
        assertThat(pluginManager.getPluginFault(pluginId)?.message).isEqualTo("top-level failure")
        assertThat(faultStore.getEffectiveStatus(pluginId)).isEqualTo(PluginEffectiveStatus.QUARANTINED)
        assertThat(
            context.getSharedPreferences("tinaide_plugins", Context.MODE_PRIVATE)
                .getBoolean("enabled_$pluginId", true),
        ).isFalse()
    }

    @Test
    fun `runtime service unavailable does not quarantine plugin`() = runBlocking {
        val pluginId = installScriptPlugin("plugin.runtime.unavailable")
        transport.loadHandler = { _, _, _ ->
            throw PluginRuntimeUnavailableException("service unavailable")
        }

        createManager()
        val state = awaitState(pluginId, ScriptPluginState.RUNTIME_UNAVAILABLE)

        assertThat(state.error).contains("service unavailable")
        assertThat(pluginManager.getPluginFault(pluginId)).isNull()
        assertThat(pluginManager.isPluginEnabled(pluginId)).isTrue()
        assertThat(faultStore.getEffectiveStatus(pluginId)).isEqualTo(PluginEffectiveStatus.RUNTIME_UNAVAILABLE)
    }

    @Test
    fun `disable during load cancels active call and leaves no active runtime`() = runBlocking {
        val pluginId = installScriptPlugin("plugin.disable.during.load")
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        transport.loadHandler = { plugin, generation, callId ->
            loadStarted.complete(Unit)
            releaseLoad.await()
            success(plugin.manifest.id, generation, callId)
        }
        transport.cancelHandler = { cancelledPluginId ->
            if (cancelledPluginId == pluginId) releaseLoad.complete(Unit)
            cancelledPluginId == pluginId
        }
        createManager()
        withTimeout(2_000) { loadStarted.await() }

        val disable = async { pluginManager.setPluginEnabled(pluginId, false).getOrThrow() }
        withTimeout(2_000) { disable.await() }

        val state = awaitState(pluginId, ScriptPluginState.DISABLED)
        assertThat(state.state).isEqualTo(ScriptPluginState.DISABLED)
        assertThat(pluginManager.getEnabledPlugin(pluginId)).isNull()
        assertThat(transport.cancelledPluginIds).contains(pluginId)
        assertThat(transport.unloads).isNotEmpty()
    }

    @Test
    fun `disable during execution cancels callback without quarantining plugin`() = runBlocking {
        val pluginId = installScriptPlugin("plugin.disable.during.execution")
        createManager()
        awaitState(pluginId, ScriptPluginState.ACTIVE)
        val invokeStarted = CompletableDeferred<Unit>()
        val releaseInvoke = CompletableDeferred<Unit>()
        transport.invokeHandler = { request ->
            invokeStarted.complete(Unit)
            releaseInvoke.await()
            success(request.pluginId, request.generation, request.callId)
        }
        transport.cancelHandler = { cancelledPluginId ->
            if (cancelledPluginId == pluginId) releaseInvoke.complete(Unit)
            cancelledPluginId == pluginId
        }

        val execution = async { requireNotNull(manager).executeInPlugin(pluginId, "onCommandRun") }
        withTimeout(2_000) { invokeStarted.await() }
        val disable = async { pluginManager.setPluginEnabled(pluginId, false).getOrThrow() }

        assertThat(withTimeout(2_000) { execution.await() }).isInstanceOf(PluginExecutionResult.Error::class.java)
        withTimeout(2_000) { disable.await() }
        awaitState(pluginId, ScriptPluginState.DISABLED)

        assertThat(pluginManager.getPluginFault(pluginId)).isNull()
        assertThat(faultStore.getInFlight()).isNull()
        assertThat(transport.cancelledPluginIds).contains(pluginId)
    }

    @Test
    fun `uninstall during execution cancels callback and removes runtime state`() = runBlocking {
        val pluginId = installScriptPlugin("plugin.uninstall.during.execution")
        createManager()
        awaitState(pluginId, ScriptPluginState.ACTIVE)
        val invokeStarted = CompletableDeferred<Unit>()
        val releaseInvoke = CompletableDeferred<Unit>()
        transport.invokeHandler = { request ->
            invokeStarted.complete(Unit)
            releaseInvoke.await()
            success(request.pluginId, request.generation, request.callId)
        }
        transport.cancelHandler = { cancelledPluginId ->
            if (cancelledPluginId == pluginId) releaseInvoke.complete(Unit)
            cancelledPluginId == pluginId
        }

        val execution = async { requireNotNull(manager).executeInPlugin(pluginId, "onCommandRun") }
        withTimeout(2_000) { invokeStarted.await() }
        val uninstall = async { pluginManager.uninstallPlugin(pluginId).getOrThrow() }

        assertThat(withTimeout(2_000) { execution.await() }).isInstanceOf(PluginExecutionResult.Error::class.java)
        withTimeout(2_000) { uninstall.await() }
        withTimeout(2_000) {
            requireNotNull(manager).pluginStates.first { states -> pluginId !in states }
        }

        assertThat(pluginManager.getInstalledPlugin(pluginId)).isNull()
        assertThat(pluginManager.getPluginFault(pluginId)).isNull()
        assertThat(faultStore.getInFlight()).isNull()
        assertThat(transport.cancelledPluginIds).contains(pluginId)
        assertThat(File(context.filesDir, "plugins/$pluginId").exists()).isFalse()
    }

    @Test
    fun `runtime death while idle reloads every healthy plugin with new generations`() = runBlocking {
        val firstPluginId = installScriptPlugin("plugin.runtime.recovery.first")
        val secondPluginId = installScriptPlugin("plugin.runtime.recovery.second")
        createManager()
        val firstGeneration = awaitState(firstPluginId, ScriptPluginState.ACTIVE).generation
        val secondGeneration = awaitState(secondPluginId, ScriptPluginState.ACTIVE).generation

        transport.simulateDeath()

        val recoveredFirst = awaitStateAfterGeneration(firstPluginId, firstGeneration)
        val recoveredSecond = awaitStateAfterGeneration(secondPluginId, secondGeneration)
        assertThat(recoveredFirst.state).isEqualTo(ScriptPluginState.ACTIVE)
        assertThat(recoveredSecond.state).isEqualTo(ScriptPluginState.ACTIVE)
        assertThat(transport.loads.count { it == firstPluginId }).isAtLeast(2)
        assertThat(transport.loads.count { it == secondPluginId }).isAtLeast(2)
        assertThat(pluginManager.getPluginFault(firstPluginId)).isNull()
        assertThat(pluginManager.getPluginFault(secondPluginId)).isNull()
    }

    @Test
    fun `unhandled callback exception quarantines active plugin`() = runBlocking {
        val pluginId = installScriptPlugin("plugin.callback.failure")
        createManager()
        awaitState(pluginId, ScriptPluginState.ACTIVE)
        transport.invokeHandler = { request ->
            PluginRuntimeResponse(
                pluginId = request.pluginId,
                generation = request.generation,
                callId = request.callId,
                status = PluginRuntimeResponseStatus.PLUGIN_ERROR,
                error = "event callback failed",
            )
        }

        val result = requireNotNull(manager).executeInPlugin(pluginId, "onProjectOpened", emptyMap<String, Any?>())

        assertThat(result).isInstanceOf(PluginExecutionResult.Error::class.java)
        val state = awaitState(pluginId, ScriptPluginState.QUARANTINED)
        assertThat(state.fault?.phase).isEqualTo(PluginFaultPhase.EVENT)
        assertThat(state.fault?.kind).isEqualTo(PluginFaultKind.UNHANDLED_EXCEPTION)
    }

    @Test
    fun `leftover in-flight journal quarantines plugin on next startup`() = runBlocking {
        val pluginId = installScriptPlugin("plugin.interrupted")
        val executionId = UUID.randomUUID().toString()
        assertThat(
            faultStore.beginExecution(
                PluginInFlightRecord(
                    pluginId = pluginId,
                    pluginVersion = "1.0.0",
                    generation = 7,
                    phase = PluginFaultPhase.COMMAND,
                    executionId = executionId,
                    startedAtMillis = System.currentTimeMillis(),
                ),
            ),
        ).isTrue()

        createManager()
        val state = awaitState(pluginId, ScriptPluginState.QUARANTINED)

        assertThat(state.fault?.kind).isEqualTo(PluginFaultKind.INTERRUPTED_EXECUTION)
        assertThat(state.fault?.executionId).isEqualTo(executionId)
        assertThat(faultStore.getInFlight()).isNull()
        assertThat(transport.loads).isEmpty()
    }

    private suspend fun installScriptPlugin(pluginId: String): String {
        pluginIds += pluginId
        faultStore.clearAllForUninstall(pluginId)
        val pluginDir = File(context.filesDir, "plugins/$pluginId").apply { mkdirs() }
        File(pluginDir, "main.lua").writeText("function ping() return 'pong' end", Charsets.UTF_8)
        File(pluginDir, "manifest.json").writeText(
            """
            {
              "id": "$pluginId",
              "name": "Isolation Test",
              "version": "1.0.0",
              "apiVersion": 1,
              "type": "script",
              "main": "main.lua"
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        pluginManager.refreshInstalledPlugins()
        checkNotNull(pluginManager.getInstalledPlugin(pluginId))
        return pluginId
    }

    private fun createManager() {
        manager = ScriptPluginManager(
            context = context,
            pluginManager = pluginManager,
            runtimeTransportFactory = PluginRuntimeTransportFactory { _, _, _, _ -> transport },
        )
    }

    private suspend fun awaitState(
        pluginId: String,
        state: ScriptPluginState,
    ): ScriptPluginInfo = withTimeout(3_000) {
        requireNotNull(manager).pluginStates
            .map { states -> states[pluginId] }
            .filterNotNull()
            .first { info -> info.state == state }
    }

    private suspend fun awaitStateAfterGeneration(
        pluginId: String,
        generation: Long,
    ): ScriptPluginInfo = withTimeout(3_000) {
        requireNotNull(manager).pluginStates
            .map { states -> states[pluginId] }
            .filterNotNull()
            .first { info -> info.state == ScriptPluginState.ACTIVE && info.generation > generation }
    }

    private class FakePluginRuntimeTransport : PluginRuntimeTransport {
        var loadHandler: suspend (
            com.wuxianggujun.tinaide.plugin.InstalledPlugin,
            Long,
            String,
        ) -> PluginRuntimeResponse = { plugin, generation, callId ->
            success(plugin.manifest.id, generation, callId)
        }
        var invokeHandler: suspend (PluginRuntimeInvokeRequest) -> PluginRuntimeResponse = { request ->
            success(request.pluginId, request.generation, request.callId)
        }
        var cancelHandler: (String) -> Boolean = { false }
        val loads = CopyOnWriteArrayList<String>()
        val unloads = CopyOnWriteArrayList<PluginRuntimeUnloadRequest>()
        val cancelledPluginIds = CopyOnWriteArrayList<String>()
        private var deathListener: (() -> Unit)? = null

        override fun setDeathListener(listener: () -> Unit) {
            deathListener = listener
        }

        override suspend fun load(
            plugin: com.wuxianggujun.tinaide.plugin.InstalledPlugin,
            generation: Long,
            callId: String,
        ): PluginRuntimeResponse {
            loads += plugin.manifest.id
            return loadHandler(plugin, generation, callId)
        }

        override suspend fun invoke(request: PluginRuntimeInvokeRequest): PluginRuntimeResponse =
            invokeHandler(request)

        override suspend fun unload(request: PluginRuntimeUnloadRequest): PluginRuntimeResponse {
            unloads += request
            return success(request.pluginId, request.generation, request.callId)
        }

        override fun cancelActiveCall(pluginId: String): Boolean {
            cancelledPluginIds += pluginId
            return cancelHandler(pluginId)
        }

        override fun shutdown() = Unit

        fun simulateDeath() {
            checkNotNull(deathListener).invoke()
        }
    }

    private companion object {
        fun success(pluginId: String, generation: Long, callId: String) = PluginRuntimeResponse(
            pluginId = pluginId,
            generation = generation,
            callId = callId,
            status = PluginRuntimeResponseStatus.SUCCESS,
        )
    }
}
