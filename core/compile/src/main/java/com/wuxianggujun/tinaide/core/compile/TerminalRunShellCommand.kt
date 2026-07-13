package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner.shellQuotePosix

/**
 * Run/Terminal 场景下，shell 命令拼装所需的纯数据：
 *
 * ```
 * cd <workingDir>
 *   && mkdir -p <stageDirPath>
 *   && cp -f <sourcePath> <stagedTargetPath>
 *   && chmod 700 <stagedTargetPath>
 *   && printf '\033[H\033[2J'
 *   && <envPrefix><ldLibraryPrefix><run-snippet>
 *   <waitForEnterSuffix>
 * ```
 *
 * - 所有路径字段都直接用字符串而不是 [java.io.File]，避免 Windows JVM 上单测被
 *   `File.absolutePath` / `File.path` 的分隔符归一化污染。生产路径本就都是
 *   Android 上的 Unix 绝对路径字符串。
 * - [kind] 必须由调用方对**真实存在的源文件** [sourcePath] 做
 *   [NativeExecutableRunner.ExecutableKind.probe] 获得，然后透传给本结构；
 *   shell 命令会把探测结果落到 stage-copy 后的目标路径上，避免对尚未创建的
 *   [stagedTargetPath] 做探测导致脚本产物被误识别为 ELF。
 */
internal data class TerminalRunLayout(
    val workingDir: String,
    val sourcePath: String,
    val stageDirPath: String,
    val stagedTargetPath: String,
    val args: List<String>,
    val envPrefix: String,
    val ldLibraryPrefix: String,
    val waitForEnterSuffix: String,
    val showLinkerWarnings: Boolean,
    val kind: NativeExecutableRunner.ExecutableKind
)

/**
 * 按 [TerminalRunLayout] 拼装最终送给 shell 的一整条命令字符串。
 *
 * 纯函数：只依赖入参，不接触 Android/文件系统。方便为"stage-copy 顺序""shebang 分支"
 * "arg 转义""ldLibraryPrefix 位置"等语义做单元级回归。
 */
internal fun assembleTerminalRunShellCommand(
    layout: TerminalRunLayout,
    preferLinker64: Boolean = NativeExecutableRunner.shouldPreferLinker64()
): String {
    val runSnippet = NativeExecutableRunner.buildShellSnippet(
        executable = layout.stagedTargetPath,
        args = layout.args,
        kind = layout.kind,
        preferLinker64 = preferLinker64
    )
    val runCommand = layout.envPrefix + layout.ldLibraryPrefix + runSnippet
    val effectiveRunCommand = if (
        layout.kind == NativeExecutableRunner.ExecutableKind.ELF &&
        preferLinker64 &&
        !layout.showLinkerWarnings
    ) {
        wrapWithKnownLinkerWarningFilter(runCommand, layout.stagedTargetPath)
    } else {
        runCommand
    }
    return buildString {
        append("cd ").append(shellQuotePosix(layout.workingDir))
        append(" && mkdir -p ").append(shellQuotePosix(layout.stageDirPath))
        append(" && cp -f ")
            .append(shellQuotePosix(layout.sourcePath))
            .append(' ')
            .append(shellQuotePosix(layout.stagedTargetPath))
        append(" && chmod 700 ").append(shellQuotePosix(layout.stagedTargetPath))
        append(" 2>/dev/null && printf '\\033[H\\033[2J' && ")
        append(effectiveRunCommand)
        append(layout.waitForEnterSuffix)
    }
}

/**
 * 实时转发 stderr，仅丢弃旧 Android linker 无法识别 AArch64 Auth RELR 标签时产生的
 * 三种固定告警。使用 FIFO 而不是重定向到临时文件，避免程序运行期间 stderr 延迟到退出后
 * 才显示；最后以子 shell 恢复程序退出码，确保后续终端提示拿到真实状态。
 */
private fun wrapWithKnownLinkerWarningFilter(
    runCommand: String,
    stagedTargetPath: String,
): String = buildString {
    append("{ __tina_err_fifo=")
    append(shellQuotePosix("$stagedTargetPath.stderr"))
    append(".\$\$")
    append("; rm -f \"\$__tina_err_fifo\" 2>/dev/null")
    append("; if mkfifo \"\$__tina_err_fifo\" 2>/dev/null; then ")
    append("(while IFS= read -r __tina_line || [ -n \"\$__tina_line\" ]; do ")
    append("case \"\$__tina_line\" in ")
    KNOWN_AARCH64_AUTH_RELR_DYNAMIC_TAGS.forEach { tag ->
        append("'WARNING: linker: Warning: '*' unused DT entry: unknown processor-specific (type ")
        append(tag)
        append(" arg '*') (ignoring)') ;; ")
    }
    append("*) printf '%s\\n' \"\$__tina_line\" >&2 ;; esac")
    append("; done < \"\$__tina_err_fifo\") & __tina_filter_pid=\$!")
    append("; ").append(runCommand).append(" 2>\"\$__tina_err_fifo\"")
    append("; __tina_program_rc=\$?")
    append("; wait \"\$__tina_filter_pid\"")
    append("; rm -f \"\$__tina_err_fifo\"")
    append("; (exit \"\$__tina_program_rc\")")
    append("; else ").append(runCommand)
    append("; fi; }")
}

private val KNOWN_AARCH64_AUTH_RELR_DYNAMIC_TAGS = listOf(
    "0x70000011",
    "0x70000012",
    "0x70000013",
)
