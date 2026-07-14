package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSeverity
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
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
class PluginInstallHelperTest {

    private lateinit var context: Application
    private lateinit var pluginManager: PluginManager
    private lateinit var permissionManager: PluginPermissionManager
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        pluginManager = PluginManager(context)
        pluginManager.onCreate()
        permissionManager = PluginPermissionManager.getInstance(context)
        tempDir = Files.createTempDirectory("plugin-install-helper-").toFile()
    }

    @After
    fun tearDown() {
        pluginManager.onDestroy()
        tempDir.deleteRecursively()
        context.filesDir.resolve("plugins").deleteRecursively()
        permissionManager.revokeAllPermissions("demo.auto.permission")
        permissionManager.revokeAllPermissions("demo.rollback.permission")
    }

    @Test
    fun `previewPluginInstall should block archive with validation errors`() = runTest {
        val zipFile = createPluginArchive(
            manifest = PluginManifest(
                id = "demo.invalid.network",
                name = "Invalid Network Plugin",
                version = "1.0.0",
                type = "config",
                networkHosts = listOf("https://api.example.com"),
            )
        )

        val preview = createPluginInstallPreview(context, zipFile)

        assertThat(preview).isInstanceOf(PluginInstallPreview.Blocked::class.java)
        val blocked = preview as PluginInstallPreview.Blocked
        assertThat(blocked.diagnosticsReport.highestSeverity)
            .isEqualTo(PluginDiagnosticSeverity.ERROR)
        assertThat(
            blocked.diagnosticsReport.issues.any { issue ->
                issue.message.contains("networkHosts")
            }
        ).isTrue()
        assertThat(blocked.tempFile.exists()).isTrue()

        blocked.tempFile.delete()
    }

    @Test
    fun `previewPluginInstall should expose warnings but allow continuation`() = runTest {
        val zipFile = createPluginArchive(
            manifest = PluginManifest(
                id = "demo.warning.permission",
                name = "Warning Permission Plugin",
                version = "1.0.0",
                type = "config",
                permissions = listOf("editor.read", "editor.read"),
            )
        )

        val preview = createPluginInstallPreview(context, zipFile)

        assertThat(preview).isInstanceOf(PluginInstallPreview.Ready::class.java)
        val pending = (preview as PluginInstallPreview.Ready).pendingInstall
        assertThat(pending.hasPreflightWarnings).isTrue()
        assertThat(pending.diagnosticsReport.highestSeverity)
            .isEqualTo(PluginDiagnosticSeverity.WARNING)
        assertThat(pending.tempFile.exists()).isTrue()

        pending.tempFile.delete()
    }

    @Test
    fun `finishPluginInstall should refresh plugin manager state`() = runTest {
        val zipFile = createPluginArchive(
            manifest = PluginManifest(
                id = "demo.refresh.install",
                name = "Refresh Install Plugin",
                version = "1.0.0",
                type = "config",
            )
        )

        val outcome = finishPluginInstall(
            context = context,
            pluginManager = pluginManager,
            pluginFile = zipFile,
            toastPluginsInstalledTemplate = "Installed %s",
            toastPluginsInstallFailedTemplate = "Failed %s",
        )

        assertThat(outcome.message).isEqualTo("Installed Refresh Install Plugin")
        assertThat(outcome.manifest?.id).isEqualTo("demo.refresh.install")
        assertThat(pluginManager.getInstalledPlugin("demo.refresh.install")?.manifest?.name)
            .isEqualTo("Refresh Install Plugin")
        assertThat(zipFile.exists()).isFalse()
    }

    @Test
    fun `finishPluginInstall should auto grant low risk permissions before script install`() = runTest {
        val pluginId = "demo.auto.permission"
        val zipFile = createPluginArchive(
            manifest = PluginManifest(
                id = pluginId,
                name = "Auto Permission Plugin",
                version = "1.0.0",
                type = "script",
                main = "main.lua",
                permissions = listOf(PluginPermission.EDITOR_READ.id),
            ),
            extraFiles = mapOf("main.lua" to "return {}"),
        )

        val outcome = finishPluginInstall(
            context = context,
            pluginManager = pluginManager,
            pluginFile = zipFile,
            toastPluginsInstalledTemplate = "Installed %s",
            toastPluginsInstallFailedTemplate = "Failed %s",
            permissionManager = permissionManager,
            permissions = setOf(PluginPermission.EDITOR_READ),
            permissionPluginId = pluginId,
        )

        assertThat(outcome.manifest?.id).isEqualTo(pluginId)
        assertThat(permissionManager.hasPermission(pluginId, PluginPermission.EDITOR_READ)).isTrue()
    }

    @Test
    fun `finishPluginInstall should restore grants when script install fails`() = runTest {
        val pluginId = "demo.rollback.permission"
        permissionManager.grantPermission(pluginId, PluginPermission.EDITOR_READ)
        val zipFile = createPluginArchive(
            manifest = PluginManifest(
                id = pluginId,
                name = "Rollback Permission Plugin",
                version = "1.0.0",
                type = "script",
                main = "missing.lua",
                permissions = listOf(PluginPermission.NETWORK_UNRESTRICTED.id),
            ),
        )

        val outcome = finishPluginInstall(
            context = context,
            pluginManager = pluginManager,
            pluginFile = zipFile,
            toastPluginsInstalledTemplate = "Installed %s",
            toastPluginsInstallFailedTemplate = "Failed %s",
            permissionManager = permissionManager,
            permissions = setOf(PluginPermission.NETWORK_UNRESTRICTED),
            permissionPluginId = pluginId,
        )

        assertThat(outcome.manifest).isNull()
        assertThat(permissionManager.getGrantedPermissions(pluginId))
            .containsExactly(PluginPermission.EDITOR_READ)
        assertThat(zipFile.exists()).isFalse()
    }

    private fun createPluginArchive(
        manifest: PluginManifest,
        extraFiles: Map<String, String> = emptyMap(),
    ): File {
        val zipFile = File(tempDir, "${manifest.id}.tinaplug")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(PluginManager.MANIFEST_FILE_NAME))
            zip.write(JsonSerializer.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            extraFiles.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return zipFile
    }
}
