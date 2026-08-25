package com.wuxianggujun.tinaide.core.editorlsp

import android.content.Context
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.lsp.CompileDatabaseProvider
import com.wuxianggujun.tinaide.project.ProjectCppStandardResolver
import java.io.File
import kotlinx.coroutines.CancellationException
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

    private data class PendingTask(
        val revision: Long,
        val deferred: Deferred<Setup?>,
    )

    private val stateLock = Any()
    private val mutex = Mutex()
    private val compileDatabaseWriteMutex = Mutex()
    private val cache = mutableMapOf<Key, Setup>()
    private val tasks = mutableMapOf<Key, PendingTask>()
    private var provider: CompileDatabaseProvider? = null
    private var revision: Long = 0L

    fun getProvider(context: Context): CompileDatabaseProvider = synchronized(stateLock) {
        provider ?: CompileDatabaseProvider(
            context = context,
            linuxEnvironmentProvider = linuxEnvironmentProvider,
        ).also { provider = it }
    }

    fun clear() {
        synchronized(stateLock) {
            revision += 1L
            cache.clear()
        }
    }

    fun invalidateForProject(file: File, projectRootPath: String?) {
        val projectHint = resolveProjectHint(file, projectRootPath)
        synchronized(stateLock) {
            revision += 1L
            cache.keys.removeAll { key -> key.projectHint == projectHint }
        }
    }

    suspend fun resolve(
        context: Context,
        file: File,
        projectRootPath: String?,
        cppStandardOverride: String? = null,
        forceRegenerateFallback: Boolean = false,
    ): Setup? {
        val startedAt = System.nanoTime()
        val compileProvider = getProvider(context)
        val resolveRevision = synchronized(stateLock) { revision }
        val key = withContext(Dispatchers.IO) {
            buildKey(file, projectRootPath, compileProvider, cppStandardOverride)
        }
        val cachedSetup = synchronized(stateLock) {
            if (revision != resolveRevision) {
                throw CancellationException("Compile setup invalidated")
            }
            cache[key]
        }
        cachedSetup?.let { cached ->
            if (isStillFresh(context, cached, cppStandardOverride)) {
                val stillCurrent = synchronized(stateLock) { revision == resolveRevision }
                if (stillCurrent) {
                    Timber.tag(TAG).d(
                        "compile setup cache hit for %s (%s) in %dms",
                        file.name,
                        key.projectHint,
                        elapsedMillis(startedAt),
                    )
                    return cached
                }
            }
            Timber.tag(TAG).i(
                "compile setup cache stale for %s (%s): compile inputs changed, recomputing",
                file.name,
                key.projectHint,
            )
            synchronized(stateLock) {
                if (revision == resolveRevision && cache[key] === cached) {
                    cache.remove(key)
                }
            }
        }

        val pendingTask = mutex.withLock {
            synchronized(stateLock) {
                cache[key]
            }?.let { cached -> return cached }

            tasks[key]?.takeIf { task ->
                task.revision == resolveRevision && task.deferred.isActive
            } ?: PendingTask(
                revision = resolveRevision,
                deferred = scope.async(Dispatchers.IO) {
                    compileDatabaseWriteMutex.withLock writeLock@{
                        if (!isRevisionCurrent(resolveRevision)) return@writeLock null
                        val prepared = compileProvider.prepare(
                            file = file,
                            projectRootPath = projectRootPath,
                            cppStandardOverride = cppStandardOverride,
                            forceRegenerateFallback = forceRegenerateFallback,
                        ) ?: return@writeLock null
                        val ensured = compileProvider.ensureWithResult(prepared) ?: return@writeLock null
                        Setup(
                            prepared = prepared,
                            compileCommandsDir = ensured.compileCommandsDir,
                        )
                    }
                },
            ).also { task ->
                synchronized(stateLock) { tasks[key] = task }
            }
        }

        return try {
            val setup = pendingTask.deferred.await()
            val stillCurrent = synchronized(stateLock) {
                if (revision == resolveRevision) {
                    if (setup != null) {
                        cache[key] = setup
                    }
                    true
                } else {
                    false
                }
            }
            if (!stillCurrent) {
                throw CancellationException("Compile setup invalidated")
            }
            setup?.also {
                Timber.tag(TAG).d(
                    "compile setup ready for %s (%s) in %dms",
                    file.name,
                    key.projectHint,
                    elapsedMillis(startedAt),
                )
            }
        } finally {
            synchronized(stateLock) {
                if (tasks[key] === pendingTask) {
                    tasks.remove(key)
                }
            }
        }
    }

    suspend fun prepareProvidedCompileCommandsForLsp(
        context: Context,
        sourceCompileCommandsFile: File,
        projectRootPath: String?,
        cppStandardOverride: String? = null,
    ): File? {
        val operationRevision = synchronized(stateLock) { revision }
        val compileCommandsDir = withContext(Dispatchers.IO) {
            compileDatabaseWriteMutex.withLock {
                ensureRevisionCurrent(operationRevision)
                getProvider(context).prepareProvidedCompileCommandsForLsp(
                    sourceCompileCommandsFile = sourceCompileCommandsFile,
                    projectRootPath = projectRootPath,
                    cppStandardOverride = cppStandardOverride,
                )
            }
        }
        ensureRevisionCurrent(operationRevision)
        return compileCommandsDir
    }

    private suspend fun isStillFresh(
        context: Context,
        cached: Setup,
        cppStandardOverride: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val currentProvider = getProvider(context)
            val currentFingerprint = currentProvider.computePackageFingerprint(cached.prepared.workspaceRoot)
            val currentRuntimeIdentity = currentProvider.resolveRuntimeIdentity(cached.prepared.workspaceRoot)
            currentFingerprint == cached.prepared.packageFingerprint &&
                currentRuntimeIdentity.toolchainId == cached.prepared.toolchainId &&
                currentRuntimeIdentity.sysrootProfileId == cached.prepared.sysrootProfileId &&
                currentRuntimeIdentity.sysrootApiLevel == cached.prepared.sysrootApiLevel &&
                resolveCppStandardFlag(
                    cached.prepared.workspaceRoot,
                    cppStandardOverride,
                ) == cached.prepared.desiredCppStandardFlag
        }.getOrDefault(true)
    }

    private fun buildKey(
        file: File,
        projectRootPath: String?,
        compileProvider: CompileDatabaseProvider,
        cppStandardOverride: String?,
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
            cppStandardFlag = resolveCppStandardFlag(workspaceRoot, cppStandardOverride),
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

    private fun resolveCppStandardFlag(projectRoot: File?, cppStandardOverride: String?): String =
        ProjectCppStandardResolver.resolveFlag(projectRoot, cppStandardOverride)

    private fun isRevisionCurrent(expectedRevision: Long): Boolean = synchronized(stateLock) {
        revision == expectedRevision
    }

    private fun ensureRevisionCurrent(expectedRevision: Long) {
        if (!isRevisionCurrent(expectedRevision)) {
            throw CancellationException("Compile setup invalidated")
        }
    }

    private fun File.stablePath(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
