package com.wuxianggujun.tinaide.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.proot.InstallLogManager
import com.wuxianggujun.tinaide.storage.ExternalFileIntents
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDangerItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.workspace.components.rememberWorkspacePainter
import com.wuxianggujun.tinaide.ui.workspace.log.ComposeInstallLogPanel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 安装日志查看界面
 *
 * 显示 PRoot 环境安装过程中的详细日志
 */
class InstallLogActivity :
    ComponentActivity(),
    KoinComponent {

    private val installLogManager: InstallLogManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置沉浸式状态栏（浅色 surface 背景使用深色图标）
        immersionBar {
            transparentStatusBar()
            statusBarDarkFont(true)
            navigationBarColor(android.R.color.transparent)
            navigationBarDarkIcon(true)
        }

        setContent {
            TinaIDETheme {
                InstallLogScreen(
                    installLogManager = installLogManager,
                    onBack = { finish() },
                    onCopyLog = { copyLogToClipboard() },
                    onExportLog = { exportLogFile() }
                )
            }
        }
    }

    private fun copyLogToClipboard() {
        val logText = installLogManager.getFullLogText()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TinaIDE Install Log", logText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, Strings.toast_log_copied.strOr(this), Toast.LENGTH_SHORT).show()
    }

    private fun exportLogFile() {
        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "install_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
            val file = installLogManager.exportLog(this@InstallLogActivity, fileName)

            withContext(Dispatchers.Main) {
                if (file != null) {
                    // 使用 FileProvider 分享文件
                    try {
                        val uri = ExternalFileIntents.getShareableUri(this@InstallLogActivity, file)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, Strings.share_title_export_log.strOr(this@InstallLogActivity)))
                    } catch (e: Exception) {
                        Toast.makeText(this@InstallLogActivity, Strings.toast_log_saved.strOr(this@InstallLogActivity, file.absolutePath), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@InstallLogActivity, Strings.toast_export_failed.strOr(this@InstallLogActivity), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallLogScreen(
    installLogManager: InstallLogManager,
    onBack: () -> Unit,
    onCopyLog: () -> Unit,
    onExportLog: () -> Unit
) {
    val entries by installLogManager.logs.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter { entry ->
                entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.tag?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            focusRequester.requestFocus()
        }
    }

    val fileTextPainter = rememberWorkspacePainter(Drawables.ic_file_text)
    val uploadPainter = rememberWorkspacePainter(Drawables.ic_upload)

    fun closeSearch() {
        isSearchMode = false
        searchQuery = ""
    }

    TinaBackHandlers(
        tinaBackAction(enabled = isSearchMode, onBack = ::closeSearch)
    )

    Scaffold(
        topBar = {
            TinaTopBar(
                title = stringResource(Strings.title_run_log),
                onNavigateBack = onBack,
                actions = {
                    IconButton(onClick = { isSearchMode = !isSearchMode }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(Strings.action_search),
                            tint = if (isSearchMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(Strings.action_more)
                            )
                        }
                        TinaDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            TinaDropdownMenuDangerItem(
                                text = { Text(stringResource(Strings.action_clear_log)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    installLogManager.clear()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider()
                TinaDialogActionRow(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TinaOutlinedButton(
                        text = stringResource(Strings.btn_copy_log),
                        onClick = onCopyLog,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        icon = fileTextPainter
                    )
                    TinaPrimaryButton(
                        text = stringResource(Strings.btn_export_file),
                        onClick = onExportLog,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        icon = uploadPainter
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isSearchMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(Strings.hint_search_log)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(Strings.action_clear)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (searchQuery.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            Strings.search_result_count,
                            filteredEntries.size,
                            entries.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            if (filteredEntries.isEmpty()) {
                InstallLogEmptyState(
                    modifier = Modifier.fillMaxSize(),
                    isSearchMode = isSearchMode,
                    hasQuery = searchQuery.isNotEmpty()
                )
            } else {
                ComposeInstallLogPanel(
                    entries = filteredEntries,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    backgroundColor = Color.Transparent,
                    fontSizeSp = 13f,
                    autoScroll = !isSearchMode
                )
            }
        }
    }
}

@Composable
private fun InstallLogEmptyState(
    modifier: Modifier = Modifier,
    isSearchMode: Boolean,
    hasQuery: Boolean
) {
    Box(
        modifier = modifier.padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = rememberWorkspacePainter(Drawables.ic_file_text),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = if (isSearchMode && hasQuery) {
                    stringResource(Strings.empty_no_search_results)
                } else {
                    stringResource(Strings.empty_no_logs)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
