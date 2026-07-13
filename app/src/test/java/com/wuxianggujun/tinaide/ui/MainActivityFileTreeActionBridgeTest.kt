package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityFileTreeActionBridgeTest {

    @Test
    fun `refreshAndRevealExportedArtifact reveals existing exported artifact`() = runTest {
        val fileTreeState = mockk<FileTreeState>(relaxed = true)
        val bridge = MainActivityFileTreeActionBridge().apply {
            bind(fileTreeState)
        }
        val exportedArtifact = Files.createTempFile("main-activity-file-tree-bridge", ".bin").toFile()

        try {
            bridge.refreshAndRevealExportedArtifact(exportedArtifact.absolutePath)

            coVerify(exactly = 1) {
                fileTreeState.reveal(
                    match { it.absolutePath == exportedArtifact.absolutePath },
                    selectTarget = false
                )
            }
        } finally {
            exportedArtifact.delete()
        }
    }

    @Test
    fun `refreshAndRevealExportedArtifact skips missing exported artifact`() = runTest {
        val fileTreeState = mockk<FileTreeState>(relaxed = true)
        val bridge = MainActivityFileTreeActionBridge().apply {
            bind(fileTreeState)
        }
        val missingPath = Files.createTempDirectory("main-activity-file-tree-bridge-missing")
            .resolve("missing.bin")
            .toFile()

        bridge.refreshAndRevealExportedArtifact(missingPath.absolutePath)

        coVerify(exactly = 0) { fileTreeState.reveal(any(), any()) }
    }

    @Test
    fun `clear disconnects reveal action`() = runTest {
        val fileTreeState = mockk<FileTreeState>(relaxed = true)
        val bridge = MainActivityFileTreeActionBridge().apply {
            bind(fileTreeState)
            clear()
        }
        val target = Files.createTempFile("main-activity-file-tree-bridge-clear", ".bin").toFile()

        try {
            bridge.reveal(target, false)

            coVerify(exactly = 0) { fileTreeState.reveal(any(), any()) }
        } finally {
            target.delete()
        }
    }
}
