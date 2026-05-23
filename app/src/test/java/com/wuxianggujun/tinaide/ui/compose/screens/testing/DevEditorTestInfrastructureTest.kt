package com.wuxianggujun.tinaide.ui.compose.screens.testing

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ConfigChangeListener
import com.wuxianggujun.tinaide.core.config.ConfigKey
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkDatabase
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.ui.compose.components.EditorStatus
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class DevEditorTestInfrastructureTest {

    @Test
    fun createWorkspaceDir_shouldResetExistingWorkspaceContent() {
        val cacheDir = Files.createTempDirectory("dev-editor-workspace-cache").toFile()

        try {
            val firstWorkspace = DevEditorTestHostSupport.createWorkspaceDir(cacheDir, "tree-sitter")
            File(firstWorkspace, "stale.txt").writeText("stale")

            val recreatedWorkspace = DevEditorTestHostSupport.createWorkspaceDir(cacheDir, "tree-sitter")

            assertThat(recreatedWorkspace.absolutePath).isEqualTo(firstWorkspace.absolutePath)
            assertThat(File(recreatedWorkspace, "stale.txt").exists()).isFalse()
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun materializeFixtures_shouldWriteNestedFilesInDeclaredOrder() = runBlocking {
        val workspaceDir = Files.createTempDirectory("dev-editor-fixtures").toFile()
        val fixtures = listOf(
            DevEditorFixture("src/Makefile", "all:\n\t@echo ok\n"),
            DevEditorFixture("cmake/CMakeLists.txt", "project(Demo)\n")
        )

        try {
            val writtenFiles = DevEditorTestHostSupport.materializeFixtures(workspaceDir, fixtures)

            assertThat(writtenFiles.map { it.relativeTo(workspaceDir).invariantSeparatorsPath })
                .containsExactly("src/Makefile", "cmake/CMakeLists.txt")
                .inOrder()
            assertThat(writtenFiles.map(File::readText))
                .containsExactly("all:\n\t@echo ok\n", "project(Demo)\n")
                .inOrder()
        } finally {
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun registry_shouldExposeEditorBackedDeveloperTestsWithStableIds() {
        val tests = DevTestRegistry.getAllTests()
        val testsById = tests.associateBy { it.id }
        val editorBackedEntries = DevEditorTestCatalog.editorBackedEntries

        assertThat(tests.map { it.id })
            .containsAtLeastElementsIn(editorBackedEntries.map { it.registryId })
        assertThat(tests.map { it.id }.distinct()).hasSize(tests.size)
        editorBackedEntries.forEach { entry ->
            val registryItem = testsById[entry.registryId]
            assertThat(registryItem).isNotNull()
            assertThat(registryItem?.titleRes).isEqualTo(entry.titleRes)
            assertThat(registryItem?.descriptionRes).isEqualTo(entry.descriptionRes)
        }
    }

    @Test
    fun registry_shouldExposeAllDeveloperTestEntriesInStableOrder() {
        val tests = DevTestRegistry.getAllTests()

        assertThat(tests.map { it.id }).containsExactly(
            DevTestIds.ThemePreview,
            DevTestIds.TreeSitter,
            DevTestIds.EditorScroll,
            DevTestIds.CppScrollStress,
            DevTestIds.Clangd,
            DevTestIds.GuiPreview,
            DevTestIds.PluginDatabase,
            DevTestIds.CompilerDiagnostics,
            DevTestIds.AiChat
        ).inOrder()
    }

    @Test
    fun themePreviewFixtures_shouldCoverThemeSmokeLanguagesIncludingMakeAndCmake() {
        val fixtures = DevEditorTestSamples.themePreviewFixtures()

        assertThat(fixtures.map { it.relativePath }).containsExactly(
            "ThemePreview.kt",
            "preview.json",
            "layout.xml",
            "CMakeLists.txt",
            "Makefile"
        ).inOrder()
        assertThat(fixtures.first { it.relativePath == "Makefile" }.content).contains(".PHONY")
        assertThat(fixtures.first { it.relativePath == "CMakeLists.txt" }.content).contains("cmake_minimum_required")
    }

    @Test
    fun editorBackedCatalog_shouldExposeUniqueWorkspaceKeysAndRegistryMappings() {
        val entries = DevEditorTestCatalog.editorBackedEntries

        assertThat(entries.map { it.workspaceKey }.distinct()).hasSize(entries.size)
        assertThat(entries.map { it.registryId }.distinct()).hasSize(entries.size)
        assertThat(entries.map { it.descriptionRes }.distinct()).hasSize(entries.size)
        assertThat(entries.associateBy { it.registryId }[DevTestIds.ThemePreview]?.workspaceKey)
            .isEqualTo("theme-preview")
        assertThat(entries.associateBy { it.registryId }[DevTestIds.EditorScroll]?.titleRes)
            .isEqualTo(com.wuxianggujun.tinaide.core.i18n.Strings.dev_options_editor_scroll_test)
        assertThat(entries.associateBy { it.registryId }[DevTestIds.Clangd]?.workspaceKey)
            .isEqualTo("clangd")
    }

    @Test
    fun editorBackedCatalog_toRegistryItemsShouldRoundTripMetadata() {
        val registryItems = DevEditorTestCatalog.toRegistryItems()

        assertThat(registryItems.map { it.id })
            .containsExactlyElementsIn(DevEditorTestCatalog.editorBackedEntries.map { it.registryId })
            .inOrder()
        assertThat(registryItems.map { it.titleRes })
            .containsExactlyElementsIn(DevEditorTestCatalog.editorBackedEntries.map { it.titleRes })
            .inOrder()
        assertThat(registryItems.map { it.descriptionRes })
            .containsExactlyElementsIn(DevEditorTestCatalog.editorBackedEntries.map { it.descriptionRes })
            .inOrder()
    }

    @Test
    fun treeSitterSampleOptions_shouldExposeStableCoverageForBuiltinEditorTests() {
        val options = DevEditorTestCatalog.treeSitterSampleOptions

        assertThat(options.map { it.id })
            .containsExactly("cmake", "make", "json", "xml", "kotlin")
            .inOrder()
        assertThat(options.map { it.fixture.relativePath })
            .containsExactly("CMakeLists.txt", "Makefile", "preview.json", "layout.xml", "ThemePreview.kt")
            .inOrder()
    }

    @Test
    fun scrollFixtures_shouldRemainLargeEnoughForRegressionCoverage() {
        val scrollFixture = DevEditorTestSamples.editorScrollFixture().single()
        val cppFixture = DevEditorTestSamples.cppScrollStressFixture()

        assertThat(scrollFixture.relativePath).isEqualTo("EditorScroll.kt")
        assertThat(scrollFixture.content.lineSequence().count()).isAtLeast(1600)
        assertThat(cppFixture.relativePath).isEqualTo("stress.cpp")
        assertThat(cppFixture.content.lineSequence().count()).isAtLeast(4000)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class DevEditorHostBootstrapTest {

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        Prefs.initialize(context, InMemoryConfigManager())
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
        runCatching { BookmarkDatabase.closeInstance() }
    }

    @Test
    fun bootstrapFlow_shouldOpenMaterializedFixturesWithRealEditorState() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
            cacheDir = context.cacheDir,
            workspaceKey = "bootstrap-${System.nanoTime()}"
        )
        val fixtures = listOf(
            DevEditorFixture("src/Makefile", "all:\n\t@echo ok\n"),
            DevEditorFixture("src/CMakeLists.txt", "project(Demo)\n")
        )

        val editorManager = createIsolatedEditorManager(
            context = context,
            configManager = InMemoryConfigManager(),
            projectContext = DevEditorTestHostSupport.createProjectContext("bootstrap", workspaceDir),
            projectSymbolIndexServiceProvider = { null }
        )
        val editorState = createEditorState(context, editorManager, workspaceDir)

        try {
            DevEditorTestHostSupport.bootstrapEditorState(
                editorManager = editorManager,
                editorState = editorState,
                workspaceDir = workspaceDir,
                fixtures = fixtures,
                activeFixtureIndex = 99
            )

            assertThat(editorState.tabs.map { it.file.name })
                .containsExactly("Makefile", "CMakeLists.txt")
                .inOrder()
            assertThat(editorState.snapshotActivePluginEditorContextOrNull()?.file?.name).isEqualTo("CMakeLists.txt")
            assertThat(editorState.getEditorProjectRootPathOrNull()).isEqualTo(workspaceDir.absolutePath)
        } finally {
            editorManager.closeAll(clearPersistentState = true)
            editorManager.onDestroy()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun themePreviewScenario_shouldOpenAllLanguageTabsThroughEditorContainerState() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
            cacheDir = context.cacheDir,
            workspaceKey = "theme-preview-${System.nanoTime()}"
        )
        val fixtures = DevEditorTestSamples.themePreviewFixtures()
        val editorManager = createIsolatedEditorManager(
            context = context,
            configManager = InMemoryConfigManager(),
            projectContext = DevEditorTestHostSupport.createProjectContext("theme-preview", workspaceDir),
            projectSymbolIndexServiceProvider = { null }
        )
        val editorState = createEditorState(context, editorManager, workspaceDir)

        try {
            DevEditorTestHostSupport.bootstrapEditorState(
                editorManager = editorManager,
                editorState = editorState,
                workspaceDir = workspaceDir,
                fixtures = fixtures,
                activeFixtureIndex = 0
            )

            assertThat(editorState.tabs.map { it.file.name }).containsExactly(
                "ThemePreview.kt",
                "preview.json",
                "layout.xml",
                "CMakeLists.txt",
                "Makefile"
            ).inOrder()
            assertThat(editorState.snapshotActivePluginEditorContextOrNull()?.file?.name).isEqualTo("ThemePreview.kt")
        } finally {
            editorManager.closeAll(clearPersistentState = true)
            editorManager.onDestroy()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun themePreviewScenario_shouldKeepOnlyBuiltinMakeAndCmakeAttachedWhenEditorLspIsDisabled() = runBlocking {
        withLspPrefs(editorLspEnabled = false, builtinCmakeLspEnabled = true) {
            val context = RuntimeEnvironment.getApplication().applicationContext as Context
            val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
                cacheDir = context.cacheDir,
                workspaceKey = "theme-preview-builtin-only-${System.nanoTime()}"
            )
            val fixtures = DevEditorTestSamples.themePreviewFixtures()
            val editorManager = createIsolatedEditorManager(
                context = context,
                configManager = InMemoryConfigManager(),
                projectContext = DevEditorTestHostSupport.createProjectContext("theme-preview-builtin-only", workspaceDir),
                projectSymbolIndexServiceProvider = { null }
            )
            val editorState = createEditorState(context, editorManager, workspaceDir)

            try {
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = 0
                )
                attachLspForAllTabs(editorState)

                val lspStatuses = editorState.tabs.associate { tab ->
                    tab.file.name to editorState.getLspStatus(tab.id)
                }

                assertThat(lspStatuses["ThemePreview.kt"]).isEqualTo(EditorStatus.NoLsp)
                assertThat(lspStatuses["preview.json"]).isEqualTo(EditorStatus.NoLsp)
                assertThat(lspStatuses["layout.xml"]).isEqualTo(EditorStatus.NoLsp)
                assertThat(lspStatuses["CMakeLists.txt"]).isEqualTo(EditorStatus.Ready)
                assertThat(lspStatuses["Makefile"]).isEqualTo(EditorStatus.Ready)
            } finally {
                editorManager.closeAll(clearPersistentState = true)
                editorManager.onDestroy()
                workspaceDir.deleteRecursively()
            }
        }
    }

    @Test
    fun clangdScenario_shouldOpenFixturesAndResolveWorkspaceTokensThroughRealEditorState() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
            cacheDir = context.cacheDir,
            workspaceKey = "clangd-${System.nanoTime()}"
        )
        val fixtures = DevEditorTestCatalog.clangd.fixturesProvider()
        val editorManager = createIsolatedEditorManager(
            context = context,
            configManager = InMemoryConfigManager(),
            projectContext = DevEditorTestHostSupport.createProjectContext("clangd", workspaceDir),
            projectSymbolIndexServiceProvider = { null }
        )
        val editorState = createEditorState(context, editorManager, workspaceDir)

        try {
            DevEditorTestHostSupport.bootstrapEditorState(
                editorManager = editorManager,
                editorState = editorState,
                workspaceDir = workspaceDir,
                fixtures = fixtures,
                activeFixtureIndex = DevEditorTestCatalog.clangd.activeFixtureIndex
            )

            assertThat(editorState.tabs.map { it.file.relativeTo(workspaceDir).invariantSeparatorsPath })
                .containsExactly(
                    "src/main.cpp",
                    "include/math_utils.h",
                    "src/math_utils.cpp",
                    "compile_commands.json",
                    ".clangd"
                )
                .inOrder()
            assertThat(editorState.snapshotActivePluginEditorContextOrNull()?.file?.relativeTo(workspaceDir)?.invariantSeparatorsPath)
                .isEqualTo("src/main.cpp")
            assertThat(editorState.getEditorProjectRootPathOrNull()).isEqualTo(workspaceDir.absolutePath)

            val compileCommands = File(workspaceDir, "compile_commands.json").readText()
            assertThat(compileCommands).doesNotContain(DEV_EDITOR_WORKSPACE_PATH_TOKEN)
            assertThat(compileCommands)
                .contains(workspaceDir.absolutePath.replace(File.separatorChar, '/'))
        } finally {
            editorManager.closeAll(clearPersistentState = true)
            editorManager.onDestroy()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun clangdScenario_shouldAttachCxxLspWhenEditorLspEnabled() = runBlocking {
        withLspPrefs(editorLspEnabled = true, builtinCmakeLspEnabled = true) {
            val context = RuntimeEnvironment.getApplication().applicationContext as Context
            val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
                cacheDir = context.cacheDir,
                workspaceKey = "clangd-lsp-enabled-${System.nanoTime()}"
            )
            val fixtures = DevEditorTestCatalog.clangd.fixturesProvider()
            val editorManager = createIsolatedEditorManager(
                context = context,
                configManager = InMemoryConfigManager(),
                projectContext = DevEditorTestHostSupport.createProjectContext("clangd-lsp-enabled", workspaceDir),
                projectSymbolIndexServiceProvider = { null }
            )
            val editorState = createEditorState(context, editorManager, workspaceDir)

            try {
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = DevEditorTestCatalog.clangd.activeFixtureIndex
                )
                attachLspForAllTabs(editorState)

                val lspStatuses = editorState.tabs.associate { tab ->
                    tab.file.relativeTo(workspaceDir).invariantSeparatorsPath to
                        editorState.getLspStatus(tab.id)
                }

                assertThat(editorState.snapshotActivePluginEditorContextOrNull()?.file?.relativeTo(workspaceDir)?.invariantSeparatorsPath)
                    .isEqualTo("src/main.cpp")
                assertThat(editorState.getActiveLspStatus())
                    .isAnyOf(EditorStatus.Ready, EditorStatus.Connecting)
                assertThat(lspStatuses["src/main.cpp"])
                    .isAnyOf(EditorStatus.Ready, EditorStatus.Connecting)
                assertThat(lspStatuses["include/math_utils.h"])
                    .isAnyOf(EditorStatus.Ready, EditorStatus.Connecting)
                assertThat(lspStatuses["src/math_utils.cpp"])
                    .isAnyOf(EditorStatus.Ready, EditorStatus.Connecting)
            } finally {
                editorManager.closeAll(clearPersistentState = true)
                editorManager.onDestroy()
                workspaceDir.deleteRecursively()
            }
        }
    }

    @Test
    fun clangdScenario_shouldKeepCxxTabsDetachedWhenEditorLspIsDisabled() = runBlocking {
        withLspPrefs(editorLspEnabled = false, builtinCmakeLspEnabled = true) {
            val context = RuntimeEnvironment.getApplication().applicationContext as Context
            val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
                cacheDir = context.cacheDir,
                workspaceKey = "clangd-lsp-disabled-${System.nanoTime()}"
            )
            val fixtures = DevEditorTestCatalog.clangd.fixturesProvider()
            val editorManager = createIsolatedEditorManager(
                context = context,
                configManager = InMemoryConfigManager(),
                projectContext = DevEditorTestHostSupport.createProjectContext("clangd-lsp-disabled", workspaceDir),
                projectSymbolIndexServiceProvider = { null }
            )
            val editorState = createEditorState(context, editorManager, workspaceDir)

            try {
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = DevEditorTestCatalog.clangd.activeFixtureIndex
                )
                attachLspForAllTabs(editorState)

                val lspStatuses = editorState.tabs.associate { tab ->
                    tab.file.relativeTo(workspaceDir).invariantSeparatorsPath to
                        editorState.getLspStatus(tab.id)
                }

                assertThat(editorState.snapshotActivePluginEditorContextOrNull()?.file?.relativeTo(workspaceDir)?.invariantSeparatorsPath)
                    .isEqualTo("src/main.cpp")
                assertThat(editorState.getActiveLspStatus()).isEqualTo(EditorStatus.NoLsp)
                assertThat(lspStatuses["src/main.cpp"]).isEqualTo(EditorStatus.NoLsp)
                assertThat(lspStatuses["include/math_utils.h"]).isEqualTo(EditorStatus.NoLsp)
                assertThat(lspStatuses["src/math_utils.cpp"]).isEqualTo(EditorStatus.NoLsp)
            } finally {
                editorManager.closeAll(clearPersistentState = true)
                editorManager.onDestroy()
                workspaceDir.deleteRecursively()
            }
        }
    }

    @Test
    fun treeSitterSamples_shouldRebootstrapRealEditorStateForEachLanguageFixture() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
            cacheDir = context.cacheDir,
            workspaceKey = "tree-sitter-${System.nanoTime()}"
        )
        val editorManager = createIsolatedEditorManager(
            context = context,
            configManager = InMemoryConfigManager(),
            projectContext = DevEditorTestHostSupport.createProjectContext("tree-sitter", workspaceDir),
            projectSymbolIndexServiceProvider = { null }
        )
        val editorState = createEditorState(context, editorManager, workspaceDir)

        try {
            DevEditorTestCatalog.treeSitterSampleOptions.forEach { sample ->
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = listOf(sample.fixture),
                    activeFixtureIndex = 0
                )

                assertThat(editorState.tabs.map { it.file.name })
                    .containsExactly(File(sample.fixture.relativePath).name)
                assertThat(editorState.snapshotActivePluginEditorContextOrNull()?.file?.name)
                    .isEqualTo(File(sample.fixture.relativePath).name)
                assertThat(editorState.getEditorProjectRootPathOrNull()).isEqualTo(workspaceDir.absolutePath)
            }
        } finally {
            editorManager.closeAll(clearPersistentState = true)
            editorManager.onDestroy()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun singleFixtureDeveloperScenarios_shouldOpenThroughRealEditorState() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
            cacheDir = context.cacheDir,
            workspaceKey = "single-fixture-${System.nanoTime()}"
        )
        val editorManager = createIsolatedEditorManager(
            context = context,
            configManager = InMemoryConfigManager(),
            projectContext = DevEditorTestHostSupport.createProjectContext("single-fixture", workspaceDir),
            projectSymbolIndexServiceProvider = { null }
        )
        val editorState = createEditorState(context, editorManager, workspaceDir)
        val scenarios = listOf(
            DevEditorTestCatalog.editorScroll,
            DevEditorTestCatalog.cppScrollStress
        )

        try {
            scenarios.forEach { scenario ->
                val fixtures = scenario.fixturesProvider()
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = scenario.activeFixtureIndex
                )

                assertThat(fixtures).hasSize(1)
                assertThat(editorState.tabs.map { it.file.name })
                    .containsExactly(File(fixtures.single().relativePath).name)
                assertThat(editorState.snapshotActivePluginEditorContextOrNull()?.file?.name)
                    .isEqualTo(File(fixtures.single().relativePath).name)
                assertThat(editorState.getEditorProjectRootPathOrNull()).isEqualTo(workspaceDir.absolutePath)
            }
        } finally {
            editorManager.closeAll(clearPersistentState = true)
            editorManager.onDestroy()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun builtinMakeAndCmake_shouldBootstrapWhenGlobalEditorLspIsDisabled() = runBlocking {
        withLspPrefs(editorLspEnabled = false, builtinCmakeLspEnabled = true) {
            val context = RuntimeEnvironment.getApplication().applicationContext as Context
            val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
                cacheDir = context.cacheDir,
                workspaceKey = "builtin-lsp-disabled-${System.nanoTime()}"
            )
            val fixtures = listOf(
                DevEditorFixture("Makefile", "all:\n\t@echo ok\n"),
                DevEditorFixture("CMakeLists.txt", "cmake_minimum_required(VERSION 3.22)\nproject(Demo)\n")
            )
            val editorManager = createIsolatedEditorManager(
                context = context,
                configManager = InMemoryConfigManager(),
                projectContext = DevEditorTestHostSupport.createProjectContext("builtin-lsp-disabled", workspaceDir),
                projectSymbolIndexServiceProvider = { null }
            )
            val editorState = createEditorState(context, editorManager, workspaceDir)

            try {
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = 0
                )
                attachLspForAllTabs(editorState)

                val lspStatuses = editorState.tabs.associate { tab ->
                    tab.file.name to editorState.getLspStatus(tab.id)
                }

                assertThat(lspStatuses["Makefile"]).isEqualTo(EditorStatus.Ready)
                assertThat(lspStatuses["CMakeLists.txt"]).isEqualTo(EditorStatus.Ready)
            } finally {
                editorManager.closeAll(clearPersistentState = true)
                editorManager.onDestroy()
                workspaceDir.deleteRecursively()
            }
        }
    }

    @Test
    fun builtinCmakeDisabledFromStart_shouldKeepMakeReadyAndCmakeDetached() = runBlocking {
        withLspPrefs(editorLspEnabled = false, builtinCmakeLspEnabled = false) {
            val context = RuntimeEnvironment.getApplication().applicationContext as Context
            val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
                cacheDir = context.cacheDir,
                workspaceKey = "builtin-cmake-disabled-${System.nanoTime()}"
            )
            val fixtures = listOf(
                DevEditorFixture("Makefile", "all:\n\t@echo ok\n"),
                DevEditorFixture("CMakeLists.txt", "cmake_minimum_required(VERSION 3.22)\nproject(Demo)\n")
            )
            val editorManager = createIsolatedEditorManager(
                context = context,
                configManager = InMemoryConfigManager(),
                projectContext = DevEditorTestHostSupport.createProjectContext("builtin-cmake-disabled", workspaceDir),
                projectSymbolIndexServiceProvider = { null }
            )
            val editorState = createEditorState(context, editorManager, workspaceDir)

            try {
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = 0
                )
                attachLspForAllTabs(editorState)

                val lspStatuses = editorState.tabs.associate { tab ->
                    tab.file.name to editorState.getLspStatus(tab.id)
                }

                assertThat(lspStatuses["Makefile"]).isEqualTo(EditorStatus.Ready)
                assertThat(lspStatuses["CMakeLists.txt"]).isEqualTo(EditorStatus.NoLsp)
            } finally {
                editorManager.closeAll(clearPersistentState = true)
                editorManager.onDestroy()
                workspaceDir.deleteRecursively()
            }
        }
    }

    @Test
    fun builtinCmake_shouldDetachAfterBuiltinToggleIsDisabled() = runBlocking {
        withLspPrefs(editorLspEnabled = false, builtinCmakeLspEnabled = true) {
            val context = RuntimeEnvironment.getApplication().applicationContext as Context
            val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
                cacheDir = context.cacheDir,
                workspaceKey = "builtin-cmake-toggle-${System.nanoTime()}"
            )
            val fixtures = listOf(
                DevEditorFixture("CMakeLists.txt", "cmake_minimum_required(VERSION 3.22)\nproject(Demo)\n")
            )
            val editorManager = createIsolatedEditorManager(
                context = context,
                configManager = InMemoryConfigManager(),
                projectContext = DevEditorTestHostSupport.createProjectContext("builtin-cmake-toggle", workspaceDir),
                projectSymbolIndexServiceProvider = { null }
            )
            val editorState = createEditorState(context, editorManager, workspaceDir)

            try {
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = 0
                )
                attachLspForAllTabs(editorState)

                val activeEditor = editorState.snapshotActivePluginEditorContextOrNull()
                assertThat(activeEditor).isNotNull()
                assertThat(editorState.getActiveLspStatus()).isEqualTo(EditorStatus.Ready)

                Prefs.devBuiltinCmakeLspEnabled = false
                editorState.refreshLspConnections()

                assertThat(editorState.getActiveLspStatus()).isEqualTo(EditorStatus.NoLsp)
                assertThat(editorState.getLspStatus(activeEditor!!.tabId)).isEqualTo(EditorStatus.NoLsp)
            } finally {
                editorManager.closeAll(clearPersistentState = true)
                editorManager.onDestroy()
                workspaceDir.deleteRecursively()
            }
        }
    }

    @Test
    fun builtinCmakeToggle_shouldOnlyDetachCmakeAndKeepMakeReady() = runBlocking {
        withLspPrefs(editorLspEnabled = false, builtinCmakeLspEnabled = true) {
            val context = RuntimeEnvironment.getApplication().applicationContext as Context
            val workspaceDir = DevEditorTestHostSupport.createWorkspaceDir(
                cacheDir = context.cacheDir,
                workspaceKey = "builtin-cmake-mixed-toggle-${System.nanoTime()}"
            )
            val fixtures = listOf(
                DevEditorFixture("Makefile", "all:\n\t@echo ok\n"),
                DevEditorFixture("CMakeLists.txt", "cmake_minimum_required(VERSION 3.22)\nproject(Demo)\n")
            )
            val editorManager = createIsolatedEditorManager(
                context = context,
                configManager = InMemoryConfigManager(),
                projectContext = DevEditorTestHostSupport.createProjectContext("builtin-cmake-mixed-toggle", workspaceDir),
                projectSymbolIndexServiceProvider = { null }
            )
            val editorState = createEditorState(context, editorManager, workspaceDir)

            try {
                DevEditorTestHostSupport.bootstrapEditorState(
                    editorManager = editorManager,
                    editorState = editorState,
                    workspaceDir = workspaceDir,
                    fixtures = fixtures,
                    activeFixtureIndex = 0
                )
                attachLspForAllTabs(editorState)

                val statusesBeforeDisable = editorState.tabs.associate { tab ->
                    tab.file.name to editorState.getLspStatus(tab.id)
                }
                assertThat(statusesBeforeDisable["Makefile"]).isEqualTo(EditorStatus.Ready)
                assertThat(statusesBeforeDisable["CMakeLists.txt"]).isEqualTo(EditorStatus.Ready)

                Prefs.devBuiltinCmakeLspEnabled = false
                editorState.refreshLspConnections()

                val statusesAfterDisable = editorState.tabs.associate { tab ->
                    tab.file.name to editorState.getLspStatus(tab.id)
                }
                assertThat(statusesAfterDisable["Makefile"]).isEqualTo(EditorStatus.Ready)
                assertThat(statusesAfterDisable["CMakeLists.txt"]).isEqualTo(EditorStatus.NoLsp)
            } finally {
                editorManager.closeAll(clearPersistentState = true)
                editorManager.onDestroy()
                workspaceDir.deleteRecursively()
            }
        }
    }

    private fun createEditorState(
        context: Context,
        editorManager: com.wuxianggujun.tinaide.editor.EditorManager,
        workspaceDir: File
    ): EditorContainerState {
        val pluginManager = PluginManager(context)
        return EditorContainerState(
            context = context,
            editorManager = editorManager,
            snippetManager = PluginSnippetManager(pluginManager),
            pluginThemeRegistry = PluginEditorThemeRegistry(context, pluginManager),
            projectSymbolIndexServiceProvider = { null },
            projectRootPathProvider = { workspaceDir.absolutePath }
        )
    }

    private inline fun withLspPrefs(
        editorLspEnabled: Boolean,
        builtinCmakeLspEnabled: Boolean,
        block: () -> Unit
    ) {
        val originalEditorLspEnabled = Prefs.devEditorLspEnabled
        val originalBuiltinCmakeLspEnabled = Prefs.devBuiltinCmakeLspEnabled
        Prefs.devEditorLspEnabled = editorLspEnabled
        Prefs.devBuiltinCmakeLspEnabled = builtinCmakeLspEnabled
        try {
            block()
        } finally {
            Prefs.devEditorLspEnabled = originalEditorLspEnabled
            Prefs.devBuiltinCmakeLspEnabled = originalBuiltinCmakeLspEnabled
        }
    }

    private fun attachLspForAllTabs(editorState: EditorContainerState) {
        editorState.tabs.forEach { tab ->
            editorState.attachTinaLspForTab(tab.id, tab.file) {
                tab.file.readText()
            }
        }
    }
}

private class InMemoryConfigManager : IConfigManager {
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashMap<String, MutableSet<ConfigChangeListener>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, default: T): T = values[key] as? T ?: default

    override fun <T> get(key: ConfigKey<T>): T = get(key.key, key.default)

    override fun <T> set(key: String, value: T) {
        values[key] = value
        listeners[key]?.forEach { it.onConfigChanged(key, value) }
    }

    override fun <T> set(key: ConfigKey<T>, value: T) {
        set(key.key, value)
    }

    override fun remove(key: String) {
        values.remove(key)
        listeners[key]?.forEach { it.onConfigChanged(key, null) }
    }

    override fun clear() {
        val keys = values.keys.toList()
        values.clear()
        keys.forEach { key ->
            listeners[key]?.forEach { it.onConfigChanged(key, null) }
        }
    }

    override fun addListener(key: String, listener: ConfigChangeListener) {
        listeners.getOrPut(key) { linkedSetOf() }.add(listener)
    }

    override fun removeListener(key: String, listener: ConfigChangeListener) {
        listeners[key]?.remove(listener)
    }

    override fun exportConfig(): String = values.toString()

    override fun importConfig(json: String) = Unit
}
