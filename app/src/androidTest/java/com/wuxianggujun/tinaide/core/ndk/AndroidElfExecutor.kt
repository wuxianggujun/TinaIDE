package com.wuxianggujun.tinaide.core.ndk

import com.wuxianggujun.tinaide.core.util.AndroidSystemLinker
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidElfExecutor {

    data class ExecResult(
        val exitCode: Int,
        val output: String,
        val usedLinker: Boolean,
    )

    suspend fun exec(
        context: Context,
        executable: File,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workDir: File? = null,
        timeoutMs: Long = 120_000L,
    ): ExecResult = withContext(Dispatchers.IO) {
        val mergedEnv = buildMap {
            putAll(env)
            putIfAbsent("TMPDIR", context.cacheDir.absolutePath)
            putIfAbsent("HOME", context.filesDir.absolutePath)
        }
        val directCommand = listOf(executable.absolutePath) + args
        val linkerCommand = listOf(AndroidSystemLinker.resolve64BitPreferred(), executable.absolutePath) + args

        // 执行策略：
        // 1. 先直接执行（适用于 PIE 可执行文件和静态 PIE 可执行文件）
        // 2. 如果直接执行失败（IOException），再通过 linker64 执行
        //    注意：linker64 只支持 PIE（ET_DYN）格式，不支持非 PIE（ET_EXEC）格式
        //    编译时应使用 -static-pie 代替 -static 以确保兼容性
        runCatching {
            val directEnv = mergedEnv.toMutableMap().apply {
                NativeExecutableRunner.applyRecommendedTinaExec(
                    environment = this,
                    context = context,
                    fullCommand = directCommand
                )
            }
            val (code, out) = runProcess(
                command = directCommand,
                env = directEnv,
                workDir = workDir,
                timeoutMs = timeoutMs
            )
            ExecResult(exitCode = code, output = out, usedLinker = false)
        }.getOrElse { directError ->
            val linkerEnv = mergedEnv.toMutableMap().apply {
                NativeExecutableRunner.applyRecommendedTinaExec(
                    environment = this,
                    context = context,
                    fullCommand = linkerCommand
                )
            }
            val (code, out) = runProcess(
                // Android dynamic linker expects argv[1] to be the absolute path of the target ELF.
                // Do not pass "--" here (some devices treat it as the target path and fail with:
                // "error: expected absolute path: \"--\"").
                command = linkerCommand,
                env = linkerEnv,
                workDir = workDir,
                timeoutMs = timeoutMs
            )
            // 仅当 linker 模式也失败时，才把 direct 的异常信息附带出来（避免误导用户）。
            val combinedOut = if (code == 0) {
                out.trim()
            } else {
                buildString {
                    append(out)
                    if (directError.message?.isNotBlank() == true) {
                        append("\n[direct-exec-error] ").append(directError.message)
                    }
                }.trim()
            }
            ExecResult(exitCode = code, output = combinedOut, usedLinker = true)
        }
    }

    private fun runProcess(
        command: List<String>,
        env: Map<String, String>,
        workDir: File?,
        timeoutMs: Long,
    ): Pair<Int, String> {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                if (workDir != null) directory(workDir)
                environment().putAll(env)
            }
            .start()

        val output = StringBuilder()
        val readerThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                    }
                }
            }
        }.apply {
            name = "AndroidElfExecutor-output"
            isDaemon = true
        }
        readerThread.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IOException("Process timeout (${timeoutMs}ms): ${command.joinToString(" ")}")
        }

        readerThread.join(2_000L)
        return process.exitValue() to output.toString().trim()
    }
}
