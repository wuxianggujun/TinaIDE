package com.wuxianggujun.tinaide.ui.workspace.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaOverlayPanelSurface
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButtonLarge
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.workspace.model.*
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 安装内容相关 Compose 组件
 *
 * 包含：
 * - InstallHeader: 安装页面头部
 * - InstallingContent: 安装中内容
 * - InstallCompletedContent: 安装完成内容
 * - InstallFailedContent: 安装失败内容
 * - EnvironmentConfigCard: 环境配置卡片
 */

/**
 * 安装中页面头部
 */
@Composable
fun InstallHeader(
    title: String,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Column {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SetupTopBarDefaults.Height)
                    .padding(
                        horizontal = SetupTopBarDefaults.HorizontalPadding,
                        vertical = SetupTopBarDefaults.VerticalPadding
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 条件显示返回按钮
                if (showBackButton) {
                    SetupActionButton(
                        onClick = onBack,
                        modifier = Modifier.size(SetupTopBarDefaults.IconSize)
                    ) {
                        Icon(
                            painter = rememberWorkspacePainter(Drawables.ic_arrow_back),
                            contentDescription = stringResource(Strings.content_desc_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            content()
        }
    }
}

/**
 * 安装中内容（极简版 - 最大化包列表空间）
 */
@Composable
fun InstallingContent(
    isPaused: Boolean,
    overallProgress: Float,
    isInstalling: Boolean,
    statusMessage: String,
    installStage: PRootBootstrap.InstallStage,
    packageList: List<PRootBootstrap.PackageInfo>,
    currentPackage: String?,
    onBack: () -> Unit,
    onPauseToggle: () -> Unit,
    onCancel: () -> Unit,
) {
    // 进度数字动画
    val animatedProgress by animateFloatAsState(
        targetValue = overallProgress,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "progressAnimation"
    )

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部栏
        InstallTopBar(
            title = stringResource(Strings.setup_title_env_config),
            onBack = onBack,
            onCancel = onCancel,
        )

        // 紧凑进度区域
        CompactProgressHeader(
            progress = animatedProgress,
            isPaused = isPaused,
            isAnimating = isInstalling && !isPaused,
            statusMessage = statusMessage,
            packageList = packageList
        )

        // 包列表（占据主要空间）
        GroupedPackageInstallList(
            packages = packageList,
            currentPackage = currentPackage,
            installStage = installStage,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        )

        // 底部智能提示（仅在需要时显示）
        AnimatedVisibility(
            visible = isPaused || overallProgress < 0.3f || overallProgress > 0.8f,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            SmartInstallHintCompact(
                overallProgress = overallProgress,
                isPaused = isPaused,
                isNetworkSlow = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 12.dp)
            )
        }
    }
}

/**
 * 安装页面顶部栏
 */
@Composable
private fun InstallTopBar(
    title: String,
    onBack: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp)
        ) {
            // 标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )

            if (onCancel != null) {
                SetupActionButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(SetupTopBarDefaults.IconSize)
                ) {
                    Icon(
                        painter = rememberWorkspacePainter(Drawables.ic_close),
                        contentDescription = stringResource(Strings.btn_cancel_install),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 紧凑进度头部
 */
@Composable
private fun CompactProgressHeader(
    progress: Float,
    isPaused: Boolean,
    isAnimating: Boolean,
    statusMessage: String,
    packageList: List<PRootBootstrap.PackageInfo>,
    modifier: Modifier = Modifier
) {
    val completedCount = packageList.count { it.status == PRootBootstrap.PackageStatus.COMPLETED }
    val totalCount = packageList.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 第一行：进度条 + 百分比
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 线性进度条（带渐变）
            Box(
                modifier = Modifier.weight(1f)
            ) {
                // 背景轨道
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                // 进度条（渐变色）
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }

            // 百分比
            Text(
                text = "${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isPaused) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }

        // 第二行：状态 + 包统计
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态消息
            Text(
                text = if (isPaused) stringResource(Strings.status_paused) else statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 包统计徽章
            if (totalCount > 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = rememberWorkspacePainter(Drawables.ic_package),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "$completedCount/$totalCount",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 安装完成内容
 */

