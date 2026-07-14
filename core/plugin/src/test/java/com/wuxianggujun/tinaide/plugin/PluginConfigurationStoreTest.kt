package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.script.api.PluginEvent
import com.wuxianggujun.tinaide.plugin.script.api.PluginEventBus
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonPrimitive
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
class PluginConfigurationStoreTest {

    private lateinit var context: Application
    private lateinit var store: PluginConfigurationStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tina_plugin_configuration", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = PluginConfigurationStore.getInstance(context)
        PluginEventBus.clear()
    }

    @After
    fun tearDown() {
        PluginEventBus.clear()
    }

    @Test
    fun `configuration store should isolate values by plugin id and fall back to defaults`() {
        val firstManifest = manifest(
            id = "demo.first",
            defaultEnabled = false,
        )
        val secondManifest = manifest(
            id = "demo.second",
            defaultEnabled = false,
        )

        assertThat(store.getValue(firstManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))
        assertThat(store.getValue(secondManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))

        assertThat(store.setValue(firstManifest, "feature.enabled", JsonPrimitive(true))).isTrue()

        assertThat(store.getValue(firstManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(true))
        assertThat(store.getValue(secondManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))

        assertThat(store.resetValue(firstManifest, "feature.enabled")).isTrue()
        assertThat(store.getValue(firstManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun `configuration store should reject undeclared keys and invalid values`() {
        val manifest = manifest(
            id = "demo.validation",
            defaultEnabled = false,
        )

        assertThat(store.setValue(manifest, "missing.key", JsonPrimitive(true))).isFalse()
        assertThat(store.setValue(manifest, "feature.enabled", JsonPrimitive("true"))).isFalse()
        assertThat(store.setValue(manifest, "output.format", JsonPrimitive("xml"))).isFalse()
        assertThat(store.setValue(manifest, "output.format", JsonPrimitive("json"))).isTrue()

        assertThat(store.getValue(manifest, "output.format")).isEqualTo(JsonPrimitive("json"))
    }

    @Test
    fun `configuration store should use a type-safe fallback only after manifest default`() {
        val manifest = manifest(
            id = "demo.fallback",
            defaultEnabled = false,
        )

        assertThat(
            store.getValue(manifest, "optional.label", JsonPrimitive("fallback")),
        ).isEqualTo(JsonPrimitive("fallback"))
        assertThat(
            store.getValue(manifest, "optional.label", JsonPrimitive(42)),
        ).isNull()
        assertThat(
            store.getValue(manifest, "output.format", JsonPrimitive("fallback")),
        ).isEqualTo(JsonPrimitive("text"))
    }

    @Test
    fun `configuration store should emit targeted config changed events`() {
        val manifest = manifest(
            id = "demo.events",
            defaultEnabled = false,
        )
        val calls = CopyOnWriteArrayList<Triple<String, String, Map<String, Any?>?>>()
        PluginEventBus.setCallbackInvoker { pluginId, callbackName, payload ->
            calls += Triple(pluginId, callbackName, payload)
            PluginExecutionResult.Success(Unit)
        }
        PluginEventBus.subscribe(manifest.id, PluginEvent.CONFIG_CHANGED.id, "onConfigChanged")
        PluginEventBus.subscribe("demo.other", PluginEvent.CONFIG_CHANGED.id, "onConfigChanged")

        assertThat(store.setValue(manifest, "feature.enabled", JsonPrimitive(true))).isTrue()

        waitUntil { calls.isNotEmpty() }
        assertThat(calls).hasSize(1)
        val (pluginId, callbackName, payload) = calls.single()
        assertThat(pluginId).isEqualTo(manifest.id)
        assertThat(callbackName).isEqualTo("onConfigChanged")
        assertThat(payload?.get("pluginId")).isEqualTo(manifest.id)
        assertThat(payload?.get("key")).isEqualTo("feature.enabled")
        assertThat(payload?.get("value")).isEqualTo(true)
        assertThat(payload?.get("previousValue")).isEqualTo(false)
    }

    private fun manifest(
        id: String,
        defaultEnabled: Boolean,
    ): PluginManifest = PluginManifest(
        id = id,
        name = "Configuration Test",
        version = "1.0.0",
        configuration = PluginConfiguration(
            title = "Configuration",
            properties = mapOf(
                "feature.enabled" to PluginConfigurationProperty(
                    type = "boolean",
                    default = JsonPrimitive(defaultEnabled),
                ),
                "output.format" to PluginConfigurationProperty(
                    type = "string",
                    default = JsonPrimitive("text"),
                    enumValues = listOf("text", "json"),
                ),
                "optional.label" to PluginConfigurationProperty(
                    type = "string",
                ),
            ),
        ),
    )

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
