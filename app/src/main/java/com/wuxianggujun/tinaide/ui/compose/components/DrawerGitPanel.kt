package com.wuxianggujun.tinaide.ui.compose.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.git.FileStatus
import com.wuxianggujun.tinaide.core.git.GitFileStatus
import com.wuxianggujun.tinaide.core.git.GitStatus
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.icons.TinaTabIcons
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter

/**
 * 侧滑栏 Git 面板内容（不带工具栏，用于工具栏在外部时）
 *
 * 美化版本：包含提交信息输入框、卡片式文件列表
 */
@Composable
fun DrawerGitPanelContent(
    status: GitStatus,
    onStageFile: (String) -> Unit,
    onUnstageFile: (String) -> Unit,
    onDiscardChanges: (String) -> Unit,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onStageAll: () -> Unit = {},
    onUnstageAll: () -> Unit = {},
    onShowDiff: (path: String, isStaged: Boolean) -> Unit = { _, _ -> },
    onCommit: (String) -> Unit = {},
    onInitRepository: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenRemotes: () -> Unit = {},
    recentCommitMessages: List<String> = emptyList(),
    onClearCommitMessageHistory: () -> Unit = {}
) {
    var commitMessage by remember { mutableStateOf("") }

    if (!status.isRepository) {
        NotARepositoryContent(
            onInitRepository = onInitRepository,
            modifier = modifier
        )
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            TinaOverlayPanelSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DrawerGitActionButton(
                        onClick = onOpenSync,
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = stringResource(Strings.git_action_sync),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    DrawerGitActionButton(
                        onClick = onOpenRemotes,
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = stringResource(Strings.git_action_remotes),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            CommitMessageSection(
                commitMessage = commitMessage,
                onMessageChange = { commitMessage = it },
                onCommit = {
                    if (commitMessage.isNotBlank() && status.staged.isNotEmpty()) {
                        onCommit(commitMessage)
                        commitMessage = ""
                    }
                },
                canCommit = commitMessage.isNotBlank() && status.staged.isNotEmpty(),
                recentCommitMessages = recentCommitMessages,
                onClearHistory = onClearCommitMessageHistory
            )

            if (!status.hasChanges) {
                NoChangesContent(modifier = Modifier.weight(1f))
            } else {
                // 更改列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 暂存的更改
                    if (status.staged.isNotEmpty()) {
                        item(key = "staged_header") {
                            StagedSectionHeader(
                                title = stringResource(Strings.git_staged_changes_section),
                                count = status.staged.size,
                                onUnstageAll = onUnstageAll
                            )
                        }
                        items(status.staged, key = { "staged_${it.path}" }) { file ->
                            GitFileCard(
                                file = file,
                                isStaged = true,
                                onUnstage = { onUnstageFile(file.path) },
                                onStage = { },
                                onDiscard = { },
                                onClick = { onFileClick(file.path) },
                                onShowDiff = { onShowDiff(file.path, true) }
                            )
                        }
                    }

                    // 未暂存的更改（包括修改和未跟踪）
                    val allUnstaged = status.unstaged + status.untracked.map {
                        GitFileStatus(it, FileStatus.UNTRACKED)
                    }
                    if (allUnstaged.isNotEmpty()) {
                        item(key = "unstaged_header") {
                            UnstagedSectionHeader(
                                title = stringResource(Strings.git_section_changes),
                                count = allUnstaged.size,
                                onStageAll = onStageAll
                            )
                        }
                        items(allUnstaged, key = { "unstaged_${it.path}" }) { file ->
                            GitFileCard(
                                file = file,
                                isStaged = false,
                                onStage = { onStageFile(file.path) },
                                onUnstage = { },
                                onDiscard = {
                                    if (file.status != FileStatus.UNTRACKED) {
                                        onDiscardChanges(file.path)
                                    }
                                },
                                onClick = { onFileClick(file.path) },
                                onShowDiff = {
                                    if (file.status != FileStatus.UNTRACKED) {
                                        onShowDiff(file.path, false)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 提交信息输入区域
 */
@Composable
private fun CommitMessageSection(
    commitMessage: String,
    onMessageChange: (String) -> Unit,
    onCommit: () -> Unit,
    canCommit: Boolean,
    recentCommitMessages: List<String> = emptyList(),
    onClearHistory: () -> Unit = {}
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    TinaOverlayPanelSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = commitMessage,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 150.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (commitMessage.isEmpty()) {
                            Text(
                                text = stringResource(Strings.git_commit_message_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DrawerGitActionButton(
                        onClick = { showEmojiPicker = true },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEmotions,
                            contentDescription = stringResource(Strings.content_desc_emoji),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DrawerGitActionButton(
                        onClick = { showHistoryDialog = true },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = stringResource(Strings.content_desc_history),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TinaPrimaryButton(
                    text = stringResource(Strings.git_commit),
                    onClick = onCommit,
                    enabled = canCommit,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                )
            }
        }
    }

    // 表情选择器对话框
    if (showEmojiPicker) {
        GitCommitEmojiPickerDialog(
            onEmojiSelected = { emoji ->
                onMessageChange("${emoji.emoji} ${commitMessage.trimStart()}")
            },
            onDismiss = { showEmojiPicker = false }
        )
    }

    // 历史记录对话框
    if (showHistoryDialog) {
        GitCommitHistoryDialog(
            recentMessages = recentCommitMessages,
            onMessageSelected = { message ->
                onMessageChange(message)
            },
            onClearHistory = onClearHistory,
            onDismiss = { showHistoryDialog = false }
        )
    }
}

/**
 * 已暂存区域标题
 */
@Composable
private fun StagedSectionHeader(
    title: String,
    count: Int,
    onUnstageAll: (() -> Unit)?
) {
    DrawerGitSectionHeader(
        title = title,
        count = count,
        actionText = onUnstageAll?.let { stringResource(Strings.action_unstage_all) },
        actionTint = MaterialTheme.colorScheme.onSurfaceVariant,
        onAction = onUnstageAll
    )
}

/**
 * 未暂存区域标题
 */
@Composable
private fun UnstagedSectionHeader(
    title: String,
    count: Int,
    onStageAll: () -> Unit
) {
    DrawerGitSectionHeader(
        title = title,
        count = count,
        actionText = stringResource(Strings.action_stage_all),
        actionIcon = Icons.Default.Add,
        actionTint = MaterialTheme.colorScheme.primary,
        onAction = onStageAll
    )
}

/**
 * Git 文件卡片组件
 *
 * 美化版本：卡片样式，左侧彩色状态图标，显示文件名和路径
 */
@Composable
private fun GitFileCard(
    file: GitFileStatus,
    isStaged: Boolean,
    onStage: () -> Unit,
    onUnstage: () -> Unit,
    onDiscard: () -> Unit,
    onClick: () -> Unit,
    onShowDiff: () -> Unit = {}
) {
    val statusColor = getFileStatusColor(file.status)
    val statusIcon = getFileStatusIcon(file.status)
    val statusText = stringResource(getFileStatusTextResId(file.status))

    TinaDialogSelectableCard(
        selected = false,
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        unselectedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
        unselectedBorder = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TinaOverlayPanelSurface(
                modifier = Modifier
                    .size(36.dp),
                shape = MaterialTheme.shapes.small,
                containerColor = statusColor.copy(alpha = 0.14f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = rememberTinaPainter(statusIcon),
                        contentDescription = statusText,
                        modifier = Modifier.size(20.dp),
                        tint = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                val fileName = file.path.substringAfterLast("/").ifEmpty { file.path }
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val pathOrStatus = if (file.path.contains("/")) {
                    file.path.substringBeforeLast("/")
                } else {
                    statusText
                }
                Text(
                    text = pathOrStatus,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (file.status != FileStatus.UNTRACKED) {
                    DrawerGitActionButton(
                        onClick = onShowDiff,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = stringResource(Strings.content_desc_view_diff),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isStaged) {
                    DrawerGitActionButton(
                        onClick = onUnstage,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                    ) {
                        Icon(
                            painter = rememberTinaPainter(Drawables.ic_remove),
                            contentDescription = stringResource(Strings.content_desc_unstage),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else {
                    if (file.status != FileStatus.UNTRACKED) {
                        DrawerGitActionButton(
                            onClick = onDiscard,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(Strings.content_desc_discard_changes),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    DrawerGitActionButton(
                        onClick = onStage,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(Strings.content_desc_stage),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * 获取文件状态图标资源
 */
private fun getFileStatusIcon(status: FileStatus): Int = when (status) {
    FileStatus.MODIFIED -> Drawables.ic_git_modified
    FileStatus.ADDED -> Drawables.ic_git_added
    FileStatus.DELETED -> Drawables.ic_git_deleted
    FileStatus.RENAMED -> Drawables.ic_git_renamed
    FileStatus.COPIED -> Drawables.ic_git_copied
    FileStatus.UNTRACKED -> Drawables.ic_git_untracked
    FileStatus.IGNORED -> Drawables.ic_git_ignored
}

/**
 * 获取文件状态文本资源 ID
 */
private fun getFileStatusTextResId(status: FileStatus): Int = when (status) {
    FileStatus.MODIFIED -> Strings.git_status_modified
    FileStatus.ADDED -> Strings.git_status_added
    FileStatus.DELETED -> Strings.git_status_deleted
    FileStatus.RENAMED -> Strings.git_status_renamed
    FileStatus.COPIED -> Strings.git_status_copied
    FileStatus.UNTRACKED -> Strings.git_status_untracked
    FileStatus.IGNORED -> Strings.git_status_ignored
}

@Composable
private fun NotARepositoryContent(
    modifier: Modifier = Modifier,
    onInitRepository: () -> Unit = {}
) {
    DrawerGitEmptyState(
        title = stringResource(Strings.git_not_a_repo),
        subtitle = stringResource(Strings.git_use_init_hint),
        actionText = stringResource(Strings.git_init),
        onAction = onInitRepository,
        modifier = modifier
    )
}

@Composable
private fun NoChangesContent(modifier: Modifier = Modifier) {
    DrawerGitEmptyState(
        title = stringResource(Strings.git_no_changes),
        modifier = modifier
    )
}

@Composable
private fun DrawerGitActionButton(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = minHeight, minHeight = minHeight),
        enabled = enabled,
        minHeight = minHeight,
        shape = MaterialTheme.shapes.small,
        color = color,
        contentPadding = PaddingValues(0.dp),
        content = content
    )
}

@Composable
private fun DrawerGitSectionHeader(
    title: String,
    count: Int,
    actionText: String?,
    actionIcon: ImageVector? = null,
    actionTint: Color,
    onAction: (() -> Unit)?
) {
    TinaOverlayPanelSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (actionText != null && onAction != null) {
                DrawerGitActionButton(
                    onClick = onAction,
                    modifier = Modifier.height(28.dp),
                    minHeight = 28.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        actionIcon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = actionTint
                            )
                        }
                        Text(
                            text = actionText,
                            style = MaterialTheme.typography.labelSmall,
                            color = actionTint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerGitEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        TinaOverlayPanelSurface(
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = TinaTabIcons.Git,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                subtitle?.takeIf(String::isNotBlank)?.let { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                    )
                }

                if (actionText != null && onAction != null) {
                    TinaPrimaryButton(
                        text = actionText,
                        onClick = onAction,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun getFileStatusColor(status: FileStatus): Color = when (status) {
    FileStatus.MODIFIED -> TinaSemanticColors.Git.modified
    FileStatus.ADDED -> TinaSemanticColors.Git.added
    FileStatus.DELETED -> TinaSemanticColors.Git.deleted
    FileStatus.RENAMED -> TinaSemanticColors.Git.renamed
    FileStatus.COPIED -> TinaSemanticColors.Git.copied
    FileStatus.UNTRACKED -> TinaSemanticColors.Git.untracked
    FileStatus.IGNORED -> TinaSemanticColors.Git.ignored
}

/**
 * 侧滑栏 Tab 枚举
 */
enum class DrawerTab(@param:StringRes val titleRes: Int) {
    FILES(Strings.drawer_tab_files_title),
    SYMBOLS(Strings.drawer_tab_symbols_title),
    GIT(Strings.drawer_tab_git_title),
    RIKKAHUB(Strings.drawer_tab_rikkahub_title)
}
