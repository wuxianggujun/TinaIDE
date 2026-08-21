package com.wuxianggujun.tinaide.file

/**
 * 项目会话接口。
 *
 * 负责项目的打开与关闭，避免调用方为了会话控制而依赖完整文件管理能力。
 *
 * 生命周期责任划分：
 * - [openProject] 启动新会话（如已有会话会先行 [closeProject]）。
 * - [closeProject] 主动关闭并忘记会话（内存清零 + 偏好移除）。
 * - [restoreLastSession] 显式地从偏好恢复上次会话（幂等；若内存已有项目直接返回）。
 * - [clearInMemorySession] 仅清空内存态（包括 FileWatcher），**不删偏好**，
 *   便于下次在 [MainActivity] 里再次调用 [restoreLastSession] 恢复。
 */
interface IProjectSession {
    fun openProject(path: String): Project
    fun closeProject()

    /**
     * 项目目录移动后，同步当前会话和最近文件中的绝对路径。
     */
    fun retargetProjectPath(oldPath: String, newPath: String)

    fun restoreLastSession(): Project?

    fun clearInMemorySession()
}
