package com.wuxianggujun.tinaide.core.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * 终端会话管理器接口
 *
 * 架构说明：
 * - 接口定义在 core:common 层
 * - feature:terminal 层的 TerminalSessionManager 需要适配为此接口
 * - app 层通过此接口访问终端会话管理功能
 */
interface ITerminalSessionManager {
    /**
     * 所有终端会话列表
     */
    val sessions: StateFlow<List<TerminalSessionInfo>>

    /**
     * 当前活动会话 ID
     */
    val activeSessionId: StateFlow<String?>

    /**
     * 帧 ID（用于触发 UI 重绘）
     */
    val frameId: StateFlow<Int>

    /**
     * 初始化会话客户端
     */
    fun initialize()

    /**
     * 创建新的终端会话
     *
     * @param workDir 工作目录
     * @param rows 终端行数
     * @param cols 终端列数
     * @param backend 终端后端（HOST 或 PROOT）
     * @param initialCommand 一次性执行的内部命令；非空时以非交互 shell 启动，避免命令回显
     * @return 新会话的 ID
     */
    fun createSession(
        workDir: String = "/",
        rows: Int = 24,
        cols: Int = 80,
        backend: TerminalBackend = TerminalBackend.HOST,
        initialCommand: String? = null,
    ): String

    /**
     * 关闭会话
     *
     * @param sessionId 要关闭的会话 ID
     * @param defaultWorkDir 当关闭最后一个会话时，新建默认会话的工作目录
     * @param createReplacement 是否在关闭最后一个会话后自动创建默认会话
     */
    fun closeSession(
        sessionId: String,
        defaultWorkDir: String = "/",
        createReplacement: Boolean = true
    )

    /**
     * 切换到指定会话
     *
     * @param sessionId 要切换到的会话 ID
     */
    fun switchSession(sessionId: String)

    /**
     * 重命名会话
     *
     * @param sessionId 要重命名的会话 ID
     * @param newTitle 新标题
     */
    fun renameSession(sessionId: String, newTitle: String)

    /**
     * 重启会话
     *
     * @param sessionId 要重启的会话 ID
     * @param workDir 工作目录
     */
    fun restartSession(sessionId: String, workDir: String = "/")

    /**
     * 向活动会话发送文本
     */
    fun sendText(text: String)

    /**
     * 向活动会话发送中断信号 (Ctrl+C)
     */
    fun sendInterrupt()

    /**
     * 调整活动会话的终端大小
     */
    fun resize(rows: Int, cols: Int)

    /**
     * 获取指定会话
     */
    fun getSessionById(sessionId: String): TerminalSessionInfo?

    /**
     * 获取当前活动会话
     */
    fun getActiveSession(): TerminalSessionInfo?

    /**
     * 设置当前项目路径
     */
    fun setProjectPath(projectPath: String)

    /**
     * 保存终端状态到项目
     */
    fun saveState()

    /**
     * 从项目恢复终端状态
     *
     * @param projectPath 项目路径
     * @param defaultWorkDir 默认工作目录
     */
    fun restoreState(projectPath: String, defaultWorkDir: String = "/")

    /**
     * 清除项目的终端状态
     */
    fun clearState()

    /**
     * 清理所有会话
     */
    fun cleanup()

    /**
     * 为指定会话开启"抑制退出横幅"标志，之后 shell 进程退出时不再把
     * `[Process completed - press Enter]` 追加到 emulator。
     *
     * 仅建议在一次性的 Run 模式会话上使用；普通交互终端保持默认行为。
     */
    fun markSuppressExitNotice(sessionId: String)

    /**
     * 获取内部终端会话对象（用于 UI 渲染）
     *
     * 注意：返回类型为 Any? 以避免 app 层直接依赖 feature 层的具体类型。
     * 实际返回的是 com.termux.terminal.TerminalSession 对象。
     *
     * @param sessionId 会话 ID
     * @return 内部终端会话对象，如果会话不存在则返回 null
     */
    fun getInternalSession(sessionId: String): Any?
}
