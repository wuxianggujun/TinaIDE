package com.wuxianggujun.tinaide.plugin.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PluginBulkPayloadStore(context: Context) {
    private val directory = File(context.cacheDir, "plugin-ipc-payloads")
    private val files = ConcurrentHashMap<String, StoredPayload>()

    init {
        val directoryKey = directory.toPath().toAbsolutePath().normalize().toString()
        if (initializedDirectories.add(directoryKey)) {
            directory.listFiles()
                .orEmpty()
                .filter { file -> file.isFile && !Files.isSymbolicLink(file.toPath()) }
                .forEach(File::delete)
        }
    }

    private data class StoredPayload(
        val pluginId: String,
        val file: File,
        val sizeBytes: Long,
    )

    @Synchronized
    fun put(pluginId: String, bytes: ByteArray, encoding: PluginBulkPayloadEncoding): PluginBulkPayloadRef {
        val pluginPayloads = files.values.filter { payload -> payload.pluginId == pluginId }
        check(pluginPayloads.size < MAX_PENDING_PAYLOADS_PER_PLUGIN) {
            "Plugin bulk payload count limit exceeded"
        }
        check(pluginPayloads.sumOf(StoredPayload::sizeBytes) + bytes.size <= MAX_PENDING_BYTES_PER_PLUGIN) {
            "Plugin bulk payload byte limit exceeded"
        }
        check(files.size < MAX_PENDING_PAYLOADS_TOTAL) {
            "Plugin bulk payload global count limit exceeded"
        }
        check(files.values.sumOf(StoredPayload::sizeBytes) + bytes.size <= MAX_PENDING_BYTES_TOTAL) {
            "Plugin bulk payload global byte limit exceeded"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Failed to create plugin payload directory" }
        val token = UUID.randomUUID().toString()
        val file = File(directory, token)
        try {
            file.writeBytes(bytes)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        files[token] = StoredPayload(pluginId, file, bytes.size.toLong())
        return PluginBulkPayloadRef(token, bytes.size.toLong(), encoding)
    }

    @Synchronized
    fun open(token: String): ParcelFileDescriptor? {
        if (!token.matches(Regex("^[0-9a-fA-F-]{36}$"))) return null
        val file = files.remove(token)?.file ?: return null
        val descriptor = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
        file.delete()
        return descriptor
    }

    @Synchronized
    fun discard(token: String) {
        files.remove(token)?.file?.delete()
    }

    @Synchronized
    fun clearPlugin(pluginId: String) {
        files.entries.toList().forEach { (token, payload) ->
            if (payload.pluginId == pluginId && files.remove(token, payload)) {
                payload.file.delete()
            }
        }
    }

    @Synchronized
    fun clear() {
        files.values.forEach { payload -> payload.file.delete() }
        files.clear()
        directory.listFiles()?.forEach(File::delete)
    }

    private companion object {
        const val MAX_PENDING_PAYLOADS_PER_PLUGIN = 8
        const val MAX_PENDING_BYTES_PER_PLUGIN = 16L * 1024L * 1024L
        const val MAX_PENDING_PAYLOADS_TOTAL = 32
        const val MAX_PENDING_BYTES_TOTAL = 64L * 1024L * 1024L
        val initializedDirectories = ConcurrentHashMap.newKeySet<String>()
    }
}
