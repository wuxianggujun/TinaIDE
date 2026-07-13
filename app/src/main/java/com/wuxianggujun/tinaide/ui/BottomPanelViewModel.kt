package com.wuxianggujun.tinaide.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.compile.BuildLogEntry
import com.wuxianggujun.tinaide.core.compile.BuildLogLevel
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.compose.components.BottomPanelTab
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 底部面板 ViewModel
 *
 * 职责：
 * - 管理构建日志、诊断信息
 * - 管理底部面板选中标签
 * - 监听 OutputManager
 *
 * 重构说明：
 * - 移除 terminalFullScreen 状态（高度控制由 BottomPanelDragState 统一管理）
 * - 简化状态管理，仅保留必要的业务状态
 */
class BottomPanelViewModel(
    application: Application,
    private val outputManager: IOutputManager,
) : AndroidViewModel(application) {

    // ============ UI 状态 ============

    private val _buildLogs = MutableStateFlow<List<BuildLogEntry>>(emptyList())
    val buildLogs: StateFlow<List<BuildLogEntry>> = _buildLogs.asStateFlow()

    private val _buildLogCount = MutableStateFlow(0)
    val buildLogCount: StateFlow<Int> = _buildLogCount.asStateFlow()

    private val _runOutputLogs = MutableStateFlow<List<BuildLogEntry>>(emptyList())
    val runOutputLogs: StateFlow<List<BuildLogEntry>> = _runOutputLogs.asStateFlow()

    private val _runOutputCount = MutableStateFlow(0)
    val runOutputCount: StateFlow<Int> = _runOutputCount.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    val diagnostics: StateFlow<List<Diagnostic>> = _diagnostics.asStateFlow()

    private val _selectedBottomTab = MutableStateFlow(BottomPanelTab.BUILD_LOG)
    val selectedBottomTab: StateFlow<BottomPanelTab> = _selectedBottomTab.asStateFlow()

    private val pendingOutputLock = Any()
    private val pendingBuildOutput = StringBuilder()
    private val pendingRunOutput = StringBuilder()
    private val outputFlushScheduled = AtomicBoolean(false)

    // ============ 监听器 ============

    /**
     * 构建日志监听器
     *
     * 逻辑：
     * - 过滤 BUILD 通道
     * - 移除末尾换行符
     * - 检测日志级别（ERROR/WARN/SUCCESS/INFO/DEBUG）
     * - 添加到构建日志列表
     */
    private val buildLogListener = object : IOutputManager.OutputListener {
        override fun onOutputAppended(text: String, channel: IOutputManager.OutputChannel) {
            synchronized(pendingOutputLock) {
                when (channel) {
                    IOutputManager.OutputChannel.BUILD -> appendPendingOutput(pendingBuildOutput, text)
                    IOutputManager.OutputChannel.RUN -> appendPendingOutput(pendingRunOutput, text)
                }
            }
            scheduleOutputFlush()
        }

        override fun onOutputCleared(channel: IOutputManager.OutputChannel) {
            when (channel) {
                IOutputManager.OutputChannel.BUILD -> {
                    synchronized(pendingOutputLock) { pendingBuildOutput.setLength(0) }
                    _buildLogs.value = emptyList()
                    _buildLogCount.value = 0
                }
                IOutputManager.OutputChannel.RUN -> {
                    synchronized(pendingOutputLock) { pendingRunOutput.setLength(0) }
                    _runOutputLogs.value = emptyList()
                    _runOutputCount.value = 0
                }
            }
        }
    }

    // ============ 初始化 ============

    init {
        restoreBuildLogsFromBuffer()
        restoreRunOutputFromBuffer()
        // 注册监听器
        outputManager.addOutputListener(buildLogListener)
    }

    // ============ 公共方法 ============

    /**
     * 设置选中的底部面板标签
     */
    fun setSelectedTab(tab: BottomPanelTab) {
        _selectedBottomTab.value = tab
    }

    /**
     * 清空构建日志
     */
    fun clearBuildLogs() {
        outputManager.clearOutput(IOutputManager.OutputChannel.BUILD)
    }

    /**
     * 清空运行输出
     */
    fun clearRunOutput() {
        outputManager.clearOutput(IOutputManager.OutputChannel.RUN)
    }

    /**
     * 替换指定文件的诊断信息
     *
     * 逻辑：
     * - 移除旧的诊断
     * - 添加新的诊断
     * - 按文件名→行号→列号→严重性→消息排序
     */
    fun replaceDiagnosticsForFile(fileUri: String, fileDiagnostics: List<Diagnostic>) {
        val currentDiagnostics = _diagnostics.value.toMutableList()

        // 移除该文件的旧诊断
        currentDiagnostics.removeAll { it.fileUri == fileUri }

        // 添加新诊断
        currentDiagnostics.addAll(fileDiagnostics)

        // 排序
        currentDiagnostics.sortWith(
            compareBy<Diagnostic>(
                { it.fileName },
                { it.line },
                { it.column },
                { it.severity.ordinal },
                { it.message }
            )
        )

        _diagnostics.value = currentDiagnostics
    }

    // ============ 生命周期管理 ============

    override fun onCleared() {
        super.onCleared()

        // 清理监听器
        outputManager.removeOutputListener(buildLogListener)
    }

    private fun scheduleOutputFlush() {
        if (!outputFlushScheduled.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(OUTPUT_FLUSH_INTERVAL_MS)
            val (buildText, runText) = synchronized(pendingOutputLock) {
                val pending = pendingBuildOutput.toString() to pendingRunOutput.toString()
                pendingBuildOutput.setLength(0)
                pendingRunOutput.setLength(0)
                outputFlushScheduled.set(false)
                pending
            }
            appendParsedOutput(buildText, IOutputManager.OutputChannel.BUILD)
            appendParsedOutput(runText, IOutputManager.OutputChannel.RUN)
        }
    }

    private fun appendPendingOutput(buffer: StringBuilder, text: String) {
        buffer.append(text)
        if (buffer.length > MAX_PENDING_OUTPUT_CHARS) {
            // 仅限制尚未渲染的 UI 队列；OutputManager 的完整有界缓冲不受影响。
            buffer.delete(0, buffer.length - MAX_PENDING_OUTPUT_CHARS / 2)
        }
    }

    private fun appendParsedOutput(text: String, channel: IOutputManager.OutputChannel) {
        if (text.isBlank()) return
        val entries = parseOutputEntries(text)
        if (entries.isEmpty()) return
        when (channel) {
            IOutputManager.OutputChannel.BUILD -> _buildLogs.update { current ->
                retainLatestLogs(current, entries, MAX_UI_LOG_ENTRIES).also {
                    _buildLogCount.value = it.size
                }
            }
            IOutputManager.OutputChannel.RUN -> _runOutputLogs.update { current ->
                retainLatestLogs(current, entries, MAX_UI_LOG_ENTRIES).also {
                    _runOutputCount.value = it.size
                }
            }
        }
    }

    private fun restoreBuildLogsFromBuffer() {
        val buffered = outputManager.getOutput(IOutputManager.OutputChannel.BUILD)
        if (buffered.isBlank()) return
        val entries = retainLatestLogs(emptyList(), parseOutputEntries(buffered), MAX_UI_LOG_ENTRIES)
        _buildLogs.value = entries
        _buildLogCount.value = entries.size
    }

    private fun restoreRunOutputFromBuffer() {
        val buffered = outputManager.getOutput(IOutputManager.OutputChannel.RUN)
        if (buffered.isBlank()) return
        val entries = retainLatestLogs(emptyList(), parseOutputEntries(buffered), MAX_UI_LOG_ENTRIES)
        _runOutputLogs.value = entries
        _runOutputCount.value = entries.size
    }

    private fun parseOutputEntries(text: String): List<BuildLogEntry> = text
        .lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .map { line ->
            val level = BuildLogLevel.detect(line)
            BuildLogEntry.create(level, line)
        }
        .toList()

    private companion object {
        private const val OUTPUT_FLUSH_INTERVAL_MS = 32L
        private const val MAX_UI_LOG_ENTRIES = 3_000
        private const val MAX_PENDING_OUTPUT_CHARS = 512 * 1_024
    }
}

internal fun <T> retainLatestLogs(current: List<T>, incoming: List<T>, limit: Int): List<T> {
    require(limit > 0) { "limit must be positive" }
    if (incoming.size >= limit) return incoming.takeLast(limit)
    val keepFromCurrent = (limit - incoming.size).coerceAtMost(current.size)
    return buildList(keepFromCurrent + incoming.size) {
        addAll(current.takeLast(keepFromCurrent))
        addAll(incoming)
    }
}
