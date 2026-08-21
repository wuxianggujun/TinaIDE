package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.editorlsp.EditorStatus
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.logging.GestureTrace
import com.wuxianggujun.tinaide.core.lsp.ProjectSyncManager
import com.wuxianggujun.tinaide.core.lsp.ProjectSyncState
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConfigManager
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * 编辑器状态栏（带拖拽功能）
 *
 * 显示：
 * - 左侧：状态指示器（Ready/Busy）+ 远程 LSP 状态（如果启用）+ 文件编码（UTF-8）
 * - 中间：拖拽条图标
 * - 右侧：光标位置（Ln x, Col y）+ 可选的快捷符号栏开关
 *
 * 整个状态栏可以拖拽来展开/收起底部面板
 */
@Composable
fun EditorStatusBar(
    modifier: Modifier = Modifier,
    status: EditorStatus = EditorStatus.Ready,
    encoding: String = "",
    line: Int = 1,
    column: Int = 1,
    bottomPanelState: BottomPanelDragState? = null,
    onStatusClick: (() -> Unit)? = null,
    onCursorPositionClick: (() -> Unit)? = null,
    isEditorSymbolBarVisible: Boolean = false,
    onToggleEditorSymbolBar: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }

    // 远程 LSP 状态
    val remoteLspConfig by RemoteLspConfigManager.configFlow.collectAsState()
    val remoteLspConnectionState by RemoteLspConfigManager.connectionStateFlow.collectAsState()
    val remoteLspLatency by RemoteLspConfigManager.latencyMsFlow.collectAsState()
    val reconnectAttempt by RemoteLspConfigManager.reconnectAttemptFlow.collectAsState()
    val isRemoteLspEnabled = remoteLspConfig.enabled

    // 项目同步进度
    val syncProgress by ProjectSyncManager.progressFlow.collectAsState()
    val isSyncing = syncProgress.state in listOf(
        ProjectSyncState.SCANNING,
        ProjectSyncState.COMPRESSING,
        ProjectSyncState.UPLOADING
    )

    val statusText = when (status) {
        EditorStatus.Ready -> if (isRemoteLspEnabled) {
            stringResource(Strings.editor_status_remote_lsp)
        } else {
            stringResource(Strings.editor_status_lsp_ready)
        }
        EditorStatus.Connecting -> if (isRemoteLspEnabled) {
            stringResource(Strings.editor_status_remote_connecting)
        } else {
            stringResource(Strings.editor_status_lsp_connecting)
        }
        EditorStatus.Busy -> stringResource(Strings.editor_status_busy)
        EditorStatus.NoLsp -> stringResource(Strings.editor_status_no_lsp)
        EditorStatus.Error -> if (isRemoteLspEnabled) {
            stringResource(Strings.editor_status_remote_error)
        } else {
            stringResource(Strings.editor_status_lsp_error)
        }
    }

    TinaOverlayPanelSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(EditorStatusBarHeight)
            .then(
                if (bottomPanelState != null) {
                    Modifier.pointerInput(bottomPanelState) {
                        val component = "BottomPanelStatusBar"
                        var lastLogUptimeMillis = 0L
                        detectVerticalDragGestures(
                            onDragStart = {
                                bottomPanelState.isDragging = true
                                velocityTracker.resetTracking()
                                if (GestureTrace.isEnabled()) {
                                    GestureTrace.w(component, "dragStart height=${bottomPanelState.currentHeight}")
                                }
                            },
                            onDragEnd = {
                                bottomPanelState.isDragging = false
                                val velocity = velocityTracker.calculateVelocity().y
                                if (GestureTrace.isEnabled()) {
                                    GestureTrace.w(component, "dragEnd vY=${"%.1f".format(velocity)} height=${bottomPanelState.currentHeight}")
                                }
                                scope.launch {
                                    bottomPanelState.settle(velocity)
                                }
                            },
                            onDragCancel = {
                                bottomPanelState.isDragging = false
                                if (GestureTrace.isEnabled()) {
                                    GestureTrace.w(component, "dragCancel height=${bottomPanelState.currentHeight}")
                                }
                                scope.launch {
                                    bottomPanelState.settle(0f)
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (GestureTrace.isEnabled()) {
                                    val now = change.uptimeMillis
                                    if (now - lastLogUptimeMillis >= 120) {
                                        lastLogUptimeMillis = now
                                        GestureTrace.d(
                                            component,
                                            "drag dY=${"%.1f".format(dragAmount)} pos=(${"%.1f".format(change.position.x)},${"%.1f".format(change.position.y)}) height=${bottomPanelState.currentHeight}"
                                        )
                                    }
                                }
                                velocityTracker.addPosition(
                                    change.uptimeMillis,
                                    change.position
                                )
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    bottomPanelState.dragBy(dragAmount)
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            ),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧：状态指示器 + 远程 LSP 状态 + 编码
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onStatusClick != null) {
                            Modifier.clickable(onClick = onStatusClick)
                        } else {
                            Modifier
                        }
                    )
            ) {
                // 状态圆点
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (status) {
                                EditorStatus.Ready -> TinaSemanticColors.Editor.ready
                                EditorStatus.Connecting -> TinaSemanticColors.Editor.connecting
                                EditorStatus.Busy -> TinaSemanticColors.Editor.busy
                                EditorStatus.NoLsp -> TinaSemanticColors.Editor.noLsp
                                EditorStatus.Error -> TinaSemanticColors.Editor.error
                            }
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                // 远程 LSP 连接状态指示器（仅在启用远程 LSP 时显示）
                if (isRemoteLspEnabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // 远程连接状态小圆点
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when (remoteLspConnectionState) {
                                    RemoteLspConnectionState.CONNECTED -> TinaSemanticColors.Editor.ready
                                    RemoteLspConnectionState.CONNECTING -> TinaSemanticColors.Editor.connecting
                                    RemoteLspConnectionState.DISCONNECTED -> TinaSemanticColors.Editor.noLsp
                                    RemoteLspConnectionState.ERROR -> TinaSemanticColors.Editor.error
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // 显示地址和延迟/重连/同步信息
                    val remoteStatusText = when {
                        // 同步进度优先显示
                        isSyncing -> {
                            val stateText = when (syncProgress.state) {
                                ProjectSyncState.SCANNING -> stringResource(Strings.status_scanning)
                                ProjectSyncState.COMPRESSING -> stringResource(Strings.status_compressing)
                                ProjectSyncState.UPLOADING -> stringResource(Strings.status_uploading)
                                else -> stringResource(Strings.status_syncing)
                            }
                            if (syncProgress.totalFiles > 0) {
                                "$stateText ${syncProgress.processedFiles}/${syncProgress.totalFiles}"
                            } else {
                                "$stateText..."
                            }
                        }
                        remoteLspConnectionState == RemoteLspConnectionState.CONNECTED && remoteLspLatency > 0 ->
                            "${remoteLspConfig.host}:${remoteLspConfig.port} (${remoteLspLatency}ms)"
                        reconnectAttempt > 0 ->
                            stringResource(Strings.status_reconnecting, reconnectAttempt).let {
                                "${remoteLspConfig.host}:${remoteLspConfig.port} ($it)"
                            }
                        else ->
                            "${remoteLspConfig.host}:${remoteLspConfig.port}"
                    }
                    Text(
                        text = remoteStatusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSyncing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 编码
                Text(
                    text = encoding,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            // 中间：拖拽条图标
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )

            // 右侧：光标位置
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Strings.editor_cursor_position, line, column),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = if (onCursorPositionClick != null) {
                        Modifier.clickable(onClick = onCursorPositionClick)
                    } else {
                        Modifier
                    }
                )

                if (onToggleEditorSymbolBar != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    TinaPanelSegmentButton(
                        onClick = onToggleEditorSymbolBar,
                        modifier = Modifier.size(32.dp),
                        minHeight = 32.dp,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = stringResource(
                                if (isEditorSymbolBarVisible) {
                                    Strings.content_desc_hide_editor_symbol_bar
                                } else {
                                    Strings.content_desc_show_editor_symbol_bar
                                }
                            ),
                            modifier = Modifier.size(19.dp),
                            tint = if (isEditorSymbolBarVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}


