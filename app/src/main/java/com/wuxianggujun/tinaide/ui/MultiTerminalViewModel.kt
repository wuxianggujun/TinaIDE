package com.wuxianggujun.tinaide.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wuxianggujun.tinaide.core.terminal.ITerminalSessionManager
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.core.terminal.TerminalSessionInfo
import com.wuxianggujun.tinaide.terminal.preferences.TerminalPreferences
import com.wuxianggujun.tinaide.terminal.theme.TerminalTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * 多终端 ViewModel
 *
 * 封装 TerminalSessionManager，提供给 UI 层使用。
 * 基于 Termux 的 terminal-emulator 和 terminal-view 模块。
 */
class MultiTerminalViewModel(
    application: Application,
    private val sessionManager: ITerminalSessionManager
) : AndroidViewModel(application) {

    private val preferences = TerminalPreferences.get(application)

    init {
        // 初始化会话管理器
        sessionManager.initialize()
    }

    /** 所有会话列表 */
    val sessions: StateFlow<List<TerminalSessionInfo>> = sessionManager.sessions

    /** 当前活动会话 ID */
    val activeSessionId: StateFlow<String?> = sessionManager.activeSessionId

    /** 帧 ID，用于触发 UI 重绘 */
    val frameId: StateFlow<Int> = sessionManager.frameId

    /** 当前主题名称 */
    val currentTheme: StateFlow<String> = preferences.themeNameFlow

    /** 字体大小（sp） */
    val fontSizeSp: StateFlow<Float> = preferences.fontSizeFlow

    /** 字体类型（builtin/system/custom） */
    val fontName: StateFlow<String> = preferences.fontNameFlow

    /** 自定义字体路径 */
    val customFontPath: StateFlow<String> = preferences.customFontPathFlow

    /** 光标闪烁启用状态 */
    val cursorBlinkEnabled: StateFlow<Boolean> = preferences.cursorBlinkEnabledFlow

    /** 光标闪烁率（毫秒） */
    val cursorBlinkRate: StateFlow<Int> = preferences.cursorBlinkRateFlow

    /** 获取当前活动会话 */
    fun getActiveSession(): TerminalSessionInfo? = sessionManager.getActiveSession()

    /** 创建新会话 */
    fun createSession(
        workDir: String = "/",
        rows: Int = 24,
        cols: Int = 80,
        backend: TerminalBackend = TerminalBackend.HOST,
        initialCommand: String? = null,
    ): String = sessionManager.createSession(workDir, rows, cols, backend, initialCommand)

    /** 关闭会话 */
    fun closeSession(
        sessionId: String,
        defaultWorkDir: String = "/",
        createReplacement: Boolean = true
    ) = sessionManager.closeSession(sessionId, defaultWorkDir, createReplacement)

    /** 标记该会话在退出时不再追加 `[Process completed - press Enter]` 横幅。 */
    fun markSuppressExitNotice(sessionId: String) = sessionManager.markSuppressExitNotice(sessionId)

    /** 切换会话 */
    fun switchSession(sessionId: String) = sessionManager.switchSession(sessionId)

    /** 重命名会话 */
    fun renameSession(sessionId: String, newTitle: String) = sessionManager.renameSession(sessionId, newTitle)

    /** 重启会话 */
    fun restartSession(sessionId: String, workDir: String = "/") = sessionManager.restartSession(sessionId, workDir)

    /** 发送文本到活动会话 */
    fun sendText(text: String) = sessionManager.sendText(text)

    /** 调整活动会话终端大小 */
    fun resize(rows: Int, cols: Int) = sessionManager.resize(rows, cols)

    /** 发送中断信号 */
    fun sendInterrupt() = sessionManager.sendInterrupt()

    /**
     * 获取内部终端会话对象（用于 UI 渲染）
     *
     * 注意：返回类型为 Any? 以避免直接依赖 feature 层的具体类型。
     * 实际返回的是 com.termux.terminal.TerminalSession 对象。
     */
    fun getInternalSession(sessionId: String): Any? = sessionManager.getInternalSession(sessionId)

    /** 设置项目路径 */
    fun setProjectPath(projectPath: String) = sessionManager.setProjectPath(projectPath)

    /**
     * 保存终端状态到项目
     * 在 Activity onPause/onStop 时调用
     */
    fun saveState() = sessionManager.saveState()

    /**
     * 从项目恢复终端状态
     * 在打开终端时调用（替代手动创建会话）
     *
     * @param projectPath 项目路径
     * @param defaultWorkDir 默认工作目录
     */
    fun restoreState(projectPath: String, defaultWorkDir: String = "/") = sessionManager.restoreState(projectPath, defaultWorkDir)

    /**
     * 清除项目的终端状态
     */
    fun clearState() = sessionManager.clearState()

    /** 设置主题 */
    fun setTheme(theme: TerminalTheme) = setThemeName(theme.name)

    fun setThemeName(themeName: String) {
        preferences.themeName = themeName
    }

    fun setFontSize(fontSizeSp: Float) {
        preferences.fontSize = fontSizeSp
    }

    fun getTerminalTypeface() = preferences.getTypeface()

    /** 获取当前主题对象 */
    fun getCurrentTheme(): TerminalTheme = TerminalTheme.fromName(currentTheme.value)

    // ITerminalSessionManager 是应用级单例；Activity ViewModel 销毁不能清理其他 Activity
    // 正在使用的共享终端。一次性 Run 会话由 TerminalActivity 按 sessionId 显式关闭。
}
