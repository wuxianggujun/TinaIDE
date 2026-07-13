package com.wuxianggujun.tinaide.ui

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.terminal.SessionStatus
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.core.terminal.TerminalSessionInfo
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.terminal.ui.TerminalExtraKeys
import com.wuxianggujun.tinaide.terminal.ui.TerminalViewWrapper
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialogScaffold
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaOverlayPanelSurface
import com.wuxianggujun.tinaide.ui.compose.components.TinaPanelSegmentButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
import com.wuxianggujun.tinaide.ui.terminal.TerminalSessionInfoTabBar
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import org.koin.androidx.viewmodel.ext.android.viewModel as koinViewModel

/**
 * 基于 Termux 的终端 Activity
 *
 * 使用 Termux 的 terminal-view 和 terminal-emulator 模块
 */
class TerminalActivity : ComponentActivity() {

    companion object {
        /** Intent extra key: 工作目录路径 */
        const val EXTRA_WORK_DIR = "work_dir"

        /** Intent extra key: 项目路径（用于状态持久化） */
        const val EXTRA_PROJECT_PATH = "project_path"

        /** Intent extra key: 启动后自动执行的命令 */
        const val EXTRA_COMMAND = "command"

        /** Intent extra key: 是否强制新建一个会话 */
        const val EXTRA_NEW_SESSION = "new_session"

        /** Intent extra key: 终端后端类型（"host" 或 "proot"） */
        const val EXTRA_BACKEND = "backend"

        private const val STATE_RUN_SESSION_ID = "state_run_session_id"
        private const val STATE_RUN_COMMAND_DISPATCHED = "state_run_command_dispatched"
    }

    private val viewModel: MultiTerminalViewModel by koinViewModel()
    private var workDir: String = "/"
    private var projectPath: String = "/"
    private var isRestoringState: Boolean = false
    private var initialCommand: String? = null
    private var requestNewSession: Boolean = false
    private var initialBackend: TerminalBackend = TerminalBackend.HOST
    private var isRunMode: Boolean = false
    private var runSessionId: String? = null
    private var runCommandDispatched: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme()
        super.onCreate(savedInstanceState)

