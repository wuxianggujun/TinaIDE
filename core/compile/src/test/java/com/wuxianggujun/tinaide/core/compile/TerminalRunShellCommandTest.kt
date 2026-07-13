package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import org.junit.Test

/**
 * Run/Terminal shell 命令拼装回归测试。
 *
 * 重点覆盖 [buildTerminalCommand] 长期缺失的两条语义：
 * 1. **源文件 vs stage 后目标**：shebang 探测必须对真实存在的源文件做，
 *    目标路径此时还没 `cp` 过去。
 * 2. **shebang 脚本产物**：必须走 `/system/bin/sh`，不能被 linker64 吞掉。
 *
 * 纯函数测试，不依赖 Android Context。路径字段统一用 String，避免 JVM 单测在
 * Windows 上被 File 分隔符归一化打扰。
 */
class TerminalRunShellCommandTest {

    private fun layout(
        workingDir: String = "/project",
        sourcePath: String = "/workspace/main",
        stageDirPath: String = "/data/files/run-bin",
        stagedTargetPath: String = "/data/files/run-bin/main.abcdef",
        args: List<String> = emptyList(),
        envPrefix: String = "",
        ldLibraryPrefix: String = "",
        waitForEnterSuffix: String = "",
        showLinkerWarnings: Boolean = false,
        kind: NativeExecutableRunner.ExecutableKind = NativeExecutableRunner.ExecutableKind.ELF
    ): TerminalRunLayout = TerminalRunLayout(
        workingDir = workingDir,
        sourcePath = sourcePath,
        stageDirPath = stageDirPath,
        stagedTargetPath = stagedTargetPath,
        args = args,
        envPrefix = envPrefix,
        ldLibraryPrefix = ldLibraryPrefix,
        waitForEnterSuffix = waitForEnterSuffix,
        showLinkerWarnings = showLinkerWarnings,
        kind = kind
    )

    @Test
    fun `ELF source produces linker64-wrapped run on staged target`() {
        val command = assembleTerminalRunShellCommand(
            layout = layout(kind = NativeExecutableRunner.ExecutableKind.ELF),
            preferLinker64 = true
        )

        assertThat(command).contains("'/system/bin/linker64' '/data/files/run-bin/main.abcdef'")
        assertThat(command).doesNotContain("'/system/bin/sh' '/data/files/run-bin/main.abcdef'")
    }

    @Test
    fun `SHEBANG_SCRIPT source routes staged target through bin sh not linker64`() {
        // 这就是 buildTerminalCommand 历史上的 bug：对 stagedTarget auto-probe 永远返回 ELF，
        // 导致脚本产物被 linker64 拉起直接 crash。修复后调用方传入源文件探测到的 kind。
        val command = assembleTerminalRunShellCommand(
            layout = layout(kind = NativeExecutableRunner.ExecutableKind.SHEBANG_SCRIPT),
            preferLinker64 = true
        )

        assertThat(command).contains("'/system/bin/sh' '/data/files/run-bin/main.abcdef'")
        assertThat(command).doesNotContain("linker64 '/data/files/run-bin/main.abcdef'")
        assertThat(command).doesNotContain("'/system/bin/linker64' '/data/files/run-bin/main.abcdef'")
    }

    @Test
    fun `command lays out mkdir cp chmod before running staged target`() {
        val command = assembleTerminalRunShellCommand(
            layout = layout(),
            preferLinker64 = true
        )

        val mkdirIdx = command.indexOf("mkdir -p '/data/files/run-bin'")
        val cpIdx = command.indexOf("cp -f '/workspace/main' '/data/files/run-bin/main.abcdef'")
        val chmodIdx = command.indexOf("chmod 700 '/data/files/run-bin/main.abcdef'")
        val linkerIdx = command.indexOf("'/system/bin/linker64'")

        assertThat(mkdirIdx).isGreaterThan(-1)
        assertThat(cpIdx).isGreaterThan(mkdirIdx)
        assertThat(chmodIdx).isGreaterThan(cpIdx)
        assertThat(linkerIdx).isGreaterThan(chmodIdx)
    }

    @Test
    fun `args are POSIX shell-quoted and trail the run snippet`() {
        val command = assembleTerminalRunShellCommand(
            layout = layout(args = listOf("--flag", "value with space", "quote'it")),
            preferLinker64 = true
        )

        assertThat(command).contains("'--flag'")
        assertThat(command).contains("'value with space'")
        // 单引号按 POSIX '"'"' 方式转义
        assertThat(command).contains("'quote'\"'\"'it'")
    }

