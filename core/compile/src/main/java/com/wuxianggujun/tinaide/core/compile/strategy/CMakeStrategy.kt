package com.wuxianggujun.tinaide.core.compile.strategy

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.cmake.CMake
import com.wuxianggujun.tinaide.cmake.CMakeDoc
import com.wuxianggujun.tinaide.cmake.analysis.CMakeAnalyzer
import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticsLog
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildResult
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.CleanResult
import com.wuxianggujun.tinaide.core.compile.CMakeBuildTypeOption
import com.wuxianggujun.tinaide.core.compile.CMakeConfigurationIdentity
import com.wuxianggujun.tinaide.core.compile.CMakeGeneratorOption
import com.wuxianggujun.tinaide.core.compile.CompileTimeoutConfig
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.FingerprintCalculator
import com.wuxianggujun.tinaide.core.compile.artifact.SourceRef
import com.wuxianggujun.tinaide.core.compile.artifact.TrackedInputCollector
import com.wuxianggujun.tinaide.core.compile.cmake.CMakeBuildExecutor
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.packages.PackageAbiCompatibility
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/**
 * CMake 构建策略。
 *
 * 设计要点:
 * - [describeOutput] 用 CMakeAnalyzer 解析 CMakeLists.txt,选定 target + 预测路径/kind,不 spawn 构建
 * - [execute] 自带 needsReconfigure 检测 + configure(PRoot / Native 双路径分派) + build + Artifact 包装
 * - 产物定位靠 [CMakeBuildExecutor] / [NativeCMakeBuildExecutor] 返回的 outputPath
 *   作为权威值,`spec.expectedPath` 仅用于首次构建前的 Planner 粗判
 * - 事件经 [BuildEventEmitter] 下发
 */
