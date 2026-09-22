package com.wuxianggujun.tinaide.core.compile.pipeline

import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticsLog
import com.wuxianggujun.tinaide.core.compile.action.BuildIntent
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.FingerprintCalculator
import com.wuxianggujun.tinaide.core.compile.artifact.SourceRef
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import java.io.File
import timber.log.Timber

/**
 * 决策中心:把 `CompileRequest` 翻译成具体的 [BuildPlan]。
 *
 * 唯一职责:
 * - 基于 [BuildStrategyRegistry] 查找策略
 * - 调用 [BuildStrategy.describeOutput] 得到产物规格
 * - 综合 [ArtifactStore] 的缓存 + [FingerprintCalculator] 的指纹做增量判定
 *
 * 不做的事情:
 * - 不执行构建(交给 [BuildExecutor])
 * - 不发射 BuildEvent(由 Orchestrator 发)
 */
class BuildPlanner(
    private val strategyRegistry: BuildStrategyRegistry,
    private val artifactStore: ArtifactStore,
    private val fingerprintCalculator: FingerprintCalculator,
) {

    companion object {
        private const val TAG = "BuildPlanner"
    }

    suspend fun plan(request: CompileRequest, ctx: BuildContext): BuildPlan {
        val strategy = strategyRegistry.resolve(ctx.buildSystem)
            ?: return BuildPlan.Invalid("no strategy registered for buildSystem=${ctx.buildSystem}")

        if (request.build is BuildIntent.Clean) {
            return BuildPlan.CleanOnly(strategy, request.build.reconfigure)
        }

        if (request.build is BuildIntent.ConfigureOnly) {
            return BuildPlan.ConfigureOnly(strategy)
        }

        val spec = strategy.describeOutput(ctx)
            ?: return BuildPlan.Invalid("strategy cannot describe output (target=${ctx.target})")

        val expectedFingerprint = fingerprintCalculator.compute(ctx, spec)
        BuildDiagnosticsLog.i {
            "planner described output buildSystem=${ctx.buildSystem} target=${ctx.target.orEmpty()} " +
                "spec=${spec.id.storageKey()} kind=${spec.kind} expected=${spec.expectedPath.absolutePath} " +
                "sources=${spec.sources.size} reconfigureSources=${spec.reconfigureSources.size} " +
                "trackedHash=${expectedFingerprint.trackedInputsHash.shortHash()} " +
                "reconfigureHash=${expectedFingerprint.reconfigureInputsHash?.shortHash().orEmpty()} " +
                "buildIntent=${request.build::class.simpleName} launchIntent=${request.launch::class.simpleName}"
        }

        return when (val intent = request.build) {
            BuildIntent.Force -> {
                BuildDiagnosticsLog.i { "planner decision=build reason=force spec=${spec.id.storageKey()}" }
                BuildPlan.Build(strategy, spec, expectedFingerprint, "force rebuild requested")
            }
            BuildIntent.None -> planLaunchOnly(spec, ctx)
            BuildIntent.IfNeeded -> planIncremental(strategy, spec, expectedFingerprint, ctx)
            is BuildIntent.Clean -> error("unreachable (handled above): $intent")
            BuildIntent.ConfigureOnly -> error("unreachable (handled above): $intent")
        }
    }

    private suspend fun planLaunchOnly(spec: ArtifactSpec, ctx: BuildContext): BuildPlan {
        val cached = artifactStore.find(spec.id, ctx.buildDir)
        return if (cached != null && cached.file().isFile) {
            BuildDiagnosticsLog.i {
                "planner decision=skip launchOnly spec=${spec.id.storageKey()} artifact=${cached.absolutePath} " +
                    "contentHash=${cached.contentHash.shortHash()} trackedHash=${cached.fingerprint.trackedInputsHash.shortHash()}"
            }
            BuildPlan.Skip(cached, "BuildIntent.None: using existing cached artifact")
        } else {
            BuildDiagnosticsLog.w {
                "planner decision=invalid launchOnly spec=${spec.id.storageKey()} reason=no cached artifact"
            }
            BuildPlan.Invalid("BuildIntent.None but no cached artifact available for ${spec.id.storageKey()}")
        }
    }

    private suspend fun planIncremental(
        strategy: BuildStrategy,
        spec: ArtifactSpec,
        expected: BuildFingerprint,
        ctx: BuildContext,
    ): BuildPlan {
        val cached = artifactStore.find(spec.id, ctx.buildDir)
            ?: run {
                BuildDiagnosticsLog.i { "planner decision=build spec=${spec.id.storageKey()} reason=no cached artifact" }
                return BuildPlan.Build(strategy, spec, expected, "no cached artifact")
            }

        BuildDiagnosticsLog.i {
            "planner cache candidate spec=${spec.id.storageKey()} artifact=${cached.absolutePath} " +
                "kind=${cached.kind} contentHash=${cached.contentHash.shortHash()} sources=${cached.sources.size} " +
                "cachedTrackedHash=${cached.fingerprint.trackedInputsHash.shortHash()} " +
                "expectedTrackedHash=${expected.trackedInputsHash.shortHash()} compiledAt=${cached.compiledAt}"
        }

        val cachedFile = cached.file()
        if (!cachedFile.isFile) {
            Timber.tag(TAG).d("cached artifact file missing: %s", cachedFile.absolutePath)
            BuildDiagnosticsLog.i {
                "planner decision=build spec=${spec.id.storageKey()} reason=cached artifact file missing " +
                    "artifact=${cachedFile.absolutePath}"
            }
            return BuildPlan.Build(strategy, spec, expected, "cached artifact file missing")
        }
        if (cached.fingerprint != expected) {
            Timber.tag(TAG).d("fingerprint mismatch for %s", spec.id.storageKey())
            val reason = fingerprintDiffReason(cached.fingerprint, expected)
            BuildDiagnosticsLog.i {
                "planner decision=build spec=${spec.id.storageKey()} reason=$reason " +
                    "cached=${cached.fingerprint.diagnosticsSummary()} expected=${expected.diagnosticsSummary()}"
            }
            return BuildPlan.Build(strategy, spec, expected, reason)
        }

        val missingTrackedInput = missingTrackedInput(spec, cached.sources, ctx.projectRoot)
        if (missingTrackedInput != null) {
            Timber.tag(TAG).d("tracked input missing from cached artifact: %s", missingTrackedInput)
            BuildDiagnosticsLog.i {
                "planner decision=build spec=${spec.id.storageKey()} reason=tracked input missing from cache " +
                    "input=$missingTrackedInput"
            }
            return BuildPlan.Build(
                strategy,
                spec,
                expected,
                "tracked input missing from cached artifact: $missingTrackedInput",
            )
        }

        val reconfigureInputsHash = expected.reconfigureInputsHash
        if (reconfigureInputsHash != null && cached.fingerprint.reconfigureInputsHash != reconfigureInputsHash) {
            Timber.tag(TAG).d("reconfigure inputs changed for %s", spec.id.storageKey())
            BuildDiagnosticsLog.i {
                "planner decision=build spec=${spec.id.storageKey()} reason=reconfigure inputs changed " +
                    "cached=${cached.fingerprint.reconfigureInputsHash?.shortHash().orEmpty()} " +
                    "expected=${reconfigureInputsHash.shortHash()}"
            }
            return BuildPlan.Build(strategy, spec, expected, "reconfigureInputsHash")
        }

        val changedInput = firstChangedInput(cached.sources, ctx.projectRoot)
        if (changedInput != null) {
            Timber.tag(TAG).d("tracked input changed: %s", changedInput.cached.relativePath)
            BuildDiagnosticsLog.i {
                "planner decision=build spec=${spec.id.storageKey()} reason=tracked input changed " +
                    "input=${changedInput.cached.relativePath} cached=${changedInput.cached.diagnosticsSummary()} " +
                    "current=${changedInput.current?.diagnosticsSummary() ?: "<missing>"}"
            }
            return BuildPlan.Build(strategy, spec, expected, "tracked input changed: ${changedInput.cached.relativePath}")
        }

        BuildDiagnosticsLog.i {
            "planner decision=skip spec=${spec.id.storageKey()} reason=up-to-date " +
                "artifact=${cached.absolutePath} trackedHash=${expected.trackedInputsHash.shortHash()} " +
                "sources=${cached.sources.size}"
        }
        return BuildPlan.Skip(cached, "up-to-date")
    }

    /** 给出 fingerprint 差异的简短说明(首个变化的字段),便于 UI "为什么要重建" 展示。 */
    private fun fingerprintDiffReason(old: BuildFingerprint, expected: BuildFingerprint): String {
        val checks: List<Pair<String, () -> Boolean>> = listOf(
            "schemaVersion" to { old.schemaVersion != expected.schemaVersion },
            "compilerType" to { old.compilerType != expected.compilerType },
            "compilerPath" to { old.compilerPath != expected.compilerPath },
            "toolchainId" to { old.toolchainId != expected.toolchainId },
            "sysrootProfileId" to { old.sysrootProfileId != expected.sysrootProfileId },
            "sysrootApiLevel" to { old.sysrootApiLevel != expected.sysrootApiLevel },
            "buildType" to { old.buildType != expected.buildType },
            "cmakeBuildType" to { old.cmakeBuildType != expected.cmakeBuildType },
            "cmakeGenerator" to { old.cmakeGenerator != expected.cmakeGenerator },
            "cFlags" to { old.cFlags != expected.cFlags },
            "cppFlags" to { old.cppFlags != expected.cppFlags },
            "ldFlags" to { old.ldFlags != expected.ldFlags },
            "ldLibs" to { old.ldLibs != expected.ldLibs },
            "cmakeExtraArgs" to { old.cmakeExtraArgs != expected.cmakeExtraArgs },
            "cppStandard" to { old.cppStandard != expected.cppStandard },
            "optimizationLevel" to { old.optimizationLevel != expected.optimizationLevel },
            "generateDebugInfo" to { old.generateDebugInfo != expected.generateDebugInfo },
            "preferSharedLibraryForRun" to { old.preferSharedLibraryForRun != expected.preferSharedLibraryForRun },
            "parallelJobs" to { old.parallelJobs != expected.parallelJobs },
            "resolvedRunMode" to { old.resolvedRunMode != expected.resolvedRunMode },
            "artifactKind" to { old.artifactKind != expected.artifactKind },
            "expectedOutputPath" to { old.expectedOutputPath != expected.expectedOutputPath },
            "trackedInputsHash" to { old.trackedInputsHash != expected.trackedInputsHash },
            "reconfigureInputsHash" to { old.reconfigureInputsHash != expected.reconfigureInputsHash },
        )
        return checks.firstOrNull { it.second() }?.first?.let { "fingerprint changed: $it" }
            ?: "fingerprint changed"
    }

    private fun missingTrackedInput(spec: ArtifactSpec, cachedSources: List<SourceRef>, projectRoot: File): String? {
        val cachedPaths = cachedSources
            .asSequence()
            .map { normalizeRelativePath(it.relativePath) }
            .toSet()
        return spec.sources
            .asSequence()
            .map { normalizeRelativePath(it.absoluteFile.relativeToOrSelf(projectRoot.absoluteFile).path) }
            .sorted()
            .firstOrNull { it !in cachedPaths }
    }

    private fun firstChangedInput(cachedSources: List<SourceRef>, projectRoot: File): TrackedInputChange? =
        cachedSources.firstNotNullOfOrNull { ref ->
            val file = File(projectRoot, ref.relativePath)
            val current = file.takeIf { it.isFile }?.let { SourceRef.capture(it, projectRoot) }
            if (current == ref) {
                null
            } else {
                TrackedInputChange(cached = ref, current = current)
            }
        }

    private fun normalizeRelativePath(path: String): String = path.replace('\\', '/')

    private fun BuildFingerprint.diagnosticsSummary(): String =
        "schema=$schemaVersion toolchain=${toolchainId.orEmpty()} sysroot=${sysrootProfileId.orEmpty()} " +
            "api=$sysrootApiLevel kind=$artifactKind output=$expectedOutputPath " +
            "tracked=${trackedInputsHash.shortHash()} reconfigure=${reconfigureInputsHash?.shortHash().orEmpty()}"

    private fun SourceRef.diagnosticsSummary(): String =
        "mtime=$mtime size=$size hash=${contentHash?.shortHash().orEmpty()}"

    private fun String.shortHash(): String = take(12)

    private data class TrackedInputChange(
        val cached: SourceRef,
        val current: SourceRef?,
    )
}
