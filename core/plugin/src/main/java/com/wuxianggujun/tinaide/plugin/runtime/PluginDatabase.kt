package com.wuxianggujun.tinaide.plugin.runtime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal class PluginDatabase(context: Context, pluginId: String) {
    companion object {
        private const val MAX_DATABASE_ROWS = 1_000
        private const val MAX_DATABASE_RESULT_BYTES = 8 * 1024 * 1024

        internal fun databaseName(pluginId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(pluginId.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            return "plugin_$digest.db"
        }

        internal fun legacyDatabaseName(pluginId: String): String =
            "plugin_${pluginId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.db"

        internal fun deletePersistentFiles(context: Context, pluginId: String) {
            val currentName = databaseName(pluginId)
            deleteDatabaseWithSidecars(context, currentName)
            if (canOwnLegacyDatabase(context, pluginId)) {
                deleteDatabaseWithSidecars(context, legacyDatabaseName(pluginId))
            }
        }

        private fun migrateLegacyDatabase(context: Context, pluginId: String) {
            if (!canOwnLegacyDatabase(context, pluginId)) return
            val current = context.getDatabasePath(databaseName(pluginId))
            val legacy = context.getDatabasePath(legacyDatabaseName(pluginId))
            if (current.exists() || !legacy.exists()) return
            current.parentFile?.mkdirs()
            moveDatabaseFile(legacy, current)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val legacySidecar = java.io.File(legacy.path + suffix)
                if (legacySidecar.exists()) {
                    moveDatabaseFile(legacySidecar, java.io.File(current.path + suffix))
                }
            }
        }

        private fun canOwnLegacyDatabase(context: Context, pluginId: String): Boolean {
            val legacyName = legacyDatabaseName(pluginId)
            val installedIds = java.io.File(context.filesDir, "plugins")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .map { it.name }
            val owners = installedIds.filter { legacyDatabaseName(it) == legacyName }
            return owners.isEmpty() || owners == listOf(pluginId)
        }

        private fun moveDatabaseFile(source: java.io.File, target: java.io.File) {
            if (source.renameTo(target)) return
            source.copyTo(target, overwrite = false)
            check(source.delete()) { "Failed to remove migrated plugin database" }
        }

        private fun deleteDatabaseWithSidecars(context: Context, name: String) {
            val database = context.getDatabasePath(name)
            val files = buildList {
                add(database)
                listOf("-wal", "-shm", "-journal").forEach { suffix ->
                    add(java.io.File(database.path + suffix))
                }
            }
            if (files.none(java.io.File::exists)) return

            context.deleteDatabase(name)
            files.filter(java.io.File::exists).forEach { file ->
                check(file.delete()) { "Failed to remove plugin database file: ${file.name}" }
            }
        }
    }

    init {
        migrateLegacyDatabase(context, pluginId)
    }

    private val helper = object : SQLiteOpenHelper(
        context,
        databaseName(pluginId),
        null,
        1,
    ) {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val database: SQLiteDatabase get() = helper.writableDatabase

    fun execute(sql: String, params: JsonElement?): Long {
        val bindings = params.asBindings()
        if (bindings.isEmpty()) database.execSQL(sql) else database.execSQL(sql, bindings.toTypedArray())
        return runCatching { database.compileStatement("SELECT changes()").use { it.simpleQueryForLong() } }
            .getOrDefault(0)
    }

    fun query(sql: String, params: JsonElement?): List<JsonElement> {
        val bindings = params.asBindings().map(Any::toString).toTypedArray()
        return database.rawQuery(sql, bindings).use { cursor ->
            buildList {
                var encodedBytes = 0
                while (cursor.moveToNext() && size < MAX_DATABASE_ROWS) {
                    val row = JsonObject(
                        cursor.columnNames.mapIndexed { index, name ->
                            name to when (cursor.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> JsonNull
                                android.database.Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
                                android.database.Cursor.FIELD_TYPE_BLOB -> {
                                    val blob = cursor.getBlob(index)
                                    require(blob.size <= MAX_BINDER_JSON_BYTES) { "Database blob exceeds limit" }
                                    JsonPrimitive(String(blob, StandardCharsets.UTF_8))
                                }
                                else -> JsonPrimitive(cursor.getString(index))
                            }
                        }.toMap(),
                    )
                    encodedBytes += row.toString().toByteArray(StandardCharsets.UTF_8).size
                    require(encodedBytes <= MAX_DATABASE_RESULT_BYTES) { "Database result exceeds limit" }
                    add(row)
                }
            }
        }
    }

    fun tableExists(tableName: String?): Boolean {
        if (tableName.isNullOrBlank()) return false
        return database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName),
        ).use { it.count > 0 }
    }

    fun beginTransaction() = database.beginTransaction()

    fun commitTransaction() {
        database.setTransactionSuccessful()
        database.endTransaction()
    }

    fun rollbackTransaction() {
        if (database.inTransaction()) database.endTransaction()
    }

    fun close() = helper.close()

    private fun JsonElement?.asBindings(): List<Any> = (this as? JsonArray).orEmpty().mapNotNull { value ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        primitive.booleanOrNull?.let { if (it) 1 else 0 }
            ?: primitive.longOrNull
            ?: primitive.doubleOrNull
            ?: primitive.contentOrNull
    }
}
