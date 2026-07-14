package com.wuxianggujun.tinaide.plugin

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostEventDispatcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

class PluginConfigurationStore private constructor(
    context: Context,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun getValue(
        manifest: PluginManifest,
        propertyKey: String,
        fallback: JsonElement? = null,
    ): JsonElement? {
        val property = PluginConfigurationSchema.resolveProperty(manifest, propertyKey) ?: return null
        val storedValue = prefs.getString(buildPreferenceKey(manifest.id, propertyKey), null)
            ?.let(JsonSerializer::parseToJsonElementOrNull)
            ?.let { value -> PluginConfigurationSchema.normalizeValue(property, value) }
        val normalizedFallback = fallback?.let { value ->
            PluginConfigurationSchema.normalizeValue(property, value)
        }
        return storedValue ?: property.defaultValue ?: normalizedFallback
    }

    fun setValue(
        manifest: PluginManifest,
        propertyKey: String,
        value: JsonElement,
    ): Boolean {
        val property = PluginConfigurationSchema.resolveProperty(manifest, propertyKey) ?: return false
        val normalizedValue = PluginConfigurationSchema.normalizeValue(property, value) ?: return false
        val previousValue = getValue(manifest, propertyKey)
        prefs.edit()
            .putString(buildPreferenceKey(manifest.id, propertyKey), normalizedValue.toString())
            .apply()
        emitChangedIfNeeded(
            pluginId = manifest.id,
            property = property,
            previousValue = previousValue,
            nextValue = normalizedValue,
        )
        return true
    }

    fun resetValue(
        manifest: PluginManifest,
        propertyKey: String,
    ): Boolean {
        val property = PluginConfigurationSchema.resolveProperty(manifest, propertyKey) ?: return false
        val previousValue = getValue(manifest, propertyKey)
        prefs.edit()
            .remove(buildPreferenceKey(manifest.id, propertyKey))
            .apply()
        emitChangedIfNeeded(
            pluginId = manifest.id,
            property = property,
            previousValue = previousValue,
            nextValue = property.defaultValue,
        )
        return true
    }

    fun clearPlugin(pluginId: String) {
        val prefix = "$pluginId:"
        val keys = prefs.all.keys.filter { key -> key.startsWith(prefix) }
        if (keys.isEmpty()) return
        prefs.edit().apply {
            keys.forEach(::remove)
        }.apply()
    }

    private fun emitChangedIfNeeded(
        pluginId: String,
        property: ResolvedPluginConfigurationProperty,
        previousValue: JsonElement?,
        nextValue: JsonElement?,
    ) {
        val previousEventValue = previousValue.toEventValue(property)
        val nextEventValue = nextValue.toEventValue(property)
        if (previousEventValue == nextEventValue) return
        PluginHostEventDispatcher.emitConfigChanged(
            pluginId = pluginId,
            key = property.key,
            value = nextEventValue,
            previousValue = previousEventValue,
        )
    }

    private fun JsonElement?.toEventValue(
        property: ResolvedPluginConfigurationProperty,
    ): Any? {
        val primitive = this?.jsonPrimitive ?: return null
        return when (property.type) {
            PluginConfigurationPropertyType.BOOLEAN -> primitive.booleanOrNull
            PluginConfigurationPropertyType.NUMBER -> primitive.doubleOrNull
            PluginConfigurationPropertyType.STRING -> PluginConfigurationSchema.stringValue(this)
        }
    }

    companion object {
        private const val PREFS_NAME = "tina_plugin_configuration"

        @Volatile
        private var instance: PluginConfigurationStore? = null

        fun getInstance(context: Context): PluginConfigurationStore = instance ?: synchronized(this) {
            instance ?: PluginConfigurationStore(context).also { store -> instance = store }
        }

        internal fun buildPreferenceKey(
            pluginId: String,
            propertyKey: String,
        ): String = "$pluginId:$propertyKey"
    }
}
