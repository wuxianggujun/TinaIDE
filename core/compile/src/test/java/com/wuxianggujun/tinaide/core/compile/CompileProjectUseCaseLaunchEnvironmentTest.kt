package com.wuxianggujun.tinaide.core.compile

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.event.BuildReport
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.launcher.LaunchDescriptor
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildContextFactory
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildExecutor
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildOrchestrator
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildPlan
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildPlanner
import com.wuxianggujun.tinaide.core.compile.pipeline.EnvironmentValidator
import com.wuxianggujun.tinaide.core.compile.pipeline.LaunchDispatcher
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.ndk.SysrootProfileInfo
import com.wuxianggujun.tinaide.core.ndk.SysrootProfileType
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.Project
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectSdlVersion
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
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
class CompileProjectUseCaseLaunchEnvironmentTest {

    private lateinit var context: Application
    private lateinit var tempRoot: File
    private lateinit var projectRoot: File
    private lateinit var buildDir: File
    private lateinit var runtimeDir: File

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

        File(context.filesDir, "installed-packages").deleteRecursively()
        File(context.filesDir, "sysroot-profile-config.json").delete()
        File(context.filesDir, "android-sysroot").deleteRecursively()
        File(context.filesDir, "android-sysroots").deleteRecursively()
        LocalInstallStateStore(context).clear()

