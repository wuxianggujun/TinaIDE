package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.RelativePattern
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.Test

class LspFileWatchPatternTest {
    @Test
    fun glob_shouldSupportRecursiveBracesAndCharacterClasses() {
        val root = Files.createTempDirectory("lsp-watch-root-").toFile()
        try {
            val pattern = requireNotNull(
                LspFileWatchPattern.create(root.absolutePath, "**/*.{c,cpp,h,hpp}"),
            )
            val numbered = requireNotNull(
                LspFileWatchPattern.create(root.absolutePath, "src/**/test[0-9]?.cpp"),
            )

            assertThat(pattern.matches(File(root, "main.c").absolutePath, LspFileWatchPattern.CREATE_EVENT)).isTrue()
            assertThat(pattern.matches(File(root, "src/lib/header.hpp").absolutePath, LspFileWatchPattern.CHANGE_EVENT)).isTrue()
            assertThat(pattern.matches(File(root, "src/lib/readme.md").absolutePath, LspFileWatchPattern.CHANGE_EVENT)).isFalse()
            assertThat(numbered.matches(File(root, "src/unit/test42.cpp").absolutePath, LspFileWatchPattern.CHANGE_EVENT)).isTrue()
            assertThat(numbered.matches(File(root, "src/unit/testA2.cpp").absolutePath, LspFileWatchPattern.CHANGE_EVENT)).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun relativePattern_shouldResolveWorkspaceFolderAndRejectOutsideFiles() {
        val root = Files.createTempDirectory("lsp-relative-root-").toFile()
        try {
            val relativePattern = RelativePattern().apply {
                setBaseUri(WorkspaceFolder(root.toURI().toString(), "workspace"))
                pattern = "config/{CMakeLists.txt,*.cmake}"
            }
            val watcher = FileSystemWatcher().apply { setGlobPattern(relativePattern) }
            val pattern = requireNotNull(LspFileWatchPattern.fromWatcher(watcher, null))

            assertThat(
                pattern.matches(File(root, "config/CMakeLists.txt").absolutePath, LspFileWatchPattern.CHANGE_EVENT),
            ).isTrue()
            assertThat(
                pattern.matches(File(root, "config/toolchain.cmake").absolutePath, LspFileWatchPattern.CHANGE_EVENT),
            ).isTrue()
            assertThat(
                pattern.matches(File(root.parentFile, "config/toolchain.cmake").absolutePath, LspFileWatchPattern.CHANGE_EVENT),
            ).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun watcherKind_shouldFilterUnrequestedEvents() {
        val root = Files.createTempDirectory("lsp-watch-kind-").toFile()
        try {
            val watcher = FileSystemWatcher().apply {
                setGlobPattern("**/*.cmake")
                kind = LspFileWatchPattern.CHANGE_EVENT
            }
            val pattern = requireNotNull(LspFileWatchPattern.fromWatcher(watcher, root.absolutePath))
            val file = File(root, "cmake/toolchain.cmake")

            assertThat(pattern.matches(file.absolutePath, LspFileWatchPattern.CHANGE_EVENT)).isTrue()
            assertThat(pattern.matches(file.absolutePath, LspFileWatchPattern.CREATE_EVENT)).isFalse()
            assertThat(pattern.matches(file.absolutePath, LspFileWatchPattern.DELETE_EVENT)).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun relativePatternWithUnsupportedUri_shouldBeRejected() {
        val relativePattern = RelativePattern().apply {
            setBaseUri("https://example.com/workspace")
            pattern = "**/*.c"
        }
        val watcher = FileSystemWatcher().apply { setGlobPattern(relativePattern) }

        assertThat(LspFileWatchPattern.fromWatcher(watcher, null)).isNull()
    }

    @Test
    fun invalidPatternOrEventMask_shouldBeRejected() {
        assertThat(LspFileWatchPattern.create(null, "[]")).isNull()
        assertThat(LspFileWatchPattern.create(null, "**/*.c", eventMask = 0)).isNull()
    }
}
