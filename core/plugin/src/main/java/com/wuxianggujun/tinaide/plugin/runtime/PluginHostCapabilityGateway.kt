package com.wuxianggujun.tinaide.plugin.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.plugin.PluginConfigurationSchema
import com.wuxianggujun.tinaide.plugin.PluginConfigurationStore
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginPanelContentStore
import com.wuxianggujun.tinaide.plugin.PluginPanelKey
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import com.wuxianggujun.tinaide.plugin.script.RateLimiter
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.plugin.script.api.PluginDiagnosticsProviderHolder
import com.wuxianggujun.tinaide.plugin.script.api.PluginEditorBridgeHolder
import com.wuxianggujun.tinaide.plugin.script.api.PluginEventBus
import com.wuxianggujun.tinaide.plugin.script.api.PluginEvent
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostEventDispatcher
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostCommandExecutorHolder
import com.wuxianggujun.tinaide.plugin.script.api.PluginWorkspaceFileAccess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Executes every privileged plugin capability inside the trusted host process. */
internal class PluginHostCapabilityGateway(
    private val context: Context,
    private val pluginManager: PluginManager,
    private val projectRootProvider: () -> String?,
    private val isGenerationCurrent: (String, Long) -> Boolean,
) {
    companion object {
        private const val MAIN_THREAD_TIMEOUT_MS = 2_000L
        private const val MAX_LOG_MESSAGE_CHARS = 8 * 1024
        private const val MAX_FILE_CONTENT_BYTES = 8 * 1024 * 1024
        private const val MAX_DATABASE_ROWS = 1_000
        private const val MAX_DIRECTORY_ENTRIES = 1_000
        private const val MAX_BULK_INLINE_BYTES = 128 * 1024
        private const val MAX_BULK_PAYLOAD_BYTES = 8 * 1024 * 1024
    }

    private val permissionManager = PluginPermissionManager.getInstance(context)
    private val logManager = PluginLogManager.getInstance(context)
    private val configurationStore = PluginConfigurationStore.getInstance(context)
    private val workspaceAccess = PluginWorkspaceFileAccess(projectRootProvider)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val payloadStore = PluginBulkPayloadStore(context)
    private val dataCapabilities = PluginHostDataCapabilities(context, pluginManager, ::hasAnyPermission)

    fun call(request: PluginHostCallRequest): PluginHostCallResponse {
        if (!isGenerationCurrent(request.pluginId, request.generation)) {
            return failure("Stale plugin runtime generation", PluginHostErrorKind.HOST_UNAVAILABLE)
        }
        val plugin = pluginManager.getInstalledPlugin(request.pluginId)
            ?: return failure("Plugin is not installed", PluginHostErrorKind.HOST_UNAVAILABLE)
        if (!plugin.enabled) {
            return failure("Plugin is disabled", PluginHostErrorKind.HOST_UNAVAILABLE)
        }
        val requiredPermissions = requiredPermissions(request.namespace, request.method)
        if (requiredPermissions.isNotEmpty() && !hasAnyPermission(plugin.manifest, requiredPermissions)) {
            val permission = requiredPermissions.first()
            val message = "Permission denied: ${permission.id}"
            return deniedResponse(request.namespace, request.method, message)
        }

        return runCatching {
            dispatch(request, plugin.manifest.name)
        }.getOrElse { error ->
            failure(
                error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName,
                PluginHostErrorKind.HOST_UNAVAILABLE,
            )
        }.withBulkPayloads(request.pluginId)
    }

    fun openPayload(token: String): ParcelFileDescriptor? = payloadStore.open(token)

    fun cleanupPlugin(pluginId: String) {
        dataCapabilities.cleanupPlugin(pluginId)
        fileLimiters.remove(pluginId)
        payloadStore.clearPlugin(pluginId)
        PluginPanelContentStore.clearPlugin(pluginId)
    }

    fun cleanup() {
        dataCapabilities.cleanup()
        fileLimiters.clear()
        payloadStore.clear()
        PluginPanelContentStore.clearAll()
    }

    private fun dispatch(request: PluginHostCallRequest, pluginName: String): PluginHostCallResponse = when (request.namespace) {
        "log" -> handleLog(request, pluginName)
        "events" -> handleEvents(request)
        "panels" -> handlePanels(request)
        "editor" -> handleEditor(request)
        "diagnostics" -> handleDiagnostics(request)
        "workspace" -> handleWorkspace(request)
        "fs" -> handleFileSystem(request)
        "commands" -> handleCommands(request, pluginName)
        "config" -> handleConfig(request)
        "storage", "network", "db" -> dataCapabilities.call(request)
        "clipboard" -> handleClipboard(request)
        "ui" -> handleUi(request)
        else -> failure("Unknown plugin API namespace: ${request.namespace}", PluginHostErrorKind.INVALID_REQUEST)
    }

    private fun handleLog(request: PluginHostCallRequest, pluginName: String): PluginHostCallResponse {
        val message = request.args.string(0).orEmpty().take(MAX_LOG_MESSAGE_CHARS)
        when (request.method) {
            "debug" -> logManager.debug(request.pluginId, pluginName, message)
            "info" -> logManager.info(request.pluginId, pluginName, message)
            "warn" -> logManager.warn(request.pluginId, pluginName, message)
            "error" -> logManager.error(request.pluginId, pluginName, message)
            else -> return invalidMethod(request)
        }
        return success()
    }

    private fun handleEvents(request: PluginHostCallRequest): PluginHostCallResponse {
        val eventId = request.args.string(0)
        return when (request.method) {
            "on" -> {
                val callbackName = request.args.string(1)
                if (eventId.isNullOrBlank() || callbackName.isNullOrBlank()) {
                    failure("Event ID and callback name are required", PluginHostErrorKind.INVALID_REQUEST)
                } else if (PluginEvent.fromId(eventId) == null) {
                    failure("Unknown plugin event: $eventId", PluginHostErrorKind.INVALID_REQUEST)
                } else {
                    PluginEventBus.subscribe(request.pluginId, eventId, callbackName)
                    success()
                }
            }
            "off" -> {
                when {
                    eventId.isNullOrBlank() -> failure("Event ID is required", PluginHostErrorKind.INVALID_REQUEST)
                    PluginEvent.fromId(eventId) == null -> failure("Unknown plugin event: $eventId", PluginHostErrorKind.INVALID_REQUEST)
                    else -> {
                        PluginEventBus.unsubscribe(request.pluginId, eventId)
                        success()
                    }
                }
            }
            "emit" -> {
                if (eventId != PluginEvent.CUSTOM.id) {
                    failure(
                        "Plugins may emit only the custom event",
                        PluginHostErrorKind.INVALID_REQUEST,
                    )
                } else {
                    val payload = request.args.getOrNull(1)
                    if (payload != null && payload !is JsonObject && payload !is JsonNull) {
                        failure("Custom event payload must be an object", PluginHostErrorKind.INVALID_REQUEST)
                    } else {
                        PluginHostEventDispatcher.emitToPlugin(
                            pluginId = request.pluginId,
                            eventId = PluginEvent.CUSTOM.id,
                            data = (payload as? JsonObject)?.mapValues { (_, value) -> value.toEventValue() }.orEmpty(),
                        )
                        success()
                    }
                }
            }
            "clear" -> {
                PluginEventBus.unsubscribeAll(request.pluginId)
                success()
            }
            else -> invalidMethod(request)
        }
    }

    private fun handlePanels(request: PluginHostCallRequest): PluginHostCallResponse {
        val panelId = request.args.string(0)?.trim().orEmpty()
        val manifest = pluginManager.getInstalledPlugin(request.pluginId)?.manifest
            ?: return failure("Plugin manifest unavailable", PluginHostErrorKind.HOST_UNAVAILABLE)
        val panel = manifest.contributions?.panels.orEmpty().find { it.id == panelId }
            ?: return failure("Unknown or undeclared plugin panel: $panelId", PluginHostErrorKind.INVALID_REQUEST)
        val key = PluginPanelKey(request.pluginId, panel.id)
        return when (request.method) {
            "setContent" -> {
                PluginPanelContentStore.set(key, request.args.string(1).orEmpty())
                success(JsonPrimitive(true))
            }
            "appendContent" -> {
                PluginPanelContentStore.append(key, request.args.string(1).orEmpty())
                success(JsonPrimitive(true))
            }
            "clear" -> {
                PluginPanelContentStore.clear(key)
                success(JsonPrimitive(true))
            }
            else -> invalidMethod(request)
        }
    }

    private fun handleEditor(request: PluginHostCallRequest): PluginHostCallResponse = runOnMainThread(request) {
        val bridge = PluginEditorBridgeHolder.get()
        when (request.method) {
            "getActiveEditor" -> {
                val editor = bridge?.getActiveEditor()
                success(
                    editor?.let {
                        JsonObject(
                            buildMap {
                                put("tabId", JsonPrimitive(it.tabId))
                                put("filePath", workspaceAccess.toPluginVisiblePath(it.filePath)?.let(::JsonPrimitive) ?: JsonNull)
                                put("fileName", JsonPrimitive(it.fileName))
                                put("languageId", JsonPrimitive(it.languageId))
                                put("isDirty", JsonPrimitive(it.isDirty))
                                put(
                                    "cursor",
                                    it.cursor?.let { cursor ->
                                        JsonObject(
                                            mapOf(
                                                "line" to JsonPrimitive(cursor.line),
                                                "column" to JsonPrimitive(cursor.column),
                                            ),
                                        )
                                    } ?: JsonNull,
                                )
                            },
                        )
                    } ?: JsonNull,
                )
            }
            "getText" -> success(JsonPrimitive(bridge?.getText().orEmpty()))
            "setText" -> success(JsonPrimitive(bridge?.setText(request.args.string(0).orEmpty()) == true))
            "getSelection" -> {
                val selection = bridge?.getSelection()
                success(
                    JsonObject(
                        mapOf(
                            "startLine" to JsonPrimitive(selection?.startLine ?: 0),
                            "startColumn" to JsonPrimitive(selection?.startColumn ?: 0),
                            "endLine" to JsonPrimitive(selection?.endLine ?: 0),
                            "endColumn" to JsonPrimitive(selection?.endColumn ?: 0),
                            "text" to JsonPrimitive(selection?.text.orEmpty()),
                        ),
                    ),
                )
            }
            "insertText" -> success(
                JsonPrimitive(
                    bridge?.insertText(
                        request.args.string(0).orEmpty(),
                        request.args.int(1),
                        request.args.int(2),
                    ) == true,
                ),
            )
            "replaceSelection" -> success(
                JsonPrimitive(bridge?.replaceSelection(request.args.string(0).orEmpty()) == true),
            )
            "getLanguage" -> success(JsonPrimitive(bridge?.getLanguage() ?: "unknown"))
            "getCursorPosition" -> {
                val cursor = bridge?.getCursorPosition()
                success(
                    JsonObject(
                        mapOf(
                            "line" to JsonPrimitive(cursor?.line ?: 0),
                            "column" to JsonPrimitive(cursor?.column ?: 0),
                        ),
                    ),
                )
            }
            "setCursorPosition" -> success(
                JsonPrimitive(bridge?.setCursorPosition(request.args.int(0) ?: 0, request.args.int(1) ?: 0) == true),
            )
            "setSelection" -> success(
                JsonPrimitive(
                    bridge?.setSelection(
                        request.args.int(0) ?: 0,
                        request.args.int(1) ?: 0,
                        request.args.int(2) ?: request.args.int(0) ?: 0,
                        request.args.int(3) ?: request.args.int(1) ?: 0,
                    ) == true,
                ),
            )
            "getFilePath" -> success(
                workspaceAccess.toPluginVisiblePath(bridge?.getActiveFile()?.absolutePath)
                    ?.let(::JsonPrimitive) ?: JsonNull,
            )
            "getFileName" -> success(bridge?.getActiveFile()?.name?.let(::JsonPrimitive) ?: JsonNull)
            else -> invalidMethod(request)
        }
    }

    private fun handleDiagnostics(request: PluginHostCallRequest): PluginHostCallResponse {
        if (request.method != "get") return invalidMethod(request)
        val requestedPath = request.args.string(0)
        val snapshot = PluginDiagnosticsProviderHolder.get()?.getDiagnostics(requestedPath)
        val diagnostics = snapshot?.diagnostics.orEmpty().take(MAX_DATABASE_ROWS).map { item ->
            JsonObject(
                mapOf(
                    "fileUri" to (workspaceAccess.toPluginVisiblePath(item.fileUri)?.let(::JsonPrimitive) ?: JsonNull),
                    "filePath" to (workspaceAccess.toPluginVisiblePath(item.filePath)?.let(::JsonPrimitive) ?: JsonNull),
                    "fileName" to JsonPrimitive(item.fileName),
                    "line" to JsonPrimitive(item.line),
                    "column" to JsonPrimitive(item.column),
                    "endLine" to JsonPrimitive(item.endLine),
                    "endColumn" to JsonPrimitive(item.endColumn),
                    "message" to JsonPrimitive(item.message),
                    "severity" to JsonPrimitive(item.severity),
                    "source" to (item.source?.let(::JsonPrimitive) ?: JsonNull),
                    "code" to (item.code?.let(::JsonPrimitive) ?: JsonNull),
                ),
            )
        }
        return success(
            JsonObject(
                mapOf(
                    "available" to JsonPrimitive(snapshot != null && snapshot.available),
                    "totalCount" to JsonPrimitive(snapshot?.totalCount ?: 0),
                    "errorCount" to JsonPrimitive(snapshot?.errorCount ?: 0),
                    "warningCount" to JsonPrimitive(snapshot?.warningCount ?: 0),
                    "infoCount" to JsonPrimitive(snapshot?.infoCount ?: 0),
                    "hintCount" to JsonPrimitive(snapshot?.hintCount ?: 0),
                    "requestedFilePath" to (
                        workspaceAccess.toPluginVisiblePath(requestedPath)?.let(::JsonPrimitive) ?: JsonNull
                    ),
                    "error" to (snapshot?.error?.let(::JsonPrimitive) ?: JsonNull),
                    "diagnostics" to JsonArray(diagnostics),
                ),
            ),
        )
    }

    private fun handleWorkspace(request: PluginHostCallRequest): PluginHostCallResponse = when (request.method) {
        "readFile" -> readWorkspaceFile(request)
        "writeFile" -> writeWorkspaceFile(request)
        "findFiles" -> success(
            JsonArray(
                workspaceAccess.findFiles(request.args.string(0), request.args.int(1) ?: 200)
                    .map(::JsonPrimitive),
            ),
        )
        else -> invalidMethod(request)
    }

    private fun handleFileSystem(request: PluginHostCallRequest): PluginHostCallResponse = when (request.method) {
        "readFile" -> readWorkspaceFile(request)
        "writeFile" -> writeWorkspaceFile(request)
        "exists" -> success(JsonPrimitive(resolveWorkspaceFile(request)?.exists() == true))
        "isDirectory" -> success(JsonPrimitive(resolveWorkspaceFile(request)?.isDirectory == true))
        "listDir" -> {
            val directory = resolveWorkspaceFile(request)
            if (directory == null || !directory.isDirectory) success(JsonNull) else success(
                JsonArray(directory.listFiles().orEmpty().take(MAX_DIRECTORY_ENTRIES).map { JsonPrimitive(it.name) }),
            )
        }
        "mkdir" -> {
            val directory = resolveWorkspaceFile(request)
            success(JsonPrimitive(directory != null && (directory.mkdirs() || directory.isDirectory)))
        }
        else -> invalidMethod(request)
    }

    private fun readWorkspaceFile(request: PluginHostCallRequest): PluginHostCallResponse {
        if (!fileLimiter(request.pluginId).tryAcquire()) return failure("File operation rate limit exceeded")
        val file = resolveWorkspaceFile(request)
            ?: return failureWithValues(JsonNull, "Invalid path or access denied")
        if (!file.isFile) return failureWithValues(JsonNull, "File not found")
        val bytes = file.inputStream().use { it.readLimited(MAX_FILE_CONTENT_BYTES) }
        return success(JsonPrimitive(String(bytes, StandardCharsets.UTF_8)))
    }

    private fun writeWorkspaceFile(request: PluginHostCallRequest): PluginHostCallResponse {
        if (!fileLimiter(request.pluginId).tryAcquire()) return failureWithValues(JsonPrimitive(false), "File operation rate limit exceeded")
        val file = resolveWorkspaceFile(request)
            ?: return failureWithValues(JsonPrimitive(false), "Invalid path or access denied")
        val content = request.args.string(1)
            ?: return failureWithValues(JsonPrimitive(false), "Path and content are required")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return success(JsonPrimitive(true))
    }

    private fun resolveWorkspaceFile(request: PluginHostCallRequest): File? = request.args.string(0)
        ?.let(workspaceAccess::resolveSafePath)

    private fun handleCommands(request: PluginHostCallRequest, pluginName: String): PluginHostCallResponse = when (request.method) {
        "register" -> {
            val commandId = request.args.string(0)?.trim().orEmpty()
            val callbackName = request.args.string(1)?.trim().orEmpty()
            if (commandId.isBlank() || callbackName.isBlank() || commandId.length > 256 || callbackName.length > 256) {
                failureWithValues(
                    JsonPrimitive(false),
                    "Command ID or callback name is invalid",
                    PluginHostErrorKind.INVALID_REQUEST,
                )
            } else {
                val result = PluginCommandRegistry.register(
                    pluginId = request.pluginId,
                    pluginName = pluginName,
                    commandId = commandId,
                    callbackName = callbackName,
                    title = request.args.string(2),
                )
                if (result.isSuccess) {
                    success(JsonPrimitive(true))
                } else {
                    failureWithValues(
                        JsonPrimitive(false),
                        result.exceptionOrNull()?.message ?: "Failed to register command",
                        PluginHostErrorKind.INVALID_REQUEST,
                    )
                }
            }
        }
        "unregister" -> success(
            JsonPrimitive(PluginCommandRegistry.unregister(request.pluginId, request.args.string(0).orEmpty())),
        )
        "execute" -> {
            val commandId = request.args.string(0)?.trim().orEmpty()
            val targetFile = request.args.string(1)?.let { path ->
                val root = projectRootProvider()?.let(::File) ?: return@let null
                val target = if (File(path).isAbsolute) File(path) else File(root, path)
                val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return@let null
                val targetCanonical = runCatching { target.canonicalFile }.getOrNull() ?: return@let null
                targetCanonical.takeIf { it == rootCanonical || it.path.startsWith(rootCanonical.path + File.separator) }
            }
            val invocation = HostCommandInvocation(targetFile, request.args.boolean(2) ?: targetFile?.isDirectory)
            val handled = when {
                HostCommands.isSupported(commandId) -> runOnMainThread(request) {
                    PluginHostCommandExecutorHolder.get()?.execute(commandId, invocation) ?: false
                }
                PluginCommandRegistry.isRegistered(commandId) -> PluginCommandRegistry.dispatch(commandId, invocation)
                else -> false
            }
            success(JsonPrimitive(handled))
        }
        else -> invalidMethod(request)
    }

    private fun handleConfig(request: PluginHostCallRequest): PluginHostCallResponse {
        val manifest = pluginManager.getInstalledPlugin(request.pluginId)?.manifest
            ?: return failure("Plugin manifest unavailable", PluginHostErrorKind.HOST_UNAVAILABLE)
        val key = request.args.string(0) ?: return failureWithValues(JsonNull, "Configuration key is required")
        return when (request.method) {
            "get" -> {
                if (PluginConfigurationSchema.resolveProperty(manifest, key) == null) {
                    failureWithValues(JsonNull, "Unknown configuration key: $key")
                } else {
                    success(
                        configurationStore.getValue(
                            manifest = manifest,
                            propertyKey = key,
                            fallback = request.args.getOrNull(1),
                        ) ?: JsonNull,
                    )
                }
            }
            "set" -> {
                val value = request.args.getOrNull(1)
                    ?: return failureWithValues(JsonPrimitive(false), "Configuration value is required")
                val stored = configurationStore.setValue(manifest, key, value)
                if (stored) success(JsonPrimitive(true)) else failureWithValues(JsonPrimitive(false), "Invalid configuration value for key: $key")
            }
            "reset" -> {
                val reset = configurationStore.resetValue(manifest, key)
                if (reset) success(JsonPrimitive(true)) else failureWithValues(JsonPrimitive(false), "Unknown configuration key: $key")
            }
            else -> invalidMethod(request)
        }
    }

    private fun handleClipboard(request: PluginHostCallRequest): PluginHostCallResponse = runOnMainThread(request) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (request.method) {
            "getText" -> success(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            "setText" -> {
                clipboard.setPrimaryClip(ClipData.newPlainText("TinaIDE Plugin", request.args.string(0).orEmpty()))
                success(JsonPrimitive(true))
            }
            "hasText" -> success(JsonPrimitive(clipboard.hasPrimaryClip()))
            else -> invalidMethod(request)
        }
    }

    private fun handleUi(request: PluginHostCallRequest): PluginHostCallResponse = runOnMainThread(request) {
        val rawMessage = request.args.string(0).orEmpty()
        val message = if (request.method == "showError") {
            Strings.plugin_ui_error_prefix.strOr(context, rawMessage)
        } else {
            rawMessage
        }
        val duration = if (request.method == "showMessage") Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        when (request.method) {
            "showMessage", "showWarning", "showError" -> {
                Toast.makeText(context, message, duration).show()
                success()
            }
            else -> invalidMethod(request)
        }
    }

    private fun requiredPermissions(namespace: String, method: String): Set<PluginPermission> = when (namespace) {
        "editor" -> when (method) {
            "getSelection" -> setOf(PluginPermission.EDITOR_SELECTION)
            "setText", "insertText", "replaceSelection", "setCursorPosition", "setSelection" -> setOf(PluginPermission.EDITOR_WRITE)
            else -> setOf(PluginPermission.EDITOR_READ)
        }
        "diagnostics" -> setOf(PluginPermission.DIAGNOSTICS_READ)
        "workspace", "fs" -> if (method in setOf("writeFile", "mkdir")) setOf(PluginPermission.FILE_WRITE) else setOf(PluginPermission.FILE_READ)
        "commands" -> setOf(PluginPermission.COMMAND_EXECUTE)
        "clipboard" -> if (method == "setText") setOf(PluginPermission.CLIPBOARD_WRITE) else setOf(PluginPermission.CLIPBOARD_READ)
        "network" -> setOf(PluginPermission.NETWORK_FETCH, PluginPermission.NETWORK_UNRESTRICTED)
        "storage" -> setOf(PluginPermission.STORAGE_LOCAL)
        "db" -> if (method == "close") emptySet() else setOf(PluginPermission.STORAGE_DATABASE)
        "ui" -> setOf(PluginPermission.UI_NOTIFICATION)
        else -> emptySet()
    }

    private fun hasAnyPermission(
        manifest: com.wuxianggujun.tinaide.plugin.PluginManifest,
        permissions: Set<PluginPermission>,
    ): Boolean {
        val required = PluginPermission.parseList(manifest.permissions)
        val optional = PluginPermission.parseList(manifest.optionalPermissions)
        val explicitGrants = permissionManager.getGrantedPermissions(manifest.id)
        return permissions.any { permission ->
            when (permission) {
                in required -> permissionManager.hasPermission(manifest.id, permission)
                in optional -> permission in explicitGrants
                else -> false
            }
        }
    }

    private fun deniedResponse(namespace: String, method: String, message: String): PluginHostCallResponse = when (namespace) {
        "db" -> when (method) {
            "execute" -> PluginHostCallResponse(JsonArray(listOf(JsonPrimitive(-1))), errorKind = PluginHostErrorKind.PERMISSION_DENIED, error = message)
            "query" -> PluginHostCallResponse(JsonArray(listOf(JsonArray(emptyList()))), errorKind = PluginHostErrorKind.PERMISSION_DENIED, error = message)
            else -> PluginHostCallResponse(JsonArray(listOf(JsonPrimitive(false))), errorKind = PluginHostErrorKind.PERMISSION_DENIED, error = message)
        }
        else -> failureWithValues(JsonNull, message, PluginHostErrorKind.PERMISSION_DENIED)
    }

    private fun invalidMethod(request: PluginHostCallRequest): PluginHostCallResponse = failure(
        "Unknown plugin API method: ${request.namespace}.${request.method}",
        PluginHostErrorKind.INVALID_REQUEST,
    )

    private fun success(vararg values: JsonElement): PluginHostCallResponse = PluginHostCallResponse(JsonArray(values.toList()))

    private fun failure(
        message: String,
        kind: PluginHostErrorKind = PluginHostErrorKind.BUSINESS_ERROR,
    ): PluginHostCallResponse = PluginHostCallResponse(
        values = JsonArray(listOf(JsonNull, JsonPrimitive(message))),
        errorKind = kind,
        error = message,
    )

    private fun failureWithValues(
        firstValue: JsonElement,
        message: String,
        kind: PluginHostErrorKind = PluginHostErrorKind.BUSINESS_ERROR,
    ): PluginHostCallResponse = PluginHostCallResponse(
        values = JsonArray(listOf(firstValue, JsonPrimitive(message))),
        errorKind = kind,
        error = message,
    )

    private fun PluginHostCallResponse.withBulkPayloads(pluginId: String): PluginHostCallResponse {
        val bulk = mutableMapOf<Int, PluginBulkPayloadRef>()
        val updated = values.mapIndexed { index, value ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val encodedValue = text ?: value.toString()
            val encodedBytes = encodedValue.toByteArray(StandardCharsets.UTF_8)
            if (encodedBytes.size > MAX_BULK_PAYLOAD_BYTES) {
                return failure("Plugin API response exceeds 8 MiB", PluginHostErrorKind.BUSINESS_ERROR)
            }
            if (encodedBytes.size > MAX_BULK_INLINE_BYTES) {
                bulk[index] = payloadStore.put(
                    pluginId = pluginId,
                    bytes = encodedBytes,
                    encoding = if (text != null) PluginBulkPayloadEncoding.STRING else PluginBulkPayloadEncoding.JSON,
                )
                JsonNull
            } else {
                value
            }
        }
        return copy(values = JsonArray(updated), bulkValues = bulk)
    }

    private fun fileLimiter(pluginId: String): RateLimiter = fileLimiters.computeIfAbsent(pluginId) { RateLimiter(60, 60_000L) }

    private fun <T> runOnMainThread(request: PluginHostCallRequest, block: () -> T): T {
        check(isGenerationCurrent(request.pluginId, request.generation)) {
            "Stale plugin runtime generation"
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        val claimed = AtomicBoolean(false)
        val action = Runnable {
            if (!claimed.compareAndSet(false, true)) {
                latch.countDown()
                return@Runnable
            }
            try {
                result.set(
                    runCatching {
                        check(isGenerationCurrent(request.pluginId, request.generation)) {
                            "Stale plugin runtime generation"
                        }
                        block()
                    },
                )
            } finally {
                latch.countDown()
            }
        }
        check(mainHandler.post(action)) { "Failed to schedule host action on main thread" }
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (claimed.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(action)
            }
            error("Host main-thread action timed out")
        }
        return checkNotNull(result.get()).getOrThrow()
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Response exceeds $maxBytes bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun JsonArray.string(index: Int): String? = getOrNull(index)
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull

    private fun JsonArray.int(index: Int): Int? = getOrNull(index)?.jsonPrimitive?.intOrNull

    private fun JsonArray.boolean(index: Int): Boolean? = getOrNull(index)?.jsonPrimitive?.booleanOrNull

    private fun JsonElement.toEventValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toEventValue() }
        is JsonArray -> map { value -> value.toEventValue() }
        is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
    }

}
