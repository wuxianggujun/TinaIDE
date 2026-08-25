package com.wuxianggujun.tinaide.plugin.script

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.plugin.PluginEffectiveStatus
import com.wuxianggujun.tinaide.plugin.PluginFaultKind
import com.wuxianggujun.tinaide.plugin.PluginFaultPhase
import com.wuxianggujun.tinaide.plugin.PluginFaultStore
import com.wuxianggujun.tinaide.plugin.PluginInFlightRecord
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeInvokeRequest
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeResponse
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeResponseStatus
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeTransport
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeTransportFactory
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeUnloadRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class PluginQuarantinePersistenceInstrumentedTest {

    @Test
    fun residualJournal_quarantinesPluginAcrossHostRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pluginId = "test.runtime.persistence.${UUID.randomUUID()}"
        val executionId = UUID.randomUUID().toString()
        val pluginDirectory = File(context.filesDir, "plugins/$pluginId")
        val pluginPreferences = context.getSharedPreferences(PLUGIN_PREFERENCES, Context.MODE_PRIVATE)
        var pluginManager: PluginManager? = null
        var scriptManager: ScriptPluginManager? = null

        try {
            writePlugin(pluginDirectory, pluginId)
            assertTrue(
                pluginPreferences.edit()
                    .putBoolean("desired_enabled_$pluginId", true)
                    .putBoolean("enabled_$pluginId", true)
                    .commit(),
            )
            val initialStore = PluginFaultStore.getInstance(context)
            initialStore.clearAllForUninstall(pluginId)
            assertTrue(
                initialStore.beginExecution(
                    PluginInFlightRecord(
                        pluginId = pluginId,
                        pluginVersion = "1.0.0",
                        generation = 7L,
                        phase = PluginFaultPhase.COMMAND,
                        executionId = executionId,
                        startedAtMillis = System.currentTimeMillis(),
                    ),
                ),
            )

            PluginFaultStore.resetForTests()
            pluginManager = PluginManager(context).also { it.onCreate() }
            val recoveryTransport = RecordingTransport()
            scriptManager = createScriptManager(context, pluginManager, recoveryTransport)
            val recoveredState = awaitState(scriptManager, pluginId, ScriptPluginState.QUARANTINED)
            val recoveredStore = PluginFaultStore.getInstance(context)

            assertEquals(PluginFaultKind.INTERRUPTED_EXECUTION, recoveredState.fault?.kind)
            assertEquals(executionId, recoveredState.fault?.executionId)
            assertEquals(PluginEffectiveStatus.QUARANTINED, recoveredStore.getEffectiveStatus(pluginId))
            assertNull(recoveredStore.getInFlight())
            assertTrue(pluginPreferences.getBoolean("desired_enabled_$pluginId", false))
            assertFalse(pluginPreferences.getBoolean("enabled_$pluginId", true))
            assertTrue(recoveryTransport.loads.isEmpty())

            scriptManager.shutdown()
            scriptManager = null
            pluginManager.onDestroy()
            pluginManager = null
            PluginFaultStore.resetForTests()

            pluginManager = PluginManager(context).also { it.onCreate() }
            val relaunchTransport = RecordingTransport()
            scriptManager = createScriptManager(context, pluginManager, relaunchTransport)
            val relaunchedState = awaitState(scriptManager, pluginId, ScriptPluginState.QUARANTINED)
            val relaunchedStore = PluginFaultStore.getInstance(context)

            assertEquals(executionId, relaunchedState.fault?.executionId)
            assertEquals(PluginFaultKind.INTERRUPTED_EXECUTION, relaunchedStore.getFault(pluginId)?.kind)
            assertFalse(pluginManager.isPluginEnabled(pluginId))
            assertNull(pluginManager.getEnabledPlugin(pluginId))
            assertTrue(relaunchTransport.loads.isEmpty())
        } finally {
            scriptManager?.shutdown()
            pluginManager?.onDestroy()
            PluginFaultStore.getInstance(context).clearAllForUninstall(pluginId)
            PluginFaultStore.resetForTests()
            pluginPreferences.edit()
                .remove("desired_enabled_$pluginId")
                .remove("enabled_$pluginId")
                .commit()
            pluginDirectory.deleteRecursively()
        }
    }

    @Test
    fun forceStopRelaunchPhase_persistsQuarantineAcrossRealProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString(RELAUNCH_PHASE_ARGUMENT)
        assumeTrue(
            "This two-phase test is orchestrated by tools/testing/plugin-device-gate.ps1",
            phase == RELAUNCH_PHASE_PREPARE || phase == RELAUNCH_PHASE_VERIFY,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        when (phase) {
            RELAUNCH_PHASE_PREPARE -> prepareForceStopFixture(context)
            RELAUNCH_PHASE_VERIFY -> verifyForceStopFixture(context)
        }
    }

    private suspend fun prepareForceStopFixture(context: Context) {
        cleanupForceStopFixture(context)
        val pluginDirectory = File(context.filesDir, "plugins/$FORCE_STOP_PLUGIN_ID")
        val pluginPreferences = context.getSharedPreferences(PLUGIN_PREFERENCES, Context.MODE_PRIVATE)
        var pluginManager: PluginManager? = null
        var scriptManager: ScriptPluginManager? = null
        var prepared = false

        try {
            writePlugin(pluginDirectory, FORCE_STOP_PLUGIN_ID)
            assertTrue(
                pluginPreferences.edit()
                    .putBoolean("desired_enabled_$FORCE_STOP_PLUGIN_ID", true)
                    .putBoolean("enabled_$FORCE_STOP_PLUGIN_ID", true)
                    .commit(),
            )
            val initialStore = PluginFaultStore.getInstance(context)
            assertTrue(
                initialStore.beginExecution(
                    PluginInFlightRecord(
                        pluginId = FORCE_STOP_PLUGIN_ID,
                        pluginVersion = "1.0.0",
                        generation = FORCE_STOP_GENERATION,
                        phase = PluginFaultPhase.COMMAND,
                        executionId = FORCE_STOP_EXECUTION_ID,
                        startedAtMillis = System.currentTimeMillis(),
                    ),
                ),
            )

            PluginFaultStore.resetForTests()
            pluginManager = PluginManager(context).also { it.onCreate() }
            val transport = RecordingTransport()
            scriptManager = createScriptManager(context, pluginManager, transport)
            val state = awaitState(scriptManager, FORCE_STOP_PLUGIN_ID, ScriptPluginState.QUARANTINED)

            assertEquals(PluginFaultKind.INTERRUPTED_EXECUTION, state.fault?.kind)
            assertEquals(FORCE_STOP_EXECUTION_ID, state.fault?.executionId)
            assertTrue(transport.loads.isEmpty())
            forceStopMarker(context).writeText(FORCE_STOP_EXECUTION_ID, Charsets.UTF_8)
            prepared = true
        } finally {
            scriptManager?.shutdown()
            pluginManager?.onDestroy()
            PluginFaultStore.resetForTests()
            if (!prepared) cleanupForceStopFixture(context)
        }
    }

    private suspend fun verifyForceStopFixture(context: Context) {
        val pluginDirectory = File(context.filesDir, "plugins/$FORCE_STOP_PLUGIN_ID")
        val pluginPreferences = context.getSharedPreferences(PLUGIN_PREFERENCES, Context.MODE_PRIVATE)
        var pluginManager: PluginManager? = null
        var scriptManager: ScriptPluginManager? = null

        try {
            assertTrue("Force-stop preparation marker is missing", forceStopMarker(context).isFile)
            assertTrue("Force-stop plugin fixture is missing", pluginDirectory.isDirectory)
            PluginFaultStore.resetForTests()
            pluginManager = PluginManager(context).also { it.onCreate() }
            val transport = RecordingTransport()
            scriptManager = createScriptManager(context, pluginManager, transport)
            val state = awaitState(scriptManager, FORCE_STOP_PLUGIN_ID, ScriptPluginState.QUARANTINED)
            val faultStore = PluginFaultStore.getInstance(context)

            assertEquals(PluginFaultKind.INTERRUPTED_EXECUTION, state.fault?.kind)
            assertEquals(FORCE_STOP_EXECUTION_ID, state.fault?.executionId)
            assertEquals(PluginEffectiveStatus.QUARANTINED, faultStore.getEffectiveStatus(FORCE_STOP_PLUGIN_ID))
            assertFalse(pluginManager.isPluginEnabled(FORCE_STOP_PLUGIN_ID))
            assertNull(pluginManager.getEnabledPlugin(FORCE_STOP_PLUGIN_ID))
            assertTrue(pluginPreferences.getBoolean("desired_enabled_$FORCE_STOP_PLUGIN_ID", false))
            assertFalse(pluginPreferences.getBoolean("enabled_$FORCE_STOP_PLUGIN_ID", true))
            assertTrue(transport.loads.isEmpty())
        } finally {
            scriptManager?.shutdown()
            pluginManager?.onDestroy()
            cleanupForceStopFixture(context)
        }
    }

    private fun cleanupForceStopFixture(context: Context) {
        PluginFaultStore.resetForTests()
        PluginFaultStore.getInstance(context).clearAllForUninstall(FORCE_STOP_PLUGIN_ID)
        context.getSharedPreferences(PLUGIN_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove("desired_enabled_$FORCE_STOP_PLUGIN_ID")
            .remove("enabled_$FORCE_STOP_PLUGIN_ID")
            .commit()
        File(context.filesDir, "plugins/$FORCE_STOP_PLUGIN_ID").deleteRecursively()
        forceStopMarker(context).delete()
        PluginFaultStore.resetForTests()
    }

    private fun forceStopMarker(context: Context): File =
        File(context.filesDir, "plugin-device-gate-force-stop.marker")

    private fun createScriptManager(
        context: Context,
        pluginManager: PluginManager,
        transport: RecordingTransport,
    ): ScriptPluginManager = ScriptPluginManager(
        context = context,
        pluginManager = pluginManager,
        runtimeTransportFactory = PluginRuntimeTransportFactory { _, _, _, _ -> transport },
    )

    private suspend fun awaitState(
        manager: ScriptPluginManager,
        pluginId: String,
        state: ScriptPluginState,
    ): ScriptPluginInfo = withTimeout(5_000L) {
        manager.pluginStates
            .map { states -> states[pluginId] }
            .filterNotNull()
            .first { info -> info.state == state }
    }

    private fun writePlugin(directory: File, pluginId: String) {
        directory.mkdirs()
        File(directory, "main.lua").writeText("function ping() return 'pong' end", Charsets.UTF_8)
        File(directory, "manifest.json").writeText(
            """
            {
              "id": "$pluginId",
              "name": "Persistence Test",
              "version": "1.0.0",
              "apiVersion": 1,
              "type": "script",
              "main": "main.lua"
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }

    private class RecordingTransport : PluginRuntimeTransport {
        val loads = CopyOnWriteArrayList<String>()

        override fun setDeathListener(listener: () -> Unit) = Unit

        override suspend fun load(
            plugin: com.wuxianggujun.tinaide.plugin.InstalledPlugin,
            generation: Long,
            callId: String,
        ): PluginRuntimeResponse {
            loads += plugin.manifest.id
            return success(plugin.manifest.id, generation, callId)
        }

        override suspend fun invoke(request: PluginRuntimeInvokeRequest): PluginRuntimeResponse =
            success(request.pluginId, request.generation, request.callId)

        override suspend fun unload(request: PluginRuntimeUnloadRequest): PluginRuntimeResponse =
            success(request.pluginId, request.generation, request.callId)

        override fun cancelActiveCall(pluginId: String): Boolean = false

        override fun shutdown() = Unit
    }

    private companion object {
        const val PLUGIN_PREFERENCES = "tinaide_plugins"
        const val RELAUNCH_PHASE_ARGUMENT = "tina.plugin.relaunch.phase"
        const val RELAUNCH_PHASE_PREPARE = "prepare"
        const val RELAUNCH_PHASE_VERIFY = "verify"
        const val FORCE_STOP_PLUGIN_ID = "test.runtime.force-stop-persistence"
        const val FORCE_STOP_EXECUTION_ID = "force-stop-execution-v1"
        const val FORCE_STOP_GENERATION = 73L

        fun success(pluginId: String, generation: Long, callId: String) = PluginRuntimeResponse(
            pluginId = pluginId,
            generation = generation,
            callId = callId,
            status = PluginRuntimeResponseStatus.SUCCESS,
        )
    }
}
