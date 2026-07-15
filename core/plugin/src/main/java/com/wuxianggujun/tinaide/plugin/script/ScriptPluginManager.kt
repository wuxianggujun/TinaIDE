package com.wuxianggujun.tinaide.plugin.script

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginFaultKind
import com.wuxianggujun.tinaide.plugin.PluginFaultPhase
import com.wuxianggujun.tinaide.plugin.PluginFaultRecord
import com.wuxianggujun.tinaide.plugin.PluginFaultStore
import com.wuxianggujun.tinaide.plugin.PluginEffectiveStatus
import com.wuxianggujun.tinaide.plugin.PluginInFlightRecord
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginRuntimeLifecycle
import com.wuxianggujun.tinaide.plugin.runtime.BinderPluginRuntimeTransportFactory
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeInvokeRequest
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimePayloadTooLargeException
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeResponse
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeResponseStatus
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeTransportFactory
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeUnloadRequest
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeUnavailableException
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandAvailability
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.plugin.script.api.PluginEventBus
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import timber.log.Timber

enum class ScriptPluginState {
    UNLOADED,
    LOADING,
    ACTIVE,
    ERROR,
    DISABLED,
    WAITING_PERMISSION,
    QUARANTINED,
    RUNTIME_UNAVAILABLE,
}

data class ScriptPluginInfo(
    val pluginId: String,
    val state: ScriptPluginState,
    val error: String? = null,
    val generation: Long = 0L,
    val fault: PluginFaultRecord? = null,
)