    @Test
    fun `ldLibraryPrefix is emitted before the run snippet`() {
        val ldPrefix = "LD_LIBRARY_PATH='/lib' "
        val command = assembleTerminalRunShellCommand(
            layout = layout(ldLibraryPrefix = ldPrefix),
            preferLinker64 = true
        )

        val ldIdx = command.indexOf(ldPrefix)
        val linkerIdx = command.indexOf("'/system/bin/linker64'")
        assertThat(ldIdx).isGreaterThan(-1)
        assertThat(linkerIdx).isGreaterThan(ldIdx)
    }

    @Test
    fun `envPrefix is emitted before ldLibraryPrefix and run snippet`() {
        val envPrefix = "FOO='bar' BAR='baz' "
        val ldPrefix = "LD_LIBRARY_PATH='/lib' "
        val command = assembleTerminalRunShellCommand(
            layout = layout(envPrefix = envPrefix, ldLibraryPrefix = ldPrefix),
            preferLinker64 = true
        )

        val envIdx = command.indexOf(envPrefix)
        val ldIdx = command.indexOf(ldPrefix)
        val linkerIdx = command.indexOf("'/system/bin/linker64'")
        assertThat(envIdx).isGreaterThan(-1)
        assertThat(ldIdx).isGreaterThan(envIdx)
        assertThat(linkerIdx).isGreaterThan(ldIdx)
    }

    @Test
    fun `workingDir with special chars is shell-quoted`() {
        val command = assembleTerminalRunShellCommand(
            layout = layout(workingDir = "/path with space/project"),
            preferLinker64 = true
        )

        assertThat(command).startsWith("cd '/path with space/project' ")
    }

    @Test
    fun `preferLinker64 false keeps ELF exec direct on staged target`() {
        // 老设备 / 关闭 linker64 的场景：保持原生 exec 语义。
        val command = assembleTerminalRunShellCommand(
            layout = layout(kind = NativeExecutableRunner.ExecutableKind.ELF),
            preferLinker64 = false
        )

        assertThat(command).doesNotContain("linker64")
        // 直接调用 stagedTarget，不再加壳
        assertThat(command).contains("&& '/data/files/run-bin/main.abcdef'")
    }

    @Test
    fun `waitForEnterSuffix is appended to the tail`() {
        val suffix = "; __tina_rc=\$?"
        val command = assembleTerminalRunShellCommand(
            layout = layout(waitForEnterSuffix = suffix),
            preferLinker64 = true
        )

        assertThat(command).endsWith(suffix)
    }

    @Test
    fun `default ELF run filters only known AArch64 auth RELR linker warnings`() {
        val command = assembleTerminalRunShellCommand(
            layout = layout(showLinkerWarnings = false),
            preferLinker64 = true
        )

        assertThat(command).contains("mkfifo \"\$__tina_err_fifo\"")
        assertThat(command).contains("0x70000011")
        assertThat(command).contains("0x70000012")
        assertThat(command).contains("0x70000013")
        assertThat(command).contains(
            "'WARNING: linker: Warning: '*' unused DT entry: unknown processor-specific " +
                "(type 0x70000012 arg '*') (ignoring)'"
        )
        assertThat(command).contains("printf '%s\\n' \"\$__tina_line\" >&2")
        assertThat(command).contains("2>\"\$__tina_err_fifo\"")
        assertThat(command).contains("(exit \"\$__tina_program_rc\")")
        assertThat(command).doesNotContain("2>/dev/null; __tina_program_rc")
    }

    @Test
    fun `explicit linker warning option keeps stderr unfiltered`() {
        val command = assembleTerminalRunShellCommand(
            layout = layout(showLinkerWarnings = true),
            preferLinker64 = true
        )

        assertThat(command).doesNotContain("__tina_err_fifo")
        assertThat(command).doesNotContain("0x70000011")
        assertThat(command).contains("'/system/bin/linker64' '/data/files/run-bin/main.abcdef'")
    }

    @Test
    fun `non linker launch does not install linker warning filter`() {
        val command = assembleTerminalRunShellCommand(
            layout = layout(showLinkerWarnings = false),
            preferLinker64 = false
        )

        assertThat(command).doesNotContain("__tina_err_fifo")
        assertThat(command).doesNotContain("0x70000011")
    }
}
