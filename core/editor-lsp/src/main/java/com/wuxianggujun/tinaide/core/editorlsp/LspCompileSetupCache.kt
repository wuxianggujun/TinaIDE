package com.wuxianggujun.tinaide.core.editorlsp

import android.content.Context
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.lsp.CompileDatabaseProvider
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * compile_commands / compile database 准备结果的缓存与并发合并。
 *
 * 从 [LspEditorManager] 抽出，避免 attach 路径继续堆缓存细节。
 */
internal class LspCompileSetupCache(
    private val scope: CoroutineScope,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider,
    private val resolveRunMode: () -> LinuxRunModePolicy.RunMode,
    private val resolveLanguageId: (File) -> String,
) {
    companion object {
        private const val TAG = "LspCompileSetupCache"
    }

    data class Key(
        val projectHint: String,
        val languageId: String,
        val runMode: LinuxRunModePolicy.RunMode,
        val toolchainId: String,
        val sysrootProfileId: String?,
        val sysrootApiLevel: Int,
        val cppStandardFlag: String,
    )

    data class Setup(
        val prepared: CompileDatabaseProvider.Prepared,
        val compileCommandsDir: File,
    )

    private val stateLock = Any()
    private val mutex = Mutex()
    private val cache = mutableMapOf<Key, Setup>()
    private val tasks = mutableMapOf<Key, Deferred<Setup?>>()
    private var provider: CompileDatabaseProvider? = null

    fun getProvider(context: Context): CompileDatabaseProvider = synchronized(stateLock) {
        provider ?: CompileDatabaseProvider(
            context = context,
            linuxEnvironmentProvider = linuxEnvironmentProvider,
        ).also { provider = it }
    }

    fun clear() {
        synchronized(stateLock) {
            cache.clear()
        }
    }

    fun invalidateForProject(file: File, projectRootPath: String?) {
        val projectHint = resolveProjectHint(file, projectRootPath)
        synchronized(stateLock) {
            cache.keys.removeAll { key -> key.projectHint == projectHint }
        }
    }

    suspend fun resolve(
        context: Context,
        file: File,
        projectRootPath: String?,
    ): Setup? {
        val startedAt = System.nanoTime()
        val compileProvider = getProvider(context)
        val key = buildKey(file, projectRootPath, compileProvider)
        synchronized(stateLock) {
            cache[key]
        }?.let { cached ->
            if (isStillFresh(context, cached)) {
                Timber.tag(TAG).d(
                    "compile setup cache hit for %s (%s) in %dms",
                    file.name,
                    key.projectHint,
                    elapsedMillis(startedAt),
                )
                return cached
            }
            Timber.tag(TAG).i(
                "compile setup cache stale for %s (%s): package fingerprint changed, recomputing",
                file.name,
                key.projectHint,
            )
            synchronized(stateLock) {
                if (cache[key] === cached) {
                    cache.remove(key)
                }
            }
        }

        val task = mutex.withLock {
            synchronized(stateLock) {
                cache[key]
            }?.let { cached -> return cached }

            tasks[key]?.takeIf { it.isActive } ?: scope.async(Dispatchers.IO) {
                val prepared = compileProvider.prepare(file, projectRootPath) ?: return@async null
                val ensured = compileProvider.ensureWithResult(prepared) ?: return@async null
                Setup(
                    prepared = prepared,
                    compileCommandsDir = ensured.compileCommandsDir,
                )
            }.also { deferred ->
                synchronized(stateLock) { tasks[key] = deferred }
            }
        }

        return try {
            task.await()?.also { setup ->
                synchronized(stateLock) { cache[key] = setup }
                Timber.tag(TAG).d(
                    "compile setup ready for %s (%s) in %dms",
                    file.name,
                    key.projectHint,
                    elapsedMillis(startedAt),
                )
            }
        } finally {
            mutex.withLock {
                synchronized(stateLock) {
                    if (tasks[key] === task) {
                        tasks.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun isStillFresh(context: Context, cached: Setup): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val currentProvider = getProvider(context)
            val currentFingerprint = currentProvider.computePackageFingerprint(cached.prepared.workspaceRoot)
            val currentRuntimeIdentity = currentProvider.resolveRuntimeIdentity(cached.prepared.workspaceRoot)
            currentFingerprint == cached.prepared.packageFingerprint &&
                currentRuntimeIdentity.toolchainId == cached.prepared.toolchainId &&
                currentRuntimeIdentity.sysrootProfileId == cached.prepared.sysrootProfileId &&
                currentRuntimeIdentity.sysrootApiLevel == cached.prepared.sysrootApiLevel &&
                resolveCppStandardFlag(cached.prepared.workspaceRoot) == cached.prepared.desiredCppStandard.flag
        }.getOrDefault(true)
    }

    private fun buildKey(
        file: File,
        projectRootPath: String?,
        compileProvider: CompileDatabaseProvider,
    ): Key {
        val workspaceRoot = resolveWorkspaceRoot(file, projectRootPath)
        val projectHint = resolveProjectHint(file, projectRootPath)
        val runtimeIdentity = compileProvider.resolveRuntimeIdentity(workspaceRoot)
        return Key(
            projectHint = projectHint,
            languageId = resolveLanguageId(file),
            runMode = resolveRunMode(),
            toolchainId = runtimeIdentity.toolchainId,
            sysrootProfileId = runtimeIdentity.sysrootProfileId,
            sysrootApiLevel = runtimeIdentity.sysrootApiLevel,
            cppStandardFlag = resolveCppStandardFlag(workspaceRoot),
        )
    }

    private fun resolveProjectHint(file: File, projectRootPath: String?): String {
        val workspaceRoot = resolveWorkspaceRoot(file, projectRootPath)
        return workspaceRoot?.stablePath()
            ?: projectRootPath
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it).stablePath() }
            ?: file.parentFile?.stablePath()
            ?: file.stablePath()
    }

    private fun resolveWorkspaceRoot(file: File, projectRootPath: String?): File? {
        val candidate = projectRootPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

        if (candidate != null) {
            val candidatePath = runCatching { candidate.canonicalPath }.getOrNull()
            val filePath = runCatching { file.canonicalPath }.getOrNull()
            if (candidatePath != null && filePath != null) {
                val inProject = filePath == candidatePath || filePath.startsWith(candidatePath + File.separator)
                if (inProject) return candidate
            }
        }

        return file.parentFile?.takeIf { it.isDirectory }
    }

    private fun resolveCppStandardFlag(projectRoot: File?): String =
        projectRoot
            ?.let { root -> runCatching { ProjectMetadataStore.read(root)?.getCppStandard()?.flag }.getOrNull() }
            ?: CppStandard.DEFAULT.flag

    private fun File.stablePath(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
