
package com.wuxianggujun.tinaide.ui.workspace.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaOverlayPanelSurface
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButtonLarge
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.workspace.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

/**
 * 安装内容相关 Compose 组件
 * 
 * 包含：
 * - InstallHeader: 安装页面头部
 * - InstallingContent: 安装中内容
 * - InstallCompletedContent: 安装完成内容
 * - InstallFailedContent: 安装失败内容
 * - EnvironmentConfigCard: 环境配置卡片
 * - InstalledComponentCard: 已安装组件卡片
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
    isRepairMode: Boolean = false,
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
@Composable
fun InstallCompletedContent(
    installedComponents: List<InstalledComponent>,
    rootfsHealth: DependencyRootfsHealthUiState = DependencyRootfsHealthUiState(),
    isRepairMode: Boolean = false,
    onEnterWorkspace: () -> Unit,
    onBack: () -> Unit,
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
            // 条件显示返回按钮（仅修复模式显示）
            if (isRepairMode) {
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
            }

            // 标题
            Text(
                text = stringResource(Strings.setup_title_ide_extension),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (isRepairMode) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                textAlign = if (isRepairMode) TextAlign.Center else TextAlign.Start
            )

            // 占位，保持标题居中（仅修复模式需要）
            if (isRepairMode) {
                Spacer(modifier = Modifier.size(SetupTopBarDefaults.IconSize))
            }
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
@Composable
fun InstallFailedContent(
    errorMessage: String,
    isNetworkRelated: Boolean,
    isRepairMode: Boolean = false,
    onRetry: () -> Unit,
    onOpenTerminal: (() -> Unit)? = null,
    onOpenLog: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val toastCopied = stringResource(Strings.toast_copied)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val errorDetailsScrollState = rememberScrollState()

    // 入场动画
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    // 失败图标的入场动画 - 带弹性效果
    val iconScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "failIconScale"
    )

    // 图标倾斜角度的动画
    val iconRotation by animateFloatAsState(
        targetValue = if (isVisible) -8f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "iconRotation"
    )

    // 检测是否是架构不匹配错误
    val isAbiMismatchError = errorMessage.contains(Strings.error_keyword_abi_mismatch.strOr(context), ignoreCase = true) ||
        errorMessage.contains("ABI mismatch", ignoreCase = true) ||
        errorMessage.contains(Strings.error_keyword_device_arch.strOr(context), ignoreCase = true) ||
        // linker/动态加载器常见提示（例如：EM_AARCH64 instead of EM_X86_64）
        (errorMessage.contains("EM_AARCH64", ignoreCase = true) && errorMessage.contains("EM_X86_64", ignoreCase = true)) ||
        (errorMessage.contains("EM_ARM", ignoreCase = true) && errorMessage.contains("EM_386", ignoreCase = true))

    // 超时错误也应该被识别为网络相关错误
    val isNetworkOrTimeoutError = isNetworkRelated ||
        errorMessage.contains("timed out", ignoreCase = true) ||
        errorMessage.contains("timeout", ignoreCase = true) ||
        errorMessage.contains("connect", ignoreCase = true)

    // 解析错误信息，提取关键路径
    val errorParts = remember(errorMessage) {
        // 尝试提取文件路径（通常是第一行或包含 / 的部分）
        val lines = errorMessage.lines()
        val mainError = lines.firstOrNull()?.trim() ?: errorMessage
        val additionalInfo = if (lines.size > 1) {
            lines.drop(1).joinToString("\n").trim()
        } else ""
        Pair(mainError, additionalInfo)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 状态栏占位
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
        )

        // 顶部导航栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp)
        ) {
            SetupActionButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
            ) {
                Icon(
                    painter = rememberWorkspacePainter(Drawables.ic_arrow_back),
                    contentDescription = stringResource(Strings.content_desc_back),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // 标题居中
            Text(
                text = stringResource(Strings.setup_title_env_config),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 主内容区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 失败图标 - 新设计：倾斜的红色圆角矩形（使用 MD3 主题色）
            val errorColor = MaterialTheme.colorScheme.error
            val errorContainerColor = MaterialTheme.colorScheme.errorContainer
            
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(iconScale),
                contentAlignment = Alignment.Center
            ) {
                // 淡粉色圆形背景（使用 errorContainer）
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(errorContainerColor)
                )

                // 倾斜的红色圆角矩形
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            rotationZ = iconRotation
                        }
                        .clip(RoundedCornerShape(TinaShapes.CardCorner))
                        .background(errorColor),
                    contentAlignment = Alignment.Center
                ) {
                    // 感叹号
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // 竖线部分
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.onError)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // 圆点部分
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onError)
                        )
                    }
                }

                // 右上角的 × 标记
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-16).dp, y = 8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    // × 符号 - 使用两条交叉的线
                    Canvas(modifier = Modifier.size(12.dp)) {
                        val strokeWidth = 2.dp.toPx()
                        // 第一条线：左上到右下
                        drawLine(
                            color = errorColor,
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                            strokeWidth = strokeWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        // 第二条线：右上到左下
                        drawLine(
                            color = errorColor,
                            start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                            end = androidx.compose.ui.geometry.Offset(0f, size.height),
                            strokeWidth = strokeWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 标题
            Text(
                text = stringResource(Strings.status_install_failed),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 副标题提示
            Text(
                text = stringResource(Strings.hint_check_network_storage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 错误日志卡片 - 新设计
            TinaOverlayPanelSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                TinaDialogContentColumn(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 标题行：日志标题 + 复制按钮
                    TinaDialogActionRow(
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 红色圆点（使用主题色）
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                            Text(
                                text = stringResource(Strings.install_log_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 复制日志按钮（使用主题色）
                        Surface(
                            onClick = {
                                scope.launch {
                                    val clipData = ClipData.newPlainText("error", errorMessage)
                                    clipboard.setClipEntry(clipData.toClipEntry())
                                }
                                Toast
                                    .makeText(context, toastCopied, Toast.LENGTH_SHORT)
                                    .show()
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.inverseSurface
                        ) {
                            Text(
                                text = stringResource(Strings.btn_copy_log),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // 错误详情区域 - 浅灰色背景
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .heightIn(max = 120.dp)
                                    .verticalScroll(errorDetailsScrollState),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 主要错误信息 - 红色高亮（使用主题色）
                                Text(
                                    text = errorParts.first,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                                // 附加信息 - 灰色
                                if (errorParts.second.isNotEmpty()) {
                                    Text(
                                        text = errorParts.second,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 底部区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isAbiMismatchError) {
                // 架构不匹配错误：只显示退出按钮
                TinaPrimaryButtonLarge(
                    text = stringResource(Strings.btn_exit_app),
                    onClick = {
                        // 退出应用
                        (context as? android.app.Activity)?.finishAffinity()
                    },
                    icon = rememberWorkspacePainter(Drawables.ic_menu_exit)
                )
                
                // 提示信息
                Text(
                    text = stringResource(Strings.hint_abi_mismatch_uninstall),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )
            } else {
                // 正常错误：显示重试和其他选项
                TinaPrimaryButtonLarge(
                    text = stringResource(Strings.btn_retry_install_now),
                    onClick = onRetry,
                    icon = rememberWorkspacePainter(Drawables.ic_sync)
                )

                if (onOpenLog != null) {
                    TinaOutlinedButton(
                        text = stringResource(Strings.link_view_full_log),
                        onClick = onOpenLog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    )
                }

                TinaOutlinedButton(
                    text = stringResource(Strings.btn_open_terminal),
                    onClick = { onOpenTerminal?.invoke() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )

                // 帮助链接
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Strings.need_help_prefix),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(Strings.settings_title_help),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            context.openSettingsRoute(
                                route = SettingsRouteIds.HELP,
                                helpDocumentId = HelpDocumentIds.FEEDBACK_GUIDE
                            )
                        }
                    )
                    Text(
                        text = stringResource(Strings.and_word),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(Strings.contact_support),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            context.openSettingsRoute(route = SettingsRouteIds.FEEDBACK)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 已安装组件卡片
 */
