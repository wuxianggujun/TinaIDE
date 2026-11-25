package com.wuxianggujun.tinaide.editor.symbol

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSTree
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterRuntime
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSymbolIndexEventsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var provider: LanguageSymbolProvider
    private var duringExtraction: (() -> Unit)? = null

    @Before
    fun setUp() {
        mockkObject(TreeSitterRuntime)
        mockkStatic(TSParser::class)
        every { TreeSitterRuntime.ensureInitialized() } returns Unit
        val parser = mockk<TSParser>(relaxed = true)
        val tree = mockk<TSTree>(relaxed = true)
        val root = mockk<TSNode>()
        every { TSParser.create() } returns parser
        every { parser.parseString(any<String>()) } returns tree
        every { tree.rootNode } returns root
        provider = mockk()
        every { provider.supportedExtensions } returns setOf("cpp")
        every { provider.createLanguage() } returns mockk<TSLanguage>()
        every { provider.extractSymbols(any(), any()) } answers {
            duringExtraction?.invoke()
            listOf(GlobalSymbol(secondArg<String>().trim(), SymbolKind.Function, "function", location = SymbolLocation(0, 0)))
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(TSParser::class)
        unmockkObject(TreeSitterRuntime)
    }

    @Test
    fun deletion_removesOnlyTheDeletedSubtree() = runTest {
        val project = temporary.newFolder("project")
        val removedDirectory = File(project, "src").apply { mkdirs() }
        File(removedDirectory, "a.cpp").writeText("removed")
        val retainedDirectory = File(project, "src-extra").apply { mkdirs() }
        File(retainedDirectory, "b.cpp").writeText("retained")
        val index = ProjectSymbolIndexService(providers = listOf(provider), workerDispatcher = StandardTestDispatcher(testScheduler))
        try {
            index.onProjectOpened(project)
            advanceUntilIdle()
            assertThat(index.queryGlobals("", 100).map { it.name }).containsExactly("removed", "retained")
            assertThat(removedDirectory.deleteRecursively()).isTrue()
            index.onFileDeleted(removedDirectory)
            advanceUntilIdle()
            assertThat(index.queryGlobals("", 100).map { it.name }).containsExactly("retained")
        } finally {
            index.close()
        }
    }

    @Test
    fun rename_updatesPathsForEveryFileInTheMovedDirectory() = runTest {
        val project = temporary.newFolder("project")
        val oldDirectory = File(project, "before").apply { mkdirs() }
        File(oldDirectory, "a.cpp").writeText("first")
        File(oldDirectory, "b.cpp").writeText("second")
        val index = ProjectSymbolIndexService(providers = listOf(provider), workerDispatcher = StandardTestDispatcher(testScheduler))
        try {
            index.onProjectOpened(project)
            advanceUntilIdle()
            val movedDirectory = File(project, "after")
            assertThat(oldDirectory.renameTo(movedDirectory)).isTrue()
            index.onFileRenamed(oldDirectory, movedDirectory)
            advanceUntilIdle()
            val symbols = index.queryGlobals("", 100)
            assertThat(symbols.map { it.name }).containsExactly("first", "second")
            assertThat(symbols.map { File(it.filePath).parentFile.canonicalFile })
                .containsExactly(movedDirectory.canonicalFile, movedDirectory.canonicalFile)
        } finally {
            index.close()
        }
    }

    @Test
    fun deletionDuringParse_doesNotResurrectTheDeletedSymbol() = runTest {
        val project = temporary.newFolder("project")
        val file = File(project, "a.cpp").apply { writeText("obsolete") }
        val index = ProjectSymbolIndexService(providers = listOf(provider), workerDispatcher = StandardTestDispatcher(testScheduler))
        try {
            duringExtraction = {
                assertThat(file.delete()).isTrue()
                index.onFileDeleted(file)
                duringExtraction = null
            }
            index.onProjectOpened(project)
            advanceUntilIdle()
            assertThat(index.queryGlobals("", 100)).isEmpty()
        } finally {
            index.close()
        }
    }

    @Test
    fun externalChangesAndImports_refreshExistingAndNewFiles() = runTest {
        val project = temporary.newFolder("project")
        val existing = File(project, "a.cpp").apply { writeText("before") }
        val index = ProjectSymbolIndexService(providers = listOf(provider), workerDispatcher = StandardTestDispatcher(testScheduler))
        try {
            index.onProjectOpened(project)
            advanceUntilIdle()
            existing.writeText("after")
            index.onFileChanged(existing)
            val added = File(project, "new.cpp").apply { writeText("imported") }
            index.onFileChanged(added)
            index.onFileChanged(added)
            advanceUntilIdle()
            assertThat(index.queryGlobals("", 100).map { it.name }).containsExactly("after", "imported")
        } finally {
            index.close()
        }
    }
}
