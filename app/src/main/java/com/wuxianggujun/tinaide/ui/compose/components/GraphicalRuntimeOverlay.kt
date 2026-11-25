package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wuxianggujun.tinaide.core.editorview.TinaCodeViewer
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.runtime.GraphicalOverlayWindowState
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLogDocument
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLogEntry
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeMenuPositionProvider
import com.wuxianggujun.tinaide.ui.runtime.captureGraphicalRuntimeLogcat
import kotlin.math.roundToInt

/**
 * Shared controls displayed over every fullscreen graphical runtime.
 *
 * 提供：
 * - 收起时只显示一个可拖拽控制球，返回与日志入口放在菜单内
 * - 日志标题栏可拖拽，右下角可缩放，支持最大化和还原
 * - 默认显示程序输出和关键运行错误，可切换当前图形进程的全部日志
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GraphicalRuntimeOverlay(
    enableFloatingLog: Boolean,
    onExit: () -> Unit,
    windowState: GraphicalOverlayWindowState,
    modifier: Modifier = Modifier,
    captureLogs: suspend (Boolean, String, (List<GraphicalRuntimeLogEntry>) -> Unit) -> Unit = { allLogs, message, append ->
        captureGraphicalRuntimeLogcat(message, append, showAllProcessLogs = allLogs)
    },
) {
    var showControlMenu by remember { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

    val logDocument = remember { GraphicalRuntimeLogDocument() }
    var wordWrap by rememberSaveable { mutableStateOf(false) }
    var showAllProcessLogs by rememberSaveable { mutableStateOf(false) }
    val logCaptureFailureMessage = stringResource(Strings.floating_overlay_log_capture_failed)
    DisposableEffect(logDocument) { onDispose { logDocument.close() } }
    SideEffect { logDocument.setWordWrap(wordWrap) }

    if (enableFloatingLog) {
        LaunchedEffect(logDocument, showAllProcessLogs) {
            // A new source replays its recent logcat tail; do not mix or duplicate the old view.
            logDocument.clear()
            captureLogs(showAllProcessLogs, logCaptureFailureMessage, logDocument::append)
        }
    }

    LaunchedEffect(enableFloatingLog) {
        if (!enableFloatingLog) windowState.collapseWindow()
    }
    val density = LocalDensity.current
    val bounds = windowState.bounds
    Box(modifier = modifier
        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
        .testTag("graphical-overlay-root")
        .semantics { testTagsAsResourceId = true },
    ) {
        if (windowState.expanded && enableFloatingLog) {
            FloatingLogPanel(
                logDocument = logDocument,
                wordWrap = wordWrap,
                onWordWrapChanged = { wordWrap = it },
                showAllProcessLogs = showAllProcessLogs,
                onAllProcessLogsChanged = { showAllProcessLogs = it },
                onExit = { showExitDialog = true },
                windowState = windowState,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FloatingControlButton(
                icon = if (enableFloatingLog) Icons.Default.Terminal else Icons.Default.MoreVert,
                contentDescription = stringResource(Strings.floating_overlay_ball_desc),
                onTap = { showControlMenu = !showControlMenu },
                onDrag = { delta ->
                    showControlMenu = false
                    windowState.dragBy(delta)
                },
                modifier = Modifier.fillMaxSize().testTag("graphical-overlay-control"),
            )
            GraphicalRuntimeActionsMenu(
                windowState = windowState,
                anchorBoundsInOverlay = { IntRect(0, 0, bounds.width, bounds.height) },
                expanded = showControlMenu,
                onDismiss = { showControlMenu = false },
                onOpenLog = if (enableFloatingLog) windowState::openWindow else null,
                onExit = { showExitDialog = true },
            )
        }
    }

    if (showExitDialog) {
        TinaConfirmDialog(
            title = stringResource(Strings.floating_overlay_exit),
            message = stringResource(Strings.floating_overlay_exit_confirm),
            confirmText = stringResource(Strings.floating_overlay_exit_confirm_yes),
            dismissText = stringResource(Strings.floating_overlay_exit_confirm_no),
            onConfirm = {
                showExitDialog = false
                onExit()
            },
            onDismiss = { showExitDialog = false }
        )
    }
}

@Composable
private fun FloatingControlButton(
    icon: ImageVector,
    contentDescription: String,
    onTap: () -> Unit,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDrag by rememberUpdatedState(onDrag)
    Surface(
        modifier = modifier
            .shadow(6.dp, CircleShape)
            .alpha(0.85f)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
                onClick(label = contentDescription) { currentOnTap(); true }
            }
            .floatingDrag { currentOnDrag(it) }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnTap() })
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun FloatingLogPanel(
    logDocument: GraphicalRuntimeLogDocument,
    wordWrap: Boolean,
    onWordWrapChanged: (Boolean) -> Unit,
    showAllProcessLogs: Boolean,
    onAllProcessLogsChanged: (Boolean) -> Unit,
    onExit: () -> Unit,
    windowState: GraphicalOverlayWindowState,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(IntRect.Zero) }
    val panelShape = RoundedCornerShape(TinaShapes.DialogCorner)
    TinaOverlayPanelSurface(
        modifier = modifier.testTag("graphical-log-window"),
        shape = panelShape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val dragLabel = stringResource(Strings.floating_overlay_window_drag)
                    Row(
                        modifier = Modifier.weight(1f).height(48.dp).padding(start = 8.dp)
                            .testTag("graphical-log-drag-handle")
                            .semantics { contentDescription = dragLabel }
                            .floatingDrag(windowState::dragBy)
                            .pointerInput(windowState) {
                                detectTapGestures(onDoubleTap = { windowState.toggleMaximized() })
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.DragIndicator, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = stringResource(Strings.floating_overlay_log_title),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    FloatingOverlayActionButton(
                        onClick = windowState::toggleMaximized,
                        modifier = Modifier.testTag("graphical-log-maximize"),
                    ) {
                        Icon(
                            if (windowState.maximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            stringResource(if (windowState.maximized) Strings.floating_overlay_window_restore else Strings.floating_overlay_window_maximize),
                            Modifier.size(20.dp),
                        )
                    }
                    FloatingOverlayActionButton(
                        onClick = windowState::collapseWindow,
                        modifier = Modifier.testTag("graphical-log-collapse"),
                    ) {
                        Icon(Icons.Default.Remove, stringResource(Strings.floating_overlay_window_minimize), Modifier.size(20.dp))
                    }
                    Box(Modifier.onGloballyPositioned { coordinates ->
                        menuAnchor = coordinates.boundsInRoot().let {
                            IntRect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
                        }
                    }) {
                        FloatingOverlayActionButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("graphical-log-more"),
                        ) {
                            Icon(Icons.Default.MoreVert, stringResource(Strings.content_desc_more_actions), Modifier.size(20.dp))
                        }
                        GraphicalRuntimeActionsMenu(
                            windowState = windowState,
                            anchorBoundsInOverlay = { menuAnchor },
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            onClearLog = logDocument::clear,
                            canClearLog = logDocument.lineCount > 0,
                            wordWrap = wordWrap,
                            onWordWrapChanged = onWordWrapChanged,
                            showAllProcessLogs = showAllProcessLogs,
                            onAllProcessLogsChanged = onAllProcessLogsChanged,
                            onExit = onExit,
                        )
                    }
                }
                FloatingLogContent(
                    logDocument = logDocument,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp),
                )
            }
            if (!windowState.maximized) {
                FloatingWindowResizeHandle(windowState, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** Drag regions are limited to the control, title and resize grip; never the engine/content area. */