/** Host-side coordinator. No Lua/JNI code is executed in this process. */
class ScriptPluginManager internal constructor(
    private val context: Context,
    private val pluginManager: PluginManager,
    private val runtimeTransportFactory: PluginRuntimeTransportFactory = BinderPluginRuntimeTransportFactory,
) {
    companion object {
        private const val TAG = "ScriptPluginManager"
        private val HOST_PATH_KEYS = setOf(
            "filePath",
            "fileUri",
            "rootPath",
            "oldPath",
            "newPath",
            "requestedFilePath",
        )

        @Volatile
        private var instance: ScriptPluginManager? = null

        fun getInstance(context: Context): ScriptPluginManager = instance ?: synchronized(this) {
            instance ?: ScriptPluginManager(
                context.applicationContext,
                PluginManager.getInstance(context),
            ).also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operationMutex = Mutex()
    private val permissionManager = PluginPermissionManager.getInstance(context)
    private val faultStore = PluginFaultStore.getInstance(context)
    private val logManager = PluginLogManager.getInstance(context)
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val loadedGenerations = ConcurrentHashMap<String, Long>()
    private val loadedVersions = ConcurrentHashMap<String, String>()
    private var projectRootProvider: () -> String? = { null }
    private val runtimeWorkspaceAccess =
        com.wuxianggujun.tinaide.plugin.script.api.PluginWorkspaceFileAccess { projectRootProvider() }
    private val lifecycleHandler = PluginRuntimeLifecycle.Handler(
        stop = { pluginId ->
            nextGeneration(pluginId)
            runtimeClient.cancelActiveCall(pluginId)
            operationMutex.withLock {
                unloadPluginLocked(pluginId, ScriptPluginState.UNLOADED)
            }
        },
        activate = { pluginId ->
            operationMutex.withLock {
                runCatching {
                    pluginManager.refreshInstalledPlugins()
                    val plugin = pluginManager.getInstalledPlugin(pluginId) ?: return@runCatching
                    if (isScriptPlugin(plugin) && plugin.enabled) loadPluginLocked(plugin).getOrThrow()
                }
            }
        },
    )

    private val runtimeClient by lazy {
        runtimeTransportFactory.create(
            context = context,
            pluginManager = pluginManager,
            projectRootProvider = { projectRootProvider() },
            isGenerationCurrent = ::isGenerationCurrent,
        )
    }

    private val _pluginStates = MutableStateFlow<Map<String, ScriptPluginInfo>>(emptyMap())
    val pluginStates: StateFlow<Map<String, ScriptPluginInfo>> = _pluginStates.asStateFlow()

    init {
        PluginRuntimeLifecycle.register(lifecycleHandler)
        runtimeClient.setDeathListener {
            scope.launch { recoverAfterRuntimeDeath() }
        }
        PluginEventBus.setCallbackInvoker { pluginId, callbackName, data ->
            executeInPlugin(pluginId, callbackName, data)
        }
        PluginCommandRegistry.setRuntimeAccess(
            callbackInvoker = { pluginId, callbackName, data ->
                executeInPlugin(pluginId, callbackName, data)
            },
            permissionChecker = ::checkCommandPermission,
        )

        scope.launch {
            try {
                pluginManager.awaitInitialization()
                recoverInterruptedExecution()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "Plugin startup recovery failed; isolated runtimes remain disabled")
                return@launch
            }

            combine(
                pluginManager.pluginStateFlow,
                permissionManager.grantsFlow,
            ) { snapshot, _ -> snapshot.installedPlugins }
                .collect { installedPlugins ->
                    try {
                        operationMutex.withLock { syncWithInstalledPluginsLocked(installedPlugins) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        Timber.tag(TAG).e(error, "Failed to synchronize isolated plugin runtimes")
                    }
                }
        }
    }

    fun setProjectRootProvider(provider: () -> String?) {
        projectRootProvider = provider
    }

    suspend fun loadPlugin(plugin: InstalledPlugin): Result<Unit> = operationMutex.withLock {
        loadPluginLocked(plugin)
    }

    suspend fun unloadPlugin(
        pluginId: String,
        nextState: ScriptPluginState = ScriptPluginState.UNLOADED,
        error: String? = null,
    ) = operationMutex.withLock {
        unloadPluginLocked(pluginId, nextState, error)
    }

    suspend fun reloadPlugin(pluginId: String): Result<Unit> = operationMutex.withLock {
        runCatching {
            pluginManager.refreshInstalledPlugins()
            val plugin = pluginManager.getInstalledPlugin(pluginId)
                ?: throw IllegalArgumentException("Plugin not found: $pluginId")
            require(isScriptPlugin(plugin)) { "Reload is only supported for script plugins" }
            check(plugin.enabled) { "Plugin is disabled or quarantined" }
            unloadPluginLocked(pluginId, ScriptPluginState.UNLOADED)
            loadPluginLocked(plugin).getOrThrow()
        }
    }

    suspend fun executeInPlugin(
        pluginId: String,
        functionName: String,
        vararg args: Any?,
    ): PluginExecutionResult = operationMutex.withLock {
        val plugin = pluginManager.getEnabledPlugin(pluginId)
            ?: return@withLock PluginExecutionResult.Error("Plugin is disabled or quarantined: $pluginId")
        val generation = loadedGenerations[pluginId]
            ?: return@withLock PluginExecutionResult.Error("Plugin not loaded: $pluginId")
        if (!isGenerationCurrent(pluginId, generation)) {
            return@withLock PluginExecutionResult.Error("Plugin runtime generation is stale")
        }

        val callId = UUID.randomUUID().toString()
        val phase = inferFaultPhase(functionName)
        val inFlight = PluginInFlightRecord(
            pluginId = pluginId,
            pluginVersion = plugin.manifest.version,
            generation = generation,
            phase = phase,
            executionId = callId,
            startedAtMillis = System.currentTimeMillis(),
        )
        if (!faultStore.beginExecution(inFlight)) {
            updateState(pluginId, ScriptPluginState.RUNTIME_UNAVAILABLE, "Failed to persist plugin execution journal", generation)
            return@withLock PluginExecutionResult.Error("Plugin execution journal is unavailable")
        }

        val response = try {
            runtimeClient.invoke(
                PluginRuntimeInvokeRequest(
                    pluginId = pluginId,
                    generation = generation,
                    callId = callId,
                    functionName = functionName,
                    args = JsonArray(args.map { value -> sanitizeRuntimeValue(value).toJsonElement() }),
                ),
            )
        } catch (error: PluginRuntimeUnavailableException) {
            if (!faultStore.clearInFlight(callId)) {
                Timber.tag(TAG).e("Failed to clear plugin execution journal for %s", pluginId)
            }
            updateState(pluginId, ScriptPluginState.RUNTIME_UNAVAILABLE, error.message, generation)
            return@withLock PluginExecutionResult.Error(error.message ?: "Plugin runtime service is unavailable")
        } catch (error: PluginRuntimePayloadTooLargeException) {
            if (!faultStore.clearInFlight(callId)) {
                Timber.tag(TAG).e("Failed to clear oversized plugin execution journal for %s", pluginId)
            }
            return@withLock PluginExecutionResult.Error(Strings.plugin_error_event_payload_too_large.strOr(context))
        } catch (error: Throwable) {
            PluginRuntimeResponse(
                pluginId = pluginId,
                generation = generation,
                callId = callId,
                status = PluginRuntimeResponseStatus.RUNTIME_ERROR,
                error = error.message ?: error::class.java.simpleName,
                stack = error.stackTraceToString(),
            )
        }

        val journalCleared = faultStore.clearInFlight(callId)
        if (!isGenerationCurrent(pluginId, generation)) {
            return@withLock PluginExecutionResult.Error("Plugin operation was cancelled")
        }
        if (!journalCleared && response.status == PluginRuntimeResponseStatus.SUCCESS) {
            val message = "Failed to clear plugin execution journal"
            unloadPluginLocked(pluginId, ScriptPluginState.RUNTIME_UNAVAILABLE, message)
            return@withLock PluginExecutionResult.Error(message)
        }
        handleExecutionResponseLocked(plugin, response, phase)
    }

    fun getPermissionManager(): PluginPermissionManager = permissionManager

    fun shutdown() {
        PluginRuntimeLifecycle.unregister(lifecycleHandler)
        scope.cancel()
        runtimeClient.shutdown()
        loadedGenerations.clear()
        loadedVersions.clear()
        PluginEventBus.clear()
        PluginCommandRegistry.clear()
        _pluginStates.value = emptyMap()
        Timber.tag(TAG).i("ScriptPluginManager shutdown")
    }

    private suspend fun syncWithInstalledPluginsLocked(plugins: List<InstalledPlugin>) {
        val scriptPlugins = plugins.filter(::isScriptPlugin)
        val pluginsById = scriptPlugins.associateBy { it.manifest.id }

        loadedGenerations.keys.toList().forEach { pluginId ->
            val installed = pluginsById[pluginId]
            if (installed == null || !installed.enabled) {
                val nextState = when {
                    installed == null -> ScriptPluginState.UNLOADED
                    faultStore.isQuarantined(pluginId) -> ScriptPluginState.QUARANTINED
                    else -> ScriptPluginState.DISABLED
                }
                unloadPluginLocked(pluginId, nextState, faultStore.getFault(pluginId)?.message)
            }
        }

        scriptPlugins.filterNot { plugin -> plugin.enabled }.forEach { plugin ->
            val fault = faultStore.getFault(plugin.manifest.id)
            updateState(
                pluginId = plugin.manifest.id,
                state = if (fault != null) ScriptPluginState.QUARANTINED else ScriptPluginState.DISABLED,
                error = fault?.message,
                generation = currentGeneration(plugin.manifest.id),
                fault = fault,
            )
        }

        _pluginStates.update { current ->
            current.filterKeys { it in pluginsById }
        }

        for (plugin in scriptPlugins) {
            val pluginId = plugin.manifest.id
            val fault = faultStore.getFault(pluginId)
            when {
                fault != null -> updateState(pluginId, ScriptPluginState.QUARANTINED, fault.message, currentGeneration(pluginId), fault)
                !plugin.enabled -> updateState(pluginId, ScriptPluginState.DISABLED, generation = currentGeneration(pluginId))
                loadedGenerations.containsKey(pluginId) && loadedVersions[pluginId] == plugin.manifest.version -> {
                    updateState(pluginId, ScriptPluginState.ACTIVE, generation = loadedGenerations.getValue(pluginId))
                }
                loadedGenerations.containsKey(pluginId) -> {
                    unloadPluginLocked(pluginId, ScriptPluginState.UNLOADED)
                    loadPluginLocked(plugin).onFailure { error ->
                        Timber.tag(TAG).w(error, "Plugin update reload failed for %s", pluginId)
                    }
                }
                else -> loadPluginLocked(plugin).onFailure { error ->
                    Timber.tag(TAG).w(error, "Auto-load failed for plugin %s", pluginId)
                }
            }
        }
    }

    private suspend fun recoverAfterRuntimeDeath() = operationMutex.withLock {
        val affectedPluginIds = loadedGenerations.keys.toSet()
        loadedGenerations.clear()
        loadedVersions.clear()
        affectedPluginIds.forEach { pluginId ->
            PluginEventBus.unsubscribeAll(pluginId)
            PluginCommandRegistry.unregisterAll(pluginId)
            updateState(
                pluginId = pluginId,
                state = ScriptPluginState.RUNTIME_UNAVAILABLE,
                error = "Plugin runtime process restarted",
                generation = nextGeneration(pluginId),
                fault = faultStore.getFault(pluginId),
            )
        }

        pluginManager.listEnabledPlugins()
            .filter(::isScriptPlugin)
            .filterNot { plugin -> faultStore.isQuarantined(plugin.manifest.id) }
            .forEach { plugin ->
                loadPluginLocked(plugin).onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to restore plugin after runtime restart: %s", plugin.manifest.id)
                }
            }
    }

    private suspend fun loadPluginLocked(plugin: InstalledPlugin): Result<Unit> = runCatching {
        require(isScriptPlugin(plugin)) { "Not a script plugin" }
        check(plugin.enabled) { "Plugin is disabled or quarantined" }
        faultStore.getFault(plugin.manifest.id)?.let { fault ->
            updateState(plugin.manifest.id, ScriptPluginState.QUARANTINED, fault.message, fault = fault)
            throw IllegalStateException("Plugin is quarantined")
        }
        if (loadedGenerations.containsKey(plugin.manifest.id)) return@runCatching
        val missingRequiredPermissions = PluginPermission.parseList(plugin.manifest.permissions)
            .filterNot { permission -> permissionManager.hasPermission(plugin.manifest.id, permission) }
        if (missingRequiredPermissions.isNotEmpty()) {
            updateState(
                pluginId = plugin.manifest.id,
                state = ScriptPluginState.WAITING_PERMISSION,
                error = null,
            )
            return@runCatching
        }

        val generation = nextGeneration(plugin.manifest.id)
        val callId = UUID.randomUUID().toString()
        updateState(plugin.manifest.id, ScriptPluginState.LOADING, generation = generation)
        logManager.info(plugin.manifest.id, plugin.manifest.name, "Loading plugin in isolated runtime")
        val inFlight = PluginInFlightRecord(
            pluginId = plugin.manifest.id,
            pluginVersion = plugin.manifest.version,
            generation = generation,
            phase = PluginFaultPhase.STARTUP,
            executionId = callId,
            startedAtMillis = System.currentTimeMillis(),
        )
        check(faultStore.beginExecution(inFlight)) { "Failed to persist plugin startup journal" }

        val response = try {
            runtimeClient.load(plugin, generation, callId)
        } catch (error: PluginRuntimeUnavailableException) {
            if (!faultStore.clearInFlight(callId)) {
                Timber.tag(TAG).e("Failed to clear plugin startup journal for %s", plugin.manifest.id)
            }
            updateState(plugin.manifest.id, ScriptPluginState.RUNTIME_UNAVAILABLE, error.message, generation)
            throw error
        } catch (error: Throwable) {
            PluginRuntimeResponse(
                pluginId = plugin.manifest.id,
                generation = generation,
                callId = callId,
                status = PluginRuntimeResponseStatus.RUNTIME_ERROR,
                error = error.message ?: error::class.java.simpleName,
                stack = error.stackTraceToString(),
            )
        }
        val journalCleared = faultStore.clearInFlight(callId)

        if (currentGeneration(plugin.manifest.id) != generation) {
            runCatching {
                runtimeClient.unload(
                    PluginRuntimeUnloadRequest(plugin.manifest.id, generation, UUID.randomUUID().toString()),
                )
            }
            updateState(plugin.manifest.id, ScriptPluginState.UNLOADED, generation = generation)
            throw IllegalStateException("Plugin loading was cancelled")
        }

        if (response.status != PluginRuntimeResponseStatus.SUCCESS) {
            quarantineLocked(plugin, response.toFaultKind(startup = true), PluginFaultPhase.STARTUP, response)
            throw IllegalStateException(response.error ?: "Plugin startup failed")
        }
        if (!journalCleared) {
            val message = "Failed to clear plugin startup journal"
            unloadPluginLocked(plugin.manifest.id, ScriptPluginState.RUNTIME_UNAVAILABLE, message)
            throw IllegalStateException(message)
        }
        val current = pluginManager.getEnabledPlugin(plugin.manifest.id)
        if (current == null || !isGenerationCurrent(plugin.manifest.id, generation)) {
            runtimeClient.unload(PluginRuntimeUnloadRequest(plugin.manifest.id, generation, UUID.randomUUID().toString()))
            updateState(plugin.manifest.id, ScriptPluginState.DISABLED, generation = generation)
            throw IllegalStateException("Plugin was disabled while loading")
        }
        loadedGenerations[plugin.manifest.id] = generation
        loadedVersions[plugin.manifest.id] = plugin.manifest.version
        updateState(plugin.manifest.id, ScriptPluginState.ACTIVE, generation = generation)
        logManager.info(plugin.manifest.id, plugin.manifest.name, "Plugin loaded successfully in isolated runtime")
    }

    private suspend fun unloadPluginLocked(
        pluginId: String,
        nextState: ScriptPluginState,
        error: String? = null,
    ) {
        val generation = nextGeneration(pluginId)
        loadedGenerations.remove(pluginId)
        loadedVersions.remove(pluginId)
        PluginEventBus.unsubscribeAll(pluginId)
        PluginCommandRegistry.unregisterAll(pluginId)
        runCatching {
            runtimeClient.unload(
                PluginRuntimeUnloadRequest(pluginId, generation, UUID.randomUUID().toString()),
            )
        }
        updateState(pluginId, nextState, error, generation, faultStore.getFault(pluginId))
    }

    private suspend fun handleExecutionResponseLocked(
        plugin: InstalledPlugin,
        response: PluginRuntimeResponse,
        phase: PluginFaultPhase,
    ): PluginExecutionResult = when (response.status) {
        PluginRuntimeResponseStatus.SUCCESS -> PluginExecutionResult.Success(
            response.values.firstOrNull()?.toAnyValue(),
        )
        PluginRuntimeResponseStatus.STALE_GENERATION -> PluginExecutionResult.Error("Plugin runtime generation is stale")
        PluginRuntimeResponseStatus.TIMEOUT -> {
            quarantineLocked(plugin, PluginFaultKind.EXECUTION_TIMEOUT, phase, response)
            PluginExecutionResult.Timeout
        }
        PluginRuntimeResponseStatus.RESOURCE_LIMIT -> {
            quarantineLocked(plugin, PluginFaultKind.RESOURCE_LIMIT, phase, response)
            PluginExecutionResult.Error(response.error ?: "Plugin resource limit exceeded", response.stack)
        }
        PluginRuntimeResponseStatus.RUNTIME_ERROR -> {
            quarantineLocked(plugin, PluginFaultKind.RUNTIME_CRASH, phase, response)
            PluginExecutionResult.Error(response.error ?: "Plugin runtime crashed", response.stack)
        }
        PluginRuntimeResponseStatus.PLUGIN_ERROR -> {
            quarantineLocked(plugin, PluginFaultKind.UNHANDLED_EXCEPTION, phase, response)
            PluginExecutionResult.Error(response.error ?: "Plugin execution failed", response.stack)
        }
    }

    private suspend fun quarantineLocked(
        plugin: InstalledPlugin,
        kind: PluginFaultKind,
        phase: PluginFaultPhase,
        response: PluginRuntimeResponse,
    ) {
        val fault = PluginFaultRecord(
            pluginId = plugin.manifest.id,
            pluginVersion = plugin.manifest.version,
            phase = phase,
            kind = kind,
            message = response.error ?: kind.name,
            timestampMillis = System.currentTimeMillis(),
            executionId = response.callId,
        )
        loadedGenerations.remove(plugin.manifest.id)
        loadedVersions.remove(plugin.manifest.id)
        PluginEventBus.unsubscribeAll(plugin.manifest.id)
        PluginCommandRegistry.unregisterAll(plugin.manifest.id)
        runCatching {
            runtimeClient.unload(
                PluginRuntimeUnloadRequest(
                    plugin.manifest.id,
                    nextGeneration(plugin.manifest.id),
                    UUID.randomUUID().toString(),
                ),
            )
        }
        pluginManager.quarantinePlugin(fault, runtimeAlreadyStopped = true).getOrThrow()
        updateState(plugin.manifest.id, ScriptPluginState.QUARANTINED, fault.message, currentGeneration(plugin.manifest.id), fault)
        logManager.error(
            plugin.manifest.id,
            plugin.manifest.name,
            "Plugin automatically quarantined: ${kind.name}",
            response.stack,
        )
    }

    private suspend fun recoverInterruptedExecution() {
        val interrupted = faultStore.getInFlight() ?: return
        val plugin = pluginManager.getInstalledPlugin(interrupted.pluginId)
        val record = PluginFaultRecord(
            pluginId = interrupted.pluginId,
            pluginVersion = interrupted.pluginVersion,
            phase = interrupted.phase,
            kind = PluginFaultKind.INTERRUPTED_EXECUTION,
            message = "Plugin execution was interrupted before completion",
            timestampMillis = System.currentTimeMillis(),
            executionId = interrupted.executionId,
        )
        pluginManager.quarantinePlugin(record).getOrThrow()
        check(faultStore.clearInFlight(interrupted.executionId)) { "Failed to clear recovered plugin journal" }
        if (plugin != null) {
            updateState(plugin.manifest.id, ScriptPluginState.QUARANTINED, record.message, fault = record)
        }
    }

    private fun checkCommandPermission(pluginId: String, permission: PluginPermission): PluginCommandAvailability {
        val plugin = pluginManager.getEnabledPlugin(pluginId)
            ?: return PluginCommandAvailability(false, "Plugin is disabled or quarantined")
        val declared = PluginPermission.parseList(plugin.manifest.permissions) +
            PluginPermission.parseList(plugin.manifest.optionalPermissions)
        val available = permission in declared && permissionManager.hasPermission(pluginId, permission)
        return PluginCommandAvailability(
            available = available,
            errorMessage = if (available) null else "Permission denied: ${permission.id}",
        )
    }

    private fun isScriptPlugin(plugin: InstalledPlugin): Boolean =
        plugin.manifest.type.equals("script", ignoreCase = true) ||
            plugin.manifest.type.equals("hybrid", ignoreCase = true)

    private fun inferFaultPhase(functionName: String): PluginFaultPhase = when {
        functionName.startsWith("onCommand", ignoreCase = true) -> PluginFaultPhase.COMMAND
        functionName.startsWith("on", ignoreCase = true) -> PluginFaultPhase.EVENT
        else -> PluginFaultPhase.API_CALL
    }

    private fun nextGeneration(pluginId: String): Long = generations
        .computeIfAbsent(pluginId) { AtomicLong(0L) }
        .incrementAndGet()

    private fun currentGeneration(pluginId: String): Long = generations[pluginId]?.get() ?: 0L

    private fun isGenerationCurrent(pluginId: String, generation: Long): Boolean =
        currentGeneration(pluginId) == generation &&
            faultStore.getFault(pluginId) == null &&
            pluginManager.getInstalledPlugin(pluginId)?.enabled == true

    private fun updateState(
        pluginId: String,
        state: ScriptPluginState,
        error: String? = null,
        generation: Long = currentGeneration(pluginId),
        fault: PluginFaultRecord? = null,
    ) {
        val effectiveStatus = when (state) {
            ScriptPluginState.UNLOADED, ScriptPluginState.DISABLED -> PluginEffectiveStatus.DISABLED
            ScriptPluginState.WAITING_PERMISSION -> PluginEffectiveStatus.WAITING_PERMISSION
            ScriptPluginState.LOADING -> PluginEffectiveStatus.LOADING
            ScriptPluginState.ACTIVE -> PluginEffectiveStatus.ACTIVE
            ScriptPluginState.QUARANTINED -> PluginEffectiveStatus.QUARANTINED
            ScriptPluginState.ERROR, ScriptPluginState.RUNTIME_UNAVAILABLE -> PluginEffectiveStatus.RUNTIME_UNAVAILABLE
        }
        if (!faultStore.setEffectiveStatus(pluginId, effectiveStatus)) {
            Timber.tag(TAG).w("Failed to persist plugin effective status for %s", pluginId)
        }
        _pluginStates.update { current ->
            current + (
                pluginId to ScriptPluginInfo(
                    pluginId = pluginId,
                    state = state,
                    error = error,
                    generation = generation,
                    fault = fault,
                )
            )
        }
    }

    private fun PluginRuntimeResponse.toFaultKind(startup: Boolean): PluginFaultKind = when (status) {
        PluginRuntimeResponseStatus.TIMEOUT -> PluginFaultKind.EXECUTION_TIMEOUT
        PluginRuntimeResponseStatus.RESOURCE_LIMIT -> PluginFaultKind.RESOURCE_LIMIT
        PluginRuntimeResponseStatus.RUNTIME_ERROR -> PluginFaultKind.RUNTIME_CRASH
        else -> if (startup) PluginFaultKind.STARTUP_EXCEPTION else PluginFaultKind.UNHANDLED_EXCEPTION
    }

    private fun Any?.toJsonElement(depth: Int = 0): JsonElement {
        if (depth > 16) return JsonNull
        return when (this) {
            null -> JsonNull
            is JsonElement -> this
            is Boolean -> JsonPrimitive(this)
            is Byte, is Short, is Int, is Long -> JsonPrimitive((this as Number).toLong())
            is Float, is Double -> JsonPrimitive((this as Number).toDouble())
            is Number -> JsonPrimitive(toDouble())
            is String -> JsonPrimitive(this)
            is ByteArray -> JsonPrimitive(String(this, StandardCharsets.UTF_8))
            is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement(depth + 1) })
            is Iterable<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
            is Array<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
            else -> JsonPrimitive(toString())
        }
    }

    private fun JsonElement.toAnyValue(): Any? = when (this) {
        JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> contentOrNull
        }
        is JsonArray -> map { element -> element.toAnyValue() }
        is JsonObject -> mapValues { (_, value) -> value.toAnyValue() }
    }

    private fun sanitizeRuntimeValue(value: Any?, key: String? = null): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (entryKey, entryValue) ->
            val normalizedKey = entryKey?.toString().orEmpty()
            normalizedKey to sanitizeRuntimeValue(entryValue, normalizedKey)
        }
        is Iterable<*> -> value.map { item -> sanitizeRuntimeValue(item) }
        is Array<*> -> value.map { item -> sanitizeRuntimeValue(item) }
        is String -> if (key in HOST_PATH_KEYS) sanitizeHostPath(value) else sanitizePrivatePath(value)
        else -> value
    }

    private fun sanitizeHostPath(path: String): String {
        return runtimeWorkspaceAccess.toPluginVisiblePath(path) ?: "<host-path>"
    }

    private fun sanitizePrivatePath(value: String): String = value
        .replace(Regex("/data/(?:data|user/\\d+)/[^/\\s]+"), "<app-data>")

}