        // 启用边到边显示
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 获取传入的工作目录
        workDir = intent.getStringExtra(EXTRA_WORK_DIR) ?: "/"
        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH) ?: workDir
        initialCommand = intent.getStringExtra(EXTRA_COMMAND)
        runSessionId = savedInstanceState?.getString(STATE_RUN_SESSION_ID)
        runCommandDispatched = savedInstanceState?.getBoolean(STATE_RUN_COMMAND_DISPATCHED) == true
        requestNewSession = intent.getBooleanExtra(EXTRA_NEW_SESSION, false)
        initialBackend = try {
            intent.getStringExtra(EXTRA_BACKEND)?.let { TerminalBackend.valueOf(it.uppercase()) }
        } catch (_: Exception) {
            null
        } ?: TerminalBackend.HOST

        viewModel.setProjectPath(projectPath)

        isRunMode = !initialCommand.isNullOrBlank()

        // 进程回收后 TerminalSession 无法恢复；绝不能因为 savedInstanceState 中仍有原命令
        // 而重复执行一次有副作用的用户程序。直接返回上层，由用户明确再次点击运行。
        if (
            isRunMode &&
            runCommandDispatched &&
            viewModel.sessions.value.none { it.id == runSessionId }
        ) {
            finish()
            return
        }

        // 尝试恢复之前的终端状态
        // 如果没有保存的状态，restoreState 会自动创建一个默认终端
        // 不要依赖 savedInstanceState：进程被系统回收后重建时 savedInstanceState 也可能不为 null，
        // 此时 ViewModel 是全新的，仍应从项目状态文件恢复。
        //
        // Run 模式跳过项目级状态恢复：命令以 `exit` 结束，不应让一次性运行污染用户保存的终端会话快照；
        // 已有内存会话时才请求追加新会话，避免与空列表场景的默认创建逻辑叠加出两个会话。
        if (isRunMode) {
            requestNewSession = !runCommandDispatched && viewModel.sessions.value.isNotEmpty()
        } else if (viewModel.sessions.value.isEmpty()) {
            isRestoringState = true
            viewModel.restoreState(projectPath, workDir)
        }

        setContent {
            TinaIDETheme {
                TerminalScreen(
                    viewModel = viewModel,
                    workDir = workDir,
                    projectPath = projectPath,
                    onBack = { closeRunOrFinish() },
                    isRestoringState = isRestoringState,
                    initialCommand = initialCommand,
                    requestNewSession = requestNewSession,
                    isRunMode = isRunMode,
                    defaultBackend = initialBackend,
                    initialRunSessionId = runSessionId,
                    commandAlreadyDispatched = runCommandDispatched,
                    onRunCommandDispatched = { id ->
                        runSessionId = id
                        runCommandDispatched = true
                    }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Run 模式不保存会话快照：会话是一次性的，`exit` 后 Activity 自动 finish，
        // 无需把已退出的会话写入项目状态文件。
        if (!isRunMode) {
            viewModel.saveState()
        }
    }

    override fun onDestroy() {
        // 兜底：系统回收或异常路径下，保证 Run 模式挂着的 `cat > /dev/null` 不会残留。
        // 正常 finishRun() 路径已经 closeSession，这里只处理异常。
        if (isRunMode && !isChangingConfigurations) {
            closeRunSession()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_RUN_SESSION_ID, runSessionId)
        outState.putBoolean(STATE_RUN_COMMAND_DISPATCHED, runCommandDispatched)
        super.onSaveInstanceState(outState)
    }

    private fun closeRunOrFinish() {
        if (isRunMode) closeRunSession()
        finish()
    }

    private fun closeRunSession() {
        val id = runSessionId ?: return
        runSessionId = null
        viewModel.markSuppressExitNotice(id)
        viewModel.closeSession(id, workDir, createReplacement = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(
    viewModel: MultiTerminalViewModel,
    workDir: String = "/",
    projectPath: String = "/",
    onBack: () -> Unit,
    isRestoringState: Boolean = false,
    initialCommand: String? = null,
    requestNewSession: Boolean = false,
    isRunMode: Boolean = false,
    defaultBackend: TerminalBackend = TerminalBackend.HOST,
    initialRunSessionId: String? = null,
    commandAlreadyDispatched: Boolean = false,
    onRunCommandDispatched: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val frameId by viewModel.frameId.collectAsStateWithLifecycle()

    val activeSession by remember {
        derivedStateOf {
            sessions.find { it.id == activeSessionId }
        }
    }

    var lastRows by remember { mutableIntStateOf(24) }
    var lastCols by remember { mutableIntStateOf(80) }
    var pendingCommand by remember(initialCommand, commandAlreadyDispatched) {
        mutableStateOf(initialCommand?.takeIf { !commandAlreadyDispatched && it.isNotBlank() })
    }
    var runSessionId by remember(initialCommand, initialRunSessionId) {
        mutableStateOf(initialRunSessionId)
    }
    var initialSessionRequested by remember { mutableStateOf(false) }

    // 首次进入页面时创建会话（仅在没有通过 restoreState 恢复的情况下）
    // restoreState 会自动处理会话创建，所以这里只在没有会话且不是恢复状态时创建
    LaunchedEffect(sessions, isRestoringState, initialSessionRequested) {
        if (sessions.isEmpty() && !isRestoringState && !initialSessionRequested) {
            // Adapter 的 sessions 映射是异步的。必须先上闩，再创建会话；否则 pendingCommand
            // 先被消费而会话列表尚未刷新时，Compose 可能再次进入这里并创建重复会话。
            initialSessionRequested = true
            val command = pendingCommand
            val id = viewModel.createSession(
                workDir = workDir,
                rows = lastRows,
                cols = lastCols,
                backend = defaultBackend,
                initialCommand = command,
            )
            if (command != null) {
                runSessionId = id
                onRunCommandDispatched(id)
                pendingCommand = null
            }
        }
    }

    // 如果要求新建会话：在已有会话恢复完成后追加创建一个
    var pendingNewSession by remember(requestNewSession) { mutableStateOf(requestNewSession) }
    LaunchedEffect(sessions.size, pendingNewSession) {
        if (!pendingNewSession) return@LaunchedEffect
        if (sessions.isEmpty()) return@LaunchedEffect
        // 与上面的首次创建同理，先消费请求再触发同步状态更新，避免 effect 重入。
        pendingNewSession = false
        val command = pendingCommand
        val id = viewModel.createSession(
            workDir = workDir,
            rows = lastRows,
            cols = lastCols,
            backend = defaultBackend,
            initialCommand = command,
        )
        if (command != null) {
            runSessionId = id
            onRunCommandDispatched(id)
            pendingCommand = null
        }
    }

    // Run 模式：shell 收到私有 OSC（tina-run-end;<code>）后会把 runExitCode 写入会话状态。
    // 此时程序已结束，shell 正阻塞在 `cat > /dev/null`——不再有 TTY 回显风险；
    // Activity 在下面插入一个隐藏 BasicTextField 抢焦，抓 Enter/IME Done 后 finish()，
    // 强杀 shell 时 `suppressExitNotice` 会阻止 Termux 追加 `[Process completed - press Enter]`。
    val runSession = if (isRunMode) sessions.find { it.id == runSessionId } else null
    val runExitCode = runSession?.runExitCode
    val runCompleted = isRunMode && runExitCode != null

    var ctrlEnabled by remember { mutableStateOf(false) }
    var altEnabled by remember { mutableStateOf(false) }
    val currentTheme by viewModel.currentTheme.collectAsStateWithLifecycle()
    val fontSizeSp by viewModel.fontSizeSp.collectAsStateWithLifecycle()
    val fontName by viewModel.fontName.collectAsStateWithLifecycle()
    val customFontPath by viewModel.customFontPath.collectAsStateWithLifecycle()
    val cursorBlinkEnabled by viewModel.cursorBlinkEnabled.collectAsStateWithLifecycle()
    val cursorBlinkRate by viewModel.cursorBlinkRate.collectAsStateWithLifecycle()
    val terminalTypeface = remember(fontName, customFontPath) { viewModel.getTerminalTypeface() }

    var showTabListDialog by remember { mutableStateOf(false) }
    var showNewTabBackendDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .windowInsetsPadding(WindowInsets.statusBars)
            .consumeWindowInsets(WindowInsets.statusBars)
    ) {
        // 顶部栏
        TinaTopBar(
            title = if (isRunMode) stringResource(Strings.title_run_output) else stringResource(Strings.title_terminal),
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f),
            onNavigateBack = onBack,
            actions = {
                if (!isRunMode) {
                    // 标签列表按钮（运行模式下隐藏）
                    Box {
                        IconButton(onClick = { showTabListDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(Strings.terminal_tab_list_title, sessions.size),
                                tint = Color.White
                            )
                        }
                        if (sessions.size > 1) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4FC3F7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (sessions.size > 9) "9+" else sessions.size.toString(),
                                    color = Color.Black,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    // 设置按钮（运行模式下隐藏）
                    IconButton(onClick = {
                        SettingsActivity.start(context, SettingsRoute.Terminal)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(Strings.content_desc_terminal_settings),
                            tint = Color.White
                        )
                    }
                }
            },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White
        )

        // 标签栏（运行模式下隐藏）
        if (!isRunMode) {
            TerminalSessionInfoTabBar(
                sessions = sessions,
                activeSessionId = activeSessionId,
                onTabClick = { viewModel.switchSession(it) },
                onTabClose = { viewModel.closeSession(it, workDir) },
                onTabRename = { sessionId, newTitle -> viewModel.renameSession(sessionId, newTitle) },
                onNewTab = { viewModel.createSession(workDir = workDir, rows = lastRows, cols = lastCols, backend = TerminalBackend.HOST) },
                onNewTabLongClick = { showNewTabBackendDialog = true },
                modifier = Modifier.zIndex(2f)
            )
        }

        // 内容区域
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .consumeWindowInsets(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
                .consumeWindowInsets(WindowInsets.ime)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 终端视图
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    activeSession?.let { sessionInfo ->
                        val internalSession = viewModel.getInternalSession(sessionInfo.id) as? com.termux.terminal.TerminalSession
                        TerminalViewWrapper(
                            session = internalSession,
                            frameId = frameId.toLong(),
                            ctrlEnabled = ctrlEnabled,
                            altEnabled = altEnabled,
                            onSingleTap = {
                                // 点击时可以显示键盘
                            },
                            onScale = { it },
                            onKeyDown = { keyCode, event, _ ->
                                if (!runCompleted) {
                                    false
                                } else if (
                                    event.action == AndroidKeyEvent.ACTION_DOWN &&
                                    (keyCode == AndroidKeyEvent.KEYCODE_ENTER || keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
                                ) {
                                    onBack()
                                    true
                                } else {
                                    !event.isSystem()
                                }
                            },
                            onCodePoint = { codePoint, _, _ ->
                                if (!runCompleted) {
                                    false
                                } else {
                                    if (codePoint == '\n'.code || codePoint == '\r'.code) {
                                        onBack()
                                    }
                                    true
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            fontSizeSp = fontSizeSp,
                            typeface = terminalTypeface,
                            onFontSizeChange = { viewModel.setFontSize(it) },
                            cursorBlinkEnabled = cursorBlinkEnabled,
                            cursorBlinkRate = cursorBlinkRate
                        )
                    }
                }

                // 快捷键栏
                TerminalExtraKeys(
                    ctrlEnabled = ctrlEnabled,
                    altEnabled = altEnabled,
                    onCtrlToggle = { ctrlEnabled = !ctrlEnabled },
                    onAltToggle = { altEnabled = !altEnabled },
                    onKey = { key ->
                        if (!runCompleted) {
                            viewModel.sendText(key)
                        } else if (key.contains('\n') || key.contains('\r')) {
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 加载/错误覆盖层
            activeSession?.let { session ->
                val showOverlay = when (session.status) {
                    SessionStatus.STARTING -> true
                    SessionStatus.ERROR -> true
                    SessionStatus.RUNNING -> false
                    SessionStatus.EXITED -> false
                }

                if (showOverlay) {
                    TerminalLoadingOverlay(
                        session = session,
                        onRetry = {
                            viewModel.restartSession(session.id, workDir)
                        },
                        onGoToSettings = {
                            SettingsActivity.start(context, SettingsRoute.Terminal)
                        }
                    )
                }
            }

            // Run 模式：程序已结束 → 插入一个 1dp 透明 BasicTextField 抢焦，
            // Enter（软键盘 Done / 物理键 Enter）都会触发 onBack()，整个过程不把任何字符投递到 shell。
            if (isRunMode && runExitCode != null) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(runExitCode) {
                    focusRequester.requestFocus()
                }
                BasicTextField(
                    value = "",
                    onValueChange = { /* 吞掉任意输入 */ },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onBack() }),
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                onBack()
                                true
                            } else {
                                false
                            }
                        }
                )
            }
        }
    }

    // 新建标签后端选择弹窗
    if (showNewTabBackendDialog) {
        NewTabBackendDialog(
            onSelect = { backend ->
                viewModel.createSession(workDir = workDir, rows = lastRows, cols = lastCols, backend = backend)
                showNewTabBackendDialog = false
            },
            onDismiss = { showNewTabBackendDialog = false }
        )
    }

    // 标签列表弹窗（运行模式下不显示）
    if (showTabListDialog && !isRunMode) {
        TerminalTabListDialog(
            sessions = sessions,
            activeSessionId = activeSessionId,
            onTabClick = { sessionId ->
                viewModel.switchSession(sessionId)
                showTabListDialog = false
            },
            onTabClose = { sessionId ->
                viewModel.closeSession(sessionId, workDir)
            },
            onDismiss = { showTabListDialog = false }
        )
    }
}

/**
 * 标签列表弹窗
 */
@Composable
private fun TerminalDialogHeader(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            subtitle?.takeIf(String::isNotBlank)?.let { value ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = value,
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        TerminalDialogActionButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Strings.btn_close),
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TerminalDialogActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier,
        minHeight = 32.dp,
        color = Color.White.copy(alpha = 0.08f),
        content = content
    )
}

@Composable
private fun TerminalDialogSectionSurface(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val background = if (selected) Color(0xFF313946) else Color(0xFF252525)
    val borderColor = if (selected) {
        Color(0xFF4FC3F7).copy(alpha = 0.36f)
    } else {
        Color.White.copy(alpha = 0.05f)
    }

    TinaOverlayPanelSurface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        containerColor = background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        TinaDialogContentColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun TerminalDialogBadge(
    text: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
    containerColor: Color = contentColor.copy(alpha = 0.16f)
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun TerminalBackendOptionCard(
    badgeText: String,
    badgeColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    TerminalDialogSectionSurface(
        onClick = onClick,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TerminalDialogBadge(text = badgeText, contentColor = badgeColor)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TerminalTabListDialog(
    sessions: List<TerminalSessionInfo>,
    activeSessionId: String?,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    TinaCustomDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFF2D2D2D),
        tonalElevation = 10.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        TinaCustomDialogScaffold(
            header = {
                TerminalDialogHeader(
                    title = stringResource(Strings.terminal_tab_list_title, sessions.size),
                    subtitle = activeSessionId?.let { stringResource(Strings.terminal_current_tab) },
                    onDismiss = onDismiss
                )
            }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    TerminalTabListItem(
                        session = session,
                        isActive = session.id == activeSessionId,
                        onClick = { onTabClick(session.id) },
                        onClose = if (sessions.size > 1) {
                            { onTabClose(session.id) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

/**
 * 标签列表项
 */
@Composable
private fun TerminalTabListItem(
    session: TerminalSessionInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?
) {
    val statusColor = when (session.status) {
        SessionStatus.STARTING -> Color(0xFFFFAA00)
        SessionStatus.RUNNING -> Color(0xFF00AA00)
        SessionStatus.EXITED -> Color(0xFF888888)
        SessionStatus.ERROR -> Color(0xFFFF4444)
    }

    TerminalDialogSectionSurface(
        selected = isActive,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium
                )
                val backendLabel = when (session.backend) {
                    TerminalBackend.HOST -> stringResource(Strings.terminal_backend_host_label)
                    TerminalBackend.PROOT -> stringResource(Strings.terminal_backend_proot_label)
                }
                val statusLabel = when (session.status) {
                    SessionStatus.STARTING -> stringResource(Strings.terminal_status_starting)
                    SessionStatus.RUNNING -> stringResource(Strings.terminal_status_running)
                    SessionStatus.EXITED -> stringResource(Strings.terminal_status_exited)
                    SessionStatus.ERROR -> stringResource(Strings.terminal_status_error)
                }
                Text(
                    text = "$backendLabel · $statusLabel",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (isActive) {
                TerminalDialogBadge(
                    text = stringResource(Strings.terminal_current_tab),
                    contentColor = Color(0xFF4FC3F7),
                    containerColor = Color(0xFF4FC3F7).copy(alpha = 0.18f)
                )
            }

            onClose?.let {
                TerminalDialogActionButton(
                    onClick = it,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Strings.terminal_close_tab),
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 新建标签后端选择弹窗
 */
@Composable
private fun NewTabBackendDialog(
    onSelect: (TerminalBackend) -> Unit,
    onDismiss: () -> Unit
) {
    TinaCustomDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFF2D2D2D),
        tonalElevation = 10.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        TinaCustomDialogScaffold(
            header = {
                TerminalDialogHeader(
                    title = stringResource(Strings.terminal_select_backend_title),
                    onDismiss = onDismiss
                )
            }
        ) {
            TerminalDialogSectionSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                TerminalBackendOptionCard(
                    badgeText = "H",
                    badgeColor = Color(0xFF81C784),
                    title = stringResource(Strings.terminal_backend_host_label),
                    description = stringResource(Strings.terminal_backend_host_desc),
                    onClick = { onSelect(TerminalBackend.HOST) }
                )
                TerminalBackendOptionCard(
                    badgeText = "P",
                    badgeColor = Color(0xFFFFB74D),
                    title = stringResource(Strings.terminal_backend_proot_label),
                    description = stringResource(Strings.terminal_backend_proot_desc),
                    onClick = { onSelect(TerminalBackend.PROOT) }
                )
            }
        }
    }
}

@Composable
private fun TerminalLoadingOverlay(
    session: TerminalSessionInfo,
    onRetry: () -> Unit = {},
    onGoToSettings: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E)),
        contentAlignment = Alignment.Center
    ) {
        TinaOverlayPanelSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xFF252525),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            TinaDialogContentColumn(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (session.status) {
                    SessionStatus.STARTING -> {
                        TerminalDialogSectionSurface(
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                TerminalDialogBadge(
                                    text = stringResource(Strings.status_terminal_starting),
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 4.dp
                                )
                            }
                        }
                    }

                    SessionStatus.ERROR -> {
                        TerminalDialogSectionSurface(
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                TerminalDialogBadge(
                                    text = stringResource(Strings.terminal_status_error),
                                    contentColor = Color(0xFFFF6B6B),
                                    containerColor = Color(0xFFFF6B6B).copy(alpha = 0.14f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6B6B),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = stringResource(Strings.status_terminal_init_failed),
                                    color = Color(0xFFFF6B6B),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = session.errorMessage ?: stringResource(Strings.error_unknown_with_message),
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        TerminalDialogSectionSurface(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            TinaDialogActionRow(horizontalArrangement = Arrangement.Center) {
                                TinaOutlinedButton(
                                    text = stringResource(Strings.btn_retry),
                                    onClick = onRetry,
                                    icon = rememberVectorPainter(Icons.Default.Refresh)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                TinaPrimaryButton(
                                    text = stringResource(Strings.btn_go_to_settings),
                                    onClick = onGoToSettings,
                                    icon = rememberVectorPainter(Icons.Default.Settings)
                                )
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}
