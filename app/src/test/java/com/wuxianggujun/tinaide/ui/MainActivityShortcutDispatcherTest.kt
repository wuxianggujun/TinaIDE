package com.wuxianggujun.tinaide.ui

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.config.KeyboardShortcut
import com.wuxianggujun.tinaide.core.config.KeyboardShortcutManager
import com.wuxianggujun.tinaide.core.config.ShortcutAction
import com.wuxianggujun.tinaide.plugin.ResolvedPluginKeyBinding
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import java.io.File
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
class MainActivityShortcutDispatcherTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("shortcut-dispatcher-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        KeyboardShortcutManager.initialize(prefs)
        PluginCommandRegistry.clear()
    }

    @After
    fun tearDown() {
        PluginCommandRegistry.clear()
    }

    @Test
    fun `dispatch should open command palette for matching shortcut`() {
        val dispatcher = MainActivityShortcutDispatcher()
        val calls = mutableListOf<Pair<String, HostCommandInvocation>>()
        dispatcher.bind(
            hostCommandExecutor = object : HostCommandExecutor {
                override fun execute(commandId: String, invocation: HostCommandInvocation): Boolean {
                    calls += commandId to invocation
                    return true
                }
            },
            invocationProvider = { HostCommandInvocation(isDirty = true) }
        )

        val handled = dispatcher.dispatch(
            keyEvent(
                keyCode = KeyEvent.KEYCODE_P,
                ctrl = true,
                shift = true
            )
        )

        assertThat(handled).isTrue()
        assertThat(calls.single().first).isEqualTo(HostCommands.VIEW_COMMAND_PALETTE)
        assertThat(calls.single().second.isDirty).isTrue()
        assertThat(
            KeyboardShortcutManager.getDefaultShortcut(ShortcutAction.COMMAND_PALETTE).toDisplayString()
        ).isEqualTo("Ctrl + Shift + P")
    }

    @Test
    fun `dispatch should execute matching plugin keybinding when built-in shortcut does not match`() {
        val target = File(context.cacheDir, "main.cpp")
        val calls = mutableListOf<Pair<String, HostCommandInvocation>>()
        val dispatcher = MainActivityShortcutDispatcher()
        dispatcher.bindPluginKeyBindings(
            keyBindingsProvider = {
                listOf(
                    ResolvedPluginKeyBinding(
                        key = "Ctrl+K",
                        shortcut = ctrlKShortcut(),
                        commandId = HostCommands.VIEW_TOGGLE_FILE_TREE,
                        pluginId = "plugin.keybinding",
                        whenExpression = "editorFocus == true"
                    )
                )
            },
            invocationProvider = {
                HostCommandInvocation(
                    file = target,
                    isDirectory = false,
                    isDirty = false
                )
            },
            editorFocusProvider = { true },
            hostCommandExecutor = object : HostCommandExecutor {
                override fun execute(commandId: String, invocation: HostCommandInvocation): Boolean {
                    calls += commandId to invocation
                    return true
                }
            }
        )

        val handled = dispatcher.dispatch(keyEvent(KeyEvent.KEYCODE_K, ctrl = true))

        assertThat(handled).isTrue()
        assertThat(calls).hasSize(1)
        assertThat(calls.single().first).isEqualTo(HostCommands.VIEW_TOGGLE_FILE_TREE)
        assertThat(calls.single().second.file).isEqualTo(target)
    }

    @Test
    fun `dispatch should ignore plugin keybinding when when expression does not match`() {
        val dispatcher = MainActivityShortcutDispatcher()
        var executed = false
        dispatcher.bindPluginKeyBindings(
            keyBindingsProvider = {
                listOf(
                    ResolvedPluginKeyBinding(
                        key = "Ctrl+K",
                        shortcut = ctrlKShortcut(),
                        commandId = HostCommands.VIEW_TOGGLE_FILE_TREE,
                        pluginId = "plugin.keybinding",
                        whenExpression = "editorFocus == true"
                    )
                )
            },
            invocationProvider = { HostCommandInvocation(isDirty = false) },
            editorFocusProvider = { false },
            hostCommandExecutor = object : HostCommandExecutor {
                override fun execute(commandId: String, invocation: HostCommandInvocation): Boolean {
                    executed = true
                    return true
                }
            }
        )

        val handled = dispatcher.dispatch(keyEvent(KeyEvent.KEYCODE_K, ctrl = true))

        assertThat(handled).isFalse()
        assertThat(executed).isFalse()
    }

    @Test
    fun `dispatch should ignore cached plugin keybinding after command is cleaned`() {
        val dispatcher = MainActivityShortcutDispatcher()
        var executed = false
        dispatcher.bindPluginKeyBindings(
            keyBindingsProvider = {
                listOf(
                    ResolvedPluginKeyBinding(
                        key = "Ctrl+K",
                        shortcut = ctrlKShortcut(),
                        commandId = "plugin.keybinding.sayHello",
                        pluginId = "plugin.keybinding",
                        whenExpression = "editorFocus == true"
                    )
                )
            },
            invocationProvider = { HostCommandInvocation(isDirty = false) },
            editorFocusProvider = { true },
            hostCommandExecutor = object : HostCommandExecutor {
                override fun execute(commandId: String, invocation: HostCommandInvocation): Boolean {
                    executed = true
                    return true
                }
            }
        )

        val handled = dispatcher.dispatch(keyEvent(KeyEvent.KEYCODE_K, ctrl = true))

        assertThat(handled).isFalse()
        assertThat(executed).isFalse()
    }

    private fun ctrlKShortcut(): KeyboardShortcut = KeyboardShortcut(
        keyCode = KeyEvent.KEYCODE_K,
        ctrl = true
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
