package com.wuxianggujun.tinaide.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.wuxianggujun.tinaide.core.crash.CrashLogAutoUploader
import com.wuxianggujun.tinaide.core.crash.CrashUploadState
import com.wuxianggujun.tinaide.core.crash.CrashUploadStatusTextResolver
import com.wuxianggujun.tinaide.core.crash.CrashUploadStatusTextSpec
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.startup.ThemeInitializer
import com.wuxianggujun.tinaide.storage.ExternalFileIntents
import com.wuxianggujun.tinaide.ui.MainPortalActivity
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess
import kotlinx.coroutines.launch

/**
 * 崩溃信息展示页面（纯 Compose 实现）
 * 显示 xCrash 生成的完整崩溃日志，支持复制和重启。
 */
class CrashActivity : ComponentActivity() {

    private var crashReport: String = ""
    private var crashSource: String = SOURCE_APP
    private var crashLogFileName: String = ""
    private var crashAutoUploadEnabled by mutableStateOf(true)
    private var crashUploadSnapshot by mutableStateOf<CrashUploadState.Snapshot?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeInitializer(this).applyNightMode()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 禁用系统返回键：必须选择"重启"或"退出"
        onBackPressedDispatcher.addCallback(this) { }

        // xCrash 已提供完整的崩溃报告，直接使用
        crashReport = intent.getStringExtra(EXTRA_STACK_TRACE) ?: Strings.crash_no_info.strOr(this)
        crashSource = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_APP
        crashLogFileName = intent.getStringExtra(EXTRA_CRASH_LOG_FILE_NAME).orEmpty()

        if (crashSource == SOURCE_APP) {
            refreshCrashUploadState()
            CrashLogAutoUploader.uploadFromCrashScreen(applicationContext, lifecycleScope) {
                lifecycleScope.launch {
                    refreshCrashUploadState()
                }
            }
        }

