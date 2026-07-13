package com.wuxianggujun.tinaide.ui.compose.screens.main.project

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProjectCloneCleanupTest {
    @Test
    fun cleanupFailedCloneTarget_completesAfterCallerCancellation() = runTest {
        val targetDir = Files.createTempDirectory("tina-clone-cleanup").toFile()
        targetDir.resolve(".git/objects/partial").apply {
            parentFile?.mkdirs()
            writeText("partial", Charsets.UTF_8)
        }
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            val job = launch {
                currentCoroutineContext().job.cancel()
                cleanupFailedCloneTarget(targetDir, dispatcher)
            }
            job.join()

            assertThat(targetDir.exists()).isFalse()
        } finally {
            targetDir.deleteRecursively()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
