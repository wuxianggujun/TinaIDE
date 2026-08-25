package com.wuxianggujun.tinaide.plugin

import android.view.KeyEvent
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginKeyBindingResolverTest {

    private lateinit var pluginDir: File

    @Before
    fun setUp() {
        pluginDir = Files.createTempDirectory("plugin-keybinding-resolver").toFile()
        PluginCommandRegistry.clear()
    }

    @After
    fun tearDown() {
        PluginCommandRegistry.clear()
        pluginDir.deleteRecursively()
    }

    @Test
    fun `resolve should parse array keybindings file`() {
        File(pluginDir, "keybindings.json").writeText(
            """
            [
              { "key": "Ctrl+Alt+S", "command": "${HostCommands.EDITOR_SAVE}", "when": "isDirty" }
            ]
            """.trimIndent()
        )
        val plugin = createPlugin(
            keybindingPaths = listOf("keybindings.json")
        )

        val bindings = PluginKeyBindingResolver.resolve(listOf(plugin))

        assertThat(bindings).hasSize(1)
        val binding = bindings.single()
        assertThat(binding.commandId).isEqualTo(HostCommands.EDITOR_SAVE)
        assertThat(binding.pluginId).isEqualTo("plugin.keybinding")
        assertThat(binding.matches(keyEvent(KeyEvent.KEYCODE_S, ctrl = true, alt = true), isDirty = true, editorFocus = true))
            .isTrue()
        assertThat(binding.matches(keyEvent(KeyEvent.KEYCODE_S, ctrl = true, alt = true), isDirty = false, editorFocus = true))
            .isFalse()
    }

    @Test
    fun `resolve should parse object keybindings file`() {
        File(pluginDir, "keybindings.json").writeText(
            """
            {
              "keybindings": [
                { "key": "Shift+F6", "command": "${HostCommands.EDITOR_RENAME_SYMBOL}", "when": "editorFocus == true" }
              ]
            }
            """.trimIndent()
        )
        val plugin = createPlugin(
            keybindingPaths = listOf("keybindings.json")
        )

        val bindings = PluginKeyBindingResolver.resolve(listOf(plugin))

        assertThat(bindings).hasSize(1)
        val binding = bindings.single()
        assertThat(binding.shortcut.toDisplayString()).isEqualTo("Shift + F6")
        assertThat(binding.matches(keyEvent(KeyEvent.KEYCODE_F6, shift = true), isDirty = false, editorFocus = true))
            .isTrue()
        assertThat(binding.matches(keyEvent(KeyEvent.KEYCODE_F6, shift = true), isDirty = false, editorFocus = false))
            .isFalse()
    }

    @Test
    fun `resolve should ignore invalid key and blank command`() {
        File(pluginDir, "keybindings.json").writeText(
            """
            [
              { "key": "Ctrl+NotAKey", "command": "${HostCommands.EDITOR_SAVE}" },
              { "key": "Ctrl+K", "command": " " }
            ]
            """.trimIndent()
        )
        val plugin = createPlugin(
            keybindingPaths = listOf("keybindings.json")
        )

        val bindings = PluginKeyBindingResolver.resolve(listOf(plugin))

        assertThat(bindings).isEmpty()
    }

    @Test
    fun `resolve should ignore unsafe keybinding path`() {
        val outsideFile = File(pluginDir.parentFile, "outside-keybindings-${System.nanoTime()}.json")
        outsideFile.writeText(
            """
            [
              { "key": "Ctrl+K", "command": "${HostCommands.EDITOR_SAVE}" }
            ]
            """.trimIndent()
        )
        try {
            val plugin = createPlugin(
                keybindingPaths = listOf("../${outsideFile.name}")
            )

            val bindings = PluginKeyBindingResolver.resolve(listOf(plugin))

            assertThat(bindings).isEmpty()
        } finally {
            outsideFile.delete()
        }
    }

    @Test
    fun `isCommandSupported should require plugin command to belong to declaring plugin`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.other",
            pluginName = "Other",
            commandId = "plugin.keybinding.sayHello",
            callbackName = "sayHello",
            title = "Say Hello"
        ).getOrThrow()
        val binding = ResolvedPluginKeyBinding(
            key = "Ctrl+K",
            shortcut = PluginKeyBindingResolver.parseShortcut("Ctrl+K")!!,
            commandId = "plugin.keybinding.sayHello",
            pluginId = "plugin.keybinding",
            whenExpression = null
        )

        assertThat(PluginKeyBindingResolver.isCommandSupported(binding)).isFalse()
    }

    @Test
    fun `isCommandSupported should return false after plugin commands are cleaned`() {
        val commandId = "plugin.keybinding.sayHello"
        val binding = ResolvedPluginKeyBinding(
            key = "Ctrl+K",
            shortcut = PluginKeyBindingResolver.parseShortcut("Ctrl+K")!!,
            commandId = commandId,
            pluginId = "plugin.keybinding",
            whenExpression = null
        )
        PluginCommandRegistry.register(
            pluginId = "plugin.keybinding",
            pluginName = "Plugin Keybinding",
            commandId = commandId,
            callbackName = "sayHello",
            title = "Say Hello"
        ).getOrThrow()

        assertThat(PluginKeyBindingResolver.isCommandSupported(binding)).isTrue()

        PluginCommandRegistry.unregisterAll("plugin.keybinding")

        assertThat(PluginKeyBindingResolver.isCommandSupported(binding)).isFalse()
    }

    private fun createPlugin(
        keybindingPaths: List<String>
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = "plugin.keybinding",
            name = "Plugin Keybinding",
            version = "1.0.0",
            type = PluginTypes.CONFIG,
            contributions = PluginContributions(
                keybindings = keybindingPaths
            )
        ),
        directory = pluginDir,
        enabled = true
    )

    private fun keyEvent(
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false
    ): KeyEvent {
        var metaState = 0
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON
        if (alt) metaState = metaState or KeyEvent.META_ALT_ON
        return KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
    }
}
