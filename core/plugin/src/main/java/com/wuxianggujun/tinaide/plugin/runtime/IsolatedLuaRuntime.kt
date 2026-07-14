package com.wuxianggujun.tinaide.plugin.runtime

import android.os.ParcelFileDescriptor
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.runtime.ipc.IPluginHostBridge
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import party.iroiro.luajava.Lua
import party.iroiro.luajava.lua54.Lua54

/** Lua VM owned exclusively by the isolated plugin process. */
internal class IsolatedLuaRuntime(
    private val request: PluginRuntimeLoadRequest,
    private val hostBridge: IPluginHostBridge,
) {
    companion object {
        private val MODULE_NAME_PATTERN = Regex("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*$")

        private val API_METHODS: Map<String, List<String>> = linkedMapOf(
            "log" to listOf("debug", "info", "warn", "error"),
            "events" to listOf("on", "off", "emit", "clear"),
            "panels" to listOf("setContent", "appendContent", "clear"),
            "editor" to listOf(
                "getActiveEditor", "getText", "setText", "getSelection", "insertText",
                "replaceSelection", "getLanguage", "getCursorPosition", "setCursorPosition",
                "setSelection", "getFilePath", "getFileName",
            ),
            "diagnostics" to listOf("get"),
            "workspace" to listOf("readFile", "writeFile", "findFiles"),
            "fs" to listOf("readFile", "writeFile", "exists", "isDirectory", "listDir", "mkdir"),
            "commands" to listOf("register", "unregister", "execute"),
            "config" to listOf("get", "set", "reset"),
            "storage" to listOf("get", "set", "remove"),
            "clipboard" to listOf("getText", "setText", "hasText"),
            "network" to listOf("fetch", "get", "post"),
            "db" to listOf("execute", "query", "transaction", "close", "tableExists"),
            "ui" to listOf("showMessage", "showWarning", "showError"),
        )
    }

    private val json = JsonSerializer.default
    private var lua: Lua? = null

    fun initialize(mainSource: ParcelFileDescriptor) {
        check(lua == null) { "Runtime already initialized" }
        val created = Lua54()
        try {
            created.openLibraries()
            registerRemoteApi(created)
            configureSandbox(created)
            val source = readUtf8(mainSource, MAX_LUA_SOURCE_BYTES)
            created.run(source)
            lua = created
        } catch (error: Throwable) {
            runCatching { created.close() }
            throw error
        } finally {
            runCatching { mainSource.close() }
        }
    }

    fun call(functionName: String, args: JsonArray): JsonArray {
        val state = checkNotNull(lua) { "Plugin runtime is not initialized" }
        state.getGlobal(functionName)
        if (state.isNil(-1)) {
            state.pop(1)
            throw IllegalArgumentException("Function '$functionName' not found")
        }
        args.forEach { argument -> state.pushJson(argument) }
        state.pCall(args.size, 1)
        val result = if (state.top > 0) state.get().toJavaObject().toJsonElement() else JsonNull
        if (state.top > 0) state.pop(1)
        return JsonArray(listOf(result))
    }

    fun close() {
        val current = lua
        lua = null
        runCatching { current?.close() }
    }

    private fun registerRemoteApi(state: Lua) {
        state.createTable(0, API_METHODS.size + 2)
        API_METHODS.forEach { (namespace, methods) ->
            state.createTable(0, methods.size)
            methods.forEach { method ->
                state.push { luaState: Lua ->
                    if (namespace == "db" && method == "transaction") {
                        invokeDatabaseTransaction(luaState)
                    } else {
                        invokeHost(luaState, namespace, method)
                    }
                }
                state.setField(-2, method)
            }
            state.setField(-2, namespace)
        }
        state.push(request.pluginId)
        state.setField(-2, "pluginId")
        state.push(request.apiVersion)
        state.setField(-2, "apiVersion")
        state.setGlobal("tina")

        state.push { luaState: Lua -> invokeHost(luaState, "log", "info") }
        state.setGlobal("print")

        state.push { luaState: Lua ->
            val moduleName = if (luaState.top >= 1) luaState.toString(1) else null
            if (moduleName == null || !MODULE_NAME_PATTERN.matches(moduleName) || ".." in moduleName) {
                luaState.pushNil()
                luaState.push("Invalid Lua module name")
                return@push 2
            }
            val moduleRequest = PluginLuaModuleRequest(request.pluginId, request.generation, moduleName)
            val encoded = json.encodeToString(moduleRequest)
            val descriptor = hostBridge.openLuaModule(encoded)
            if (descriptor == null) {
                luaState.pushNil()
                luaState.push("Lua module not found: $moduleName")
                return@push 2
            }
            val source = runCatching { readUtf8(descriptor, MAX_LUA_SOURCE_BYTES) }
            runCatching { descriptor.close() }
            source.fold(
                onSuccess = {
                    luaState.push(it)
                    1
                },
                onFailure = {
                    luaState.pushNil()
                    luaState.push(it.message ?: "Failed to read Lua module")
                    2
                },
            )
        }
        state.setGlobal("__tina_read_module")
    }

    private fun configureSandbox(state: Lua) {
        state.run(
            """
            local safe_os = nil
            if os then
              safe_os = { clock = os.clock, date = os.date, difftime = os.difftime, time = os.time }
            end
            os = safe_os
            io = nil
            debug = nil
            loadfile = nil
            dofile = nil
            java = nil
            luajava = nil

            local tina_loaded_modules = {}
            function require(name)
              if type(name) ~= "string" then error("module name must be a string", 2) end
              local cached = tina_loaded_modules[name]
              if cached ~= nil then return cached end
              local source, read_error = __tina_read_module(name)
              if not source then error(read_error or ("module not found: " .. name), 2) end
              local chunk, load_error = load(source, "@plugin:" .. name, "t", _ENV)
              if not chunk then error(load_error, 2) end
              local result = chunk()
              if result == nil then result = true end
              tina_loaded_modules[name] = result
              return result
            end
            package = nil
            """.trimIndent(),
        )
    }

    private fun invokeHost(state: Lua, namespace: String, method: String): Int {
        val args = buildList {
            for (index in 1..state.top) {
                state.pushValue(index)
                add(state.get().toJavaObject().toJsonElement())
                state.pop(1)
            }
        }
        val response = callHost(namespace, method, JsonArray(args))
        response.values.forEach { state.pushJson(it) }
        return response.values.size
    }

    private fun invokeDatabaseTransaction(state: Lua): Int {
        if (state.top < 1 || !state.isFunction(1)) {
            state.push(false)
            return 1
        }
        val begin = callHost("db", "__beginTransaction", JsonArray(emptyList()))
        if (begin.error != null) {
            state.push(false)
            return 1
        }
        return try {
            state.pushValue(1)
            state.pCall(0, 0)
            callHost("db", "__commitTransaction", JsonArray(emptyList()))
            state.push(true)
            1
        } catch (error: Throwable) {
            runCatching { callHost("db", "__rollbackTransaction", JsonArray(emptyList())) }
            state.push(false)
            1
        }
    }

    private fun callHost(namespace: String, method: String, args: JsonArray): PluginHostCallResponse {
        val encoded = json.encodeToString(
            PluginHostCallRequest(
                pluginId = request.pluginId,
                generation = request.generation,
                namespace = namespace,
                method = method,
                args = args,
            ),
        )
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_BINDER_JSON_BYTES) {
            "Plugin API request exceeds Binder limit"
        }
        val rawResponse = hostBridge.call(encoded)
        require(rawResponse.toByteArray(StandardCharsets.UTF_8).size <= MAX_BINDER_JSON_BYTES) {
            "Plugin API response exceeds Binder limit"
        }
        val decoded = json.decodeFromString<PluginHostCallResponse>(rawResponse)
        if (decoded.errorKind == PluginHostErrorKind.INVALID_REQUEST) {
            throw IllegalArgumentException(decoded.error ?: "Invalid plugin contribution or API request")
        }
        if (decoded.bulkValues.isEmpty()) return decoded
        val resolvedValues = decoded.values.toMutableList()
        decoded.bulkValues.forEach { (index, reference) ->
            require(index in resolvedValues.indices) { "Invalid bulk payload index" }
            require(reference.sizeBytes in 0..(8L * 1024L * 1024L)) { "Bulk payload exceeds limit" }
            val descriptor = requireNotNull(hostBridge.openPayload(reference.token)) {
                "Bulk payload is unavailable"
            }
            val bytes = FileInputStream(descriptor.fileDescriptor).use { input ->
                input.readLimitedBytes(reference.sizeBytes.toInt())
            }
            runCatching { descriptor.close() }
            val payloadText = String(bytes, StandardCharsets.UTF_8)
            resolvedValues[index] = when (reference.encoding) {
                PluginBulkPayloadEncoding.STRING -> JsonPrimitive(payloadText)
                PluginBulkPayloadEncoding.JSON -> json.parseToJsonElement(payloadText)
            }
        }
        return decoded.copy(values = JsonArray(resolvedValues), bulkValues = emptyMap())
    }

    private fun readUtf8(descriptor: ParcelFileDescriptor, maxBytes: Int): String {
        FileInputStream(descriptor.fileDescriptor).use { input ->
            val bytes = input.readLimitedBytes(maxBytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun java.io.InputStream.readLimitedBytes(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Payload exceeds $maxBytes bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun Lua.pushJson(element: JsonElement) {
        when (element) {
            JsonNull -> pushNil()
            is JsonPrimitive -> when {
                element.isString -> push(element.content)
                element.booleanOrNull != null -> push(element.booleanOrNull!!)
                element.longOrNull != null -> push(element.longOrNull!!)
                element.doubleOrNull != null -> push(element.doubleOrNull!!)
                else -> push(element.contentOrNull ?: "")
            }
            is JsonArray -> {
                createTable(element.size, 0)
                element.forEachIndexed { index, value ->
                    pushJson(value)
                    rawSetI(-2, index + 1)
                }
            }
            is JsonObject -> {
                createTable(0, element.size)
                element.forEach { (key, value) ->
                    pushJson(value)
                    setField(-2, key)
                }
            }
        }
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
            is Map<*, *> -> JsonObject(
                entries.associate { (key, value) -> key.toString() to value.toJsonElement(depth + 1) },
            )
            is Iterable<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
            is Array<*> -> JsonArray(map { it.toJsonElement(depth + 1) })
            else -> JsonPrimitive(toString())
        }
    }
}
