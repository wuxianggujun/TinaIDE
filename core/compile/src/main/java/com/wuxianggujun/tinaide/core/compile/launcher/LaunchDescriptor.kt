package com.wuxianggujun.tinaide.core.compile.launcher

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import java.io.File

/**
 * Launch 描述符：告知 UI 层要启动什么。
 * 这里不组装实际 shell 命令；终端启动时由 UI 层调用 TerminalCommandBuilder 完成。
 */
sealed interface LaunchDescriptor {
    val artifact: Artifact

    /** SDL 图形运行时加载 .so 运行。 */
    data class Sdl(
        override val artifact: Artifact,
        val libraryPath: String,
    ) : LaunchDescriptor

    /** NativeActivity 图形运行时加载 .so 运行。 */
    data class NativeActivity(
        override val artifact: Artifact,
        val libraryPath: String,
    ) : LaunchDescriptor

    /** 启动调试会话(gdb / lldb)。 */
    data class Debug(
        override val artifact: Artifact,
        val programPath: String,
        val workingDir: String,
    ) : LaunchDescriptor

    /**
     * 在终端中运行可执行文件。
     *
     * UI 层(CompileActionsHelper)拿到后用 TerminalCommandBuilder 组装实际 shell 命令,
     * 含 sysroot LD_LIBRARY_PATH / staged copy / wait-for-enter 等策略。
     */
    data class Terminal(
        override val artifact: Artifact,
        val runnablePath: String,
        val workingDir: File,
        val args: List<String> = emptyList(),
    ) : LaunchDescriptor
}
