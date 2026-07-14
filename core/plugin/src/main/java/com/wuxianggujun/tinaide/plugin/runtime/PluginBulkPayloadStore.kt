package com.wuxianggujun.tinaide.plugin.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PluginBulkPayloadStore(context: Context) {
    private val directory = File(context.cacheDir, "plugin-ipc-payloads")
    private val files = ConcurrentHashMap<String, StoredPayload>()

    private data class StoredPayload(
        val pluginId: String,
        val file: File,
    )

    fun put(pluginId: String, bytes: ByteArray, encoding: PluginBulkPayloadEncoding): PluginBulkPayloadRef {
        directory.mkdirs()
        val token = UUID.randomUUID().toString()
        val file = File(directory, token)
        file.writeBytes(bytes)
        files[token] = StoredPayload(pluginId, file)
        return PluginBulkPayloadRef(token, bytes.size.toLong(), encoding)
    }

    fun open(token: String): ParcelFileDescriptor? {
        if (!token.matches(Regex("^[0-9a-fA-F-]{36}$"))) return null
        val file = files.remove(token)?.file ?: return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { file.delete() }
        }.getOrNull()
    }

    fun clearPlugin(pluginId: String) {
        files.entries.toList().forEach { (token, payload) ->
            if (payload.pluginId == pluginId && files.remove(token, payload)) {
                payload.file.delete()
            }
        }
    }

    fun clear() {
        files.values.forEach { payload -> payload.file.delete() }
        files.clear()
        directory.listFiles()?.forEach(File::delete)
    }
}
