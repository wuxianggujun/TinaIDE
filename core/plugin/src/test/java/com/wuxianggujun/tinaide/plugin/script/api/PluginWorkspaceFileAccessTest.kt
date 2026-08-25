package com.wuxianggujun.tinaide.plugin.script.api

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class PluginWorkspaceFileAccessTest {

    private lateinit var rootDir: File
    private lateinit var fileAccess: PluginWorkspaceFileAccess

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("tina-plugin-workspace").toFile()
        fileAccess = PluginWorkspaceFileAccess { rootDir.absolutePath }
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun resolveSafePath_shouldBlockEscapesAndAllowSafeDotNames() {
        val safeFile = File(rootDir, "docs/a..b.txt").apply {
            parentFile?.mkdirs()
            writeText("safe")
        }

        assertThat(fileAccess.resolveSafePath("docs/a..b.txt")?.canonicalFile)
            .isEqualTo(safeFile.canonicalFile)
        assertThat(fileAccess.resolveSafePath("../outside.txt")).isNull()
        assertThat(fileAccess.resolveSafePath("docs/../outside.txt")).isNull()
        assertThat(fileAccess.resolveSafePath("/absolute.txt")).isNull()
        assertThat(fileAccess.resolveSafePath("C:\\absolute.txt")).isNull()
        assertThat(fileAccess.resolveSafePath("\\\\server\\share\\absolute.txt")).isNull()
    }

    @Test
    fun findFiles_shouldSupportRecursiveGlobAndSkipHeavyDirectories() {
        File(rootDir, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("main")
        }
        File(rootDir, "src/Main.java").writeText("main")
        File(rootDir, "build/Generated.kt").apply {
            parentFile?.mkdirs()
            writeText("generated")
        }

        val files = fileAccess.findFiles(pattern = "*.kt", maxResults = 10)

        assertThat(files).containsExactly("src/Main.kt")
    }

    @Test
    fun findFiles_shouldSortBeforeApplyingResultLimit() {
        File(rootDir, "c.txt").writeText("c")
        File(rootDir, "b.txt").writeText("b")
        File(rootDir, "a.txt").writeText("a")

        val files = fileAccess.findFiles(pattern = "*.txt", maxResults = 2)

        assertThat(files).hasSize(2)
        assertThat(files).containsExactly("a.txt", "b.txt")
    }

    @Test
    fun findFiles_shouldRejectUnsafePatterns() {
        File(rootDir, "safe.txt").writeText("safe")

        assertThat(fileAccess.findFiles(pattern = "../*.txt", maxResults = 10)).isEmpty()
        assertThat(fileAccess.findFiles(pattern = "/**/*.txt", maxResults = 10)).isEmpty()
        assertThat(fileAccess.findFiles(pattern = "C:\\**\\*.txt", maxResults = 10)).isEmpty()
    }

    @Test
    fun findFiles_shouldNotFollowSymbolicLinkDirectories() {
        val sourceDirectory = File(rootDir, "source").apply { mkdirs() }
        File(sourceDirectory, "only-once.txt").writeText("safe")
        val link = File(rootDir, "linked-source")
        val linkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), sourceDirectory.toPath())
        }.isSuccess
        assumeTrue("Symbolic links are unavailable on this platform", linkCreated)

        try {
            assertThat(fileAccess.findFiles(pattern = "*.txt", maxResults = 10))
                .containsExactly("source/only-once.txt")
        } finally {
            Files.deleteIfExists(link.toPath())
        }
    }

    @Test
    fun mutations_shouldRejectSymbolicLinkTargetsAndParents() {
        val outsideDirectory = Files.createTempDirectory("tina-plugin-outside").toFile()
        val targetLink = File(rootDir, "linked-file.txt")
        val parentLink = File(rootDir, "linked-directory")
        val linksCreated = runCatching {
            Files.createSymbolicLink(targetLink.toPath(), outsideDirectory.resolve("outside.txt").toPath())
            Files.createSymbolicLink(parentLink.toPath(), outsideDirectory.toPath())
        }.isSuccess
        if (!linksCreated) {
            Files.deleteIfExists(targetLink.toPath())
            Files.deleteIfExists(parentLink.toPath())
            outsideDirectory.deleteRecursively()
        }
        assumeTrue("Symbolic links are unavailable on this platform", linksCreated)

        try {
            assertThat(fileAccess.writeUtf8File("linked-file.txt", "blocked")).isFalse()
            assertThat(fileAccess.openFileForRead("linked-file.txt")).isNull()
            assertThat(fileAccess.resolveSafePath("linked-file.txt")).isNull()
            assertThat(fileAccess.exists("linked-file.txt")).isFalse()
            assertThat(fileAccess.writeUtf8File("linked-directory/child.txt", "blocked")).isFalse()
            assertThat(fileAccess.createDirectories("linked-directory/nested")).isFalse()
            assertThat(fileAccess.isDirectory("linked-directory")).isFalse()
            assertThat(fileAccess.listDirectory("linked-directory", 10)).isNull()
            assertThat(outsideDirectory.resolve("outside.txt").exists()).isFalse()
            assertThat(outsideDirectory.resolve("child.txt").exists()).isFalse()
        } finally {
            Files.deleteIfExists(targetLink.toPath())
            Files.deleteIfExists(parentLink.toPath())
            outsideDirectory.deleteRecursively()
        }
    }

    @Test
    fun mutationHelpers_shouldCreateAndReadRegularWorkspaceFiles() {
        assertThat(fileAccess.createDirectories("generated/nested")).isTrue()
        assertThat(fileAccess.writeUtf8File("generated/nested/result.txt", "UTF-8 内容")).isTrue()

        val content = fileAccess.openFileForRead("generated/nested/result.txt")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }

        assertThat(content).isEqualTo("UTF-8 内容")
        assertThat(fileAccess.exists("generated/nested/result.txt")).isTrue()
        assertThat(fileAccess.isDirectory("generated/nested")).isTrue()
        assertThat(fileAccess.listDirectory("generated/nested", 10)).containsExactly("result.txt")
    }
}
