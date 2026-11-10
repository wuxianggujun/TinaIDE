package com.wuxianggujun.tinaide.core.compile.cmake

import com.wuxianggujun.tinaide.core.packages.VcpkgRuntime
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** vcpkg versioning needs Git objects even when the catalog was obtained as a source archive. */
internal object VcpkgGitBootstrap {
    private val mutex = Mutex()
    suspend fun prepare(runtime: VcpkgRuntime, project: File, execute: suspend (List<String>) -> Unit) = mutex.withLock {
        val revision = runtime.root.name
        require(revision.matches(Regex("[0-9a-f]{40}")))
        val git = File(runtime.environment.getValue("PATH"), "git").absolutePath
        suspend fun run(vararg arguments: String) = execute(listOf("/system/bin/sh", git, "-C", runtime.root.absolutePath) + arguments)
        val ready = File(runtime.root, ".git/tina-ready")
        if (!ready.isFile) {
            run("init", "--initial-branch=master")
            run("config", "remote.origin.url", "https://github.com/microsoft/vcpkg.git")
            run("config", "remote.origin.promisor", "true")
            run("config", "remote.origin.partialclonefilter", "blob:none")
            run("fetch", "--filter=blob:none", "--depth=1", "origin", revision)
            // Files already come from the verified source snapshot; populate only Git's HEAD/index.
            run("reset", "--mixed", "FETCH_HEAD")
            ready.writeText(revision, Charsets.UTF_8)
        }
        val manifest = File(project, "vcpkg.json")
        require(manifest.length() <= 1024 * 1024)
        val baseline = JSONObject(manifest.readText(Charsets.UTF_8)).optString("builtin-baseline")
        if (baseline.isNotBlank()) {
            require(baseline.matches(Regex("[0-9a-f]{40}")))
            val marker = File(runtime.root, ".git/tina-baseline-$baseline")
            if (baseline != revision && !marker.exists()) {
                run("fetch", "--filter=blob:none", "--depth=1", "origin", baseline)
                marker.writeText(baseline, Charsets.UTF_8)
            }
        }
    }
}
