package com.wuxianggujun.tinaide.storage

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class FileDeletionPhase {
    SCANNING,
    DELETING,
}

data class FileDeletionProgress(
    val phase: FileDeletionPhase,
    val completedItems: Long,
    val totalItems: Long?,
    val currentName: String?,
) {
    val fraction: Float?
        get() = totalItems
            ?.takeIf { it > 0L }
            ?.let { total -> (completedItems.toFloat() / total.toFloat()).coerceIn(0f, 1f) }
}

sealed interface FileDeletionResult {
    val deletedItems: Long
    val totalItems: Long?

    data class Success(
        override val deletedItems: Long,
        override val totalItems: Long,
        val stagedOutsidePublicStorage: Boolean,
    ) : FileDeletionResult

    data class Cancelled(
        override val deletedItems: Long,
        override val totalItems: Long?,
        val remainingAtOriginalPath: Boolean,
    ) : FileDeletionResult

    data class Failure(
        override val deletedItems: Long,
        override val totalItems: Long?,
        val failedPath: String?,
        val remainingAtOriginalPath: Boolean,
        val cause: Throwable? = null,
    ) : FileDeletionResult
}

class FileDeletionCancellationSignal {
    private val requested = AtomicBoolean(false)

    val isCancellationRequested: Boolean
        get() = requested.get()

    fun cancel() {
        requested.set(true)
    }
}

/**
 * 可取消的大目录删除服务。
 *
 * 对默认公开项目目录中的文件夹，优先通过一次同卷 rename 移入 App 专属外部目录，再执行
 * 逐项删除。这样公开 Documents 目录只发生一次目录移动，避免文件树与 MediaProvider 对每个
 * 子项重复处理；rename 不可用时自动回退为原地删除。
 */
