package com.wuxianggujun.tinaide.plugin.runtime

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import android.os.CancellationSignal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal class PluginDatabase(context: Context, pluginId: String) {
    companion object {
        private const val MAX_DATABASE_ROWS = 1_000
        private const val MAX_DATABASE_RESULT_BYTES = 192 * 1024
        private const val MAX_DATABASE_BYTES = 64L * 1024L * 1024L
        private const val QUERY_TIMEOUT_MS = 2_000L
        private const val READ_ONLY_EDIT_TABLE = ""
        private val queryDeadlineExecutor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "plugin-db-query-deadline").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }

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

    private var openedDatabase: SQLiteDatabase? = null
    private val helper = object : SQLiteOpenHelper(
        context,
        databaseName(pluginId),
        null,
        1,
    ) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setMaximumSize(MAX_DATABASE_BYTES)
        }

        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val database: SQLiteDatabase
        get() = helper.writableDatabase.also { openedDatabase = it }
    private val lifecycleLock = Any()
    private var closed = false
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "plugin-db-${databaseName(pluginId).substring(7, 19)}").apply {
            isDaemon = true
        }
    }

    fun execute(sql: String, params: JsonElement?): Long = onDatabaseThread {
        val bindings = params.asBindings()
        val writableDatabase = database
        if (bindings.isEmpty()) {
            writableDatabase.execSQL(sql)
        } else {
            writableDatabase.execSQL(sql, bindings.toTypedArray())
        }
        runCatching { writableDatabase.compileStatement("SELECT changes()").use { it.simpleQueryForLong() } }
            .getOrDefault(0)
    }

    fun query(sql: String, params: JsonElement?): List<JsonElement> = onDatabaseThread {
        withQueryDeadline { cancellationSignal ->
            database.rawQueryWithBindings(sql, params.asBindings(), cancellationSignal).use { cursor ->
                buildList {
                    var encodedBytes = 0
                    while (size < MAX_DATABASE_ROWS && cursor.moveToNext()) {
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
    }

    fun tableExists(tableName: String?): Boolean {
        if (tableName.isNullOrBlank()) return false
        return onDatabaseThread {
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName),
            ).use { it.count > 0 }
        }
    }

    fun beginTransaction() = onDatabaseThread { database.beginTransaction() }

    fun commitTransaction() = onDatabaseThread {
        database.setTransactionSuccessful()
        database.endTransaction()
    }

    fun rollbackTransaction() = onDatabaseThread {
        if (database.inTransaction()) database.endTransaction()
    }

    fun close() {
        val closeTask = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            submitDatabaseTask {
                runCatching {
                    openedDatabase?.let { database ->
                        if (database.inTransaction()) database.endTransaction()
                    }
                }
                helper.close()
            }
        }
        try {
            awaitDatabaseTask(closeTask)
        } finally {
            databaseExecutor.shutdownNow()
        }
    }

    private fun <T> onDatabaseThread(block: () -> T): T {
        val task = synchronized(lifecycleLock) {
            check(!closed) { "Plugin database is closed" }
            submitDatabaseTask(block)
        }
        return awaitDatabaseTask(task)
    }

    private fun <T> submitDatabaseTask(block: () -> T): Future<T> = try {
        databaseExecutor.submit(Callable { block() })
    } catch (error: RejectedExecutionException) {
        throw IllegalStateException("Plugin database is closed", error)
    }

    private fun <T> awaitDatabaseTask(task: Future<T>): T = try {
        task.get()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while waiting for plugin database", error)
    } catch (error: ExecutionException) {
        val cause = error.cause ?: error
        when (cause) {
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw IllegalStateException("Plugin database operation failed", cause)
        }
    }

    private fun SQLiteDatabase.rawQueryWithBindings(
        sql: String,
        bindings: List<Any?>,
        cancellationSignal: CancellationSignal,
    ): Cursor = rawQueryWithFactory(
        { _, driver, editTable, query ->
            query.bindAll(bindings)
            SQLiteCursor(driver, editTable, query)
        },
        sql,
        null,
        READ_ONLY_EDIT_TABLE,
        cancellationSignal,
    )

    private fun <T> withQueryDeadline(block: (CancellationSignal) -> T): T {
        val cancellationSignal = CancellationSignal()
        val timeout = queryDeadlineExecutor.schedule(
            cancellationSignal::cancel,
            QUERY_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        return try {
            block(cancellationSignal)
        } finally {
            timeout.cancel(false)
        }
    }

    private fun SQLiteProgram.bindAll(bindings: List<Any?>) {
        bindings.forEachIndexed { index, value ->
            val parameterIndex = index + 1
            when (value) {
                null -> bindNull(parameterIndex)
                is Long -> bindLong(parameterIndex, value)
                is Double -> bindDouble(parameterIndex, value)
                is String -> bindString(parameterIndex, value)
                else -> error("Unsupported database binding type: ${value::class.java.simpleName}")
            }
        }
    }

    private fun JsonElement?.asBindings(): List<Any?> = when (this) {
        null, JsonNull -> emptyList()
        is JsonArray -> map { value ->
            when (value) {
                JsonNull -> null
                is JsonPrimitive -> if (value.isString) {
                    value.content
                } else {
                    value.booleanOrNull?.let { if (it) 1L else 0L }
                        ?: value.longOrNull
                        ?: value.doubleOrNull
                        ?: error("Unsupported database binding")
                }
                else -> throw IllegalArgumentException("Database bindings must be scalar values")
            }
        }
        else -> throw IllegalArgumentException("Database bindings must be an array")
    }
}
