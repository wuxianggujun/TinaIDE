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
 * Install failed UI, smart install hint, and route id helpers.
 */

@Composable
fun InstallFailedContent(
    errorMessage: String,
    isNetworkRelated: Boolean,
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
        } else {
            ""
        }
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
                                helpDocumentId = HelpDocumentIds.ABOUT_AND_LOGS
                            )
                        }
                    )
                }
            }
        }
    }
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

private object SettingsRouteIds {
    const val HELP = "help"
}

private object HelpDocumentIds {
    const val ABOUT_AND_LOGS = "about-and-logs"
}

private const val SETTINGS_ACTIVITY_CLASS_NAME = "com.wuxianggujun.tinaide.settings.SettingsActivity"
private const val EXTRA_INITIAL_ROUTE = "extra_initial_route"
private const val EXTRA_INITIAL_HELP_DOCUMENT_ID = "extra_initial_help_document_id"

internal fun Context.openSettingsRoute(
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
