package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.runtime.PluginDatabase
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = context.packageName
                versionName = "0.18.11"
                applicationInfo = context.applicationInfo
            },
        )
        pluginsDir = File(context.filesDir, "plugins")
        pluginsDir.deleteRecursively()
        pluginsDir.mkdirs()
        prefs = context.getSharedPreferences("tinaide_plugins", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        faultStore = PluginFaultStore.getInstance(context)
    }

    @After
    fun tearDown() {
        val permissionManager = PluginPermissionManager.getInstance(context)
        pluginIds.forEach { pluginId ->
            faultStore.clearAllForUninstall(pluginId)
            permissionManager.revokeAllPermissions(pluginId)
        }
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
    fun `first install enables declarative config plugin`() = runBlocking {
        val pluginId = pluginId("config-first-install")
        val pluginManager = PluginManager(context)

        pluginManager.installPluginFromDirectory(
            extractedDir = createInstallSource(pluginId, version = "1.0.0"),
            allowSkipIfSameVersion = false,
        )
        pluginManager.refreshInstalledPlugins()

        assertThat(prefs.getBoolean("desired_enabled_$pluginId", false)).isTrue()
        assertThat(pluginManager.isPluginEnabled(pluginId)).isTrue()
    }

    @Test
    fun `first install keeps executable plugin types disabled`() = runBlocking {
        val pluginManager = PluginManager(context)

        listOf(PluginTypes.SCRIPT, PluginTypes.HYBRID, PluginTypes.LSP, PluginTypes.SYSTEM).forEach { type ->
            val pluginId = pluginId("${type.lowercase()}-first-install")
            pluginManager.installPluginFromDirectory(
                extractedDir = createInstallSource(pluginId, version = "1.0.0", type = type),
                allowSkipIfSameVersion = false,
            )
            pluginManager.refreshInstalledPlugins()

            assertThat(prefs.getBoolean("desired_enabled_$pluginId", true)).isFalse()
            assertThat(pluginManager.isPluginEnabled(pluginId)).isFalse()
        }
    }

    @Test
    fun `incompatible installed plugin remains visible but cannot be enabled`() = runBlocking {
        val pluginId = pluginId("incompatible-refresh")
        writePluginManifest(
            pluginId = pluginId,
            type = PluginTypes.CONFIG,
            minAppVersion = "999.0.0",
        )
        prefs.edit()
            .putBoolean("desired_enabled_$pluginId", true)
            .putBoolean("enabled_$pluginId", true)
            .commit()
        val pluginManager = PluginManager(context)

        pluginManager.refreshInstalledPlugins()
        val enableResult = pluginManager.setPluginEnabled(pluginId, true)

        assertThat(pluginManager.getInstalledPlugin(pluginId)).isNotNull()
        assertThat(pluginManager.getInstalledPlugin(pluginId)?.enabled).isFalse()
        assertThat(pluginManager.isPluginEnabled(pluginId)).isFalse()
        assertThat(prefs.getBoolean("desired_enabled_$pluginId", false)).isTrue()
        assertThat(prefs.getBoolean("enabled_$pluginId", true)).isFalse()
        assertThat(enableResult.isFailure).isTrue()
    }

    @Test
    fun `install rejects plugin requiring a newer host before commit`() = runBlocking {
        val pluginId = pluginId("incompatible-install")
        val pluginManager = PluginManager(context)
        val installSource = createInstallSource(
            pluginId = pluginId,
            version = "1.0.0",
            minAppVersion = "999.0.0",
        )

        val result = runCatching {
            pluginManager.installPluginFromDirectory(
                extractedDir = installSource,
                allowSkipIfSameVersion = false,
            )
        }

        assertThat(result.isFailure).isTrue()
        assertThat(File(pluginsDir, pluginId).exists()).isFalse()
        assertThat(pluginManager.getInstalledPlugin(pluginId)).isNull()
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
    fun `permission install transaction restores grants after package identity mismatch`() = runBlocking {
        val requestedId = pluginId("permission-requested-id")
        val packageId = pluginId("permission-package-id")
        val permissionManager = PluginPermissionManager.getInstance(context)
        permissionManager.grantPermission(requestedId, PluginPermission.EDITOR_READ)
        val archive = createPluginArchive(
            PluginManifest(
                id = packageId,
                name = packageId,
                version = "2.0.0",
                type = PluginTypes.CONFIG,
            ),
        )

        val result = PluginManager(context).installWithPermissions(
            zipFile = archive,
            pluginId = requestedId,
            version = "2.0.0",
            permissions = setOf(PluginPermission.NETWORK_UNRESTRICTED),
        )

        assertThat(result.isFailure).isTrue()
        assertThat(permissionManager.getGrantedPermissions(requestedId))
            .containsExactly(PluginPermission.EDITOR_READ)
        assertThat(File(pluginsDir, requestedId).exists()).isFalse()
        assertThat(File(pluginsDir, packageId).exists()).isFalse()
    }

    @Test
    fun `permission install transaction rejects grants absent from verified manifest`() = runBlocking {
        val pluginId = pluginId("permission-declaration")
        val permissionManager = PluginPermissionManager.getInstance(context)
        permissionManager.grantPermission(pluginId, PluginPermission.EDITOR_READ)
        val archive = createPluginArchive(
            PluginManifest(
                id = pluginId,
                name = pluginId,
                version = "1.0.0",
                type = PluginTypes.CONFIG,
            ),
        )

        val result = PluginManager(context).installWithPermissions(
            zipFile = archive,
            pluginId = pluginId,
            version = "1.0.0",
            permissions = setOf(PluginPermission.NETWORK_UNRESTRICTED),
        )

        assertThat(result.isFailure).isTrue()
        assertThat(permissionManager.getGrantedPermissions(pluginId))
            .containsExactly(PluginPermission.EDITOR_READ)
        assertThat(File(pluginsDir, pluginId).exists()).isFalse()
    }

    @Test
    fun `permission install transaction removes stale grants after verified replacement`() = runBlocking {
        val pluginId = pluginId("permission-trim")
        val permissionManager = PluginPermissionManager.getInstance(context)
        permissionManager.replacePermissions(
            pluginId,
            setOf(PluginPermission.EDITOR_READ, PluginPermission.CLIPBOARD_READ),
        )
        val archive = createPluginArchive(
            PluginManifest(
                id = pluginId,
                name = pluginId,
                version = "2.0.0",
                type = PluginTypes.CONFIG,
                permissions = listOf(PluginPermission.NETWORK_UNRESTRICTED.id),
                optionalPermissions = listOf(PluginPermission.EDITOR_READ.id),
            ),
        )

        val result = PluginManager(context).installWithPermissions(
            zipFile = archive,
            pluginId = pluginId,
            version = "2.0.0",
            permissions = setOf(PluginPermission.NETWORK_UNRESTRICTED),
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(permissionManager.getGrantedPermissions(pluginId))
            .containsExactly(
                PluginPermission.EDITOR_READ,
                PluginPermission.NETWORK_UNRESTRICTED,
            )
        Unit
    }

    @Test
    fun `plain install also removes grants absent from replacement manifest`() = runBlocking {
        val pluginId = pluginId("plain-install-permission-trim")
        val permissionManager = PluginPermissionManager.getInstance(context)
        permissionManager.replacePermissions(
            pluginId,
            setOf(PluginPermission.EDITOR_READ, PluginPermission.CLIPBOARD_READ),
        )
        val archive = createPluginArchive(
            PluginManifest(
                id = pluginId,
                name = pluginId,
                version = "2.0.0",
                type = PluginTypes.CONFIG,
                optionalPermissions = listOf(PluginPermission.EDITOR_READ.id),
            ),
        )

        val result = PluginManager(context).install(archive)

        assertThat(result.isSuccess).isTrue()
        assertThat(permissionManager.getGrantedPermissions(pluginId))
            .containsExactly(PluginPermission.EDITOR_READ)
        Unit
    }

    @Test
    fun `optional permission mutation accepts only manifest optional permissions`() = runBlocking {
        val pluginId = pluginId("optional-permission")
        writePluginManifest(
            pluginId = pluginId,
            type = PluginTypes.CONFIG,
            optionalPermissions = listOf(PluginPermission.NETWORK_UNRESTRICTED.id),
        )
        val pluginManager = PluginManager(context)

        val grant = pluginManager.setOptionalPermission(
            pluginId = pluginId,
            permission = PluginPermission.NETWORK_UNRESTRICTED,
            granted = true,
        )
        val undeclared = pluginManager.setOptionalPermission(
            pluginId = pluginId,
            permission = PluginPermission.EDITOR_READ,
            granted = true,
        )

        assertThat(grant.isSuccess).isTrue()
        assertThat(undeclared.isFailure).isTrue()
        assertThat(PluginPermissionManager.getInstance(context).getGrantedPermissions(pluginId))
            .containsExactly(PluginPermission.NETWORK_UNRESTRICTED)
        Unit
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

    @Test
    fun `uninstall commits plugin removal and retries interrupted persistent cleanup`() = runBlocking {
        val pluginId = pluginId("uninstall-recovery")
        writePluginManifest(pluginId = pluginId, type = PluginTypes.CONFIG)
        val pluginManager = PluginManager(context)
        pluginManager.refreshInstalledPlugins()
        val permissionManager = PluginPermissionManager.getInstance(context)
        permissionManager.grantPermission(pluginId, PluginPermission.NETWORK_UNRESTRICTED)
        val databaseFile = context.getDatabasePath(PluginDatabase.databaseName(pluginId)).apply {
            parentFile?.mkdirs()
            writeText("database", Charsets.UTF_8)
        }
        val blockedSidecar = File(databaseFile.path + "-wal").apply {
            mkdirs()
            File(this, "blocked").writeText("keep directory non-empty", Charsets.UTF_8)
        }

        val result = pluginManager.uninstallPlugin(pluginId)

        assertThat(result.isSuccess).isTrue()
        assertThat(File(pluginsDir, pluginId).exists()).isFalse()
        assertThat(
            prefs.getStringSet(PluginManager.PREF_PENDING_UNINSTALL_CLEANUP, emptySet()),
        ).contains(pluginId)
        assertThat(prefs.getBoolean("enabled_$pluginId", true)).isFalse()

        blockedSidecar.deleteRecursively()
        val recoveredManager = PluginManager(context)
        recoveredManager.onCreate()
        recoveredManager.awaitInitialization()

        assertThat(permissionManager.getGrantedPermissions(pluginId)).isEmpty()
        assertThat(databaseFile.exists()).isFalse()
        assertThat(
            prefs.getStringSet(PluginManager.PREF_PENDING_UNINSTALL_CLEANUP, emptySet()),
        ).doesNotContain(pluginId)
        recoveredManager.onDestroy()
    }

    @Test
    fun `reinstall finishes pending uninstall before committing replacement`() = runBlocking {
        val pluginId = pluginId("reinstall-after-cleanup")
        writePluginManifest(pluginId = pluginId, type = PluginTypes.CONFIG)
        val pluginManager = PluginManager(context)
        pluginManager.refreshInstalledPlugins()
        val permissionManager = PluginPermissionManager.getInstance(context)
        permissionManager.grantPermission(pluginId, PluginPermission.NETWORK_UNRESTRICTED)
        val databaseFile = context.getDatabasePath(PluginDatabase.databaseName(pluginId)).apply {
            parentFile?.mkdirs()
            writeText("database", Charsets.UTF_8)
        }
        val blockedSidecar = File(databaseFile.path + "-wal").apply {
            mkdirs()
            File(this, "blocked").writeText("keep directory non-empty", Charsets.UTF_8)
        }

        pluginManager.uninstallPlugin(pluginId).getOrThrow()
        blockedSidecar.deleteRecursively()
        val replacement = createPluginArchive(
            PluginManifest(
                id = pluginId,
                name = pluginId,
                version = "2.0.0",
                type = PluginTypes.CONFIG,
            ),
        )

        val installed = pluginManager.install(replacement).getOrThrow()

        assertThat(installed.manifest.version).isEqualTo("2.0.0")
        assertThat(File(pluginsDir, pluginId).isDirectory).isTrue()
        assertThat(permissionManager.getGrantedPermissions(pluginId)).isEmpty()
        assertThat(databaseFile.exists()).isFalse()
        assertThat(
            prefs.getStringSet(PluginManager.PREF_PENDING_UNINSTALL_CLEANUP, emptySet()),
        ).doesNotContain(pluginId)
    }

    private fun writePluginManifest(
        pluginId: String,
        type: String,
        version: String = "1.0.0",
        root: File = pluginsDir,
        optionalPermissions: List<String>? = null,
        minAppVersion: String? = null,
    ) {
        val pluginDir = File(root, pluginId).apply { mkdirs() }
        File(pluginDir, PluginManager.MANIFEST_FILE_NAME).writeText(
            JsonSerializer.encode(
                PluginManifest(
                    id = pluginId,
                    name = pluginId,
                    version = version,
                    type = type,
                    optionalPermissions = optionalPermissions,
                    minAppVersion = minAppVersion,
                ),
            ),
            Charsets.UTF_8,
        )
    }

    private fun createPluginArchive(manifest: PluginManifest): File {
        val archive = File(context.cacheDir, "${manifest.id}-${System.nanoTime()}.tinaplug")
        temporaryDirectories += archive
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(PluginManager.MANIFEST_FILE_NAME))
            zip.write(JsonSerializer.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return archive
    }

    private fun createInstallSource(
        pluginId: String,
        version: String,
        minAppVersion: String? = null,
        type: String = PluginTypes.CONFIG,
    ): File {
        val sourceRoot = File(context.cacheDir, "plugin-install-${System.nanoTime()}").apply {
            mkdirs()
            temporaryDirectories += this
        }
        writePluginManifest(
            pluginId = pluginId,
            type = type,
            version = version,
            root = sourceRoot,
            minAppVersion = minAppVersion,
        )
        return File(sourceRoot, pluginId).also { pluginDir ->
            if (type.equals(PluginTypes.SCRIPT, ignoreCase = true) ||
                type.equals(PluginTypes.HYBRID, ignoreCase = true)
            ) {
                File(pluginDir, "main.lua").writeText("return {}\n", Charsets.UTF_8)
            }
        }
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
