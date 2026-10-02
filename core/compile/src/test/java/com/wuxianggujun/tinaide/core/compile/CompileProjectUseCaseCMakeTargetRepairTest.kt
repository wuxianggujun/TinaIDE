package com.wuxianggujun.tinaide.core.compile

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildContextFactory
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildExecutor
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildOrchestrator
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildPlan
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildPlanner
import com.wuxianggujun.tinaide.core.compile.pipeline.EnvironmentValidator
import com.wuxianggujun.tinaide.core.compile.pipeline.LaunchDispatcher
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.Project
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class CompileProjectUseCaseCMakeTargetRepairTest {

    private lateinit var context: Application
    private lateinit var tempRoot: File
    private lateinit var projectRoot: File
    private lateinit var buildDir: File

    @Before
    fun setUp() {
        val realContext = RuntimeEnvironment.getApplication()
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns realContext.filesDir
        every { context.cacheDir } returns realContext.cacheDir
        every { context.assets } returns realContext.assets
        every { context.getSharedPreferences(any(), any()) } answers {
            realContext.getSharedPreferences(firstArg(), secondArg())
        }
        every { context.getString(any<Int>()) } answers { "string-${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg()) } answers {
            "string-${firstArg<Int>()}-formatted"
        }
        context.getSharedPreferences("tinaide_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tinaide_config", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.initialize(context, ConfigManager(context))

        tempRoot = Files.createTempDirectory("compile-cmake-target-repair-").toFile()
        projectRoot = File(tempRoot, "project").apply { mkdirs() }
        buildDir = File(projectRoot, "build")
        File(projectRoot, "CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.22)
            project(TargetRepair)
            add_executable(demo_test main.cpp)
            add_library(demo SHARED main.cpp)
            """.trimIndent()
        )
        File(projectRoot, "main.cpp").writeText("int main() { return 0; }\n")
        runBlocking {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = "Target Repair",
                buildSystem = ProjectBuildSystem.CMAKE,
                apkExportType = ProjectApkExportType.TERMINAL,
                defaultRunTargetName = "demo_test",
                defaultSdlTargetName = "demo",
            )
        }
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
        context.getSharedPreferences("tinaide_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tinaide_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `execute repairs invalid cmake target to metadata default target`() = runTest {
        var capturedCtx: BuildContext? = null
        val outputLines = mutableListOf<String>()
        val useCase = newUseCase(
            targets = listOf(
                TargetInfo("demo_test", TargetInfo.Type.EXECUTABLE, sources = listOf("main.cpp")),
                TargetInfo("demo", TargetInfo.Type.SHARED_LIBRARY, sources = listOf("main.cpp")),
            ),
            captureCtx = { capturedCtx = it },
            outputLines = outputLines,
        )

        useCase.execute(
            operation = CompileProjectUseCase.Operation.forBuild(),
            onProgress = {},
            runConfig = RunConfiguration(
                outputMode = OutputMode.TERMINAL,
                targetName = "old_template_target",
            ),
        )

        assertThat(capturedCtx?.target).isEqualTo("demo_test")
        assertThat(outputLines).contains("string-${Strings.cmake_run_target_auto_repaired}-formatted\n")
    }

    @Test
    fun `execute keeps valid cmake target instead of metadata default target`() = runTest {
        var capturedCtx: BuildContext? = null
        val outputLines = mutableListOf<String>()
        val useCase = newUseCase(
            targets = listOf(
                TargetInfo("demo_test", TargetInfo.Type.EXECUTABLE, sources = listOf("main.cpp")),
                TargetInfo("custom_target", TargetInfo.Type.EXECUTABLE, sources = listOf("main.cpp")),
            ),
            captureCtx = { capturedCtx = it },
            outputLines = outputLines,
        )

        useCase.execute(
            operation = CompileProjectUseCase.Operation.forBuild(),
            onProgress = {},
            runConfig = RunConfiguration(
                outputMode = OutputMode.TERMINAL,
                targetName = "custom_target",
            ),
        )

        assertThat(capturedCtx?.target).isEqualTo("custom_target")
        assertThat(outputLines).doesNotContain("string-${Strings.cmake_run_target_auto_repaired}-formatted\n")
    }

    private fun newUseCase(
        targets: List<TargetInfo>,
        captureCtx: (BuildContext) -> Unit,
        outputLines: MutableList<String>,
    ): CompileProjectUseCase {
        val strategy = mockk<BuildStrategy>()
        every { strategy.buildSystem } returns BuildSystem.CMAKE
        coEvery { strategy.getTargets(any()) } returns targets

        val planner = mockk<BuildPlanner>()
        coEvery { planner.plan(any(), any()) } coAnswers {
            captureCtx(secondArg())
            BuildPlan.Invalid("captured")
        }

        return CompileProjectUseCase(
            appContext = context,
            projectContext = projectContext(),
            outputManager = outputManager(outputLines),
            orchestratorProvider = {
                BuildOrchestrator(
                    validator = EnvironmentValidator(),
                    planner = planner,
                    executor = mockk<BuildExecutor>(relaxed = true),
                    dispatcher = mockk<LaunchDispatcher>(relaxed = true),
                    artifactStore = mockk<ArtifactStore>(relaxed = true),
                    events = SharedFlowBuildEventEmitter(),
                )
            },
            strategyRegistry = BuildStrategyRegistry(listOf(strategy)),
            buildContextFactory = BuildContextFactory(),
            terminalCommandBuilder = TerminalCommandBuilder(context),
            eventBus = SharedFlowBuildEventEmitter(),
        )
    }

    private fun outputManager(outputLines: MutableList<String>): IOutputManager {
        val outputManager = mockk<IOutputManager>(relaxed = true)
        every { outputManager.appendOutput(any(), any()) } answers {
            outputLines += firstArg<String>()
        }
        return outputManager
    }

    private fun projectContext(): IProjectContext {
        val project = Project(
            id = "target-repair",
            name = "Target Repair",
            rootPath = projectRoot.absolutePath,
            workspaceRootPath = projectRoot.absolutePath,
            files = emptyList(),
            buildDirPath = buildDir.absolutePath,
        )
        return mockk {
            every { getCurrentProject() } returns project
            every { currentProjectFlow } returns MutableStateFlow(project)
        }
    }
}