class FileDeletionService internal constructor(
    private val stagingRootResolver: (File) -> File?,
    private val recoveryRootsProvider: () -> List<File> = { emptyList() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressIntervalNanos: Long = DEFAULT_PROGRESS_INTERVAL_NANOS,
) {
    private val deletionMutex = Mutex()

    constructor(context: Context) : this(
        stagingRootResolver = publicStorageStagingRootResolver(context.applicationContext),
        recoveryRootsProvider = {
            listOf(ProjectPaths.getDeletionStagingRoot(context.applicationContext))
        },
    )

    suspend fun delete(
        target: File,
        cancellationSignal: FileDeletionCancellationSignal = FileDeletionCancellationSignal(),
        onProgress: suspend (FileDeletionProgress) -> Unit = {},
    ): FileDeletionResult {
        val callbackContext = currentCoroutineContext().minusKey(Job)
        return withContext(ioDispatcher) {
            deletionMutex.withLock {
                recoverAbandonedStagingOnCurrentDispatcher()
                deleteOnCurrentDispatcher(
                    target = target,
                    cancellationSignal = cancellationSignal,
                    onProgress = { progress ->
                        withContext(callbackContext) { onProgress(progress) }
                    },
                )
            }
        }
    }

    /** 恢复上次进程异常退出时尚未完成的暂存删除，避免项目内容永久停留在隐藏暂存区。 */
    suspend fun recoverAbandonedStaging() {
        withContext(ioDispatcher) {
            deletionMutex.withLock {
                recoverAbandonedStagingOnCurrentDispatcher()
            }
        }
    }

    private suspend fun deleteOnCurrentDispatcher(
        target: File,
        cancellationSignal: FileDeletionCancellationSignal,
        onProgress: suspend (FileDeletionProgress) -> Unit,
    ): FileDeletionResult {
        if (!target.exists()) {
            onProgress(FileDeletionProgress(FileDeletionPhase.DELETING, 0L, 0L, target.name))
            return FileDeletionResult.Success(0L, 0L, stagedOutsidePublicStorage = false)
        }
        if (cancellationSignal.isCancellationRequested) {
            return FileDeletionResult.Cancelled(
                deletedItems = 0L,
                totalItems = null,
                remainingAtOriginalPath = true,
            )
        }

        val stagedTarget = stageTargetIfPossible(target)
        val workingRoot = stagedTarget?.stagedPath ?: target
        var discoveredItems = 0L
        var deletedItems = 0L
        var knownTotalItems: Long? = null
        var lastProgressAt = 0L

        suspend fun reportProgress(
            phase: FileDeletionPhase,
            completed: Long,
            total: Long?,
            currentName: String?,
            force: Boolean = false,
        ) {
            val now = System.nanoTime()
            if (!force && now - lastProgressAt < progressIntervalNanos) return
            lastProgressAt = now
            onProgress(
                FileDeletionProgress(
                    phase = phase,
                    completedItems = completed,
                    totalItems = total,
                    currentName = currentName,
                )
            )
        }

        suspend fun cancelledResult(): FileDeletionResult.Cancelled {
            val remainingAtOriginalPath = restoreRemainingTarget(stagedTarget, target)
            return FileDeletionResult.Cancelled(
                deletedItems = deletedItems,
                totalItems = knownTotalItems,
                remainingAtOriginalPath = remainingAtOriginalPath,
            )
        }

        suspend fun failureResult(
            failedPath: File?,
            cause: Throwable? = null,
        ): FileDeletionResult.Failure {
            val remainingAtOriginalPath = restoreRemainingTarget(stagedTarget, target)
            return FileDeletionResult.Failure(
                deletedItems = deletedItems,
                totalItems = knownTotalItems,
                failedPath = failedPath?.absolutePath,
                remainingAtOriginalPath = remainingAtOriginalPath,
                cause = cause,
            )
        }

        try {
            val deletionOrder = ArrayList<File>()
            val pending = ArrayDeque<File>()
            pending.add(workingRoot)

            reportProgress(
                phase = FileDeletionPhase.SCANNING,
                completed = 0L,
                total = null,
                currentName = target.name,
                force = true,
            )

            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (cancellationSignal.isCancellationRequested) return cancelledResult()

                val current = pending.removeLast()
                deletionOrder.add(current)
                discoveredItems++
                if (current.isDirectoryWithoutFollowingLinks()) {
                    val children = current.listFiles() ?: return failureResult(current)
                    children.forEach(pending::addLast)
                }
                reportProgress(
                    phase = FileDeletionPhase.SCANNING,
                    completed = discoveredItems,
                    total = null,
                    currentName = current.displayNameForProgress(workingRoot, target),
                )
            }

            knownTotalItems = deletionOrder.size.toLong()
            reportProgress(
                phase = FileDeletionPhase.DELETING,
                completed = 0L,
                total = knownTotalItems,
                currentName = target.name,
                force = true,
            )

            for (index in deletionOrder.lastIndex downTo 0) {
                currentCoroutineContext().ensureActive()
                if (cancellationSignal.isCancellationRequested) return cancelledResult()

                val current = deletionOrder[index]
                if (current.exists() && !current.delete()) {
                    return failureResult(current)
                }
                deletedItems++
                reportProgress(
                    phase = FileDeletionPhase.DELETING,
                    completed = deletedItems,
                    total = knownTotalItems,
                    currentName = current.displayNameForProgress(workingRoot, target),
                    force = deletedItems == knownTotalItems,
                )
            }

            stagedTarget?.let(::cleanupStagedOperation)
            return FileDeletionResult.Success(
                deletedItems = deletedItems,
                totalItems = knownTotalItems,
                stagedOutsidePublicStorage = stagedTarget != null,
            )
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                restoreRemainingTarget(stagedTarget, target)
            }
            throw cancellation
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "Failed to delete path: %s", target.absolutePath)
            return failureResult(workingRoot, error)
        }
    }

    private fun stageTargetIfPossible(target: File): StagedTarget? = runCatching {
        stageTargetUnchecked(target)
    }.onFailure { error ->
        Timber.tag(TAG).w(error, "Unable to stage deletion; falling back to in-place deletion")
    }.getOrNull()

    private fun stageTargetUnchecked(target: File): StagedTarget? {
        if (!target.isDirectory) return null
        val stagingRoot = stagingRootResolver(target) ?: return null
        val targetPath = target.canonicalPathOrAbsolute()
        val stagingPath = stagingRoot.canonicalPathOrAbsolute()
        if (targetPath == stagingPath || stagingPath.startsWith(targetPath + File.separator)) return null
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) return null

        val operationDir = File(stagingRoot, "delete-${UUID.randomUUID()}")
        if (!operationDir.mkdir()) return null
        val originalPathMarker = File(operationDir, ORIGINAL_PATH_MARKER)
        val markerCreated = runCatching {
            originalPathMarker.writeText(target.absolutePath, Charsets.UTF_8)
        }.isSuccess
        if (!markerCreated) {
            operationDir.deleteRecursively()
            stagingRoot.deleteIfEmpty()
            return null
        }

        val stagedPath = File(operationDir, STAGED_PAYLOAD_NAME)
        if (!target.renameTo(stagedPath)) {
            operationDir.deleteRecursively()
            stagingRoot.deleteIfEmpty()
            return null
        }

        val stagedTarget = StagedTarget(
            stagingRoot = stagingRoot,
            operationDir = operationDir,
            stagedPath = stagedPath,
        )
        runCatching {
            Timber.tag(TAG).i(
                "Moved public deletion target into app staging: name=%s operation=%s",
                target.name,
                operationDir.name,
            )
        }
        return stagedTarget
    }

    private fun restoreRemainingTarget(
        stagedTarget: StagedTarget?,
        originalTarget: File,
    ): Boolean = runCatching {
        restoreRemainingTargetUnchecked(stagedTarget, originalTarget)
    }.onFailure { error ->
        Timber.tag(TAG).e(error, "Failed to restore interrupted deletion target")
    }.getOrDefault(false)

    private fun restoreRemainingTargetUnchecked(
        stagedTarget: StagedTarget?,
        originalTarget: File,
    ): Boolean {
        if (stagedTarget == null) return originalTarget.exists()
        if (!stagedTarget.stagedPath.exists()) return originalTarget.exists()
        if (originalTarget.exists()) return false
        originalTarget.parentFile?.mkdirs()
        val restored = stagedTarget.stagedPath.renameTo(originalTarget)
        if (restored) cleanupStagedOperation(stagedTarget)
        return restored
    }

    private fun recoverAbandonedStagingOnCurrentDispatcher() {
        val recoveryRoots = runCatching(recoveryRootsProvider).onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to resolve deletion recovery roots")
        }.getOrDefault(emptyList())
        recoveryRoots.forEach { stagingRoot ->
            runCatching {
                stagingRoot.listFiles()
                    ?.filter(File::isDirectory)
                    ?.forEach { operationDir -> recoverStagedOperation(stagingRoot, operationDir) }
                stagingRoot.deleteIfEmpty()
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to recover staged deletion root")
            }
        }
    }

    private fun recoverStagedOperation(stagingRoot: File, operationDir: File) {
        val stagedPath = File(operationDir, STAGED_PAYLOAD_NAME)
        if (!stagedPath.exists()) {
            operationDir.deleteRecursively()
            return
        }

        val originalPath = runCatching {
            File(operationDir, ORIGINAL_PATH_MARKER).readText(Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return
        val originalTarget = File(originalPath)
        val expectedStagingRoot = stagingRootResolver(originalTarget) ?: return
        if (!expectedStagingRoot.hasSameCanonicalPath(stagingRoot) || originalTarget.exists()) return

        originalTarget.parentFile?.mkdirs()
        if (stagedPath.renameTo(originalTarget)) {
            operationDir.deleteRecursively()
            Timber.tag(TAG).w(
                "Recovered interrupted staged deletion: name=%s operation=%s",
                originalTarget.name,
                operationDir.name,
            )
        }
    }

    private fun cleanupStagedOperation(stagedTarget: StagedTarget) {
        runCatching {
            stagedTarget.operationDir.deleteRecursively()
            stagedTarget.stagingRoot.deleteIfEmpty()
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to clean completed deletion staging metadata")
        }
    }

    private data class StagedTarget(
        val stagingRoot: File,
        val operationDir: File,
        val stagedPath: File,
    )

    private fun File.isDirectoryWithoutFollowingLinks(): Boolean =
        isDirectory && runCatching { !Files.isSymbolicLink(toPath()) }.getOrDefault(true)

    private fun File.canonicalPathOrAbsolute(): String =
        runCatching { canonicalPath }.getOrElse { absolutePath }

    private fun File.hasSameCanonicalPath(other: File): Boolean =
        canonicalPathOrAbsolute() == other.canonicalPathOrAbsolute()

    private fun File.displayNameForProgress(workingRoot: File, originalTarget: File): String =
        if (this == workingRoot) originalTarget.name else name

    private fun File.deleteIfEmpty() {
        if (listFiles()?.isEmpty() == true) delete()
    }

    private companion object {
        private const val TAG = "FileDeletion"
        private const val DEFAULT_PROGRESS_INTERVAL_NANOS = 75_000_000L
        private const val ORIGINAL_PATH_MARKER = "original-path.txt"
        private const val STAGED_PAYLOAD_NAME = "payload"

        fun publicStorageStagingRootResolver(context: Context): (File) -> File? = { target ->
            if (ProjectPaths.isUnderPublicProjectsRoot(context, target)) {
                ProjectPaths.getDeletionStagingRoot(context)
            } else {
                null
            }
        }
    }
}