        tempRoot = Files.createTempDirectory("compile-launch-env-").toFile()
        projectRoot = File(tempRoot, "project").apply { mkdirs() }
        buildDir = File(projectRoot, "build")
        runtimeDir = File(projectRoot, "runtime-libs").apply { mkdirs() }
        File(projectRoot, "main.cpp").writeText("int main() { return 0; }\n")
        ProjectMetadataStore.ensure(
            projectRoot = projectRoot,
            displayNameFallback = "Launch Env",
            buildSystem = ProjectBuildSystem.SINGLE_FILE,
        )
        ProjectMetadataStore.updateNativeDependencyPaths(
            projectRoot = projectRoot,
            includeDirs = emptyList(),
            libraryDirs = emptyList(),
            runtimeDirs = listOf("runtime-libs"),
        )
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
        File(context.filesDir, "installed-packages").deleteRecursively()
        File(context.filesDir, "sysroot-profile-config.json").delete()
        File(context.filesDir, "android-sysroot").deleteRecursively()
        File(context.filesDir, "android-sysroots").deleteRecursively()
        context.getSharedPreferences("tinaide_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tinaide_config", Context.MODE_PRIVATE).edit().clear().commit()
        LocalInstallStateStore(context).clear()
    }

    @Test
    fun `debug launch environment includes project runtime library dirs`() = runTest {
        val artifact = newArtifact(File(buildDir, "demo"))
        val planner = mockk<BuildPlanner>()
        val dispatcher = mockk<LaunchDispatcher>()
        coEvery { planner.plan(any(), any()) } returns BuildPlan.Skip(artifact, "cached for launch environment test")
        coEvery { dispatcher.dispatch(any(), artifact, true, any(), any()) } returns BuildReport.Success(
            artifact = artifact,
            descriptor = LaunchDescriptor.Debug(
                artifact = artifact,
                programPath = artifact.absolutePath,
                workingDir = buildDir.absolutePath,
            ),
            summary = "debug ready",
        )

        val useCase = newUseCase(planner, dispatcher)

        val result = useCase.execute(
            operation = CompileProjectUseCase.Operation.forDebug(),
            onProgress = {},
            launchEnvironment = mapOf("LD_LIBRARY_PATH" to "/manual/lib"),
        )

        val launch = (result as CompileProjectUseCase.Result.Success).report.launch
            as CompileProjectUseCase.LaunchSpec.Debug
        val ldLibraryPath = launch.environment["LD_LIBRARY_PATH"].orEmpty()
        val runtimePath = runtimeDir.canonicalFile.absolutePath
        assertThat(ldLibraryPath).contains(runtimePath)
        assertThat(ldLibraryPath).contains("/manual/lib")
        assertThat(ldLibraryPath.indexOf(runtimePath)).isLessThan(ldLibraryPath.indexOf("/manual/lib"))
    }

    @Test
    fun `sdl launch environment includes active sysroot runtime library dir`() = runTest {
        val sysrootRuntimeDir = createInstalledSysrootRuntime()
        val artifact = newArtifact(
            file = File(buildDir, "libdemo.so"),
            kind = ArtifactKind.SHARED_LIBRARY,
        )
        val planner = mockk<BuildPlanner>()
        val dispatcher = mockk<LaunchDispatcher>()
        coEvery { planner.plan(any(), any()) } returns BuildPlan.Skip(artifact, "cached for SDL launch environment test")
        coEvery { dispatcher.dispatch(any(), artifact, true, any(), any()) } returns BuildReport.Success(
            artifact = artifact,
            descriptor = LaunchDescriptor.Sdl(
                artifact = artifact,
                libraryPath = artifact.absolutePath,
            ),
            summary = "sdl ready",
        )

        val result = newUseCase(planner, dispatcher).execute(
            operation = CompileProjectUseCase.Operation.forRun(),
            onProgress = {},
            runConfig = RunConfiguration(
                outputMode = OutputMode.SDL,
                sdlVersion = ProjectSdlVersion.SDL3,
                sdlOrientation = SdlOrientation.LANDSCAPE,
                enableFloatingLog = true,
            ),
            launchEnvironment = mapOf("LD_LIBRARY_PATH" to "/manual/lib"),
        )

        val launch = (result as CompileProjectUseCase.Result.Success).report.launch
            as CompileProjectUseCase.LaunchSpec.Sdl
        val ldLibraryPath = launch.environment["LD_LIBRARY_PATH"].orEmpty()
        val sysrootPath = sysrootRuntimeDir.absolutePath
        val runtimePath = runtimeDir.canonicalFile.absolutePath
        assertThat(launch.preferredSdlMajor).isEqualTo(3)
        assertThat(launch.orientation).isEqualTo(SdlOrientation.LANDSCAPE)
        assertThat(launch.enableFloatingLog).isTrue()
        assertThat(ldLibraryPath).contains(sysrootPath)
        assertThat(ldLibraryPath).contains(runtimePath)
        assertThat(ldLibraryPath).contains("/manual/lib")
        assertThat(ldLibraryPath.indexOf(sysrootPath)).isLessThan(ldLibraryPath.indexOf(runtimePath))
        assertThat(ldLibraryPath.indexOf(runtimePath)).isLessThan(ldLibraryPath.indexOf("/manual/lib"))
    }

    @Test
    fun `native activity launch environment includes runtime library dirs`() = runTest {
        val sysrootRuntimeDir = createInstalledSysrootRuntime()
        val artifact = newArtifact(
            file = File(buildDir, "libmain.so"),
            kind = ArtifactKind.SHARED_LIBRARY,
        )
        val planner = mockk<BuildPlanner>()
        val dispatcher = mockk<LaunchDispatcher>()
        coEvery { planner.plan(any(), any()) } returns BuildPlan.Skip(
            artifact,
            "cached for NativeActivity launch environment test",
        )
        coEvery { dispatcher.dispatch(any(), artifact, true, any(), any()) } returns BuildReport.Success(
            artifact = artifact,
            descriptor = LaunchDescriptor.NativeActivity(
                artifact = artifact,
                libraryPath = artifact.absolutePath,
            ),
            summary = "native activity ready",
        )

        val result = newUseCase(planner, dispatcher).execute(
            operation = CompileProjectUseCase.Operation.forRun(),
            onProgress = {},
            runConfig = RunConfiguration(
                outputMode = OutputMode.NATIVE_ACTIVITY,
                enableFloatingLog = true,
            ),
            launchEnvironment = mapOf("LD_LIBRARY_PATH" to "/manual/lib"),
        )

        val launch = (result as CompileProjectUseCase.Result.Success).report.launch
            as CompileProjectUseCase.LaunchSpec.NativeActivity
        val ldLibraryPath = launch.environment["LD_LIBRARY_PATH"].orEmpty()
        val runtimePath = runtimeDir.canonicalFile.absolutePath
        assertThat(launch.libraryPath).isEqualTo(artifact.absolutePath)
        assertThat(launch.enableFloatingLog).isTrue()
        assertThat(ldLibraryPath).contains(sysrootRuntimeDir.absolutePath)
        assertThat(ldLibraryPath).contains(runtimePath)
        assertThat(ldLibraryPath).contains("/manual/lib")
        assertThat(ldLibraryPath.indexOf(sysrootRuntimeDir.absolutePath))
            .isLessThan(ldLibraryPath.indexOf(runtimePath))
        assertThat(ldLibraryPath.indexOf(runtimePath)).isLessThan(ldLibraryPath.indexOf("/manual/lib"))
    }

    @Test
    fun `sdl launch environment uses artifact sysroot profile instead of current active profile`() = runTest {
        val artifactRuntimeDir = createSysrootProfileRuntime("sysroot-artifact")
        val activeRuntimeDir = createSysrootProfileRuntime("sysroot-active", activate = true)
        val artifact = newArtifact(
            file = File(buildDir, "libdemo.so"),
            kind = ArtifactKind.SHARED_LIBRARY,
            sysrootProfileId = "sysroot-artifact",
        )
        val planner = mockk<BuildPlanner>()
        val dispatcher = mockk<LaunchDispatcher>()
        coEvery { planner.plan(any(), any()) } returns BuildPlan.Skip(artifact, "cached for runtime identity test")
        coEvery { dispatcher.dispatch(any(), artifact, true, any(), any()) } returns BuildReport.Success(
            artifact = artifact,
            descriptor = LaunchDescriptor.Sdl(
                artifact = artifact,
                libraryPath = artifact.absolutePath,
            ),
            summary = "sdl ready",
        )

        val result = newUseCase(planner, dispatcher).execute(
            operation = CompileProjectUseCase.Operation.forRun(),
            onProgress = {},
            runConfig = RunConfiguration(outputMode = OutputMode.SDL),
            launchEnvironment = mapOf("LD_LIBRARY_PATH" to "/manual/lib"),
        )

        val launch = (result as CompileProjectUseCase.Result.Success).report.launch
            as CompileProjectUseCase.LaunchSpec.Sdl
        val ldLibraryPath = launch.environment["LD_LIBRARY_PATH"].orEmpty()
        assertThat(ldLibraryPath).contains(artifactRuntimeDir.absolutePath)
        assertThat(ldLibraryPath).doesNotContain(activeRuntimeDir.absolutePath)
        assertThat(ldLibraryPath.indexOf(artifactRuntimeDir.absolutePath))
            .isLessThan(ldLibraryPath.indexOf("/manual/lib"))
    }

    private fun projectContext(): IProjectContext {
        val project = Project(
            id = "launch-env",
            name = "Launch Env",
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

    private fun singleFileStrategyRegistry(): BuildStrategyRegistry {
        val strategy = mockk<BuildStrategy>()
        every { strategy.buildSystem } returns BuildSystem.SINGLE_FILE
        return BuildStrategyRegistry(listOf(strategy))
    }

    private fun newUseCase(
        planner: BuildPlanner,
        dispatcher: LaunchDispatcher,
    ): CompileProjectUseCase = CompileProjectUseCase(
        appContext = context,
        projectContext = projectContext(),
        outputManager = mockk<IOutputManager>(relaxed = true),
        orchestratorProvider = {
            BuildOrchestrator(
                validator = EnvironmentValidator(),
                planner = planner,
                executor = mockk<BuildExecutor>(relaxed = true),
                dispatcher = dispatcher,
                artifactStore = mockk<ArtifactStore>(relaxed = true),
                events = SharedFlowBuildEventEmitter(),
            )
        },
        strategyRegistry = singleFileStrategyRegistry(),
        buildContextFactory = BuildContextFactory(),
        terminalCommandBuilder = TerminalCommandBuilder(context),
        eventBus = SharedFlowBuildEventEmitter(),
    )

    private fun createInstalledSysrootRuntime(): File {
        val arch = AndroidSysrootManager.Companion.Arch.current()
        val sysrootDir = AndroidSysrootManager(context).getSysrootDir(arch)
        return createCompleteSysrootRuntime(sysrootDir, arch, "runtime")
    }

    private fun createSysrootProfileRuntime(profileId: String, activate: Boolean = false): File {
        val arch = AndroidSysrootManager.Companion.Arch.current()
        val manager = AndroidSysrootManager(context)
        val profileDir = manager.getConfigManager().getProfileDir(profileId)
        val runtimeDir = createCompleteSysrootRuntime(profileDir, arch, profileId)
        manager.getConfigManager().registerOrReplaceProfile(
            SysrootProfileInfo(
                id = profileId,
                name = profileId,
                arch = arch.name,
                type = SysrootProfileType.CUSTOM,
                path = "android-sysroots/$profileId",
                installedAt = System.currentTimeMillis(),
                apiLevels = listOf(28),
                toolchainTriple = arch.triple,
            )
        ).getOrThrow()
        if (activate) {
            manager.activateProfile(profileId, arch).getOrThrow()
        }
        return runtimeDir.absoluteFile
    }

    private fun createCompleteSysrootRuntime(
        sysrootDir: File,
        arch: AndroidSysrootManager.Companion.Arch,
        sharedRuntimeContent: String,
    ): File {
        File(sysrootDir, "usr/include/android").apply { mkdirs() }
            .resolve("api-level.h")
            .writeText("#define __ANDROID_API__ 28\n")
        val apiRuntimeDir = File(sysrootDir, "usr/lib/${arch.triple}/28").apply { mkdirs() }
        File(apiRuntimeDir, "libc++.a").writeText("INPUT(-lc++_static -lc++abi)\n")
        return File(sysrootDir, "usr/lib/${arch.triple}").apply {
            mkdirs()
            File(this, NativeRuntimeLibraryPaths.CXX_SHARED_LIBRARY_NAME).writeText(sharedRuntimeContent)
            File(this, "libc++_static.a").writeText("runtime")
            File(this, "libc++abi.a").writeText("runtime")
        }.absoluteFile
    }

    private fun newArtifact(
        file: File,
        kind: ArtifactKind = ArtifactKind.EXECUTABLE,
        sysrootProfileId: String? = null,
    ): Artifact {
        file.parentFile?.mkdirs()
        file.writeText("artifact")
        return Artifact(
            id = ArtifactId(projectId = "launch-env", targetName = file.nameWithoutExtension),
            absolutePath = file.absolutePath,
            kind = kind,
            contentHash = "hash",
            fingerprint = BuildFingerprint(
                compilerType = "clang",
                compilerPath = "clang",
                toolchainId = null,
                sysrootProfileId = sysrootProfileId,
                sysrootApiLevel = 28,
                buildType = "DEBUG",
                cmakeBuildType = null,
                cmakeGenerator = null,
                cFlags = "",
                cppFlags = "",
                ldFlags = "",
                ldLibs = "",
                cmakeExtraArgs = "",
                cppStandard = null,
                optimizationLevel = "O0",
                generateDebugInfo = true,
                preferSharedLibraryForRun = false,
                parallelJobs = 1,
                resolvedRunMode = "NATIVE",
                artifactKind = kind.name,
                expectedOutputPath = file.absolutePath,
                trackedInputsHash = "inputs",
            ),
            sources = emptyList(),
            compiledAt = 1L,
            buildTimeMs = 1L,
        )
    }
}
