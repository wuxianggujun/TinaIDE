package com.wuxianggujun.tinaide.core.compile

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import timber.log.Timber

/** Owns a separate POSIX session so cancelling vcpkg also stops its CMake/compiler children. */
internal class NativeBuildProcessGroup(command: List<String>, stateDir: File) {
    private val pidFile = File(stateDir, "process-group.pid")

    val command: List<String> = listOf(
        "/system/bin/toybox", "setsid", "/system/bin/sh", "-c",
        // Arguments remain separate argv entries, including paths with shell metacharacters.
        "printf '%s' \"\$\$\" > \"\$1\" || exit 1; shift; exec \"\$@\"",
        "tina-build-group", pidFile.absolutePath,
    ) + command

    fun terminate() {
        val groupId = try {
            pidFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.toIntOrNull()
        } catch (error: Exception) {
            Timber.tag("NativeBuildProcessGroup").w(error, "Cannot read build process group")
            null
        } ?: return
        if (groupId <= 1 || groupId == android.os.Process.myPid()) return
        try {
            Os.kill(-groupId, OsConstants.SIGKILL)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) {
                Timber.tag("NativeBuildProcessGroup").w(error, "Cannot terminate build process group %d", groupId)
            }
        }
    }
}
