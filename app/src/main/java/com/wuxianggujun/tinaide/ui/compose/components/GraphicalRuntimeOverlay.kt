package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
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
 * - 可拖拽的半透明小球（始终显示）
 * - 开启悬浮日志时默认展开，可手动折叠
 * - 日志面板捕获当前图形进程的全部日志标签
 */
@Composable
fun GraphicalRuntimeOverlay(
    enableFloatingLog: Boolean,
    onExit: () -> Unit,
    windowBounds: IntSize,
    onDragWindow: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(enableFloatingLog) { mutableStateOf(enableFloatingLog) }
    var showExitDialog by remember { mutableStateOf(false) }

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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FloatingControlGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(FloatingControlGap)) {
            FloatingControlButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Strings.floating_overlay_ball_desc),
                onTap = { showExitDialog = true },
                onDrag = onDragWindow,
            )
            if (enableFloatingLog) {
                FloatingControlButton(
                    icon = Icons.Default.Terminal,
                    contentDescription = stringResource(Strings.floating_overlay_log_title),
                    active = expanded,
                    onTap = { expanded = !expanded },
                    onDrag = onDragWindow,
                )
            }
        }
        if (expanded && enableFloatingLog) {
            FloatingLogPanel(
                logLines = logLines,
                listState = listState,
                onClearLog = { logLines.clear() },
                onClose = { expanded = false },
                onExit = { showExitDialog = true },
                windowBounds = windowBounds,
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
    active: Boolean = false,
    modifier: Modifier = Modifier
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDrag by rememberUpdatedState(onDrag)
    Surface(
        modifier = modifier
            .size(FloatingControlSize)
            .shadow(6.dp, CircleShape)
            .alpha(0.7f)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnTap() })
            },
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
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
    onClose: () -> Unit,
    onExit: () -> Unit,
    windowBounds: IntSize,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val windowSize = windowBounds
    val panelWidth = with(density) { (windowSize.width * FLOATING_LOG_PANEL_WIDTH_FRACTION).toDp() }
    val expandedLogHeight = with(density) {
        (windowSize.height * FLOATING_LOG_PANEL_HEIGHT_FRACTION).toDp()
            .coerceAtMost((windowSize.height.toDp() - 200.dp).coerceAtLeast(48.dp))
    }
    val logPanelHeight = if (logLines.isEmpty()) {
        FloatingLogPanelEmptyHeight.coerceAtMost(expandedLogHeight)
    } else {
        expandedLogHeight
    }
    val panelShape = RoundedCornerShape(TinaShapes.DialogCorner)

    TinaOverlayPanelSurface(
        modifier = modifier
            .widthIn(max = panelWidth),
        shape = panelShape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TinaCustomDialogHeader(
                title = stringResource(Strings.floating_overlay_log_title),
                trailingContent = {
                    if (logLines.isNotEmpty()) {
                        FloatingOverlayActionButton(
                            onClick = onClearLog,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(Strings.floating_overlay_log_clear),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    FloatingOverlayActionButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(Strings.floating_overlay_exit_confirm_no),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )

            FloatingLogContent(
                logLines = logLines,
                listState = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(logPanelHeight)
            )

            TinaDialogActionRow {
                TinaOutlinedButton(
                    text = stringResource(Strings.floating_overlay_exit),
                    onClick = onExit,
                    leadingIcon = Icons.AutoMirrored.Filled.ArrowBack
                )
            }
        }
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
        modifier = modifier.size(32.dp),
        minHeight = 32.dp,
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
private const val FLOATING_LOG_PANEL_WIDTH_FRACTION = 0.85f
private const val FLOATING_LOG_PANEL_HEIGHT_FRACTION = 0.60f
private val FloatingControlSize = 48.dp
private val FloatingControlGap = 8.dp
private val FloatingLogPanelEmptyHeight = 112.dp

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
