package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.security.PathValidator
import com.wuxianggujun.tinaide.exec.TinaExecPreloadMode
import com.wuxianggujun.tinaide.exec.TinaExecRuntime
import com.wuxianggujun.tinaide.exec.TinaExecSystemLinkerMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * PRoot 进程管理器
 *
 * 直接从 `nativeLibraryDir/libproot.so` 执行 proot。
 *
 * 注意：proot 必须在 nativeLibraryDir 中才能被执行，因为 Android 允许执行 APK 中的 native library。
 * 如果从可写目录执行，会因为 W^X 保护而失败。
 */
class PRootManager(
    private val context: Context,
    private val rootfsPath: String
) {
    private val compatLoggedOnce = AtomicBoolean(false)
    private val launchConfigLock = Any()
    private val pathValidator by lazy { PathValidator(context) }

    /**
     * PRoot 兼容模式开关：
     * - `null`：自动（仅在老旧内核上可能开启）
     * - `true/false`：强制开/关（用于调试与回归对比）
     */
    @Volatile
    var forceCompatMode: Boolean? = null

    private val nativeLibDir: String by lazy { context.applicationInfo.nativeLibraryDir ?: "" }

    private val projectsHostDir: File by lazy {
        com.wuxianggujun.tinaide.storage.ProjectPaths.getPrivateProjectsRoot(context).apply { mkdirs() }
    }

    private val workspaceHostDir: File by lazy {
        com.wuxianggujun.tinaide.storage.ProjectPaths.getWorkspaceRoot(context).apply { mkdirs() }
    }

    // proot 二进制文件（在 APK 的 nativeLibraryDir 中）
    private val prootBinary: File by lazy {
        File(nativeLibDir, "libproot.so")
    }

    // 注意：Android 15+ 对私有目录实施 W^X 保护，不允许执行私有目录下的文件。
    // 因此必须始终使用 nativeLibraryDir 下的 libproot.so，不再复制到私有目录。

    // init-proot.sh 脚本路径（从 assets 复制到可写目录）
    private val initProotScript: File by lazy {
        File(com.wuxianggujun.tinaide.storage.ProjectPaths.getPRootRoot(context), "init-proot.sh")
    }

    private val launchConfigFile: File by lazy {
        File(com.wuxianggujun.tinaide.storage.ProjectPaths.getPRootRoot(context), "proot-launch-config.txt")
    }

    // proot-loader：Termux 官方的 loader，proot 内部使用的 ELF 加载器
    // 这是 proot 用 PROOT_UNBUNDLE_LOADER 编译时需要的外部 loader
    private val prootLoaderPath: File by lazy {
        File(nativeLibDir, "libproot-loader.so")
    }

    // proot-loader32：Termux 官方的 32 位 loader，用于在 64 位系统上运行 32 位程序
    private val prootLoader32Path: File by lazy {
        File(nativeLibDir, "libproot-loader32.so")
    }

    /**
     * 兼容性检查：
     * - 在 x86_64 模拟器/设备上，系统可能允许安装/运行 arm64 APK（native bridge/翻译层）
     * - 但本项目的 PRoot 运行方式依赖 bionic linker/ELF 行为，翻译层经常无法正确执行 `nativeLibraryDir/libproot.so`
     * - 结果是安装流程长时间卡住（等待超时）或直接失败
     *
     * 这里做“快速失败”，引导用户安装匹配架构的 APK 或换 arm64 设备/模拟器。
     */
    private fun ensureSupportedRuntime() {
        val libDir = nativeLibDir
        val deviceAbis = Build.SUPPORTED_ABIS.toList()

        val deviceSupportsX8664Abi = deviceAbis.any { it.equals("x86_64", ignoreCase = true) }
        val appUsesArm64Libs = libDir.contains("/arm64", ignoreCase = true) || libDir.contains("\\arm64", ignoreCase = true)

        // x86_64 设备上运行 arm64 变体：大概率是翻译层环境，PRoot 执行不可靠
        if (deviceSupportsX8664Abi && appUsesArm64Libs) {
            throw AbiMismatchException(
                """
                |架构不匹配错误：
                |
                |检测到当前设备/模拟器支持 x86_64，但应用正在使用 arm64 原生库（可能处于 native bridge/翻译层环境）。
                |该环境下 PRoot 通常无法正确启动，安装会卡住或失败。
                |
                |? 设备支持 ABI：${deviceAbis.joinToString()}
                |? 应用 nativeLibraryDir：$libDir
                |
                |请使用以下任一方式：
                |1) arm64 设备/模拟器 → 安装 arm64 版本 APK（app-arm64-v8a-*.apk）
                |2) x86_64 设备/模拟器 → 安装 x86_64 版本 APK（app-x86_64-*.apk）
                |
                |提示：如已安装错误 variant，请先卸载再安装正确的 APK。
                """.trimMargin()
            )
        }
    }

    fun isRuntimeSupported(): Boolean = runCatching {
        ensureSupportedRuntime()
        prootBinary.isFile
    }.getOrDefault(false)

    fun isInstalled(): Boolean {
        val rootDir = File(rootfsPath)
        return prootBinary.exists() &&
            rootDir.isDirectory &&
            RootfsFileChecks.exists(rootDir, "/bin/sh")
    }

    /**
     * 确保 init-proot.sh 脚本存在且是最新版本
     * 从 assets 复制到可写目录
     *
     * 注意：每次都重新复制，确保脚本是最新的
     */
    private fun ensureInitScript() {
        try {
            initProotScript.parentFile?.mkdirs()
            // 每次都重新复制，确保脚本是最新的
            // 重要：转换 Windows CRLF 为 Unix LF，避免 shell 语法错误
            context.assets.open("proot/init-proot.sh").use { input ->
                val content = input.bufferedReader().use { it.readText() }
                // 将 CRLF (\r\n) 和 CR (\r) 都替换为 LF (\n)
                val unixContent = content.replace("\r\n", "\n").replace("\r", "\n")
                initProotScript.writeText(unixContent, Charsets.UTF_8)
            }
            initProotScript.setExecutable(true, false)
            Timber.tag("PRootManager").d("init-proot.sh copied to %s (line endings normalized)", initProotScript.absolutePath)
        } catch (e: Exception) {
            Timber.tag("PRootManager").e(e, "Failed to copy init-proot.sh")
        }
    }

    private data class LaunchConfig(
        val mode: String, // direct|linker
    ) {
        fun isValid(): Boolean = (mode == "direct" || mode == "linker")
    }

    private fun readLaunchConfigFromDisk(): LaunchConfig? {
        return runCatching {
            if (!launchConfigFile.exists()) return@runCatching null
            val text = launchConfigFile.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) return@runCatching null
            val parts = text.split('|', limit = 2)
            if (parts.getOrNull(0) != LAUNCH_CONFIG_VERSION) return@runCatching null
            val mode = parts.getOrNull(1)?.trim().orEmpty()
            LaunchConfig(mode = mode).takeIf { it.isValid() }
        }.getOrNull()
    }

    private fun writeLaunchConfigToDisk(config: LaunchConfig) {
        runCatching {
            launchConfigFile.parentFile?.mkdirs()
            launchConfigFile.writeText("$LAUNCH_CONFIG_VERSION|${config.mode}", Charsets.UTF_8)
        }.onFailure { e ->
            Timber.tag("PRootManager").w(e, "Failed to persist proot launch config")
        }
    }

    private fun clearLaunchConfigFromDisk(reason: String) {
        runCatching {
            if (launchConfigFile.exists()) {
                launchConfigFile.delete()
            }
        }.onSuccess {
            Timber.tag("PRootManager").w("Cleared proot launch config: %s", reason)
        }.onFailure { e ->
            Timber.tag("PRootManager").w(e, "Failed to clear proot launch config: %s", reason)
        }
    }

    private fun isLikelyLinkerHelpOnly(output: String): Boolean {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return false
        return trimmed == "This is /system/bin/linker64, the helper program for dynamic executables." ||
            trimmed == "This is /system/bin/linker, the helper program for dynamic executables." ||
            trimmed.contains("helper program for dynamic executables", ignoreCase = true)
    }

    private fun isLikelyExecFormatOrPermissionError(output: String): Boolean {
        val lower = output.lowercase()
        return (lower.contains("not executable") && lower.contains("elf")) ||
            lower.contains("exec format error") ||
            (lower.contains("permission denied") && (lower.contains("proot") || lower.contains("libproot.so")))
    }

    private fun isLikelyLaunchConfigFailure(result: PRootResult): Boolean {
        if (result.exitCode == 0 || result.timedOut) return false

        val output = result.combinedOutput
        if (isLikelyLinkerHelpOnly(output)) return true
        if (isLikelyExecFormatOrPermissionError(output)) return true

        val lower = output.lowercase()
        return result.exitCode == 139 ||
            lower.contains("terminated with signal 11") ||
            lower.contains("signal 11") ||
            lower.contains("sigsegv")
    }

    private fun invalidateLaunchConfigIfNeeded(
        result: PRootResult,
        launchConfig: LaunchConfig?,
    ): Boolean {
        if (launchConfig == null || !isLikelyLaunchConfigFailure(result)) return false

        synchronized(launchConfigLock) {
            val current = readLaunchConfigFromDisk()
            if (current == null || current == launchConfig) {
                clearLaunchConfigFromDisk("mode=${launchConfig.mode}, exit=${result.exitCode}")
                return true
            }
        }
        return false
    }

    private suspend fun probeLaunchConfig(baseEnv: Map<String, String>): LaunchConfig? = coroutineScope {
        // rootfs 未就绪时不探测，避免误写缓存
        if (!File(rootfsPath).exists()) return@coroutineScope null

        ensureSupportedRuntime()
        ensureInitScript()

        // Use a real child process so a launch mode is not cached merely because
        // the initial guest shell and its builtins can run.
        val probeCmd = listOf("/bin/sh", "-lc", "/bin/true && echo __TINA_PROOT_OK__")
        // Android 15+ 必须使用 nativeLibraryDir，只需探测启动模式（direct/linker）
        val candidates = listOf(
            LaunchConfig(mode = "direct"),
            LaunchConfig(mode = "linker"),
        )

        suspend fun tryCandidate(candidate: LaunchConfig): Boolean = withContext(Dispatchers.IO) {
            if (!prootBinary.exists()) return@withContext false

            val env = baseEnv.toMutableMap()
            env["PROOT_LAUNCH_MODE"] = candidate.mode
            env["PROOT_BIN"] = prootBinary.absolutePath
            env["WORK_DIR"] = "/"

            val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
            val cmdLine = arrayOf("/system/bin/sh", initProotScript.absolutePath) + probeCmd.toTypedArray()

            val process = try {
                startHostProcess(cmdLine, envArray)
            } catch (e: IOException) {
                Timber.tag("PRootManager").d(e, "Probe launch failed to start (mode=%s)", candidate.mode)
                return@withContext false
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val t1 = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(stdout) {
                                if (stdout.isNotEmpty()) stdout.append('\n')
                                stdout.append(line)
                            }
                        }
                    }
                }
            }
            val t2 = Thread {
                runCatching {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(stderr) {
                                if (stderr.isNotEmpty()) stderr.append('\n')
                                stderr.append(line)
                            }
                        }
                    }
                }
            }
            t1.start()
            t2.start()

            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(200, TimeUnit.MILLISECONDS)
            }
            t1.join(500)
            t2.join(500)

            val out = buildString {
                val s1 = synchronized(stdout) { stdout.toString() }
                val s2 = synchronized(stderr) { stderr.toString() }
                if (s1.isNotBlank()) append(s1)
                if (s2.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(s2)
                }
            }

            val ok = out.contains("__TINA_PROOT_OK__")
            if (!ok) {
                Timber.tag("PRootManager").d(
                    "Probe failed (mode=%s, exit=%d): %s",
                    candidate.mode,
                    runCatching { process.exitValue() }.getOrElse { -1 },
                    out.take(200)
                )
            }
            ok
        }

        for (candidate in candidates) {
            val ok = tryCandidate(candidate)
            if (ok) return@coroutineScope candidate
        }
        null
    }

    private suspend fun ensureLaunchConfig(baseEnv: Map<String, String>): LaunchConfig? {
        synchronized(launchConfigLock) {
            val existing = readLaunchConfigFromDisk()
            if (existing != null) return existing
        }

        val probed = probeLaunchConfig(baseEnv) ?: return null
        synchronized(launchConfigLock) {
            writeLaunchConfigToDisk(probed)
        }
        return probed
    }

    suspend fun execute(
        command: List<String>,
        workDir: String = "/",
        env: Map<String, String> = emptyMap(),
        timeout: Long? = 60_000,
        stdin: String? = null
    ): PRootResult = withContext(Dispatchers.IO) {
        ensureSupportedRuntime()
        val startTime = System.currentTimeMillis()
        val mappedWorkDir = validateAndMapWorkDir(workDir)

        val sessionLogger = PRootSessionLogger.create(context)

        val prootCommand = buildPRootCommandLine(command, mappedWorkDir)

        // 构建环境变量
        val envMap = mutableMapOf<String, String>()
        envMap["LD_LIBRARY_PATH"] = listOfNotNull(nativeLibDir.takeIf { it.isNotBlank() })
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
        applyEnvironment(envMap, env)
        // 设置工作目录（init-proot.sh 需要）
        envMap["WORK_DIR"] = mappedWorkDir
        // 不把 LD_PRELOAD 直接带进外层 /system/bin/sh，实际 preload 由 init-proot.sh
        // 在最终 exec proot/linker 前按启动模式注入。
        envMap.remove("LD_PRELOAD")

        sessionLogger?.apply {
            envMap["PROOT_SESSION_LOG_FILE"] = sessionFile.absolutePath
            envMap["PROOT_ERROR_LOG_FILE"] = errorFile.absolutePath
            writeCommand(command.joinToString(" "))
            writeInfo("workDir=${envMap["WORK_DIR"]}")
        }

        // 选择并缓存可用的启动方式（避免在不同 ROM/架构上反复踩坑）
        // Android 15+ 必须使用 nativeLibraryDir 下的 libproot.so
        val launchConfig = if (env.containsKey("PROOT_LAUNCH_MODE")) {
            null
        } else {
            ensureLaunchConfig(envMap)
        }
        launchConfig?.let { cfg ->
            applyLaunchConfig(envMap, cfg, overwrite = true)
        }

        // 转换为环境变量数组
        val envArray = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

        Timber.tag("PRootManager").d("Executing command: %s", prootCommand.joinToString(" "))
        Timber.tag("PRootManager").d("LD_LIBRARY_PATH: %s", envMap["LD_LIBRARY_PATH"])
        Timber.tag("PRootManager").d("PROOT_LOADER: %s", envMap["PROOT_LOADER"])

        sessionLogger?.writeInfo("proot=${prootCommand.joinToString(" ")}")

        val process = try {
            // 使用 Runtime.exec() 直接执行，避免 ProcessBuilder 可能的 shell 包装
            startHostProcess(prootCommand.toTypedArray(), envArray)
        } catch (e: IOException) {
            Timber.tag("PRootManager").e(e, "Failed to start proot process")
            return@withContext PRootResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to start PRoot process: ${e.message}\nproot=${prootBinary.absolutePath}\nLD_LIBRARY_PATH=${envMap["LD_LIBRARY_PATH"]}",
                durationMs = System.currentTimeMillis() - startTime,
                timedOut = false
            )
        }

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { process.destroyForcibly() }
            }
        }

        try {
            stdin?.let { input ->
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(input)
                }
            } ?: process.outputStream.close()

            val result: PRootResult? = when (timeout) {
                null -> {
                    // 并行读取 stdout 和 stderr，避免死锁
                    val stdoutBuilder = StringBuilder()
                    val stderrBuilder = StringBuilder()

                    val stdoutThread = Thread {
                        try {
                            process.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    synchronized(stdoutBuilder) {
                                        if (stdoutBuilder.isNotEmpty()) stdoutBuilder.append('\n')
                                        stdoutBuilder.append(line)
                                    }
                                    sessionLogger?.writeStdout(line)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.d(e, "PRootManager: stream reader finished")
                        }
                    }

                    val stderrThread = Thread {
                        try {
                            process.errorStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    synchronized(stderrBuilder) {
                                        if (stderrBuilder.isNotEmpty()) stderrBuilder.append('\n')
                                        stderrBuilder.append(line)
                                    }
                                    sessionLogger?.writeStderr(line)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.d(e, "PRootManager: stream reader finished")
                        }
                    }

                    stdoutThread.start()
                    stderrThread.start()

                    val exitCode = process.waitFor()
                    stdoutThread.join()
                    stderrThread.join()

                    PRootResult(
                        exitCode = exitCode,
                        stdout = synchronized(stdoutBuilder) { stdoutBuilder.toString() },
                        stderr = synchronized(stderrBuilder) { stderrBuilder.toString() },
                        durationMs = System.currentTimeMillis() - startTime,
                        timedOut = false
                    )
                }

                else -> withTimeoutOrNull(timeout) {
                    // 并行读取 stdout 和 stderr，避免死锁
                    val stdoutBuilder = StringBuilder()
                    val stderrBuilder = StringBuilder()

                    val stdoutThread = Thread {
                        try {
                            process.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    synchronized(stdoutBuilder) {
                                        if (stdoutBuilder.isNotEmpty()) stdoutBuilder.append('\n')
                                        stdoutBuilder.append(line)
                                    }
                                    sessionLogger?.writeStdout(line)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.d(e, "PRootManager: stream reader finished")
                        }
                    }

                    val stderrThread = Thread {
                        try {
                            process.errorStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    synchronized(stderrBuilder) {
                                        if (stderrBuilder.isNotEmpty()) stderrBuilder.append('\n')
                                        stderrBuilder.append(line)
                                    }
                                    sessionLogger?.writeStderr(line)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.d(e, "PRootManager: stream reader finished")
                        }
                    }

                    stdoutThread.start()
                    stderrThread.start()

                    val exitCode = process.waitFor()
                    stdoutThread.join()
                    stderrThread.join()

                    PRootResult(
                        exitCode = exitCode,
                        stdout = synchronized(stdoutBuilder) { stdoutBuilder.toString() },
                        stderr = synchronized(stderrBuilder) { stderrBuilder.toString() },
                        durationMs = System.currentTimeMillis() - startTime,
                        timedOut = false
                    )
                }
            }

            val finalResult = result ?: run {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                PRootResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Process timed out after ${timeout}ms",
                    durationMs = System.currentTimeMillis() - startTime,
                    timedOut = true
                )
            }

            if (env[ENV_TINA_PROOT_LAUNCH_CONFIG_RETRY] != "1" &&
                invalidateLaunchConfigIfNeeded(finalResult, launchConfig)
            ) {
                sessionLogger?.writeInfo("auto-retry: cleared stale proot launch config")
                val retryEnv = env + (ENV_TINA_PROOT_LAUNCH_CONFIG_RETRY to "1")
                return@withContext execute(
                    command = command,
                    workDir = workDir,
                    env = retryEnv,
                    timeout = timeout,
                    stdin = stdin
                )
            }

            if (shouldAutoRetryWithCompat(finalResult, env)) {
                sessionLogger?.writeInfo("auto-retry: detected proot runtime crash, retry with PROOT_NO_SECCOMP=1")
                Timber.tag("PRootManager").w("Detected proot runtime crash; retrying with compat mode (PROOT_NO_SECCOMP=1)")

                // 若用户未强制关闭 compat，则在本实例内记忆为“需要 compat”，避免后续反复崩溃。
                if (forceCompatMode == null) {
                    forceCompatMode = true
                }

                val retryEnv = env + buildCompatOverrideEnv()
                return@withContext execute(
                    command = command,
                    workDir = workDir,
                    env = retryEnv,
                    timeout = timeout,
                    stdin = stdin
                )
            }

            sessionLogger?.writeExit(finalResult.exitCode, finalResult.timedOut, finalResult.durationMs)
            finalResult
        } catch (e: CancellationException) {
            runCatching { process.destroyForcibly() }
            throw e
        } finally {
            sessionLogger?.close()
            cancelHandle?.dispose()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    /**
     * 执行命令并实时输出（支持可选超时）
     *
     * 用于运行用户程序，支持无限循环、长时间运行的程序
     * 程序会一直运行直到自然结束或被手动停止
     *
     * @param timeout 可选超时（毫秒）；为 null 时不限制
     * @param onProcessStarted 进程启动后的回调，传递 Process 对象用于外部管理（如停止）
     */
    suspend fun executeWithOutput(
        command: List<String>,
        workDir: String = "/",
        env: Map<String, String> = emptyMap(),
        timeout: Long? = null,
        onProcessStarted: ((Process) -> Unit)? = null,
        onOutput: (String) -> Unit = {}
    ): PRootResult = withContext(Dispatchers.IO) {
        ensureSupportedRuntime()
        val startTime = System.currentTimeMillis()

        val sessionLogger = PRootSessionLogger.create(context)

        val prootCommand = buildPRootCommandLine(command, workDir)

        // 构建环境变量
        val envMap = mutableMapOf<String, String>()
        envMap["LD_LIBRARY_PATH"] = listOfNotNull(nativeLibDir.takeIf { it.isNotBlank() })
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
        applyEnvironment(envMap, env)
        // 设置工作目录（init-proot.sh 需要）
        envMap["WORK_DIR"] = toGuestPath(workDir)
        // 不把 LD_PRELOAD 直接带进外层 /system/bin/sh，实际 preload 由 init-proot.sh
        // 在最终 exec proot/linker 前按启动模式注入。
        envMap.remove("LD_PRELOAD")

        sessionLogger?.apply {
            envMap["PROOT_SESSION_LOG_FILE"] = sessionFile.absolutePath
            envMap["PROOT_ERROR_LOG_FILE"] = errorFile.absolutePath
            writeCommand(command.joinToString(" "))
            writeInfo("workDir=${envMap["WORK_DIR"]}")
            writeInfo("proot=${prootCommand.joinToString(" ")}")
        }

        // Android 15+ 必须使用 nativeLibraryDir 下的 libproot.so
        val launchConfig = if (env.containsKey("PROOT_LAUNCH_MODE")) {
            null
        } else {
            ensureLaunchConfig(envMap)
        }
        launchConfig?.let { cfg ->
            applyLaunchConfig(envMap, cfg, overwrite = true)
        }

        val envArray = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

        val process = try {
            startHostProcess(prootCommand.toTypedArray(), envArray)
        } catch (e: IOException) {
            Timber.tag("PRootManager").e(e, "Failed to start proot process")
            return@withContext PRootResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to start PRoot process: ${e.message}\nproot=${prootBinary.absolutePath}",
                durationMs = System.currentTimeMillis() - startTime,
                timedOut = false
            )
        }
        process.outputStream.close()

        // 通知外部进程已启动
        onProcessStarted?.invoke(process)

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { process.destroyForcibly() }
            }
        }

        try {
            val finalResult = coroutineScope {
                val stdoutTailLines = ArrayDeque<String>(OUTPUT_TAIL_MAX_LINES)
                val stderrTailLines = ArrayDeque<String>(OUTPUT_TAIL_MAX_LINES)

                val stdoutJob = launch(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (stdoutTailLines.size >= OUTPUT_TAIL_MAX_LINES) {
                                    stdoutTailLines.removeFirst()
                                }
                                stdoutTailLines.addLast(line)
                                sessionLogger?.writeStdout(line)
                                onOutput(line)
                            }
                        }
                    } catch (_: Exception) {
                        // 进程被强制结束时，流可能抛异常；忽略即可
                    }
                }

                val stderrJob = launch(Dispatchers.IO) {
                    try {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (stderrTailLines.size >= OUTPUT_TAIL_MAX_LINES) {
                                    stderrTailLines.removeFirst()
                                }
                                stderrTailLines.addLast(line)
                                sessionLogger?.writeStderr(line)
                                onOutput(line)
                            }
                        }
                    } catch (_: Exception) {
                        // 进程被强制结束时，流可能抛异常；忽略即可
                    }
                }

                val (exitCode, timedOut) = when (timeout) {
                    null -> {
                        val code = process.waitFor()
                        stdoutJob.join()
                        stderrJob.join()
                        code to false
                    }

                    else -> {
                        val finished = waitForExitWithTimeout(process, timeout)

                        if (!finished) {
                            process.destroyForcibly()
                            process.waitFor(1, TimeUnit.SECONDS)
                            runCatching { process.inputStream.close() }
                            runCatching { process.errorStream.close() }
                        }

                        stdoutJob.join()
                        stderrJob.join()
                        (if (finished) process.exitValue() else -1) to !finished
                    }
                }

                PRootResult(
                    exitCode = exitCode,
                    stdout = stdoutTailLines.joinToString("\n"),
                    stderr = buildString {
                        if (stderrTailLines.isNotEmpty()) append(stderrTailLines.joinToString("\n"))
                        if (timedOut) {
                            if (isNotEmpty()) appendLine()
                            append("Process timed out after ${timeout}ms")
                        }
                    },
                    durationMs = System.currentTimeMillis() - startTime,
                    timedOut = timedOut
                )
            }

            if (env[ENV_TINA_PROOT_LAUNCH_CONFIG_RETRY] != "1" &&
                invalidateLaunchConfigIfNeeded(finalResult, launchConfig)
            ) {
                sessionLogger?.writeInfo("auto-retry: cleared stale proot launch config")
                onOutput("[TinaIDE] PRoot launch mode changed; retrying...")
                val retryEnv = env + (ENV_TINA_PROOT_LAUNCH_CONFIG_RETRY to "1")
                return@withContext executeWithOutput(
                    command = command,
                    workDir = workDir,
                    env = retryEnv,
                    timeout = timeout,
                    onProcessStarted = onProcessStarted,
                    onOutput = onOutput
                )
            }

            if (shouldAutoRetryWithCompat(finalResult, env)) {
                sessionLogger?.writeInfo("auto-retry: detected proot runtime crash, retry with PROOT_NO_SECCOMP=1")
                Timber.tag("PRootManager").w("Detected proot runtime crash; retrying with compat mode (PROOT_NO_SECCOMP=1)")
                onOutput("[TinaIDE] Detected PRoot runtime compatibility issue; retrying with compat mode...")

                if (forceCompatMode == null) {
                    forceCompatMode = true
                }

                val retryEnv = env + buildCompatOverrideEnv()
                return@withContext executeWithOutput(
                    command = command,
                    workDir = workDir,
                    env = retryEnv,
                    timeout = timeout,
                    onProcessStarted = onProcessStarted,
                    onOutput = onOutput
                )
            }

            sessionLogger?.writeExit(finalResult.exitCode, finalResult.timedOut, finalResult.durationMs)
            finalResult
        } catch (e: CancellationException) {
            runCatching { process.destroyForcibly() }
            throw e
        } finally {
            sessionLogger?.close()
            cancelHandle?.dispose()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun waitForExitWithTimeout(process: Process, timeoutMs: Long): Boolean {
        return try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun shouldAutoRetryWithCompat(result: PRootResult, extraEnv: Map<String, String>): Boolean {
        if (result.exitCode == 0 || result.timedOut) return false
        if (forceCompatMode == false) return false
        if (extraEnv.containsKey("PROOT_NO_SECCOMP")) return false

        val lower = result.combinedOutput.lowercase()

        // SIGSEGV：常见表现为 “terminated with signal 11” 或 exitCode=139
        if (result.exitCode == 139) return true
        if (lower.contains("terminated with signal 11") || lower.contains("signal 11") || lower.contains("sigsegv")) return true

        // seccomp / syscall 兼容性：fork()/execve()/clone() 可能返回 ENOSYS。
        if (lower.contains("function not implemented") &&
            (lower.contains("fork") || lower.contains("execve") || lower.contains("clone") || lower.contains("posix_spawn"))
        ) {
            return true
        }
        if (lower.contains("en osys") || lower.contains("enosys")) return true

        return false
    }

    private fun buildCompatOverrideEnv(): Map<String, String> = mapOf(
        "PROOT_NO_SECCOMP" to "1",
        "KERNEL_RELEASE" to getEffectiveKernelRelease(compatModeEnabled = true),
    )

    fun startInteractive(
        command: List<String>,
        workDir: String = "/",
        extraEnv: Map<String, String> = emptyMap()
    ): InteractiveProcess {
        ensureSupportedRuntime()
        val mappedWorkDir = validateAndMapWorkDir(workDir)
        val prootCommand = buildPRootCommandLine(command, mappedWorkDir)

        // 交互式进程和普通 execute() 共用同一套环境约定。
        val envMap = buildExecEnvironment(extraEnv).toMutableMap()
        // 设置工作目录（init-proot.sh 需要）
        envMap["WORK_DIR"] = mappedWorkDir

        val envArray = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

        Timber.tag("PRootManager").d("Starting interactive proot: %s", prootCommand.joinToString(" "))

        val process = startHostProcess(prootCommand.toTypedArray(), envArray)
        return InteractiveProcessImpl(process)
    }

    private fun startHostProcess(
        command: Array<String>,
        environment: Array<String>,
    ): Process {
        val hostWorkingDirectory = context.filesDir.apply { mkdirs() }
        return Runtime.getRuntime().exec(command, environment, hostWorkingDirectory)
    }

    fun buildPRootCommandLine(
        command: List<String>,
        workDir: String,
    ): List<String> {
        val filesDir = context.filesDir.absolutePath

        File(rootfsPath, "projects").mkdirs()
        // workDir 既可是 host 路径也可是 guest 路径；统一映射后做边界校验。
        val mappedWorkDir = validateAndMapWorkDir(workDir)
        val kernelRelease = getEffectiveKernelRelease()

        // 构建 proot 参数
        val prootArgs = buildList {
            add("--rootfs=$rootfsPath")
            add("--cwd=$mappedWorkDir")
            add("-0")

            add("--bind=/dev:/dev")
            add("--bind=/dev/urandom:/dev/random")
            add("--bind=/proc:/proc")
            add("--bind=/sys:/sys")

            if (File("/sdcard").exists()) add("--bind=/sdcard:/sdcard")
            if (File("/storage").exists()) add("--bind=/storage:/storage")

            add("--bind=/data:/data")
            add("--bind=$filesDir:$filesDir")

            // 将私有源码目录映射为 /projects，将私有工作区映射为 /workspace
            add("--bind=${projectsHostDir.absolutePath}:/projects")

            if (workspaceHostDir.exists() || workspaceHostDir.mkdirs()) {
                add("--bind=${workspaceHostDir.absolutePath}:/workspace")
            }

            val shmDir = File(rootfsPath, "tmp").apply { mkdirs() }
            add("--bind=${shmDir.absolutePath}:/dev/shm")

            listOf("/apex", "/system", "/vendor", "/product").forEach { systemPath ->
                if (File(systemPath).exists()) add("--bind=$systemPath:$systemPath")
            }

            // 兼容性说明：
            // - 该值只影响 guest 的 uname() 返回，不会改变 host 内核。
            // - normal 模式最多收敛到 5.4；compat 模式最多收敛到 4.14（更保守）。
            add("--kernel-release=$kernelRelease")
            add("--link2symlink")
            add("--sysvipc")
            add("--kill-on-exit")

            addAll(command)
        }

        // 诊断日志
        Timber.tag("PRootManager").d("proot path: %s", prootBinary.absolutePath)
        Timber.tag("PRootManager").d("proot exists: %s", prootBinary.exists())
        Timber.tag("PRootManager").d("proot canExecute: %s", prootBinary.canExecute())
        Timber.tag("PRootManager").d("proot canRead: %s", prootBinary.canRead())
        Timber.tag("PRootManager").d("proot size: %d bytes", prootBinary.length())

        // 验证 ELF 魔数
        try {
            val elfMagic = prootBinary.inputStream().use { stream ->
                val header = ByteArray(4)
                stream.read(header)
                header.joinToString("") { String.format("%02X", it) }
            }
            Timber.tag("PRootManager").d("proot ELF magic: %s (expected: 7F454C46)", elfMagic)
            if (elfMagic != "7F454C46") {
                Timber.tag("PRootManager").e("ERROR: proot is NOT a valid ELF file!")
            }
        } catch (e: Exception) {
            Timber.tag("PRootManager").e(e, "Failed to read proot ELF header")
        }

        // 检查 proot-loader
        Timber.tag("PRootManager").d("proot-loader path: %s", prootLoaderPath.absolutePath)
        Timber.tag("PRootManager").d("proot-loader exists: %s", prootLoaderPath.exists())

        // 确保 init 脚本存在
        ensureInitScript()

        // 使用 /system/bin/sh 执行 init-proot.sh 脚本（参考 ReTerminal 的实现）
        // 在 Android 上，直接执行 ELF 文件或通过 Java 调用 linker64 都可能失败
        // 但通过 shell 脚本调用 linker64 可以正常工作
        Timber.tag("PRootManager").d("Using init script: %s", initProotScript.absolutePath)
        Timber.tag("PRootManager").d("Work dir: %s", mappedWorkDir)

        // 注意：prootArgs 已经不需要了，因为 init-proot.sh 会自己构建参数
        // 我们只需要传递要执行的命令
        return listOf("/system/bin/sh", initProotScript.absolutePath) + command
    }

    /**
     * 映射并校验 guest 工作目录，供 execute / startInteractive / buildPRootCommandLine 共用。
     */
    private fun validateAndMapWorkDir(workDir: String): String {
        val mapped = toGuestPath(workDir)
        pathValidator.validateGuestWorkDir(mapped)
        return mapped
    }

    /**
     * 将 host 路径转换为 PRoot guest 路径（与 buildPRootCommandLine 的 bind 规则保持一致）。
     *
     * 规则：
     * - `<privateProjectsRoot>/xxx` → `/projects/xxx`
     * - `<workspaceRoot>/xxx` → `/workspace/xxx`
     * - 已是 `/projects`/`/workspace` 则原样返回
     * - 其它路径原样返回（由调用方保证在 guest 中可访问）
     */
    fun toGuestPath(path: String): String {
        val normalized = path.trim().ifEmpty { "/" }

        if (normalized == "/workspace" || normalized.startsWith("/workspace/")) return normalized
        if (normalized == "/projects" || normalized.startsWith("/projects/")) return normalized

        // 使用规范化路径进行比较，解决 /data/user/0/ 和 /data/data/ 别名问题
        val canonicalPath = try {
            File(normalized).canonicalPath
        } catch (e: Exception) {
            Timber.d(e, "PRootManager: canonicalPath failed for: %s", normalized)
            normalized
        }.normalizeHostPathForGuestMapping()

        val projectsRoot = try {
            projectsHostDir.canonicalPath
        } catch (e: Exception) {
            Timber.d(e, "PRootManager: canonicalPath failed for projectsHostDir")
            projectsHostDir.absolutePath
        }.normalizeHostPathForGuestMapping()
        val workspaceRoot = try {
            workspaceHostDir.canonicalPath
        } catch (e: Exception) {
            Timber.d(e, "PRootManager: canonicalPath failed for workspaceHostDir")
            workspaceHostDir.absolutePath
        }.normalizeHostPathForGuestMapping()

        return when {
            canonicalPath == projectsRoot -> "/projects"
            canonicalPath.startsWith("$projectsRoot/") -> "/projects/" + canonicalPath.removePrefix("$projectsRoot/")

            canonicalPath == workspaceRoot -> "/workspace"
            canonicalPath.startsWith("$workspaceRoot/") -> "/workspace/" + canonicalPath.removePrefix("$workspaceRoot/")

            else -> normalized
        }
    }

    private fun String.normalizeHostPathForGuestMapping(): String = replace('\\', '/')

    private fun applyEnvironment(
        targetEnv: MutableMap<String, String>,
        extraEnv: Map<String, String>,
    ) {
        val prootTmpDir = com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(
            com.wuxianggujun.tinaide.storage.ProjectPaths.getPRootTmpRoot(context)
        )

        val prootLogDir = com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(
            com.wuxianggujun.tinaide.storage.ProjectPaths.getPRootLogsRoot(context)
        )

        // 基本环境变量（用于 guest 环境）
        targetEnv["HOME"] = "/root"
        targetEnv["TERM"] = "xterm-256color"
        targetEnv["LANG"] = "C.UTF-8"
        targetEnv["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${System.getenv("PATH") ?: ""}"
        targetEnv["TMPDIR"] = "/tmp"
        targetEnv["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        targetEnv["PROOT_LOG_DIR"] = prootLogDir.absolutePath

        // init-proot.sh 脚本需要的环境变量
        targetEnv["NATIVE_LIB_DIR"] = nativeLibDir
        targetEnv["ROOTFS_PATH"] = rootfsPath

        // Android 15+ 必须使用 nativeLibraryDir 下的 libproot.so
        // 私有目录（/data/data/...）有 W^X 保护，不允许执行
        if (!targetEnv.containsKey("PROOT_BIN")) {
            targetEnv["PROOT_BIN"] = prootBinary.absolutePath
        }

        // 工作空间目录映射（host 路径 -> guest 路径）
        // WORKSPACE_HOST_DIR: Android 上的真实路径
        // 在 init-proot.sh 中会绑定为 /workspace
        targetEnv["WORKSPACE_HOST_DIR"] = workspaceHostDir.absolutePath
        targetEnv["PROJECTS_HOST_DIR"] = projectsHostDir.absolutePath

        // 动态链接器路径
        val linker = if (File("/system/bin/linker64").exists()) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }
        targetEnv["LINKER"] = linker

        // 兼容性策略（KISS）：
        // - 默认：不设置 PROOT_NO_SECCOMP（保持 seccomp 加速开启）。
        // - 仅当 host 内核过旧（< 4.14）且未显式覆盖时，才注入 PROOT_NO_SECCOMP=1 兜底。
        //   说明：Android 10+（Linux 4.14+）一般完整支持 seccomp，在新内核上禁用反而可能触发 syscall 兼容问题。
        val compatModeEnabled = isCompatModeEnabled()
        if (compatModeEnabled && !targetEnv.containsKey("PROOT_NO_SECCOMP") && !extraEnv.containsKey("PROOT_NO_SECCOMP")) {
            targetEnv["PROOT_NO_SECCOMP"] = "1"
        }

        // kernel release：仅影响 guest 内 uname()，用于兼容性与行为收敛（例如避免部分程序倾向使用 clone3）。
        if (!targetEnv.containsKey("KERNEL_RELEASE") && !extraEnv.containsKey("KERNEL_RELEASE")) {
            targetEnv["KERNEL_RELEASE"] = getEffectiveKernelRelease(compatModeEnabled)
        }

        // PROOT_LOADER 环境变量：指定 proot 内部使用的 ELF loader 路径
        // 与 ReTerminal 的实现方式一致
        if (prootLoaderPath.exists()) {
            targetEnv["PROOT_LOADER"] = prootLoaderPath.absolutePath
        }

        // PROOT_LOADER32 环境变量：指定 proot 内部使用的 32 位 ELF loader 路径
        // 用于在 64 位系统上运行 32 位程序
        if (prootLoader32Path.exists()) {
            targetEnv["PROOT_LOADER32"] = prootLoader32Path.absolutePath
        }

        if (shouldEnableTinaExecBridge(extraEnv)) {
            applyTinaExecBridgeEnvironment(targetEnv)
        }

        extraEnv.forEach { (key, value) -> targetEnv[key] = value }

        logCompatOnceIfNeeded(
            kernelRelease = targetEnv["KERNEL_RELEASE"].orEmpty(),
            prootNoSeccomp = targetEnv["PROOT_NO_SECCOMP"]
        )
    }

    private fun applyLaunchConfig(
        targetEnv: MutableMap<String, String>,
        config: LaunchConfig,
        overwrite: Boolean,
    ) {
        if (overwrite || !targetEnv.containsKey("PROOT_LAUNCH_MODE")) {
            targetEnv["PROOT_LAUNCH_MODE"] = config.mode
        }
        if (overwrite || !targetEnv.containsKey("PROOT_BIN")) {
            targetEnv["PROOT_BIN"] = prootBinary.absolutePath
        }
    }

    private fun applyCachedLaunchConfig(targetEnv: MutableMap<String, String>) {
        val cached = readLaunchConfigFromDisk() ?: return
        applyLaunchConfig(targetEnv, cached, overwrite = false)
    }

    /**
     * 为 init-proot.sh 准备 tina-exec 环境。
     *
     * 说明：
     * - 不在 Java 外层直接注入 LD_PRELOAD，避免影响 /system/bin/sh 本身
     * - 仅把 direct/linker 两种 preload 路径和基础环境透传给脚本
     * - 由脚本在最终 exec proot/linker 前按实际启动模式设置 LD_PRELOAD
     */
    private fun applyTinaExecBridgeEnvironment(targetEnv: MutableMap<String, String>) {
        val bridgeEnv = mutableMapOf<String, String>()
        TinaExecRuntime.applyLdPreload(
            environment = bridgeEnv,
            context = context,
            mode = TinaExecPreloadMode.DIRECT,
            systemLinkerMode = TinaExecSystemLinkerMode.ENABLE
        )

        val directLdPreload = bridgeEnv.remove("LD_PRELOAD")
        bridgeEnv.forEach { (key, value) ->
            targetEnv.putIfAbsent(key, value)
        }
        directLdPreload?.takeIf { it.isNotBlank() }?.let { value ->
            targetEnv.putIfAbsent(ENV_TINA_PROOT_LD_PRELOAD_DIRECT, value)
        }

        TinaExecRuntime.resolveLibraryPath(context, TinaExecPreloadMode.LINKER)
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                targetEnv.putIfAbsent(ENV_TINA_PROOT_LD_PRELOAD_LINKER, value)
            }
    }

    private fun shouldEnableTinaExecBridge(extraEnv: Map<String, String>): Boolean = extraEnv[ENV_TINA_PROOT_ENABLE_TINA_EXEC] == "1"

    fun buildExecEnvironment(extraEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["LD_LIBRARY_PATH"] = listOfNotNull(nativeLibDir.takeIf { it.isNotBlank() })
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
        applyEnvironment(env, extraEnv)
        applyCachedLaunchConfig(env)
        // 不把 LD_PRELOAD 直接带进外层 /system/bin/sh，实际 preload 由 init-proot.sh
        // 在最终 exec proot/linker 前按启动模式注入。
        env.remove("LD_PRELOAD")
        val filesDir = context.filesDir.absolutePath
        // HOME 保持为 /root（由 applyEnvironment 设置），不要覆盖为私有路径
        env["TINA_BASE"] = filesDir
        // 统一显示相对风格路径：/projects/nih -> projects/nih（不暴露 /data/data/...）
        // 依赖 POSIX 参数展开：bash/ash/sh 均可用
        env["PS1"] = "\${PWD#/}# "
        return env
    }

    private fun getEffectiveKernelRelease(): String = getEffectiveKernelRelease(isCompatModeEnabled())

    private fun getEffectiveKernelRelease(compatModeEnabled: Boolean): String {
        // KISS：只做两档收敛：
        // - normal：最多伪装到 5.4（常见稳定基线）
        // - compat：最多伪装到 4.14（Android 10 常见基线，且 < 5.3 避免 guest 侧倾向 clone3）
        val cap = if (compatModeEnabled) KernelVersion(4, 14) else KernelVersion(5, 4)
        val host = readHostKernelVersion()
        val chosen = if (host != null && host.isLessThan(cap)) host else cap
        return "${chosen.major}.${chosen.minor}.0"
    }

    private fun logCompatOnceIfNeeded(kernelRelease: String, prootNoSeccomp: String?) {
        if (!isCompatModeEnabled()) return
        if (!compatLoggedOnce.compareAndSet(false, true)) return

        val hostKernelRelease = readHostKernelRelease()
        Timber.tag("PRootManager").w(
            "PRoot compat mode enabled: PROOT_NO_SECCOMP=%s, kernelRelease=%s, hostKernel=%s, proot=%s",
            (prootNoSeccomp ?: "<unset>"),
            kernelRelease,
            (hostKernelRelease ?: "<unknown>"),
            prootBinary.absolutePath
        )
    }

    private fun isCompatModeEnabled(): Boolean = forceCompatMode ?: shouldDisableSeccompByDefault()

    fun isCompatModeActive(): Boolean = isCompatModeEnabled()

    private fun shouldDisableSeccompByDefault(): Boolean {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) return true

        val host = readHostKernelVersion() ?: return false
        // Android 10+ 基线通常是 Linux 4.14+；仅在明显更老的内核上才默认关闭 seccomp 加速兜底。
        return host.major < 4 || (host.major == 4 && host.minor < 14)
    }

    private fun readHostKernelRelease(): String? = runCatching {
        File("/proc/sys/kernel/osrelease").readText(Charsets.UTF_8).trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun readHostKernelVersion(): KernelVersion? {
        val release = readHostKernelRelease() ?: return null
        val m = Regex("""^(\d+)\.(\d+)""").find(release) ?: return null
        val major = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minor = m.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return KernelVersion(major, minor)
    }

    fun existsInRootfs(pathInRootfs: String): Boolean = RootfsFileChecks.exists(File(rootfsPath), pathInRootfs)

    suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetPath = if (path.startsWith("/")) {
                File(rootfsPath, path.removePrefix("/"))
            } else {
                File(rootfsPath, path)
            }
            targetPath.parentFile?.mkdirs()
            targetPath.writeText(content)
            true
        } catch (e: Exception) {
            Timber.w(e, "PRootManager: writeFile failed: %s", path)
            false
        }
    }

    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            RootfsFileChecks.readTextOrNull(File(rootfsPath), path)
        } catch (e: Exception) {
            Timber.w(e, "PRootManager: readFile failed: %s", path)
            null
        }
    }

    companion object {
        private const val ENV_TINA_PROOT_LD_PRELOAD_DIRECT = "TINA_PROOT__LD_PRELOAD_DIRECT"
        private const val ENV_TINA_PROOT_LD_PRELOAD_LINKER = "TINA_PROOT__LD_PRELOAD_LINKER"
        private const val ENV_TINA_PROOT_ENABLE_TINA_EXEC = "TINA_PROOT_ENABLE_TINA_EXEC"
        private const val ENV_TINA_PROOT_LAUNCH_CONFIG_RETRY = "TINA_PROOT_LAUNCH_CONFIG_RETRY"
        private const val LAUNCH_CONFIG_VERSION = "guest-child-probe-v2"
        private const val OUTPUT_TAIL_MAX_LINES = 200

        fun getCurrentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }
}

private data class KernelVersion(
    val major: Int,
    val minor: Int,
) {
    fun isLessThan(other: KernelVersion): Boolean = major < other.major || (major == other.major && minor < other.minor)
}

interface InteractiveProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream

    fun isRunning(): Boolean
    fun waitFor(timeout: Long = 0): Int
    fun destroy()
}

internal class InteractiveProcessImpl(
    private val process: Process
) : InteractiveProcess {

    override val stdin: OutputStream
        get() = process.outputStream

    override val stdout: InputStream
        get() = process.inputStream

    override val stderr: InputStream
        get() = process.errorStream

    override fun isRunning(): Boolean = process.isAlive

    override fun waitFor(timeout: Long): Int = if (timeout > 0) {
        if (process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            -1
        }
    } else {
        process.waitFor()
    }

    override fun destroy() {
        process.destroyForcibly()
    }
}

/**
 * 架构不匹配异常（例如 x86_64 设备运行 arm64 APK）
 */
class AbiMismatchException(message: String) : RuntimeException(message)
