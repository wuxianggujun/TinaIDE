package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ConfigChangeListener
import com.wuxianggujun.tinaide.core.config.ConfigKey
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorCallback
import com.wuxianggujun.tinaide.ui.compose.state.editor.TextEditOperation
import com.wuxianggujun.tinaide.ui.compose.state.editor.CursorSnapshot
import io.mockk.mockk
import java.io.File
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
class TinaPluginEditorBridgeTest {

    private lateinit var context: Application
    private lateinit var state: EditorContainerState

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Prefs.initialize(context, InMemoryConfigManager())
        state = EditorContainerState(
            context = context,
            editorManager = mockk<IEditorManager>(relaxed = true),
            snippetManager = mockk<PluginSnippetManager>(relaxed = true),
            pluginThemeRegistry = mockk<PluginEditorThemeRegistry>(relaxed = true),
            projectSymbolIndexServiceProvider = { null },
            projectRootPathProvider = { context.cacheDir.absolutePath }
        )
    }

    @Test
    fun getActiveFileAndTabId_shouldReuseEditorContainerActiveTab() {
        val file = File(context.cacheDir, "BridgeTest.kt")
        state.syncFromManager(
            managerTabs = listOf(EditorTab(id = "tab-bridge", file = file)),
            activeTabId = "tab-bridge"
        )
        val bridge = TinaPluginEditorBridge(stateProvider = { state })

        assertThat(bridge.getActiveTabId()).isEqualTo("tab-bridge")
        assertThat(bridge.getActiveFile()).isEqualTo(file)
    }

    @Test
    fun getLanguage_shouldKeepPluginHeaderSemantics() {
        state.syncFromManager(
            managerTabs = listOf(EditorTab(id = "tab-bridge", file = File(context.cacheDir, "BridgeTest.h"))),
            activeTabId = "tab-bridge"
        )
        val bridge = TinaPluginEditorBridge(stateProvider = { state })

        assertThat(bridge.getLanguage()).isEqualTo("c")
    }

    @Test
    fun getActiveEditor_shouldExposeStableSnapshot() {
        val file = File(context.cacheDir, "BridgeTest.kt")
        state.syncFromManager(
            managerTabs = listOf(EditorTab(id = "tab-bridge", file = file)),
            activeTabId = "tab-bridge"
        )
        state.updateTabState(
            tabId = "tab-bridge",
            isDirty = true,
            canUndo = false,
            canRedo = false
        )
        state.registerCodeEditorCallback(
            tabId = "tab-bridge",
            callback = CodeEditorCallback(
                goToPosition = { _, _ -> false },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { false },
                applyTextEdits = { false },
                toggleLineComment = { false },
                replaceAll = { _, _, _, _ -> 0 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = {},
                cursorPosition = { CursorSnapshot(7, 3) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )
        val bridge = TinaPluginEditorBridge(stateProvider = { state })

        val editor = bridge.getActiveEditor()

        assertThat(editor).isNotNull()
        assertThat(editor?.tabId).isEqualTo("tab-bridge")
        assertThat(editor?.filePath).isEqualTo(file.absolutePath)
        assertThat(editor?.fileName).isEqualTo("BridgeTest.kt")
        assertThat(editor?.languageId).isEqualTo("kotlin")
        assertThat(editor?.isDirty).isTrue()
        assertThat(editor?.cursor).isEqualTo(com.wuxianggujun.tinaide.plugin.script.api.CursorPosition(7, 3))
    }

    @Test
    fun insertTextWithExplicitPosition_shouldReuseActiveTabTextEditEntry() {
        val file = File(context.cacheDir, "BridgeTest.kt")
        state.syncFromManager(
            managerTabs = listOf(EditorTab(id = "tab-bridge", file = file)),
            activeTabId = "tab-bridge"
        )

        var appliedEdit: TextEditOperation? = null
        state.registerCodeEditorCallback(
            tabId = "tab-bridge",
            callback = CodeEditorCallback(
                goToPosition = { _, _ -> false },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { false },
                applyTextEdits = { edits ->
                    appliedEdit = edits.singleOrNull()
                    true
                },
                toggleLineComment = { false },
                replaceAll = { _, _, _, _ -> 0 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = {},
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        val bridge = TinaPluginEditorBridge(stateProvider = { state })

        assertThat(bridge.insertText("hello", 3, 5)).isTrue()
        assertThat(appliedEdit).isNotNull()
        assertThat(appliedEdit?.startLine).isEqualTo(3)
        assertThat(appliedEdit?.startColumn).isEqualTo(5)
        assertThat(appliedEdit?.endLine).isEqualTo(3)
        assertThat(appliedEdit?.endColumn).isEqualTo(5)
        assertThat(appliedEdit?.newText).isEqualTo("hello")
    }

    @Test
    fun setCursorPosition_shouldReuseActiveEditableEditorNavigation() {
        val file = File(context.cacheDir, "BridgeTest.kt")
        state.syncFromManager(
            managerTabs = listOf(EditorTab(id = "tab-bridge", file = file)),
            activeTabId = "tab-bridge"
        )

        var navigatedLine = -1
        var navigatedColumn = -1
        state.registerCodeEditorCallback(
            tabId = "tab-bridge",
            callback = CodeEditorCallback(
                goToPosition = { line, column ->
                    navigatedLine = line
                    navigatedColumn = column
                    true
                },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { false },
                applyTextEdits = { false },
                toggleLineComment = { false },
                replaceAll = { _, _, _, _ -> 0 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = {},
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        val bridge = TinaPluginEditorBridge(stateProvider = { state })

        assertThat(bridge.setCursorPosition(6, 2)).isTrue()
        assertThat(navigatedLine).isEqualTo(6)
        assertThat(navigatedColumn).isEqualTo(2)
    }
}

private class InMemoryConfigManager : IConfigManager {
    private val values = LinkedHashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, default: T): T = values[key] as? T ?: default

    override fun <T> get(key: ConfigKey<T>): T = get(key.key, key.default)

    override fun <T> set(key: String, value: T) {
        values[key] = value
    }

    override fun <T> set(key: ConfigKey<T>, value: T) {
        set(key.key, value)
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() {
        values.clear()
    }

    override fun addListener(key: String, listener: ConfigChangeListener) = Unit

    override fun removeListener(key: String, listener: ConfigChangeListener) = Unit

    override fun exportConfig(): String = "{}"

    override fun importConfig(json: String) = Unit
}
