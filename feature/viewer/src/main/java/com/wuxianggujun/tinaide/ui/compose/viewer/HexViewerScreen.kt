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

@Composable
private fun HexHeader() {
    val headerTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(AddressColumnWidth)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Strings.hex_header_offset),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = headerTextColor
                )
            }

            VerticalDivider()

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW) { column ->
                    if (column == 4) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = column.toString(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = headerTextColor
                        )
                    }
                }
            }

            VerticalDivider()

            Box(
                modifier = Modifier
                    .width(AsciiColumnWidth)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Strings.hex_header_ascii),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = headerTextColor
                )
            }
        }
    }
}

@Composable
private fun HexTopActionBar(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    isAnalysisLoading: Boolean,
    isSearchExpanded: Boolean,
    isAnalysisPanelOpen: Boolean,
    onToggleSearch: () -> Unit,
    onToggleAnalysisPanel: () -> Unit,
    onOpenCommands: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onToggleSearch) {
                Text(
                    stringResource(
                        if (isSearchExpanded) {
                            Strings.content_desc_collapse
                        } else {
                            Strings.hex_search_label
                        }
                    )
                )
            }
            TextButton(
                onClick = onToggleAnalysisPanel,
                enabled = state.fileSize > 0L
            ) {
                Text(
                    stringResource(
                        if (isAnalysisPanelOpen) {
                            Strings.hex_workbench_hide_analysis_panel
                        } else {
                            Strings.hex_workbench_show_analysis_panel
                        }
                    )
                )
            }
            TextButton(
                onClick = onOpenCommands,
                enabled = state.fileSize > 0L
            ) {
                Text(stringResource(Strings.hex_workbench_commands_title))
            }

            when {
                isAnalysisLoading -> {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Strings.hex_analysis_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                analysis != null -> {
                    Text(
                        text = stringResource(Strings.hex_analysis_file_kind, hexFileKindLabel(analysis.fileKind)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.searchResults.isNotEmpty()) {
                Text(
                    text = stringResource(
                        Strings.hex_search_results_count,
                        state.searchResultIndex + 1,
                        state.searchResults.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HexAnalysisDialog(
    analysis: HexBinaryAnalysis?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_analysis_title)) },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                HexAnalysisPanel(
                    analysis = analysis,
                    isLoading = isLoading,
                    onGotoOffset = onGotoOffset,
                    onMarkOffsets = onMarkOffsets
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexDockedAnalysisPanel(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    isLoading: Boolean,
    onClose: () -> Unit,
    onOpenCommands: (HexBinaryFinding?) -> Unit,
    onOpenReport: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    Surface(
        modifier = Modifier
            .width(HexDockedAnalysisPanelWidth)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(Strings.hex_workbench_analysis_panel_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Strings.hex_workbench_close_analysis_panel)
                    )
                }
            }
            HorizontalDivider()
            HexWorkbenchCommandStrip(
                state = state,
                onOpenCommands = { onOpenCommands(null) }
            )
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                HexBinaryFindingsPanel(
                    analysis = analysis,
                    onGotoOffset = onGotoOffset,
                    onMarkOffsets = onMarkOffsets,
                    onOpenCommands = onOpenCommands,
                    onOpenReport = onOpenReport
                )
                HorizontalDivider()
                HexAnalysisPanel(
                    analysis = analysis,
                    isLoading = isLoading,
                    onGotoOffset = onGotoOffset,
                    onMarkOffsets = onMarkOffsets,
                    showTitle = false
                )
            }
        }
    }
}

@Composable
private fun HexWorkbenchCommandStrip(
    state: HexViewerState,
    onOpenCommands: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(Strings.hex_selected_offset, state.selectedOffset),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        state.selectionRange?.let { selectionRange ->
            Text(
                text = stringResource(
                    Strings.hex_selection_range,
                    selectionRange.firstOffset,
                    selectionRange.lastOffset,
                    selectionRange.byteCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
        TextButton(onClick = onOpenCommands) {
            Text(stringResource(Strings.hex_workbench_commands_title))
        }
    }
}

@Composable
private fun HexBinaryFindingsPanel(
    analysis: HexBinaryAnalysis?,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit,
    onOpenCommands: (HexBinaryFinding) -> Unit,
    onOpenReport: () -> Unit
) {
    val findings = remember(analysis) { buildHexBinaryFindings(analysis) }
    val markableOffsets = remember(findings) { findings.mapNotNull { finding -> finding.offset }.distinct() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(Strings.hex_workbench_findings_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (findings.isNotEmpty()) {
                Text(
                    text = stringResource(Strings.hex_workbench_findings_count, findings.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (markableOffsets.isNotEmpty()) {
                TextButton(onClick = { onMarkOffsets(markableOffsets) }) {
                    Text(stringResource(Strings.hex_bookmark_mark_all))
                }
            }
            TextButton(
                onClick = onOpenReport,
                enabled = analysis != null
            ) {
                Text(stringResource(Strings.hex_workbench_reports_title))
            }
        }
        if (findings.isEmpty()) {
            Text(
                text = stringResource(Strings.hex_workbench_findings_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            findings.take(MAX_WORKBENCH_FINDING_PANEL_ITEMS).forEachIndexed { index, finding ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                HexBinaryFindingRow(
                    finding = finding,
                    onGotoOffset = onGotoOffset,
                    onOpenCommands = onOpenCommands
                )
            }
        }
    }
}

@Composable
private fun HexBinaryFindingRow(
    finding: HexBinaryFinding,
    onGotoOffset: (Long) -> Unit,
    onOpenCommands: (HexBinaryFinding) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = hexBinaryFindingSeverityLabel(finding.severity),
                style = MaterialTheme.typography.labelSmall,
                color = hexBinaryFindingSeverityColor(finding.severity),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = hexBinaryFindingKindLabel(finding.kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onOpenCommands(finding) }) {
                Text(stringResource(Strings.hex_workbench_commands_title))
            }
            finding.offset?.let { offset ->
                TextButton(onClick = { onGotoOffset(offset) }) {
                    Text(stringResource(Strings.hex_workbench_goto_finding))
                }
            }
        }
        Text(
            text = finding.primary.compactForAnalysisPanel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        finding.secondary?.takeIf { it.isNotBlank() }?.let { secondary ->
            Text(
                text = secondary.compactForAnalysisPanel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HexWorkbenchCommandsDialog(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    finding: HexBinaryFinding?,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    fun copyCommand(label: String, command: String) {
        scope.launch {
            clipboard.setClipEntry(
                ClipData.newPlainText(label, command).toClipEntry()
            )
        }
    }

    val navigationScript = formatHexNavigationScript(state.selectedOffset)
    val selectionScript = state.selectionRange?.let { formatHexSelectionDumpScript(it) }
    val patchScript = formatHexPatchScript(state.stagedPatches)
    val fallbackFinding = remember(analysis) { buildHexBinaryFindings(analysis, maxCount = 1).firstOrNull() }
    val activeFinding = finding ?: fallbackFinding
    val workbenchScript = formatHexWorkbenchScript(
        selectedOffset = state.selectedOffset,
        selectionRange = state.selectionRange,
        patches = state.stagedPatches
    )
    val reverseActions = remember(state.selectedOffset, state.selectionRange, analysis, activeFinding) {
        buildHexReverseActions(
            selectedOffset = state.selectedOffset,
            selectionRange = state.selectionRange,
            analysis = analysis,
            finding = activeFinding
        )
    }
    val readOnlyAnalysisScript = reverseActions.actionContent(HexReverseActionKind.READ_ONLY_ANALYSIS)
    val disassemblyPreviewScript = reverseActions.actionContent(HexReverseActionKind.DISASSEMBLY_PREVIEW)
    val fridaHookTemplate = reverseActions.actionContent(HexReverseActionKind.FRIDA_HOOK)
    val lldbBreakpointTemplate = reverseActions.actionContent(HexReverseActionKind.LLDB_BREAKPOINT)

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_workbench_commands_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.hex_workbench_commands_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        activeFinding?.let { selectedFinding ->
                            Text(
                                text = stringResource(
                                    Strings.hex_workbench_active_finding,
                                    hexBinaryFindingKindLabel(selectedFinding.kind),
                                    selectedFinding.primary.compactForAnalysisPanel()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_navigation_script),
                    script = navigationScript,
                    onCopy = { copyCommand("hex-r2-navigation", navigationScript) }
                )
                if (selectionScript == null) {
                    TinaDialogCard {
                        Text(
                            text = stringResource(Strings.hex_workbench_commands_no_selection),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    HexCommandSnippetCard(
                        title = stringResource(Strings.hex_workbench_selection_script),
                        script = selectionScript,
                        onCopy = { copyCommand("hex-r2-selection", selectionScript) }
                    )
                }
                if (patchScript.isBlank()) {
                    TinaDialogCard {
                        Text(
                            text = stringResource(Strings.hex_workbench_commands_no_patches),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    HexCommandSnippetCard(
                        title = stringResource(Strings.hex_workbench_patch_script),
                        script = patchScript,
                        onCopy = { copyCommand("hex-r2-patches", patchScript) }
                    )
                }
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_full_script),
                    script = workbenchScript,
                    onCopy = { copyCommand("hex-r2-workbench", workbenchScript) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_read_only_analysis_script),
                    script = readOnlyAnalysisScript,
                    onCopy = { copyCommand("hex-r2-read-only-analysis", readOnlyAnalysisScript) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_disassembly_preview_script),
                    script = disassemblyPreviewScript,
                    onCopy = { copyCommand("hex-r2-disassembly-preview", disassemblyPreviewScript) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_frida_hook_template),
                    script = fridaHookTemplate,
                    onCopy = { copyCommand("hex-frida-hook-template", fridaHookTemplate) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_lldb_breakpoint_template),
                    script = lldbBreakpointTemplate,
                    onCopy = { copyCommand("hex-lldb-breakpoint-template", lldbBreakpointTemplate) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexWorkbenchReportDialog(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val report = remember(analysis) { buildHexJniAnalysisReport(analysis) }
    val reverseActions = remember(state.selectedOffset, state.selectionRange, analysis) {
        buildHexReverseActions(
            selectedOffset = state.selectedOffset,
            selectionRange = state.selectionRange,
            analysis = analysis
        )
    }
    val markdownReport = reverseActions.actionContent(HexReverseActionKind.JNI_MARKDOWN_REPORT)
    val jsonReport = reverseActions.actionContent(HexReverseActionKind.JNI_JSON_REPORT)

    fun copyReport(label: String, reportText: String) {
        scope.launch {
            clipboard.setClipEntry(
                ClipData.newPlainText(label, reportText).toClipEntry()
            )
        }
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_workbench_reports_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Text(
                        text = stringResource(
                            Strings.hex_workbench_reports_summary,
                            report.nativeMethods.size,
                            report.nativeLibraries.size,
                            report.jniHints.size,
                            report.nativeApis.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_markdown_report),
                    script = markdownReport,
                    copyLabel = stringResource(Strings.hex_workbench_copy_report),
                    onCopy = { copyReport("hex-jni-report-markdown", markdownReport) }
                )
                HexCommandSnippetCard(
                    title = stringResource(Strings.hex_workbench_json_report),
                    script = jsonReport,
                    copyLabel = stringResource(Strings.hex_workbench_copy_report),
                    onCopy = { copyReport("hex-jni-report-json", jsonReport) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexCommandSnippetCard(
    title: String,
    script: String,
    copyLabel: String? = null,
    onCopy: () -> Unit
) {
    TinaDialogCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCopy) {
                    Text(copyLabel ?: stringResource(Strings.hex_workbench_copy_script))
                }
            }
            Text(
                text = script,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun HexSearchPanel(
    state: HexViewerState,
    onQueryChange: (String) -> Unit,
    onRunSearch: () -> Unit,
    onPreviousResult: () -> Unit,
    onNextResult: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(Strings.hex_search_label)) },
                    placeholder = { Text(stringResource(Strings.hex_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onRunSearch,
                    enabled = !state.isSearchRunning
                ) {
                    Text(stringResource(Strings.hex_search_run))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onPreviousResult,
                    enabled = state.searchResults.isNotEmpty()
                ) {
                    Text(stringResource(Strings.hex_search_previous))
                }
                TextButton(
                    onClick = onNextResult,
                    enabled = state.searchResults.isNotEmpty()
                ) {
                    Text(stringResource(Strings.hex_search_next))
                }

                val resultText = when {
                    state.isSearchRunning -> stringResource(Strings.hex_search_running)
                    state.searchResults.isEmpty() && state.searchQuery.isNotBlank() -> {
                        stringResource(Strings.hex_search_results_empty)
                    }
                    state.searchResults.isNotEmpty() -> {
                        stringResource(
                            Strings.hex_search_results_count,
                            state.searchResultIndex + 1,
                            state.searchResults.size
                        )
                    }
                    else -> stringResource(Strings.hex_search_hint)
                }
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                state.searchError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun HexAnalysisPanel(
    analysis: HexBinaryAnalysis?,
    isLoading: Boolean,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit,
    showTitle: Boolean = true
) {
    var showStringsDialog by remember(analysis) { mutableStateOf(false) }
    var showSectionsDialog by remember(analysis) { mutableStateOf(false) }
    var showProgramHeadersDialog by remember(analysis) { mutableStateOf(false) }
    var showSectionSegmentsDialog by remember(analysis) { mutableStateOf(false) }
    var showSectionEntropyDialog by remember(analysis) { mutableStateOf(false) }
    var showNotesDialog by remember(analysis) { mutableStateOf(false) }
    var showSymbolsDialog by remember(analysis) { mutableStateOf(false) }
    var showDynamicEntriesDialog by remember(analysis) { mutableStateOf(false) }
    var showDynamicFlagsDialog by remember(analysis) { mutableStateOf(false) }
    var showEntropyDialog by remember(analysis) { mutableStateOf(false) }
    var showInitArrayDialog by remember(analysis) { mutableStateOf(false) }
    var showRelocationsDialog by remember(analysis) { mutableStateOf(false) }
    var showLinkageDialog by remember(analysis) { mutableStateOf(false) }
    var showDynamicLinkerStepsDialog by remember(analysis) { mutableStateOf(false) }
    var showRiskFindingsDialog by remember(analysis) { mutableStateOf(false) }
    var showNativeApiHintsDialog by remember(analysis) { mutableStateOf(false) }
    var showJniHintsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexStringsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexTypesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexProtosDialog by remember(analysis) { mutableStateOf(false) }
    var showDexFieldsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexMethodsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexClassesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexClassDataDialog by remember(analysis) { mutableStateOf(false) }
    var showDexCodeItemsDialog by remember(analysis) { mutableStateOf(false) }
    var showDexCallReferencesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexStringReferencesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexFieldReferencesDialog by remember(analysis) { mutableStateOf(false) }
    var showDexMapDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveEntriesDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveZipStructureDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveManifestDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveResourcesDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveNativeLibrariesDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveDexDialog by remember(analysis) { mutableStateOf(false) }
    var showArchiveSigningBlockDialog by remember(analysis) { mutableStateOf(false) }
    var showFingerprintDialog by remember(analysis) { mutableStateOf(false) }
    var showByteFrequencyDialog by remember(analysis) { mutableStateOf(false) }
    var showRepeatedByteRunsDialog by remember(analysis) { mutableStateOf(false) }
    var showMagicSignaturesDialog by remember(analysis) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showTitle) {
                    Text(
                        text = stringResource(Strings.hex_analysis_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Strings.hex_analysis_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (analysis != null) {
                    Text(
                        text = stringResource(Strings.hex_analysis_file_kind, hexFileKindLabel(analysis.fileKind)),
                        style = MaterialTheme.typography.labelSmall
                    )
                    analysis.fingerprint?.let { fingerprint ->
                        TextButton(onClick = { showFingerprintDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_fingerprint_preview,
                                    fingerprint.sha256.toShortHashPreview()
                                )
                            )
                        }
                    }
                    analysis.byteFrequency?.let { byteFrequency ->
                        val topByteLabel = byteFrequency.topBytes.firstOrNull()?.byteValue?.toHexByteLabel()
                            ?: stringResource(Strings.hex_byte_frequency_none)
                        TextButton(onClick = { showByteFrequencyDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_byte_frequency_preview,
                                    byteFrequency.uniqueByteValues,
                                    topByteLabel
                                )
                            )
                        }
                    }
                    if (analysis.repeatedByteRuns.isNotEmpty()) {
                        val longestRun = analysis.repeatedByteRuns.maxByOrNull { it.length }
                        TextButton(onClick = { showRepeatedByteRunsDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_repeated_runs_preview,
                                    analysis.repeatedByteRuns.size,
                                    longestRun?.length ?: 0L
                                )
                            )
                        }
                    }
                    if (analysis.magicSignatures.isNotEmpty()) {
                        TextButton(onClick = { showMagicSignaturesDialog = true }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_magic_signatures_preview,
                                    analysis.magicSignatures.size
                                )
                            )
                        }
                    }
                    analysis.entropy.maxByOrNull { it.entropy }?.let { maxEntropy ->
                        Text(
                            text = stringResource(Strings.hex_analysis_entropy_max, maxEntropy.entropy),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        text = stringResource(Strings.hex_analysis_strings_count, analysis.strings.size),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (!analysis?.entropyVisualBuckets.isNullOrEmpty()) {
                val visualBuckets = analysis!!.entropyVisualBuckets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_entropy_map_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { showEntropyDialog = true }) {
                        Text(
                            stringResource(
                                Strings.hex_entropy_show_all,
                                visualBuckets.size
                            )
                        )
                    }
                    visualBuckets.forEach { bucket ->
                        EntropyBucketBar(
                            bucket = bucket,
                            onClick = { onGotoOffset(bucket.startOffset) }
                        )
                    }
                }
            }

            analysis?.dex?.let { dex ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_dex_summary,
                            dex.version,
                            dex.fileSizeFromHeader,
                            dex.headerSize
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_dex_counts,
                            dex.stringIdsSize,
                            dex.typeIdsSize,
                            dex.protoIdsSize,
                            dex.fieldIdsSize,
                            dex.methodIdsSize,
                            dex.classDefsSize
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dex.nativeMethodCount > 0) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_dex_native_methods,
                                dex.nativeMethodCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (dex.stringEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexStringsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_strings_show_all,
                                    dex.stringEntries.size
                                )
                            )
                        }
                    }
                    if (dex.typeEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexTypesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_types_show_all,
                                    dex.typeEntries.size
                                )
                            )
                        }
                    }
                    if (dex.protoEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexProtosDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_protos_show_all,
                                    dex.protoEntries.size
                                )
                            )
                        }
                    }
                    if (dex.fieldEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexFieldsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_fields_show_all,
                                    dex.fieldEntries.size
                                )
                            )
                        }
                    }
                    if (dex.methodEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexMethodsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_methods_show_all,
                                    dex.methodEntries.size
                                )
                            )
                        }
                    }
                    if (dex.classDefEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexClassesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_classes_show_all,
                                    dex.classDefEntries.size
                                )
                            )
                        }
                    }
                    if (dex.classDataMethodEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexClassDataDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_class_data_methods_show_all,
                                    dex.classDataMethodEntries.size
                                )
                            )
                        }
                    }
                    if (dex.codeItemEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexCodeItemsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_code_items_show_all,
                                    dex.codeItemEntries.size
                                )
                            )
                        }
                    }
                    if (dex.callReferenceEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexCallReferencesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_call_references_show_all,
                                    dex.callReferenceEntries.size
                                )
                            )
                        }
                    }
                    if (dex.stringReferenceEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexStringReferencesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_string_references_show_all,
                                    dex.stringReferenceEntries.size
                                )
                            )
                        }
                    }
                    if (dex.fieldReferenceEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexFieldReferencesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_field_references_show_all,
                                    dex.fieldReferenceEntries.size
                                )
                            )
                        }
                    }
                    if (dex.mapEntries.isNotEmpty()) {
                        TextButton(onClick = { showDexMapDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_dex_map_show_all,
                                    dex.mapEntries.size
                                )
                            )
                        }
                    }
                    dex.stringEntries.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { entry ->
                        TextButton(onClick = { onGotoOffset(entry.dataOffset) }) {
                            Text(
                                stringResource(
                                    Strings.hex_analysis_dex_string_item,
                                    entry.index,
                                    entry.value.compactForAnalysisPanel()
                                )
                            )
                        }
                    }
                }
            }

            analysis?.archive?.let { archive ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = { showArchiveEntriesDialog = true }) {
                        Text(
                            stringResource(
                                Strings.hex_archive_entries_show_all,
                                archive.entries.size
                            )
                        )
                    }
                    archive.zipStructure?.let { structure ->
                        TextButton(onClick = { showArchiveZipStructureDialog = true }) {
                            Text(stringResource(Strings.hex_archive_zip_structure_show))
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_zip_structure_preview,
                                structure.entryCount,
                                structure.centralDirectoryOffset,
                                structure.eocdOffset
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_archive_counts,
                            archive.dexFiles.size,
                            archive.nativeLibraries.size,
                            archive.resources.size,
                            archive.signatureFiles.size
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (archive.embeddedDexFiles.isNotEmpty()) {
                        TextButton(onClick = { showArchiveDexDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_archive_dex_show_all,
                                    archive.embeddedDexFiles.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_dex_preview,
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.protoIdsSize },
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.fieldIdsSize },
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.methodIdsSize },
                                archive.embeddedDexFiles.sumOf { entry -> entry.dex.classDefsSize }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (archive.signingBlockEntries.isNotEmpty()) {
                        TextButton(onClick = { showArchiveSigningBlockDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_archive_signing_block_show_all,
                                    archive.signingBlockEntries.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_signing_block_preview,
                                archive.signingBlockEntries.signingBlockNamesPreview()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    archive.manifest?.let { manifest ->
                        TextButton(onClick = { onGotoOffset(manifest.localHeaderOffset) }) {
                            Text(stringResource(Strings.hex_analysis_archive_manifest))
                        }
                    }
                    archive.manifestSummary?.let { manifest ->
                        TextButton(onClick = { showArchiveManifestDialog = true }) {
                            Text(stringResource(Strings.hex_archive_manifest_show))
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_manifest_preview,
                                manifest.packageName ?: stringResource(Strings.hex_archive_manifest_package_unknown),
                                manifest.permissions.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    archive.resourcesSummary?.let { resources ->
                        TextButton(onClick = { showArchiveResourcesDialog = true }) {
                            Text(stringResource(Strings.hex_archive_resources_show))
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_resources_preview,
                                resources.packages.size,
                                resources.typeSpecCount,
                                resources.typeChunkCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (archive.nativeLibrarySummaries.isNotEmpty()) {
                        TextButton(onClick = { showArchiveNativeLibrariesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_archive_native_show_all,
                                    archive.nativeLibrarySummaries.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_native_summary_preview,
                                archive.nativeLibrarySummaries.nativeLibraryAbiPreview(),
                                archive.nativeLibrarySummaries.sumOf { entry -> entry.obfuscationMarkers.size }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (archive.nativeLibraries.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_archive_native_preview,
                                archive.nativeLibraries.archiveEntryNamesPreview()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    archive.dexFiles.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { entry ->
                        TextButton(onClick = { onGotoOffset(entry.localHeaderOffset) }) {
                            Text(
                                stringResource(
                                    Strings.hex_analysis_archive_entry_item,
                                    entry.name.compactForAnalysisPanel()
                                )
                            )
                        }
                    }
                }
            }

            analysis?.elf?.let { elf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_elf_summary,
                            if (elf.is64Bit) 64 else 32,
                            hexEndianLabel(elf.endian),
                            elf.machineName
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val entryFileOffset = elf.entryFileOffset
                    if (entryFileOffset != null) {
                        TextButton(onClick = { onGotoOffset(entryFileOffset) }) {
                            Text(
                                stringResource(
                                    Strings.hex_analysis_elf_entry_mapped,
                                    elf.entryPoint,
                                    entryFileOffset
                                )
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(Strings.hex_analysis_elf_entry, elf.entryPoint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(
                            Strings.hex_analysis_elf_sections,
                            elf.sectionHeaderCount,
                            elf.programHeaderCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (elf.sections.isNotEmpty()) {
                        TextButton(onClick = { showSectionsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_sections_show_all,
                                    elf.sections.size
                                )
                            )
                        }
                    }
                    if (elf.programHeaders.isNotEmpty()) {
                        TextButton(onClick = { showProgramHeadersDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_program_headers_show_all,
                                    elf.programHeaders.size
                                )
                            )
                        }
                    }
                    if (elf.sectionSegmentMappings.isNotEmpty()) {
                        TextButton(onClick = { showSectionSegmentsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_section_segments_show_all,
                                    elf.sectionSegmentMappings.size
                                )
                            )
                        }
                    }
                    if (elf.sectionEntropyEntries.isNotEmpty()) {
                        TextButton(onClick = { showSectionEntropyDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_section_entropy_show_all,
                                    elf.sectionEntropyEntries.size
                                )
                            )
                        }
                    }
                    if (elf.noteEntries.isNotEmpty()) {
                        TextButton(onClick = { showNotesDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_notes_show_all,
                                    elf.noteEntries.size
                                )
                            )
                        }
                    }
                    elf.buildId?.let { buildId ->
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_build_id_preview,
                                buildId.descriptionHex.compactForAnalysisPanel()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (elf.initArrayEntries.isNotEmpty()) {
                        TextButton(onClick = { showInitArrayDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_init_array_show_all,
                                    elf.initArrayEntries.size
                                )
                            )
                        }
                    }
                    if (elf.relocations.isNotEmpty()) {
                        TextButton(onClick = { showRelocationsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_relocations_show_all,
                                    elf.relocations.size
                                )
                            )
                        }
                    }
                    if (elf.linkageEntries.isNotEmpty()) {
                        val pltEntries = elf.linkageEntries.count { entry ->
                            entry.entryKind == HexElfLinkageEntryKind.PLT
                        }
                        val lazyEntries = elf.linkageEntries.count { entry ->
                            entry.bindingMode == HexElfLinkageBindingMode.LAZY
                        }
                        TextButton(onClick = { showLinkageDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_linkage_show_all,
                                    elf.linkageEntries.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_linkage_preview,
                                pltEntries,
                                elf.linkageEntries.count { entry ->
                                    entry.slotSectionName?.contains("got", ignoreCase = true) == true ||
                                        entry.entryKind == HexElfLinkageEntryKind.GOT
                                },
                                lazyEntries
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (elf.dynamicLinkerSteps.isNotEmpty()) {
                    val loadingSteps = elf.dynamicLinkerSteps.count { step ->
                        step.type == HexElfDynamicLinkerStepType.MAP_LOAD_SEGMENTS ||
                            step.type == HexElfDynamicLinkerStepType.LOAD_NEEDED_LIBRARIES
                    }
                    val bindingSteps = elf.dynamicLinkerSteps.count { step ->
                        step.type == HexElfDynamicLinkerStepType.RESOLVE_NOW_BINDINGS ||
                            step.type == HexElfDynamicLinkerStepType.ENABLE_LAZY_PLT
                    }
                    val entrypointSteps = elf.dynamicLinkerSteps.count { step ->
                        step.type == HexElfDynamicLinkerStepType.CALL_INIT_ARRAY ||
                            step.type == HexElfDynamicLinkerStepType.EXPOSE_JNI_ENTRYPOINTS
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showDynamicLinkerStepsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_loader_steps_show_all,
                                    elf.dynamicLinkerSteps.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_loader_steps_preview,
                                loadingSteps,
                                bindingSteps,
                                entrypointSteps
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (elf.riskFindings.isNotEmpty()) {
                    val highRisks = elf.riskFindings.count { finding ->
                        finding.severity == HexElfRiskSeverity.HIGH
                    }
                    val warningRisks = elf.riskFindings.count { finding ->
                        finding.severity == HexElfRiskSeverity.WARNING
                    }
                    val infoRisks = elf.riskFindings.count { finding ->
                        finding.severity == HexElfRiskSeverity.INFO
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showRiskFindingsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_risks_show_all,
                                    elf.riskFindings.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_risk_preview,
                                highRisks,
                                warningRisks,
                                infoRisks
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (highRisks > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (elf.hardeningChecks.isNotEmpty()) {
                    val enabledChecks = elf.hardeningChecks.count { check -> check.enabled }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_elf_hardening_summary,
                                enabledChecks,
                                elf.hardeningChecks.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (enabledChecks == elf.hardeningChecks.size) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        elf.hardeningChecks.forEach { check ->
                            val evidenceOffset = check.evidenceFileOffset
                            val text = stringResource(
                                Strings.hex_elf_hardening_item,
                                elfHardeningTypeLabel(check.type),
                                elfHardeningStatusLabel(check.enabled)
                            )
                            if (evidenceOffset != null) {
                                TextButton(onClick = { onGotoOffset(evidenceOffset) }) {
                                    Text(text)
                                }
                            } else {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (elf.dynamicStringEntries.isNotEmpty() || elf.dynamicFlagEntries.isNotEmpty()) {
                    val neededLibraries = elf.neededLibraries
                    val runtimeSearchPaths = elf.runtimeSearchPaths
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_elf_dynamic_counts,
                                neededLibraries.size,
                                elf.soname?.value ?: stringResource(Strings.hex_elf_dynamic_soname_empty),
                                runtimeSearchPaths.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (elf.dynamicStringEntries.isNotEmpty()) {
                            TextButton(onClick = { showDynamicEntriesDialog = true }) {
                                Text(
                                    stringResource(
                                        Strings.hex_elf_dynamic_show_all,
                                        elf.dynamicStringEntries.size
                                    )
                                )
                            }
                        }
                        if (elf.dynamicFlagEntries.isNotEmpty()) {
                            TextButton(onClick = { showDynamicFlagsDialog = true }) {
                                Text(
                                    stringResource(
                                        Strings.hex_elf_dynamic_flags_show_all,
                                        elf.dynamicFlagEntries.size
                                    )
                                )
                            }
                        }
                        if (neededLibraries.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_needed_preview,
                                    neededLibraries.dynamicValuesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (runtimeSearchPaths.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_search_path_preview,
                                    runtimeSearchPaths.dynamicValuesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (elf.dynamicSymbols.isNotEmpty()) {
                    val importedSymbols = elf.importedSymbols
                    val exportedSymbols = elf.exportedSymbols
                    val jniSymbols = elf.jniSymbols
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_elf_symbol_counts,
                                importedSymbols.size,
                                exportedSymbols.size,
                                jniSymbols.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { showSymbolsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_symbols_show_all,
                                    elf.dynamicSymbols.size
                                )
                            )
                        }
                        if (importedSymbols.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_imports_preview,
                                    importedSymbols.symbolNamesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (exportedSymbols.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_exports_preview,
                                    exportedSymbols.symbolNamesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (jniSymbols.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_jni_preview,
                                    jniSymbols.symbolNamesPreview()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        (exportedSymbols + jniSymbols)
                            .asSequence()
                            .filter { symbol -> symbol.fileOffset != null }
                            .distinctBy { symbol -> symbol.name }
                            .take(MAX_ANALYSIS_PANEL_ITEMS)
                            .forEach { symbol ->
                                val fileOffset = symbol.fileOffset ?: return@forEach
                                TextButton(onClick = { onGotoOffset(fileOffset) }) {
                                    Text(
                                        stringResource(
                                            Strings.hex_analysis_symbol_with_offset,
                                            symbol.name.compactForAnalysisPanel(),
                                            fileOffset
                                        )
                                    )
                                }
                            }
                    }
                }

                if (elf.nativeApiHints.isNotEmpty()) {
                    val loaderHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.DYNAMIC_LOADING
                    }
                    val memoryHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.MEMORY_PROTECTION
                    }
                    val processHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.PROCESS_CONTROL
                    }
                    val networkHints = elf.nativeApiHints.count { hint ->
                        hint.category == HexElfNativeApiCategory.NETWORK
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showNativeApiHintsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_native_api_hints_show_all,
                                    elf.nativeApiHints.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_native_api_preview,
                                loaderHints,
                                memoryHints,
                                processHints,
                                networkHints
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (elf.jniRegistrationHints.isNotEmpty()) {
                    val registerNativeHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.REGISTER_NATIVES_SYMBOL ||
                            hint.type == HexElfJniRegistrationHintType.REGISTER_NATIVES_STRING
                    }
                    val entrypointHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.JNI_ONLOAD_ENTRY ||
                            hint.type == HexElfJniRegistrationHintType.JNI_ONUNLOAD_ENTRY
                    }
                    val staticExportHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.STATIC_JNI_EXPORT
                    }
                    val descriptorHints = elf.jniRegistrationHints.count { hint ->
                        hint.type == HexElfJniRegistrationHintType.JAVA_CLASS_DESCRIPTOR ||
                            hint.type == HexElfJniRegistrationHintType.JNI_METHOD_SIGNATURE
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = { showJniHintsDialog = true }) {
                            Text(
                                stringResource(
                                    Strings.hex_elf_jni_hints_show_all,
                                    elf.jniRegistrationHints.size
                                )
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_analysis_jni_hints_preview,
                                registerNativeHints,
                                entrypointHints,
                                staticExportHints,
                                descriptorHints
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!analysis?.obfuscationFindings.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_obfuscation_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val obfuscationOffsets = analysis!!.obfuscationFindings.mapNotNull { finding -> finding.offset }
                    if (obfuscationOffsets.isNotEmpty()) {
                        TextButton(onClick = { onMarkOffsets(obfuscationOffsets) }) {
                            Text(stringResource(Strings.hex_bookmark_mark_all))
                        }
                    }
                    analysis!!.obfuscationFindings.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { finding ->
                        val label = hexObfuscationFindingLabel(finding.type)
                        val confidence = hexFindingConfidenceLabel(finding.confidence)
                        val text = stringResource(
                            Strings.hex_obfuscation_item,
                            label,
                            confidence,
                            finding.evidence.compactForAnalysisPanel()
                        )
                        if (finding.offset != null) {
                            TextButton(onClick = { onGotoOffset(finding.offset) }) {
                                Text(text)
                            }
                        } else {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!analysis?.signals.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_analysis_signals_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val signalOffsets = analysis!!.signals.mapNotNull { signal -> signal.offset }
                    if (signalOffsets.isNotEmpty()) {
                        TextButton(onClick = { onMarkOffsets(signalOffsets) }) {
                            Text(stringResource(Strings.hex_bookmark_mark_all))
                        }
                    }
                    analysis!!.signals.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { signal ->
                        val label = hexSignalLabel(signal.type)
                        if (signal.offset != null) {
                            TextButton(onClick = { onGotoOffset(signal.offset) }) {
                                Text(stringResource(Strings.hex_analysis_signal_with_offset, label, signal.offset))
                            }
                        } else {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!analysis?.strings.isNullOrEmpty()) {
                val analysisStrings = analysis!!.strings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_analysis_strings_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { showStringsDialog = true }) {
                        Text(
                            stringResource(
                                Strings.hex_analysis_strings_show_all,
                                analysisStrings.size
                            )
                        )
                    }
                    analysisStrings.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { entry ->
                        TextButton(onClick = { onGotoOffset(entry.offset) }) {
                            Text(
                                text = stringResource(
                                    Strings.hex_analysis_string_item,
                                    entry.offset,
                                    stringEncodingLabel(entry.encoding),
                                    entry.value.compactForAnalysisPanel()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStringsDialog && analysis != null) {
        StringsListDialog(
            entries = analysis.strings,
            onDismiss = { showStringsDialog = false },
            onGotoOffset = { offset ->
                showStringsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showFingerprintDialog && analysis?.fingerprint != null) {
        HexFingerprintDialog(
            fingerprint = analysis.fingerprint,
            onDismiss = { showFingerprintDialog = false }
        )
    }

    if (showByteFrequencyDialog && analysis?.byteFrequency != null) {
        HexByteFrequencyDialog(
            byteFrequency = analysis.byteFrequency,
            onDismiss = { showByteFrequencyDialog = false }
        )
    }

    if (showRepeatedByteRunsDialog && !analysis?.repeatedByteRuns.isNullOrEmpty()) {
        HexRepeatedByteRunsDialog(
            runs = analysis!!.repeatedByteRuns,
            onDismiss = { showRepeatedByteRunsDialog = false },
            onGotoOffset = { offset ->
                showRepeatedByteRunsDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showMagicSignaturesDialog && !analysis?.magicSignatures.isNullOrEmpty()) {
        HexMagicSignaturesDialog(
            matches = analysis!!.magicSignatures,
            onDismiss = { showMagicSignaturesDialog = false },
            onGotoOffset = { offset ->
                showMagicSignaturesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexStringsDialog && analysis?.dex != null) {
        DexStringsDialog(
            entries = analysis.dex.stringEntries,
            onDismiss = { showDexStringsDialog = false },
            onGotoOffset = { offset ->
                showDexStringsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexTypesDialog && analysis?.dex != null) {
        DexTypesDialog(
            entries = analysis.dex.typeEntries,
            onDismiss = { showDexTypesDialog = false },
            onGotoOffset = { offset ->
                showDexTypesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexProtosDialog && analysis?.dex != null) {
        DexProtosDialog(
            entries = analysis.dex.protoEntries,
            onDismiss = { showDexProtosDialog = false },
            onGotoOffset = { offset ->
                showDexProtosDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexFieldsDialog && analysis?.dex != null) {
        DexFieldsDialog(
            entries = analysis.dex.fieldEntries,
            onDismiss = { showDexFieldsDialog = false },
            onGotoOffset = { offset ->
                showDexFieldsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexMethodsDialog && analysis?.dex != null) {
        DexMethodsDialog(
            entries = analysis.dex.methodEntries,
            onDismiss = { showDexMethodsDialog = false },
            onGotoOffset = { offset ->
                showDexMethodsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexClassesDialog && analysis?.dex != null) {
        DexClassesDialog(
            entries = analysis.dex.classDefEntries,
            onDismiss = { showDexClassesDialog = false },
            onGotoOffset = { offset ->
                showDexClassesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexClassDataDialog && analysis?.dex != null) {
        DexClassDataMethodsDialog(
            entries = analysis.dex.classDataMethodEntries,
            onDismiss = { showDexClassDataDialog = false },
            onGotoOffset = { offset ->
                showDexClassDataDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexCodeItemsDialog && analysis?.dex != null) {
        DexCodeItemsDialog(
            entries = analysis.dex.codeItemEntries,
            onDismiss = { showDexCodeItemsDialog = false },
            onGotoOffset = { offset ->
                showDexCodeItemsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDexCallReferencesDialog && analysis?.dex != null) {
        DexCallReferencesDialog(
            entries = analysis.dex.callReferenceEntries,
            onDismiss = { showDexCallReferencesDialog = false },
            onGotoOffset = { offset ->
                showDexCallReferencesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexStringReferencesDialog && analysis?.dex != null) {
        DexStringReferencesDialog(
            entries = analysis.dex.stringReferenceEntries,
            onDismiss = { showDexStringReferencesDialog = false },
            onGotoOffset = { offset ->
                showDexStringReferencesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexFieldReferencesDialog && analysis?.dex != null) {
        DexFieldReferencesDialog(
            entries = analysis.dex.fieldReferenceEntries,
            onDismiss = { showDexFieldReferencesDialog = false },
            onGotoOffset = { offset ->
                showDexFieldReferencesDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showDexMapDialog && analysis?.dex != null) {
        DexMapEntriesDialog(
            entries = analysis.dex.mapEntries,
            onDismiss = { showDexMapDialog = false },
            onGotoOffset = { offset ->
                showDexMapDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveEntriesDialog && analysis?.archive != null) {
        ArchiveEntriesDialog(
            entries = analysis.archive.entries,
            onDismiss = { showArchiveEntriesDialog = false },
            onGotoOffset = { offset ->
                showArchiveEntriesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveZipStructureDialog && analysis?.archive?.zipStructure != null) {
        ArchiveZipStructureDialog(
            structure = analysis.archive.zipStructure,
            onDismiss = { showArchiveZipStructureDialog = false },
            onGotoOffset = { offset ->
                showArchiveZipStructureDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveManifestDialog && analysis?.archive?.manifestSummary != null) {
        ArchiveManifestDialog(
            manifest = analysis.archive.manifestSummary,
            onDismiss = { showArchiveManifestDialog = false },
            onGotoOffset = { offset ->
                showArchiveManifestDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveResourcesDialog && analysis?.archive?.resourcesSummary != null) {
        ArchiveResourcesDialog(
            resources = analysis.archive.resourcesSummary,
            onDismiss = { showArchiveResourcesDialog = false },
            onGotoOffset = { offset ->
                showArchiveResourcesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveNativeLibrariesDialog && analysis?.archive?.nativeLibrarySummaries?.isNotEmpty() == true) {
        ArchiveNativeLibrariesDialog(
            entries = analysis.archive.nativeLibrarySummaries,
            onDismiss = { showArchiveNativeLibrariesDialog = false },
            onGotoOffset = { offset ->
                showArchiveNativeLibrariesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveDexDialog && analysis?.archive != null) {
        ArchiveDexSummariesDialog(
            entries = analysis.archive.embeddedDexFiles,
            onDismiss = { showArchiveDexDialog = false },
            onGotoOffset = { offset ->
                showArchiveDexDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSectionsDialog && analysis?.elf != null) {
        ElfSectionsDialog(
            sections = analysis.elf.sections,
            onDismiss = { showSectionsDialog = false },
            onGotoOffset = { offset ->
                showSectionsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showProgramHeadersDialog && analysis?.elf != null) {
        ElfProgramHeadersDialog(
            programHeaders = analysis.elf.programHeaders,
            onDismiss = { showProgramHeadersDialog = false },
            onGotoOffset = { offset ->
                showProgramHeadersDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSectionSegmentsDialog && analysis?.elf != null) {
        ElfSectionSegmentsDialog(
            mappings = analysis.elf.sectionSegmentMappings,
            onDismiss = { showSectionSegmentsDialog = false },
            onGotoOffset = { offset ->
                showSectionSegmentsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSectionEntropyDialog && analysis?.elf != null) {
        ElfSectionEntropyDialog(
            entries = analysis.elf.sectionEntropyEntries,
            onDismiss = { showSectionEntropyDialog = false },
            onGotoOffset = { offset ->
                showSectionEntropyDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showNotesDialog && analysis?.elf != null) {
        ElfNotesDialog(
            notes = analysis.elf.noteEntries,
            onDismiss = { showNotesDialog = false },
            onGotoOffset = { offset ->
                showNotesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showSymbolsDialog && analysis?.elf != null) {
        ElfSymbolsDialog(
            symbols = analysis.elf.dynamicSymbols,
            onDismiss = { showSymbolsDialog = false },
            onGotoOffset = { offset ->
                showSymbolsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDynamicEntriesDialog && analysis?.elf != null) {
        ElfDynamicEntriesDialog(
            entries = analysis.elf.dynamicStringEntries,
            onDismiss = { showDynamicEntriesDialog = false },
            onGotoOffset = { offset ->
                showDynamicEntriesDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDynamicFlagsDialog && analysis?.elf != null) {
        ElfDynamicFlagsDialog(
            entries = analysis.elf.dynamicFlagEntries,
            onDismiss = { showDynamicFlagsDialog = false },
            onGotoOffset = { offset ->
                showDynamicFlagsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showEntropyDialog && analysis != null) {
        EntropyBucketsDialog(
            buckets = analysis.entropyVisualBuckets,
            onDismiss = { showEntropyDialog = false },
            onGotoOffset = { offset ->
                showEntropyDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showInitArrayDialog && analysis?.elf != null) {
        ElfInitArrayDialog(
            entries = analysis.elf.initArrayEntries,
            onDismiss = { showInitArrayDialog = false },
            onGotoOffset = { offset ->
                showInitArrayDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showRelocationsDialog && analysis?.elf != null) {
        ElfRelocationsDialog(
            relocations = analysis.elf.relocations,
            onDismiss = { showRelocationsDialog = false },
            onGotoOffset = { offset ->
                showRelocationsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showLinkageDialog && analysis?.elf != null) {
        ElfLinkageDialog(
            entries = analysis.elf.linkageEntries,
            onDismiss = { showLinkageDialog = false },
            onGotoOffset = { offset ->
                showLinkageDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showDynamicLinkerStepsDialog && analysis?.elf != null) {
        ElfDynamicLinkerStepsDialog(
            steps = analysis.elf.dynamicLinkerSteps,
            onDismiss = { showDynamicLinkerStepsDialog = false },
            onGotoOffset = { offset ->
                showDynamicLinkerStepsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showRiskFindingsDialog && analysis?.elf != null) {
        ElfRiskFindingsDialog(
            findings = analysis.elf.riskFindings,
            onDismiss = { showRiskFindingsDialog = false },
            onGotoOffset = { offset ->
                showRiskFindingsDialog = false
                onGotoOffset(offset)
            },
            onMarkOffsets = onMarkOffsets
        )
    }

    if (showNativeApiHintsDialog && analysis?.elf != null) {
        ElfNativeApiHintsDialog(
            hints = analysis.elf.nativeApiHints,
            onDismiss = { showNativeApiHintsDialog = false },
            onGotoOffset = { offset ->
                showNativeApiHintsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showJniHintsDialog && analysis?.elf != null) {
        ElfJniHintsDialog(
            hints = analysis.elf.jniRegistrationHints,
            onDismiss = { showJniHintsDialog = false },
            onGotoOffset = { offset ->
                showJniHintsDialog = false
                onGotoOffset(offset)
            }
        )
    }

    if (showArchiveSigningBlockDialog && analysis?.archive != null) {
        ArchiveSigningBlockDialog(
            entries = analysis.archive.signingBlockEntries,
            onDismiss = { showArchiveSigningBlockDialog = false },
            onGotoOffset = { offset ->
                showArchiveSigningBlockDialog = false
                onGotoOffset(offset)
            }
        )
    }
}

@Composable
private fun EntropyBucketBar(
    bucket: HexEntropyVisualBucket,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(18.dp)
            .height(34.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 3.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp * bucket.normalizedHeight)
                    .background(entropyBucketColor(bucket.level))
            )
        }
    }
}

@Composable
private fun entropyBucketColor(level: HexEntropyLevel): Color = when (level) {
    HexEntropyLevel.LOW -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    HexEntropyLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
    HexEntropyLevel.HIGH -> MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
}

@Composable
private fun HexFingerprintDialog(
    fingerprint: HexFileFingerprint,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val crc32Text = stringResource(Strings.hex_fingerprint_crc32_value, fingerprint.crc32)

    fun copyFingerprint(value: String) {
        scope.launch {
            clipboard.setClipEntry(
                ClipData.newPlainText("hex-fingerprint", value).toClipEntry()
            )
        }
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_fingerprint_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_fingerprint_size,
                                fingerprint.byteCount,
                                formatFileSize(fingerprint.byteCount)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_sha256),
                            value = fingerprint.sha256,
                            onCopy = ::copyFingerprint
                        )
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_sha1),
                            value = fingerprint.sha1,
                            onCopy = ::copyFingerprint
                        )
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_md5),
                            value = fingerprint.md5,
                            onCopy = ::copyFingerprint
                        )
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_crc32),
                            value = crc32Text,
                            onCopy = ::copyFingerprint
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexFingerprintRow(
    label: String,
    value: String,
    onCopy: (String) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { onCopy(value) }) {
                Text(stringResource(Strings.action_copy))
            }
        }
    }
}

@Composable
private fun HexByteFrequencyDialog(
    byteFrequency: HexByteFrequencySummary,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_byte_frequency_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_summary,
                                byteFrequency.totalBytes,
                                byteFrequency.uniqueByteValues
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_zero_ff_ratio,
                                byteFrequency.zeroBytes.percentOf(byteFrequency.totalBytes),
                                byteFrequency.ffBytes.percentOf(byteFrequency.totalBytes)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_ascii_ratio,
                                byteFrequency.printableAsciiBytes.percentOf(byteFrequency.totalBytes)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_control_ratio,
                                byteFrequency.controlBytes.percentOf(byteFrequency.totalBytes)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.hex_byte_frequency_top_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (byteFrequency.topBytes.isEmpty()) {
                            Text(
                                text = stringResource(Strings.hex_byte_frequency_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    count = byteFrequency.topBytes.size,
                                    key = { index -> byteFrequency.topBytes[index].byteValue }
                                ) { index ->
                                    HexByteFrequencyRow(entry = byteFrequency.topBytes[index])
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexByteFrequencyRow(entry: HexByteFrequencyEntry) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = entry.byteValue.toHexByteLabel(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(58.dp)
            )
            Text(
                text = stringResource(
                    Strings.hex_byte_frequency_row,
                    entry.count,
                    entry.ratio * 100.0
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HexRepeatedByteRunsDialog(
    runs: List<HexRepeatedByteRun>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_repeated_runs_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_repeated_runs_summary,
                                    runs.size,
                                    runs.maxOfOrNull { it.length } ?: 0L
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onMarkOffsets(runs.map { run -> run.startOffset }) },
                                enabled = runs.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_all))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (runs.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_repeated_runs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = runs.size,
                                key = { index ->
                                    val run = runs[index]
                                    "${run.byteValue}-${run.startOffset}-${run.length}"
                                }
                            ) { index ->
                                HexRepeatedByteRunRow(
                                    run = runs[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexRepeatedByteRunRow(
    run: HexRepeatedByteRun,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(run.startOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = run.byteValue.toHexByteLabel(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(58.dp)
            )
            Text(
                text = stringResource(
                    Strings.hex_repeated_run_meta,
                    run.startOffset,
                    run.length
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onMarkOffset(run.startOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
private fun HexMagicSignaturesDialog(
    matches: List<HexMagicSignatureMatch>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_magic_signatures_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.hex_magic_signatures_summary, matches.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onMarkOffsets(matches.map { match -> match.offset }) },
                            enabled = matches.isNotEmpty()
                        ) {
                            Text(stringResource(Strings.hex_bookmark_mark_all))
                        }
                    }
                }
                TinaDialogCard {
                    if (matches.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_magic_signatures_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = matches.size,
                                key = { index ->
                                    val match = matches[index]
                                    "${match.kind}-${match.offset}-${match.signatureLength}"
                                }
                            ) { index ->
                                HexMagicSignatureRow(
                                    match = matches[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexMagicSignatureRow(
    match: HexMagicSignatureMatch,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(match.offset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = hexMagicSignatureKindLabel(match.kind),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(136.dp)
            )
            Text(
                text = stringResource(
                    Strings.hex_magic_signature_meta,
                    match.offset,
                    match.signatureLength
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onMarkOffset(match.offset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
private fun hexMagicSignatureKindLabel(kind: HexMagicSignatureKind): String = stringResource(
    when (kind) {
        HexMagicSignatureKind.ELF -> Strings.hex_magic_signature_kind_elf
        HexMagicSignatureKind.DEX -> Strings.hex_magic_signature_kind_dex
        HexMagicSignatureKind.ZIP_LOCAL_FILE -> Strings.hex_magic_signature_kind_zip_local
        HexMagicSignatureKind.ZIP_CENTRAL_DIRECTORY -> Strings.hex_magic_signature_kind_zip_central
        HexMagicSignatureKind.ZIP_EOCD -> Strings.hex_magic_signature_kind_zip_eocd
        HexMagicSignatureKind.PNG -> Strings.hex_magic_signature_kind_png
        HexMagicSignatureKind.JPEG -> Strings.hex_magic_signature_kind_jpeg
        HexMagicSignatureKind.ANDROID_RESOURCES -> Strings.hex_magic_signature_kind_android_resources
        HexMagicSignatureKind.SQLITE -> Strings.hex_magic_signature_kind_sqlite
    }
)

@Composable
private fun EntropyBucketsDialog(
    buckets: List<HexEntropyVisualBucket>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var bucketFilter by remember(buckets) { mutableStateOf(EntropyBucketFilter.ALL) }
    val filteredBuckets = remember(buckets, bucketFilter) {
        filterEntropyVisualBuckets(
            buckets = buckets,
            filter = bucketFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_entropy_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.ALL,
                                selected = bucketFilter == EntropyBucketFilter.ALL,
                                onClick = { bucketFilter = EntropyBucketFilter.ALL }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.LOW,
                                selected = bucketFilter == EntropyBucketFilter.LOW,
                                onClick = { bucketFilter = EntropyBucketFilter.LOW }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.MEDIUM,
                                selected = bucketFilter == EntropyBucketFilter.MEDIUM,
                                onClick = { bucketFilter = EntropyBucketFilter.MEDIUM }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.HIGH,
                                selected = bucketFilter == EntropyBucketFilter.HIGH,
                                onClick = { bucketFilter = EntropyBucketFilter.HIGH }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_entropy_filter_count,
                                    filteredBuckets.size,
                                    buckets.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onMarkOffsets(filteredBuckets.map { bucket -> bucket.startOffset }) },
                                enabled = filteredBuckets.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredBuckets.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_entropy_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredBuckets.size,
                                key = { index ->
                                    val bucket = filteredBuckets[index]
                                    "${bucket.startOffset}-${bucket.endOffset}-${bucket.level}"
                                }
                            ) { index ->
                                EntropyBucketRow(
                                    bucket = filteredBuckets[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun EntropyBucketFilterButton(
    filter: EntropyBucketFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = entropyBucketFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EntropyBucketRow(
    bucket: HexEntropyVisualBucket,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(bucket.startOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(28.dp)
                    .background(entropyBucketColor(bucket.level))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(
                        Strings.hex_entropy_bucket_meta,
                        bucket.startOffset,
                        bucket.endOffset,
                        entropyLevelLabel(bucket.level),
                        bucket.entropy
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onMarkOffset(bucket.startOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
private fun ElfSectionEntropyDialog(
    entries: List<HexElfSectionEntropyEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var entropyFilter by remember(entries) { mutableStateOf(EntropyBucketFilter.ALL) }
    val filteredEntries = remember(entries, query, entropyFilter) {
        filterElfSectionEntropyEntries(
            entries = entries,
            query = query,
            entropyFilter = entropyFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_section_entropy_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_section_entropy_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.ALL,
                                selected = entropyFilter == EntropyBucketFilter.ALL,
                                onClick = { entropyFilter = EntropyBucketFilter.ALL }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.LOW,
                                selected = entropyFilter == EntropyBucketFilter.LOW,
                                onClick = { entropyFilter = EntropyBucketFilter.LOW }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.MEDIUM,
                                selected = entropyFilter == EntropyBucketFilter.MEDIUM,
                                onClick = { entropyFilter = EntropyBucketFilter.MEDIUM }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.HIGH,
                                selected = entropyFilter == EntropyBucketFilter.HIGH,
                                onClick = { entropyFilter = EntropyBucketFilter.HIGH }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_section_entropy_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_section_entropy_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.sectionIndex}-${entry.fileOffset}-${entry.sampleSize}"
                                }
                            ) { index ->
                                ElfSectionEntropyRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfSectionEntropyRow(
    entry: HexElfSectionEntropyEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.fileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(34.dp)
                    .background(entropyBucketColor(entry.level))
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val unnamedSection = stringResource(Strings.hex_elf_section_name_empty)
                val sectionName = entry.sectionName.ifBlank { unnamedSection }
                Text(
                    text = stringResource(
                        Strings.hex_elf_section_entropy_display,
                        sectionName,
                        entropyLevelLabel(entry.level),
                        entry.entropy
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        Strings.hex_elf_section_entropy_meta,
                        entry.sectionIndex,
                        entry.virtualAddress,
                        entry.fileOffset,
                        entry.size,
                        entry.sampleSize
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ElfInitArrayDialog(
    entries: List<HexElfInitArrayEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_init_array_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    if (entries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_init_array_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = entries.size,
                                key = { index ->
                                    val entry = entries[index]
                                    "${entry.index}-${entry.pointerFileOffset}-${entry.functionAddress}"
                                }
                            ) { index ->
                                ElfInitArrayRow(
                                    entry = entries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfInitArrayRow(
    entry: HexElfInitArrayEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = entry.functionFileOffset ?: entry.pointerFileOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (entry.functionFileOffset != null) {
                    stringResource(
                        Strings.hex_elf_init_array_meta_mapped,
                        entry.index,
                        entry.pointerFileOffset,
                        entry.functionAddress,
                        entry.functionFileOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_init_array_meta_unmapped,
                        entry.index,
                        entry.pointerFileOffset,
                        entry.functionAddress
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ElfRelocationsDialog(
    relocations: List<HexElfRelocationEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(relocations) { mutableStateOf("") }
    var relocationFilter by remember(relocations) { mutableStateOf(ElfRelocationFilter.ALL) }
    val filteredRelocations = remember(relocations, query, relocationFilter) {
        filterElfRelocations(
            relocations = relocations,
            query = query,
            relocationFilter = relocationFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_relocations_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_relocations_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfRelocationFilterButton(
                                filter = ElfRelocationFilter.ALL,
                                selected = relocationFilter == ElfRelocationFilter.ALL,
                                onClick = { relocationFilter = ElfRelocationFilter.ALL }
                            )
                            ElfRelocationFilterButton(
                                filter = ElfRelocationFilter.PLT,
                                selected = relocationFilter == ElfRelocationFilter.PLT,
                                onClick = { relocationFilter = ElfRelocationFilter.PLT }
                            )
                            ElfRelocationFilterButton(
                                filter = ElfRelocationFilter.DYNAMIC,
                                selected = relocationFilter == ElfRelocationFilter.DYNAMIC,
                                onClick = { relocationFilter = ElfRelocationFilter.DYNAMIC }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_relocations_filter_count,
                                filteredRelocations.size,
                                relocations.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredRelocations.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_relocations_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredRelocations.size,
                                key = { index ->
                                    val relocation = filteredRelocations[index]
                                    "${relocation.sectionName}-${relocation.relocationFileOffset}-${relocation.offsetAddress}"
                                }
                            ) { index ->
                                ElfRelocationRow(
                                    relocation = filteredRelocations[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfRelocationFilterButton(
    filter: ElfRelocationFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfRelocationFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfRelocationRow(
    relocation: HexElfRelocationEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = relocation.offsetFileOffset ?: relocation.relocationFileOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = relocation.symbolName
                    ?: stringResource(Strings.hex_elf_relocation_symbol_index, relocation.symbolIndex),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = relocationMetaText(relocation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_relocation_semantic,
                    elfRelocationSemanticLabel(relocation.semantic)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun relocationMetaText(relocation: HexElfRelocationEntry): String {
    val sectionName = relocation.sectionName.ifBlank { stringResource(Strings.hex_elf_section_name_empty) }
    val typeLabel = relocationTypeLabel(relocation)
    val symbolLabel = relocationSymbolMetaLabel(relocation)
    val targetSectionLabel = relocationTargetSectionLabel(relocation)
    val addendLabel = relocation.addend?.let { addend ->
        stringResource(Strings.hex_elf_relocation_addend, addend)
    } ?: stringResource(Strings.hex_elf_relocation_no_addend)
    val offsetFileOffset = relocation.offsetFileOffset
    return if (offsetFileOffset != null) {
        stringResource(
            Strings.hex_elf_relocation_meta_mapped,
            relocation.index,
            sectionName,
            typeLabel,
            symbolLabel,
            targetSectionLabel,
            relocation.relocationFileOffset,
            relocation.offsetAddress,
            offsetFileOffset,
            addendLabel
        )
    } else {
        stringResource(
            Strings.hex_elf_relocation_meta_unmapped,
            relocation.index,
            sectionName,
            typeLabel,
            symbolLabel,
            targetSectionLabel,
            relocation.relocationFileOffset,
            relocation.offsetAddress,
            addendLabel
        )
    }
}

@Composable
private fun relocationSymbolMetaLabel(relocation: HexElfRelocationEntry): String {
    val binding = relocation.symbolBinding
    val type = relocation.symbolType
    return if (binding != null && type != null) {
        stringResource(
            Strings.hex_elf_relocation_symbol_meta,
            relocationSymbolRoleLabel(relocation),
            elfSymbolTypeLabel(type),
            elfSymbolBindingLabel(binding)
        )
    } else {
        stringResource(Strings.hex_elf_relocation_symbol_meta_unknown)
    }
}

@Composable
private fun relocationSymbolRoleLabel(relocation: HexElfRelocationEntry): String = stringResource(
    when {
        relocation.isSymbolJni -> Strings.hex_elf_symbol_role_jni
        relocation.isSymbolImported -> Strings.hex_elf_symbol_role_imported
        relocation.isSymbolExported -> Strings.hex_elf_symbol_role_exported
        else -> Strings.hex_elf_symbol_role_local
    }
)

@Composable
private fun relocationTargetSectionLabel(relocation: HexElfRelocationEntry): String {
    val targetSectionName = relocation.targetSectionName
    return if (targetSectionName != null) {
        stringResource(Strings.hex_elf_relocation_target_section, targetSectionName)
    } else {
        stringResource(Strings.hex_elf_relocation_target_section_unknown)
    }
}

@Composable
private fun relocationTypeLabel(relocation: HexElfRelocationEntry): String {
    val typeName = relocation.typeName
    return if (typeName != null) {
        stringResource(Strings.hex_elf_relocation_type_named, typeName, relocation.type)
    } else {
        stringResource(Strings.hex_elf_relocation_type_unknown, relocation.type)
    }
}

@Composable
private fun elfRelocationSemanticLabel(semantic: HexElfRelocationSemantic): String = stringResource(
    when (semantic) {
        HexElfRelocationSemantic.JUMP_SLOT_BINDING -> Strings.hex_elf_relocation_semantic_jump_slot_binding
        HexElfRelocationSemantic.GLOB_DAT_ADDRESS -> Strings.hex_elf_relocation_semantic_glob_dat_address
        HexElfRelocationSemantic.RELATIVE_REBASE -> Strings.hex_elf_relocation_semantic_relative_rebase
        HexElfRelocationSemantic.COPY_RELOCATION -> Strings.hex_elf_relocation_semantic_copy_relocation
        HexElfRelocationSemantic.ABSOLUTE_ADDRESS -> Strings.hex_elf_relocation_semantic_absolute_address
        HexElfRelocationSemantic.PC_RELATIVE_ADDRESS -> Strings.hex_elf_relocation_semantic_pc_relative_address
        HexElfRelocationSemantic.OTHER -> Strings.hex_elf_relocation_semantic_other
    }
)

@Composable
private fun elfSymbolBindingLabel(binding: HexElfSymbolBinding): String = stringResource(
    when (binding) {
        HexElfSymbolBinding.LOCAL -> Strings.hex_elf_symbol_binding_local
        HexElfSymbolBinding.GLOBAL -> Strings.hex_elf_symbol_binding_global
        HexElfSymbolBinding.WEAK -> Strings.hex_elf_symbol_binding_weak
        HexElfSymbolBinding.OTHER -> Strings.hex_elf_symbol_binding_other
    }
)

@Composable
private fun ElfLinkageDialog(
    entries: List<HexElfLinkageEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var linkageFilter by remember(entries) { mutableStateOf(ElfLinkageFilter.ALL) }
    val filteredEntries = remember(entries, query, linkageFilter) {
        filterElfLinkageEntries(
            entries = entries,
            query = query,
            linkageFilter = linkageFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_linkage_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_linkage_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.ALL,
                                selected = linkageFilter == ElfLinkageFilter.ALL,
                                onClick = { linkageFilter = ElfLinkageFilter.ALL }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.IMPORTS,
                                selected = linkageFilter == ElfLinkageFilter.IMPORTS,
                                onClick = { linkageFilter = ElfLinkageFilter.IMPORTS }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.PLT,
                                selected = linkageFilter == ElfLinkageFilter.PLT,
                                onClick = { linkageFilter = ElfLinkageFilter.PLT }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.GOT,
                                selected = linkageFilter == ElfLinkageFilter.GOT,
                                onClick = { linkageFilter = ElfLinkageFilter.GOT }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.JNI,
                                selected = linkageFilter == ElfLinkageFilter.JNI,
                                onClick = { linkageFilter = ElfLinkageFilter.JNI }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.NOW,
                                selected = linkageFilter == ElfLinkageFilter.NOW,
                                onClick = { linkageFilter = ElfLinkageFilter.NOW }
                            )
                            ElfLinkageFilterButton(
                                filter = ElfLinkageFilter.LAZY,
                                selected = linkageFilter == ElfLinkageFilter.LAZY,
                                onClick = { linkageFilter = ElfLinkageFilter.LAZY }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_linkage_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_linkage_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.relocationSectionName}-${entry.relocationFileOffset}-${entry.slotAddress}"
                                }
                            ) { index ->
                                ElfLinkageRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfLinkageFilterButton(
    filter: ElfLinkageFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfLinkageFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfLinkageRow(
    entry: HexElfLinkageEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = entry.slotFileOffset ?: entry.relocationFileOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.symbolName
                    ?: stringResource(Strings.hex_elf_relocation_symbol_index, entry.symbolIndex),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = linkageMetaText(entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_linkage_resolution_semantic,
                    elfLinkageResolutionSemanticLabel(entry.resolutionSemantic)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.pltStub?.let { pltStub ->
                Text(
                    text = pltStubMetaText(pltStub),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(Strings.hex_elf_linkage_plt_stub_bytes, pltStub.instructionBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onGotoOffset(pltStub.fileOffset) }) {
                        Text(stringResource(Strings.hex_elf_linkage_plt_stub_jump))
                    }
                    pltStub.slotFileOffset?.let { slotFileOffset ->
                        TextButton(onClick = { onGotoOffset(slotFileOffset) }) {
                            Text(stringResource(Strings.hex_elf_linkage_slot_jump))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun linkageMetaText(entry: HexElfLinkageEntry): String {
    val slotSectionLabel = entry.slotSectionName ?: stringResource(Strings.hex_elf_relocation_target_section_unknown)
    val symbolLabel = linkageSymbolMetaLabel(entry)
    val typeLabel = entry.relocationTypeName?.let { typeName ->
        stringResource(Strings.hex_elf_linkage_type_named, typeName)
    } ?: stringResource(Strings.hex_elf_linkage_type_unknown)
    val slotFileOffset = entry.slotFileOffset
    return if (slotFileOffset != null) {
        stringResource(
            Strings.hex_elf_linkage_meta_mapped,
            entry.index,
            elfLinkageKindLabel(entry.entryKind),
            elfLinkageBindingModeLabel(entry.bindingMode),
            symbolLabel,
            typeLabel,
            entry.relocationSectionName,
            entry.relocationFileOffset,
            entry.slotAddress,
            slotFileOffset,
            slotSectionLabel
        )
    } else {
        stringResource(
            Strings.hex_elf_linkage_meta_unmapped,
            entry.index,
            elfLinkageKindLabel(entry.entryKind),
            elfLinkageBindingModeLabel(entry.bindingMode),
            symbolLabel,
            typeLabel,
            entry.relocationSectionName,
            entry.relocationFileOffset,
            entry.slotAddress,
            slotSectionLabel
        )
    }
}

@Composable
private fun pltStubMetaText(stub: HexElfPltStub): String {
    val slotFileOffset = stub.slotFileOffset
    return if (slotFileOffset != null) {
        stringResource(
            Strings.hex_elf_linkage_plt_stub_meta_mapped,
            stub.fileOffset,
            pltStubArchitectureLabel(stub.architecture),
            pltStubSemanticLabel(stub.semantic),
            stub.byteCount,
            slotFileOffset
        )
    } else {
        stringResource(
            Strings.hex_elf_linkage_plt_stub_meta_unmapped,
            stub.fileOffset,
            pltStubArchitectureLabel(stub.architecture),
            pltStubSemanticLabel(stub.semantic),
            stub.byteCount
        )
    }
}

@Composable
private fun pltStubArchitectureLabel(architecture: HexElfPltStubArchitecture): String = stringResource(
    when (architecture) {
        HexElfPltStubArchitecture.AARCH64 -> Strings.hex_elf_plt_arch_aarch64
        HexElfPltStubArchitecture.X86_64 -> Strings.hex_elf_plt_arch_x86_64
    }
)

@Composable
private fun pltStubSemanticLabel(semantic: HexElfPltStubSemantic): String = stringResource(
    when (semantic) {
        HexElfPltStubSemantic.LOAD_GOT_SLOT_AND_BRANCH -> Strings.hex_elf_plt_semantic_load_got_slot_branch
        HexElfPltStubSemantic.UNKNOWN -> Strings.hex_elf_plt_semantic_unknown
    }
)

@Composable
private fun elfLinkageResolutionSemanticLabel(semantic: HexElfLinkageResolutionSemantic): String = stringResource(
    when (semantic) {
        HexElfLinkageResolutionSemantic.EAGER_PLT_BINDING -> Strings.hex_elf_linkage_semantic_eager_plt_binding
        HexElfLinkageResolutionSemantic.LAZY_PLT_CALL -> Strings.hex_elf_linkage_semantic_lazy_plt_call
        HexElfLinkageResolutionSemantic.LOAD_TIME_GOT_WRITE -> Strings.hex_elf_linkage_semantic_load_time_got_write
        HexElfLinkageResolutionSemantic.RELATIVE_REBASE -> Strings.hex_elf_linkage_semantic_relative_rebase
        HexElfLinkageResolutionSemantic.LOCAL_RELOCATION -> Strings.hex_elf_linkage_semantic_local_relocation
    }
)

@Composable
private fun linkageSymbolMetaLabel(entry: HexElfLinkageEntry): String {
    val binding = entry.symbolBinding
    val type = entry.symbolType
    return if (binding != null && type != null) {
        stringResource(
            Strings.hex_elf_relocation_symbol_meta,
            linkageSymbolRoleLabel(entry),
            elfSymbolTypeLabel(type),
            elfSymbolBindingLabel(binding)
        )
    } else {
        stringResource(Strings.hex_elf_relocation_symbol_meta_unknown)
    }
}

@Composable
private fun linkageSymbolRoleLabel(entry: HexElfLinkageEntry): String = stringResource(
    when {
        entry.isJni -> Strings.hex_elf_symbol_role_jni
        entry.isImported -> Strings.hex_elf_symbol_role_imported
        entry.isExported -> Strings.hex_elf_symbol_role_exported
        else -> Strings.hex_elf_symbol_role_local
    }
)

@Composable
private fun ElfDynamicLinkerStepsDialog(
    steps: List<HexElfDynamicLinkerStep>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(steps) { mutableStateOf("") }
    var stepFilter by remember(steps) { mutableStateOf(ElfDynamicLinkerStepFilter.ALL) }
    val filteredSteps = remember(steps, query, stepFilter) {
        filterElfDynamicLinkerSteps(
            steps = steps,
            query = query,
            stepFilter = stepFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_loader_steps_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_loader_steps_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.ALL,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.ALL,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.ALL }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.LOADING,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.LOADING,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.LOADING }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.RELOCATIONS,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.RELOCATIONS,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.RELOCATIONS }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.BINDING,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.BINDING,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.BINDING }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.HARDENING,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.HARDENING,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.HARDENING }
                            )
                            ElfDynamicLinkerStepFilterButton(
                                filter = ElfDynamicLinkerStepFilter.ENTRYPOINTS,
                                selected = stepFilter == ElfDynamicLinkerStepFilter.ENTRYPOINTS,
                                onClick = { stepFilter = ElfDynamicLinkerStepFilter.ENTRYPOINTS }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_loader_steps_filter_count,
                                filteredSteps.size,
                                steps.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredSteps.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_loader_steps_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredSteps.size,
                                key = { index ->
                                    val step = filteredSteps[index]
                                    "${step.index}-${step.type}-${step.evidenceFileOffset}"
                                }
                            ) { index ->
                                ElfDynamicLinkerStepRow(
                                    step = filteredSteps[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfDynamicLinkerStepFilterButton(
    filter: ElfDynamicLinkerStepFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfDynamicLinkerStepFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfDynamicLinkerStepRow(
    step: HexElfDynamicLinkerStep,
    onGotoOffset: (Long) -> Unit
) {
    val evidenceOffset = step.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = elfDynamicLinkerStepTypeLabel(step.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_loader_step_meta_mapped,
                        step.index,
                        step.relatedCount,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_loader_step_meta_unmapped,
                        step.index,
                        step.relatedCount
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            step.detailValue?.let { detail ->
                Text(
                    text = stringResource(Strings.hex_elf_loader_step_detail, detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}

@Composable
private fun ElfRiskFindingsDialog(
    findings: List<HexElfRiskFinding>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(findings) { mutableStateOf("") }
    var riskFilter by remember(findings) { mutableStateOf(ElfRiskFilter.ALL) }
    val filteredFindings = remember(findings, query, riskFilter) {
        filterElfRiskFindings(
            findings = findings,
            query = query,
            riskFilter = riskFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_risks_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_risks_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.ALL,
                                selected = riskFilter == ElfRiskFilter.ALL,
                                onClick = { riskFilter = ElfRiskFilter.ALL }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.HIGH,
                                selected = riskFilter == ElfRiskFilter.HIGH,
                                onClick = { riskFilter = ElfRiskFilter.HIGH }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.WARNING,
                                selected = riskFilter == ElfRiskFilter.WARNING,
                                onClick = { riskFilter = ElfRiskFilter.WARNING }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.HARDENING,
                                selected = riskFilter == ElfRiskFilter.HARDENING,
                                onClick = { riskFilter = ElfRiskFilter.HARDENING }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.SEGMENTS,
                                selected = riskFilter == ElfRiskFilter.SEGMENTS,
                                onClick = { riskFilter = ElfRiskFilter.SEGMENTS }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.PATHS,
                                selected = riskFilter == ElfRiskFilter.PATHS,
                                onClick = { riskFilter = ElfRiskFilter.PATHS }
                            )
                            ElfRiskFilterButton(
                                filter = ElfRiskFilter.METADATA,
                                selected = riskFilter == ElfRiskFilter.METADATA,
                                onClick = { riskFilter = ElfRiskFilter.METADATA }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_elf_risks_filter_count,
                                    filteredFindings.size,
                                    findings.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            val filteredEvidenceOffsets = filteredFindings.mapNotNull { finding ->
                                finding.evidenceFileOffset
                            }
                            TextButton(
                                onClick = { onMarkOffsets(filteredEvidenceOffsets) },
                                enabled = filteredEvidenceOffsets.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredFindings.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_risks_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredFindings.size,
                                key = { index ->
                                    val finding = filteredFindings[index]
                                    "${finding.index}-${finding.type}-${finding.evidenceFileOffset}"
                                }
                            ) { index ->
                                ElfRiskFindingRow(
                                    finding = filteredFindings[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfRiskFilterButton(
    filter: ElfRiskFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfRiskFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfRiskFindingRow(
    finding: HexElfRiskFinding,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    val evidenceOffset = finding.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_elf_risk_row_title,
                    elfRiskSeverityLabel(finding.severity),
                    elfRiskTypeLabel(finding.type)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = when (finding.severity) {
                    HexElfRiskSeverity.HIGH -> MaterialTheme.colorScheme.error
                    HexElfRiskSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                    HexElfRiskSeverity.INFO -> MaterialTheme.colorScheme.onSurface
                },
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_risk_meta_mapped,
                        finding.index,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_risk_meta_unmapped,
                        finding.index
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            finding.detailValue?.let { detail ->
                Text(
                    text = stringResource(Strings.hex_elf_risk_detail, detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (evidenceOffset != null) {
                TextButton(onClick = { onMarkOffset(evidenceOffset) }) {
                    Text(stringResource(Strings.hex_bookmark_mark))
                }
            }
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}

@Composable
private fun ElfNativeApiHintsDialog(
    hints: List<HexElfNativeApiHint>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(hints) { mutableStateOf("") }
    var apiFilter by remember(hints) { mutableStateOf(ElfNativeApiFilter.ALL) }
    val filteredHints = remember(hints, query, apiFilter) {
        filterElfNativeApiHints(
            hints = hints,
            query = query,
            apiFilter = apiFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_native_api_hints_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_native_api_hints_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.ALL,
                                selected = apiFilter == ElfNativeApiFilter.ALL,
                                onClick = { apiFilter = ElfNativeApiFilter.ALL }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.DYNAMIC_LOADING,
                                selected = apiFilter == ElfNativeApiFilter.DYNAMIC_LOADING,
                                onClick = { apiFilter = ElfNativeApiFilter.DYNAMIC_LOADING }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.MEMORY,
                                selected = apiFilter == ElfNativeApiFilter.MEMORY,
                                onClick = { apiFilter = ElfNativeApiFilter.MEMORY }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.PROCESS,
                                selected = apiFilter == ElfNativeApiFilter.PROCESS,
                                onClick = { apiFilter = ElfNativeApiFilter.PROCESS }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.FILE,
                                selected = apiFilter == ElfNativeApiFilter.FILE,
                                onClick = { apiFilter = ElfNativeApiFilter.FILE }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.NETWORK,
                                selected = apiFilter == ElfNativeApiFilter.NETWORK,
                                onClick = { apiFilter = ElfNativeApiFilter.NETWORK }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.CRYPTO,
                                selected = apiFilter == ElfNativeApiFilter.CRYPTO,
                                onClick = { apiFilter = ElfNativeApiFilter.CRYPTO }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.THREADING,
                                selected = apiFilter == ElfNativeApiFilter.THREADING,
                                onClick = { apiFilter = ElfNativeApiFilter.THREADING }
                            )
                            ElfNativeApiFilterButton(
                                filter = ElfNativeApiFilter.LOGGING,
                                selected = apiFilter == ElfNativeApiFilter.LOGGING,
                                onClick = { apiFilter = ElfNativeApiFilter.LOGGING }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_native_api_hints_filter_count,
                                filteredHints.size,
                                hints.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredHints.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_native_api_hints_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredHints.size,
                                key = { index ->
                                    val hint = filteredHints[index]
                                    "${hint.index}-${hint.category}-${hint.symbolName}"
                                }
                            ) { index ->
                                ElfNativeApiHintRow(
                                    hint = filteredHints[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfNativeApiFilterButton(
    filter: ElfNativeApiFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfNativeApiFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfNativeApiHintRow(
    hint: HexElfNativeApiHint,
    onGotoOffset: (Long) -> Unit
) {
    val evidenceOffset = hint.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_elf_native_api_hint_title,
                    elfNativeApiCategoryLabel(hint.category),
                    hint.symbolName
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_native_api_hint_meta_mapped,
                        hint.index,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_native_api_hint_meta_unmapped,
                        hint.index
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}

@Composable
private fun ElfJniHintsDialog(
    hints: List<HexElfJniRegistrationHint>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(hints) { mutableStateOf("") }
    var hintFilter by remember(hints) { mutableStateOf(ElfJniHintFilter.ALL) }
    val filteredHints = remember(hints, query, hintFilter) {
        filterElfJniRegistrationHints(
            hints = hints,
            query = query,
            hintFilter = hintFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_jni_hints_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_jni_hints_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.ALL,
                                selected = hintFilter == ElfJniHintFilter.ALL,
                                onClick = { hintFilter = ElfJniHintFilter.ALL }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.REGISTER_NATIVES,
                                selected = hintFilter == ElfJniHintFilter.REGISTER_NATIVES,
                                onClick = { hintFilter = ElfJniHintFilter.REGISTER_NATIVES }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.ENTRYPOINTS,
                                selected = hintFilter == ElfJniHintFilter.ENTRYPOINTS,
                                onClick = { hintFilter = ElfJniHintFilter.ENTRYPOINTS }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.STATIC_EXPORTS,
                                selected = hintFilter == ElfJniHintFilter.STATIC_EXPORTS,
                                onClick = { hintFilter = ElfJniHintFilter.STATIC_EXPORTS }
                            )
                            ElfJniHintFilterButton(
                                filter = ElfJniHintFilter.DESCRIPTORS,
                                selected = hintFilter == ElfJniHintFilter.DESCRIPTORS,
                                onClick = { hintFilter = ElfJniHintFilter.DESCRIPTORS }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_jni_hints_filter_count,
                                filteredHints.size,
                                hints.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredHints.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_jni_hints_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredHints.size,
                                key = { index ->
                                    val hint = filteredHints[index]
                                    "${hint.index}-${hint.type}-${hint.evidenceFileOffset}"
                                }
                            ) { index ->
                                ElfJniHintRow(
                                    hint = filteredHints[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfJniHintFilterButton(
    filter: ElfJniHintFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfJniHintFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfJniHintRow(
    hint: HexElfJniRegistrationHint,
    onGotoOffset: (Long) -> Unit
) {
    val evidenceOffset = hint.evidenceFileOffset
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = elfJniHintTypeLabel(hint.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (evidenceOffset != null) {
                    stringResource(
                        Strings.hex_elf_jni_hint_meta_mapped,
                        hint.index,
                        evidenceOffset
                    )
                } else {
                    stringResource(
                        Strings.hex_elf_jni_hint_meta_unmapped,
                        hint.index
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            hint.symbolName?.let { symbolName ->
                Text(
                    text = stringResource(Strings.hex_elf_jni_hint_symbol, symbolName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            hint.stringValue?.let { stringValue ->
                Text(
                    text = stringResource(Strings.hex_elf_jni_hint_string, stringValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (evidenceOffset != null) {
        Surface(
            onClick = { onGotoOffset(evidenceOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            rowContent()
        }
    }
}

@Composable
private fun HexContent(
    dataManager: HexFileDataManager,
    listState: LazyListState,
    cacheVersion: Int,
    selectedOffset: Long,
    selectionRange: HexSelectionRange?,
    pendingNibble: String,
    patchMap: Map<Long, HexPatch>,
    bookmarkedOffsets: Set<Long>,
    onCacheVersionChanged: () -> Unit,
    onOffsetSelected: (Long) -> Unit,
    onByteLongPressed: (HexContextTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(listState.firstVisibleItemIndex, cacheVersion) {
        if (dataManager.preloadAroundRow(listState.firstVisibleItemIndex)) {
            onCacheVersionChanged()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        items(
            count = dataManager.totalRows,
            key = { it }
        ) { lineIndex ->
            val line = remember(cacheVersion, lineIndex) {
                dataManager.getCachedRow(lineIndex)
            }

            LaunchedEffect(lineIndex, cacheVersion) {
                if (line == null && dataManager.loadChunkForRow(lineIndex)) {
                    onCacheVersionChanged()
                }
            }

            if (line == null) {
                HexPlaceholderLine(offset = dataManager.getRowOffset(lineIndex))
            } else {
                HexByteRowBlock(
                    row = line,
                    selectedOffset = selectedOffset,
                    selectionRange = selectionRange,
                    pendingNibble = pendingNibble,
                    patchMap = patchMap,
                    bookmarkedOffsets = bookmarkedOffsets,
                    onOffsetSelected = onOffsetSelected,
                    onByteLongPressed = onByteLongPressed
                )
            }
        }
    }
}

@Composable
private fun HexByteRowBlock(
    row: HexByteRow,
    selectedOffset: Long,
    selectionRange: HexSelectionRange?,
    pendingNibble: String,
    patchMap: Map<Long, HexPatch>,
    bookmarkedOffsets: Set<Long>,
    onOffsetSelected: (Long) -> Unit,
    onByteLongPressed: (HexContextTarget) -> Unit
) {
    val firstRowBytes = row.bytes.take(HexFileDataManager.VISUAL_BYTES_PER_ROW)
    val secondRowBytes = row.bytes.drop(HexFileDataManager.VISUAL_BYTES_PER_ROW)

    HexVisualRow(
        offset = row.offset,
        bytes = firstRowBytes,
        selectedOffset = selectedOffset,
        selectionRange = selectionRange,
        pendingNibble = pendingNibble,
        patchMap = patchMap,
        bookmarkedOffsets = bookmarkedOffsets,
        isEven = (row.offset / HexFileDataManager.VISUAL_BYTES_PER_ROW) % 2L == 0L,
        onOffsetSelected = onOffsetSelected,
        onByteLongPressed = onByteLongPressed
    )

    if (secondRowBytes.isNotEmpty()) {
        HexVisualRow(
            offset = row.offset + HexFileDataManager.VISUAL_BYTES_PER_ROW,
            bytes = secondRowBytes,
            selectedOffset = selectedOffset,
            selectionRange = selectionRange,
            pendingNibble = pendingNibble,
            patchMap = patchMap,
            bookmarkedOffsets = bookmarkedOffsets,
            isEven = ((row.offset + HexFileDataManager.VISUAL_BYTES_PER_ROW) / HexFileDataManager.VISUAL_BYTES_PER_ROW) % 2L == 0L,
            onOffsetSelected = onOffsetSelected,
            onByteLongPressed = onByteLongPressed
        )
    }
}

@Composable
private fun HexVisualRow(
    offset: Long,
    bytes: List<Byte>,
    selectedOffset: Long,
    selectionRange: HexSelectionRange?,
    pendingNibble: String,
    patchMap: Map<Long, HexPatch>,
    bookmarkedOffsets: Set<Long>,
    isEven: Boolean,
    onOffsetSelected: (Long) -> Unit,
    onByteLongPressed: (HexContextTarget) -> Unit
) {
    val rowStartOffset = offset
    val rowEndOffset = offset + bytes.size - 1
    val rowSelected = selectedOffset in rowStartOffset..rowEndOffset
    val selectedColumn = (selectedOffset % HexFileDataManager.VISUAL_BYTES_PER_ROW).toInt()
    val density = LocalDensity.current
    val dividerPx = with(density) { 1.dp.toPx() }
    var hexRowWidthPx by remember { mutableIntStateOf(0) }
    val backgroundColor = if (isEven) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val rangeColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.64f)
    val patchedColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
    val bookmarkColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.54f)
    val columnHighlight = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.36f)
    val byteTextColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(backgroundColor)
            .padding(end = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(AddressColumnWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%08X".format(offset),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        VerticalDivider()

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { hexRowWidthPx = it.width }
                .pointerInput(offset, bytes.size, hexRowWidthPx) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val column = computeHexColumn(
                                tapX = tapOffset.x,
                                totalWidth = hexRowWidthPx.toFloat(),
                                dividerWidth = dividerPx,
                                byteCount = bytes.size
                            )
                            if (column in bytes.indices) {
                                onOffsetSelected(offset + column)
                            }
                        },
                        onLongPress = { tapOffset ->
                            val column = computeHexColumn(
                                tapX = tapOffset.x,
                                totalWidth = hexRowWidthPx.toFloat(),
                                dividerWidth = dividerPx,
                                byteCount = bytes.size
                            )
                            if (column in bytes.indices) {
                                val targetOffset = offset + column
                                val displayByte = patchMap[targetOffset]?.newByte ?: bytes[column]
                                onByteLongPressed(HexContextTarget(offset = targetOffset, byte = displayByte))
                            }
                        }
                    )
                }
                .fillMaxWidth()
                .padding(vertical = 1.dp)
        ) {
            bytes.forEachIndexed { column, byte ->
                if (column == 4) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                val byteOffset = offset + column
                val displayByte = patchMap[byteOffset]?.newByte ?: byte
                val selected = byteOffset == selectedOffset
                val inSelectionRange = selectionRange?.contains(byteOffset) == true
                val patched = patchMap.containsKey(byteOffset)
                val bookmarked = bookmarkedOffsets.contains(byteOffset)
                val highlighted = !selected && (rowSelected || column == selectedColumn)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            when {
                                selected -> selectedColor
                                inSelectionRange -> rangeColor
                                patched -> patchedColor
                                bookmarked -> bookmarkColor
                                highlighted -> columnHighlight
                                else -> Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selected && pendingNibble.isNotEmpty()) {
                            pendingNibble
                        } else {
                            displayByte.toHexCellText()
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = if (selected) selectedTextColor else byteTextColor,
                        textAlign = TextAlign.Center
                    )
                }
            }

            repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW - bytes.size) { padIndex ->
                val column = bytes.size + padIndex
                if (column == 4) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        VerticalDivider()

        Row(
            modifier = Modifier
                .width(AsciiColumnWidth)
                .fillMaxHeight()
                .pointerInput(offset, bytes.size) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val charWidthPx = size.width / HexFileDataManager.VISUAL_BYTES_PER_ROW.toFloat()
                            val column = (tapOffset.x / charWidthPx).toInt().coerceIn(0, bytes.lastIndex)
                            if (column in bytes.indices) {
                                onOffsetSelected(offset + column)
                            }
                        },
                        onLongPress = { tapOffset ->
                            val charWidthPx = size.width / HexFileDataManager.VISUAL_BYTES_PER_ROW.toFloat()
                            val column = (tapOffset.x / charWidthPx).toInt().coerceIn(0, bytes.lastIndex)
                            if (column in bytes.indices) {
                                val targetOffset = offset + column
                                val displayByte = patchMap[targetOffset]?.newByte ?: bytes[column]
                                onByteLongPressed(HexContextTarget(offset = targetOffset, byte = displayByte))
                            }
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            bytes.forEachIndexed { column, byte ->
                val byteOffset = offset + column
                val displayByte = patchMap[byteOffset]?.newByte ?: byte
                val selected = byteOffset == selectedOffset
                val inSelectionRange = selectionRange?.contains(byteOffset) == true
                val patched = patchMap.containsKey(byteOffset)
                val bookmarked = bookmarkedOffsets.contains(byteOffset)
                val highlighted = !selected && (rowSelected || column == selectedColumn)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            when {
                                selected -> selectedColor
                                inSelectionRange -> rangeColor
                                patched -> patchedColor
                                bookmarked -> bookmarkColor
                                highlighted -> columnHighlight
                                else -> Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayByte.toPrintableAscii(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = if (selected) selectedTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW - bytes.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HexContextMenu(
    target: HexContextTarget?,
    onDismiss: () -> Unit,
    onCopyOffset: (HexContextTarget) -> Unit,
    onCopyByte: (HexContextTarget) -> Unit,
    onCopyAscii: (HexContextTarget) -> Unit,
    onSetSelectionStart: (HexContextTarget) -> Unit,
    onSetSelectionEnd: (HexContextTarget) -> Unit,
    onExportSelection: () -> Unit,
    onToggleBookmark: (HexContextTarget) -> Unit,
    onEditHere: (HexContextTarget) -> Unit
) {
    DropdownMenu(
        expanded = target != null,
        onDismissRequest = onDismiss
    ) {
        if (target != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_copy_offset)) },
                onClick = {
                    onCopyOffset(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_copy_byte)) },
                onClick = {
                    onCopyByte(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_copy_ascii)) },
                onClick = {
                    onCopyAscii(target)
                    onDismiss()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_set_selection_start)) },
                onClick = {
                    onSetSelectionStart(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_set_selection_end)) },
                onClick = {
                    onSetSelectionEnd(target)
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_export_selection)) },
                onClick = {
                    onExportSelection()
                    onDismiss()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_toggle_bookmark)) },
                onClick = {
                    onToggleBookmark(target)
                    onDismiss()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Strings.hex_menu_edit_here)) },
                onClick = {
                    onEditHere(target)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun HexPlaceholderLine(offset: Long) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest

    HexVisualRowShell(offset = offset, backgroundColor = MaterialTheme.colorScheme.surface) {
        repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW) { column ->
            if (column == 4) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .padding(horizontal = 3.dp)
                    .background(placeholderColor, MaterialTheme.shapes.extraSmall)
            )
        }
    }
}

@Composable
private fun HexVisualRowShell(
    offset: Long,
    backgroundColor: Color,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(backgroundColor)
            .padding(end = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(AddressColumnWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%08X".format(offset),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        VerticalDivider()
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        VerticalDivider()
        Spacer(modifier = Modifier.width(AsciiColumnWidth))
    }
}

@Composable
private fun HexFooter(
    state: HexViewerState,
    canEdit: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onToggleEditMode: () -> Unit,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onMarkSelectionStart: () -> Unit,
    onMarkSelectionEnd: () -> Unit,
    onClearSelection: () -> Unit,
    onInspectSelection: () -> Unit,
    onExportSelection: () -> Unit,
    onUndoPatch: () -> Unit,
    onRedoPatch: () -> Unit,
    onDiscardPatch: (Long) -> Unit,
    onSavePatches: () -> Unit,
    onDiscardPatches: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var showGotoDialog by remember { mutableStateOf(false) }
    var showPatchDetailsDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var areFooterToolsExpanded by remember { mutableStateOf(initialHexFooterToolsExpanded()) }
    val horizontalScrollState = rememberScrollState()
    val selectionRange = state.selectionRange
    val isSelectedBookmarked = isHexBookmarked(state.bookmarkedOffsets, state.selectedOffset)
    val hasPatchActivity = state.stagedPatches.isNotEmpty() || state.redoPatches.isNotEmpty()
    val showFooterDetails = shouldShowHexFooterDetails(
        isUserExpanded = areFooterToolsExpanded,
        hasPatchActivity = hasPatchActivity
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onToggleEditMode,
                    enabled = canEdit,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (state.isEditMode) Icons.Filled.Keyboard else Icons.Filled.Edit,
                        contentDescription = stringResource(Strings.hex_edit_toggle_desc),
                        tint = if (state.isEditMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Text(
                    text = stringResource(Strings.hex_footer_size, formatFileSize(state.fileSize)),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = stringResource(Strings.hex_footer_offset).format(state.currentOffset),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = stringResource(Strings.hex_selected_offset, state.selectedOffset),
                    style = MaterialTheme.typography.bodySmall
                )

                TextButton(onClick = onGoBack, enabled = canGoBack) {
                    Text(stringResource(Strings.hex_history_back))
                }
                TextButton(onClick = onGoForward, enabled = canGoForward) {
                    Text(stringResource(Strings.hex_history_forward))
                }
                TextButton(onClick = { showGotoDialog = true }, enabled = canEdit) {
                    Text(stringResource(Strings.btn_goto))
                }
                Text(
                    text = stringResource(Strings.hex_bookmark_count, state.bookmarkedOffsets.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onToggleBookmark, enabled = canEdit) {
                    Text(
                        stringResource(
                            if (isSelectedBookmarked) {
                                Strings.hex_bookmark_unmark
                            } else {
                                Strings.hex_bookmark_mark
                            }
                        )
                    )
                }
                TextButton(
                    onClick = { showBookmarksDialog = true },
                    enabled = state.bookmarkedOffsets.isNotEmpty()
                ) {
                    Text(stringResource(Strings.hex_bookmark_list))
                }
                TextButton(onClick = { areFooterToolsExpanded = !areFooterToolsExpanded }) {
                    Text(
                        stringResource(
                            if (areFooterToolsExpanded) {
                                Strings.content_desc_collapse
                            } else {
                                Strings.action_more
                            }
                        )
                    )
                }
            }

            if (showFooterDetails) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (selectionRange == null) {
                            stringResource(Strings.hex_selection_empty)
                        } else {
                            stringResource(
                                Strings.hex_selection_range,
                                selectionRange.firstOffset,
                                selectionRange.lastOffset,
                                selectionRange.byteCount
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onMarkSelectionStart, enabled = canEdit) {
                        Text(stringResource(Strings.hex_mark_start))
                    }
                    TextButton(onClick = onMarkSelectionEnd, enabled = canEdit) {
                        Text(stringResource(Strings.hex_mark_end))
                    }
                    TextButton(onClick = onClearSelection, enabled = selectionRange != null) {
                        Text(stringResource(Strings.hex_clear_selection))
                    }
                    TextButton(onClick = onInspectSelection, enabled = canEdit) {
                        Text(stringResource(Strings.hex_inspect_selection))
                    }
                    TextButton(onClick = onExportSelection, enabled = canEdit) {
                        Text(stringResource(Strings.hex_export_selection))
                    }
                }
            }

            if (showFooterDetails && hasPatchActivity) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Strings.hex_patch_pending, state.stagedPatches.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showPatchDetailsDialog = true }, enabled = state.stagedPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_details))
                    }
                    TextButton(onClick = onUndoPatch, enabled = state.stagedPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_undo))
                    }
                    TextButton(onClick = onRedoPatch, enabled = state.redoPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_redo))
                    }
                    TextButton(onClick = onSavePatches, enabled = state.stagedPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_save))
                    }
                    TextButton(onClick = onDiscardPatches, enabled = state.stagedPatches.isNotEmpty() || state.redoPatches.isNotEmpty()) {
                        Text(stringResource(Strings.hex_patch_discard))
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showGotoDialog) {
        GotoOffsetDialog(
            maxOffset = state.fileSize,
            currentOffset = state.selectedOffset,
            onDismiss = { showGotoDialog = false },
            onConfirm = { offset ->
                onGotoOffset(offset)
                showGotoDialog = false
            }
        )
    }

    if (showPatchDetailsDialog) {
        HexPatchDetailsDialog(
            patches = sortHexPatchesForDisplay(state.stagedPatches),
            onDismiss = { showPatchDetailsDialog = false },
            onGotoOffset = { offset ->
                showPatchDetailsDialog = false
                onGotoOffset(offset)
            },
            onDiscardPatch = onDiscardPatch
        )
    }

    if (showBookmarksDialog) {
        HexBookmarksDialog(
            bookmarks = sortHexBookmarks(state.bookmarkedOffsets),
            currentOffset = state.selectedOffset,
            onDismiss = { showBookmarksDialog = false },
            onGotoOffset = { offset ->
                showBookmarksDialog = false
                onGotoOffset(offset)
            },
            onRemoveBookmark = onRemoveBookmark
        )
    }
}

@Composable
private fun HexBookmarksDialog(
    bookmarks: List<Long>,
    currentOffset: Long,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_bookmarks_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Text(
                        text = stringResource(Strings.hex_bookmarks_summary, bookmarks.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TinaDialogCard {
                    if (bookmarks.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_bookmarks_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = bookmarks.size,
                                key = { index -> bookmarks[index] }
                            ) { index ->
                                HexBookmarkRow(
                                    offset = bookmarks[index],
                                    isCurrent = bookmarks[index] == currentOffset,
                                    onGotoOffset = onGotoOffset,
                                    onRemoveBookmark = onRemoveBookmark
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexBookmarkRow(
    offset: Long,
    isCurrent: Boolean,
    onGotoOffset: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(Strings.hex_bookmark_row_meta, offset),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                if (isCurrent) {
                    Text(
                        text = stringResource(Strings.hex_bookmark_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(onClick = { onGotoOffset(offset) }) {
                Text(stringResource(Strings.hex_bookmark_jump))
            }
            TextButton(onClick = { onRemoveBookmark(offset) }) {
                Text(stringResource(Strings.hex_bookmark_remove))
            }
        }
    }
}

@Composable
private fun HexPatchDetailsDialog(
    patches: List<HexPatch>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onDiscardPatch: (Long) -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_patch_details_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.hex_patch_details_summary, patches.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipData
                                            .newPlainText("hex-patch-r2", formatHexPatchScript(patches))
                                            .toClipEntry()
                                    )
                                }
                            },
                            enabled = patches.isNotEmpty()
                        ) {
                            Text(stringResource(Strings.hex_patch_copy_r2))
                        }
                    }
                }
                TinaDialogCard {
                    if (patches.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_patch_details_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = patches.size,
                                key = { index -> patches[index].offset }
                            ) { index ->
                                HexPatchDetailsRow(
                                    patch = patches[index],
                                    onGotoOffset = onGotoOffset,
                                    onDiscardPatch = onDiscardPatch
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HexPatchDetailsRow(
    patch: HexPatch,
    onGotoOffset: (Long) -> Unit,
    onDiscardPatch: (Long) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(Strings.hex_patch_row_meta, patch.offset),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        Strings.hex_patch_row_change,
                        patch.originalByte.toHexCellText(),
                        patch.newByte.toHexCellText()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onGotoOffset(patch.offset) }) {
                Text(stringResource(Strings.hex_patch_jump))
            }
            TextButton(onClick = { onDiscardPatch(patch.offset) }) {
                Text(stringResource(Strings.hex_patch_discard_one))
            }
        }
    }
}

@Composable
private fun HexSelectionInspectorDialog(
    inspector: HexSelectionInspector,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_selection_inspector_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_selection_inspector_range,
                                inspector.range.firstOffset,
                                inspector.range.lastOffset,
                                inspector.range.byteCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                if (inspector.truncated) {
                                    Strings.hex_selection_inspector_sample_truncated
                                } else {
                                    Strings.hex_selection_inspector_sample
                                },
                                inspector.inspectedByteCount,
                                inspector.range.byteCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SelectionInspectorValueRow(
                            label = stringResource(Strings.hex_selection_inspector_hex),
                            value = inspector.hexPreview
                        )
                        SelectionInspectorValueRow(
                            label = stringResource(Strings.hex_selection_inspector_ascii),
                            value = inspector.asciiPreview
                        )
                        inspector.utf8Preview?.let { utf8Preview ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_utf8),
                                value = utf8Preview
                            )
                        }
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        inspector.unsigned8?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u8),
                                value = value
                            )
                        }
                        inspector.signed8?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_i8),
                                value = value
                            )
                        }
                        inspector.unsigned16LittleEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u16_le),
                                value = value
                            )
                        }
                        inspector.unsigned16BigEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u16_be),
                                value = value
                            )
                        }
                        inspector.unsigned32LittleEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u32_le),
                                value = value
                            )
                        }
                        inspector.unsigned32BigEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u32_be),
                                value = value
                            )
                        }
                        inspector.unsigned64LittleEndianHex?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u64_le),
                                value = value
                            )
                        }
                        inspector.unsigned64BigEndianHex?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u64_be),
                                value = value
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun SelectionInspectorValueRow(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HexKeyboard(
    onNibbleClick: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HexKeyboardRow(('0'..'7').toList(), onNibbleClick)
            HexKeyboardRow(listOf('8', '9', 'A', 'B', 'C', 'D', 'E', 'F'), onNibbleClick)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HexKeyboardAction(
                    icon = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(Strings.hex_keyboard_backspace_desc),
                    onClick = onBackspace,
                    modifier = Modifier.weight(1f)
                )
                HexKeyboardAction(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(Strings.hex_keyboard_close_desc),
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HexKeyboardRow(
    keys: List<Char>,
    onNibbleClick: (Char) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        keys.forEach { key ->
            Surface(
                onClick = { onNibbleClick(key) },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = key.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun HexKeyboardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExportSelectionDialog(
    onDismiss: () -> Unit,
    onFormatSelected: (HexExportFormat) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_export_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_hex_dump),
                            onClick = { onFormatSelected(HexExportFormat.HEX_DUMP) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_c_array),
                            onClick = { onFormatSelected(HexExportFormat.C_ARRAY) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_kotlin_byte_array),
                            onClick = { onFormatSelected(HexExportFormat.KOTLIN_BYTE_ARRAY) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_base64),
                            onClick = { onFormatSelected(HexExportFormat.BASE64) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_ascii),
                            onClick = { onFormatSelected(HexExportFormat.ASCII) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun StringsListDialog(
    entries: List<HexStringEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var query by remember(entries) { mutableStateOf("") }
    var encodingFilter by remember(entries) { mutableStateOf(StringEntryEncodingFilter.ALL) }
    val filteredEntries = remember(entries, query, encodingFilter) {
        filterStringEntries(
            entries = entries,
            query = query,
            encodingFilter = encodingFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_strings_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_strings_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.ALL,
                                selected = encodingFilter == StringEntryEncodingFilter.ALL,
                                onClick = { encodingFilter = StringEntryEncodingFilter.ALL }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.ASCII,
                                selected = encodingFilter == StringEntryEncodingFilter.ASCII,
                                onClick = { encodingFilter = StringEntryEncodingFilter.ASCII }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.UTF_8,
                                selected = encodingFilter == StringEntryEncodingFilter.UTF_8,
                                onClick = { encodingFilter = StringEntryEncodingFilter.UTF_8 }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.UTF_16LE,
                                selected = encodingFilter == StringEntryEncodingFilter.UTF_16LE,
                                onClick = { encodingFilter = StringEntryEncodingFilter.UTF_16LE }
                            )
                            StringEncodingFilterButton(
                                filter = StringEntryEncodingFilter.UTF_16BE,
                                selected = encodingFilter == StringEntryEncodingFilter.UTF_16BE,
                                onClick = { encodingFilter = StringEntryEncodingFilter.UTF_16BE }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_strings_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_strings_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.offset}-${entry.encoding}-${entry.value}"
                                }
                            ) { index ->
                                val entry = filteredEntries[index]
                                StringEntryRow(
                                    entry = entry,
                                    onClick = { onGotoOffset(entry.offset) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.hex_strings_copy_filtered),
                enabled = filteredEntries.isNotEmpty(),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipData.newPlainText(
                                "hex-strings",
                                formatStringEntriesExport(filteredEntries)
                            ).toClipEntry()
                        )
                    }
                }
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexStringsDialog(
    entries: List<HexDexStringEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexStringEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_strings_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_strings_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_strings_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_strings_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.dataOffset}-${entry.value}"
                                }
                            ) { index ->
                                DexStringEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexStringEntryRow(
    entry: HexDexStringEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.dataOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_string_meta,
                    entry.index,
                    entry.stringIdOffset,
                    entry.dataOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DexTypesDialog(
    entries: List<HexDexTypeEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexTypeEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_types_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_types_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_types_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_types_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.typeIdOffset}-${entry.descriptor}"
                                }
                            ) { index ->
                                DexTypeEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexTypeEntryRow(
    entry: HexDexTypeEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.typeIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.descriptor,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_type_meta,
                    entry.index,
                    entry.typeIdOffset,
                    entry.descriptorStringIndex
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DexProtosDialog(
    entries: List<HexDexProtoEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexProtoEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_protos_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_protos_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_protos_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_protos_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.protoIdOffset}-${entry.signature}"
                                }
                            ) { index ->
                                DexProtoEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexProtoEntryRow(
    entry: HexDexProtoEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.protoIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.signature,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_proto_meta,
                    entry.index,
                    entry.protoIdOffset,
                    entry.shorty,
                    entry.shortyStringIndex,
                    entry.returnTypeIndex,
                    entry.parametersOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DexFieldsDialog(
    entries: List<HexDexFieldEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexFieldEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_fields_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_fields_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_fields_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_fields_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.fieldIdOffset}-${entry.name}"
                                }
                            ) { index ->
                                DexFieldEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexFieldEntryRow(
    entry: HexDexFieldEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.fieldIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_field_display,
                    entry.classDescriptor,
                    entry.name,
                    entry.typeDescriptor
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_field_meta,
                    entry.index,
                    entry.fieldIdOffset,
                    entry.classIndex,
                    entry.typeIndex,
                    entry.nameStringIndex
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DexMethodsDialog(
    entries: List<HexDexMethodEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexMethodEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_methods_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_methods_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_methods_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_methods_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.methodIdOffset}-${entry.name}"
                                }
                            ) { index ->
                                DexMethodEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexMethodEntryRow(
    entry: HexDexMethodEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.methodIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_method_display,
                    entry.classDescriptor,
                    entry.name,
                    entry.protoSignature
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_method_meta,
                    entry.index,
                    entry.methodIdOffset,
                    entry.classIndex,
                    entry.protoIndex,
                    entry.protoShorty,
                    entry.nameStringIndex
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DexClassesDialog(
    entries: List<HexDexClassDefEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexClassDefEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_classes_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_classes_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_classes_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_classes_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.classDefOffset}-${entry.classDescriptor}"
                                }
                            ) { index ->
                                DexClassDefEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexClassDefEntryRow(
    entry: HexDexClassDefEntry,
    onGotoOffset: (Long) -> Unit
) {
    val superclassLabel = entry.superclassDescriptor ?: stringResource(Strings.hex_dex_index_none)
    Surface(
        onClick = { onGotoOffset(entry.classDefOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.classDescriptor,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_class_meta,
                    entry.index,
                    entry.classDefOffset,
                    entry.classIndex,
                    entry.accessFlags,
                    superclassLabel,
                    entry.classDataOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.sourceFile?.let { sourceFile ->
                Text(
                    text = stringResource(Strings.hex_dex_class_source, sourceFile),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DexClassDataMethodsDialog(
    entries: List<HexDexClassDataMethodEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexClassDataMethodEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_class_data_methods_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_class_data_methods_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_class_data_methods_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_class_data_methods_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.entryOffset}-${entry.codeOffset}-${entry.methodName}"
                                }
                            ) { index ->
                                DexClassDataMethodEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexClassDataMethodEntryRow(
    entry: HexDexClassDataMethodEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = if (entry.codeOffset > 0L) entry.codeOffset else entry.entryOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_class_data_method_display,
                    entry.methodClassDescriptor,
                    entry.methodName,
                    entry.protoSignature
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_class_data_method_meta,
                    entry.index,
                    dexClassDataMethodKindLabel(entry.kind),
                    entry.methodIndex,
                    entry.accessFlags,
                    entry.entryOffset,
                    entry.codeOffset,
                    dexClassDataMethodExecutionKindLabel(entry.executionKind)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DexCodeItemsDialog(
    entries: List<HexDexCodeItemEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexCodeItemEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_code_items_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_code_items_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_code_items_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_code_items_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.codeOffset}-${entry.methodName}-${entry.firstOpcode}"
                                }
                            ) { index ->
                                DexCodeItemEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexCodeItemEntryRow(
    entry: HexDexCodeItemEntry,
    onGotoOffset: (Long) -> Unit
) {
    val previewText = if (entry.previewCodeUnitsHex.isEmpty()) {
        stringResource(Strings.hex_dex_code_item_preview_empty)
    } else {
        entry.previewCodeUnitsHex
    }
    Surface(
        onClick = { onGotoOffset(entry.codeOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_code_item_display,
                    entry.methodClassDescriptor,
                    entry.methodName,
                    entry.protoSignature
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_code_item_meta,
                    entry.index,
                    entry.methodIndex,
                    entry.registersSize,
                    entry.insSize,
                    entry.outsSize,
                    entry.triesSize,
                    entry.debugInfoOffset,
                    entry.insnsSize,
                    entry.firstOpcodeName,
                    entry.firstOpcode
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_code_item_preview,
                    previewText
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DexCallReferencesDialog(
    entries: List<HexDexCallReferenceEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexCallReferenceEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_call_references_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_call_references_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_dex_call_references_filter_count,
                                    filteredEntries.size,
                                    entries.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    onMarkOffsets(filteredEntries.map { entry -> entry.instructionOffset })
                                },
                                enabled = filteredEntries.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_call_references_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.instructionOffset}-${entry.targetMethodIndex}"
                                }
                            ) { index ->
                                DexCallReferenceEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexCallReferenceEntryRow(
    entry: HexDexCallReferenceEntry,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.instructionOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_call_reference_display,
                    entry.callerClassDescriptor,
                    entry.callerMethodName,
                    entry.targetClassDescriptor,
                    entry.targetMethodName
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_call_reference_meta,
                    entry.index,
                    entry.opcodeName,
                    entry.opcode,
                    entry.callerMethodIndex,
                    entry.targetMethodIndex,
                    entry.instructionOffset,
                    entry.codeOffset,
                    entry.targetMethodIdOffset ?: -1L
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_call_reference_signature,
                    entry.callerProtoSignature,
                    entry.targetProtoSignature
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { onMarkOffset(entry.instructionOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
private fun DexStringReferencesDialog(
    entries: List<HexDexStringReferenceEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexStringReferenceEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_string_references_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_string_references_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_dex_string_references_filter_count,
                                    filteredEntries.size,
                                    entries.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    onMarkOffsets(filteredEntries.map { entry -> entry.instructionOffset })
                                },
                                enabled = filteredEntries.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_string_references_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.instructionOffset}-${entry.stringIndex}"
                                }
                            ) { index ->
                                DexStringReferenceEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexStringReferenceEntryRow(
    entry: HexDexStringReferenceEntry,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.instructionOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_string_reference_display,
                    entry.callerClassDescriptor,
                    entry.callerMethodName,
                    entry.value
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_string_reference_meta,
                    entry.index,
                    entry.opcodeName,
                    entry.opcode,
                    entry.callerMethodIndex,
                    entry.stringIndex,
                    entry.instructionOffset,
                    entry.codeOffset,
                    entry.stringIdOffset ?: -1L,
                    entry.stringDataOffset ?: -1L
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onMarkOffset(entry.instructionOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
private fun DexFieldReferencesDialog(
    entries: List<HexDexFieldReferenceEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexFieldReferenceEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_field_references_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_field_references_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_dex_field_references_filter_count,
                                    filteredEntries.size,
                                    entries.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    onMarkOffsets(filteredEntries.map { entry -> entry.instructionOffset })
                                },
                                enabled = filteredEntries.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_field_references_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.instructionOffset}-${entry.fieldIndex}"
                                }
                            ) { index ->
                                DexFieldReferenceEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexFieldReferenceEntryRow(
    entry: HexDexFieldReferenceEntry,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.instructionOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_field_reference_display,
                    entry.callerClassDescriptor,
                    entry.callerMethodName,
                    entry.fieldClassDescriptor,
                    entry.fieldName,
                    entry.fieldTypeDescriptor
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_field_reference_meta,
                    entry.index,
                    entry.opcodeName,
                    entry.opcode,
                    entry.callerMethodIndex,
                    entry.fieldIndex,
                    entry.instructionOffset,
                    entry.codeOffset,
                    entry.fieldIdOffset ?: -1L
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onMarkOffset(entry.instructionOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
private fun DexMapEntriesDialog(
    entries: List<HexDexMapEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var mapFilter by remember(entries) { mutableStateOf(DexMapEntryFilter.ALL) }
    val filteredEntries = remember(entries, query, mapFilter) {
        filterDexMapEntries(
            entries = entries,
            query = query,
            mapFilter = mapFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_map_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_map_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.ALL,
                                selected = mapFilter == DexMapEntryFilter.ALL,
                                onClick = { mapFilter = DexMapEntryFilter.ALL }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.IDS,
                                selected = mapFilter == DexMapEntryFilter.IDS,
                                onClick = { mapFilter = DexMapEntryFilter.IDS }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.CLASS_DATA,
                                selected = mapFilter == DexMapEntryFilter.CLASS_DATA,
                                onClick = { mapFilter = DexMapEntryFilter.CLASS_DATA }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.CODE,
                                selected = mapFilter == DexMapEntryFilter.CODE,
                                onClick = { mapFilter = DexMapEntryFilter.CODE }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.DATA,
                                selected = mapFilter == DexMapEntryFilter.DATA,
                                onClick = { mapFilter = DexMapEntryFilter.DATA }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_dex_map_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_map_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.type}-${entry.offset}"
                                }
                            ) { index ->
                                DexMapEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun DexMapFilterButton(
    filter: DexMapEntryFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = dexMapFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun DexMapEntryRow(
    entry: HexDexMapEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.offset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.typeName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_map_meta,
                    entry.index,
                    entry.type,
                    entry.size,
                    entry.offset,
                    entry.entryFileOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ArchiveEntriesDialog(
    entries: List<HexArchiveEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var archiveFilter by remember(entries) { mutableStateOf(ArchiveEntryFilter.ALL) }
    val filteredEntries = remember(entries, query, archiveFilter) {
        filterArchiveEntries(
            entries = entries,
            query = query,
            archiveFilter = archiveFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_entries_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_archive_entries_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArchiveEntryFilterButton(
                                filter = ArchiveEntryFilter.ALL,
                                selected = archiveFilter == ArchiveEntryFilter.ALL,
                                onClick = { archiveFilter = ArchiveEntryFilter.ALL }
                            )
                            ArchiveEntryFilterButton(
                                filter = ArchiveEntryFilter.DEX,
                                selected = archiveFilter == ArchiveEntryFilter.DEX,
                                onClick = { archiveFilter = ArchiveEntryFilter.DEX }
                            )
                            ArchiveEntryFilterButton(
                                filter = ArchiveEntryFilter.NATIVE_LIBRARIES,
                                selected = archiveFilter == ArchiveEntryFilter.NATIVE_LIBRARIES,
                                onClick = { archiveFilter = ArchiveEntryFilter.NATIVE_LIBRARIES }
                            )
                            ArchiveEntryFilterButton(
                                filter = ArchiveEntryFilter.MANIFEST,
                                selected = archiveFilter == ArchiveEntryFilter.MANIFEST,
                                onClick = { archiveFilter = ArchiveEntryFilter.MANIFEST }
                            )
                            ArchiveEntryFilterButton(
                                filter = ArchiveEntryFilter.RESOURCES,
                                selected = archiveFilter == ArchiveEntryFilter.RESOURCES,
                                onClick = { archiveFilter = ArchiveEntryFilter.RESOURCES }
                            )
                            ArchiveEntryFilterButton(
                                filter = ArchiveEntryFilter.SIGNATURE,
                                selected = archiveFilter == ArchiveEntryFilter.SIGNATURE,
                                onClick = { archiveFilter = ArchiveEntryFilter.SIGNATURE }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_archive_entries_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_archive_entries_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.localHeaderOffset}-${entry.name}"
                                }
                            ) { index ->
                                ArchiveEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ArchiveEntryFilterButton(
    filter: ArchiveEntryFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = archiveEntryFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ArchiveEntryRow(
    entry: HexArchiveEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.localHeaderOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_entry_meta,
                    entry.index,
                    entry.compressionMethod,
                    archiveEntryFlagsLabel(entry),
                    entry.crc32,
                    entry.compressedSize,
                    entry.uncompressedSize,
                    entry.localHeaderOffset,
                    archiveEntryDataOffsetLabel(entry),
                    entry.centralDirectoryOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_entry_data_range,
                    archiveEntryDataRangeLabel(entry),
                    archiveEntryDataRangeStatusLabel(entry.dataRangeStatus)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = archiveEntryDataRangeStatusColor(entry.dataRangeStatus)
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_entry_local_header,
                    archiveEntryLocalHeaderLabel(entry),
                    archiveEntryLocalHeaderStatusLabel(entry.localHeaderConsistency)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = archiveEntryLocalHeaderStatusColor(entry.localHeaderConsistency),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = archiveEntryNameRiskLabel(entry.nameRisks),
                style = MaterialTheme.typography.labelSmall,
                color = archiveEntryNameRiskColor(entry.nameRisks),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onGotoOffset(entry.localHeaderOffset) }) {
                    Text(stringResource(Strings.hex_archive_entry_jump_local))
                }
                entry.dataOffset?.let { dataOffset ->
                    TextButton(onClick = { onGotoOffset(dataOffset) }) {
                        Text(stringResource(Strings.hex_archive_entry_jump_data))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveDexSummariesDialog(
    entries: List<HexArchiveDexSummary>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterArchiveDexSummaries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_dex_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_archive_dex_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_archive_dex_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_archive_dex_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.entryName}-${entry.localHeaderOffset}"
                                }
                            ) { index ->
                                ArchiveDexSummaryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ArchiveDexSummaryRow(
    entry: HexArchiveDexSummary,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.localHeaderOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.entryName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_dex_meta,
                    entry.dex.version,
                    entry.dex.stringIdsSize,
                    entry.dex.protoIdsSize,
                    entry.dex.fieldIdsSize,
                    entry.dex.methodIdsSize,
                    entry.dex.classDefsSize,
                    entry.analyzedBytes,
                    archiveDexTruncatedLabel(entry.truncated),
                    entry.localHeaderOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.dex.stringEntries.isNotEmpty()) {
                Text(
                    text = stringResource(
                        Strings.hex_archive_dex_strings_preview,
                        entry.dex.stringEntries.dexStringValuesPreview()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.dex.nativeMethodCount > 0) {
                Text(
                    text = stringResource(
                        Strings.hex_archive_dex_native_methods,
                        entry.dex.nativeMethodCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ArchiveManifestDialog(
    manifest: HexArchiveManifestSummary,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_manifest_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_archive_manifest_meta,
                                manifest.rootElementName ?: stringResource(Strings.hex_archive_manifest_root_unknown),
                                manifest.packageName ?: stringResource(Strings.hex_archive_manifest_package_unknown),
                                manifest.stringCount,
                                manifest.elementCount,
                                manifest.analyzedBytes,
                                archiveDexTruncatedLabel(manifest.truncated),
                                manifest.localHeaderOffset
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { onGotoOffset(manifest.localHeaderOffset) }) {
                            Text(stringResource(Strings.hex_archive_manifest_jump_local))
                        }
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_archive_manifest_permissions_title,
                                manifest.permissions.size
                            ),
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (manifest.permissions.isEmpty()) {
                            Text(
                                text = stringResource(Strings.hex_archive_manifest_permissions_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    count = manifest.permissions.size,
                                    key = { index -> "$index-${manifest.permissions[index]}" }
                                ) { index ->
                                    ArchiveManifestPermissionRow(permission = manifest.permissions[index])
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ArchiveManifestPermissionRow(permission: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = permission,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArchiveResourcesDialog(
    resources: HexArchiveResourcesSummary,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_resources_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_archive_resources_meta,
                                resources.packages.size,
                                resources.packageCountFromHeader,
                                resources.globalStringCount,
                                resources.typeSpecCount,
                                resources.typeChunkCount,
                                resources.analyzedBytes,
                                archiveDexTruncatedLabel(resources.truncated),
                                resources.localHeaderOffset
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { onGotoOffset(resources.localHeaderOffset) }) {
                            Text(stringResource(Strings.hex_archive_resources_jump_local))
                        }
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_archive_resources_packages_title,
                                resources.packages.size
                            ),
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (resources.packages.isEmpty()) {
                            Text(
                                text = stringResource(Strings.hex_archive_resources_packages_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    count = resources.packages.size,
                                    key = { index ->
                                        val resourcePackage = resources.packages[index]
                                        "${resourcePackage.id}-${resourcePackage.name}"
                                    }
                                ) { index ->
                                    ArchiveResourcePackageRow(resourcePackage = resources.packages[index])
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ArchiveResourcePackageRow(resourcePackage: HexArchiveResourcePackage) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_archive_resources_package_display,
                    resourcePackage.id,
                    resourcePackage.name.ifBlank { stringResource(Strings.hex_archive_resources_package_unknown) }
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_resources_package_meta,
                    resourcePackage.typeStringCount,
                    resourcePackage.keyStringCount,
                    resourcePackage.typeSpecCount,
                    resourcePackage.typeChunkCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ArchiveNativeLibrariesDialog(
    entries: List<HexArchiveNativeLibrarySummary>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var loadModeFilter by remember(entries) { mutableStateOf(ArchiveNativeLibraryLoadModeFilter.ALL) }
    val filteredEntries = remember(entries, query, loadModeFilter) {
        filterArchiveNativeLibrarySummaries(
            entries = entries,
            query = query,
            loadModeFilter = loadModeFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_native_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_archive_native_dialog_meta,
                                entries.size,
                                entries.nativeLibraryAbiPreview(),
                                entries.sumOf { entry -> entry.obfuscationMarkers.size }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_archive_native_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.ALL,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.ALL,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.ALL }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION }
                            )
                            ArchiveNativeLoadModeFilterButton(
                                filter = ArchiveNativeLibraryLoadModeFilter.UNKNOWN,
                                selected = loadModeFilter == ArchiveNativeLibraryLoadModeFilter.UNKNOWN,
                                onClick = { loadModeFilter = ArchiveNativeLibraryLoadModeFilter.UNKNOWN }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_archive_native_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_archive_native_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.localHeaderOffset}-${entry.entryName}"
                                }
                            ) { index ->
                                ArchiveNativeLibraryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ArchiveNativeLoadModeFilterButton(
    filter: ArchiveNativeLibraryLoadModeFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = archiveNativeLoadModeFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ArchiveNativeLibraryRow(
    entry: HexArchiveNativeLibrarySummary,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_archive_native_entry_display,
                    entry.abi.ifBlank { stringResource(Strings.hex_archive_native_unknown) },
                    entry.fileName
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_native_entry_meta,
                    entry.machineName ?: stringResource(Strings.hex_archive_native_unknown),
                    archiveNativeBitnessLabel(entry),
                    entry.compressionMethod,
                    entry.compressedSize,
                    entry.uncompressedSize,
                    entry.analyzedBytes,
                    archiveDexTruncatedLabel(entry.truncated),
                    entry.crc32,
                    entry.localHeaderOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_native_load_mode,
                    archiveNativeLoadModeLabel(entry.loadMode),
                    archiveNativePageAlignmentLabel(entry)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.obfuscationMarkers.take(MAX_ANALYSIS_PANEL_ITEMS).forEach { marker ->
                Text(
                    text = stringResource(
                        Strings.hex_archive_native_obfuscation_marker,
                        hexObfuscationFindingLabel(marker.type),
                        marker.evidence.compactForAnalysisPanel(),
                        marker.relativeOffset ?: 0L
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onGotoOffset(entry.localHeaderOffset) }) {
                    Text(stringResource(Strings.hex_archive_entry_jump_local))
                }
                entry.dataOffset?.let { dataOffset ->
                    TextButton(onClick = { onGotoOffset(dataOffset) }) {
                        Text(stringResource(Strings.hex_archive_entry_jump_data))
                    }
                }
            }
        }
    }
}

@Composable
private fun archiveNativeBitnessLabel(entry: HexArchiveNativeLibrarySummary): String = when {
    !entry.isElf -> stringResource(Strings.hex_archive_native_not_elf)
    entry.is64Bit == true && entry.endian != null -> stringResource(
        Strings.hex_archive_native_elf64,
        hexEndianLabel(entry.endian)
    )
    entry.is64Bit == false && entry.endian != null -> stringResource(
        Strings.hex_archive_native_elf32,
        hexEndianLabel(entry.endian)
    )
    else -> stringResource(Strings.hex_archive_native_unknown)
}

@Composable
private fun archiveNativeLoadModeFilterLabel(filter: ArchiveNativeLibraryLoadModeFilter): String = stringResource(
    when (filter) {
        ArchiveNativeLibraryLoadModeFilter.ALL -> Strings.hex_archive_native_load_filter_all
        ArchiveNativeLibraryLoadModeFilter.DIRECT_MMAP_READY -> Strings.hex_archive_native_load_filter_direct_mmap
        ArchiveNativeLibraryLoadModeFilter.STORED_UNALIGNED -> Strings.hex_archive_native_load_filter_stored_unaligned
        ArchiveNativeLibraryLoadModeFilter.NEEDS_DECOMPRESSION -> Strings.hex_archive_native_load_filter_needs_decompression
        ArchiveNativeLibraryLoadModeFilter.UNKNOWN -> Strings.hex_archive_native_load_filter_unknown
    }
)

@Composable
private fun archiveNativeLoadModeLabel(loadMode: HexArchiveNativeLoadMode): String = stringResource(
    when (loadMode) {
        HexArchiveNativeLoadMode.DIRECT_MMAP_READY -> Strings.hex_archive_native_load_direct_mmap
        HexArchiveNativeLoadMode.STORED_UNALIGNED -> Strings.hex_archive_native_load_stored_unaligned
        HexArchiveNativeLoadMode.NEEDS_DECOMPRESSION -> Strings.hex_archive_native_load_needs_decompression
        HexArchiveNativeLoadMode.UNKNOWN -> Strings.hex_archive_native_load_unknown
    }
)

@Composable
private fun archiveNativePageAlignmentLabel(entry: HexArchiveNativeLibrarySummary): String {
    val remainder = entry.pageAlignmentRemainder ?: return stringResource(Strings.hex_archive_native_unknown)
    return if (remainder == 0L) {
        stringResource(Strings.hex_archive_native_page_aligned)
    } else {
        stringResource(Strings.hex_archive_native_page_remainder, remainder)
    }
}

@Composable
private fun ArchiveZipStructureDialog(
    structure: HexArchiveZipStructure,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_zip_structure_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ArchiveZipStructureRow(
                            title = stringResource(Strings.hex_archive_zip_structure_central_directory),
                            meta = stringResource(
                                Strings.hex_archive_zip_structure_central_meta,
                                structure.centralDirectoryOffset,
                                structure.centralDirectorySize,
                                structure.entryCount
                            ),
                            offset = structure.centralDirectoryOffset,
                            onGotoOffset = onGotoOffset
                        )
                        ArchiveZipStructureRow(
                            title = stringResource(Strings.hex_archive_zip_structure_eocd),
                            meta = stringResource(
                                Strings.hex_archive_zip_structure_eocd_meta,
                                structure.eocdOffset,
                                structure.commentLength
                            ),
                            offset = structure.eocdOffset,
                            onGotoOffset = onGotoOffset
                        )
                        structure.zip64LocatorOffset?.let { offset ->
                            ArchiveZipStructureRow(
                                title = stringResource(Strings.hex_archive_zip_structure_zip64_locator),
                                meta = stringResource(
                                    Strings.hex_archive_zip_structure_zip64_meta,
                                    offset
                                ),
                                offset = offset,
                                onGotoOffset = onGotoOffset
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ArchiveZipStructureRow(
    title: String,
    meta: String,
    offset: Long,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(offset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ArchiveSigningBlockDialog(
    entries: List<HexArchiveSigningBlockEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterArchiveSigningBlockEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_archive_signing_block_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_archive_signing_block_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_archive_signing_block_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_archive_signing_block_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.id}-${entry.pairOffset}"
                                }
                            ) { index ->
                                ArchiveSigningBlockRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ArchiveSigningBlockRow(
    entry: HexArchiveSigningBlockEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.pairOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_archive_signing_block_display,
                    entry.idName,
                    entry.id
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_archive_signing_block_meta,
                    entry.index,
                    entry.valueSize,
                    entry.blockOffset,
                    entry.blockSize,
                    entry.pairOffset,
                    entry.valueOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ElfSectionsDialog(
    sections: List<HexElfSection>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(sections) { mutableStateOf("") }
    var sectionFilter by remember(sections) { mutableStateOf(ElfSectionFilter.ALL) }
    val filteredSections = remember(sections, query, sectionFilter) {
        filterElfSections(
            sections = sections,
            query = query,
            sectionFilter = sectionFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_sections_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_sections_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.ALL,
                                selected = sectionFilter == ElfSectionFilter.ALL,
                                onClick = { sectionFilter = ElfSectionFilter.ALL }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.ALLOCATED,
                                selected = sectionFilter == ElfSectionFilter.ALLOCATED,
                                onClick = { sectionFilter = ElfSectionFilter.ALLOCATED }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.EXECUTABLE,
                                selected = sectionFilter == ElfSectionFilter.EXECUTABLE,
                                onClick = { sectionFilter = ElfSectionFilter.EXECUTABLE }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.WRITABLE,
                                selected = sectionFilter == ElfSectionFilter.WRITABLE,
                                onClick = { sectionFilter = ElfSectionFilter.WRITABLE }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.STRING_TABLE,
                                selected = sectionFilter == ElfSectionFilter.STRING_TABLE,
                                onClick = { sectionFilter = ElfSectionFilter.STRING_TABLE }
                            )
                            ElfSectionFilterButton(
                                filter = ElfSectionFilter.SYMBOL_TABLE,
                                selected = sectionFilter == ElfSectionFilter.SYMBOL_TABLE,
                                onClick = { sectionFilter = ElfSectionFilter.SYMBOL_TABLE }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_sections_filter_count,
                                filteredSections.size,
                                sections.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredSections.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_sections_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredSections.size,
                                key = { index ->
                                    val section = filteredSections[index]
                                    "${section.index}-${section.name}-${section.fileOffset}"
                                }
                            ) { index ->
                                ElfSectionRow(
                                    section = filteredSections[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfSectionFilterButton(
    filter: ElfSectionFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfSectionFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfSectionRow(
    section: HexElfSection,
    onGotoOffset: (Long) -> Unit
) {
    if (section.fileOffset > 0L && section.size > 0L) {
        Surface(
            onClick = { onGotoOffset(section.fileOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSectionRowContent(section)
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSectionRowContent(section)
        }
    }
}

@Composable
private fun ElfSectionRowContent(section: HexElfSection) {
    val unnamedSection = stringResource(Strings.hex_elf_section_name_empty)
    val sectionName = section.name.ifBlank { unnamedSection }
    val typeLabel = elfSectionTypeLabel(section.type)
    val flagsLabel = elfSectionFlagsLabel(section.flags)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = sectionName,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(
                Strings.hex_elf_section_meta,
                section.index,
                typeLabel,
                flagsLabel,
                section.virtualAddress,
                section.fileOffset,
                section.size
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ElfProgramHeadersDialog(
    programHeaders: List<HexElfProgramHeader>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(programHeaders) { mutableStateOf("") }
    var programHeaderFilter by remember(programHeaders) { mutableStateOf(ElfProgramHeaderFilter.ALL) }
    val filteredProgramHeaders = remember(programHeaders, query, programHeaderFilter) {
        filterElfProgramHeaders(
            programHeaders = programHeaders,
            query = query,
            programHeaderFilter = programHeaderFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_program_headers_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_program_headers_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.ALL,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.ALL,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.ALL }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.LOAD,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.LOAD,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.LOAD }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.EXECUTABLE,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.EXECUTABLE,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.EXECUTABLE }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.WRITABLE,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.WRITABLE,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.WRITABLE }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.DYNAMIC,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.DYNAMIC,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.DYNAMIC }
                            )
                            ElfProgramHeaderFilterButton(
                                filter = ElfProgramHeaderFilter.HARDENING,
                                selected = programHeaderFilter == ElfProgramHeaderFilter.HARDENING,
                                onClick = { programHeaderFilter = ElfProgramHeaderFilter.HARDENING }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_program_headers_filter_count,
                                filteredProgramHeaders.size,
                                programHeaders.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredProgramHeaders.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_program_headers_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredProgramHeaders.size,
                                key = { index ->
                                    val programHeader = filteredProgramHeaders[index]
                                    "${programHeader.index}-${programHeader.type}-${programHeader.fileOffset}"
                                }
                            ) { index ->
                                ElfProgramHeaderRow(
                                    programHeader = filteredProgramHeaders[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfProgramHeaderFilterButton(
    filter: ElfProgramHeaderFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfProgramHeaderFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfProgramHeaderRow(
    programHeader: HexElfProgramHeader,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(programHeader.programHeaderFileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = programHeader.typeName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_program_header_meta,
                    programHeader.index,
                    elfProgramHeaderFlagsLabel(programHeader.flags),
                    programHeader.virtualAddress,
                    programHeader.fileOffset,
                    programHeader.fileSize,
                    programHeader.memorySize,
                    programHeader.align
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ElfSectionSegmentsDialog(
    mappings: List<HexElfSectionSegmentMapping>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(mappings) { mutableStateOf("") }
    var sectionSegmentFilter by remember(mappings) { mutableStateOf(ElfSectionSegmentFilter.ALL) }
    val filteredMappings = remember(mappings, query, sectionSegmentFilter) {
        filterElfSectionSegmentMappings(
            mappings = mappings,
            query = query,
            sectionSegmentFilter = sectionSegmentFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_section_segments_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_section_segments_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.ALL,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.ALL,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.ALL }
                            )
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.EXECUTABLE,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.EXECUTABLE,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.EXECUTABLE }
                            )
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.WRITABLE,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.WRITABLE,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.WRITABLE }
                            )
                            ElfSectionSegmentFilterButton(
                                filter = ElfSectionSegmentFilter.READABLE,
                                selected = sectionSegmentFilter == ElfSectionSegmentFilter.READABLE,
                                onClick = { sectionSegmentFilter = ElfSectionSegmentFilter.READABLE }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_section_segments_filter_count,
                                filteredMappings.size,
                                mappings.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredMappings.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_section_segments_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredMappings.size,
                                key = { index ->
                                    val mapping = filteredMappings[index]
                                    "${mapping.sectionIndex}-${mapping.segmentIndex}-${mapping.sectionFileOffset}"
                                }
                            ) { index ->
                                ElfSectionSegmentRow(
                                    mapping = filteredMappings[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfSectionSegmentFilterButton(
    filter: ElfSectionSegmentFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfSectionSegmentFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfSectionSegmentRow(
    mapping: HexElfSectionSegmentMapping,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(mapping.sectionFileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        val unnamedSection = stringResource(Strings.hex_elf_section_name_empty)
        val sectionName = mapping.sectionName.ifBlank { unnamedSection }
        val segmentFlagsLabel = elfProgramHeaderFlagsLabel(mapping.segmentFlags)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_elf_section_segment_display,
                    sectionName,
                    mapping.segmentIndex,
                    mapping.segmentTypeName,
                    segmentFlagsLabel
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_section_segment_meta,
                    mapping.sectionIndex,
                    mapping.sectionVirtualAddress,
                    mapping.sectionFileOffset,
                    mapping.sectionSize,
                    mapping.segmentVirtualAddress,
                    mapping.segmentFileOffset,
                    mapping.segmentFileSize,
                    mapping.segmentMemorySize
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ElfNotesDialog(
    notes: List<HexElfNoteEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(notes) { mutableStateOf("") }
    var noteFilter by remember(notes) { mutableStateOf(ElfNoteFilter.ALL) }
    val filteredNotes = remember(notes, query, noteFilter) {
        filterElfNotes(
            notes = notes,
            query = query,
            noteFilter = noteFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_notes_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_notes_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.ALL,
                                selected = noteFilter == ElfNoteFilter.ALL,
                                onClick = { noteFilter = ElfNoteFilter.ALL }
                            )
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.BUILD_ID,
                                selected = noteFilter == ElfNoteFilter.BUILD_ID,
                                onClick = { noteFilter = ElfNoteFilter.BUILD_ID }
                            )
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.GNU,
                                selected = noteFilter == ElfNoteFilter.GNU,
                                onClick = { noteFilter = ElfNoteFilter.GNU }
                            )
                            ElfNoteFilterButton(
                                filter = ElfNoteFilter.ANDROID,
                                selected = noteFilter == ElfNoteFilter.ANDROID,
                                onClick = { noteFilter = ElfNoteFilter.ANDROID }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_notes_filter_count,
                                filteredNotes.size,
                                notes.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredNotes.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_notes_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredNotes.size,
                                key = { index ->
                                    val note = filteredNotes[index]
                                    "${note.sectionName}-${note.noteFileOffset}-${note.type}"
                                }
                            ) { index ->
                                ElfNoteRow(
                                    note = filteredNotes[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfNoteFilterButton(
    filter: ElfNoteFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfNoteFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfNoteRow(
    note: HexElfNoteEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(note.descriptionOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val noteName = note.name.ifBlank { stringResource(Strings.hex_elf_note_name_empty) }
            Text(
                text = stringResource(Strings.hex_elf_note_title, noteName, note.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_note_meta,
                    note.sectionName.ifBlank { stringResource(Strings.hex_elf_section_name_empty) },
                    note.noteFileOffset,
                    note.descriptionOffset,
                    note.descriptionSize
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            note.descriptionText?.let { text ->
                Text(
                    text = stringResource(Strings.hex_elf_note_description_text, text),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            note.properties.forEach { property ->
                Text(
                    text = stringResource(
                        Strings.hex_elf_note_property,
                        property.typeName,
                        property.valueHex
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (property.features.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            Strings.hex_elf_note_property_features,
                            hexElfNotePropertyFeatureLabels(property.features)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = stringResource(Strings.hex_elf_note_description_hex, note.descriptionHex),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ElfSymbolsDialog(
    symbols: List<HexElfSymbol>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(symbols) { mutableStateOf("") }
    var symbolFilter by remember(symbols) { mutableStateOf(ElfSymbolFilter.ALL) }
    val filteredSymbols = remember(symbols, query, symbolFilter) {
        filterElfSymbols(
            symbols = symbols,
            query = query,
            symbolFilter = symbolFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_symbols_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_symbols_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.ALL,
                                selected = symbolFilter == ElfSymbolFilter.ALL,
                                onClick = { symbolFilter = ElfSymbolFilter.ALL }
                            )
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.IMPORTED,
                                selected = symbolFilter == ElfSymbolFilter.IMPORTED,
                                onClick = { symbolFilter = ElfSymbolFilter.IMPORTED }
                            )
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.EXPORTED,
                                selected = symbolFilter == ElfSymbolFilter.EXPORTED,
                                onClick = { symbolFilter = ElfSymbolFilter.EXPORTED }
                            )
                            ElfSymbolFilterButton(
                                filter = ElfSymbolFilter.JNI,
                                selected = symbolFilter == ElfSymbolFilter.JNI,
                                onClick = { symbolFilter = ElfSymbolFilter.JNI }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_symbols_filter_count,
                                filteredSymbols.size,
                                symbols.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredSymbols.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_symbols_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredSymbols.size,
                                key = { index ->
                                    val symbol = filteredSymbols[index]
                                    "${symbol.name}-${symbol.value}-${symbol.fileOffset}"
                                }
                            ) { index ->
                                ElfSymbolRow(
                                    symbol = filteredSymbols[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfSymbolFilterButton(
    filter: ElfSymbolFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfSymbolFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfSymbolRow(
    symbol: HexElfSymbol,
    onGotoOffset: (Long) -> Unit
) {
    val fileOffset = symbol.fileOffset
    if (fileOffset != null) {
        Surface(
            onClick = { onGotoOffset(fileOffset) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSymbolRowContent(symbol)
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            ElfSymbolRowContent(symbol)
        }
    }
}

@Composable
private fun ElfSymbolRowContent(symbol: HexElfSymbol) {
    val fileOffset = symbol.fileOffset
    val roleLabel = elfSymbolRoleLabel(symbol)
    val typeLabel = elfSymbolTypeLabel(symbol.type)
    val sectionLabel = symbol.sectionName ?: stringResource(Strings.hex_elf_symbol_section_unknown)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = symbol.name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (fileOffset != null) {
                stringResource(
                    Strings.hex_elf_symbol_meta_mapped,
                    roleLabel,
                    typeLabel,
                    sectionLabel,
                    symbol.value,
                    fileOffset
                )
            } else {
                stringResource(
                    Strings.hex_elf_symbol_meta_unmapped,
                    roleLabel,
                    typeLabel,
                    sectionLabel,
                    symbol.value
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ElfDynamicEntriesDialog(
    entries: List<HexElfDynamicStringEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var dynamicEntryFilter by remember(entries) { mutableStateOf(ElfDynamicEntryFilter.ALL) }
    val filteredEntries = remember(entries, query, dynamicEntryFilter) {
        filterElfDynamicEntries(
            entries = entries,
            query = query,
            dynamicEntryFilter = dynamicEntryFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_dynamic_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_dynamic_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.ALL,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.ALL,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.ALL }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.NEEDED,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.NEEDED,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.NEEDED }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.SONAME,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.SONAME,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.SONAME }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.RPATH,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.RPATH,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.RPATH }
                            )
                            ElfDynamicEntryFilterButton(
                                filter = ElfDynamicEntryFilter.RUNPATH,
                                selected = dynamicEntryFilter == ElfDynamicEntryFilter.RUNPATH,
                                onClick = { dynamicEntryFilter = ElfDynamicEntryFilter.RUNPATH }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_dynamic_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_dynamic_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.type}-${entry.entryFileOffset}-${entry.value}"
                                }
                            ) { index ->
                                ElfDynamicEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfDynamicEntryFilterButton(
    filter: ElfDynamicEntryFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfDynamicEntryFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfDynamicEntryRow(
    entry: HexElfDynamicStringEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.entryFileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_dynamic_meta,
                    elfDynamicStringTypeLabel(entry.type),
                    entry.entryFileOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_dynamic_semantic,
                    elfDynamicStringSemanticLabel(entry.semantic)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.loadOrder?.let { loadOrder ->
                Text(
                    text = stringResource(Strings.hex_elf_dynamic_load_order, loadOrder),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ElfDynamicFlagsDialog(
    entries: List<HexElfDynamicFlagEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var dynamicFlagFilter by remember(entries) { mutableStateOf(ElfDynamicFlagFilter.ALL) }
    val filteredEntries = remember(entries, query, dynamicFlagFilter) {
        filterElfDynamicFlags(
            entries = entries,
            query = query,
            dynamicFlagFilter = dynamicFlagFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_elf_dynamic_flags_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_elf_dynamic_flags_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.ALL,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.ALL,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.ALL }
                            )
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.BIND_NOW,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.BIND_NOW,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.BIND_NOW }
                            )
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.FLAGS,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.FLAGS,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.FLAGS }
                            )
                            ElfDynamicFlagFilterButton(
                                filter = ElfDynamicFlagFilter.FLAGS_1,
                                selected = dynamicFlagFilter == ElfDynamicFlagFilter.FLAGS_1,
                                onClick = { dynamicFlagFilter = ElfDynamicFlagFilter.FLAGS_1 }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_elf_dynamic_flags_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_elf_dynamic_flags_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.type}-${entry.entryFileOffset}-${entry.value}"
                                }
                            ) { index ->
                                ElfDynamicFlagRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ElfDynamicFlagFilterButton(
    filter: ElfDynamicFlagFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = elfDynamicFlagFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ElfDynamicFlagRow(
    entry: HexElfDynamicFlagEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.entryFileOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = elfDynamicFlagTypeLabel(entry.type),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_elf_dynamic_flag_meta,
                    entry.value,
                    entry.entryFileOffset,
                    elfDynamicFlagBindNowLabel(entry.isBindNow)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StringEncodingFilterButton(
    filter: StringEntryEncodingFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = stringEncodingFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun StringEntryRow(
    entry: HexStringEntry,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_strings_dialog_meta,
                    entry.offset,
                    stringEncodingLabel(entry.encoding)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExportFormatButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

@Composable
private fun GotoOffsetDialog(
    maxOffset: Long,
    currentOffset: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var offsetText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val maxAllowedOffset = (maxOffset - 1).coerceAtLeast(0)
    val errorInvalidOffset = stringResource(Strings.error_invalid_offset)
    val errorOutOfRange = stringResource(Strings.error_offset_out_of_range, maxAllowedOffset)

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.dialog_title_goto_offset)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = {
                            offsetText = it
                            error = null
                        },
                        label = { Text(stringResource(Strings.hint_offset_address)) },
                        placeholder = { Text(stringResource(Strings.hint_offset_example_relative)) },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_goto),
                enabled = offsetText.isNotBlank(),
                onClick = {
                    val offset = parseOffsetExpression(offsetText, currentOffset)
                    if (offset == null) {
                        error = errorInvalidOffset
                    } else if (offset < 0 || offset >= maxOffset) {
                        error = errorOutOfRange
                    } else {
                        onConfirm(offset)
                    }
                }
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

