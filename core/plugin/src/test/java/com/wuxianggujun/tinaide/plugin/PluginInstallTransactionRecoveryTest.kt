package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import org.junit.After
import org.junit.Assert.assertThrows
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
    application = Application::class,
)
class PluginInstallTransactionRecoveryTest {

    private lateinit var context: Application
    private lateinit var pluginsDir: File
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var faultStore: PluginFaultStore
    private val pluginIds = mutableSetOf<String>()

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
        prefs.edit().clear().commit()
        pluginsDir.deleteRecursively()
    }

    @Test
    fun `recovery restores previous directory preferences and quarantine after interrupted upgrade`() {
        val pluginId = pluginId("upgrade")
        val backupName = "$pluginId-backup"
        writeManifest(File(pluginsDir, pluginId), pluginId, "2.0.0")
        writeManifest(File(pluginsDir, ".backup/$backupName"), pluginId, "1.0.0")
        prefs.edit()
            .putBoolean("desired_enabled_$pluginId", false)
            .putBoolean("enabled_$pluginId", false)
            .commit()
        val previousFault = fault(pluginId, "1.0.0", "previous fault")
        faultStore.recordFault(fault(pluginId, "2.0.0", "new fault"))
        writeTransaction(
            PluginInstallTransactionRecord(
                transactionId = "upgrade",
                pluginId = pluginId,
                previousVersion = "1.0.0",
                backupDirectoryName = backupName,
                hadDesiredEnabled = true,
                oldDesiredEnabled = true,
                hadLegacyEnabled = true,
                oldLegacyEnabled = true,
                previousFault = previousFault,
            ),
        )

        PluginManager(context).recoverInterruptedInstallTransactions()

        assertThat(readVersion(File(pluginsDir, pluginId))).isEqualTo("1.0.0")
        assertThat(prefs.getBoolean("desired_enabled_$pluginId", false)).isTrue()
        assertThat(prefs.getBoolean("enabled_$pluginId", false)).isTrue()
        assertThat(faultStore.getFault(pluginId)).isEqualTo(previousFault)
        assertThat(File(pluginsDir, ".transactions").listFiles()).isEmpty()
        assertThat(File(pluginsDir, ".backup").listFiles()).isEmpty()
    }

    @Test
    fun `recovery removes incomplete new install and newly-created preference keys`() {
        val pluginId = pluginId("new-install")
        writeManifest(File(pluginsDir, pluginId), pluginId, "1.0.0")
        prefs.edit()
            .putBoolean("desired_enabled_$pluginId", true)
            .putBoolean("enabled_$pluginId", true)
            .commit()
        writeTransaction(
            PluginInstallTransactionRecord(
                transactionId = "new-install",
                pluginId = pluginId,
                hadDesiredEnabled = false,
                oldDesiredEnabled = false,
                hadLegacyEnabled = false,
                oldLegacyEnabled = false,
            ),
        )

        PluginManager(context).recoverInterruptedInstallTransactions()

        assertThat(File(pluginsDir, pluginId).exists()).isFalse()
        assertThat(prefs.contains("desired_enabled_$pluginId")).isFalse()
        assertThat(prefs.contains("enabled_$pluginId")).isFalse()
    }

    @Test
    fun `invalid journal fails closed and preserves rollback artifacts`() {
        val stagingArtifact = File(pluginsDir, ".staging/pending/file.lua").apply {
            parentFile?.mkdirs()
            writeText("return true")
        }
        val backupArtifact = File(pluginsDir, ".backup/unknown/manifest.json").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        File(pluginsDir, ".transactions/broken.json").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }

        assertThrows(IllegalStateException::class.java) {
            PluginManager(context).recoverInterruptedInstallTransactions()
        }

        assertThat(stagingArtifact.exists()).isTrue()
        assertThat(backupArtifact.exists()).isTrue()
    }

    private fun pluginId(suffix: String): String = "plugin.transaction.$suffix.${System.nanoTime()}".also(pluginIds::add)

    private fun writeManifest(directory: File, pluginId: String, version: String) {
        directory.mkdirs()
        JsonSerializer.encodeToFile(
            File(directory, PluginManager.MANIFEST_FILE_NAME),
            PluginManifest.serializer(),
            PluginManifest(id = pluginId, name = pluginId, version = version),
        )
    }

    private fun readVersion(directory: File): String = JsonSerializer.decodeFromFile<PluginManifest>(
        File(directory, PluginManager.MANIFEST_FILE_NAME),
    ).version

    private fun writeTransaction(record: PluginInstallTransactionRecord) {
        val transactionFile = File(pluginsDir, ".transactions/${record.transactionId}.json")
        transactionFile.parentFile?.mkdirs()
        JsonSerializer.encodeToFile(transactionFile, PluginInstallTransactionRecord.serializer(), record)
    }

    private fun fault(pluginId: String, version: String, message: String) = PluginFaultRecord(
        pluginId = pluginId,
        pluginVersion = version,
        phase = PluginFaultPhase.STARTUP,
        kind = PluginFaultKind.STARTUP_EXCEPTION,
        message = message,
        timestampMillis = 1L,
        executionId = "execution-$version",
    )
}
