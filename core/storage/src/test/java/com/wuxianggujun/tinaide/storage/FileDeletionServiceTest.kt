package com.wuxianggujun.tinaide.storage

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileDeletionServiceTest {

    @Test
    fun delete_removesNestedTreeAndReportsDeterminateProgress() = runTest {
        val root = createTree(fileCount = 8)
        val progress = mutableListOf<FileDeletionProgress>()
        try {
            val result = service().delete(root, onProgress = progress::add)

            assertThat(result).isInstanceOf(FileDeletionResult.Success::class.java)
            val success = result as FileDeletionResult.Success
            assertThat(success.deletedItems).isEqualTo(success.totalItems)
            assertThat(success.stagedOutsidePublicStorage).isFalse()
            assertThat(root.exists()).isFalse()
            assertThat(progress.first().phase).isEqualTo(FileDeletionPhase.SCANNING)
            assertThat(progress.last().phase).isEqualTo(FileDeletionPhase.DELETING)
            assertThat(progress.last().fraction).isEqualTo(1f)
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun cancel_stopsBetweenItemsAndKeepsRemainingTreeAtOriginalPath() = runTest {
        val root = createTree(fileCount = 12)
        val signal = FileDeletionCancellationSignal()
        try {
            val result = service().delete(root, signal) { progress ->
                if (progress.phase == FileDeletionPhase.DELETING && progress.completedItems >= 1L) {
                    signal.cancel()
                }
            }

            assertThat(result).isInstanceOf(FileDeletionResult.Cancelled::class.java)
            val cancelled = result as FileDeletionResult.Cancelled
            assertThat(cancelled.deletedItems).isEqualTo(1L)
            assertThat(cancelled.remainingAtOriginalPath).isTrue()
            assertThat(root.exists()).isTrue()
            assertThat(root.walkTopDown().count()).isGreaterThan(1)
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun publicTree_isMovedToStagingBeforeRecursiveDeletion() = runTest {
        val testRoot = Files.createTempDirectory("tina-delete-staging").toFile()
        val target = createTree(parent = testRoot.resolve("public"), fileCount = 10)
        val stagingRoot = testRoot.resolve("app-external/deletion-staging")
        var observedMoveBeforeScan = false
        try {
            val result = service(stagingRoot).delete(target) { progress ->
                if (progress.phase == FileDeletionPhase.SCANNING) {
                    observedMoveBeforeScan = observedMoveBeforeScan ||
                        (!target.exists() && stagingRoot.listFiles()?.isNotEmpty() == true)
                }
            }

            assertThat(result).isInstanceOf(FileDeletionResult.Success::class.java)
            assertThat((result as FileDeletionResult.Success).stagedOutsidePublicStorage).isTrue()
            assertThat(observedMoveBeforeScan).isTrue()
            assertThat(target.exists()).isFalse()
            assertThat(stagingRoot.listFiles().orEmpty()).isEmpty()
        } finally {
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun cancel_afterStaging_restoresRemainingTreeToOriginalPath() = runTest {
        val testRoot = Files.createTempDirectory("tina-delete-restore").toFile()
        val target = createTree(parent = testRoot.resolve("public"), fileCount = 12)
        val stagingRoot = testRoot.resolve("app-external/deletion-staging")
        val signal = FileDeletionCancellationSignal()
        try {
            val result = service(stagingRoot).delete(target, signal) { progress ->
                if (progress.phase == FileDeletionPhase.DELETING && progress.completedItems >= 1L) {
                    signal.cancel()
                }
            }

            assertThat(result).isInstanceOf(FileDeletionResult.Cancelled::class.java)
            val cancelled = result as FileDeletionResult.Cancelled
            assertThat(cancelled.remainingAtOriginalPath).isTrue()
            assertThat(cancelled.deletedItems).isEqualTo(1L)
            assertThat(target.exists()).isTrue()
            assertThat(stagingRoot.listFiles().orEmpty()).isEmpty()
        } finally {
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun recoverAbandonedStaging_restoresTargetAfterProcessInterruption() = runTest {
        val testRoot = Files.createTempDirectory("tina-delete-recovery").toFile()
        val target = createTree(parent = testRoot.resolve("public"), fileCount = 6)
        val stagingRoot = testRoot.resolve("app-external/deletion-staging")
        val operationDir = stagingRoot.resolve("delete-interrupted").apply { mkdirs() }
        operationDir.resolve("original-path.txt").writeText(target.absolutePath, Charsets.UTF_8)
        assertThat(target.renameTo(operationDir.resolve("payload"))).isTrue()
        try {
            service(stagingRoot).recoverAbandonedStaging()

            assertThat(target.exists()).isTrue()
            assertThat(target.walkTopDown().count()).isGreaterThan(1)
            assertThat(stagingRoot.listFiles().orEmpty()).isEmpty()
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun service(stagingRoot: File? = null): FileDeletionService = FileDeletionService(
        stagingRootResolver = { stagingRoot },
        recoveryRootsProvider = { listOfNotNull(stagingRoot) },
        ioDispatcher = Dispatchers.Unconfined,
        progressIntervalNanos = 0L,
    )

    private fun createTree(
        parent: File = Files.createTempDirectory("tina-delete-tree").toFile(),
        fileCount: Int,
    ): File {
        val root = parent.resolve("build").apply { mkdirs() }
        repeat(fileCount) { index ->
            root.resolve("dir-${index % 3}/file-$index.o").apply {
                parentFile?.mkdirs()
                writeText("object-$index", Charsets.UTF_8)
            }
        }
        return root
    }
}
