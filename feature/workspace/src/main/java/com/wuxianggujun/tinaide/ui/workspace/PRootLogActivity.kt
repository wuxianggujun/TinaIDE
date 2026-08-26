package com.wuxianggujun.tinaide.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.storage.ExternalFileIntents
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialogHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaOverlayPanelSurface
import com.wuxianggujun.tinaide.ui.compose.components.TinaPanelSegmentButton
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PRootLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        immersionBar {
            transparentStatusBar()
            statusBarDarkFont(false)
            navigationBarColor(android.R.color.transparent)
            navigationBarDarkIcon(false)
        }

        setContent {
            TinaIDETheme {
                PRootLogScreen(
                    onFinish = { finish() },
                    onShareFile = { shareLogFile(it) },
                    onCopyText = { copyTextToClipboard(it) },
                    onToast = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }

    private fun shareLogFile(file: File) {
        try {
            val uri = ExternalFileIntents.getShareableUri(this, file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, Strings.share_title_export_log.strOr(this)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                Strings.proot_log_share_failed.strOr(this, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TinaIDE PRoot Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, Strings.toast_log_copied.strOr(this), Toast.LENGTH_SHORT).show()
    }
}

private data class LogFileItem(
    val file: File,
    val sizeBytes: Long,
    val lastModified: Long,
)

@Composable
private fun PRootLogScreen(
    onFinish: () -> Unit,
    onShareFile: (File) -> Unit,
    onCopyText: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<LogFileItem>>(emptyList()) }
    var selected by remember { mutableStateOf<LogFileItem?>(null) }
    var selectedContent by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    fun refresh() {
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                val dir = ProjectPaths.ensureDir(ProjectPaths.getPRootLogsRoot(context))
                val list = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }.orEmpty()
                list.sortedByDescending { it.lastModified() }
                    .map { LogFileItem(it, it.length(), it.lastModified()) }
            }
            files = items
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    fun handleBack() {
        if (selected != null) {
            selected = null
            selectedContent = null
        } else {
            onFinish()
        }
    }

    // 处理系统手势返回：优先退出详情态，再交给外层页面
    TinaBackHandlers(
        tinaBackAction(enabled = selected != null) {
            selected = null
            selectedContent = null
        }
    )

    fun open(item: LogFileItem) {
        selected = item
        selectedContent = null
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                readFileTailUtf8(item.file, maxBytes = 1_000_000L)
            }
            selectedContent = content
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { paddingValues ->
        TinaDialogContentColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TinaCustomDialogHeader(
                title = stringResource(Strings.proot_logs_title),
                leadingContent = {
                    PRootLogActionButton(
                        onClick = ::handleBack,
                        modifier = Modifier.size(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Strings.content_desc_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                trailingContent = {
                    PRootLogHeaderActions(
                        selected = selected,
                        selectedContent = selectedContent,
                        onRefresh = ::refresh,
                        onShareFile = onShareFile,
                        onCopyText = onCopyText,
                        onDelete = { showDeleteDialog = true }
                    )
                }
            )

            if (selected == null) {
                LogFileList(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    files = files,
                    dateFormat = dateFormat,
                    onOpen = { open(it) },
                )
            } else {
                LogFileDetail(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    item = selected!!,
                    content = selectedContent,
                    dateFormat = dateFormat,
                )
            }
        }
    }

    if (showDeleteDialog) {
        TinaConfirmDialog(
            title = stringResource(Strings.dialog_title_delete_log),
            message = stringResource(Strings.dialog_message_delete_log),
            onConfirm = {
                val item = selected
                showDeleteDialog = false
                if (item != null) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { item.file.delete() }.getOrDefault(false)
                        }
                        if (ok) {
                            onToast(Strings.toast_log_deleted.strOr(context))
                            selected = null
                            selectedContent = null
                            refresh()
                        } else {
                            onToast(Strings.toast_delete_failed.strOr(context))
                        }
                    }
                }
            },
            onDismiss = { showDeleteDialog = false },
            isDanger = true
        )
    }
}

@Composable
private fun LogFileList(
    modifier: Modifier,
    files: List<LogFileItem>,
    dateFormat: SimpleDateFormat,
    onOpen: (LogFileItem) -> Unit,
) {
    if (files.isEmpty()) {
        Column(
            modifier = modifier.padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PRootLogSectionSurface(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = stringResource(Strings.proot_logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(files, key = { it.file.absolutePath }) { item ->
            PRootLogSectionSurface(
                onClick = { onOpen(item) }
            ) {
                Text(
                    text = item.file.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateFormat.format(Date(item.lastModified)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PRootLogMetaBadge(text = formatSize(item.sizeBytes))
                }
            }
        }
    }
}

@Composable
private fun LogFileDetail(
    modifier: Modifier,
    item: LogFileItem,
    content: String?,
    dateFormat: SimpleDateFormat,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PRootLogSectionSurface {
            Text(
                text = item.file.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormat.format(Date(item.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PRootLogMetaBadge(text = formatSize(item.sizeBytes))
            }
        }

        TinaOverlayPanelSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            if (content == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(Strings.loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PRootLogHeaderActions(
    selected: LogFileItem?,
    selectedContent: String?,
    onRefresh: () -> Unit,
    onShareFile: (File) -> Unit,
    onCopyText: (String) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected == null) {
            PRootLogActionButton(
                onClick = onRefresh,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(Strings.btn_refresh),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            PRootLogActionButton(
                onClick = { onShareFile(selected.file) },
                modifier = Modifier.size(36.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(Strings.share_title_export_log),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val copyEnabled = selectedContent != null
            PRootLogActionButton(
                onClick = { selectedContent?.let(onCopyText) },
                modifier = Modifier.size(36.dp),
                enabled = copyEnabled,
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(Strings.content_desc_copy),
                    tint = if (copyEnabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                    }
                )
            }

            PRootLogActionButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(Strings.content_desc_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PRootLogActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = 36.dp,
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentPadding = contentPadding,
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun PRootLogSectionSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val body: @Composable () -> Unit = {
        TinaDialogContentColumn(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = body
        )
    } else {
        TinaOverlayPanelSurface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = body
        )
    }
}

@Composable
private fun PRootLogMetaBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private fun formatSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    if (b < 1024) return "${b}B"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1fGB", gb)
}

private fun readFileTailUtf8(file: File, maxBytes: Long): String {
    val safeMaxBytes = maxBytes.coerceAtLeast(1L)
    val len = runCatching { file.length() }.getOrDefault(0L)
    if (len <= safeMaxBytes) {
        return runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { it.message ?: AppStrings.get(Strings.proot_log_read_failed) }
    }

    val header = AppStrings.get(
        Strings.proot_log_tail_header,
        file.name,
        len,
        safeMaxBytes
    )

    return buildString {
        append(header)
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek((len - safeMaxBytes).coerceAtLeast(0L))
                val buf = ByteArray(16 * 1024)
                var remaining = safeMaxBytes
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    append(String(buf, 0, read, Charsets.UTF_8))
                    remaining -= read.toLong()
                }
            }
        }.onFailure { append(it.message ?: AppStrings.get(Strings.proot_log_read_failed)) }
    }
}
