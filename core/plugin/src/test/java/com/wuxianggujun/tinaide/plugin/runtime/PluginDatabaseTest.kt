package com.wuxianggujun.tinaide.plugin.runtime

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class PluginDatabaseTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "plugins").deleteRecursively()
        context.databaseList().forEach(context::deleteDatabase)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "plugins").deleteRecursively()
        context.databaseList().forEach(context::deleteDatabase)
    }

    @Test
    fun `database names preserve distinct plugin identities`() {
        val dottedName = PluginDatabase.databaseName("com.example.plugin")

        assertThat(dottedName).hasLength("plugin_".length + 64 + ".db".length)
        assertThat(dottedName).isNotEqualTo(PluginDatabase.databaseName("com_example_plugin"))
    }

    @Test
    fun `legacy database migrates only when its installed owner is unambiguous`() {
        val pluginId = "com.example.plugin"
        File(context.filesDir, "plugins/$pluginId").mkdirs()
        val legacy = context.getDatabasePath(PluginDatabase.legacyDatabaseName(pluginId)).apply {
            parentFile?.mkdirs()
            writeText("legacy", Charsets.UTF_8)
        }

        PluginDatabase(context, pluginId).close()

        assertThat(legacy.exists()).isFalse()
        assertThat(context.getDatabasePath(PluginDatabase.databaseName(pluginId)).readText(Charsets.UTF_8))
            .isEqualTo("legacy")
    }

    @Test
    fun `ambiguous legacy database is never claimed by either plugin`() {
        val dottedId = "com.example.plugin"
        val underscoredId = "com_example_plugin"
        File(context.filesDir, "plugins/$dottedId").mkdirs()
        File(context.filesDir, "plugins/$underscoredId").mkdirs()
        val legacy = context.getDatabasePath(PluginDatabase.legacyDatabaseName(dottedId)).apply {
            parentFile?.mkdirs()
            writeText("ambiguous", Charsets.UTF_8)
        }

        PluginDatabase(context, dottedId).close()
        PluginDatabase(context, underscoredId).close()

        assertThat(legacy.exists()).isTrue()
        assertThat(context.getDatabasePath(PluginDatabase.databaseName(dottedId)).exists()).isFalse()
        assertThat(context.getDatabasePath(PluginDatabase.databaseName(underscoredId)).exists()).isFalse()
    }

    @Test
    fun `persistent file deletion removes current and legacy sidecars`() {
        val pluginId = "com.example.plugin"
        File(context.filesDir, "plugins/$pluginId").mkdirs()
        val names = listOf(
            PluginDatabase.databaseName(pluginId),
            PluginDatabase.legacyDatabaseName(pluginId),
        )
        val files = names.flatMap { name ->
            val database = context.getDatabasePath(name)
            listOf(
                database,
                File(database.path + "-wal"),
                File(database.path + "-shm"),
                File(database.path + "-journal"),
            )
        }.onEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("data", Charsets.UTF_8)
        }

        PluginDatabase.deletePersistentFiles(context, pluginId)

        assertThat(files.filter(File::exists)).isEmpty()
    }
}
