package com.wuxianggujun.tinaide.ui.compose.state.editor

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ConfigChangeListener
import com.wuxianggujun.tinaide.core.config.ConfigKey
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.DocumentSessionState
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.ui.compose.components.EditorStatus
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorToolBarState
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class EditorContainerStateTest {

    private lateinit var context: Application
    private lateinit var editorManager: IEditorManager
    private lateinit var state: EditorContainerState

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        editorManager = mockk(relaxed = true)
        Prefs.initialize(context, InMemoryConfigManager())
        state = EditorContainerState(
            context = context,
            editorManager = editorManager,
            snippetManager = mockk<PluginSnippetManager>(relaxed = true),
            pluginThemeRegistry = mockk<PluginEditorThemeRegistry>(relaxed = true),
            projectSymbolIndexServiceProvider = { null },
            projectRootPathProvider = { context.cacheDir.absolutePath }
        )
    }

    @Test
    fun snapshotSelectedCodeContext_shouldReuseSelectionSnapshot() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = {
                    EditorContainerState.SelectionSnapshot(
                        text = "selected-text",
                        startLine = 1,
                        startColumn = 2,
                        endLine = 3,
                        endColumn = 4
                    )
                }
            )
        )

        val context = state.snapshotSelectedCodeContext()

        assertThat(context).isNotNull()
        assertThat(context?.content).isEqualTo("selected-text")
        assertThat(context?.startLine).isEqualTo(2)
        assertThat(context?.endLine).isEqualTo(4)
    }

    @Test
    fun snapshotCurrentFileContext_shouldReuseActiveTabText() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "full-text" },
                readSelection = { null }
            )
        )

        val context = state.snapshotCurrentFileContext()

        assertThat(context).isNotNull()
        assertThat(context?.content).isEqualTo("full-text")
        assertThat(context?.fileName).isEqualTo("EditorContainerStateTest.kt")
    }

    @Test
    fun snapshotActiveSelectedCodeContextOrNull_shouldExposeFileAndSelection() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = {
                    EditorContainerState.SelectionSnapshot(
                        text = "selected-text",
                        startLine = 1,
                        startColumn = 2,
                        endLine = 3,
                        endColumn = 4
                    )
                }
            )
        )

        val context = state.snapshotActiveSelectedCodeContextOrNull()

        assertThat(context).isNotNull()
        assertThat(context?.file?.name).isEqualTo("EditorContainerStateTest.kt")
        assertThat(context?.content).isEqualTo("selected-text")
        assertThat(context?.startLine).isEqualTo(2)
        assertThat(context?.endLine).isEqualTo(4)
    }

    @Test
    fun snapshotActiveFileContextOrNull_shouldExposeFileAndText() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "full-text" },
                readSelection = { null }
            )
        )

        val context = state.snapshotActiveFileContextOrNull()

        assertThat(context).isNotNull()
        assertThat(context?.file?.name).isEqualTo("EditorContainerStateTest.kt")
        assertThat(context?.content).isEqualTo("full-text")
        assertThat(context?.language).isEqualTo("kt")
    }

    @Test
    fun snapshotActivePluginEditorContextOrNull_shouldExposeTabFileAndLanguage() {
        state.syncFromManager(
            managerTabs = listOf(
                EditorTab(
                    id = "tab-1",
                    file = File(context.cacheDir, "EditorContainerStateTest.h")
                )
            ),
            activeTabId = "tab-1"
        )

        val context = state.snapshotActivePluginEditorContextOrNull(cHeaderLanguageId = "c")

        assertThat(context).isNotNull()
        assertThat(context?.tabId).isEqualTo("tab-1")
        assertThat(context?.file?.name).isEqualTo("EditorContainerStateTest.h")
        assertThat(context?.languageId).isEqualTo("c")
    }

    @Test
    fun getActiveSaveTargetResult_shouldDistinguishOpenStateAndExposeTarget() {
        assertThat(state.getActiveSaveTargetResult())
            .isEqualTo(EditorContainerState.ActiveSaveTargetResult.NoOpenFile)

        setActiveTab()

        assertThat(state.getActiveSaveTargetResult())
            .isEqualTo(
                EditorContainerState.ActiveSaveTargetResult.Available(
                    EditorContainerState.ActiveSaveTarget(
                        tabId = "tab-1",
                        file = File(context.cacheDir, "EditorContainerStateTest.kt")
                    )
                )
            )
    }

    @Test
    fun insertTextAtCursor_shouldReturnFalseWithoutActiveCallback() {
        setActiveTab()

        assertThat(state.insertTextAtCursor("hello")).isFalse()
    }

    @Test
    fun insertTextAtCursor_shouldReturnTrueWhenCallbackRegistered() {
        setActiveTab()
        var insertedText: String? = null
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
                goToPosition = { _, _ -> false },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { false },
                applyTextEdits = { false },
                toggleLineComment = { false },
                replaceAll = { _, _, _, _ -> 0 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = { text -> insertedText = text },
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.insertTextAtCursor("hello")).isTrue()
        assertThat(insertedText).isEqualTo("hello")
    }

    @Test
    fun activeTabSupportsEditorPerformancePanel_shouldReturnFalseWithoutActiveCallback() {
        setActiveTab()

        assertThat(state.activeTabSupportsEditorPerformancePanel()).isFalse()
    }

    @Test
    fun activeTabSupportsEditorPerformancePanel_shouldReturnTrueWhenCallbackRegistered() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.activeTabSupportsEditorPerformancePanel()).isTrue()
    }

    @Test
    fun getActiveEditorToolBarState_shouldExposeUnifiedActiveTabSnapshot() {
        assertThat(state.getActiveEditorToolBarState()).isEqualTo(
            EditorToolBarState(
                hasFiles = false,
                canUndo = false,
                canRedo = false,
                isDirty = false
            )
        )

        setActiveTab()
        state.updateTabState(
            tabId = "tab-1",
            isDirty = true,
            canUndo = true,
            canRedo = false
        )

        assertThat(state.getActiveEditorToolBarState()).isEqualTo(
            EditorToolBarState(
                hasFiles = true,
                canUndo = true,
                canRedo = false,
                isDirty = true
            )
        )
    }

    @Test
    fun getTabToolbarStateFlow_shouldExposeMappedToolbarState() = runTest {
        val file = File(context.cacheDir, "EditorContainerStateTest.kt")
        every { editorManager.getSessionState("tab-1") } returns MutableStateFlow(
            DocumentSessionState(
                tabId = "tab-1",
                file = file,
                title = file.name,
                isDirty = true,
                canUndo = true,
                canRedo = false,
                charsetName = "GBK"
            )
        )

        val toolbarState = state.getTabToolbarStateFlow("tab-1")?.first()

        assertThat(toolbarState).isEqualTo(
            EditorContainerState.TabToolbarState(
                isDirty = true,
                canUndo = true,
                canRedo = false,
                charsetName = "GBK"
            )
        )
    }

    @Test
    fun getTabLastEditAtFlow_shouldExposeLastEditAt() = runTest {
        val file = File(context.cacheDir, "EditorContainerStateTest.kt")
        every { editorManager.getSessionState("tab-1") } returns MutableStateFlow(
            DocumentSessionState(
                tabId = "tab-1",
                file = file,
                title = file.name,
                lastEditAt = 123L
            )
        )

        val lastEditAt = state.getTabLastEditAtFlow("tab-1")?.first()

        assertThat(lastEditAt).isEqualTo(123L)
    }

    @Test
    fun getActiveEditorSessionAlertFlow_shouldIncludeHandleAndTrimErrorMessage() = runTest {
        setActiveTab()
        val file = File(context.cacheDir, "EditorContainerStateTest.kt")
        every { editorManager.getSessionState("tab-1") } returns MutableStateFlow(
            DocumentSessionState(
                tabId = "tab-1",
                file = file,
                title = file.name,
                hasExternalModification = true,
                lastError = "  write failed  "
            )
        )

        val alertState = state.getActiveEditorSessionAlertFlow()?.first()

        assertThat(alertState).isEqualTo(
            EditorContainerState.ActiveEditorSessionAlertState(
                tabId = "tab-1",
                file = file,
                hasExternalModification = true,
                lastError = "write failed"
            )
        )
    }

    @Test
    fun activeTabSemanticResults_shouldExposeOutlineAndSymbolsTargets() {
        assertThat(state.getActiveDocumentSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveDocumentSymbolsTargetResult.NoOpenFile)
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveWorkspaceSymbolsTargetResult.NoOpenFile)

        setActiveTab()
        assertThat(state.getActiveDocumentSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveDocumentSymbolsTargetResult.Unavailable)
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveWorkspaceSymbolsTargetResult.Unavailable)
        setLspStatus(tabId = "tab-1", status = EditorStatus.Ready)

        assertThat(state.getActiveDocumentSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveDocumentSymbolsTargetResult.Available("tab-1"))
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveWorkspaceSymbolsTargetResult.Available("tab-1"))

        setLspStatus(tabId = "tab-1", status = EditorStatus.Busy)
        assertThat(state.getActiveDocumentSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveDocumentSymbolsTargetResult.Available("tab-1"))
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(EditorContainerState.ActiveWorkspaceSymbolsTargetResult.Available("tab-1"))
    }

    @Test
    fun snapshotActivePluginEditorContextOrNull_shouldReflectCurrentActiveTab() {
        setActiveTab()
        val expectedFile = File(context.cacheDir, "EditorContainerStateTest.kt")

        val context = state.snapshotActivePluginEditorContextOrNull()

        assertThat(context).isNotNull()
        assertThat(context?.tabId).isEqualTo("tab-1")
        assertThat(context?.file).isEqualTo(expectedFile)
    }

    @Test
    fun activeEditableEditorAvailability_shouldDistinguishEditableAndReadonlyTabs() {
        assertThat(state.getActiveEditableEditorCommandAvailability())
            .isEqualTo(EditorContainerState.ActiveEditorCommandResult.NO_OPEN_FILE)
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(EditorContainerState.ActiveEditableEditorSnapshotResult.NoOpenFile)

        state.openFileWithType(
            file = File(context.cacheDir, "Preview.png"),
            contentType = ContentType.IMAGE
        )

        assertThat(state.getActiveEditableEditorCommandAvailability())
            .isEqualTo(EditorContainerState.ActiveEditorCommandResult.UNSUPPORTED_EDITOR)
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(EditorContainerState.ActiveEditableEditorSnapshotResult.UnsupportedEditor)

        setActiveTab()
        state.selectTab(0)
        assertThat(state.getActiveEditableEditorCommandAvailability())
            .isEqualTo(EditorContainerState.ActiveEditorCommandResult.UNSUPPORTED_EDITOR)
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.getActiveEditableEditorCommandAvailability())
            .isEqualTo(EditorContainerState.ActiveEditorCommandResult.SUCCESS)
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(
                EditorContainerState.ActiveEditableEditorSnapshotResult.Success(
                    EditorContainerState.ActiveEditableEditorSnapshot(
                        file = File(context.cacheDir, "EditorContainerStateTest.kt"),
                        text = ""
                    )
                )
            )
    }

    @Test
    fun activeBookmarkResults_shouldDistinguishOpenStateAndUnsupportedEditor() {
        assertThat(state.getActiveBookmarkCursorContextResult())
            .isEqualTo(EditorContainerState.ActiveBookmarkCursorContextResult.NoOpenFile)
        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(EditorContainerState.ActiveBookmarkTargetResult.NoOpenFile)

        state.openFileWithType(
            file = File(context.cacheDir, "Preview.png"),
            contentType = ContentType.IMAGE
        )

        assertThat(state.getActiveBookmarkCursorContextResult())
            .isEqualTo(EditorContainerState.ActiveBookmarkCursorContextResult.UnsupportedEditor)
        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(EditorContainerState.ActiveBookmarkTargetResult.UnsupportedEditor)
    }

    @Test
    fun activeBookmarkTargetResult_shouldResolveMarkerLineFromActiveCursor() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(1, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "fun demo() {\n\n}\nvalue = 1" },
                readSelection = { null }
            )
        )

        assertThat(state.getActiveBookmarkCursorContextResult())
            .isEqualTo(
                EditorContainerState.ActiveBookmarkCursorContextResult.Success(
                    EditorContainerState.ActiveBookmarkCursorContext(
                        file = File(context.cacheDir, "EditorContainerStateTest.kt"),
                        line = 1
                    )
                )
            )
        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(
                EditorContainerState.ActiveBookmarkTargetResult.Success(
                    EditorContainerState.ActiveBookmarkTarget(
                        file = File(context.cacheDir, "EditorContainerStateTest.kt"),
                        line = 0
                    )
                )
            )

        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(1, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "{\n\n}" },
                readSelection = { null }
            )
        )

        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(EditorContainerState.ActiveBookmarkTargetResult.NoBookmarkableLine)
    }

    @Test
    fun goToPositionInActiveEditableEditor_shouldRequireEditableActiveEditor() {
        setActiveTab()

        assertThat(state.goToPositionInActiveEditableEditor(3, 4)).isFalse()

        var navigatedLine = -1
        var navigatedColumn = -1
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.goToPositionInActiveEditableEditor(3, 4)).isTrue()
        assertThat(navigatedLine).isEqualTo(3)
        assertThat(navigatedColumn).isEqualTo(4)
    }

    @Test
    fun requestGoToPositionInActiveEditableEditor_shouldExposeDialogFriendlyResults() {
        assertThat(state.requestGoToPositionInActiveEditableEditor(2, 0))
            .isEqualTo(EditorContainerState.ActiveEditorCommandResult.NO_OPEN_FILE)

        setActiveTab()
        assertThat(state.requestGoToPositionInActiveEditableEditor(2, 0))
            .isEqualTo(EditorContainerState.ActiveEditorCommandResult.UNSUPPORTED_EDITOR)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
                goToPosition = { _, _ -> true },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { false },
                applyTextEdits = { false },
                toggleLineComment = { false },
                replaceAll = { _, _, _, _ -> 0 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = {},
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.requestGoToPositionInActiveEditableEditor(2, 0))
            .isEqualTo(EditorContainerState.ActiveEditorCommandResult.SUCCESS)
    }

    @Test
    fun requestReplaceAllInActiveEditor_shouldExposeCapabilityAndMatchResults() {
        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(EditorContainerState.ReplaceAllInActiveEditorResult.NoOpenFile)

        setActiveTab()
        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(EditorContainerState.ReplaceAllInActiveEditorResult.UnsupportedEditor)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(EditorContainerState.ReplaceAllInActiveEditorResult.NoMatches)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
                goToPosition = { _, _ -> false },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { false },
                applyTextEdits = { false },
                toggleLineComment = { false },
                replaceAll = { _, _, _, _ -> 3 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = {},
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(EditorContainerState.ReplaceAllInActiveEditorResult.Success(3))
    }

    @Test
    fun requestToggleLineCommentInActiveEditor_shouldExposeCapabilityAndResolvedToken() {
        assertThat(
            state.requestToggleLineCommentInActiveEditor { "//" }
        ).isEqualTo(EditorContainerState.ActiveEditorCommandResult.NO_OPEN_FILE)

        setActiveTab()
        assertThat(
            state.requestToggleLineCommentInActiveEditor { "//" }
        ).isEqualTo(EditorContainerState.ActiveEditorCommandResult.UNSUPPORTED_EDITOR)

        var resolvedToken: String? = null
        var resolvedFileName: String? = null
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
                goToPosition = { _, _ -> false },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { false },
                applyTextEdits = { false },
                toggleLineComment = { token ->
                    resolvedToken = token
                    true
                },
                replaceAll = { _, _, _, _ -> 0 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = {},
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(
            state.requestToggleLineCommentInActiveEditor { file ->
                resolvedFileName = file.name
                "//"
            }
        ).isEqualTo(EditorContainerState.ActiveEditorCommandResult.SUCCESS)
        assertThat(resolvedToken).isEqualTo("//")
        assertThat(resolvedFileName).isEqualTo("EditorContainerStateTest.kt")
    }

    @Test
    fun snapshotActiveEditableEditorContent_shouldExposeCapabilityAndText() {
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(EditorContainerState.ActiveEditableEditorSnapshotResult.NoOpenFile)

        setActiveTab()
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(EditorContainerState.ActiveEditableEditorSnapshotResult.UnsupportedEditor)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "fun test() = Unit" },
                readSelection = { null }
            )
        )

        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(
                EditorContainerState.ActiveEditableEditorSnapshotResult.Success(
                    snapshot = EditorContainerState.ActiveEditableEditorSnapshot(
                        file = File(context.cacheDir, "EditorContainerStateTest.kt"),
                        text = "fun test() = Unit"
                    )
                )
            )
    }

    @Test
    fun openTabFileHelpers_shouldReadWriteTargetTabWithoutChangingActiveSelection() {
        val firstFile = File(context.cacheDir, "First.kt")
        val secondFile = File(context.cacheDir, "Second.kt")
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = firstFile),
                EditorTab(id = "tab-2", file = secondFile)
            ),
            activeTabId = "tab-1"
        )

        var replacedText: String? = null
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "first-text" },
                readSelection = { null }
            )
        )
        state.registerCodeEditorCallback(
            tabId = "tab-2",
            callback = EditorContainerState.CodeEditorCallback(
                goToPosition = { _, _ -> false },
                selectAll = { false },
                replaceSelection = { false },
                replaceWholeText = { newText ->
                    replacedText = newText
                    true
                },
                applyTextEdits = { false },
                toggleLineComment = { false },
                replaceAll = { _, _, _, _ -> 0 },
                undo = { false },
                redo = { false },
                insertTextAtCursor = {},
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "second-text" },
                readSelection = { null }
            )
        )

        assertThat(state.findOpenTabIdByFileOrNull(secondFile)).isEqualTo("tab-2")
        assertThat(state.readTextFromOpenTabIfPresent(secondFile)).isEqualTo("second-text")
        assertThat(state.updateOpenTabTextIfPresent(secondFile, "updated-text")).isTrue()
        assertThat(replacedText).isEqualTo("updated-text")
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")
    }

    @Test
    fun requestCloseTabForFile_shouldCloseMatchedTabWithoutCallerTrackingIndex() {
        val firstFile = File(context.cacheDir, "First.kt")
        val secondFile = File(context.cacheDir, "Second.kt")
        val thirdFile = File(context.cacheDir, "Third.kt")
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = firstFile),
                EditorTab(id = "tab-2", file = secondFile),
                EditorTab(id = "tab-3", file = thirdFile)
            ),
            activeTabId = "tab-2"
        )

        assertThat(state.requestCloseTabForFile(firstFile)).isTrue()
        assertThat(state.tabs.map { it.id }).containsExactly("tab-2", "tab-3")
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-2")
    }

    @Test
    fun openFileAndGoToPosition_shouldNotReuseCurrentActiveTabWhenTargetCannotOpen() {
        val activeFile = File(context.cacheDir, "ActiveEditor.kt").apply {
            writeText("fun active() = Unit")
        }
        setTabs(
            managerTabs = listOf(EditorTab(id = "tab-1", file = activeFile)),
            activeTabId = "tab-1"
        )

        var navigateCalls = 0
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
                goToPosition = { _, _ ->
                    navigateCalls++
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.openFileAndGoToPosition(context.cacheDir, line = 8, column = 1)).isFalse()
        assertThat(navigateCalls).isEqualTo(0)
    }

    @Test
    fun openFileAndGoToPosition_shouldNavigateTargetFileThroughUnifiedStateEntry() {
        val file = File(context.cacheDir, "JumpTarget.kt").apply {
            writeText("fun jump() = Unit")
        }
        val editorTab = EditorTab(id = "tab-1", file = file)
        every { editorManager.openFile(file) } returns editorTab
        every { editorManager.getOpenTabs() } returns listOf(editorTab)
        every { editorManager.getActiveTabId() } returns "tab-1"

        setTabs(
            managerTabs = listOf(editorTab),
            activeTabId = "tab-1"
        )

        var navigatedLine = -1
        var navigatedColumn = -1
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.openFileAndGoToPosition(file, line = 4, column = 2)).isTrue()
        assertThat(navigatedLine).isEqualTo(4)
        assertThat(navigatedColumn).isEqualTo(2)
    }

    @Test
    fun requestCloseActiveTab_shouldCloseCurrentSelection() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )

        assertThat(state.requestCloseActiveTab()).isTrue()
        assertThat(state.tabs.map { it.id }).containsExactly("tab-1")
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")
    }

    @Test
    fun confirmSaveAndClose_shouldClosePendingDirtyTabWithoutCallerResolvingIndex() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.updateTabState(tabId = "tab-2", isDirty = true, canUndo = false, canRedo = false)

        state.requestCloseTab(1)

        assertThat(state.pendingCloseTab?.id).isEqualTo("tab-2")
        assertThat(state.confirmSaveAndClose()).isTrue()
        assertThat(state.pendingCloseTab).isNull()
        assertThat(state.tabs.map { it.id }).containsExactly("tab-1")
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")
    }

    @Test
    fun confirmSaveAndClose_shouldNormalizePaneStateAfterClosingDirtySecondaryTab() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.moveActiveTabToSecondaryPane()
        state.updateTabState(tabId = "tab-2", isDirty = true, canUndo = false, canRedo = false)

        state.requestCloseActiveTab()

        assertThat(state.pendingCloseTab?.id).isEqualTo("tab-2")
        assertThat(state.confirmSaveAndClose()).isTrue()
        assertThat(state.pendingCloseTab).isNull()
        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY)).isEmpty()
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")
    }

    @Test
    fun resolveSuccessfulSaveAllNotificationTargets_shouldKeepOnlySuccessfulDirtyTabs() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt")),
                EditorTab(id = "tab-3", file = File(context.cacheDir, "Third.kt"))
            ),
            activeTabId = "tab-1"
        )
        state.updateTabState(tabId = "tab-1", isDirty = true, canUndo = false, canRedo = false)
        state.updateTabState(tabId = "tab-2", isDirty = false, canUndo = false, canRedo = false)
        state.updateTabState(tabId = "tab-3", isDirty = true, canUndo = false, canRedo = false)

        state.rememberDirtyTabsForSaveAllNotification()

        val successfulTargets = state.resolveSuccessfulSaveAllNotificationTargets(
            listOf(
                SaveResult.Success(timestamp = 1L, reason = SaveReason.MANUAL),
                SaveResult.Failure(message = "save failed")
            )
        )

        assertThat(successfulTargets.map { it.tabId }).containsExactly("tab-1")
        assertThat(successfulTargets.map { it.file.name }).containsExactly("First.kt")
        assertThat(
            state.resolveSuccessfulSaveAllNotificationTargets(
                listOf(SaveResult.Success(timestamp = 2L, reason = SaveReason.MANUAL))
            )
        ).isEmpty()
    }

    @Test
    fun selectNextAndPreviousTab_shouldWrapWithoutCallerTrackingIndices() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt")),
                EditorTab(id = "tab-3", file = File(context.cacheDir, "Third.kt"))
            ),
            activeTabId = "tab-2"
        )

        assertThat(state.selectNextTab()).isTrue()
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-3")

        assertThat(state.selectNextTab()).isTrue()
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")

        assertThat(state.selectPreviousTab()).isTrue()
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-3")
    }

    @Test
    fun closeOtherTabsForActiveTab_shouldKeepOnlyCurrentSelection() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt")),
                EditorTab(id = "tab-3", file = File(context.cacheDir, "Third.kt"))
            ),
            activeTabId = "tab-2"
        )

        assertThat(state.closeOtherTabsForActiveTab()).isTrue()
        assertThat(state.tabs.map { it.id }).containsExactly("tab-2")
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.file?.name).isEqualTo("Second.kt")
    }

    @Test
    fun toggleSplitEditor_shouldCollapseTabsBackToPrimaryPane() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )

        state.toggleSplitEditor()

        assertThat(state.isSplitEditorEnabled).isTrue()
        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY)).isEmpty()

        state.toggleSplitEditor()

        assertThat(state.isSplitEditorEnabled).isFalse()
        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY)).isEmpty()
    }

    @Test
    fun toggleSplitEditor_shouldCollapseSecondaryTabsBackToPrimaryPane() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.moveActiveTabToSecondaryPane()

        state.toggleSplitEditor()

        assertThat(state.isSplitEditorEnabled).isFalse()
        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY)).isEmpty()
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-2")
    }

    @Test
    fun moveActiveTabToSecondaryPane_shouldEnableSplitAndPreservePrimaryTabs() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )

        assertThat(state.moveActiveTabToSecondaryPane()).isTrue()

        assertThat(state.isSplitEditorEnabled).isTrue()
        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.SECONDARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("tab-2")
        assertThat(state.getActiveIndexForPane(EditorContainerState.EditorPaneId.SECONDARY)).isEqualTo(1)
        assertThat(state.canMoveActiveTabToSecondaryPane()).isFalse()
    }

    @Test
    fun selectTabInPane_shouldMakePaneActiveForToolbarAndPluginContext() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.moveActiveTabToSecondaryPane()
        state.selectTabInPane(EditorContainerState.EditorPaneId.PRIMARY, 0)

        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.PRIMARY)
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")

        state.selectTabInPane(EditorContainerState.EditorPaneId.SECONDARY, 1)

        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.SECONDARY)
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-2")
    }

    @Test
    fun selectTabInPane_shouldNotMoveTabFromAnotherPane() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.moveActiveTabToSecondaryPane()

        state.selectTabInPane(EditorContainerState.EditorPaneId.PRIMARY, 1)

        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.SECONDARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("tab-2")
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-2")
    }

    @Test
    fun requestCloseActiveTab_shouldMoveFocusWhenSecondaryPaneBecomesEmpty() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.moveActiveTabToSecondaryPane()

        assertThat(state.requestCloseActiveTab()).isTrue()

        assertThat(state.isSplitEditorEnabled).isTrue()
        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY)).isEmpty()
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")
    }

    @Test
    fun openFileWithType_shouldAssignNewTabToFocusedPane() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.moveActiveTabToSecondaryPane()

        val primaryPreview = File(context.cacheDir, "PrimaryPreview.png")
        state.focusEditorPane(EditorContainerState.EditorPaneId.PRIMARY)
        state.openFileWithType(primaryPreview, ContentType.IMAGE)

        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.PRIMARY).map { it.file.name })
            .containsExactly("First.kt", "PrimaryPreview.png")
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("tab-2")

        val secondaryPreview = File(context.cacheDir, "SecondaryPreview.png")
        state.focusEditorPane(EditorContainerState.EditorPaneId.SECONDARY)
        state.openFileWithType(secondaryPreview, ContentType.IMAGE)

        assertThat(state.focusedPane).isEqualTo(EditorContainerState.EditorPaneId.SECONDARY)
        assertThat(state.getTabsForPane(EditorContainerState.EditorPaneId.SECONDARY).map { it.file.name })
            .containsExactly("Second.kt", "SecondaryPreview.png")
    }

    @Test
    fun activeTabEditStateAccessors_shouldReflectUpdatedTabState() {
        setActiveTab()

        state.updateTabState(tabId = "tab-1", isDirty = true, canUndo = true, canRedo = false)

        assertThat(state.isActiveTabDirty()).isTrue()
        assertThat(state.canUndoInActiveTab()).isTrue()
        assertThat(state.canRedoInActiveTab()).isFalse()
    }

    @Test
    fun getBottomPanelEditorStatus_shouldPreferDebugBusyOverLspState() {
        setActiveTab()
        setLspStatus(tabId = "tab-1", status = EditorStatus.Ready)

        assertThat(state.getBottomPanelEditorStatus(isDebugSessionActive = true))
            .isEqualTo(EditorStatus.Busy)
        assertThat(state.getBottomPanelEditorStatus(isDebugSessionActive = false))
            .isEqualTo(EditorStatus.Ready)
    }

    @Test
    fun getEditorProjectRootPathOrNull_shouldReuseResolvedProjectRoot() {
        assertThat(state.getEditorProjectRootPathOrNull())
            .isEqualTo(context.cacheDir.absolutePath)
    }

    @Test
    fun getBookmarksProjectRootPathOrNull_shouldReuseResolvedProjectRoot() {
        assertThat(state.getBookmarksProjectRootPathOrNull())
            .isEqualTo(context.cacheDir.absolutePath)
    }

    @Test
    fun resolveMarkerLineFromSnapshot_shouldSkipBlankAndBraceOnlyLines() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "fun demo() {\n\n}\nvalue = 1" },
                readSelection = { null }
            )
        )

        assertThat(state.resolveMarkerLineFromSnapshot(1)).isEqualTo(0)
        assertThat(state.resolveMarkerLineFromSnapshot(2)).isEqualTo(0)
    }

    @Test
    fun resolveMarkerLineFromSnapshot_shouldScanForwardWhenPreviousLinesAreMarkers() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = EditorContainerState.CodeEditorCallback(
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
                cursorPosition = { EditorContainerState.CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "{\n\nvalue = 1" },
                readSelection = { null }
            )
        )

        assertThat(state.resolveMarkerLineFromSnapshot(0)).isEqualTo(2)
        assertThat(state.resolveMarkerLineFromSnapshot(1)).isEqualTo(2)
    }

    private fun setActiveTab() {
        setTabs(
            managerTabs = listOf(
                EditorTab(
                    id = "tab-1",
                    file = File(context.cacheDir, "EditorContainerStateTest.kt")
                )
            ),
            activeTabId = "tab-1"
        )
    }

    private fun setTabs(managerTabs: List<EditorTab>, activeTabId: String?) {
        state.syncFromManager(
            managerTabs = managerTabs,
            activeTabId = activeTabId
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun setLspStatus(tabId: String, status: EditorStatus) {
        val field = EditorContainerState::class.java.getDeclaredField("lspStatusesByTabId")
        field.isAccessible = true
        val statuses = field.get(state) as MutableMap<String, EditorStatus>
        statuses[tabId] = status
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