@Composable
fun InstalledComponentCard(
    component: InstalledComponent,
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
                // 组件图标
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = rememberWorkspacePainter(component.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color.Unspecified // 保持原始颜色
                    )
                }
                
                Column {
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = component.version,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 完成勾选
            Icon(
                painter = rememberWorkspacePainter(Drawables.ic_check_circle),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 性能标签
 */
@Composable
fun PerformanceTag(
    label: String,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isHighlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * 带动画效果的进度百分比文字
 *
 * 特性：
 * - 数字平滑变化动画
 * - 暂停时显示不同样式
 */
@Composable
fun AnimatedProgressText(
    progress: Float,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val percentage = (progress * 100).roundToInt()

    // 轻微的缩放动画，当数字变化时
    val scale by animateFloatAsState(
        targetValue = if (isPaused) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "textScale"
    )

    Text(
        text = "$percentage%",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = if (isPaused) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = modifier.scale(scale)
    )
}

/**
 * 带脉冲动画的状态指示圆点
 *
 * 用于显示"运行中"的状态
 */
@Composable
fun PulsingStatusDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: androidx.compose.ui.unit.Dp = 12.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusDot")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 紧凑版智能提示（仅在关键时刻显示）
 */
@Composable
fun SmartInstallHintCompact(
    overallProgress: Float,
    isPaused: Boolean,
    isNetworkSlow: Boolean,
    modifier: Modifier = Modifier
) {
    val (icon, message) = when {
        isPaused -> Pair(
            Drawables.ic_pause,
            stringResource(Strings.hint_paused_can_resume)
        )
        isNetworkSlow -> Pair(
            Drawables.ic_warning_amber,
            stringResource(Strings.hint_slow_download)
        )
        overallProgress < 0.3f -> Pair(
            Drawables.ic_info_outline,
            stringResource(Strings.hint_first_install_slow)
        )
        overallProgress > 0.8f -> Pair(
            Drawables.ic_check_circle,
            stringResource(Strings.hint_almost_done)
        )
        else -> return // 不显示
    }
    val containerColor = when {
        isPaused -> MaterialTheme.colorScheme.surfaceVariant
        isNetworkSlow -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        overallProgress > 0.8f -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = when {
        isNetworkSlow -> MaterialTheme.colorScheme.error
        overallProgress > 0.8f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    TinaOverlayPanelSurface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        containerColor = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = rememberWorkspacePainter(icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = iconTint
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 智能安装提示
 *
 * 根据安装状态和网络情况动态显示不同的提示信息
 */
@Composable
fun SmartInstallHint(
    overallProgress: Float,
    isPaused: Boolean,
    isNetworkSlow: Boolean,
    modifier: Modifier = Modifier
) {
    // 根据进度和状态决定显示什么提示
    val (icon, message) = when {
        isPaused -> {
            Pair(
                Drawables.ic_pause,
                stringResource(Strings.hint_paused_can_resume)
            )
        }
        isNetworkSlow -> {
            Pair(
                Drawables.ic_warning_amber,
                stringResource(Strings.hint_slow_download)
            )
        }
        overallProgress < 0.3f -> {
            Pair(
                Drawables.ic_info_outline,
                stringResource(Strings.hint_first_install_slow)
            )
        }
        overallProgress > 0.8f -> {
            Pair(
                Drawables.ic_check_circle,
                stringResource(Strings.hint_almost_done)
            )
        }
        else -> {
            Pair(
                Drawables.ic_info_outline,
                stringResource(Strings.hint_installing_packages)
            )
        }
    }
    val containerColor = when {
        isPaused -> MaterialTheme.colorScheme.surfaceVariant
        isNetworkSlow -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        overallProgress > 0.8f -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = when {
        isNetworkSlow -> MaterialTheme.colorScheme.error
        overallProgress > 0.8f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        containerColor = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = rememberWorkspacePainter(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private object SettingsRouteIds {
    const val HELP = "help"
    const val FEEDBACK = "feedback"
}

private object HelpDocumentIds {
    const val FEEDBACK_GUIDE = "feedback-guide"
}

private const val SETTINGS_ACTIVITY_CLASS_NAME = "com.wuxianggujun.tinaide.settings.SettingsActivity"
private const val EXTRA_INITIAL_ROUTE = "extra_initial_route"
private const val EXTRA_INITIAL_HELP_DOCUMENT_ID = "extra_initial_help_document_id"

private fun Context.openSettingsRoute(
    route: String,
    helpDocumentId: String? = null
) {
    val intent = Intent()
        .setClassName(packageName, SETTINGS_ACTIVITY_CLASS_NAME)
        .putExtra(EXTRA_INITIAL_ROUTE, route)

    helpDocumentId
        ?.takeUnless { it.isBlank() }
        ?.let { intent.putExtra(EXTRA_INITIAL_HELP_DOCUMENT_ID, it) }

    runCatching { startActivity(intent) }
        .onFailure {
            Toast.makeText(
                this,
                Strings.error_cannot_open_link.strOr(this),
                Toast.LENGTH_SHORT
            ).show()
        }
}
