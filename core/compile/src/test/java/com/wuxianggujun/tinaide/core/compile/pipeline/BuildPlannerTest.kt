package com.wuxianggujun.tinaide.core.compile.pipeline

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.BuildType
import com.wuxianggujun.tinaide.core.compile.CMakeBuildTypeOption
import com.wuxianggujun.tinaide.core.compile.CMakeGeneratorOption
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.compile.action.BuildIntent
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.FingerprintCalculator
import com.wuxianggujun.tinaide.core.compile.artifact.SourceRef
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import com.wuxianggujun.tinaide.core.compile.strategy.ExecutionOutcome
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import io.mockk.mockk
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BuildPlanner 决策路径覆盖:
 * - Force → Build
 * - None + 有缓存 → Skip
 * - None + 无缓存 → Invalid
 * - IfNeeded + 无缓存 → Build
 * - IfNeeded + fingerprint 不一致 → Build
 * - IfNeeded + tracked input 更新 → Build
 * - IfNeeded + 全命中 → Skip
 * - Clean → CleanOnly
 * - 无 strategy → Invalid
 * - describeOutput 返回 null → Invalid
 */
class BuildPlannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectRoot: File
    private lateinit var buildDir: File
    private lateinit var store: FakeArtifactStore
    private lateinit var strategy: FakeStrategy
    private lateinit var planner: BuildPlanner
    private val calculator = FingerprintCalculator()

    @Before
    fun setUp() {
        projectRoot = tempFolder.newFolder("project")
        buildDir = tempFolder.newFolder("build")
        store = FakeArtifactStore()
        strategy = FakeStrategy()
        val registry = BuildStrategyRegistry(listOf(strategy))
        planner = BuildPlanner(registry, store, FingerprintCalculator())
    }

    @Test
    fun `force intent always yields Build`() = runTest {
        store.put(newArtifact())
        val plan = planner.plan(CompileRequest(BuildIntent.Force, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
    }

    @Test
    fun `none intent with cached artifact yields Skip`() = runTest {
        val cached = newArtifact().also { store.put(it) }
        val plan = planner.plan(CompileRequest(BuildIntent.None, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Skip::class.java)
        assertThat((plan as BuildPlan.Skip).artifact.id).isEqualTo(cached.id)
    }

    @Test
    fun `none intent without cache yields Invalid`() = runTest {
        val plan = planner.plan(CompileRequest(BuildIntent.None, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Invalid::class.java)
    }

    @Test
    fun `ifNeeded without cache yields Build`() = runTest {
        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
    }

    @Test
    fun `ifNeeded with stale fingerprint yields Build`() = runTest {
        store.put(newArtifact(compilerType = "GCC"))
        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("fingerprint")
    }

    @Test
    fun `ifNeeded with cached artifact file missing yields Build`() = runTest {
        // 模拟"外部清理/损坏"场景:缓存记录里的路径指向已不存在的文件。
        // Planner 必须把这种情况识别为需要重建,避免下游 Launch 拿到失效产物。
        val missing = newArtifact().let { it.copy(absolutePath = File(buildDir, "not-there").absolutePath) }
        store.put(missing)
        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("cached artifact file missing")
    }

    @Test
    fun `none intent with cached file missing yields Invalid`() = runTest {
        // BuildIntent.None 专指"不要 build, 直接启动":若缓存文件不在, Planner 必须 Invalid,
        // 绝不能吐一个指向空路径的 Skip。
        val missing = newArtifact().let { it.copy(absolutePath = File(buildDir, "nothing").absolutePath) }
        store.put(missing)
        val plan = planner.plan(CompileRequest(BuildIntent.None, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Invalid::class.java)
    }

    @Test
    fun `ifNeeded with newer source yields Build`() = runTest {
        val sourceFile = File(projectRoot, "main.c").apply {
            writeText("int main() {}")
        }
        val cached = newArtifact(
            sources = listOf(SourceRef("main.c", mtime = sourceFile.lastModified() - 1L, size = sourceFile.length())),
            compiledAt = 0L,
        )
        store.put(cached)
        sourceFile.setLastModified(System.currentTimeMillis())

        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("tracked input changed")
    }

    @Test
    fun `ifNeeded with tracked input mtime mismatch yields Build even when compiledAt is newer`() = runTest {
        val sourceFile = File(projectRoot, "main.c").apply {
            writeText("int main() {}")
        }
        val cached = newArtifact(
            sources = listOf(SourceRef("main.c", mtime = sourceFile.lastModified() - 1_000L, size = sourceFile.length())),
            compiledAt = sourceFile.lastModified() + 10_000L,
        )
        store.put(cached)

        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("tracked input changed")
    }

    @Test
    fun `ifNeeded with changed tracked input set yields Build`() = runTest {
        val main = File(projectRoot, "main.c").apply { writeText("int main() {}") }
        val helper = File(projectRoot, "src/util.c").apply {
            parentFile?.mkdirs()
            writeText("int util() { return 1; }")
        }
        val oldSpec = defaultSpec(sources = listOf(main))
        val newSpec = defaultSpec(sources = listOf(main, helper))
        val cached = newArtifact(
            fingerprint = calculator.compute(newContext(), oldSpec),
            sources = oldSpec.sources.map(::sourceRefOf),
        )
        store.put(cached)
        strategy.describeOutputOverride = { newSpec }

        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("trackedInputsHash")
    }

    @Test
    fun `ifNeeded with empty cached source refs yields Build`() = runTest {
        val sourceFile = File(projectRoot, "main.c").apply {
            writeText("int main() { return 0; }")
        }
        val spec = defaultSpec(sources = listOf(sourceFile))
        val cached = newArtifact(
            fingerprint = calculator.compute(newContext(), spec),
            sources = emptyList(),
        )
        store.put(cached)
        strategy.describeOutputOverride = { spec }

        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("tracked input missing from cached artifact")
    }

    @Test
    fun `ifNeeded fully matched yields Skip`() = runTest {
        val sourceFile = File(projectRoot, "main.c").apply {
            writeText("int main() {}")
        }
        val cached = newArtifact(
            sources = listOf(sourceRefOf(sourceFile)),
            compiledAt = System.currentTimeMillis() + 10_000L,
        )
        store.put(cached)

        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Skip::class.java)
    }

    @Test
    fun `ifNeeded with same timestamp and size but changed content yields Build`() = runTest {
        val sourceFile = File(projectRoot, "main.c").apply {
            writeText("int main() { return 0; }")
        }
        val capturedTimestamp = sourceFile.lastModified()
        val cached = newArtifact(
            sources = listOf(sourceRefOf(sourceFile)),
            compiledAt = capturedTimestamp + 10_000L,
        )
        store.put(cached)

        sourceFile.writeText("int main() { return 1; }")
        sourceFile.setLastModified(capturedTimestamp)

        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("trackedInputsHash")
    }

    @Test
    fun `ifNeeded with reconfigure input hash mismatch yields Build`() = runTest {
        val sourceFile = File(projectRoot, "main.c").apply {
            writeText("int main() {}")
        }
        val cmakeFile = File(projectRoot, "CMakeLists.txt").apply {
            writeText("add_executable(app main.c)")
        }
        val spec = defaultSpec(
            sources = listOf(sourceFile),
        ).copy(
            reconfigureSources = listOf(cmakeFile),
        )
        val cached = newArtifact(
            fingerprint = calculator.compute(newContext(), spec),
            sources = listOf(sourceRefOf(sourceFile)),
        )
        store.put(cached)
        strategy.describeOutputOverride = { spec }

        cmakeFile.writeText("add_executable(app main.c)\n")

        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Build::class.java)
        assertThat((plan as BuildPlan.Build).reason).contains("reconfigureInputsHash")
    }

    @Test
    fun `clean intent yields CleanOnly`() = runTest {
        val plan = planner.plan(
            CompileRequest(BuildIntent.Clean(reconfigure = true), LaunchIntent.None),
            newContext(),
        )
        assertThat(plan).isInstanceOf(BuildPlan.CleanOnly::class.java)
        assertThat((plan as BuildPlan.CleanOnly).reconfigure).isTrue()
    }

    @Test
    fun `configureOnly intent yields ConfigureOnly without touching artifact cache`() = runTest {
        // ConfigureOnly 和 Clean 一样在 describeOutput 之前短路:不查缓存、不算指纹,
        // 只把解析到的 strategy 交给下游执行 configure。
        store.put(newArtifact())
        val plan = planner.plan(
            CompileRequest(BuildIntent.ConfigureOnly, LaunchIntent.None),
            newContext(),
        )
        assertThat(plan).isInstanceOf(BuildPlan.ConfigureOnly::class.java)
        assertThat((plan as BuildPlan.ConfigureOnly).strategy).isSameInstanceAs(strategy)
    }

    @Test
    fun `configureOnly without registered strategy yields Invalid`() = runTest {
        val emptyRegistry = BuildStrategyRegistry(emptyList())
        val planner = BuildPlanner(emptyRegistry, store, FingerprintCalculator())
        val plan = planner.plan(
            CompileRequest(BuildIntent.ConfigureOnly, LaunchIntent.None),
            newContext(),
        )
        assertThat(plan).isInstanceOf(BuildPlan.Invalid::class.java)
    }

    @Test
    fun `unknown buildSystem yields Invalid`() = runTest {
        val emptyRegistry = BuildStrategyRegistry(emptyList())
        val planner = BuildPlanner(emptyRegistry, store, FingerprintCalculator())
        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Invalid::class.java)
    }

    @Test
    fun `missing describeOutput yields Invalid`() = runTest {
        strategy.describeOutputOverride = { null }
        val plan = planner.plan(CompileRequest(BuildIntent.IfNeeded, LaunchIntent.None), newContext())
        assertThat(plan).isInstanceOf(BuildPlan.Invalid::class.java)
    }

    // ---------- helpers ----------

    private fun newContext(): BuildContext = BuildContext(
        appContext = mockk(relaxed = true),
        projectRoot = projectRoot,
        buildDir = buildDir,
        buildSystem = BuildSystem.SINGLE_FILE,
        options = BuildOptions(
            buildType = BuildType.DEBUG,
            compilerType = CompilerType.CLANG,
            cmakeBuildType = CMakeBuildTypeOption.DEBUG,
            cmakeGenerator = CMakeGeneratorOption.NINJA,
            resolvedRunMode = LinuxRunModePolicy.RunMode.NATIVE,
            parallelJobs = 4,
        ),
        projectId = "test-project",
        target = null,
    )

    private fun newArtifact(
        compilerType: String = "CLANG",
        fingerprint: BuildFingerprint? = null,
        sources: List<SourceRef> = emptyList(),
        compiledAt: Long = System.currentTimeMillis(),
    ): Artifact {
        val fp = fingerprint ?: calculator.compute(newContext(), defaultSpec()).copy(compilerType = compilerType)
        val file = File(buildDir, "hello").apply { writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) }
        return Artifact(
            id = ArtifactId("test-project", "hello", "default"),
            absolutePath = file.absolutePath,
            kind = ArtifactKind.EXECUTABLE,
            contentHash = "deadbeef",
            fingerprint = fp,
            sources = sources,
            compiledAt = compiledAt,
            buildTimeMs = 100L,
        )
    }

    private fun defaultSpec(sources: List<File> = listOf(File(projectRoot, "main.c"))): ArtifactSpec = ArtifactSpec(
        id = ArtifactId("test-project", "hello", "default"),
        expectedPath = File(buildDir, "hello"),
        kind = ArtifactKind.EXECUTABLE,
        sources = sources,
    )

    private fun sourceRefOf(file: File): SourceRef = SourceRef.capture(file, projectRoot)

    // ---------- fakes ----------

    private class FakeArtifactStore : ArtifactStore {
        private val map = ConcurrentHashMap<String, Artifact>()
        fun put(a: Artifact) {
            map[key(a.id, File(a.absolutePath).parentFile!!)] = a
        }
        override suspend fun find(id: ArtifactId, buildDir: File): Artifact? = map[key(id, buildDir)]
        override suspend fun register(artifact: Artifact, buildDir: File) {
            map[key(artifact.id, buildDir)] = artifact
        }
        override suspend fun invalidate(id: ArtifactId, buildDir: File) {
            map.remove(key(id, buildDir))
        }
        override suspend fun listAll(buildDir: File): List<Artifact> = map.filterKeys { it.endsWith("|${buildDir.absolutePath}") }.values.toList()
        override suspend fun clearAll(buildDir: File) {
            map.keys.filter { it.endsWith("|${buildDir.absolutePath}") }.forEach { map.remove(it) }
        }
        private fun key(id: ArtifactId, buildDir: File) = "${id.storageKey()}|${buildDir.absolutePath}"
    }

    private class FakeStrategy : BuildStrategy {
        var describeOutputOverride: ((BuildContext) -> ArtifactSpec?)? = null
        override val buildSystem: BuildSystem = BuildSystem.SINGLE_FILE
        override suspend fun canHandle(projectRoot: File): Boolean = true
        override suspend fun describeOutput(ctx: BuildContext): ArtifactSpec? {
            describeOutputOverride?.let { return it(ctx) }
            return ArtifactSpec(
                id = ArtifactId("test-project", "hello", "default"),
                expectedPath = File(ctx.buildDir, "hello"),
                kind = ArtifactKind.EXECUTABLE,
                sources = listOf(File(ctx.projectRoot, "main.c")),
            )
        }
        override suspend fun execute(
            ctx: BuildContext,
            spec: ArtifactSpec,
            fingerprint: BuildFingerprint,
            emitter: BuildEventEmitter,
        ): ExecutionOutcome = ExecutionOutcome.Failure("not used in planner tests")
        override suspend fun clean(ctx: BuildContext, reconfigure: Boolean) = Unit
        override suspend fun getTargets(ctx: BuildContext): List<TargetInfo> = emptyList()
    }
}
