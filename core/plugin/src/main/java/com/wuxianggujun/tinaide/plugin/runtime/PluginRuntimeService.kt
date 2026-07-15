package com.wuxianggujun.tinaide.plugin.runtime

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginHostBridge
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginRuntimeCallback
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginRuntimeService
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray

/** Minimal, permissionless process that owns every Lua VM. */
class PluginRuntimeService : Service() {
    companion object {
        private const val MAX_PROCESS_PSS_KB = 192 * 1024
        private const val MAX_OPERATION_PSS_DELTA_KB = 64 * 1024
        private const val STACK_TRACE_LIMIT = 16 * 1024
    }

    private val json = JsonSerializer.default
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tina-plugin-runtime").apply { isDaemon = true }
    }
    private val monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tina-plugin-watchdog").apply { isDaemon = true }
    }
    private val runtimes = LinkedHashMap<String, RuntimeEntry>()

    private data class RuntimeEntry(
        val generation: Long,
        val runtime: IsolatedLuaRuntime,
    )

    private val binder = object : IPluginRuntimeService.Stub() {
        override fun getProcessId(): Int = Process.myPid()

        override fun load(
            requestJson: String,
            mainSource: android.os.ParcelFileDescriptor,
            hostBridge: IPluginHostBridge,
            callback: IPluginRuntimeCallback,
        ) {
            val request = json.decodeFromString<PluginRuntimeLoadRequest>(requestJson)
            executor.execute {
                runOperation(request.pluginId, request.generation, request.callId, callback) {
                    runtimes.remove(request.pluginId)?.runtime?.close()
                    val runtime = IsolatedLuaRuntime(request, hostBridge)
                    runtime.initialize(mainSource)
                    runtimes[request.pluginId] = RuntimeEntry(request.generation, runtime)
                    JsonArray(emptyList())
                }
            }
        }

        override fun invoke(requestJson: String, callback: IPluginRuntimeCallback) {
            val request = json.decodeFromString<PluginRuntimeInvokeRequest>(requestJson)
            executor.execute {
                runOperation(request.pluginId, request.generation, request.callId, callback) {
                    val entry = runtimes[request.pluginId]
                        ?: throw IllegalStateException("Plugin runtime is not loaded")
                    if (entry.generation != request.generation) {
                        return@runOperation JsonArray(emptyList()) to PluginRuntimeResponseStatus.STALE_GENERATION
                    }
                    entry.runtime.call(request.functionName, request.args)
                }
            }
        }

        override fun unload(requestJson: String, callback: IPluginRuntimeCallback) {
            val request = json.decodeFromString<PluginRuntimeUnloadRequest>(requestJson)
            executor.execute {
                val entry = runtimes[request.pluginId]
                if (entry != null && entry.generation <= request.generation) {
                    runtimes.remove(request.pluginId)?.runtime?.close()
                }
                callback.safeComplete(
                    PluginRuntimeResponse(
                        pluginId = request.pluginId,
                        generation = request.generation,
                        callId = request.callId,
                        status = PluginRuntimeResponseStatus.SUCCESS,
                    ),
                )
            }
        }

        override fun terminate() {
            Process.killProcess(Process.myPid())
        }

        override fun crashWithSigsegvForTest() {
            check(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "Native crash injection is restricted to debuggable builds"
            }
            Os.kill(Process.myPid(), OsConstants.SIGSEGV)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runtimes.values.forEach { entry -> entry.runtime.close() }
        runtimes.clear()
        executor.shutdownNow()
        monitor.shutdownNow()
        super.onDestroy()
    }

    private fun runOperation(
        pluginId: String,
        generation: Long,
        callId: String,
        callback: IPluginRuntimeCallback,
        block: () -> Any,
    ) {
        val completed = AtomicBoolean(false)
        val baselinePss = Debug.getPss()
        var memoryWatch: ScheduledFuture<*>? = null
        memoryWatch = monitor.scheduleAtFixedRate(
            {
                val currentPss = Debug.getPss()
                if (currentPss > MAX_PROCESS_PSS_KB || currentPss - baselinePss > MAX_OPERATION_PSS_DELTA_KB) {
                    if (completed.compareAndSet(false, true)) {
                        callback.safeComplete(
                            PluginRuntimeResponse(
                                pluginId = pluginId,
                                generation = generation,
                                callId = callId,
                                status = PluginRuntimeResponseStatus.RESOURCE_LIMIT,
                                error = "Plugin runtime memory limit exceeded",
                            ),
                        )
                    }
                    Process.killProcess(Process.myPid())
                }
            },
            250L,
            250L,
            TimeUnit.MILLISECONDS,
        )

        try {
            val raw = block()
            val (values, status) = when (raw) {
                is Pair<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (raw.first as JsonArray) to (raw.second as PluginRuntimeResponseStatus)
                }
                else -> (raw as JsonArray) to PluginRuntimeResponseStatus.SUCCESS
            }
            if (completed.compareAndSet(false, true)) {
                callback.safeComplete(
                    PluginRuntimeResponse(pluginId, generation, callId, status, values),
                )
            }
        } catch (error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                callback.safeComplete(
                    PluginRuntimeResponse(
                        pluginId = pluginId,
                        generation = generation,
                        callId = callId,
                        status = PluginRuntimeResponseStatus.PLUGIN_ERROR,
                        error = error.message ?: error::class.java.simpleName,
                        stack = error.stackTraceToString().take(STACK_TRACE_LIMIT),
                    ),
                )
            }
        } finally {
            memoryWatch?.cancel(false)
        }
    }

    private fun IPluginRuntimeCallback.safeComplete(response: PluginRuntimeResponse) {
        val encoded = json.encodeToString(response)
        val boundedResponse = if (encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_BINDER_JSON_BYTES) {
            encoded
        } else {
            json.encodeToString(
                response.copy(
                    status = PluginRuntimeResponseStatus.RESOURCE_LIMIT,
                    values = JsonArray(emptyList()),
                    error = "Plugin runtime response exceeds Binder limit",
                    stack = null,
                ),
            )
        }
        runCatching { onComplete(boundedResponse) }
    }
}