        setContent {
            TinaIDETheme {
                ApplySystemBars(window = window)
                CrashScreen(
                    crashReport = crashReport,
                    crashSource = crashSource,
                    crashAutoUploadEnabled = crashAutoUploadEnabled,
                    crashUploadSnapshot = crashUploadSnapshot,
                    onCopy = { copyToClipboard() },
                    onShare = { shareCrashReport() },
                    onRestart = { restartApp() },
                    onExit = {
                        finishAffinity()
                        exitProcess(0)
                    }
                )
            }
        }
    }

    private fun refreshCrashUploadState() {
        crashAutoUploadEnabled = CrashUploadState.isAutoUploadEnabled(applicationContext)
        crashUploadSnapshot = getCrashUploadSnapshotForCurrentCrash()
    }

    private fun getCrashUploadSnapshotForCurrentCrash(): CrashUploadState.Snapshot {
        val snapshot = CrashUploadState.getLastUploadSnapshot(applicationContext)
        val currentFileName = crashLogFileName.trim()
        if (currentFileName.isBlank()) {
            return CrashUploadState.Snapshot(
                status = CrashUploadState.Status.NONE,
                fileName = "",
                fileMtime = 0L,
                updatedAt = 0L,
                reason = "",
                attemptCount = 0,
            )
        }
        if (snapshot.fileName == currentFileName) {
            return snapshot
        }
        return CrashUploadState.Snapshot(
            status = CrashUploadState.Status.NONE,
            fileName = currentFileName,
            fileMtime = 0L,
            updatedAt = 0L,
            reason = "",
            attemptCount = 0,
        )
    }

    private fun copyToClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(Strings.crash_log_title.strOr(this), crashReport)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, Strings.crash_copied.strOr(this), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, Strings.crash_copy_failed.strOr(this, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCrashReport() {
        try {
            val shareDir = File(cacheDir, "crash_reports").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = if (crashSource == SOURCE_USER_NATIVE) {
                "user_native_crash_$timestamp.txt"
            } else {
                "tinaide_crash_$timestamp.txt"
            }
            val crashFile = File(shareDir, fileName).apply {
                writeText(crashReport, Charsets.UTF_8)
            }
            val uri = ExternalFileIntents.getShareableUri(this, crashFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, Strings.crash_share_subject.strOr(this@CrashActivity))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, Strings.crash_share_chooser_title.strOr(this)))
        } catch (e: Exception) {
            Toast.makeText(this, Strings.crash_share_failed.strOr(this, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartApp() {
        val intent = Intent(this, MainPortalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finishAffinity()
        exitProcess(0)
    }

    companion object {
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
        const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_CRASH_LOG_FILE_NAME = "extra_crash_log_file_name"
        const val SOURCE_APP = "app"
        const val SOURCE_USER_NATIVE = "user_native"
        private const val MAX_CRASH_REPORT_EXTRA_CHARS = 120_000

        fun start(
            context: Context,
            crashReport: String,
            source: String = SOURCE_APP,
            crashLogFileName: String = "",
        ) {
            val safeReport = if (crashReport.length > MAX_CRASH_REPORT_EXTRA_CHARS) {
                crashReport.take(MAX_CRASH_REPORT_EXTRA_CHARS) +
                    "\n\n[Crash report truncated before launching CrashActivity]"
            } else {
                crashReport
            }
            val intent = Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_STACK_TRACE, safeReport)
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_CRASH_LOG_FILE_NAME, crashLogFileName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun ApplySystemBars(window: Window) {
    val systemBarColor = MaterialTheme.colorScheme.background
    val useDarkIcons = systemBarColor.luminance() > 0.5f

    SideEffect {
        // 设置系统栏颜色 - API 35+ 不再支持直接设置，使用 edge-to-edge 模式
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // API 34 及以下使用传统方式
            @Suppress("DEPRECATION")
            window.statusBarColor = systemBarColor.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = systemBarColor.toArgb()
        }
        // API 35+ 系统会自动处理边到边显示，这里只需设置图标颜色

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }
}

@Composable
private fun CrashScreen(
    crashReport: String,
    crashSource: String,
    crashAutoUploadEnabled: Boolean,
    crashUploadSnapshot: CrashUploadState.Snapshot?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit
) {
    val isUserNativeCrash = crashSource == CrashActivity.SOURCE_USER_NATIVE
    val uploadStatusModel = remember(crashUploadSnapshot, isUserNativeCrash, crashAutoUploadEnabled) {
        when {
            isUserNativeCrash -> null
            !crashAutoUploadEnabled -> CrashUploadStatusTextSpec(
                messageRes = Strings.settings_crash_upload_status_disabled,
                isAttention = true,
            )
            crashUploadSnapshot == null -> null
            else -> CrashUploadStatusTextResolver.resolve(crashUploadSnapshot.status)
        }
    }
    val truncatedSuffix = stringResource(Strings.crash_log_truncated)
    // 限制显示长度，避免 UI 卡顿（完整内容仍可复制）
    val displayText = remember(crashReport, truncatedSuffix) {
        if (crashReport.length > 50000) {
            crashReport.take(50000) + truncatedSuffix
        } else {
            crashReport
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
        ) {
            // 标题
            Text(
                text = stringResource(
                    if (isUserNativeCrash) {
                        Strings.crash_title_user_native
                    } else {
                        Strings.crash_title
                    }
                ),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(
                    if (isUserNativeCrash) {
                        Strings.crash_hint_user_native
                    } else {
                        Strings.crash_hint
                    }
                ),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uploadStatusModel != null) {
                Spacer(Modifier.height(12.dp))
                CrashUploadStatusCard(uploadStatusModel)
            }

            if (isUserNativeCrash) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(Strings.crash_user_native_privacy_note),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(Modifier.height(16.dp))

            // 崩溃信息滚动区域（移除 SelectionContainer 提升性能）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                val verticalScrollState = rememberScrollState()
                val horizontalScrollState = rememberScrollState()

                Text(
                    text = displayText,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(horizontalScrollState),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 14.sp
                )

                // 垂直滚动条指示器
                if (verticalScrollState.maxValue > 0) {
                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(end = 2.dp),
                        scrollState = verticalScrollState
                    )
                }

                // 水平滚动条指示器
                if (horizontalScrollState.maxValue > 0) {
                    HorizontalScrollbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        scrollState = horizontalScrollState
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 按钮区域
            if (isUserNativeCrash) {
                TinaOutlinedButton(
                    text = stringResource(Strings.crash_btn_share),
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TinaOutlinedButton(
                    text = stringResource(Strings.crash_btn_exit),
                    onClick = onExit
                )

                Spacer(Modifier.width(8.dp))

                TinaOutlinedButton(
                    text = stringResource(Strings.crash_btn_copy),
                    onClick = onCopy
                )

                Spacer(Modifier.width(8.dp))

                TinaPrimaryButton(
                    text = stringResource(Strings.crash_btn_restart),
                    onClick = onRestart
                )
            }
        }
    }
}

@Composable
private fun CrashUploadStatusCard(model: CrashUploadStatusTextSpec) {
    val background = if (model.isAttention) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = if (model.isAttention) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = background,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(Strings.crash_upload_status_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = foreground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(model.messageRes),
            fontSize = 13.sp,
            color = foreground
        )
    }
}

/**
 * 垂直滚动条指示器
 */
@Composable
private fun VerticalScrollbar(
    modifier: Modifier = Modifier,
    scrollState: ScrollState
) {
    Box(
        modifier = modifier
            .width(8.dp)
            .drawWithContent {
                drawContent()

                val scrollbarHeight = size.height
                val contentHeight = scrollState.maxValue + scrollbarHeight
                val thumbHeight = (scrollbarHeight * scrollbarHeight / contentHeight).coerceAtLeast(20.dp.toPx())
                val thumbPosition = (scrollState.value.toFloat() / scrollState.maxValue) * (scrollbarHeight - thumbHeight)

                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.5f),
                    topLeft = Offset(0f, thumbPosition),
                    size = Size(size.width, thumbHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
    )
}

/**
 * 水平滚动条指示器
 */
@Composable
private fun HorizontalScrollbar(
    modifier: Modifier = Modifier,
    scrollState: ScrollState
) {
    Box(
        modifier = modifier
            .height(8.dp)
            .drawWithContent {
                drawContent()

                val scrollbarWidth = size.width
                val contentWidth = scrollState.maxValue + scrollbarWidth
                val thumbWidth = (scrollbarWidth * scrollbarWidth / contentWidth).coerceAtLeast(20.dp.toPx())
                val thumbPosition = (scrollState.value.toFloat() / scrollState.maxValue) * (scrollbarWidth - thumbWidth)

                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.5f),
                    topLeft = Offset(thumbPosition, 0f),
                    size = Size(thumbWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
    )
}
