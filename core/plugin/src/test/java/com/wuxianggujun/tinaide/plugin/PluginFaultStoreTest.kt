package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class PluginFaultStoreTest {

    private lateinit var context: Application
    private lateinit var store: PluginFaultStore
    private val testPluginIds = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PluginFaultStore.resetForTests()
        store = PluginFaultStore.getInstance(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_IN_FLIGHT)
            .commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_IN_FLIGHT)
            .commit()
        testPluginIds.forEach(store::clearAllForUninstall)
        PluginFaultStore.resetForTests()
    }

    @Test
    fun concurrentFaultWrites_preserveEveryPluginRecord() {
        val prefix = "test.fault.${UUID.randomUUID()}"
        val pluginIds = (0 until 32).map { index -> "$prefix.$index" }
        testPluginIds += pluginIds
        val executor = Executors.newFixedThreadPool(8)

        try {
            val results = pluginIds.map { pluginId ->
                executor.submit<Boolean> { store.recordFault(fault(pluginId)) }
            }

            results.forEach { result -> assertThat(result.get(5, TimeUnit.SECONDS)).isTrue() }
            assertThat(store.faults.value.keys).containsAtLeastElementsIn(pluginIds)
            pluginIds.forEach { pluginId ->
                assertThat(store.getEffectiveStatus(pluginId))
                    .isEqualTo(PluginEffectiveStatus.QUARANTINED)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun beginExecution_doesNotOverwriteResidualJournal() {
        val first = inFlight("test.journal.first")
        val second = inFlight("test.journal.second")
        testPluginIds += first.pluginId
        testPluginIds += second.pluginId

        assertThat(store.beginExecution(first)).isTrue()
        assertThat(store.beginExecution(second)).isFalse()
        assertThat(store.getInFlight()).isEqualTo(first)
    }

    @Test
    fun malformedJournal_failsClosed() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IN_FLIGHT, "{not-json")
            .commit()

        assertThrows(Exception::class.java) { store.getInFlight() }
    }

    private fun fault(pluginId: String) = PluginFaultRecord(
        pluginId = pluginId,
        pluginVersion = "1.0.0",
        phase = PluginFaultPhase.COMMAND,
        kind = PluginFaultKind.UNHANDLED_EXCEPTION,
        message = "failure",
        timestampMillis = 1L,
        executionId = UUID.randomUUID().toString(),
    )

    private fun inFlight(pluginId: String) = PluginInFlightRecord(
        pluginId = pluginId,
        pluginVersion = "1.0.0",
        generation = 1L,
        phase = PluginFaultPhase.STARTUP,
        executionId = UUID.randomUUID().toString(),
        startedAtMillis = 1L,
    )

    private companion object {
        const val PREFS_NAME = "tinaide_plugin_runtime_state"
        const val KEY_IN_FLIGHT = "in_flight"
    }
}
