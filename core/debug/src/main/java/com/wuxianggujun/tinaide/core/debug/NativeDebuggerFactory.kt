package com.wuxianggujun.tinaide.core.debug

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.proot.InteractiveProcess
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import java.io.File
import java.util.concurrent.TimeUnit

enum class DebugBackend { NATIVE, PROOT }

internal object NativeDebuggerFactory {
    fun create(context: Context, toolchainId: String?): LldbDebugger {
        val bin = AndroidNativeToolchainManager(context).getBinDir(toolchainId)
        val lldb = File(bin, "lldb")
        val server = File(bin, "lldb-server")
        check(lldb.isFile && server.isFile) { Strings.debug_error_native_lldb_unavailable.strOr(context) }
        return LldbDebugger(lldb.absolutePath) { command, directory, extraEnv ->
            val process = NativeExecutableRunner.createProcessBuilder(
                executable = command.first(), args = command.drop(1), workingDir = File(directory),
                nativeLibDir = context.applicationInfo.nativeLibraryDir,
                toolchainBinDir = bin.absolutePath, tmpDir = context.cacheDir.absolutePath,
                homeDir = context.filesDir.absolutePath, tinaExecContext = context,
            ).apply {
                environment().putAll(extraEnv)
                environment()["LLDB_DEBUGSERVER_PATH"] = server.absolutePath
            }.start()
            object : InteractiveProcess {
                override val stdin get() = process.outputStream
                override val stdout get() = process.inputStream
                override val stderr get() = process.errorStream
                override fun isRunning() = process.isAlive
                override fun waitFor(timeout: Long): Int = if (timeout <= 0) process.waitFor() else {
                    if (process.waitFor(timeout, TimeUnit.MILLISECONDS)) process.exitValue() else -1
                }
                override fun destroy() { process.destroyForcibly() }
            }
        }
    }
}
