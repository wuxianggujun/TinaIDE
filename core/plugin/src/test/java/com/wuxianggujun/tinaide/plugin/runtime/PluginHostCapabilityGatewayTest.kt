package com.wuxianggujun.tinaide.plugin.runtime

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.PluginFaultStore
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginPanelContentStore
import com.wuxianggujun.tinaide.plugin.PluginPanelKey
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
    application = Application::class,
)
class PluginHostCapabilityGatewayTest {

    private lateinit var context: Application
    private lateinit var pluginManager: PluginManager
    private lateinit var permissionManager: PluginPermissionManager
    private lateinit var projectRoot: File
    private lateinit var gateway: PluginHostCapabilityGateway

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "plugins").deleteRecursively()
        context.getSharedPreferences("tinaide_plugins", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tina_plugin_configuration", Context.MODE_PRIVATE).edit().clear().commit()
        projectRoot = File(context.cacheDir, "plugin-gateway-workspace").apply {
            deleteRecursively()
            mkdirs()
        }
        installPluginFixture()

        pluginManager = PluginManager(context)
        PluginFaultStore.getInstance(context).clearAllForUninstall(PLUGIN_ID)
        pluginManager.refreshInstalledPlugins()
        permissionManager = PluginPermissionManager.getInstance(context).also {
            it.revokeAllPermissions(PLUGIN_ID)
        }
        gateway = PluginHostCapabilityGateway(
            context = context,
            pluginManager = pluginManager,
            projectRootProvider = { projectRoot.absolutePath },
            isGenerationCurrent = { pluginId, generation ->
                pluginId == PLUGIN_ID && generation == GENERATION
            },
        )
    }

    @After
    fun tearDown() {
        gateway.cleanup()
        permissionManager.revokeAllPermissions(PLUGIN_ID)
        PluginFaultStore.getInstance(context).clearAllForUninstall(PLUGIN_ID)
        pluginManager.onDestroy()
        File(context.filesDir, "plugins").deleteRecursively()
        projectRoot.deleteRecursively()
    }

    @Test
    fun `config get should preserve fallback and manifest default semantics`() {
        val fallback = gateway.call(
            request(
                namespace = "config",
                method = "get",
                args = JsonArray(listOf(JsonPrimitive("optional.label"), JsonPrimitive("fallback"))),
            ),
        )
        val invalidFallback = gateway.call(
            request(
                namespace = "config",
                method = "get",
                args = JsonArray(listOf(JsonPrimitive("optional.label"), JsonPrimitive(42))),
            ),
        )
        val manifestDefault = gateway.call(
            request(
                namespace = "config",
                method = "get",
                args = JsonArray(listOf(JsonPrimitive("label.withDefault"), JsonPrimitive("fallback"))),
            ),
        )

        assertThat(fallback.values.single()).isEqualTo(JsonPrimitive("fallback"))
        assertThat(invalidFallback.values.single()).isEqualTo(JsonNull)
        assertThat(manifestDefault.values.single()).isEqualTo(JsonPrimitive("manifest"))
    }

    @Test
    fun `workspace access should require both manifest declaration and runtime grant`() {
        File(projectRoot, "hello.txt").writeText("hello", Charsets.UTF_8)

        val missingRuntimeGrant = gateway.call(
            request("workspace", "readFile", JsonArray(listOf(JsonPrimitive("hello.txt")))),
        )
        permissionManager.grantPermission(PLUGIN_ID, PluginPermission.FILE_WRITE)
        val missingManifestDeclaration = gateway.call(
            request(
                "workspace",
                "writeFile",
                JsonArray(listOf(JsonPrimitive("blocked.txt"), JsonPrimitive("blocked"))),
            ),
        )
        permissionManager.grantPermission(PLUGIN_ID, PluginPermission.FILE_READ)
        val allowed = gateway.call(
            request("workspace", "readFile", JsonArray(listOf(JsonPrimitive("hello.txt")))),
        )

        assertThat(missingRuntimeGrant.errorKind).isEqualTo(PluginHostErrorKind.PERMISSION_DENIED)
        assertThat(missingManifestDeclaration.errorKind).isEqualTo(PluginHostErrorKind.PERMISSION_DENIED)
        assertThat(File(projectRoot, "blocked.txt").exists()).isFalse()
        assertThat(allowed.error).isNull()
        assertThat(allowed.values.single()).isEqualTo(JsonPrimitive("hello"))
    }

    @Test
    fun `host error text should be bounded by utf8 bytes`() {
        val response = gateway.call(
            request(
                namespace = "界".repeat(8 * 1024),
                method = "unknown",
                args = JsonArray(emptyList()),
            ),
        )

        assertThat(response.error).isNotNull()
        assertThat(response.error.orEmpty().toByteArray(Charsets.UTF_8).size).isAtMost(8 * 1024)
    }

    @Test
    fun `workspace scans should be rate limited independently`() {
        permissionManager.grantPermission(PLUGIN_ID, PluginPermission.FILE_READ)

        repeat(12) {
            val response = gateway.call(
                request("workspace", "findFiles", JsonArray(listOf(JsonPrimitive("*.txt")))),
            )
            assertThat(response.error).isNull()
        }
        val limited = gateway.call(
            request("workspace", "findFiles", JsonArray(listOf(JsonPrimitive("*.txt")))),
        )

        assertThat(limited.error).contains("rate limit")
        assertThat(limited.values.first()).isEqualTo(JsonArray(emptyList()))
    }

    @Test
    fun `commands should reject invalid target instead of executing without context`() {
        permissionManager.grantPermission(PLUGIN_ID, PluginPermission.COMMAND_EXECUTE)

        val response = gateway.call(
            request(
                "commands",
                "execute",
                JsonArray(
                    listOf(
                        JsonPrimitive("view.toggleFileTree"),
                        JsonPrimitive("../outside.txt"),
                    ),
                ),
            ),
        )

        assertThat(response.errorKind).isEqualTo(PluginHostErrorKind.INVALID_REQUEST)
        assertThat(response.values.first()).isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun `optional permissions require explicit grant even at no-risk level`() {
        val beforeGrant = gateway.call(request("diagnostics", "get", JsonArray(emptyList())))
        permissionManager.grantPermission(PLUGIN_ID, PluginPermission.DIAGNOSTICS_READ)
        val afterGrant = gateway.call(request("diagnostics", "get", JsonArray(emptyList())))
        permissionManager.revokePermission(PLUGIN_ID, PluginPermission.DIAGNOSTICS_READ)
        val afterRevoke = gateway.call(request("diagnostics", "get", JsonArray(emptyList())))

        assertThat(beforeGrant.errorKind).isEqualTo(PluginHostErrorKind.PERMISSION_DENIED)
        assertThat(afterGrant.error).isNull()
        assertThat(afterRevoke.errorKind).isEqualTo(PluginHostErrorKind.PERMISSION_DENIED)
    }

    @Test
    fun `panels can update only manifest-declared content and cleanup is targeted`() {
        val setResponse = gateway.call(
            request(
                "panels",
                "setContent",
                JsonArray(listOf(JsonPrimitive("status"), JsonPrimitive("ready"))),
            ),
        )
        val appendResponse = gateway.call(
            request(
                "panels",
                "appendContent",
                JsonArray(listOf(JsonPrimitive("status"), JsonPrimitive("\ndone"))),
            ),
        )
        val undeclaredResponse = gateway.call(
            request(
                "panels",
                "setContent",
                JsonArray(listOf(JsonPrimitive("private"), JsonPrimitive("blocked"))),
            ),
        )

        assertThat(setResponse.error).isNull()
        assertThat(appendResponse.error).isNull()
        assertThat(PluginPanelContentStore.contents.value[PluginPanelKey(PLUGIN_ID, "status")])
            .isEqualTo("ready\ndone")
        assertThat(undeclaredResponse.errorKind).isEqualTo(PluginHostErrorKind.INVALID_REQUEST)

        gateway.cleanupPlugin(PLUGIN_ID)

        assertThat(PluginPanelContentStore.contents.value).isEmpty()
    }

    @Test
    fun `events reject unknown subscriptions and prevent host event spoofing`() {
        val unknown = gateway.call(
            request(
                "events",
                "on",
                JsonArray(listOf(JsonPrimitive("host.private"), JsonPrimitive("callback"))),
            ),
        )
        val spoofed = gateway.call(
            request(
                "events",
                "emit",
                JsonArray(listOf(JsonPrimitive("editor.saved"))),
            ),
        )
        val invalidPayload = gateway.call(
            request(
                "events",
                "emit",
                JsonArray(listOf(JsonPrimitive("custom"), JsonPrimitive("not-an-object"))),
            ),
        )

        assertThat(unknown.errorKind).isEqualTo(PluginHostErrorKind.INVALID_REQUEST)
        assertThat(spoofed.errorKind).isEqualTo(PluginHostErrorKind.INVALID_REQUEST)
        assertThat(invalidPayload.errorKind).isEqualTo(PluginHostErrorKind.INVALID_REQUEST)
    }

    @Test
    fun `storage should persist accepted values and reject oversized values`() {
        permissionManager.grantPermission(PLUGIN_ID, PluginPermission.STORAGE_LOCAL)
        val accepted = gateway.call(
            request(
                "storage",
                "set",
                JsonArray(listOf(JsonPrimitive("key"), JsonPrimitive("value"))),
            ),
        )
        val loaded = gateway.call(
            request("storage", "get", JsonArray(listOf(JsonPrimitive("key")))),
        )
        val oversized = gateway.call(
            request(
                "storage",
                "set",
                JsonArray(listOf(JsonPrimitive("large"), JsonPrimitive("x".repeat(64 * 1024 + 1)))),
            ),
        )

        assertThat(accepted.error).isNull()
        assertThat(loaded.values.single()).isEqualTo(JsonPrimitive("value"))
        assertThat(oversized.error).contains("size limit")
    }

    @Test
    fun `network should reject malformed urls and oversized request bodies as invalid requests`() {
        permissionManager.grantPermission(PLUGIN_ID, PluginPermission.NETWORK_FETCH)

        val malformedUrl = gateway.call(
            request("network", "get", JsonArray(listOf(JsonPrimitive("not a url")))),
        )
        val oversizedBody = gateway.call(
            request(
                "network",
                "post",
                JsonArray(
                    listOf(
                        JsonPrimitive("https://example.com/upload"),
                        JsonPrimitive("x".repeat(8 * 1024 * 1024 + 1)),
                    ),
                ),
            ),
        )

        assertThat(malformedUrl.errorKind).isEqualTo(PluginHostErrorKind.INVALID_REQUEST)
        assertThat(oversizedBody.errorKind).isEqualTo(PluginHostErrorKind.INVALID_REQUEST)
        assertThat(oversizedBody.error).contains("size limit")
    }

    @Test
    fun `network should encapsulate oversized responses as business errors`() {
        val responseClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("x".repeat(8 * 1024 * 1024 + 1).toResponseBody())
                    .build()
            }
            .build()
        val capabilities = PluginHostDataCapabilities(
            context = context,
            pluginManager = pluginManager,
            hasAnyPermission = { _, _ -> true },
            networkClient = responseClient,
        )

        val response = capabilities.call(
            request("network", "get", JsonArray(listOf(JsonPrimitive("https://example.com/large")))),
        )

        assertThat(response.errorKind).isEqualTo(PluginHostErrorKind.BUSINESS_ERROR)
        assertThat(response.error).contains("size limit")
        capabilities.cleanup()
    }

    private fun request(
        namespace: String,
        method: String,
        args: JsonArray,
    ) = PluginHostCallRequest(
        pluginId = PLUGIN_ID,
        generation = GENERATION,
        namespace = namespace,
        method = method,
        args = args,
    )

    private fun installPluginFixture() {
        val pluginDir = File(context.filesDir, "plugins/$PLUGIN_ID").apply { mkdirs() }
        File(pluginDir, "main.lua").writeText("function ping() return 'pong' end", Charsets.UTF_8)
        File(pluginDir, "manifest.json").writeText(
            """
            {
              "id": "$PLUGIN_ID",
              "name": "Gateway Test",
              "version": "1.0.0",
              "apiVersion": 1,
              "type": "script",
              "main": "main.lua",
              "permissions": ["workspace.read", "storage.local", "network.fetch", "command.execute"],
              "optionalPermissions": ["diagnostics.read"],
              "networkHosts": ["example.com"],
              "contributions": {
                "panels": [
                  { "id": "status", "title": "Status" }
                ]
              },
              "configuration": {
                "title": "Gateway",
                "properties": {
                  "optional.label": { "type": "string" },
                  "label.withDefault": { "type": "string", "default": "manifest" }
                }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }

    private companion object {
        const val PLUGIN_ID = "test.gateway.capabilities"
        const val GENERATION = 7L
    }
}
