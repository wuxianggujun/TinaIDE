package com.wuxianggujun.tinaide.core.compile.pipeline

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.FingerprintCalculator
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.ExecutionOutcome
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildExecutorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `execute passes planner build reason into strategy context`() = runTest {
        val projectRoot = tempFolder.newFolder("project")
        val buildDir = tempFolder.newFolder("build")
        val source = File(projectRoot, "main.c").apply { writeText("int main() { return 0; }\n") }
        val ctx = BuildContext(
            appContext = mockk(relaxed = true),
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = BuildSystem.SINGLE_FILE,
            options = BuildOptions(),
            projectId = "project",
        )
        val spec = ArtifactSpec(
            id = ArtifactId("project", "main", "debug"),
            expectedPath = File(buildDir, "main"),
            kind = ArtifactKind.EXECUTABLE,
            sources = listOf(source),
        )
        val strategy = RecordingStrategy()
        val reason = "fingerprint changed: trackedInputsHash"
        val plan = BuildPlan.Build(
            strategy = strategy,
            spec = spec,
            fingerprint = FingerprintCalculator().compute(ctx, spec),
            reason = reason,
        )

        BuildExecutor().execute(plan, ctx, SharedFlowBuildEventEmitter())

        assertThat(strategy.seenBuildReason).isEqualTo(reason)
        assertThat(ctx.buildReason).isNull()
    }

    @Test
    fun `configureOnly routes to strategy configureOnly and never triggers build`() = runTest {
        val projectRoot = tempFolder.newFolder("project")
        val buildDir = tempFolder.newFolder("build")
        val ctx = BuildContext(
            appContext = mockk(relaxed = true),
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = BuildSystem.SINGLE_FILE,
            options = BuildOptions(),
            projectId = "project",
        )
        val strategy = RecordingStrategy().apply {
            configureOnlyResult = ConfigureResult.Success(
                message = "configured",
                compileCommandsPath = File(buildDir, "compile_commands.json"),
            )
        }
        val plan = BuildPlan.ConfigureOnly(strategy)
        val emitter = SharedFlowBuildEventEmitter(replay = 64)

        val result = BuildExecutor().configureOnly(plan, ctx, emitter)

        // configure-only 只重生成编译数据库,绝不能落到 execute(全量编译)。
        assertThat(strategy.configureOnlyCalled).isTrue()
        assertThat(strategy.executeCalled).isFalse()
        assertThat(result).isInstanceOf(ConfigureResult.Success::class.java)
        val events = emitter.events.replayCache
        assertThat(events).contains(BuildEvent.Build.ConfigureStarted(ctx.target))
        assertThat(events.any { it is BuildEvent.Build.ConfigureCompleted }).isTrue()
    }

    private class RecordingStrategy : BuildStrategy {
        var seenBuildReason: String? = null
        var executeCalled: Boolean = false
        var configureOnlyCalled: Boolean = false
        var configureOnlyResult: ConfigureResult = ConfigureResult.Error("not set")

        override val buildSystem: BuildSystem = BuildSystem.SINGLE_FILE

        override suspend fun canHandle(projectRoot: File): Boolean = true

        override suspend fun describeOutput(ctx: BuildContext): ArtifactSpec? = null

        override suspend fun execute(
            ctx: BuildContext,
            spec: ArtifactSpec,
            fingerprint: BuildFingerprint,
            emitter: BuildEventEmitter,
        ): ExecutionOutcome {
            executeCalled = true
            seenBuildReason = ctx.buildReason
            return ExecutionOutcome.Failure("stop")
        }

        override suspend fun configureOnly(ctx: BuildContext): ConfigureResult {
            configureOnlyCalled = true
            return configureOnlyResult
        }

        override suspend fun clean(ctx: BuildContext, reconfigure: Boolean) = Unit

        override suspend fun getTargets(ctx: BuildContext): List<TargetInfo> = emptyList()
    }
}
