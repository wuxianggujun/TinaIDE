package com.wuxianggujun.tinaide.ui.activity

import android.app.ActivityManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.wuxianggujun.tinaide.MainActivity
import com.wuxianggujun.tinaide.core.crash.CrashLogAutoUploader
import com.wuxianggujun.tinaide.core.crash.CrashReportFiles
import com.wuxianggujun.tinaide.core.crash.CrashUploadState
import com.wuxianggujun.tinaide.core.crash.CrashUploadStatusTextResolver
import com.wuxianggujun.tinaide.core.crash.CrashUploadStatusTextSpec
import com.wuxianggujun.tinaide.core.crash.NativeCrashHandler
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.startup.ThemeInitializer
import com.wuxianggujun.tinaide.storage.ExternalFileIntents
import com.wuxianggujun.tinaide.ui.MainPortalActivity
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Crash presentation runs in its own task and never treats a user program as the IDE. */
class CrashActivity : ComponentActivity() {
    private var crashReport by mutableStateOf("")
    private var crashEventId by mutableIntStateOf(0)
    private var crashOrigin by mutableStateOf(CrashScreenOrigin.COMPONENT)
    private var crashLogFileName: String = ""
    private var crashAutoUploadEnabled by mutableStateOf(true)
    private var crashUploadSnapshot by mutableStateOf<CrashUploadState.Snapshot?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeInitializer(this).applyNightMode()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readCrashIntent(intent)
        onBackPressedDispatcher.addCallback(this) { finish() }

