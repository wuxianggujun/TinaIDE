package com.wuxianggujun.tinaide.plugin.runtime

import android.content.Context
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginManager

/**
 * Host-side transport boundary for the isolated plugin process.
 *
 * JVM tests replace this interface with a fake. Production must use Binder and
 * must never execute Lua/JNI inside the host process.
 */
internal interface PluginRuntimeTransport {
    fun setDeathListener(listener: () -> Unit)

    suspend fun load(
        plugin: InstalledPlugin,
        generation: Long,
        callId: String,
    ): PluginRuntimeResponse

    suspend fun invoke(request: PluginRuntimeInvokeRequest): PluginRuntimeResponse

    suspend fun unload(request: PluginRuntimeUnloadRequest): PluginRuntimeResponse

    fun cancelActiveCall(pluginId: String): Boolean = false

    fun shutdown()
}

internal fun interface PluginRuntimeTransportFactory {
    fun create(
        context: Context,
        pluginManager: PluginManager,
        projectRootProvider: () -> String?,
        isGenerationCurrent: (String, Long) -> Boolean,
    ): PluginRuntimeTransport
}

internal val BinderPluginRuntimeTransportFactory = PluginRuntimeTransportFactory { context, manager, rootProvider, generationCheck ->
    PluginRuntimeClient(context, manager, rootProvider, generationCheck)
}
