package com.wuxianggujun.tinaide.plugin

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import java.io.File
import java.nio.file.Files
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
class PluginMenuResolverTest {

    private lateinit var context: Application
    private lateinit var pluginDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        pluginDir = Files.createTempDirectory("plugin-menu-resolver").toFile()
        PluginCommandRegistry.clear()
    }

    @After
    fun tearDown() {
        PluginCommandRegistry.clear()
        pluginDir.deleteRecursively()
    }

    @Test
    fun `resolveFileTreeContextMenuItems should include registered plugin command`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.menu",
            pluginName = "Plugin Menu",
            commandId = "plugin.menu.sayHello",
            callbackName = "handleHello",
            title = "Runtime title"
        ).getOrThrow()
        val plugin = createPlugin(
            commands = listOf(
                PluginCommand(
                    id = "plugin.menu.sayHello",
                    title = "Manifest title"
                )
            ),
            menuItems = listOf(
                PluginMenuItem(command = "plugin.menu.sayHello")
            )
        )

        val items = PluginMenuResolver.resolveFileTreeContextMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = File(pluginDir, "README.md"),
            isDirectory = false
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().commandId).isEqualTo("plugin.menu.sayHello")
        assertThat(items.single().title).isEqualTo("Manifest title")
        assertThat(items.single().pluginId).isEqualTo("plugin.menu")
    }

    @Test
    fun `resolveFileTreeContextMenuItems should ignore unregistered plugin command`() {
        val plugin = createPlugin(
            commands = listOf(
                PluginCommand(
                    id = "plugin.menu.sayHello",
                    title = "Manifest title"
                )
            ),
            menuItems = listOf(
                PluginMenuItem(command = "plugin.menu.sayHello")
            )
        )

        val items = PluginMenuResolver.resolveFileTreeContextMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = File(pluginDir, "README.md"),
            isDirectory = false
        )

        assertThat(items).isEmpty()
    }

    @Test
    fun `resolveFileTreeContextMenuItems should ignore plugin command registered by another plugin`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.other",
            pluginName = "Other Plugin",
            commandId = "plugin.menu.sayHello",
            callbackName = "handleHello",
            title = "Runtime title"
        ).getOrThrow()
        val plugin = createPlugin(
            commands = listOf(
                PluginCommand(
                    id = "plugin.menu.sayHello",
                    title = "Manifest title"
                )
            ),
            menuItems = listOf(
                PluginMenuItem(command = "plugin.menu.sayHello")
            )
        )

        val items = PluginMenuResolver.resolveFileTreeContextMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = File(pluginDir, "README.md"),
            isDirectory = false
        )

        assertThat(items).isEmpty()
    }

    @Test
    fun `resolveEditorToolbarMenuItems should include host command and respect dirty condition`() {
        val plugin = InstalledPlugin(
            manifest = PluginManifest(
                id = "plugin.menu",
                name = "Plugin Menu",
                version = "1.0.0",
                type = "config",
                contributions = PluginContributions(
                    menus = PluginMenus(
                        editorToolbar = listOf(
                            PluginMenuItem(
                                command = HostCommands.EDITOR_SAVE,
                                group = "1_editor",
                                `when` = "isDirty"
                            )
                        )
                    )
                )
            ),
            directory = pluginDir,
            enabled = true
        )
        val file = File(pluginDir, "main.cpp")

        val dirtyItems = PluginMenuResolver.resolveEditorToolbarMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = file,
            isDirty = true
        )
        val cleanItems = PluginMenuResolver.resolveEditorToolbarMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = file,
            isDirty = false
        )

        assertThat(dirtyItems).hasSize(1)
        assertThat(dirtyItems.single().commandId).isEqualTo(HostCommands.EDITOR_SAVE)
        assertThat(dirtyItems.single().group).isEqualTo("1_editor")
        assertThat(cleanItems).isEmpty()
    }

    @Test
    fun `resolveEditorToolbarMenuItems should trim host command before resolving`() {
        val plugin = InstalledPlugin(
            manifest = PluginManifest(
                id = "plugin.menu",
                name = "Plugin Menu",
                version = "1.0.0",
                type = "config",
                contributions = PluginContributions(
                    menus = PluginMenus(
                        editorToolbar = listOf(
                            PluginMenuItem(command = " ${HostCommands.EDITOR_SAVE} ")
                        )
                    )
                )
            ),
            directory = pluginDir,
            enabled = true
        )

        val items = PluginMenuResolver.resolveEditorToolbarMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = File(pluginDir, "main.cpp"),
            isDirty = false
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().commandId).isEqualTo(HostCommands.EDITOR_SAVE)
    }

    @Test
    fun `resolveEditorToolbarMenuItems should preserve default group`() {
        val plugin = InstalledPlugin(
            manifest = PluginManifest(
                id = "plugin.menu",
                name = "Plugin Menu",
                version = "1.0.0",
                type = "config",
                contributions = PluginContributions(
                    menus = PluginMenus(
                        editorToolbar = listOf(
                            PluginMenuItem(command = HostCommands.EDITOR_SAVE)
                        )
                    )
                )
            ),
            directory = pluginDir,
            enabled = true
        )

        val items = PluginMenuResolver.resolveEditorToolbarMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = File(pluginDir, "main.cpp"),
            isDirty = false
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().group).isEqualTo("9_plugin")
    }

    @Test
    fun `resolveEditorToolbarMenuItems should include registered plugin command`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.menu",
            pluginName = "Plugin Menu",
            commandId = "plugin.menu.formatSelection",
            callbackName = "formatSelection",
            title = "Format Selection"
        ).getOrThrow()
        val plugin = InstalledPlugin(
            manifest = PluginManifest(
                id = "plugin.menu",
                name = "Plugin Menu",
                version = "1.0.0",
                type = "script",
                contributions = PluginContributions(
                    menus = PluginMenus(
                        editorToolbar = listOf(
                            PluginMenuItem(
                                command = "plugin.menu.formatSelection",
                                group = "2_plugin"
                            )
                        )
                    )
                )
            ),
            directory = pluginDir,
            enabled = true
        )

        val items = PluginMenuResolver.resolveEditorToolbarMenuItems(
            context = context,
            installedPlugins = listOf(plugin),
            file = File(pluginDir, "main.cpp"),
            isDirty = false
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().commandId).isEqualTo("plugin.menu.formatSelection")
        assertThat(items.single().title).isEqualTo("Format Selection")
        assertThat(items.single().pluginId).isEqualTo("plugin.menu")
    }

    @Test
    fun `resolveEditorToolbarCommands should expose plugin command metadata`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.menu",
            pluginName = "Plugin Menu",
            commandId = "plugin.menu.formatSelection",
            callbackName = "formatSelection",
            title = "Format Selection"
        ).getOrThrow()
        val plugin = InstalledPlugin(
            manifest = PluginManifest(
                id = "plugin.menu",
                name = "Plugin Menu",
                version = "1.0.0",
                type = "script",
                contributions = PluginContributions(
                    menus = PluginMenus(
                        editorToolbar = listOf(
                            PluginMenuItem(
                                command = "plugin.menu.formatSelection",
                                group = "2_plugin"
                            )
                        )
                    )
                )
            ),
            directory = pluginDir,
            enabled = true
        )

        val commands = PluginMenuResolver.resolveEditorToolbarCommands(
            context = context,
            installedPlugins = listOf(plugin),
            file = File(pluginDir, "main.cpp"),
            isDirty = false
        )

        assertThat(commands).hasSize(1)
        with(commands.single()) {
            assertThat(commandId).isEqualTo("plugin.menu.formatSelection")
            assertThat(title).isEqualTo("Format Selection")
            assertThat(group).isEqualTo("2_plugin")
            assertThat(pluginId).isEqualTo("plugin.menu")
            assertThat(pluginName).isEqualTo("Plugin Menu")
            assertThat(surface).isEqualTo(ResolvedPluginCommandSurface.EDITOR_TOOLBAR)
            assertThat(source).isEqualTo(ResolvedPluginCommandSource.PLUGIN)
        }
    }

    private fun createPlugin(
        commands: List<PluginCommand>,
        menuItems: List<PluginMenuItem>
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = "plugin.menu",
            name = "Plugin Menu",
            version = "1.0.0",
            type = "script",
            contributions = PluginContributions(
                commands = commands,
                menus = PluginMenus(
                    fileTreeContext = menuItems
                )
            )
        ),
        directory = pluginDir,
        enabled = true
    )
}
