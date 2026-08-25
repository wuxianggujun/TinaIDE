package com.wuxianggujun.tinaide.plugin.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.isSafePluginRelativePath
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginHostBridge
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginRuntimeCallback
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginRuntimeService
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class PluginRuntimeUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class PluginRuntimeClient(
    context: Context,
    private val pluginManager: PluginManager,
    projectRootProvider: () -> String?,
    private val isGenerationCurrent: (String, Long) -> Boolean,
) : PluginRuntimeTransport {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val WATCHDOG_POLL_MS = 100L
        private const val RUNTIME_DEATH_GRACE_MS = 500L
        private val MODULE_NAME_PATTERN = Regex("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*$")
    }

    private val appContext = context.applicationContext
    private val json = JsonSerializer.default
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serviceRef = AtomicReference<IPluginRuntimeService?>()
    private val connectionRef = AtomicReference<RuntimeServiceConnection?>()
    private val activeCall = AtomicReference<ActiveCall?>()
    private val moduleBytesByRuntime = ConcurrentHashMap<String, AtomicLong>()
    private val gateway = PluginHostCapabilityGateway(
        context = appContext,
        pluginManager = pluginManager,
        projectRootProvider = projectRootProvider,
        isGenerationCurrent = isGenerationCurrent,
    )

    @Volatile
    private var deathListener: (() -> Unit)? = null

    private data class ActiveCall(
        val pluginId: String,
        val generation: Long,
        val callId: String,
        val startedNanos: Long,
        val luaSegmentStartedNanos: AtomicLong,
        val hostCallDepth: AtomicLong,
        val completionClaimed: AtomicBoolean,
        val deferred: CompletableDeferred<PluginRuntimeResponse>,
    )

    private inner class RuntimeServiceConnection : ServiceConnection {
        val deferred = CompletableDeferred<IPluginRuntimeService>()
        val deathHandled = AtomicBoolean(false)
        val deathRecipient = IBinder.DeathRecipient { handleBinderDeath(this) }

        @Volatile
        var binder: IBinder? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                onNullBinding(name)
                return
            }
            this.binder = binder
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (error: Throwable) {
                disconnect(this, error)
                return
            }
            val service = IPluginRuntimeService.Stub.asInterface(binder)
            val accepted = synchronized(this@PluginRuntimeClient) {
                if (connectionRef.get() !== this) {
                    false
                } else {
                    serviceRef.set(service)
                    deferred.complete(service)
                    true
                }
            }
            if (!accepted) {
                runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleBinderDeath(this)

        override fun onBindingDied(name: ComponentName?) = handleBinderDeath(this)

        override fun onNullBinding(name: ComponentName?) {
            val error = IllegalStateException("Plugin runtime service returned a null binding")
            disconnect(this, error)
        }
    }

    private val hostBridge = object : IPluginHostBridge.Stub() {
        override fun call(requestJson: String): String {
            requireSize(requestJson, "Plugin API request")
            val request = json.decodeFromString<PluginHostCallRequest>(requestJson)
            markHostCallStarted(request.pluginId, request.generation)
            return try {
                json.encodeToString(gateway.call(request)).also { response ->
                    requireSize(response, "Plugin API response")
                }
            } finally {
                markHostCallFinished(request.pluginId, request.generation)
            }
        }

        override fun openLuaModule(requestJson: String): ParcelFileDescriptor? {
            requireSize(requestJson, "Lua module request")
            val request = json.decodeFromString<PluginLuaModuleRequest>(requestJson)
            markHostCallStarted(request.pluginId, request.generation)
            return try {
                openModule(request)
            } finally {
                markHostCallFinished(request.pluginId, request.generation)
            }
        }

        override fun openPayload(token: String): ParcelFileDescriptor? = gateway.openPayload(token)
    }

    override fun setDeathListener(listener: () -> Unit) {
        deathListener = listener
    }

    override suspend fun load(
        plugin: InstalledPlugin,
        generation: Long,
        callId: String,
    ): PluginRuntimeResponse {
        val mainEntry = plugin.manifest.main ?: "main.lua"
        require(isSafePluginRelativePath(mainEntry)) { "Unsafe plugin main path" }
        val mainFile = resolveInside(plugin.directory, mainEntry)
        require(mainFile.isFile) { "Plugin main file does not exist: $mainEntry" }
        require(mainFile.length() <= MAX_LUA_SOURCE_BYTES) { "Plugin main file exceeds $MAX_LUA_SOURCE_BYTES bytes" }
        moduleBytesByRuntime[runtimeKey(plugin.manifest.id, generation)] = AtomicLong(mainFile.length())
        try {
            val request = PluginRuntimeLoadRequest(
                pluginId = plugin.manifest.id,
                pluginName = plugin.manifest.name,
                version = plugin.manifest.version,
                apiVersion = plugin.manifest.apiVersion,
                generation = generation,
                callId = callId,
            )
            val service = connect()
            val descriptor = ParcelFileDescriptor.open(mainFile, ParcelFileDescriptor.MODE_READ_ONLY)
            return executeCall(plugin.manifest.id, generation, callId) { callback ->
                try {
                    service.load(encode(request), descriptor, hostBridge, callback)
                } finally {
                    descriptor.close()
                }
            }.also { response ->
                if (response.status != PluginRuntimeResponseStatus.SUCCESS) {
                    clearModuleCounters(plugin.manifest.id)
                }
            }
        } catch (error: Throwable) {
            clearModuleCounters(plugin.manifest.id)
            throw error
        }
    }

    override suspend fun invoke(request: PluginRuntimeInvokeRequest): PluginRuntimeResponse {
        val service = connect()
        return executeCall(request.pluginId, request.generation, request.callId) { callback ->
            service.invoke(encode(request), callback)
        }
    }

    override suspend fun unload(request: PluginRuntimeUnloadRequest): PluginRuntimeResponse {
        try {
            val service = serviceRef.get()
            if (service == null) {
                return PluginRuntimeResponse(
                    request.pluginId,
                    request.generation,
                    request.callId,
                    PluginRuntimeResponseStatus.SUCCESS,
                )
            }
            return executeCall(request.pluginId, request.generation, request.callId) { callback ->
                service.unload(encode(request), callback)
            }
        } finally {
            clearModuleCounters(request.pluginId)
            gateway.cleanupPlugin(request.pluginId)
        }
    }

    fun terminate() {
        val connection = connectionRef.get()
        val service = serviceRef.get()
        disconnect(connection)
        runCatching { service?.terminate() }
    }

    fun runtimeProcessId(): Int? = runCatching { serviceRef.get()?.processId }.getOrNull()

    internal fun requestRuntimeProcessTermination(): Boolean {
        val service = serviceRef.get() ?: return false
        return runCatching { service.terminate() }.isSuccess
    }

    internal fun requestRuntimeNativeCrashForTest(): Boolean {
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        val service = serviceRef.get() ?: return false
        return runCatching { service.crashWithSigsegvForTest() }.isSuccess
    }

    private fun terminateRuntimeAfterFault() {
        val connection = connectionRef.get() ?: return
        if (!requestRuntimeProcessTermination()) {
            handleBinderDeath(connection)
            return
        }
        scope.launch {
            delay(RUNTIME_DEATH_GRACE_MS)
            if (connectionRef.get() === connection) {
                handleBinderDeath(connection)
            }
        }
    }

    override fun cancelActiveCall(pluginId: String): Boolean {
        val call = activeCall.get()?.takeIf { active -> active.pluginId == pluginId } ?: return false
        if (!call.completionClaimed.compareAndSet(false, true)) return false
        val response = PluginRuntimeResponse(
            pluginId = call.pluginId,
            generation = call.generation,
            callId = call.callId,
            status = PluginRuntimeResponseStatus.STALE_GENERATION,
            error = "Plugin operation was cancelled",
        )
        terminate()
        call.deferred.complete(response)
        return true
    }

    override fun shutdown() {
        deathListener = null
        terminate()
        gateway.cleanup()
        scope.cancel()
    }

    private suspend fun connect(): IPluginRuntimeService {
        serviceRef.get()?.let { return it }
        val connection = synchronized(this) {
            serviceRef.get()?.let { return it }
            connectionRef.get() ?: RuntimeServiceConnection().also { created ->
                connectionRef.set(created)
                val intent = Intent(appContext, PluginRuntimeService::class.java)
                if (!appContext.bindService(intent, created, Context.BIND_AUTO_CREATE)) {
                    disconnect(created, IllegalStateException("Unable to bind plugin runtime service"))
                }
            }
        }
        return try {
            withTimeout(CONNECT_TIMEOUT_MS) { connection.deferred.await() }
        } catch (error: Throwable) {
            disconnect(connection)
            throw PluginRuntimeUnavailableException("Plugin runtime service is unavailable", error)
        }
    }

    private suspend fun executeCall(
        pluginId: String,
        generation: Long,
        callId: String,
        dispatch: (IPluginRuntimeCallback) -> Unit,
    ): PluginRuntimeResponse {
        val deferred = CompletableDeferred<PluginRuntimeResponse>()
        val now = System.nanoTime()
        val call = ActiveCall(
            pluginId = pluginId,
            generation = generation,
            callId = callId,
            startedNanos = now,
            luaSegmentStartedNanos = AtomicLong(now),
            hostCallDepth = AtomicLong(0),
            completionClaimed = AtomicBoolean(false),
            deferred = deferred,
        )
        check(activeCall.compareAndSet(null, call)) { "Plugin runtime calls must be serialized" }
        val callback = object : IPluginRuntimeCallback.Stub() {
            override fun onComplete(responseJson: String) {
                if (!call.completionClaimed.compareAndSet(false, true)) return
                runCatching {
                    requireSize(responseJson, "Plugin runtime response")
                    json.decodeFromString<PluginRuntimeResponse>(responseJson)
                }.fold(deferred::complete) { error -> deferred.completeExceptionally(error) }
            }
        }
        val watchdog = startWatchdog(call)
        return try {
            dispatch(callback)
            deferred.await()
        } finally {
            watchdog.cancel()
            activeCall.compareAndSet(call, null)
        }
    }

    private fun startWatchdog(call: ActiveCall): Job = scope.launch {
        while (!call.deferred.isCompleted) {
            delay(WATCHDOG_POLL_MS)
            val now = System.nanoTime()
            val totalMs = TimeUnit.NANOSECONDS.toMillis(now - call.startedNanos)
            val luaMs = TimeUnit.NANOSECONDS.toMillis(now - call.luaSegmentStartedNanos.get())
            val timedOut = totalMs > MAX_PLUGIN_CALL_DURATION_MS ||
                (call.hostCallDepth.get() == 0L && luaMs > DEFAULT_PLUGIN_EXECUTION_TIMEOUT_MS)
            if (timedOut && call.completionClaimed.compareAndSet(false, true)) {
                val response = PluginRuntimeResponse(
                    pluginId = call.pluginId,
                    generation = call.generation,
                    callId = call.callId,
                    status = PluginRuntimeResponseStatus.TIMEOUT,
                    error = "Plugin execution timed out",
                )
                terminateRuntimeAfterFault()
                call.deferred.complete(response)
                break
            }
        }
    }

    private fun markHostCallStarted(pluginId: String, generation: Long) {
        activeCall.get()?.takeIf { it.pluginId == pluginId && it.generation == generation }
            ?.hostCallDepth
            ?.incrementAndGet()
    }

    private fun markHostCallFinished(pluginId: String, generation: Long) {
        activeCall.get()?.takeIf { it.pluginId == pluginId && it.generation == generation }?.let { call ->
            call.hostCallDepth.updateAndGet { depth -> maxOf(0, depth - 1) }
            call.luaSegmentStartedNanos.set(System.nanoTime())
        }
    }

    private fun openModule(request: PluginLuaModuleRequest): ParcelFileDescriptor? {
        if (!isGenerationCurrent(request.pluginId, request.generation)) return null
        if (!MODULE_NAME_PATTERN.matches(request.moduleName) || ".." in request.moduleName) return null
        val plugin = pluginManager.getInstalledPlugin(request.pluginId)?.takeIf { it.enabled } ?: return null
        val relativePath = request.moduleName.replace('.', '/') + ".lua"
        val moduleFile = resolveInside(plugin.directory, relativePath)
        if (!moduleFile.isFile || moduleFile.length() > MAX_LUA_SOURCE_BYTES) return null
        val counter = moduleBytesByRuntime.computeIfAbsent(runtimeKey(request.pluginId, request.generation)) { AtomicLong() }
        if (counter.addAndGet(moduleFile.length()) > MAX_LUA_SOURCE_TOTAL_BYTES) return null
        return ParcelFileDescriptor.open(moduleFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolveInside(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(root, relativePath).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) { "Plugin path escaped its root" }
        return target
    }

    private fun handleBinderDeath(connection: RuntimeServiceConnection) {
        if (!connection.deathHandled.compareAndSet(false, true)) return
        if (connectionRef.get() !== connection) return
        disconnect(connection)
        moduleBytesByRuntime.clear()
        gateway.cleanup()
        val call = activeCall.get()
        if (call != null && call.completionClaimed.compareAndSet(false, true)) {
            val response = PluginRuntimeResponse(
                pluginId = call.pluginId,
                generation = call.generation,
                callId = call.callId,
                status = PluginRuntimeResponseStatus.RUNTIME_ERROR,
                error = "Plugin runtime process terminated unexpectedly",
            )
            call.deferred.complete(response)
        }
        deathListener?.invoke()
    }

    private fun disconnect(
        connection: RuntimeServiceConnection?,
        cause: Throwable? = null,
    ) {
        if (connection == null) return
        val detached = synchronized(this) {
            if (connectionRef.get() !== connection) {
                false
            } else {
                connectionRef.set(null)
                serviceRef.set(null)
                if (cause != null) {
                    connection.deferred.completeExceptionally(cause)
                } else {
                    connection.deferred.cancel()
                }
                true
            }
        }
        if (!detached) return
        connection.binder?.let { binder ->
            runCatching { binder.unlinkToDeath(connection.deathRecipient, 0) }
        }
        runCatching { appContext.unbindService(connection) }
    }

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value).also {
        requireSize(it, "Plugin runtime request")
    }

    private fun requireSize(value: String, label: String) {
        if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_BINDER_JSON_BYTES) {
            throw PluginRuntimePayloadTooLargeException(label)
        }
    }

    internal fun clearModuleCounters(pluginId: String) {
        val prefix = "$pluginId:"
        moduleBytesByRuntime.keys
            .filter { key -> key.startsWith(prefix) }
            .forEach(moduleBytesByRuntime::remove)
    }

    private fun runtimeKey(pluginId: String, generation: Long): String = "$pluginId:$generation"
}
