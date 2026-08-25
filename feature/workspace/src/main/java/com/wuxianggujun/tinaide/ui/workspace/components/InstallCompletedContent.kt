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
 * Install completed UI and environment config card.
 */

@Composable
fun InstallCompletedContent(
    installedComponents: List<InstalledComponent>,
    rootfsHealth: DependencyRootfsHealthUiState = DependencyRootfsHealthUiState(),
    onEnterWorkspace: () -> Unit,
    onRefreshRootfsHealth: (() -> Unit)? = null,
    onOpenLog: (() -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
) {
    val hasLinuxRuntime = installedComponents.any { it.iconRes == Drawables.ic_linux_default }
    val runtimeEnvValue = if (hasLinuxRuntime) {
        stringResource(Strings.linux_runtime_env)
    } else {
        stringResource(Strings.android_native_toolchain_env)
    }

    // 入场动画
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    // 成功图标的弹性缩放动画
    val iconScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "iconScale"
    )

    // 成功图标的呼吸发光效果
    val infiniteTransition = rememberInfiniteTransition(label = "successGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val rootfsHealthUnknownText = stringResource(Strings.workspace_linux_health_unknown)
    val rootfsHealthStatusText = rootfsHealth.statusText.ifBlank { rootfsHealthUnknownText }
    val rootfsHealthSubtitle = listOf(rootfsHealthStatusText, rootfsHealth.detailText)
        .filter { value -> value.isNotBlank() }
        .joinToString(" · ")
    val isRootfsHealthChecking = rootfsHealth.status == DependencyRootfsHealthStatus.CHECKING
    val refreshRootfsHealthButtonText = if (isRootfsHealthChecking) {
        stringResource(Strings.btn_checking_linux_health)
    } else {
        stringResource(Strings.btn_refresh_linux_health)
    }

    // 环境配置项列表
    val baseConfigItems = listOf(
        EnvironmentConfigItem(
            iconType = ConfigIconType.CODE,
            title = stringResource(Strings.config_runtime_env),
            subtitle = runtimeEnvValue
        ),
        EnvironmentConfigItem(
            iconType = ConfigIconType.FOLDER,
            title = stringResource(Strings.config_workspace_path),
            subtitle = stringResource(Strings.config_workspace_path_value)
        ),
        EnvironmentConfigItem(
            iconType = ConfigIconType.SETTINGS,
            title = stringResource(Strings.config_security_policy),
            subtitle = stringResource(Strings.config_security_policy_value)
        )
    )
    val configItems = if (hasLinuxRuntime) {
        baseConfigItems + EnvironmentConfigItem(
            iconType = ConfigIconType.SETTINGS,
            title = stringResource(Strings.workspace_linux_health_status),
            subtitle = rootfsHealthSubtitle,
        )
    } else {
        baseConfigItems
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 状态栏占位
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
        )

        // 顶部区域
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
            // 标题
            Text(
                text = stringResource(Strings.setup_title_ide_extension),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }

        // 主内容区域
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 终端图标 - 带动画效果
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(iconScale),
                contentAlignment = Alignment.Center
            ) {
                // 发光背景
                val glowColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.size(100.dp)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = glowAlpha),
                                Color.Transparent
                            )
                        ),
                        radius = 50.dp.toPx()
                    )
                }

                // 蓝色圆角矩形背景
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    // 代码符号 <>
                    Text(
                        text = "<>",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // 右上角成功勾选标记
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = rememberWorkspacePainter(Drawables.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 标题
            Text(
                text = stringResource(Strings.status_linux_ready),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 描述
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(Strings.desc_linux_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 4.dp,
                        alignment = Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Strings.desc_sandbox_mode_prefix),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(Strings.desc_sandbox_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(Strings.desc_sandbox_mode_suffix),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 环境配置项列表 - 带延迟入场动画
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                configItems.forEach { item ->
                    EnvironmentConfigCard(item = item)
                }
            }
        }

        // 底部区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasLinuxRuntime && onRefreshRootfsHealth != null) {
                TinaOutlinedButton(
                    text = refreshRootfsHealthButtonText,
                    onClick = onRefreshRootfsHealth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !isRootfsHealthChecking,
                    icon = rememberWorkspacePainter(Drawables.ic_sync),
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (onOpenLog != null || onOpenTerminal != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (onOpenLog != null) {
                        TinaOutlinedButton(
                            text = stringResource(Strings.link_view_full_log),
                            onClick = onOpenLog,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        )
                    }

                    if (onOpenTerminal != null) {
                        TinaOutlinedButton(
                            text = stringResource(Strings.btn_open_terminal),
                            onClick = onOpenTerminal,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // 进入工作台按钮
            TinaPrimaryButtonLarge(
                text = stringResource(Strings.btn_enter_workspace),
                onClick = onEnterWorkspace
            )
        }
    }
}

/**
 * 环境配置卡片
 */
@Composable
fun EnvironmentConfigCard(
    item: EnvironmentConfigItem,
    modifier: Modifier = Modifier
) {
    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标背景
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    when (item.iconType) {
                        ConfigIconType.CODE -> {
                            Text(
                                text = "<>",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ConfigIconType.FOLDER -> {
                            Icon(
                                painter = rememberWorkspacePainter(Drawables.ic_folder),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ConfigIconType.SETTINGS -> {
                            Icon(
                                painter = rememberWorkspacePainter(Drawables.ic_settings),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 绿色圆点状态指示
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * 安装失败内容 - 新版设计
 *
 * 设计特点：
 * - 倾斜的红色圆角矩形图标，带感叹号
 * - 淡粉色圆形背景
 * - 右上角带 × 的关闭标记
 * - 错误日志卡片带高亮显示关键路径
 * - 底部带帮助链接
 */

