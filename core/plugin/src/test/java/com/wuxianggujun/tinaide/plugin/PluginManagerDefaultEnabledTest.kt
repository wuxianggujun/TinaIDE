package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class PluginManagerDefaultEnabledTest {

    private lateinit var context: Application
    private lateinit var pluginsDir: File
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var faultStore: PluginFaultStore
    private val pluginIds = mutableSetOf<String>()
    private val temporaryDirectories = mutableListOf<File>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        pluginsDir = File(context.filesDir, "plugins")
        pluginsDir.deleteRecursively()
        pluginsDir.mkdirs()
        prefs = context.getSharedPreferences("tinaide_plugins", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        faultStore = PluginFaultStore.getInstance(context)
    }

    @After
    fun tearDown() {
        pluginIds.forEach(faultStore::clearAllForUninstall)
        temporaryDirectories.forEach(File::deleteRecursively)
        pluginsDir.deleteRecursively()
        prefs.edit().clear().commit()
    }

    @Test
    fun refreshInstalledPlugins_shouldUseCurrentDefaultEnabledRules() = runBlocking {
        writePluginManifest(pluginId = "tinaide.system.default", type = PluginTypes.SYSTEM)
        writePluginManifest(pluginId = "tinaide.config.default", type = PluginTypes.CONFIG)

        val pluginManager = PluginManager(context)
        pluginManager.refreshInstalledPlugins()

        assertThat(pluginManager.isPluginEnabled("tinaide.system.default")).isFalse()
        assertThat(pluginManager.getInstalledPlugin("tinaide.system.default")?.enabled).isFalse()
        assertThat(pluginManager.isPluginEnabled("tinaide.config.default")).isTrue()
        assertThat(pluginManager.getInstalledPlugin("tinaide.config.default")?.enabled).isTrue()
    }

    @Test
    fun `user re-enable clears quarantine and restores downgrade-safe enabled state`() = runBlocking {
        val pluginId = pluginId("manual-reenable")
        writePluginManifest(pluginId = pluginId, type = PluginTypes.CONFIG)
        quarantine(pluginId, version = "1.0.0")

        val pluginManager = PluginManager(context)
        pluginManager.refreshInstalledPlugins()
        assertThat(pluginManager.isPluginEnabled(pluginId)).isFalse()

        pluginManager.setPluginEnabled(pluginId, true).getOrThrow()

        assertThat(faultStore.getFault(pluginId)).isNull()
        assertThat(prefs.getBoolean("desired_enabled_$pluginId", false)).isTrue()
        assertThat(prefs.getBoolean("enabled_$pluginId", false)).isTrue()
        assertThat(pluginManager.isPluginEnabled(pluginId)).isTrue()
    }

    @Test
    fun `only strictly higher version clears quarantine and legacy disabled state`() = runBlocking {
        val pluginId = pluginId("version-recovery")
        writePluginManifest(pluginId = pluginId, type = PluginTypes.CONFIG, version = "1.0.0")
        quarantine(pluginId, version = "1.0.0")
        val pluginManager = PluginManager(context)
        pluginManager.refreshInstalledPlugins()

        pluginManager.installPluginFromDirectory(
            extractedDir = createInstallSource(pluginId, version = "1.0.0"),
            allowSkipIfSameVersion = false,
        )

        assertThat(faultStore.getFault(pluginId)).isNotNull()
        assertThat(prefs.getBoolean("desired_enabled_$pluginId", false)).isTrue()
        assertThat(prefs.getBoolean("enabled_$pluginId", true)).isFalse()
        assertThat(pluginManager.isPluginEnabled(pluginId)).isFalse()

        pluginManager.installPluginFromDirectory(
            extractedDir = createInstallSource(pluginId, version = "1.1.0"),
            allowSkipIfSameVersion = false,
        )

        assertThat(faultStore.getFault(pluginId)).isNull()
        assertThat(prefs.getBoolean("desired_enabled_$pluginId", false)).isTrue()
        assertThat(prefs.getBoolean("enabled_$pluginId", false)).isTrue()
        assertThat(pluginManager.isPluginEnabled(pluginId)).isTrue()
    }

    private fun writePluginManifest(
        pluginId: String,
        type: String,
        version: String = "1.0.0",
        root: File = pluginsDir,
    ) {
        val pluginDir = File(root, pluginId).apply { mkdirs() }
        File(pluginDir, PluginManager.MANIFEST_FILE_NAME).writeText(
            """
            {
              "id": "$pluginId",
              "name": "$pluginId",
              "version": "$version",
              "type": "$type"
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }

    private fun createInstallSource(pluginId: String, version: String): File {
        val sourceRoot = File(context.cacheDir, "plugin-install-${System.nanoTime()}").apply {
            mkdirs()
            temporaryDirectories += this
        }
        writePluginManifest(pluginId, PluginTypes.CONFIG, version, sourceRoot)
        return File(sourceRoot, pluginId)
    }

    private fun quarantine(pluginId: String, version: String) {
        prefs.edit()
            .putBoolean("desired_enabled_$pluginId", true)
            .putBoolean("enabled_$pluginId", false)
            .commit()
        check(
            faultStore.recordFault(
                PluginFaultRecord(
                    pluginId = pluginId,
                    pluginVersion = version,
                    phase = PluginFaultPhase.STARTUP,
                    kind = PluginFaultKind.STARTUP_EXCEPTION,
                    message = "quarantined",
                    timestampMillis = 1L,
                    executionId = "execution-$pluginId",
                ),
            ),
        )
    }

    private fun pluginId(suffix: String): String = "plugin.manager.$suffix.${System.nanoTime()}".also(pluginIds::add)
}
