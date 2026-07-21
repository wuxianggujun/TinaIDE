package com.wuxianggujun.tinaide.ui.compose.viewer

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HexViewerScreen(
    filePath: String,
    onRegisterSearch: ((search: (String) -> List<Long>, goToOffset: (Long) -> Unit) -> Unit)? = null,
    onUnregisterSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val file = remember(filePath) { File(filePath) }
    val dataManager = remember(filePath) { HexFileDataManager(file) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val readOnlyMessage = stringResource(Strings.hex_read_only_file)
    val writeFailedMessage = stringResource(Strings.hex_write_failed)
    val saveFailedMessage = stringResource(Strings.hex_patch_save_failed)
    val exportFailedMessage = stringResource(Strings.hex_export_failed)
    val inspectFailedMessage = stringResource(Strings.hex_selection_inspect_failed)
    val searchFailedMessage = stringResource(Strings.hex_search_failed)
    val exportTooLargeMessage = stringResource(Strings.hex_export_range_too_large, formatFileSize(MAX_HEX_EXPORT_BYTES.toLong()))

    var state by remember(filePath) {
        mutableStateOf(HexViewerState(filePath = filePath))
    }
    var cacheVersion by remember(filePath) { mutableIntStateOf(0) }
    var contextTarget by remember(filePath) { mutableStateOf<HexContextTarget?>(null) }
    var showExportDialog by remember(filePath) { mutableStateOf(false) }
    var showSelectionInspectorDialog by remember(filePath) { mutableStateOf(false) }
    var selectionInspector by remember(filePath) { mutableStateOf<HexSelectionInspector?>(null) }
    var binaryAnalysis by remember(filePath) { mutableStateOf<HexBinaryAnalysis?>(null) }
    var isAnalysisLoading by remember(filePath) { mutableStateOf(false) }
    var showSearchPanel by remember(filePath) { mutableStateOf(initialHexSearchPanelExpanded()) }
    var showAnalysisPanel by remember(filePath) { mutableStateOf(initialHexAnalysisPanelExpanded()) }
    var showAnalysisDialog by remember(filePath) { mutableStateOf(false) }
    var showWorkbenchCommandsDialog by remember(filePath) { mutableStateOf(false) }
    var showWorkbenchReportDialog by remember(filePath) { mutableStateOf(false) }
    var workbenchCommandFinding by remember(filePath) { mutableStateOf<HexBinaryFinding?>(null) }

    fun openWorkbenchCommands(finding: HexBinaryFinding? = null) {
        workbenchCommandFinding = finding
        showWorkbenchCommandsDialog = true
    }

    fun scrollToOffset(offset: Long) {
        val targetOffset = dataManager.coerceOffset(offset)
        val targetRow = dataManager.getRowIndexForOffset(targetOffset)
        scope.launch {
            listState.animateScrollToItem(targetRow)
            if (dataManager.loadChunkForRow(targetRow)) {
                cacheVersion++
            }
        }
    }

    fun goToOffset(offset: Long, recordHistory: Boolean = true) {
        val targetOffset = dataManager.coerceOffset(offset)
        val currentOffset = state.selectedOffset
        val nextBackStack = if (recordHistory && currentOffset != targetOffset) {
            (state.gotoBackStack + currentOffset).takeLast(MAX_GOTO_HISTORY)
        } else {
            state.gotoBackStack
        }
        state = state.copy(
            currentOffset = targetOffset,
            selectedOffset = targetOffset,
            pendingNibble = "",
            gotoBackStack = nextBackStack,
            gotoForwardStack = if (recordHistory) emptyList() else state.gotoForwardStack,
            error = null
        )
        scrollToOffset(targetOffset)
    }

    fun goBackInHistory() {
        val previousOffset = state.gotoBackStack.lastOrNull() ?: return
        val currentOffset = state.selectedOffset
        state = state.copy(
            selectedOffset = previousOffset,
            currentOffset = previousOffset,
            gotoBackStack = state.gotoBackStack.dropLast(1),
            gotoForwardStack = (state.gotoForwardStack + currentOffset).takeLast(MAX_GOTO_HISTORY),
            pendingNibble = "",
            error = null
        )
        scrollToOffset(previousOffset)
    }

    fun goForwardInHistory() {
        val nextOffset = state.gotoForwardStack.lastOrNull() ?: return
        val currentOffset = state.selectedOffset
        state = state.copy(
            selectedOffset = nextOffset,
            currentOffset = nextOffset,
            gotoBackStack = (state.gotoBackStack + currentOffset).takeLast(MAX_GOTO_HISTORY),
            gotoForwardStack = state.gotoForwardStack.dropLast(1),
            pendingNibble = "",
            error = null
        )
        scrollToOffset(nextOffset)
    }

    fun goToSearchResult(index: Int) {
        val resultOffset = state.searchResults.getOrNull(index) ?: return
        state = state.copy(searchResultIndex = index)
        goToOffset(resultOffset)
    }

    fun copyRange(format: HexExportFormat) {
        val range = state.selectionRange ?: HexSelectionRange(state.selectedOffset, state.selectedOffset)
        if (range.byteCount > MAX_HEX_EXPORT_BYTES) {
            state = state.copy(error = exportTooLargeMessage)
            return
        }
        val patches = state.stagedPatches
        scope.launch {
            runCatching {
                val rawBytes = dataManager.readBytes(range.firstOffset, range.byteCount.toInt())
                val exportBytes = applyHexPatchesToRange(range, rawBytes, patches)
                formatHexExport(range, exportBytes, format)
            }.onSuccess { text ->
                clipboard.setClipEntry(
                    ClipData.newPlainText("hex-export", text).toClipEntry()
                )
                state = state.copy(error = null)
            }.onFailure {
                state = state.copy(error = exportFailedMessage)
            }
        }
    }

    fun inspectSelection() {
        val range = state.selectionRange ?: HexSelectionRange(state.selectedOffset, state.selectedOffset)
        val sampleByteCount = minOf(range.byteCount, HEX_SELECTION_INSPECT_SAMPLE_BYTES.toLong()).toInt()
        val patches = state.stagedPatches
        scope.launch {
            runCatching {
                val rawBytes = dataManager.readBytes(range.firstOffset, sampleByteCount)
                val sampleRange = if (rawBytes.isEmpty()) {
                    HexSelectionRange(range.firstOffset, range.firstOffset)
                } else {
                    HexSelectionRange(range.firstOffset, range.firstOffset + rawBytes.size - 1L)
                }
                val inspectBytes = applyHexPatchesToRange(sampleRange, rawBytes, patches)
                inspectHexSelection(range, inspectBytes)
            }.onSuccess { inspector ->
                selectionInspector = inspector
                showSelectionInspectorDialog = true
                state = state.copy(error = null)
            }.onFailure {
                state = state.copy(error = inspectFailedMessage)
            }
        }
    }

    fun savePatches() {
        val patches = state.stagedPatches
        if (patches.isEmpty()) return
        if (!dataManager.canWrite()) {
            state = state.copy(error = readOnlyMessage)
            return
        }
        scope.launch {
            runCatching {
                dataManager.writePatches(patches)
            }.onSuccess {
                cacheVersion++
                state = state.copy(
                    stagedPatches = emptyList(),
                    redoPatches = emptyList(),
                    pendingNibble = "",
                    error = null
                )
            }.onFailure {
                state = state.copy(error = saveFailedMessage)
            }
        }
    }

    fun runSearch() {
        val query = state.searchQuery
        if (query.isBlank()) {
            state = state.copy(
                searchResults = emptyList(),
                searchResultIndex = -1,
                isSearchRunning = false,
                searchError = null
            )
            return
        }
        state = state.copy(isSearchRunning = true, searchError = null)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    searchInHexFile(file, query)
                }
            }.onSuccess { results ->
                state = state.copy(
                    searchResults = results,
                    searchResultIndex = if (results.isEmpty()) -1 else 0,
                    isSearchRunning = false,
                    searchError = null
                )
                results.firstOrNull()?.let { goToOffset(it) }
            }.onFailure {
                state = state.copy(
                    searchResults = emptyList(),
                    searchResultIndex = -1,
                    isSearchRunning = false,
                    searchError = searchFailedMessage
                )
            }
        }
    }

    LaunchedEffect(filePath) {
        val fileSize = dataManager.refreshFileSize()
        binaryAnalysis = null
        isAnalysisLoading = fileSize > 0L
        state = HexViewerState(
            filePath = filePath,
            fileSize = fileSize,
            currentOffset = 0L,
            selectedOffset = 0L,
            isLoading = false
        )
        if (fileSize > 0L && dataManager.preloadAroundRow(0)) {
            cacheVersion++
        }
        if (fileSize > 0L) {
            binaryAnalysis = analyzeHexBinaryFile(file)
            isAnalysisLoading = false
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, state.fileSize) {
        if (state.fileSize > 0L) {
            state = state.copy(currentOffset = dataManager.getRowOffset(listState.firstVisibleItemIndex))
        }
    }

    LaunchedEffect(state.fileSize, onRegisterSearch) {
        if (state.fileSize > 0L && onRegisterSearch != null) {
            onRegisterSearch(
                { query -> searchInHexFile(file, query) },
                { offset -> goToOffset(offset) }
            )
        }
    }

    DisposableEffect(filePath) {
        onDispose {
            onUnregisterSearch?.invoke()
            dataManager.clearCache()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HexHeader()
        HexTopActionBar(
            state = state,
            analysis = binaryAnalysis,
            isAnalysisLoading = isAnalysisLoading,
            isSearchExpanded = showSearchPanel,
            isAnalysisPanelOpen = showAnalysisPanel,
            onToggleSearch = { showSearchPanel = !showSearchPanel },
            onToggleAnalysisPanel = { showAnalysisPanel = !showAnalysisPanel },
            onOpenCommands = { openWorkbenchCommands() }
        )
        if (showSearchPanel) {
            HexSearchPanel(
                state = state,
                onQueryChange = { query ->
                    state = state.copy(searchQuery = query, searchError = null)
                },
                onRunSearch = { runSearch() },
                onPreviousResult = {
                    if (state.searchResults.isNotEmpty()) {
                        val previousIndex = if (state.searchResultIndex <= 0) {
                            state.searchResults.lastIndex
                        } else {
                            state.searchResultIndex - 1
                        }
                        goToSearchResult(previousIndex)
                    }
                },
                onNextResult = {
                    if (state.searchResults.isNotEmpty()) {
                        val nextIndex = if (state.searchResultIndex >= state.searchResults.lastIndex) {
                            0
                        } else {
                            state.searchResultIndex + 1
                        }
                        goToSearchResult(nextIndex)
                    }
                }
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val canDockAnalysisPanel = shouldDockHexAnalysisPanel(maxWidth.value.toInt())
            val showDockedAnalysisPanel = shouldShowHexDockedAnalysisPanel(
                isUserExpanded = showAnalysisPanel,
                canDock = canDockAnalysisPanel,
                hasContent = state.fileSize > 0L
            )
            val shouldOpenAnalysisDialog = shouldOpenHexAnalysisDialog(
                isUserExpanded = showAnalysisPanel,
                canDock = canDockAnalysisPanel,
                hasContent = state.fileSize > 0L
            )

            LaunchedEffect(shouldOpenAnalysisDialog) {
                if (shouldOpenAnalysisDialog) {
                    showAnalysisDialog = true
                    showAnalysisPanel = false
                }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    when {
                        state.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        state.fileSize <= 0L -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(Strings.hex_empty_file),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> {
                            HexContent(
                                dataManager = dataManager,
                                listState = listState,
                                cacheVersion = cacheVersion,
                                selectedOffset = state.selectedOffset,
                                selectionRange = state.selectionRange,
                                pendingNibble = state.pendingNibble,
                                patchMap = remember(state.stagedPatches) {
                                    state.stagedPatches.associateBy { it.offset }
                                },
                                bookmarkedOffsets = remember(state.bookmarkedOffsets) {
                                    state.bookmarkedOffsets.toSet()
                                },
                                onCacheVersionChanged = { cacheVersion++ },
                                onOffsetSelected = { offset ->
                                    val selectedOffset = dataManager.coerceOffset(offset)
                                    state = state.copy(
                                        selectedOffset = selectedOffset,
                                        currentOffset = selectedOffset,
                                        pendingNibble = "",
                                        error = null
                                    )
                                },
                                onByteLongPressed = { target ->
                                    val selectedOffset = dataManager.coerceOffset(target.offset)
                                    contextTarget = target.copy(offset = selectedOffset)
                                    state = state.copy(
                                        selectedOffset = selectedOffset,
                                        currentOffset = selectedOffset,
                                        pendingNibble = "",
                                        error = null
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                if (showDockedAnalysisPanel) {
                    VerticalDivider()
                    HexDockedAnalysisPanel(
                        state = state,
                        analysis = binaryAnalysis,
                        isLoading = isAnalysisLoading,
                        onClose = { showAnalysisPanel = false },
                        onOpenCommands = { finding -> openWorkbenchCommands(finding) },
                        onOpenReport = { showWorkbenchReportDialog = true },
                        onGotoOffset = { offset -> goToOffset(offset) },
                        onMarkOffsets = { offsets ->
                            state = state.copy(
                                bookmarkedOffsets = markHexBookmarks(state.bookmarkedOffsets, offsets),
                                error = null
                            )
                        }
                    )
                }
            }
        }

        HexContextMenu(
            target = contextTarget,
            onDismiss = { contextTarget = null },
            onCopyOffset = { target ->
                scope.launch {
                    clipboard.setClipEntry(
                        ClipData.newPlainText("hex-offset", "0x%08X".format(target.offset)).toClipEntry()
                    )
                }
            },
            onCopyByte = { target ->
                scope.launch {
                    clipboard.setClipEntry(
                        ClipData.newPlainText("hex-byte", target.byte.toHexCellText()).toClipEntry()
                    )
                }
            },
            onCopyAscii = { target ->
                scope.launch {
                    clipboard.setClipEntry(
                        ClipData.newPlainText("hex-ascii", target.byte.toPrintableAscii()).toClipEntry()
                    )
                }
            },
            onSetSelectionStart = { target ->
                val offset = dataManager.coerceOffset(target.offset)
                state = state.copy(
                    selectionStartOffset = offset,
                    selectionEndOffset = state.selectionEndOffset ?: offset,
                    selectedOffset = offset,
                    currentOffset = offset,
                    error = null
                )
            },
            onSetSelectionEnd = { target ->
                val offset = dataManager.coerceOffset(target.offset)
                state = state.copy(
                    selectionStartOffset = state.selectionStartOffset ?: offset,
                    selectionEndOffset = offset,
                    selectedOffset = offset,
                    currentOffset = offset,
                    error = null
                )
            },
            onExportSelection = {
                showExportDialog = true
            },
            onToggleBookmark = { target ->
                val offset = dataManager.coerceOffset(target.offset)
                state = state.copy(
                    bookmarkedOffsets = toggleHexBookmark(state.bookmarkedOffsets, offset),
                    selectedOffset = offset,
                    currentOffset = offset,
                    pendingNibble = "",
                    error = null
                )
            },
            onEditHere = { target ->
                if (!dataManager.canWrite()) {
                    state = state.copy(error = readOnlyMessage)
                } else {
                    val selectedOffset = dataManager.coerceOffset(target.offset)
                    state = state.copy(
                        selectedOffset = selectedOffset,
                        currentOffset = selectedOffset,
                        isEditMode = true,
                        pendingNibble = "",
                        error = null
                    )
                }
            }
        )

        HexFooter(
            state = state,
            canEdit = state.fileSize > 0L,
            canGoBack = state.gotoBackStack.isNotEmpty(),
            canGoForward = state.gotoForwardStack.isNotEmpty(),
            onToggleEditMode = {
                if (!state.isEditMode && !dataManager.canWrite()) {
                    state = state.copy(error = readOnlyMessage)
                } else {
                    state = state.copy(
                        isEditMode = !state.isEditMode,
                        pendingNibble = "",
                        error = null
                    )
                }
            },
            onGoBack = { goBackInHistory() },
            onGoForward = { goForwardInHistory() },
            onToggleBookmark = {
                state = state.copy(
                    bookmarkedOffsets = toggleHexBookmark(state.bookmarkedOffsets, state.selectedOffset),
                    error = null
                )
            },
            onRemoveBookmark = { offset ->
                state = state.copy(
                    bookmarkedOffsets = removeHexBookmark(state.bookmarkedOffsets, offset),
                    error = null
                )
            },
            onMarkSelectionStart = {
                state = state.copy(
                    selectionStartOffset = state.selectedOffset,
                    selectionEndOffset = state.selectionEndOffset ?: state.selectedOffset,
                    error = null
                )
            },
            onMarkSelectionEnd = {
                state = state.copy(
                    selectionStartOffset = state.selectionStartOffset ?: state.selectedOffset,
                    selectionEndOffset = state.selectedOffset,
                    error = null
                )
            },
            onClearSelection = {
                state = state.copy(selectionStartOffset = null, selectionEndOffset = null, error = null)
            },
            onInspectSelection = { inspectSelection() },
            onExportSelection = {
                showExportDialog = true
            },
            onUndoPatch = {
                val history = undoLastHexPatch(state.stagedPatches, state.redoPatches)
                state = state.copy(
                    stagedPatches = history.stagedPatches,
                    redoPatches = history.redoPatches,
                    pendingNibble = "",
                    error = null
                )
            },
            onRedoPatch = {
                val history = redoLastHexPatch(state.stagedPatches, state.redoPatches)
                state = state.copy(
                    stagedPatches = history.stagedPatches,
                    redoPatches = history.redoPatches,
                    pendingNibble = "",
                    error = null
                )
            },
            onDiscardPatch = { offset ->
                state = state.copy(
                    stagedPatches = discardHexPatchAtOffset(state.stagedPatches, offset),
                    redoPatches = emptyList(),
                    pendingNibble = "",
                    error = null
                )
            },
            onSavePatches = { savePatches() },
            onDiscardPatches = {
                state = state.copy(stagedPatches = emptyList(), redoPatches = emptyList(), pendingNibble = "", error = null)
            },
            onGotoOffset = { goToOffset(it) }
        )

        if (showExportDialog) {
            ExportSelectionDialog(
                onDismiss = { showExportDialog = false },
                onFormatSelected = { format ->
                    showExportDialog = false
                    copyRange(format)
                }
            )
        }

        if (showSelectionInspectorDialog && selectionInspector != null) {
            HexSelectionInspectorDialog(
                inspector = selectionInspector!!,
                onDismiss = { showSelectionInspectorDialog = false }
            )
        }

        if (showWorkbenchCommandsDialog) {
            HexWorkbenchCommandsDialog(
                state = state,
                analysis = binaryAnalysis,
                finding = workbenchCommandFinding,
                onDismiss = {
                    showWorkbenchCommandsDialog = false
                    workbenchCommandFinding = null
                }
            )
        }

        if (showWorkbenchReportDialog) {
            HexWorkbenchReportDialog(
                state = state,
                analysis = binaryAnalysis,
                onDismiss = { showWorkbenchReportDialog = false }
            )
        }

        if (showAnalysisDialog) {
            HexAnalysisDialog(
                analysis = binaryAnalysis,
                isLoading = isAnalysisLoading,
                onDismiss = { showAnalysisDialog = false },
                onGotoOffset = { offset ->
                    showAnalysisDialog = false
                    goToOffset(offset)
                },
                onMarkOffsets = { offsets ->
                    state = state.copy(
                        bookmarkedOffsets = markHexBookmarks(state.bookmarkedOffsets, offsets),
                        error = null
                    )
                }
            )
        }

        if (state.isEditMode && state.fileSize > 0L) {
            HexKeyboard(
                onNibbleClick = { nibble ->
                    val nextNibble = state.pendingNibble + nibble
                    if (nextNibble.length == 1) {
                        state = state.copy(pendingNibble = nextNibble, error = null)
                        return@HexKeyboard
                    }

                    val byteValue = parseHexByte(nextNibble)
                    if (byteValue == null) {
                        state = state.copy(pendingNibble = "", error = writeFailedMessage)
                        return@HexKeyboard
                    }

                    val writeOffset = state.selectedOffset
                    scope.launch {
                        runCatching {
                            val originalByte = state.stagedPatches
                                .firstOrNull { it.offset == writeOffset }
                                ?.originalByte
                                ?: dataManager.getCachedByte(writeOffset)
                                ?: dataManager.readByte(writeOffset)
                            stageHexPatch(
                                patches = state.stagedPatches,
                                offset = writeOffset,
                                originalByte = originalByte,
                                newByte = byteValue
                            )
                        }.onSuccess {
                            val nextOffset = dataManager.coerceOffset(writeOffset + 1)
                            state = state.copy(
                                selectedOffset = nextOffset,
                                currentOffset = nextOffset,
                                stagedPatches = it,
                                redoPatches = emptyList(),
                                pendingNibble = "",
                                error = null
                            )
                        }.onFailure {
                            state = state.copy(pendingNibble = "", error = writeFailedMessage)
                        }
                    }
                },
                onBackspace = {
                    state = if (state.pendingNibble.isNotEmpty()) {
                        state.copy(pendingNibble = state.pendingNibble.dropLast(1), error = null)
                    } else {
                        val previousOffset = dataManager.coerceOffset(state.selectedOffset - 1)
                        state.copy(
                            selectedOffset = previousOffset,
                            currentOffset = previousOffset,
                            error = null
                        )
                    }
                },
                onClose = {
                    state = state.copy(isEditMode = false, pendingNibble = "", error = null)
                }
            )
        }
    }
}