        setContent {
            TinaIDETheme {
                ApplySystemBars(window)
                CrashScreen(
                    crashReport = crashReport,
                    origin = crashOrigin,
                    crashAutoUploadEnabled = crashAutoUploadEnabled,
                    crashUploadSnapshot = crashUploadSnapshot,
                    onCopy = ::copyToClipboard,
                    onShare = ::shareCrashReport,
                    onPrimaryAction = {
                        if (crashOrigin.canRestartIde) restartIde() else returnToEditor()
                    },
                    onClose = { finish() },
                    eventId = crashEventId,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readCrashIntent(intent)
    }

    private fun readCrashIntent(intent: Intent) {
        crashEventId++
        crashReport = intent.getStringExtra(EXTRA_STACK_TRACE) ?: Strings.crash_no_info.strOr(this)
        crashOrigin = resolveCrashScreenOrigin(
            packageName = packageName,
            processName = intent.getStringExtra(EXTRA_PROCESS_NAME).orEmpty(),
            requestedSource = intent.getStringExtra(EXTRA_SOURCE),
            report = crashReport,
        )
        crashLogFileName = intent.getStringExtra(EXTRA_CRASH_LOG_FILE_NAME).orEmpty()
        crashUploadSnapshot = null
        if (crashOrigin.canUploadToHost) {
            refreshCrashUploadState()
            CrashLogAutoUploader.uploadFromCrashScreen(applicationContext, lifecycleScope) {
                lifecycleScope.launch { refreshCrashUploadState() }
            }
        }
    }

    private fun refreshCrashUploadState() {
        crashAutoUploadEnabled = CrashUploadState.isAutoUploadEnabled(applicationContext)
        val last = CrashUploadState.getLastUploadSnapshot(applicationContext)
        crashUploadSnapshot = if (crashLogFileName.isNotBlank() && last.fileName == crashLogFileName) {
            last
        } else {
            CrashUploadState.Snapshot(
                status = CrashUploadState.Status.NONE,
                fileName = crashLogFileName,
                fileMtime = 0L,
                updatedAt = 0L,
                reason = "",
                attemptCount = 0,
            )
        }
    }

    private fun copyToClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(Strings.crash_log_title.strOr(this), crashReport))
            Toast.makeText(this, Strings.crash_copied.strOr(this), Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Timber.w(error, "Could not copy crash preview")
            Toast.makeText(this, Strings.crash_copy_failed.strOr(this, error.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCrashReport() {
        lifecycleScope.launch {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val fileName = if (crashOrigin == CrashScreenOrigin.USER_PROGRAM) {
                    "user_native_crash_$timestamp.txt"
                } else {
                    "tinaide_crash_$timestamp.txt"
                }
                val crashFile = withContext(Dispatchers.IO) {
                    CrashReportFiles.export(
                        tombstoneRoot = NativeCrashHandler.getTombstoneDir(this@CrashActivity),
                        fileName = crashLogFileName,
                        fallbackReport = crashReport,
                        destination = File(File(cacheDir, "crash_reports"), fileName),
                    )
                }
                val uri = ExternalFileIntents.getShareableUri(this@CrashActivity, crashFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    val subject = if (crashOrigin.canUploadToHost) Strings.crash_share_subject else Strings.crash_log_title
                    putExtra(Intent.EXTRA_SUBJECT, subject.strOr(this@CrashActivity))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, Strings.crash_share_chooser_title.strOr(this@CrashActivity)))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "Could not share crash report")
                Toast.makeText(this@CrashActivity, Strings.crash_share_failed.strOr(this@CrashActivity, error.message.orEmpty()), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun returnToEditor() {
        // Bring the existing host task forward without recreating its editor or discarding buffers.
        val restored = runCatching {
            val manager = getSystemService(ActivityManager::class.java)
            val hostNames = setOf(MainActivity::class.java.name, MainPortalActivity::class.java.name)
            val hostTask = manager?.appTasks?.firstOrNull { task ->
                val info = task.taskInfo ?: return@firstOrNull false
                info.taskId != taskId &&
                    (info.baseActivity?.className?.let { it in hostNames } == true ||
                        info.topActivity?.className?.let { it in hostNames } == true)
            }
            hostTask?.moveToFront()
            hostTask != null
        }.onFailure { Timber.w(it, "Could not bring the existing IDE task forward") }.getOrDefault(false)
        if (!restored) {
            startActivity(Intent(this, MainPortalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }
        finish()
    }

    private fun restartIde() {
        // Defensive guard: even a future incorrectly wired button cannot restart the host for a runtime crash.
        if (!crashOrigin.canRestartIde) {
            returnToEditor()
            return
        }
        startActivity(Intent(this, MainPortalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    companion object {
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_PROCESS_NAME = "extra_process_name"
        private const val EXTRA_CRASH_LOG_FILE_NAME = "extra_crash_log_file_name"
        const val SOURCE_APP = "app"
        const val SOURCE_USER_NATIVE = "user_native"

        internal fun createIntent(
            context: Context,
            crashReport: String,
            source: String,
            crashLogFileName: String,
            processName: String,
        ): Intent {
            val safeReport = if (crashReport.length > CrashReportFiles.MAX_PREVIEW_CHARS) {
                crashReport.take(CrashReportFiles.MAX_PREVIEW_CHARS) + Strings.crash_log_truncated.strOr(context)
            } else {
                crashReport
            }
            return Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_STACK_TRACE, safeReport)
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_PROCESS_NAME, processName)
                putExtra(EXTRA_CRASH_LOG_FILE_NAME, crashLogFileName)
                // The manifest assigns a separate crash affinity. Never CLEAR_TASK here.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }

        fun start(
            context: Context,
            crashReport: String,
            source: String = SOURCE_APP,
            crashLogFileName: String = "",
            processName: String = "",
        ) {
            context.startActivity(createIntent(context, crashReport, source, crashLogFileName, processName))
        }
    }
}

@Composable
private fun ApplySystemBars(window: Window) {
    val color = MaterialTheme.colorScheme.background
    val darkIcons = color.luminance() > 0.5f
    SideEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = color.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = color.toArgb()
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

@Composable
internal fun CrashScreen(
    crashReport: String,
    origin: CrashScreenOrigin,
    crashAutoUploadEnabled: Boolean,
    crashUploadSnapshot: CrashUploadState.Snapshot?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPrimaryAction: () -> Unit,
    onClose: () -> Unit,
    eventId: Int = 0,
) {
    var showDetails by remember(eventId, crashReport, origin) { mutableStateOf(false) }
    val summary = remember(crashReport) { crashSummaryIdentifier(crashReport) }
    val titleRes = when (origin) {
        CrashScreenOrigin.HOST_APP -> Strings.crash_title
        CrashScreenOrigin.USER_PROGRAM -> Strings.crash_title_user_native
        CrashScreenOrigin.COMPONENT -> Strings.crash_title_component
    }
    val hintRes = when (origin) {
        CrashScreenOrigin.HOST_APP -> Strings.crash_hint
        CrashScreenOrigin.USER_PROGRAM -> Strings.crash_hint_user_native
        CrashScreenOrigin.COMPONENT -> Strings.crash_hint_component
    }
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showDetails) Spacer(Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(if (showDetails) 12.dp else 18.dp).size(32.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!showDetails) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(
                            MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)
                        ).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(Strings.crash_btn_copy))
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Outlined.Share, stringResource(Strings.crash_btn_share))
                    }
                }
                if (origin.canUploadToHost) {
                    val status = if (!crashAutoUploadEnabled) {
                        CrashUploadStatusTextSpec(Strings.settings_crash_upload_status_disabled, isAttention = true)
                    } else {
                        crashUploadSnapshot?.let { CrashUploadStatusTextResolver.resolve(it.status) }
                    }
                    if (status != null) {
                        Text(
                            text = stringResource(status.messageRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                }
                CrashLogPreview(crashReport, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
            }
            TinaPrimaryButton(
                text = stringResource(
                    if (origin.canRestartIde) Strings.crash_btn_restart else Strings.crash_btn_return_editor
                ),
                onClick = onPrimaryAction,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
            Row(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(stringResource(if (showDetails) Strings.crash_btn_hide_details else Strings.crash_btn_show_details))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(if (origin.canRestartIde) Strings.crash_btn_exit else Strings.crash_btn_close))
                }
            }
        }
    }
}

@Composable
private fun CrashLogPreview(report: String, modifier: Modifier) {
    val truncatedSuffix = stringResource(Strings.crash_log_truncated)
    val preview = remember(report, truncatedSuffix) {
        if (report.length > MAX_DETAILS_CHARS) report.take(MAX_DETAILS_CHARS) + truncatedSuffix else report
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = preview,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        )
    }
}

private const val MAX_DETAILS_CHARS = 50_000
