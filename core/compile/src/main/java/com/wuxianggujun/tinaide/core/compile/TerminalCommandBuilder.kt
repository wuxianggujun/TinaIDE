package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner.shellQuotePosix
import java.io.File

/**
 * 终端运行命令组装器。
 *
 * 把一个已构建产物 + 运行工作目录 + 参数组装成可直接喂给 TerminalBackend 的
 * 完整 shell 命令字符串,内含:
 * - sysroot `libc++_shared.so` 存在时的 `LD_LIBRARY_PATH` 注入
 * - 产物 staged copy 到 app 私有 run-bin(规避公有目录 noexec)
 * - 运行结束后"按 Enter 键关闭"交互(靠 OSC 777;tina-run-end 通知 Activity)
 */
class TerminalCommandBuilder(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 组装终端运行 shell 命令。
     *
     * @param workingDir 运行工作目录(通常来自 RunConfiguration 解析后的绝对路径)
     * @param outputPath 产物绝对路径,会被 staged 到 run-bin 再启动
     * @param args 命令行参数(已经过变量替换)
     * @param projectRoot 项目根目录,用于解析已安装包的 runtime lib 目录
     * @param extraEnvironment 额外注入到运行 shell 的环境变量
     * @param showLinkerWarnings 是否原样显示已知的 AArch64 Auth RELR linker 兼容告警
     */
    fun build(
        workingDir: String,
        outputPath: String,
        args: List<String>,
        projectRoot: File,
        extraEnvironment: Map<String, String> = emptyMap(),
        nativeRuntimeIdentity: NativeRuntimeIdentity? = null,
        showLinkerWarnings: Boolean = false,
    ): String {
        val outputFile = File(outputPath)
        val stageDir = File(appContext.filesDir, "run-bin")
        val stageIdentity = "${outputFile.absolutePath}:${outputFile.lastModified()}:${outputFile.length()}"
        val stageKey = stageIdentity.hashCode().toUInt().toString(16)
        val stagedOutput = File(stageDir, "${outputFile.name}.$stageKey")
        RunStagingCleaner.cleanup(stageDir, keepFileName = stagedOutput.name)

        // 对**真实存在**的源文件做 shebang 探测;stagedOutput 此时尚未拷贝到位,
        // 若对它探测会误判为 ELF,脚本产物会被套上 linker64 导致启动失败。
        val sourceKind = NativeExecutableRunner.ExecutableKind.probe(outputFile)

        // 仅在 sysroot 中存在 libc++_shared.so 时设置 LD_LIBRARY_PATH(供 C++ 程序使用)。
        // 不能把整个 sysroot lib 目录加入 LD_LIBRARY_PATH,因为其中包含 NDK stub 库
        // (libc.so 等),这些 stub 没有实际代码,运行时加载会导致 SIGSEGV。
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectRoot)
        val runtimeLibPaths = buildList {
            outputFile.parentFile?.absolutePath?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(
                NativeRuntimeLibraryPaths.sysrootRuntimeDirs(
                    context = appContext,
                    sysrootProfileId = nativeRuntimeIdentity?.sysrootProfileId,
                ).map { it.absolutePath }
            )
            addAll(packagePaths.runtimeLibDirs.map { it.absolutePath })
        }.distinct()
        val launchEnvironment = LaunchEnvironment.withPrependedPath(
            environment = extraEnvironment,
            variableName = "LD_LIBRARY_PATH",
            paths = runtimeLibPaths,
        )
        val ldLibraryPath = launchEnvironment["LD_LIBRARY_PATH"].orEmpty()
        val ldLibraryPrefix = if (ldLibraryPath.isNotBlank()) {
            "LD_LIBRARY_PATH=${shellQuotePosix(ldLibraryPath)}:\$LD_LIBRARY_PATH "
        } else {
            ""
        }
        val envPrefix = LaunchEnvironment.buildShellPrefix(launchEnvironment - "LD_LIBRARY_PATH")

        val waitForEnterSuffix = buildWaitForEnterSuffix()

        return assembleTerminalRunShellCommand(
            layout = TerminalRunLayout(
                workingDir = workingDir,
                sourcePath = outputPath,
                stageDirPath = stageDir.absolutePath,
                stagedTargetPath = stagedOutput.absolutePath,
                args = args,
                envPrefix = envPrefix,
                ldLibraryPrefix = ldLibraryPrefix,
                waitForEnterSuffix = waitForEnterSuffix,
                showLinkerWarnings = showLinkerWarnings,
                kind = sourceKind,
            )
        )
    }

    /**
     * 运行结束后"按 Enter 键关闭"交互后缀。
     *
     * 细节见旧 `CompileProjectUseCase.buildWaitForEnterSuffix` 的文档注释(OSC 协议、
     * 不回显、exitCode 透传等)。
     */
    private fun buildWaitForEnterSuffix(): String {
        val rawTemplate = Strings.compile_run_press_enter_to_close.strOr(appContext)
        return buildLocalizedWaitForEnterSuffix(rawTemplate)
    }
}

/**
 * 把 Android 本地化资源模板安全转换为运行结束后的 shell 后缀。
 *
 * 翻译文本始终作为 `printf` 的 `%s` 数据参数，只有退出码使用受控的 `%d`。这样译文中
 * 即使包含 `%`、单引号或换行，也不会被 shell `printf` 误解为额外格式占位符。
 */
internal fun buildLocalizedWaitForEnterSuffix(rawTemplate: String): String {
    val exitCodePlaceholder = "%1\$d"
    val placeholderIndex = rawTemplate.indexOf(exitCodePlaceholder)
    require(placeholderIndex >= 0 && rawTemplate.lastIndexOf(exitCodePlaceholder) == placeholderIndex) {
        "Terminal exit prompt must contain exactly one %1\$d placeholder"
    }

    val promptBeforeExitCode = shellQuotePosix(rawTemplate.substring(0, placeholderIndex))
    val promptAfterExitCode = shellQuotePosix(
        rawTemplate.substring(placeholderIndex + exitCodePlaceholder.length)
    )
    return "; __tina_rc=\$?" +
        "; printf '%s%d%s' $promptBeforeExitCode \"\$__tina_rc\" $promptAfterExitCode" +
        "; printf '\\033]777;tina-run-end;%d\\a' \"\$__tina_rc\"" +
        "; cat > /dev/null"
}