class CMakeStrategy(
    private val context: Context,
    private val prootEnv: PRootEnvironment? = null,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
    timeoutConfig: CompileTimeoutConfig? = null,
) : BuildStrategy {

    companion object {
        private const val TAG = "CMakeStrategy"

        internal fun shouldCleanBeforeBuild(reason: String?): Boolean {
            val normalized = reason.normalizedBuildReason()
            if (normalized.isEmpty()) return false
            return normalized == "force rebuild requested" ||
                normalized == "no cached artifact" ||
                normalized == "cached artifact file missing" ||
                normalized.startsWith("fingerprint changed:") ||
                normalized.startsWith("tracked input changed") ||
                normalized.startsWith("tracked input missing")
        }

        internal fun shouldForceReconfigureForReason(reason: String?): Boolean =
            "reconfigureInputsHash" in reason.normalizedBuildReason()

        private fun String?.normalizedBuildReason(): String = orEmpty().trim()
    }

    private val sharedTimeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(context)

    @Volatile
    private var lastResolvedRunMode: LinuxRunModePolicy.RunMode = LinuxRunModePolicy.RunMode.NATIVE

    private val prootExecutor by lazy {
        CMakeBuildExecutor(context, resolvePRootEnvironment(), sharedTimeoutConfig)
    }
    private val nativeExecutor by lazy {
        NativeCMakeBuildExecutor(context, sharedTimeoutConfig)
    }
    private val nativeToolchainManager by lazy {
        AndroidNativeToolchainManager(context.applicationContext)
    }
    private val pathResolver by lazy { ToolchainPathResolver(context) }

    override val buildSystem: BuildSystem = BuildSystem.CMAKE

    override suspend fun canHandle(projectRoot: File): Boolean = File(projectRoot, "CMakeLists.txt").exists()

    override suspend fun describeOutput(ctx: BuildContext): ArtifactSpec? {
        val all = loadTargets(ctx.projectRoot, ctx.buildDir)
        BuildDiagnosticsLog.i {
            "cmake describeOutput targets loaded project=${ctx.projectRoot.absolutePath} " +
                "requested=${ctx.target.orEmpty()} preferSharedLibraryForRun=${ctx.options.preferSharedLibraryForRun} " +
                "count=${all.size} targets=${all.diagnosticsSummary()}"
        }
        if (all.isEmpty()) {
            Timber.tag(TAG).w("describeOutput: no targets in %s", ctx.projectRoot.absolutePath)
            BuildDiagnosticsLog.w { "cmake describeOutput failed: no targets project=${ctx.projectRoot.absolutePath}" }
            return null
        }
        val selected = selectTarget(all, ctx.target, ctx.options) ?: run {
            Timber.tag(TAG).w("describeOutput: cannot resolve target=%s", ctx.target)
            BuildDiagnosticsLog.w {
                "cmake describeOutput failed: cannot resolve target requested=${ctx.target.orEmpty()} targets=${all.diagnosticsSummary()}"
            }
            return null
        }

        val kind = mapKind(selected.type)
        val outputName = selected.outputName.orEmpty().ifBlank { selected.name }
        val expectedFileName = when (kind) {
            ArtifactKind.SHARED_LIBRARY -> if (outputName.startsWith("lib")) "$outputName.so" else "lib$outputName.so"
            ArtifactKind.STATIC_LIBRARY -> if (outputName.startsWith("lib")) "$outputName.a" else "lib$outputName.a"
            else -> outputName
        }

        val sourceFiles = selected.sources
            .map { relative -> File(ctx.projectRoot, relative) }
            .filter { it.isFile }
        val targetClosure = resolveTargetClosure(selected, all)
        val compileInputs = TrackedInputCollector.collectCMakeCompileInputs(
            projectRoot = ctx.projectRoot,
            buildDir = ctx.buildDir,
            targetNames = targetClosure,
            targetSources = sourceFiles,
        )
        val reconfigureInputs = TrackedInputCollector.collectCMakeReconfigureInputs(ctx.projectRoot)

        val spec = ArtifactSpec(
            id = ArtifactId(
                projectId = ctx.projectId,
                targetName = selected.name,
                variant = resolveVariant(ctx),
            ),
            expectedPath = File(ctx.buildDir, expectedFileName),
            kind = kind,
            sources = compileInputs,
            reconfigureSources = reconfigureInputs,
        )
        BuildDiagnosticsLog.i {
            "cmake describeOutput selected=${selected.diagnosticsSummary()} targetClosure=${targetClosure.sorted().joinToString("|")} " +
                "kind=$kind expected=${spec.expectedPath.absolutePath} targetSourceFiles=${sourceFiles.size} " +
                "compileInputs=${compileInputs.size} reconfigureInputs=${reconfigureInputs.size}"
        }
        return spec
    }

    override suspend fun execute(
        ctx: BuildContext,
        spec: ArtifactSpec,
        fingerprint: BuildFingerprint,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome {
        emitter.emit(BuildEvent.Build.CompileStarted(spec.id.targetName))
        val start = System.currentTimeMillis()
        val options = ctx.options
        val onProgress: (String) -> Unit = { line -> emitter.tryEmit(BuildEvent.Build.CompileProgress(line)) }
        val optionsWithProgress = options.copy(onProgress = onProgress)

        // 1. 需要时重新 configure(CMakeCache 变旧 / 参数变更 / 元数据变更)
        lastResolvedRunMode = options.resolvedRunMode
        val forceReconfigure = shouldForceReconfigureForReason(ctx.buildReason)
        if (forceReconfigure) {
            BuildDiagnosticsLog.i {
                "cmake force reconfigure before build reason=${ctx.buildReason.orEmpty()} " +
                    "target=${spec.id.targetName}"
            }
        }
        if (forceReconfigure || needsReconfigure(ctx.projectRoot, ctx.buildDir, optionsWithProgress)) {
            emitter.emit(BuildEvent.Build.ConfigureStarted(spec.id.targetName))
            onProgress(Strings.cmake_progress_reconfiguring.strOr(context))
            val cfgStart = System.currentTimeMillis()
            val cfgResult = configure(ctx.projectRoot, ctx.buildDir, optionsWithProgress)
            if (cfgResult is ConfigureResult.Error) {
                emitter.emit(BuildEvent.Build.ConfigureFailed(cfgResult.message))
                return reportFailure(emitter, cfgResult.message, emptyList(), cfgResult.message)
            }
            emitter.emit(BuildEvent.Build.ConfigureCompleted(System.currentTimeMillis() - cfgStart))
        }

        cleanExistingOutputBeforeBuild(ctx, spec, optionsWithProgress)?.let { message ->
            return reportFailure(emitter, message, emptyList(), message)
        }

        // 2. build
        val buildResult = if (isNativeMode(options.resolvedRunMode)) {
            buildNative(ctx.projectRoot, ctx.buildDir, spec.id.targetName.takeIf { it.isNotBlank() } ?: ctx.target, optionsWithProgress)
        } else {
            buildPRoot(ctx.projectRoot, ctx.buildDir, spec.id.targetName.takeIf { it.isNotBlank() } ?: ctx.target, optionsWithProgress)
        }
        val elapsed = System.currentTimeMillis() - start

        return when (buildResult) {
            is BuildResult.Success -> wrapArtifact(ctx, spec, fingerprint, buildResult, elapsed, emitter)
            is BuildResult.Error -> reportFailure(emitter, buildResult.rawOutput, buildResult.diagnostics, buildResult.rawOutput)
        }
    }

    override suspend fun clean(ctx: BuildContext, reconfigure: Boolean) {
        if (reconfigure) {
            File(ctx.buildDir, "CMakeCache.txt").delete()
            File(ctx.buildDir, "CMakeFiles").deleteRecursively()
        } else {
            // 完整清理:委托给对应执行器以享有各自的清理语义
            if (isNativeMode(lastResolvedRunMode)) {
                nativeExecutor.clean(ctx.buildDir, ctx.options.toolchainId)
            } else {
                prootExecutor.clean(ctx.buildDir)
            }
        }
    }

    override suspend fun getTargets(ctx: BuildContext): List<TargetInfo> = loadTargets(ctx.projectRoot, ctx.buildDir)

    // ---------- configure / build 分派 ----------

    private suspend fun configure(
        projectRoot: File,
        buildDir: File,
        options: BuildOptions,
    ): ConfigureResult = if (isNativeMode(options.resolvedRunMode)) {
        configureNative(projectRoot, buildDir, options)
    } else {
        configurePRoot(projectRoot, buildDir, options)
    }

    private suspend fun configureNative(
        projectRoot: File,
        buildDir: File,
        options: BuildOptions,
    ): ConfigureResult {
        val customCCompiler = RunConfiguration.normalizeCompilerPath(options.customCCompiler)
        val customCppCompiler = RunConfiguration.normalizeCompilerPath(options.customCppCompiler)
        val cmakeOptions = NativeCMakeBuildExecutor.Options(
            buildType = mapNativeBuildType(options.cmakeBuildType),
            generator = mapNativeGenerator(options.cmakeGenerator),
            parallelJobs = resolveParallelJobs(options.parallelJobs),
            extraCMakeArgs = options.nativeCMakeArgs,
            generateCompileCommands = true,
            compilerType = options.compilerType,
            toolchainId = options.toolchainId,
            cCompilerPath = customCCompiler,
            cxxCompilerPath = customCppCompiler,
            sysrootProfileId = options.sysrootProfileId,
            sysrootApiLevel = options.sysrootApiLevel,
            cFlags = options.nativeCFlags,
            cppFlags = options.nativeCppFlags,
            ldFlags = options.nativeLdFlags,
            ldLibs = options.nativeLdLibs,
            cppStandard = options.cppStandard,
            onProgress = options.onProgress,
        )
        return nativeExecutor.configure(
            projectDir = projectRoot,
            buildDir = buildDir,
            options = cmakeOptions,
        )
    }

    private suspend fun configurePRoot(
        projectRoot: File,
        buildDir: File,
        options: BuildOptions,
    ): ConfigureResult {
        val (cCompiler, cxxCompiler) = try {
            resolveCompilerPaths(options)
        } catch (e: IllegalArgumentException) {
            return ConfigureResult.Error(e.message ?: Strings.cmake_error_invalid_compiler_config.strOr(context))
        }
        val cmakeOptions = CMakeBuildExecutor.Options(
            buildType = mapPRootBuildType(options.cmakeBuildType),
            generator = mapPRootGenerator(options.cmakeGenerator),
            parallelJobs = resolveParallelJobs(options.parallelJobs),
            extraCMakeArgs = options.nativeCMakeArgs,
            generateCompileCommands = true,
            compilerType = options.compilerType,
            cCompilerPath = cCompiler,
            cxxCompilerPath = cxxCompiler,
            sysrootProfileId = options.sysrootProfileId,
            sysrootApiLevel = options.sysrootApiLevel,
            cFlags = options.nativeCFlags,
            cppFlags = options.nativeCppFlags,
            ldFlags = options.nativeLdFlags,
            ldLibs = options.nativeLdLibs,
            cppStandard = options.cppStandard,
        )
        return prootExecutor.configure(
            projectRoot = projectRoot,
            buildDir = buildDir,
            options = cmakeOptions,
            progress = options.onProgress ?: {},
        )
    }

    private suspend fun buildNative(
        projectRoot: File,
        buildDir: File,
        target: String?,
        options: BuildOptions,
    ): BuildResult {
        val cmakeOptions = NativeCMakeBuildExecutor.Options(
            generator = mapNativeGenerator(options.cmakeGenerator),
            parallelJobs = resolveParallelJobs(options.parallelJobs),
            toolchainId = options.toolchainId,
            sysrootProfileId = options.sysrootProfileId,
            sysrootApiLevel = options.sysrootApiLevel,
            onProgress = options.onProgress,
        )
        return nativeExecutor.build(
            projectDir = projectRoot,
            buildDir = buildDir,
            target = target,
            options = cmakeOptions,
        )
    }

    private suspend fun buildPRoot(
        projectRoot: File,
        buildDir: File,
        target: String?,
        options: BuildOptions,
    ): BuildResult = prootExecutor.build(
        projectRoot = projectRoot,
        buildDir = buildDir,
        target = target,
        parallelJobs = resolveParallelJobs(options.parallelJobs),
        progress = options.onProgress ?: {},
    )

    private suspend fun cleanExistingOutputBeforeBuild(
        ctx: BuildContext,
        spec: ArtifactSpec,
        options: BuildOptions,
    ): String? {
        if (!shouldCleanBeforeBuild(ctx.buildReason)) return null

        val target = spec.id.targetName.takeIf { it.isNotBlank() } ?: ctx.target.orEmpty()
        BuildDiagnosticsLog.i {
            "cmake prebuild clean start target=$target reason=${ctx.buildReason.orEmpty()} " +
                "artifact=${spec.expectedPath.absolutePath}"
        }
        val result = if (isNativeMode(options.resolvedRunMode)) {
            nativeExecutor.clean(ctx.buildDir, options.toolchainId)
        } else {
            prootExecutor.clean(ctx.buildDir)
        }
        return when (result) {
            CleanResult.Success -> {
                BuildDiagnosticsLog.i {
                    "cmake prebuild clean success target=$target artifact=${spec.expectedPath.absolutePath}"
                }
                null
            }
            is CleanResult.Error -> {
                val message = result.message.ifBlank {
                    Strings.compile_cmake_clear_build_dir_failed.strOr(context)
                }
                BuildDiagnosticsLog.w {
                    "cmake prebuild clean failed target=$target reason=${ctx.buildReason.orEmpty()} message=$message"
                }
                message
            }
        }
    }

    // ---------- needsReconfigure ----------

    private fun needsReconfigure(projectRoot: File, buildDir: File, options: BuildOptions): Boolean {
        val cacheFile = File(buildDir, "CMakeCache.txt")
        if (!cacheFile.exists()) return true

        val cmakeListsFile = File(projectRoot, "CMakeLists.txt")
        if (cmakeListsFile.exists() && cmakeListsFile.lastModified() > cacheFile.lastModified()) return true

        val metadataFile = ProjectMetadataStore.getMetaFile(projectRoot)
        if (metadataFile.isFile && metadataFile.lastModified() > cacheFile.lastModified()) return true

        try {
            val cacheContent = cacheFile.readText()
            val (expectedCCompiler, expectedCxxCompiler) = resolveExpectedCompilerConfig(options)

            val cCompilerRegex = Regex("""(?m)^CMAKE_C_COMPILER:FILEPATH=(.+)$""")
            val cMatch = cCompilerRegex.find(cacheContent)
            if (cMatch == null || cMatch.groupValues[1].trim() != expectedCCompiler) return true

            val cxxCompilerRegex = Regex("""(?m)^CMAKE_CXX_COMPILER:FILEPATH=(.+)$""")
            val cxxMatch = cxxCompilerRegex.find(cacheContent)
            if (cxxMatch == null || cxxMatch.groupValues[1].trim() != expectedCxxCompiler) return true

            val generatorRegex = Regex("""(?m)^CMAKE_GENERATOR:INTERNAL=(.+)$""")
            val cachedGenerator = generatorRegex.find(cacheContent)?.groupValues?.get(1)?.trim()
            val expectedGenerator = options.cmakeGenerator.cmakeValue
            if (cachedGenerator == null || cachedGenerator != expectedGenerator) return true

            val buildTypeRegex = Regex("""(?m)^CMAKE_BUILD_TYPE:STRING=(.*)$""")
            val cachedBuildType = buildTypeRegex.find(cacheContent)?.groupValues?.get(1)?.trim()
            val expectedBuildType = options.cmakeBuildType.cmakeValue
            if (cachedBuildType == null || cachedBuildType != expectedBuildType) return true

            val androidAbi = if (isNativeMode(options.resolvedRunMode)) {
                PackageAbiCompatibility.currentAppAbi(
                    nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
                    supportedAbis = Build.SUPPORTED_ABIS,
                )
            } else {
                ""
            }
            CMakeConfigurationIdentity.from(options, androidAbi)
                .asCMakeCacheEntries()
                .forEach { (key, expectedValue) ->
                    val cachedValue = readCMakeCacheValue(cacheContent, key) ?: return true
                    if (cachedValue != expectedValue) {
                        Timber.tag(TAG).d(
                            "CMake cache identity mismatch: %s cached=%s expected=%s",
                            key,
                            cachedValue,
                            expectedValue
                        )
                        return true
                    }
                }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to read CMakeCache.txt: %s", e.message)
            return true
        }
        return false
    }

    private fun readCMakeCacheValue(cacheContent: String, key: String): String? =
        Regex("""(?m)^${Regex.escape(key)}:[A-Z_]+=(.*)$""")
            .find(cacheContent)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

    private fun resolveExpectedCompilerConfig(options: BuildOptions): Pair<String, String> {
        if (!isNativeMode(options.resolvedRunMode)) {
            val (cCompiler, cxxCompiler) = resolveCompilerPaths(options)
            return cCompiler to cxxCompiler
        }
        val binDir = nativeToolchainManager.getBinDir(options.toolchainId)
        val realCCompiler = NativeCMakeBuildExecutor.resolveConfiguredCompilerPath(
            configuredPath = options.customCCompiler,
            fallbackPath = File(binDir, "clang").absolutePath,
        )
        val realCxxCompiler = NativeCMakeBuildExecutor.resolveConfiguredCompilerPath(
            configuredPath = options.customCppCompiler,
            fallbackPath = File(binDir, "clang++").absolutePath,
        )
        return realCCompiler to realCxxCompiler
    }

    private fun resolveCompilerPaths(options: BuildOptions): Pair<String, String> {
        val c = pathResolver.getCCompiler(options.compilerType, options.customCCompiler)
        val cxx = pathResolver.getCppCompiler(options.compilerType, options.customCppCompiler)
        Timber.tag(TAG).d("Resolved compilers: C=%s, C++=%s", c, cxx)
        return c to cxx
    }

    private fun resolvePRootEnvironment(): PRootEnvironment {
        val fromProvider = (linuxEnvironmentProvider.get() as? PRootEnvironment)
        return fromProvider ?: prootEnv ?: error("PRoot environment is unavailable for CMake build")
    }

    private fun resolveParallelJobs(fallbackJobs: Int): Int = fallbackJobs.coerceIn(1, 8)

    private fun isNativeMode(runMode: LinuxRunModePolicy.RunMode): Boolean = runMode == LinuxRunModePolicy.RunMode.NATIVE

    // ---------- targets / 产物 / variant / hash ----------

    private suspend fun loadTargets(projectRoot: File, buildDir: File): List<TargetInfo> {
        val cmakeFile = File(projectRoot, "CMakeLists.txt")
        if (cmakeFile.exists()) {
            try {
                val doc = CMake.parse(cmakeFile.readText()).getOrNull()
                if (doc != null) {
                    val analysis = CMakeAnalyzer(doc).analyze()
                    return doc.targets.map { target ->
                        val targetType = mapTargetType(target.type)
                        val targetAnalysis = analysis.targets[target.name]
                        val properties = targetAnalysis?.properties.orEmpty()
                        val outputName = when (targetType) {
                            TargetInfo.Type.SHARED_LIBRARY -> properties["LIBRARY_OUTPUT_NAME"] ?: properties["OUTPUT_NAME"]
                            TargetInfo.Type.STATIC_LIBRARY -> properties["ARCHIVE_OUTPUT_NAME"] ?: properties["OUTPUT_NAME"]
                            TargetInfo.Type.EXECUTABLE -> properties["RUNTIME_OUTPUT_NAME"] ?: properties["OUTPUT_NAME"]
                            else -> properties["OUTPUT_NAME"]
                        }
                        val dependencies = buildList {
                            targetAnalysis?.linkLibraries
                                ?.map { it.library }
                                ?.let(::addAll)
                            targetAnalysis?.dependencies?.let(::addAll)
                        }.distinct()
                        TargetInfo(
                            name = target.name,
                            type = targetType,
                            sources = target.sources,
                            outputName = outputName,
                            dependencies = dependencies,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("CMakeLists.txt parse failed, fallback to build-system query: %s", e.message)
            }
        }

        val fromBuildSystem = if (isNativeMode(lastResolvedRunMode)) {
            emptyList()
        } else {
            prootExecutor.getTargetsFromBuildSystem(buildDir)
        }
        return fromBuildSystem.map { name ->
            TargetInfo(name = name, type = TargetInfo.Type.OTHER, sources = emptyList(), outputName = null)
        }
    }

    private fun resolveTargetClosure(target: TargetInfo, allTargets: List<TargetInfo>): Set<String> {
        val byName = allTargets.associateBy { it.name }
        val visited = linkedSetOf<String>()

        fun visit(targetName: String) {
            if (!visited.add(targetName)) return
            byName[targetName]
                ?.dependencies
                .orEmpty()
                .filter { dependencyName -> dependencyName in byName }
                .forEach(::visit)
        }

        visit(target.name)
        return visited
    }

    private suspend fun wrapArtifact(
        ctx: BuildContext,
        spec: ArtifactSpec,
        fingerprint: BuildFingerprint,
        success: BuildResult.Success,
        elapsedMs: Long,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome {
        val artifactFile = success.outputPath?.let(::File)?.takeIf { it.isFile }
            ?: spec.expectedPath.takeIf { it.isFile }
        if (artifactFile == null) {
            val reason = "CMake reported success but artifact missing (expected ${spec.expectedPath.absolutePath})"
            BuildDiagnosticsLog.w {
                "cmake wrap artifact failed: reported success but artifact missing " +
                    "reported=${success.outputPath.orEmpty()} expected=${spec.expectedPath.absolutePath}"
            }
            emitter.emit(BuildEvent.Build.CompileFailed(reason, emptyList()))
            return ExecutionOutcome.Failure(reason)
        }
        val refreshedSpec = refreshTrackedInputSpec(ctx, spec)
        val refreshedFingerprint = if (refreshedSpec == spec) {
            fingerprint
        } else {
            FingerprintCalculator().compute(ctx, refreshedSpec)
        }
        val artifact = Artifact(
            id = refreshedSpec.id,
            absolutePath = artifactFile.absolutePath,
            kind = refreshedSpec.kind,
            contentHash = computeContentHash(artifactFile),
            fingerprint = refreshedFingerprint,
            sources = refreshedSpec.sources.map { captureSourceRef(it, ctx.projectRoot) },
            compiledAt = System.currentTimeMillis(),
            buildTimeMs = elapsedMs,
        )
        BuildDiagnosticsLog.i {
            "cmake wrap artifact success target=${artifact.id.targetName} artifact=${artifact.absolutePath} " +
                "kind=${artifact.kind} elapsedMs=$elapsedMs sources=${artifact.sources.size} " +
                "contentHash=${artifact.contentHash.take(12)} trackedHash=${artifact.fingerprint.trackedInputsHash.take(12)}"
        }
        emitter.emit(BuildEvent.Build.CompileCompleted(artifact))
        return ExecutionOutcome.Success(artifact, success.message)
    }

    private suspend fun reportFailure(
        emitter: BuildEventEmitter,
        rawOutput: String,
        diagnostics: List<com.wuxianggujun.tinaide.core.compile.BuildDiagnostic>,
        fallbackReason: String,
    ): ExecutionOutcome {
        emitter.emit(BuildEvent.Build.CompileFailed(rawOutput, diagnostics))
        val firstLine = rawOutput.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return ExecutionOutcome.Failure(
            reason = firstLine.ifBlank { fallbackReason.ifBlank { "cmake build failed" } },
            diagnostics = diagnostics,
            rawOutput = rawOutput,
        )
    }

    private suspend fun refreshTrackedInputSpec(ctx: BuildContext, spec: ArtifactSpec): ArtifactSpec {
        val all = loadTargets(ctx.projectRoot, ctx.buildDir)
        val selected = all.firstOrNull { it.name == spec.id.targetName } ?: run {
            BuildDiagnosticsLog.w {
                "cmake refresh tracked inputs skipped: target missing target=${spec.id.targetName} targets=${all.diagnosticsSummary()}"
            }
            return spec
        }
        val sourceFiles = selected.sources
            .map { relative -> File(ctx.projectRoot, relative) }
            .filter { it.isFile }
        val targetClosure = resolveTargetClosure(selected, all)
        val compileInputs = TrackedInputCollector.collectCMakeCompileInputs(
            projectRoot = ctx.projectRoot,
            buildDir = ctx.buildDir,
            targetNames = targetClosure,
            targetSources = sourceFiles,
        )
        val reconfigureInputs = TrackedInputCollector.collectCMakeReconfigureInputs(ctx.projectRoot)
        BuildDiagnosticsLog.i {
            "cmake refresh tracked inputs target=${selected.name} targetClosure=${targetClosure.sorted().joinToString("|")} " +
                "oldCompileInputs=${spec.sources.size} newCompileInputs=${compileInputs.size} " +
                "oldReconfigureInputs=${spec.reconfigureSources.size} newReconfigureInputs=${reconfigureInputs.size}"
        }
        return spec.copy(
            sources = compileInputs,
            reconfigureSources = reconfigureInputs,
        )
    }

    private fun selectTarget(all: List<TargetInfo>, requested: String?, options: BuildOptions): TargetInfo? {
        val selected = if (!requested.isNullOrBlank()) {
            all.firstOrNull { it.name == requested }
        } else {
            if (options.preferSharedLibraryForRun) {
                all.firstOrNull { it.type == TargetInfo.Type.SHARED_LIBRARY && !it.isAuxiliaryTarget() }
                    ?: all.firstOrNull { it.type == TargetInfo.Type.SHARED_LIBRARY }
            } else {
                all.firstOrNull { it.type == TargetInfo.Type.EXECUTABLE && !it.isAuxiliaryTarget() }
                    ?: all.firstOrNull { it.type == TargetInfo.Type.EXECUTABLE }
            }
                ?: all.firstOrNull { it.type != TargetInfo.Type.OTHER }
                ?: all.firstOrNull()
        }
        BuildDiagnosticsLog.i {
            "cmake selectTarget requested=${requested.orEmpty()} preferSharedLibraryForRun=${options.preferSharedLibraryForRun} " +
                "selected=${selected?.diagnosticsSummary().orEmpty()} candidates=${all.diagnosticsSummary()}"
        }
        return selected
    }

    private fun TargetInfo.isAuxiliaryTarget(): Boolean {
        val normalized = name.trim().lowercase()
        return normalized == "test" ||
            normalized == "tests" ||
            normalized.endsWith("_test") ||
            normalized.endsWith("-test") ||
            normalized.endsWith("_tests") ||
            normalized.endsWith("-tests")
    }

    private fun List<TargetInfo>.diagnosticsSummary(limit: Int = 12): String =
        if (isEmpty()) {
            "<none>"
        } else {
            take(limit).joinToString(separator = ";") { it.diagnosticsSummary() } +
                if (size > limit) ";...(+${size - limit})" else ""
        }

    private fun TargetInfo.diagnosticsSummary(): String =
        "$name:$type:aux=${isAuxiliaryTarget()}:sources=${sources.size}:deps=${
            dependencies.joinToString(separator = "|", limit = 4)
        }:output=${outputName.orEmpty()}"

    private fun mapKind(type: TargetInfo.Type): ArtifactKind = when (type) {
        TargetInfo.Type.EXECUTABLE -> ArtifactKind.EXECUTABLE
        TargetInfo.Type.SHARED_LIBRARY -> ArtifactKind.SHARED_LIBRARY
        TargetInfo.Type.STATIC_LIBRARY -> ArtifactKind.STATIC_LIBRARY
        TargetInfo.Type.OTHER -> ArtifactKind.UNKNOWN
    }

    private fun mapTargetType(type: CMakeDoc.TargetType): TargetInfo.Type = when (type) {
        CMakeDoc.TargetType.EXECUTABLE -> TargetInfo.Type.EXECUTABLE
        CMakeDoc.TargetType.STATIC_LIBRARY -> TargetInfo.Type.STATIC_LIBRARY
        CMakeDoc.TargetType.SHARED_LIBRARY -> TargetInfo.Type.SHARED_LIBRARY
        CMakeDoc.TargetType.MODULE_LIBRARY -> TargetInfo.Type.SHARED_LIBRARY
        CMakeDoc.TargetType.OBJECT_LIBRARY -> TargetInfo.Type.STATIC_LIBRARY
        CMakeDoc.TargetType.INTERFACE_LIBRARY -> TargetInfo.Type.OTHER
        CMakeDoc.TargetType.CUSTOM_TARGET -> TargetInfo.Type.OTHER
        CMakeDoc.TargetType.UNKNOWN -> TargetInfo.Type.OTHER
    }

    private fun mapNativeBuildType(type: CMakeBuildTypeOption): NativeCMakeBuildExecutor.CMakeBuildType = when (type) {
        CMakeBuildTypeOption.DEBUG -> NativeCMakeBuildExecutor.CMakeBuildType.DEBUG
        CMakeBuildTypeOption.RELEASE -> NativeCMakeBuildExecutor.CMakeBuildType.RELEASE
        CMakeBuildTypeOption.REL_WITH_DEB_INFO -> NativeCMakeBuildExecutor.CMakeBuildType.REL_WITH_DEB_INFO
        CMakeBuildTypeOption.MIN_SIZE_REL -> NativeCMakeBuildExecutor.CMakeBuildType.MIN_SIZE_REL
    }

    private fun mapPRootBuildType(type: CMakeBuildTypeOption): CMakeBuildExecutor.CMakeBuildType = when (type) {
        CMakeBuildTypeOption.DEBUG -> CMakeBuildExecutor.CMakeBuildType.DEBUG
        CMakeBuildTypeOption.RELEASE -> CMakeBuildExecutor.CMakeBuildType.RELEASE
        CMakeBuildTypeOption.REL_WITH_DEB_INFO -> CMakeBuildExecutor.CMakeBuildType.REL_WITH_DEB_INFO
        CMakeBuildTypeOption.MIN_SIZE_REL -> CMakeBuildExecutor.CMakeBuildType.MIN_SIZE_REL
    }

    private fun mapNativeGenerator(generator: CMakeGeneratorOption): NativeCMakeBuildExecutor.CMakeGenerator = when (generator) {
        CMakeGeneratorOption.NINJA -> NativeCMakeBuildExecutor.CMakeGenerator.NINJA
        CMakeGeneratorOption.UNIX_MAKEFILES -> NativeCMakeBuildExecutor.CMakeGenerator.UNIX_MAKEFILES
    }

    private fun mapPRootGenerator(generator: CMakeGeneratorOption): CMakeBuildExecutor.CMakeGenerator = when (generator) {
        CMakeGeneratorOption.NINJA -> CMakeBuildExecutor.CMakeGenerator.NINJA
        CMakeGeneratorOption.UNIX_MAKEFILES -> CMakeBuildExecutor.CMakeGenerator.UNIX_MAKEFILES
    }

    private fun resolveVariant(ctx: BuildContext): String {
        val buildType = ctx.options.cmakeBuildType.cmakeValue.lowercase()
        val generator = ctx.options.cmakeGenerator.name.lowercase()
        return "$buildType-$generator"
    }

    private fun captureSourceRef(file: File, projectRoot: File): SourceRef =
        SourceRef.capture(file, projectRoot)

    private fun computeContentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val bytes = digest.digest()
        return buildString(32) {
            for (i in 0 until 16) {
                val b = bytes[i].toInt() and 0xFF
                append(b.toString(16).padStart(2, '0'))
            }
        }
    }
}
