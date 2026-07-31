package com.wuxianggujun.tinaide.ui.compose.components.editor

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.debug.BreakpointStore
import com.wuxianggujun.tinaide.core.editor.IBookmarkRepository
import com.wuxianggujun.tinaide.core.editorlsp.CompletionFetchResult
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItem
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItemKind
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.editorlsp.CompletionTextEdit
import com.wuxianggujun.tinaide.core.editorlsp.DefaultCompletionProvider
import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken as LspSemanticToken
import com.wuxianggujun.tinaide.core.editorview.DiagnosticSeverity
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionFetchResult
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionItem
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionKind
import com.wuxianggujun.tinaide.core.editorview.EditorCompletionTextEdit
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorDiagnostic
import com.wuxianggujun.tinaide.core.editorview.EditorRenderPerformanceSnapshot
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.GutterDecoration
import com.wuxianggujun.tinaide.core.editorview.SemanticToken as EditorSemanticToken
import com.wuxianggujun.tinaide.core.editorview.SemanticTokenModifier
import com.wuxianggujun.tinaide.core.editorview.SemanticTokenType
import com.wuxianggujun.tinaide.core.editorview.TinaEditor
import com.wuxianggujun.tinaide.core.font.AppFontManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextChangeListener
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.editor.session.DocumentSession
import com.wuxianggujun.tinaide.editor.session.EditorViewState
import com.wuxianggujun.tinaide.search.CodeSearchEngine
import com.wuxianggujun.tinaide.search.CodeSearchResult
import com.wuxianggujun.tinaide.core.editorlsp.CMakeLanguageSupport
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.editorlsp.MakeLanguageSupport
import com.wuxianggujun.tinaide.core.editorlsp.SemanticTokensRequestResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorCallback
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorDocumentBinding
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorRuntime
import com.wuxianggujun.tinaide.ui.compose.state.editor.CodeEditorStateBinding
import com.wuxianggujun.tinaide.ui.compose.state.editor.CursorSnapshot
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.SelectionSnapshot
import com.wuxianggujun.tinaide.ui.compose.state.editor.TextEditOperation
import com.wuxianggujun.tinaide.ui.compose.state.editor.TinaTextContentProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import timber.log.Timber

