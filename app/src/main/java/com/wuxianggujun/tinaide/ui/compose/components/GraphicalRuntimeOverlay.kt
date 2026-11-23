package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.runtime.GraphicalOverlayWindowState
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * Shared controls displayed over every fullscreen graphical runtime.
 *
 * 提供：
 * - 收起时只显示一个可拖拽控制球，返回与日志入口放在菜单内
 * - 日志标题栏可拖拽，右下角可缩放，支持最大化和还原
 * - 日志面板捕获当前图形进程的全部日志标签
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GraphicalRuntimeOverlay(
    enableFloatingLog: Boolean,
    onExit: () -> Unit,
    windowState: GraphicalOverlayWindowState,
    modifier: Modifier = Modifier,
) {
    var showControlMenu by remember { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

    val logLines = remember { mutableStateListOf<LogEntry>() }
    val listState = rememberLazyListState()
    val logCaptureFailureMessage = stringResource(Strings.floating_overlay_log_capture_failed)

    if (enableFloatingLog) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                captureLogcat(logLines, logCaptureFailureMessage)
            }
        }

        LaunchedEffect(logLines.size) {
            if (logLines.isNotEmpty()) {
                listState.animateScrollToItem(logLines.size - 1)
            }
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
                logLines = logLines,
                listState = listState,
                onClearLog = { logLines.clear() },
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
    logLines: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClearLog: () -> Unit,
    onExit: () -> Unit,
    windowState: GraphicalOverlayWindowState,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
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
                    Box {
                        FloatingOverlayActionButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("graphical-log-more"),
                        ) {
                            Icon(Icons.Default.MoreVert, stringResource(Strings.content_desc_more_actions), Modifier.size(20.dp))
                        }
                        GraphicalRuntimeActionsMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            onClearLog = onClearLog,
                            canClearLog = logLines.isNotEmpty(),
                            onExit = onExit,
                        )
                    }
                }
                FloatingLogContent(
                    logLines = logLines,
                    listState = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
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
    expanded: Boolean,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onOpenLog: (() -> Unit)? = null,
    onClearLog: (() -> Unit)? = null,
    canClearLog: Boolean = false,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        if (onOpenLog != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Strings.floating_overlay_log_title)) },
                leadingIcon = { Icon(Icons.Default.Terminal, null) },
                onClick = { onDismiss(); onOpenLog() },
                modifier = Modifier.testTag("graphical-overlay-open-log"),
            )
        }
        if (onClearLog != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Strings.floating_overlay_log_clear)) },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { onDismiss(); onClearLog() },
                enabled = canClearLog,
                modifier = Modifier.testTag("graphical-overlay-clear-log"),
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(Strings.floating_overlay_exit)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
            onClick = { onDismiss(); onExit() },
            modifier = Modifier.testTag("graphical-overlay-exit"),
        )
    }
}

@Composable
private fun FloatingLogContent(
    logLines: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    FloatingOverlayLogSurface(modifier = modifier) {
        if (logLines.isEmpty()) {
            FloatingLogEmptyState()
        } else {
            FloatingLogList(
                logLines = logLines,
                listState = listState
            )
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
private fun FloatingLogList(
    logLines: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(logLines) { entry ->
            Text(
                text = entry.message,
                color = when (entry.level) {
                    'E' -> Color(0xFFFF6B6B)
                    'W' -> Color(0xFFFFD93D)
                    else -> Color(0xFFCCCCCC)
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
        shape = RoundedCornerShape(16.dp),
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

data class LogEntry(
    val level: Char,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

private const val MAX_LOG_LINES = 500
private const val MAX_LOG_LINE_CHARS = 8_192
private const val LOGCAT_INITIAL_LINE_COUNT = 200

/**
 * Keep engine-specific and user-defined log tags, bounded to the current graphical process.
 */
internal fun graphicalRuntimeLogcatCommand(processId: Int): List<String> = listOf(
    "logcat", "--pid=$processId", "-v", "brief", "-T", LOGCAT_INITIAL_LINE_COUNT.toString(), "*:V",
)

/**
 * 捕获隔离图形进程的全部标签，包括 stdout/stderr、SDL、raylib 和用户自定义 Android 日志。
 *
 * 使用 `--pid` 限制为当前进程，保留启动初期的有限历史，不依赖引擎标签白名单。
 * 当行数超过 [MAX_LOG_LINES] 时移除最早的条目。
 */
private suspend fun captureLogcat(
    logLines: MutableList<LogEntry>,
    failureMessage: String,
) = coroutineScope {
    val pid = android.os.Process.myPid()
    val cmd = graphicalRuntimeLogcatCommand(pid)

    val process = try {
        ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
    } catch (e: Exception) {
        Timber.tag("GraphicalRuntimeOverlay").w(e, "Unable to start graphical log capture")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            logLines.add(LogEntry('E', failureMessage))
        }
        return@coroutineScope
    }

    val cancellationCloser = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            process.destroy()
        }
    }
    try {
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        var line: String?
        while (isActive) {
            line = reader.readLine() ?: break
            if (line.isBlank() || line.startsWith("-----")) continue

            val level = when {
                line.isNotEmpty() && line[0] in "VDIWEF" -> line[0]
                else -> 'I'
            }
            val message = line.substringAfter("): ", line).take(MAX_LOG_LINE_CHARS)

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                logLines.add(LogEntry(level, message))
                while (logLines.size > MAX_LOG_LINES) {
                    logLines.removeAt(0)
                }
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (isActive) Timber.tag("GraphicalRuntimeOverlay").w(error, "Graphical log capture failed")
        if (isActive) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            logLines.add(LogEntry('E', failureMessage))
        }
    } finally {
        cancellationCloser.cancel()
        process.destroy()
        listOf(process.inputStream, process.errorStream, process.outputStream).forEach { stream ->
            runCatching { stream.close() }.onFailure { error ->
                Timber.tag("GraphicalRuntimeOverlay").d(error, "Log capture stream already closed")
            }
        }
    }
}