@Composable
private fun Modifier.floatingDrag(onDrag: (Offset) -> Unit): Modifier {
    val currentOnDrag by rememberUpdatedState(onDrag)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }.pointerInput(Unit) {
        var previousScreenPosition: Offset? = null
        detectDragGestures(
            onDragStart = { previousScreenPosition = null },
            onDragEnd = { previousScreenPosition = null },
            onDragCancel = { previousScreenPosition = null },
        ) { change, overSlopDelta ->
            // Moving a PopupWindow also moves its local origin. Local deltas lose part
            // of the finger's movement; compare actual screen positions instead.
            val screenPosition = coordinates?.takeIf { it.isAttached }
                ?.localToScreen(change.position)?.takeIf { it.x.isFinite() && it.y.isFinite() }
            val previous = previousScreenPosition
            val delta = if (screenPosition != null && previous != null) screenPosition - previous else overSlopDelta
            previousScreenPosition = screenPosition
            change.consume()
            currentOnDrag(delta)
        }
    }
}

@Composable
private fun FloatingWindowResizeHandle(windowState: GraphicalOverlayWindowState, modifier: Modifier = Modifier) {
    val resizeLabel = stringResource(Strings.floating_overlay_window_resize)
    val growLabel = stringResource(Strings.floating_overlay_window_grow)
    val shrinkLabel = stringResource(Strings.floating_overlay_window_shrink)
    val step = with(LocalDensity.current) { 32.dp.toPx() }
    Box(
        modifier = modifier.size(32.dp).testTag("graphical-log-resize")
            .semantics {
                contentDescription = resizeLabel
                customActions = listOf(
                    CustomAccessibilityAction(growLabel) { windowState.resizeBy(Offset(step, step)); true },
                    CustomAccessibilityAction(shrinkLabel) { windowState.resizeBy(Offset(-step, -step)); true },
                )
            }
            .floatingDrag(windowState::resizeBy),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.DragHandle, null, Modifier.size(20.dp).rotate(-45f), tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GraphicalRuntimeActionsMenu(
    windowState: GraphicalOverlayWindowState,
    anchorBoundsInOverlay: () -> IntRect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onOpenLog: (() -> Unit)? = null,
    onClearLog: (() -> Unit)? = null,
    canClearLog: Boolean = false,
    wordWrap: Boolean = false,
    onWordWrapChanged: ((Boolean) -> Unit)? = null,
    showAllProcessLogs: Boolean = false,
    onAllProcessLogsChanged: ((Boolean) -> Unit)? = null,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val currentAnchor by rememberUpdatedState(anchorBoundsInOverlay)
    val positionProvider = remember(windowState, density) {
        GraphicalRuntimeMenuPositionProvider(
            screenAnchor = { currentAnchor().translate(windowState.bounds.topLeft) },
            safeScreenBounds = { windowState.safeMenuBounds },
            gapPx = with(density) { 4.dp.toPx().roundToInt() },
        )
    }
    val safeBounds = windowState.safeMenuBounds
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        TinaOverlayPanelSurface(
            modifier = Modifier.testTag("graphical-overlay-actions-menu").semantics { testTagsAsResourceId = true },
            shape = RoundedCornerShape(TinaShapes.CardCorner),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        ) {
            Column(Modifier.widthIn(max = with(density) { safeBounds.width.toDp() })
                .width(IntrinsicSize.Max)
                .heightIn(max = with(density) { safeBounds.height.toDp() })
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            ) {
                if (onOpenLog != null) {
                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.floating_overlay_log_title)) },
                        leadingIcon = { Icon(Icons.Default.Terminal, null) },
                        onClick = { onDismiss(); onOpenLog() },
                        modifier = Modifier.testTag("graphical-overlay-open-log"),
                    )
                }
                if (onClearLog != null) {
                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.floating_overlay_log_clear)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { onDismiss(); onClearLog() },
                        enabled = canClearLog,
                        modifier = Modifier.testTag("graphical-overlay-clear-log"),
                    )
                }
                if (onWordWrapChanged != null) {
                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.settings_word_wrap)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.WrapText, null) },
                        trailingIcon = { Checkbox(checked = wordWrap, onCheckedChange = null) },
                        onClick = { onWordWrapChanged(!wordWrap) },
                        modifier = Modifier.testTag("graphical-log-word-wrap").semantics {
                            role = Role.Checkbox
                            toggleableState = if (wordWrap) ToggleableState.On else ToggleableState.Off
                        },
                    )
                }
                if (onAllProcessLogsChanged != null) {
                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.floating_overlay_log_all_process)) },
                        leadingIcon = { Icon(Icons.Default.Terminal, null) },
                        trailingIcon = { Checkbox(checked = showAllProcessLogs, onCheckedChange = null) },
                        onClick = { onAllProcessLogsChanged(!showAllProcessLogs) },
                        modifier = Modifier.testTag("graphical-log-all-process").semantics {
                            role = Role.Checkbox
                            toggleableState = if (showAllProcessLogs) ToggleableState.On else ToggleableState.Off
                        },
                    )
                }
                TinaDropdownMenuItem(
                    text = { Text(stringResource(Strings.floating_overlay_exit)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                    onClick = { onDismiss(); onExit() },
                    modifier = Modifier.testTag("graphical-overlay-exit"),
                )
            }
        }
    }
}

@Composable
private fun FloatingLogContent(
    logDocument: GraphicalRuntimeLogDocument,
    modifier: Modifier = Modifier
) {
    val editorState = logDocument.state
    val scrollConnection = remember(logDocument) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y != 0f) logDocument.onUserScroll()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(logDocument.revision, editorState.viewportWidthPx, editorState.viewportHeightPx, editorState.config.wordWrap) {
        if (logDocument.followingTail) logDocument.scrollToEnd()
    }
    FloatingOverlayLogSurface(modifier = modifier.testTag("graphical-log-content")) {
        TinaCodeViewer(editorState, Modifier.fillMaxSize().nestedScroll(scrollConnection), selectable = true)
        if (logDocument.lineCount == 0) {
            FloatingLogEmptyState()
        }
    }
}

@Composable
private fun FloatingLogEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Strings.floating_overlay_log_empty),
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FloatingOverlayActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        minHeight = 48.dp,
        color = color,
        contentPadding = PaddingValues(0.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun FloatingOverlayLogSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        containerColor = Color(0xFF1E1E1E),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            content = content
        )
    }
}