private const val LSP_EDITOR_STATE_BINDING_KEY = "lsp-editor-actions"
private const val GUTTER_EDITOR_STATE_BINDING_KEY = "gutter-actions"

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
fun TinaCodeEditorPage(
    state: EditorContainerState,
    tab: EditorTabState,
    modifier: Modifier = Modifier,
    onCursorPositionChanged: (line: Int, column: Int) -> Unit = { _, _ -> },
    onFileEncodingChanged: (encoding: String) -> Unit = { _ -> },
    onLoadingStateChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember(tab.id, state, tab.file) { state.getOrCreateCodeEditorRuntime(tab) }
    val buffer = runtime.buffer
    val textSnapshot = remember(buffer) { VersionedBufferTextSnapshot(buffer) }
    val textContentProvider = remember(tab.id, buffer) { TinaTextContentProvider(buffer) }
    val codeSearchEngine = remember(tab.id, textContentProvider) { CodeSearchEngine(textContentProvider) }
    val completionProvider = remember(tab.id, buffer, textSnapshot, tab.file) {
        val localCompletionCache = LocalCompletionCache()
        DefaultCompletionProvider(
            localProvider = { _, position, triggerChar ->
                buildLocalCompletions(
                    state = state,
                    buffer = buffer,
                    file = tab.file,
                    position = position,
                    triggerChar = triggerChar,
                    localCompletionCache = localCompletionCache,
                    textSnapshot = textSnapshot
                )
            },
            lspProvider = { _, position, triggerChar ->
                state.requestLspCompletion(tab.id, position, triggerChar)
            }
        )
    }
    val editorState = runtime.editorState
    val syntaxHighlighter = remember(tab.id, runtime, tab.file) { state.getOrCreateSyntaxHighlighter(tab) }
    val foldingProvider = remember(tab.id, runtime, tab.file) { state.getOrCreateFoldingProvider(tab) }
    val breakpointStore: BreakpointStore = koinInject()
    val bookmarkRepository: IBookmarkRepository = koinInject()
    val bookmarkProjectRootPath = state.getBookmarksProjectRootPathOrNull()
    val breakpointSupportedExtensions = remember {
        CxxFileSupport.editorRelatedExtensions + setOf(
            "java", "kt", "kts", "py", "js", "ts", "rs", "go", "swift",
            "sh", "bash", "zsh",
            "json", "yaml", "yml", "xml", "txt"
        )
    }

    val latestOnCursorPositionChanged by rememberUpdatedState(onCursorPositionChanged)
    val latestOnFileEncodingChanged by rememberUpdatedState(onFileEncodingChanged)

    var loading by remember(tab.id) { mutableStateOf(!runtime.isContentLoaded) }
    var loadError by remember(tab.id) { mutableStateOf<String?>(null) }
    // 300ms 内加载完就不显示进度条，避免小文件一闪而过造成的 UI 抖动
    val showLoadingIndicator by produceState(initialValue = false, loading) {
        if (loading) {
            delay(300)
            value = true
        } else {
            value = false
        }
    }
    // 把 loading 状态上报给上层 TabBar 绘制共用的扩散指示器。
    val latestOnLoadingStateChanged by rememberUpdatedState(onLoadingStateChanged)
    LaunchedEffect(showLoadingIndicator) {
        latestOnLoadingStateChanged(showLoadingIndicator)
    }
    DisposableEffect(tab.id) {
        onDispose { latestOnLoadingStateChanged(false) }
    }
    var performanceSnapshotReader by remember(tab.id) {
        mutableStateOf<(() -> EditorRenderPerformanceSnapshot)?>(null)
    }
    var externalEditPreparer by remember(tab.id) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    val updatePerformanceSnapshotReader = remember(tab.id) {
        { reader: (() -> EditorRenderPerformanceSnapshot)? -> performanceSnapshotReader = reader }
    }
    val updateExternalEditPreparer = remember(tab.id) {
        { preparer: (() -> Unit)? -> externalEditPreparer = preparer }
    }
    val callbackRegistrationId = remember(tab.id, runtime) { Any() }

    LaunchedEffect(tab.id, editorState, buffer, foldingProvider) {
        combine(
            snapshotFlow { editorState.config.codeFolding }.distinctUntilChanged(),
            Prefs.lspFoldingRangeEnabledFlow
        ) { codeFoldingEnabled, preferLsp ->
            FoldingDriverKey(enabled = codeFoldingEnabled, preferLsp = preferLsp)
        }
            .distinctUntilChanged()
            .flatMapLatest { driver ->
                if (!driver.enabled) {
                    flowOf<FoldingComputeRequest?>(null)
                } else {
                    buffer.versionFlow
                        .debounce(280)
                        .map { version ->
                            FoldingComputeRequest(
                                documentVersion = version,
                                preferLsp = driver.preferLsp
                            )
                        }
                }
            }
            .collectLatest { requestOrNull ->
                val request = requestOrNull ?: run {
                    editorState.clearFoldRegions()
                    return@collectLatest
                }
                val documentVersion = request.documentVersion
                val provider = foldingProvider

                if (request.preferLsp) {
                    val lspRegions = try {
                        state.requestLspFoldingRanges(
                            tabId = tab.id,
                            documentVersion = documentVersion
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Timber.tag("EditorFolding").w(error, "LSP folding request failed for %s", tab.file.name)
                        null
                    }
                    if (lspRegions != null) {
                        editorState.setFoldRegions(lspRegions, documentVersion = documentVersion)
                        return@collectLatest
                    }
                }

                if (provider == null) {
                    editorState.clearFoldRegions()
                    return@collectLatest
                }

                val regions = withContext(Dispatchers.Default) {
                    provider.computeFoldRegions(textSnapshot.readText())
                }
                editorState.setFoldRegions(regions, documentVersion = documentVersion)
            }
    }

    DisposableEffect(tab.id, state, editorState, buffer, codeSearchEngine, callbackRegistrationId) {
        val editorCallback = CodeEditorCallback(
            goToPosition = goToPosition@ { line, column ->
                if (loading || loadError != null) {
                    return@goToPosition false
                }
                externalEditPreparer?.invoke()
                editorState.gotoLine(line, column)
                true
            },
            selectAll = {
                externalEditPreparer?.invoke()
                editorState.selectAll()
                true
            },
            replaceSelection = { replacement ->
                externalEditPreparer?.invoke()
                editorState.replaceSelection(replacement)
            },
            replaceWholeText = { newText ->
                externalEditPreparer?.invoke()
                replaceWholeText(buffer, editorState, textSnapshot, newText)
            },
            applyTextEdits = { edits ->
                externalEditPreparer?.invoke()
                applyTextEdits(buffer, editorState, edits)
            },
            validateTextEdits = { edits ->
                resolveTextEdits(buffer, edits) != null
            },
            documentVersion = { buffer.version },
            toggleLineComment = { commentToken ->
                externalEditPreparer?.invoke()
                editorState.toggleLineComment(commentToken)
            },
            replaceAll = { findText, replaceText, caseSensitive, useRegex ->
                externalEditPreparer?.invoke()
                editorState.replaceAll(
                    findText = findText,
                    replaceText = replaceText,
                    caseSensitive = caseSensitive,
                    useRegex = useRegex
                )
            },
            undo = {
                externalEditPreparer?.invoke()
                editorState.undo()
            },
            redo = {
                externalEditPreparer?.invoke()
                editorState.redo()
            },
            insertTextAtCursor = { text ->
                externalEditPreparer?.invoke()
                editorState.insertUserInput(text)
            },
            cursorPosition = {
                val cursor = editorState.cursorPosition
                CursorSnapshot(
                    line = cursor.line,
                    column = cursor.column
                )
            },
            setSelectionRange = selection@ { startLine, startColumn, endLine, endColumn ->
                val startOffset = buffer.strictOffset(startLine, startColumn) ?: return@selection false
                val endOffset = buffer.strictOffset(endLine, endColumn) ?: return@selection false
                if (endOffset < startOffset) return@selection false
                externalEditPreparer?.invoke()
                editorState.selectRange(
                    startOffset = startOffset,
                    endOffset = endOffset
                )
                true
            },
            readAllText = {
                textSnapshot.readText()
            },
            readSelection = fun(): SelectionSnapshot? {
                val range = editorState.selectionRange ?: return null
                if (range.isEmpty) return null
                val selectedText = editorState.selectedText() ?: return null
                val startPos = buffer.offsetToPosition(range.start)
                val endPos = buffer.offsetToPosition(range.end)
                return SelectionSnapshot(
                    text = selectedText,
                    startLine = startPos.line,
                    startColumn = startPos.column,
                    endLine = endPos.line,
                    endColumn = endPos.column
                )
            },
            readPerformanceSnapshot = {
                performanceSnapshotReader?.invoke()
            },
            applyEditorSettings = { settings ->
                // 统一从 Prefs 读取 EditorConfig，确保“设置页变更 → 已打开编辑器即时生效”。
                editorState.config = EditorConfig.fromPrefs()
                editorState.fontSizeSp = settings.fontSize

                val appContext = context.applicationContext
                val typeface = if (settings.fontPath.isNotBlank()) {
                    AppFontManager.loadCustomFont(settings.fontPath)
                        ?: AppFontManager.getMonospaceTypeface(appContext)
                } else {
                    AppFontManager.getMonospaceTypeface(appContext)
                }
                editorState.typeface = typeface
            },
            applyEditorColorScheme = { scheme ->
                editorState.colorScheme = scheme
            }
        )
        state.bindCodeEditorCallbacks(
            tabId = tab.id,
            registrationId = callbackRegistrationId,
            search = { query, options ->
                codeSearchEngine.search(query, options).filterIsInstance<CodeSearchResult>()
            },
            goToMatch = { hit ->
                externalEditPreparer?.invoke()
                editorState.selectRange(
                    startOffset = hit.range.startIndex,
                    endOffset = hit.range.endIndex
                )
            },
            editorCallback = editorCallback
        )
        onDispose {
            state.unbindCodeEditorCallbacks(tab.id, callbackRegistrationId)
        }
    }

    val binding = remember(tab.id, state, runtime, textSnapshot, tab.file) {
        runtime.getOrCreateDocumentBinding {
            TextBufferSessionBinding(
                tabId = tab.id,
                state = state,
                buffer = buffer,
                editorState = editorState,
                textSnapshot = textSnapshot
            ) { _, _, documentVersion, change ->
                state.notifyTinaTextChanged(tab.id, change, documentVersion)
            }
        }
    }

    DisposableEffect(runtime, binding) {
        runtime.acquireDocumentBinding(binding)
        onDispose { runtime.releaseDocumentBinding(binding) }
    }

    DisposableEffect(tab.id, editorState, completionProvider, state, buffer, tab.file) {
        val stateBinding = runtime.getOrCreateStateBinding(LSP_EDITOR_STATE_BINDING_KEY) {
            val supportsBasicNavigation = state.supportsBasicLspNavigation(tab.file)
            val supportsAdvancedNavigation = state.supportsAdvancedLspNavigation(tab.file)
            val supportsRefactorActions = state.supportsLspRefactorActions(tab.file)
            val supportsHeaderSourceSwitch = state.supportsHeaderSourceSwitch(tab.file)

            RuntimeEditorStateBinding(
                onAttach = {
                    editorState.onRequestCompletion = { position, triggerChar ->
                        when (
                            val result = completionProvider.requestCompletion(
                                fileUri = tab.file.toURI().toString(),
                                position = position,
                                triggerChar = triggerChar
                            )
                        ) {
                            is CompletionFetchResult.Success -> EditorCompletionFetchResult.Success(
                                result.items.map { item ->
                                    EditorCompletionItem(
                                        label = item.label,
                                        detail = item.detail,
                                        insertText = item.insertText ?: item.label,
                                        kind = item.kind.toEditorCompletionKind(),
                                        filterText = item.filterText,
                                        textEdit = item.textEdit?.toEditorCompletionTextEdit(),
                                        additionalTextEdits = item.additionalTextEdits.map {
                                            it.toEditorCompletionTextEdit()
                                        },
                                        snippetText = item.snippetText,
                                        isLsp = item.source ==
                                            com.wuxianggujun.tinaide.core.editorlsp.CompletionSource.LSP
                                    )
                                }
                            )

                            is CompletionFetchResult.TransientFailure -> {
                                EditorCompletionFetchResult.TransientFailure(result.reason)
                            }
                        }
                    }
                    editorState.onRequestHover = { position ->
                        state.requestLspHoverMarkdown(tab.id, position.line, position.column)
                    }
                    editorState.onRequestSignatureHelp = { position ->
                        state.requestLspSignatureHelp(tab.id, position.line, position.column)
                    }
                    editorState.onRequestGotoDefinition = if (supportsBasicNavigation) {
                        { state.onLspNavigationRequested?.invoke(tab.id, "definition") }
                    } else {
                        null
                    }
                    editorState.onRequestPeekDefinition = if (supportsBasicNavigation) {
                        { state.onLspNavigationRequested?.invoke(tab.id, "peekDefinition") }
                    } else {
                        null
                    }
                    editorState.onRequestFindReferences = if (supportsBasicNavigation) {
                        { state.onLspNavigationRequested?.invoke(tab.id, "references") }
                    } else {
                        null
                    }
                    editorState.onRequestGotoTypeDefinition = if (supportsAdvancedNavigation) {
                        { state.onLspNavigationRequested?.invoke(tab.id, "typeDefinition") }
                    } else {
                        null
                    }
                    editorState.onRequestGotoImplementation = if (supportsAdvancedNavigation) {
                        { state.onLspNavigationRequested?.invoke(tab.id, "implementation") }
                    } else {
                        null
                    }
                    editorState.onRequestCodeActions = if (supportsRefactorActions) {
                        {
                            val (start, end) = resolveSelectedRangeOrCursor(buffer, editorState)
                            state.onLspCodeActionsRequested?.invoke(
                                tab.id,
                                start.line,
                                start.column,
                                end.line,
                                end.column
                            )
                        }
                    } else {
                        null
                    }
                    editorState.onRequestRenameSymbol = if (supportsRefactorActions) {
                        {
                            val cursor = editorState.cursorPosition
                            val currentName = resolveIdentifierAroundCursor(
                                buffer = buffer,
                                line = cursor.line,
                                column = cursor.column
                            )
                            state.onLspRenameRequested?.invoke(
                                tab.id,
                                cursor.line,
                                cursor.column,
                                currentName
                            )
                        }
                    } else {
                        null
                    }
                    editorState.onRequestSwitchHeaderSource = if (supportsHeaderSourceSwitch) {
                        { state.onLspNavigationRequested?.invoke(tab.id, "switchHeaderSource") }
                    } else {
                        null
                    }
                },
                onDetach = {
                    editorState.onRequestCompletion = null
                    editorState.onRequestHover = null
                    editorState.onRequestSignatureHelp = null
                    editorState.onRequestPeekDefinition = null
                    editorState.onRequestGotoDefinition = null
                    editorState.onRequestFindReferences = null
                    editorState.onRequestGotoTypeDefinition = null
                    editorState.onRequestGotoImplementation = null
                    editorState.onRequestCodeActions = null
                    editorState.onRequestRenameSymbol = null
                    editorState.onRequestSwitchHeaderSource = null
                }
            )
        }
        runtime.acquireStateBinding(LSP_EDITOR_STATE_BINDING_KEY, stateBinding)
        onDispose {
            runtime.releaseStateBinding(LSP_EDITOR_STATE_BINDING_KEY, stateBinding)
        }
    }

    DisposableEffect(tab.id, editorState, buffer, breakpointStore, tab.file) {
        val stateBinding = runtime.getOrCreateStateBinding(GUTTER_EDITOR_STATE_BINDING_KEY) {
            val toggleBreakpoint: (Int) -> Unit = { requestedLine ->
                if (tab.file.extension.lowercase() in breakpointSupportedExtensions) {
                    val targetLine = resolveMarkerLine(buffer, requestedLine)
                    if (targetLine != null) {
                        breakpointStore.toggle(tab.file.absolutePath, targetLine)
                    }
                }
            }
            RuntimeEditorStateBinding(
                onAttach = {
                    editorState.onLineNumberTap = toggleBreakpoint
                    editorState.onLineNumberLongPress = toggleBreakpoint
                    editorState.onGutterFoldToggle = { line -> editorState.toggleFoldAtLine(line) }
                },
                onDetach = {
                    editorState.onLineNumberTap = null
                    editorState.onLineNumberLongPress = null
                    editorState.onGutterFoldToggle = null
                }
            )
        }
        runtime.acquireStateBinding(GUTTER_EDITOR_STATE_BINDING_KEY, stateBinding)
        onDispose {
            runtime.releaseStateBinding(GUTTER_EDITOR_STATE_BINDING_KEY, stateBinding)
        }
    }

    // 合并 breakpoints / bookmarks / diagnostics 三条对 editorState 下发 marker 的订阅：
    // 同样的 (tab.id, editorState, tab.file) 触发器改挂到单个 LaunchedEffect 下，用 launch 子协程并行收集。
    // 好处：tab 切换 / file 改名时只做一次重启，避免三个独立 effect 的启动风暴。
    LaunchedEffect(tab.id, state, editorState, tab.file, breakpointStore, bookmarkRepository, bookmarkProjectRootPath) {
        launch {
            // 把 tab.file.absolutePath 外提：否则每次 breakpoints 变化都要对每条断点重复调用
            // File.getAbsolutePath()（会走 JNI + path normalize），断点多时每事件一次扫描全集。
            val tabAbsolutePath = tab.file.absolutePath
            breakpointStore.breakpoints.collect { breakpoints ->
                val lines = breakpoints.asSequence()
                    .filter { it.file == tabAbsolutePath }
                    .map { it.line }
                    .filter { it >= 0 }
                    .toSet()
                applyBreakpoints(editorState, lines)
            }
        }
        launch {
            val root = bookmarkProjectRootPath ?: return@launch
            val tabAbsolutePath = tab.file.absolutePath
            bookmarkRepository.bookmarksFlow(root).collect { bookmarks ->
                val lines = bookmarks.asSequence()
                    .filter { it.filePath == tabAbsolutePath }
                    .map { it.line }
                    .filter { it >= 0 }
                    .toSet()
                applyBookmarks(editorState, lines)
            }
        }
        launch {
            state.getDiagnosticsFlow(tab.file)
                .collect { diagnostics ->
                    applyDiagnostics(editorState, diagnostics)
                }
        }
    }

    LaunchedEffect(tab.id, tab.file, syntaxHighlighter) {
        runtime.contentLoadMutex.withLock {
            if (runtime.isContentLoaded) {
                loading = false
                loadError = null
                ensureTreeSitterPrepared(
                    runtime = runtime,
                    editorState = editorState,
                    syntaxHighlighter = syntaxHighlighter,
                    textSnapshot = textSnapshot
                )
                return@withLock
            }

            loading = true
            loadError = null
            val detachedSnapshot = state.getTabDetachedEditorSnapshot(tab.id)
            if (detachedSnapshot != null) {
                try {
                    binding.withSuppressed { buffer.replaceAll(detachedSnapshot.text) }
                    ensureTreeSitterPrepared(
                        runtime = runtime,
                        editorState = editorState,
                        syntaxHighlighter = syntaxHighlighter,
                        textSnapshot = textSnapshot
                    )
                    restoreEditorViewState(editorState, detachedSnapshot.viewState)
                    state.markTabDetachedEditorSnapshotRestored(tab.id, detachedSnapshot)
                    state.updateTabState(
                        tabId = tab.id,
                        isDirty = detachedSnapshot.isDirty,
                        canUndo = buffer.canUndo(),
                        canRedo = buffer.canRedo()
                    )
                    state.markCodeEditorRuntimeLoaded(tab.id)
                    loading = false
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    loadError = error.message ?: Strings.editor_load_failed.strOr(context)
                    loading = false
                }
                return@withLock
            }

            val detectedCharset = FileEncodingDetector.detectCharset(tab.file)
            binding.withSuppressed { buffer.loadFromFile(tab.file, detectedCharset) }
                .onSuccess {
                    ensureTreeSitterPrepared(
                        runtime = runtime,
                        editorState = editorState,
                        syntaxHighlighter = syntaxHighlighter,
                        textSnapshot = textSnapshot
                    )
                    restoreEditorViewState(editorState, state.getTabEditorViewState(tab.id))
                    state.markTabEditorSnapshotClean(tab.id, detectedCharset)
                    state.updateTabState(
                        tabId = tab.id,
                        isDirty = false,
                        canUndo = buffer.canUndo(),
                        canRedo = buffer.canRedo()
                    )
                    state.markCodeEditorRuntimeLoaded(tab.id)
                    loading = false
                }
                .onFailure { error ->
                    loadError = error.message ?: Strings.editor_load_failed.strOr(context)
                    loading = false
                }
        }
    }

    LaunchedEffect(tab.id, state, tab.file) {
        // 活动页切换只影响 LSP 生命周期，不值得让整页跟着重组。
        snapshotFlow {
            ActiveTabLspAttachmentState(
                isActive = state.isTabActive(tab.id),
                loading = loading,
                loadError = loadError
            )
        }
            .distinctUntilChanged()
            .collect { attachmentState ->
                if (attachmentState.loading || attachmentState.loadError != null) {
                    return@collect
                }
                if (attachmentState.isActive) {
                    state.attachTinaLspForTab(tab.id, tab.file) { textSnapshot.readText() }
                }
            }
    }

    LaunchedEffect(tab.id, state) {
        state.getTabToolbarStateFlow(tab.id)
            ?.collect { toolbarState ->
                state.updateTabState(
                    tab.id,
                    toolbarState.isDirty,
                    toolbarState.canUndo,
                    toolbarState.canRedo
                )
                latestOnFileEncodingChanged(toolbarState.charsetName)
            }
    }

    LaunchedEffect(tab.id, state, editorState, buffer) {
        val visibleFlow = snapshotFlow { editorState.visibleDocumentLines }
            .distinctUntilChanged()
        val semanticEnabledFlow = Prefs.lspAssistSettingsFlow
            .map { it.semanticTokensEnabled }
            .distinctUntilChanged()
        // 直接消费 buffer 自带的 versionFlow —— LaunchedEffect 重启时不再注册/注销 listener，
        // 不再每次分配 callbackFlow / channel。StateFlow 本身已做去重。
        val versionFlow = buffer.versionFlow
        val lspReadyFlow = state.getLspStatusFlow(tab.id)
            .map { it == EditorStatus.Ready }
            .distinctUntilChanged()

        combine(visibleFlow, versionFlow, semanticEnabledFlow, lspReadyFlow) { visible, version, enabled, lspReady ->
            SemanticTokenRequestKey(
                firstLine = visible.first,
                lastLine = visible.last,
                documentVersion = version,
                semanticTokensEnabled = enabled,
                lspReady = lspReady,
            )
        }
            .debounce(120)
            .distinctUntilChanged()
            .collectLatest { key ->
                if (!key.semanticTokensEnabled) {
                    applySemanticTokens(editorState, emptyList(), requestedVisibleLines = null)
                    return@collectLatest
                }
                // LSP 未就绪时按兵不动：不发请求，也不清空既有 token。
                // 一旦状态跳到 Ready，combine 会重新 emit 触发一次重发请求。
                if (!key.lspReady) return@collectLatest
                if (key.lastLine < key.firstLine) {
                    applySemanticTokens(editorState, emptyList(), requestedVisibleLines = null)
                    return@collectLatest
                }
                val result = state.requestLspSemanticTokens(
                    tabId = tab.id,
                    visibleLines = key.firstLine..key.lastLine,
                    documentVersion = key.documentVersion
                )
                if (buffer.version != key.documentVersion) return@collectLatest
                if (result is SemanticTokensRequestResult.Success) {
                    applySemanticTokens(
                        editorState = editorState,
                        tokens = result.tokens,
                        requestedVisibleLines = key.firstLine..key.lastLine
                    )
                }
            }
    }

    LaunchedEffect(editorState.cursorPosition) {
        val cursor = editorState.cursorPosition
        latestOnCursorPositionChanged(cursor.line + 1, cursor.column + 1)
        state.updateTabCursorPosition(tab.id, cursor.line, cursor.column)
    }

    LaunchedEffect(tab.id, state, editorState, buffer) {
        snapshotFlow {
            editorState.selectionRange?.let { range -> range.start to range.end }
        }
            .debounce(180)
            .distinctUntilChanged()
            .collect { range ->
                if (range == null || range.first == range.second) {
                    state.notifyTabSelectionChanged(tab.id, null)
                    return@collect
                }
                val startPos = buffer.offsetToPosition(range.first)
                val endPos = buffer.offsetToPosition(range.second)
                val selectedText = editorState.selectedText().orEmpty()
                state.notifyTabSelectionChanged(
                    tabId = tab.id,
                    selection = SelectionSnapshot(
                        text = selectedText,
                        startLine = startPos.line,
                        startColumn = startPos.column,
                        endLine = endPos.line,
                        endColumn = endPos.column
                    )
                )
            }
    }

    LaunchedEffect(tab.id, state, editorState) {
        snapshotFlow {
            editorState.scrollOffsetXPx.roundToInt() to editorState.scrollOffsetPx.roundToInt()
        }
            .map { (scrollX, scrollY) ->
                val quantizedX = (scrollX / 8) * 8
                val quantizedY = (scrollY / 8) * 8
                quantizedX to quantizedY
            }
            .distinctUntilChanged()
            .debounce(90)
            .collect { (scrollX, scrollY) ->
                state.updateTabScrollPosition(tab.id, scrollX, scrollY)
            }
    }

    DisposableEffect(tab.id, state, editorState) {
        onDispose {
            state.updateTabScrollPosition(
                tabId = tab.id,
                scrollX = editorState.scrollOffsetXPx.roundToInt(),
                scrollY = editorState.scrollOffsetPx.roundToInt()
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        TinaEditor(
            state = editorState,
            modifier = Modifier.fillMaxSize(),
            onPerformanceSnapshotReaderChanged = updatePerformanceSnapshotReader,
            onExternalEditPreparerChanged = updateExternalEditPreparer
        )

        state.peekDefinitionPanelState
            ?.takeIf { it.ownerTabId == tab.id }
            ?.let { panelState ->
                PeekDefinitionPanel(
                    panelState = panelState,
                    onLocationSelected = { location ->
                        state.dismissPeekDefinitionPanel(tab.id)
                        state.openFileAndGoToPosition(
                            File(location.filePath),
                            location.line,
                            location.column
                        )
                    },
                    onDismiss = { state.dismissPeekDefinitionPanel(tab.id) },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

        if (loadError != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 8.dp,
                        top = 6.dp,
                        bottom = 6.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = loadError ?: Strings.editor_load_failed.strOr(context),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                runtime.contentLoadMutex.withLock {
                                    loading = true
                                    loadError = null
                                    val detachedSnapshot = state.getTabDetachedEditorSnapshot(tab.id)
                                    if (detachedSnapshot != null) {
                                        binding.withSuppressed { buffer.replaceAll(detachedSnapshot.text) }
                                        runtime.isTreeSitterSnapshotReady = false
                                        ensureTreeSitterPrepared(
                                            runtime = runtime,
                                            editorState = editorState,
                                            syntaxHighlighter = syntaxHighlighter,
                                            textSnapshot = textSnapshot
                                        )
                                        restoreEditorViewState(editorState, detachedSnapshot.viewState)
                                        state.markTabDetachedEditorSnapshotRestored(tab.id, detachedSnapshot)
                                        state.updateTabState(
                                            tabId = tab.id,
                                            isDirty = detachedSnapshot.isDirty,
                                            canUndo = buffer.canUndo(),
                                            canRedo = buffer.canRedo()
                                        )
                                        state.markCodeEditorRuntimeLoaded(tab.id)
                                        loading = false
                                        return@withLock
                                    }
                                    val detectedCharset = FileEncodingDetector.detectCharset(tab.file)
                                    binding.withSuppressed { buffer.loadFromFile(tab.file, detectedCharset) }
                                        .onSuccess {
                                            runtime.isTreeSitterSnapshotReady = false
                                            ensureTreeSitterPrepared(
                                                runtime = runtime,
                                                editorState = editorState,
                                                syntaxHighlighter = syntaxHighlighter,
                                                textSnapshot = textSnapshot
                                            )
                                            restoreEditorViewState(editorState, state.getTabEditorViewState(tab.id))
                                            state.markTabEditorSnapshotClean(tab.id, detectedCharset)
                                            state.updateTabState(
                                                tabId = tab.id,
                                                isDirty = false,
                                                canUndo = buffer.canUndo(),
                                                canRedo = buffer.canRedo()
                                            )
                                            state.markCodeEditorRuntimeLoaded(tab.id)
                                            loading = false
                                        }
                                        .onFailure {
                                            loadError = it.message ?: Strings.editor_load_failed.strOr(context)
                                            loading = false
                                        }
                                }
                            }
                        }
                    ) {
                        Text(Strings.btn_retry.strOr(context))
                    }
                }
            }
        }
    }
}

