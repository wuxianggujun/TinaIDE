package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.runtime.PluginDatabase
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
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

    @Test
    fun `marketplace expectation rejects a package before it can replace another plugin`() = runBlocking {
        val requestedId = pluginId("requested-market-id")
        val packageId = pluginId("package-id")
        val pluginManager = PluginManager(context)
        val source = createInstallSource(packageId, version = "2.0.0")

        val result = runCatching {
            pluginManager.installPluginFromDirectory(
                extractedDir = source,
                allowSkipIfSameVersion = false,
                expectedPackage = PluginPackageExpectation(requestedId, "2.0.0"),
            )
        }

        assertThat(result.isFailure).isTrue()
        assertThat(File(pluginsDir, requestedId).exists()).isFalse()
        assertThat(File(pluginsDir, packageId).exists()).isFalse()
    }

    @Test
    fun `uninstall revokes grants and removes plugin persistent data`() = runBlocking {
        val pluginId = pluginId("uninstall-cleanup")
        writePluginManifest(pluginId = pluginId, type = PluginTypes.CONFIG)
        val pluginManager = PluginManager(context)
        pluginManager.refreshInstalledPlugins()
        val permissionManager = PluginPermissionManager.getInstance(context)
        permissionManager.grantPermission(pluginId, PluginPermission.NETWORK_UNRESTRICTED)
        val storage = context.getSharedPreferences("plugin_storage", Context.MODE_PRIVATE)
        storage.edit().putString("$pluginId:key", "value").commit()
        val databaseFile = context.getDatabasePath(PluginDatabase.databaseName(pluginId)).apply {
            parentFile?.mkdirs()
            writeText("database", Charsets.UTF_8)
        }

        pluginManager.uninstallPlugin(pluginId).getOrThrow()

        assertThat(permissionManager.getGrantedPermissions(pluginId)).isEmpty()
        assertThat(storage.contains("$pluginId:key")).isFalse()
        assertThat(databaseFile.exists()).isFalse()
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
