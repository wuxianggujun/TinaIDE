package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorCallback
import com.wuxianggujun.tinaide.ui.compose.state.editor.CursorSnapshot

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
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
import com.wuxianggujun.tinaide.editor.session.SaveTarget
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.ui.compose.components.editor.ContentType
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorToolBarState
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class EditorContainerStateTest {

    private lateinit var context: Application
    private lateinit var editorManager: IEditorManager
    private lateinit var state: EditorContainerState

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tinaide_editor_split_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        editorManager = mockk(relaxed = true)
        Prefs.initialize(context, InMemoryConfigManager())
        state = newEditorContainerState()
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
            .isEqualTo(ActiveSaveTargetResult.NoOpenFile)

        setActiveTab()

        assertThat(state.getActiveSaveTargetResult())
            .isEqualTo(
                ActiveSaveTargetResult.Available(
                    ActiveSaveTarget(
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
                insertTextAtCursor = { text -> insertedText = text },
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.insertTextAtCursor("hello")).isTrue()
        assertThat(insertedText).isEqualTo("hello")
    }

    @Test
    fun activeTabHasAttachedCodeEditor_shouldReturnFalseWithoutActiveCallback() {
        setActiveTab()

        assertThat(state.activeTabHasAttachedCodeEditor()).isFalse()
    }

    @Test
    fun activeTabHasAttachedCodeEditor_shouldReturnTrueWhenCallbackRegistered() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
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
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.activeTabHasAttachedCodeEditor()).isTrue()
    }

    @Test
    fun activeTabHasAttachedCodeEditor_registrationShouldInvalidateSnapshotRead() {
        setActiveTab()
        val observer = SnapshotStateObserver { callback -> callback() }
        var invalidationCount = 0
        observer.start()
        try {
            observer.observeReads(
                scope = Unit,
                onValueChangedForScope = { invalidationCount++ }
            ) {
                state.activeTabHasAttachedCodeEditor()
            }

            state.registerCodeEditorCallback(
                tabId = "tab-1",
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
                    cursorPosition = { CursorSnapshot(0, 0) },
                    setSelectionRange = { _, _, _, _ -> false },
                    readAllText = { "" },
                    readSelection = { null }
                )
            )
            Snapshot.sendApplyNotifications()

            assertThat(invalidationCount).isEqualTo(1)
        } finally {
            observer.stop()
        }
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
            TabToolbarState(
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
            ActiveEditorSessionAlertState(
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
            .isEqualTo(ActiveDocumentSymbolsTargetResult.NoOpenFile)
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(ActiveWorkspaceSymbolsTargetResult.NoOpenFile)

        setActiveTab()
        assertThat(state.getActiveDocumentSymbolsTargetResult())
            .isEqualTo(ActiveDocumentSymbolsTargetResult.Unavailable)
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(ActiveWorkspaceSymbolsTargetResult.Unavailable)
        setLspStatus(tabId = "tab-1", status = EditorStatus.Ready)

        assertThat(state.getActiveDocumentSymbolsTargetResult())
            .isEqualTo(ActiveDocumentSymbolsTargetResult.Available("tab-1"))
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(ActiveWorkspaceSymbolsTargetResult.Available("tab-1"))

        setLspStatus(tabId = "tab-1", status = EditorStatus.Busy)
        assertThat(state.getActiveDocumentSymbolsTargetResult())
            .isEqualTo(ActiveDocumentSymbolsTargetResult.Available("tab-1"))
        assertThat(state.getActiveWorkspaceSymbolsTargetResult())
            .isEqualTo(ActiveWorkspaceSymbolsTargetResult.Available("tab-1"))
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
            .isEqualTo(ActiveEditorCommandResult.NO_OPEN_FILE)
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(ActiveEditableEditorSnapshotResult.NoOpenFile)

        state.openFileWithType(
            file = File(context.cacheDir, "Preview.png"),
            contentType = ContentType.IMAGE
        )

        assertThat(state.getActiveEditableEditorCommandAvailability())
            .isEqualTo(ActiveEditorCommandResult.UNSUPPORTED_EDITOR)
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(ActiveEditableEditorSnapshotResult.UnsupportedEditor)

        setActiveTab()
        state.selectTab(0)
        assertThat(state.getActiveEditableEditorCommandAvailability())
            .isEqualTo(ActiveEditorCommandResult.UNSUPPORTED_EDITOR)
        state.registerCodeEditorCallback(
            tabId = "tab-1",
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
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.getActiveEditableEditorCommandAvailability())
            .isEqualTo(ActiveEditorCommandResult.SUCCESS)
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(
                ActiveEditableEditorSnapshotResult.Success(
                    ActiveEditableEditorSnapshot(
                        file = File(context.cacheDir, "EditorContainerStateTest.kt"),
                        text = ""
                    )
                )
            )
    }

    @Test
    fun activeBookmarkResults_shouldDistinguishOpenStateAndUnsupportedEditor() {
        assertThat(state.getActiveBookmarkCursorContextResult())
            .isEqualTo(ActiveBookmarkCursorContextResult.NoOpenFile)
        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(ActiveBookmarkTargetResult.NoOpenFile)

        state.openFileWithType(
            file = File(context.cacheDir, "Preview.png"),
            contentType = ContentType.IMAGE
        )

        assertThat(state.getActiveBookmarkCursorContextResult())
            .isEqualTo(ActiveBookmarkCursorContextResult.UnsupportedEditor)
        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(ActiveBookmarkTargetResult.UnsupportedEditor)
    }

    @Test
    fun activeBookmarkTargetResult_shouldResolveMarkerLineFromActiveCursor() {
        setActiveTab()
        state.registerCodeEditorCallback(
            tabId = "tab-1",
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
                cursorPosition = { CursorSnapshot(1, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "fun demo() {\n\n}\nvalue = 1" },
                readSelection = { null }
            )
        )

        assertThat(state.getActiveBookmarkCursorContextResult())
            .isEqualTo(
                ActiveBookmarkCursorContextResult.Success(
                    ActiveBookmarkCursorContext(
                        file = File(context.cacheDir, "EditorContainerStateTest.kt"),
                        line = 1
                    )
                )
            )
        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(
                ActiveBookmarkTargetResult.Success(
                    ActiveBookmarkTarget(
                        file = File(context.cacheDir, "EditorContainerStateTest.kt"),
                        line = 0
                    )
                )
            )

        state.registerCodeEditorCallback(
            tabId = "tab-1",
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
                cursorPosition = { CursorSnapshot(1, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "{\n\n}" },
                readSelection = { null }
            )
        )

        assertThat(state.getActiveBookmarkTargetResult())
            .isEqualTo(ActiveBookmarkTargetResult.NoBookmarkableLine)
    }

    @Test
    fun goToPositionInActiveEditableEditor_shouldRequireEditableActiveEditor() {
        setActiveTab()

        assertThat(state.goToPositionInActiveEditableEditor(3, 4)).isFalse()

        var navigatedLine = -1
        var navigatedColumn = -1
        state.registerCodeEditorCallback(
            tabId = "tab-1",
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

        assertThat(state.goToPositionInActiveEditableEditor(3, 4)).isTrue()
        assertThat(navigatedLine).isEqualTo(3)
        assertThat(navigatedColumn).isEqualTo(4)
    }

    @Test
    fun requestGoToPositionInActiveEditableEditor_shouldExposeDialogFriendlyResults() {
        assertThat(state.requestGoToPositionInActiveEditableEditor(2, 0))
            .isEqualTo(ActiveEditorCommandResult.NO_OPEN_FILE)

        setActiveTab()
        assertThat(state.requestGoToPositionInActiveEditableEditor(2, 0))
            .isEqualTo(ActiveEditorCommandResult.UNSUPPORTED_EDITOR)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = CodeEditorCallback(
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
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.requestGoToPositionInActiveEditableEditor(2, 0))
            .isEqualTo(ActiveEditorCommandResult.SUCCESS)
    }

    @Test
    fun requestReplaceAllInActiveEditor_shouldExposeCapabilityAndMatchResults() {
        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(ReplaceAllInActiveEditorResult.NoOpenFile)

        setActiveTab()
        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(ReplaceAllInActiveEditorResult.UnsupportedEditor)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
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
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(ReplaceAllInActiveEditorResult.NoMatches)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = CodeEditorCallback(
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
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "" },
                readSelection = { null }
            )
        )

        assertThat(state.requestReplaceAllInActiveEditor("foo", "bar"))
            .isEqualTo(ReplaceAllInActiveEditorResult.Success(3))
    }

    @Test
    fun requestToggleLineCommentInActiveEditor_shouldExposeCapabilityAndResolvedToken() {
        assertThat(
            state.requestToggleLineCommentInActiveEditor { "//" }
        ).isEqualTo(ActiveEditorCommandResult.NO_OPEN_FILE)

        setActiveTab()
        assertThat(
            state.requestToggleLineCommentInActiveEditor { "//" }
        ).isEqualTo(ActiveEditorCommandResult.UNSUPPORTED_EDITOR)

        var resolvedToken: String? = null
        var resolvedFileName: String? = null
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = CodeEditorCallback(
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
                cursorPosition = { CursorSnapshot(0, 0) },
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
        ).isEqualTo(ActiveEditorCommandResult.SUCCESS)
        assertThat(resolvedToken).isEqualTo("//")
        assertThat(resolvedFileName).isEqualTo("EditorContainerStateTest.kt")
    }

    @Test
    fun snapshotActiveEditableEditorContent_shouldExposeCapabilityAndText() {
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(ActiveEditableEditorSnapshotResult.NoOpenFile)

        setActiveTab()
        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(ActiveEditableEditorSnapshotResult.UnsupportedEditor)

        state.registerCodeEditorCallback(
            tabId = "tab-1",
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
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "fun test() = Unit" },
                readSelection = { null }
            )
        )

        assertThat(state.snapshotActiveEditableEditorContent())
            .isEqualTo(
                ActiveEditableEditorSnapshotResult.Success(
                    snapshot = ActiveEditableEditorSnapshot(
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
                cursorPosition = { CursorSnapshot(0, 0) },
                setSelectionRange = { _, _, _, _ -> false },
                readAllText = { "first-text" },
                readSelection = { null }
            )
        )
        state.registerCodeEditorCallback(
            tabId = "tab-2",
            callback = CodeEditorCallback(
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
                cursorPosition = { CursorSnapshot(0, 0) },
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
    fun closeTabsForDeletedPath_shouldCloseOpenedFilesUnderDeletedDirectory() {
        val deletedDir = File(context.cacheDir, "src")
        val firstFile = File(deletedDir, "First.kt")
        val nestedFile = File(deletedDir, "nested/Second.kt")
        val siblingFile = File(context.cacheDir, "src2/Third.kt")
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = firstFile),
                EditorTab(id = "tab-2", file = nestedFile),
                EditorTab(id = "tab-3", file = siblingFile)
            ),
            activeTabId = "tab-1"
        )

        val closedCount = state.closeTabsForDeletedPath(deletedDir)

        assertThat(closedCount).isEqualTo(2)
        assertThat(state.tabs.map { it.id }).containsExactly("tab-3")
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-3")
    }

    @Test
    fun closeTabsForDeletedPath_shouldAskBeforeClosingDirtyDeletedTab() {
        val deletedFile = File(context.cacheDir, "Dirty.kt")
        setTabs(
            managerTabs = listOf(EditorTab(id = "tab-1", file = deletedFile)),
            activeTabId = "tab-1"
        )
        state.updateTabState(tabId = "tab-1", isDirty = true, canUndo = true, canRedo = false)

        val closedCount = state.closeTabsForDeletedPath(deletedFile)

        assertThat(closedCount).isEqualTo(0)
        assertThat(state.tabs.map { it.id }).containsExactly("tab-1")
        assertThat(state.pendingCloseTab?.id).isEqualTo("tab-1")
    }

    @Test
    fun syncTabsForMovedPath_shouldRetargetOpenedFilesUnderMovedDirectory() {
        val oldDir = File(context.cacheDir, "src")
        val newDir = File(context.cacheDir, "source")
        val firstFile = File(oldDir, "First.kt")
        val nestedFile = File(oldDir, "nested/Second.kt")
        val siblingFile = File(context.cacheDir, "src2/Third.kt")
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = firstFile),
                EditorTab(id = "tab-2", file = nestedFile),
                EditorTab(id = "tab-3", file = siblingFile)
            ),
            activeTabId = "tab-2"
        )

        val updatedCount = state.syncTabsForMovedPath(oldDir, newDir)

        assertThat(updatedCount).isEqualTo(2)
        assertThat(state.tabs.map { it.file.absolutePath }).containsExactly(
            File(newDir, "First.kt").absolutePath,
            File(newDir, "nested/Second.kt").absolutePath,
            siblingFile.absolutePath
        ).inOrder()
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.file)
            .isEqualTo(File(newDir, "nested/Second.kt"))
    }

    @Test
    fun syncTabsForMovedPath_shouldKeepDirtyMovedTabOpen() {
        val oldFile = File(context.cacheDir, "Dirty.kt")
        val newFile = File(context.cacheDir, "RenamedDirty.kt")
        setTabs(
            managerTabs = listOf(EditorTab(id = "tab-1", file = oldFile)),
            activeTabId = "tab-1"
        )
        state.updateTabState(tabId = "tab-1", isDirty = true, canUndo = true, canRedo = false)

        val updatedCount = state.syncTabsForMovedPath(oldFile, newFile)

        assertThat(updatedCount).isEqualTo(1)
        assertThat(state.tabs.single().file).isEqualTo(newFile)
        assertThat(state.tabs.single().isDirty).isTrue()
        assertThat(state.pendingCloseTab).isNull()
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
            callback = CodeEditorCallback(
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
                cursorPosition = { CursorSnapshot(0, 0) },
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

        assertThat(state.openFileAndGoToPosition(file, line = 4, column = 2)).isTrue()
        assertThat(navigatedLine).isEqualTo(4)
        assertThat(navigatedColumn).isEqualTo(2)
    }

    @Test
    fun openFileAndGoToPosition_shouldRetryOriginalPositionUntilEditorReady() {
        val file = File(context.cacheDir, "RetryJumpTarget.kt").apply {
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

        var attempts = 0
        var navigatedLine = -1
        var navigatedColumn = -1
        state.registerCodeEditorCallback(
            tabId = "tab-1",
            callback = CodeEditorCallback(
                goToPosition = { line, column ->
                    attempts++
                    if (attempts < 3) {
                        false
                    } else {
                        navigatedLine = line
                        navigatedColumn = column
                        true
                    }
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

        assertThat(state.openFileAndGoToPosition(file, line = 12, column = 5)).isTrue()
        shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS)

        assertThat(attempts).isAtLeast(3)
        assertThat(navigatedLine).isEqualTo(12)
        assertThat(navigatedColumn).isEqualTo(5)
    }

    @Test
    fun syncFromManager_shouldKeepPreviousTabLspStatusWhenOnlyActiveTabChanges() {
        val firstFile = File(context.cacheDir, "First.kt")
        val secondFile = File(context.cacheDir, "Second.kt")
        val tabs = listOf(
            EditorTab(id = "tab-1", file = firstFile),
            EditorTab(id = "tab-2", file = secondFile)
        )
        setTabs(managerTabs = tabs, activeTabId = "tab-1")
        setLspStatus("tab-1", EditorStatus.Ready)

        setTabs(managerTabs = tabs, activeTabId = "tab-2")

        assertThat(state.getLspStatus("tab-1")).isEqualTo(EditorStatus.Ready)
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
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY)).isEmpty()
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
    fun resolveSuccessfulSaveAllNotificationTargets_shouldPreferTargetsFromSaveResults() {
        val savedFile = File(context.cacheDir, "Saved.kt")

        val successfulTargets = state.resolveSuccessfulSaveAllNotificationTargets(
            listOf(
                SaveResult.Success(
                    timestamp = 1L,
                    reason = SaveReason.MANUAL,
                    target = SaveTarget(tabId = "tab-saved", file = savedFile)
                )
            )
        )

        assertThat(successfulTargets.map { it.tabId }).containsExactly("tab-saved")
        assertThat(successfulTargets.map { it.file }).containsExactly(savedFile)
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
    fun closeOtherTabsForActiveTab_shouldPromptBeforeClosingDirtyOtherTab() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt")),
                EditorTab(id = "tab-3", file = File(context.cacheDir, "Third.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.updateTabState(tabId = "tab-1", isDirty = true, canUndo = false, canRedo = false)

        assertThat(state.closeOtherTabsForActiveTab()).isTrue()

        assertThat(state.pendingCloseTab?.id).isEqualTo("tab-1")
        assertThat(state.tabs.map { it.id }).containsExactly("tab-1", "tab-2", "tab-3")
    }

    @Test
    fun closeAllTabs_shouldPromptBeforeClosingDirtyTab() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-2"
        )
        state.updateTabState(tabId = "tab-2", isDirty = true, canUndo = false, canRedo = false)

        assertThat(state.closeAllTabs()).isTrue()

        assertThat(state.pendingCloseTab?.id).isEqualTo("tab-2")
        assertThat(state.tabs.map { it.id }).containsExactly("tab-1", "tab-2")
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
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY)).isEmpty()

        state.toggleSplitEditor()

        assertThat(state.isSplitEditorEnabled).isFalse()
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY)).isEmpty()
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
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY)).isEmpty()
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
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("tab-2")
        assertThat(state.getActiveIndexForPane(EditorPaneId.SECONDARY)).isEqualTo(1)
        assertThat(state.canMoveActiveTabToSecondaryPane()).isFalse()
    }

    @Test
    fun copyActiveTabToSecondaryPane_shouldShowSameTabInBothPanes() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-1"
        )

        assertThat(state.copyActiveTabToSecondaryPane()).isTrue()

        assertThat(state.isSplitEditorEnabled).isTrue()
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getActiveIndexForPane(EditorPaneId.PRIMARY)).isEqualTo(0)
        assertThat(state.getActiveIndexForPane(EditorPaneId.SECONDARY)).isEqualTo(0)
        assertThat(state.canCopyActiveTabToSecondaryPane()).isFalse()
    }

    @Test
    fun splitEditorSnapshot_shouldRestorePaneStateAfterTabIdsChange() {
        val firstFile = File(context.cacheDir, "First.kt")
        val secondFile = File(context.cacheDir, "Second.kt")
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "old-tab-1", file = firstFile),
                EditorTab(id = "old-tab-2", file = secondFile)
            ),
            activeTabId = "old-tab-2"
        )
        state.updateSplitEditorLayout(SplitEditorLayout.VERTICAL)
        state.updateSplitEditorPrimaryRatio(0.7f)
        state.moveActiveTabToSecondaryPane()

        val snapshot = state.createSplitEditorStateSnapshot()
        val restored = newEditorContainerState()
        restored.syncFromManager(
            managerTabs = listOf(
                EditorTab(id = "new-tab-1", file = firstFile),
                EditorTab(id = "new-tab-2", file = secondFile)
            ),
            activeTabId = "new-tab-1"
        )

        restored.restoreSplitEditorStateSnapshot(snapshot)

        assertThat(restored.isSplitEditorEnabled).isTrue()
        assertThat(restored.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
        assertThat(restored.splitEditorLayout).isEqualTo(SplitEditorLayout.VERTICAL)
        assertThat(restored.splitEditorPrimaryRatio).isWithin(0.0001f).of(0.7f)
        assertThat(restored.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("new-tab-1")
        assertThat(restored.getTabsForPane(EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("new-tab-2")
    }

    @Test
    fun restoreFromManager_shouldRestorePersistedSplitEditorSession() {
        val firstFile = File(context.cacheDir, "First.kt")
        val secondFile = File(context.cacheDir, "Second.kt")
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "old-tab-1", file = firstFile),
                EditorTab(id = "old-tab-2", file = secondFile)
            ),
            activeTabId = "old-tab-1"
        )
        state.copyActiveTabToSecondaryPane()
        state.updateSplitEditorLayout(SplitEditorLayout.VERTICAL)
        state.updateSplitEditorPrimaryRatio(0.65f)

        val restoredEditorManager = mockk<IEditorManager>(relaxed = true)
        every { restoredEditorManager.getOpenTabs() } returns listOf(
            EditorTab(id = "new-tab-1", file = firstFile),
            EditorTab(id = "new-tab-2", file = secondFile)
        )
        every { restoredEditorManager.getActiveTabId() } returns "new-tab-2"
        val restored = newEditorContainerState(restoredEditorManager)

        restored.restoreFromManager()

        assertThat(restored.isSplitEditorEnabled).isTrue()
        assertThat(restored.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
        assertThat(restored.splitEditorLayout).isEqualTo(SplitEditorLayout.VERTICAL)
        assertThat(restored.splitEditorPrimaryRatio).isWithin(0.0001f).of(0.65f)
        assertThat(restored.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("new-tab-1", "new-tab-2")
        assertThat(restored.getTabsForPane(EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("new-tab-1")
    }

    @Test
    fun copyActiveTabToSecondaryPane_shouldNotDuplicateExistingSecondaryView() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-1"
        )
        state.copyActiveTabToSecondaryPane()

        assertThat(state.copyActiveTabToSecondaryPane()).isFalse()

        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("tab-1")
    }

    @Test
    fun closeSecondaryPane_shouldClearCopiedTabView() {
        setTabs(
            managerTabs = listOf(
                EditorTab(id = "tab-1", file = File(context.cacheDir, "First.kt")),
                EditorTab(id = "tab-2", file = File(context.cacheDir, "Second.kt"))
            ),
            activeTabId = "tab-1"
        )
        state.copyActiveTabToSecondaryPane()

        state.closeSecondaryPane()

        assertThat(state.isSplitEditorEnabled).isFalse()
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1", "tab-2")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY)).isEmpty()
        assertThat(state.canCopyActiveTabToSecondaryPane()).isTrue()
    }

    @Test
    fun splitEditorPrimaryRatio_shouldDefaultToEvenPanes() {
        assertThat(state.splitEditorPrimaryRatio).isEqualTo(0.5f)
    }

    @Test
    fun splitEditorLayout_shouldDefaultToHorizontal() {
        assertThat(state.splitEditorLayout).isEqualTo(SplitEditorLayout.HORIZONTAL)
    }

    @Test
    fun updateSplitEditorLayout_shouldUpdateLayout() {
        state.updateSplitEditorLayout(SplitEditorLayout.VERTICAL)

        assertThat(state.splitEditorLayout).isEqualTo(SplitEditorLayout.VERTICAL)
    }

    @Test
    fun toggleSplitEditor_shouldKeepSelectedLayoutForNextOpen() {
        state.updateSplitEditorLayout(SplitEditorLayout.VERTICAL)
        state.toggleSplitEditor()
        state.toggleSplitEditor()
        state.toggleSplitEditor()

        assertThat(state.isSplitEditorEnabled).isTrue()
        assertThat(state.splitEditorLayout).isEqualTo(SplitEditorLayout.VERTICAL)
    }

    @Test
    fun resizeSplitEditorBy_shouldUpdateRatioFromContainerWidth() {
        state.resizeSplitEditorBy(deltaPx = 120f, containerWidthPx = 1000f)

        assertThat(state.splitEditorPrimaryRatio).isWithin(0.0001f).of(0.62f)
    }

    @Test
    fun updateSplitEditorPrimaryRatio_shouldClampToUsableRange() {
        state.updateSplitEditorPrimaryRatio(0.1f)
        assertThat(state.splitEditorPrimaryRatio).isEqualTo(0.25f)

        state.updateSplitEditorPrimaryRatio(0.9f)
        assertThat(state.splitEditorPrimaryRatio).isEqualTo(0.75f)
    }

    @Test
    fun resizeSplitEditorBy_shouldIgnoreInvalidInput() {
        state.resizeSplitEditorBy(deltaPx = 100f, containerWidthPx = 0f)
        state.updateSplitEditorPrimaryRatio(Float.NaN)
        state.resizeSplitEditorBy(deltaPx = Float.POSITIVE_INFINITY, containerWidthPx = 1000f)

        assertThat(state.splitEditorPrimaryRatio).isEqualTo(0.5f)
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
        state.selectTabInPane(EditorPaneId.PRIMARY, 0)

        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.snapshotActivePluginEditorContextOrNull()?.tabId).isEqualTo("tab-1")

        state.selectTabInPane(EditorPaneId.SECONDARY, 1)

        assertThat(state.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
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

        state.selectTabInPane(EditorPaneId.PRIMARY, 1)

        assertThat(state.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY).map { it.id })
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
        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.id })
            .containsExactly("tab-1")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY)).isEmpty()
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
        state.focusEditorPane(EditorPaneId.PRIMARY)
        state.openFileWithType(primaryPreview, ContentType.IMAGE)

        assertThat(state.focusedPane).isEqualTo(EditorPaneId.PRIMARY)
        assertThat(state.getTabsForPane(EditorPaneId.PRIMARY).map { it.file.name })
            .containsExactly("First.kt", "PrimaryPreview.png")
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY).map { it.id })
            .containsExactly("tab-2")

        val secondaryPreview = File(context.cacheDir, "SecondaryPreview.png")
        state.focusEditorPane(EditorPaneId.SECONDARY)
        state.openFileWithType(secondaryPreview, ContentType.IMAGE)

        assertThat(state.focusedPane).isEqualTo(EditorPaneId.SECONDARY)
        assertThat(state.getTabsForPane(EditorPaneId.SECONDARY).map { it.file.name })
            .containsExactly("Second.kt", "SecondaryPreview.png")
    }

    @Test
    fun codeEditorRuntimeCache_shouldEvictOldCleanUnmountedTabsOverLimit() {
        val managerTabs = createCodeTabs(EditorContainerState.CODE_EDITOR_RUNTIME_CACHE_LIMIT + 2)
        setTabs(managerTabs, activeTabId = managerTabs.last().id)

        managerTabs.forEach { tab ->
            state.getOrCreateCodeEditorRuntime(state.tabs.first { it.id == tab.id })
            state.markCodeEditorRuntimeLoaded(tab.id)
        }

        assertThat(state.isCodeEditorRuntimeLoaded("tab-1")).isFalse()
        assertThat(state.isCodeEditorRuntimeLoaded("tab-2")).isFalse()
        assertThat(state.isCodeEditorRuntimeLoaded(managerTabs.last().id)).isTrue()
        assertThat(managerTabs.count { state.isCodeEditorRuntimeLoaded(it.id) })
            .isAtMost(EditorContainerState.CODE_EDITOR_RUNTIME_CACHE_LIMIT)
    }

    @Test
    fun codeEditorRuntimeCache_shouldKeepDirtyUnmountedTabsOverLimit() {
        val managerTabs = createCodeTabs(EditorContainerState.CODE_EDITOR_RUNTIME_CACHE_LIMIT + 2)
        setTabs(managerTabs, activeTabId = managerTabs.last().id)
        state.updateTabState(tabId = "tab-1", isDirty = true, canUndo = true, canRedo = false)

        managerTabs.forEach { tab ->
            state.getOrCreateCodeEditorRuntime(state.tabs.first { it.id == tab.id })
            state.markCodeEditorRuntimeLoaded(tab.id)
        }

        assertThat(state.isCodeEditorRuntimeLoaded("tab-1")).isTrue()
        assertThat(state.isCodeEditorRuntimeLoaded("tab-2")).isFalse()
        assertThat(state.isCodeEditorRuntimeLoaded(managerTabs.last().id)).isTrue()
        assertThat(managerTabs.count { state.isCodeEditorRuntimeLoaded(it.id) })
            .isAtMost(EditorContainerState.CODE_EDITOR_RUNTIME_CACHE_LIMIT)
    }

    @Test
    fun activeTabEditStateAccessors_shouldReflectUpdatedTabState() {
        setActiveTab()

        state.updateTabState(tabId = "tab-1", isDirty = true, canUndo = true, canRedo = false)

        assertThat(state.isActiveTabDirty()).isTrue()
        assertThat(state.getActiveEditorToolBarState().canUndo).isTrue()
        assertThat(state.getActiveEditorToolBarState().canRedo).isFalse()
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
                cursorPosition = { CursorSnapshot(0, 0) },
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
                cursorPosition = { CursorSnapshot(0, 0) },
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

    private fun createCodeTabs(count: Int): List<EditorTab> = (1..count).map { index ->
        EditorTab(
            id = "tab-$index",
            file = File(context.cacheDir, "File$index.kt")
        )
    }

    private fun newEditorContainerState(manager: IEditorManager = editorManager): EditorContainerState = EditorContainerState(
        context = context,
        editorManager = manager,
        snippetManager = mockk<PluginSnippetManager>(relaxed = true),
        pluginThemeRegistry = mockk<PluginEditorThemeRegistry>(relaxed = true),
        projectSymbolIndexServiceProvider = { null },
        projectRootPathProvider = { context.cacheDir.absolutePath }
    )

    private fun setLspStatus(tabId: String, status: EditorStatus) {
        val field = EditorContainerState::class.java.getDeclaredField("lspUiState")
        field.isAccessible = true
        val lspUiState = field.get(state) as EditorLspUiState
        lspUiState.handleStatusChanged(tabId, status)
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
