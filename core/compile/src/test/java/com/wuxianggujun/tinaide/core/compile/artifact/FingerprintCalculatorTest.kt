package com.wuxianggujun.tinaide.core.compile.artifact

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.BuildType
import com.wuxianggujun.tinaide.core.compile.CMakeBuildTypeOption
import com.wuxianggujun.tinaide.core.compile.CMakeGeneratorOption
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import io.mockk.mockk
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 验证 FingerprintCalculator 把 BuildOptions 的全部关键字段都带入 BuildFingerprint。
 *
 * 原则:任何修改产物二进制的字段变化,必须导致 fingerprint 不相等。
 */
class FingerprintCalculatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val calc = FingerprintCalculator()

    @Test
    fun `fingerprint schema invalidates artifacts built with the old runtime policy`() {
        val fingerprint = calc.compute(newContext(options = defaultOptions()), sampleSpec())

        assertThat(fingerprint.schemaVersion).isEqualTo(7)
    }

    @Test
    fun `identical options produce identical fingerprint`() {
        val ctx = newContext(options = defaultOptions())
        val spec = sampleSpec()
        val fp1 = calc.compute(ctx, spec)
        val fp2 = calc.compute(ctx, spec)
        assertThat(fp1).isEqualTo(fp2)
    }

    @Test
    fun `buildType change invalidates fingerprint`() {
        val ctx1 = newContext(defaultOptions().copy(buildType = BuildType.DEBUG))
        val ctx2 = newContext(defaultOptions().copy(buildType = BuildType.RELEASE))
        assertThat(calc.compute(ctx1, sampleSpec())).isNotEqualTo(calc.compute(ctx2, sampleSpec()))
    }

    @Test
    fun `preferSharedLibraryForRun change invalidates fingerprint`() {
        val ctx1 = newContext(defaultOptions().copy(preferSharedLibraryForRun = false))
        val ctx2 = newContext(defaultOptions().copy(preferSharedLibraryForRun = true))
        assertThat(calc.compute(ctx1, sampleSpec())).isNotEqualTo(calc.compute(ctx2, sampleSpec()))
    }

    @Test
    fun `nativeCFlags change invalidates fingerprint`() {
        val ctx1 = newContext(defaultOptions().copy(nativeCFlags = "-O2"))
        val ctx2 = newContext(defaultOptions().copy(nativeCFlags = "-O0 -g"))
        assertThat(calc.compute(ctx1, sampleSpec())).isNotEqualTo(calc.compute(ctx2, sampleSpec()))
    }

    @Test
    fun `toolchainId change invalidates fingerprint`() {
        val ctx1 = newContext(defaultOptions().copy(toolchainId = null))
        val ctx2 = newContext(defaultOptions().copy(toolchainId = "ndk-r27"))
        assertThat(calc.compute(ctx1, sampleSpec())).isNotEqualTo(calc.compute(ctx2, sampleSpec()))
    }

    @Test
    fun `sysrootApiLevel change invalidates fingerprint`() {
        val ctx1 = newContext(defaultOptions().copy(sysrootApiLevel = 28))
        val ctx2 = newContext(defaultOptions().copy(sysrootApiLevel = 33))
        assertThat(calc.compute(ctx1, sampleSpec())).isNotEqualTo(calc.compute(ctx2, sampleSpec()))
    }

    @Test
    fun `sysrootProfileId change invalidates fingerprint`() {
        val ctx1 = newContext(defaultOptions().copy(sysrootProfileId = "builtin-ndk-r27c-arm64"))
        val ctx2 = newContext(defaultOptions().copy(sysrootProfileId = "custom-ndk-r27-arm64"))
        assertThat(calc.compute(ctx1, sampleSpec())).isNotEqualTo(calc.compute(ctx2, sampleSpec()))
    }

    @Test
    fun `expected output path change invalidates fingerprint`() {
        val ctx = newContext(defaultOptions())
        val fp1 = calc.compute(ctx, sampleSpec(expectedPath = File(ctx.buildDir, "hello")))
        val fp2 = calc.compute(ctx, sampleSpec(expectedPath = File(ctx.buildDir, "renamed")))
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun `artifact kind change invalidates fingerprint`() {
        val ctx = newContext(defaultOptions())
        val fp1 = calc.compute(ctx, sampleSpec(kind = ArtifactKind.EXECUTABLE))
        val fp2 = calc.compute(ctx, sampleSpec(kind = ArtifactKind.SHARED_LIBRARY))
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun `tracked input set change invalidates fingerprint`() {
        val ctx = newContext(defaultOptions())
        val fp1 = calc.compute(ctx, sampleSpec(sources = listOf(File(ctx.projectRoot, "main.c"))))
        val fp2 = calc.compute(
            ctx,
            sampleSpec(sources = listOf(File(ctx.projectRoot, "main.c"), File(ctx.projectRoot, "src/util.c")))
        )
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun `tracked input content change invalidates fingerprint`() {
        val ctx = newContext(defaultOptions())
        val sourceFile = File(ctx.projectRoot, "main.c").apply {
            writeText("int main() { return 0; }")
        }
        val fp1 = calc.compute(ctx, sampleSpec(sources = listOf(sourceFile)))

        sourceFile.writeText("int main() { return 1; }")
        val fp2 = calc.compute(ctx, sampleSpec(sources = listOf(sourceFile)))

        assertThat(fp1.trackedInputsHash).isNotEqualTo(fp2.trackedInputsHash)
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun `reconfigure input content change invalidates fingerprint`() {
        val ctx = newContext(defaultOptions())
        val sourceFile = File(ctx.projectRoot, "main.c").apply {
            writeText("int main() { return 0; }")
        }
        val cmakeFile = File(ctx.projectRoot, "CMakeLists.txt").apply {
            writeText("add_executable(app main.c)")
        }
        val fp1 = calc.compute(
            ctx,
            sampleSpec(
                sources = listOf(sourceFile),
                reconfigureSources = listOf(cmakeFile),
            )
        )

        cmakeFile.writeText("add_executable(app main.c)\n")
        val fp2 = calc.compute(
            ctx,
            sampleSpec(
                sources = listOf(sourceFile),
                reconfigureSources = listOf(cmakeFile),
            )
        )

        assertThat(fp1.reconfigureInputsHash).isNotEqualTo(fp2.reconfigureInputsHash)
        assertThat(fp1).isNotEqualTo(fp2)
    }

    // ---------- helpers ----------

    private fun defaultOptions() = BuildOptions(
        buildType = BuildType.DEBUG,
        compilerType = CompilerType.CLANG,
        cmakeBuildType = CMakeBuildTypeOption.DEBUG,
        cmakeGenerator = CMakeGeneratorOption.NINJA,
        resolvedRunMode = LinuxRunModePolicy.RunMode.NATIVE,
        toolchainId = null,
        sysrootApiLevel = 28,
        nativeCFlags = "",
        nativeCppFlags = "",
        nativeLdFlags = "",
        nativeLdLibs = "",
        optimizationLevel = "O2",
        generateDebugInfo = true,
        preferSharedLibraryForRun = false,
        parallelJobs = 4,
    )

    private fun newContext(options: BuildOptions): BuildContext = BuildContext(
        appContext = mockk(relaxed = true),
        projectRoot = tempFolder.newFolder("root-${options.hashCode()}"),
        buildDir = tempFolder.newFolder("build-${options.hashCode()}"),
        buildSystem = BuildSystem.SINGLE_FILE,
        options = options,
        projectId = "test-project",
        target = null,
    )

    private fun sampleSpec(
        expectedPath: File = File(tempFolder.root, "hello"),
        kind: ArtifactKind = ArtifactKind.EXECUTABLE,
        sources: List<File> = listOf(File(tempFolder.root, "main.c")),
        reconfigureSources: List<File> = emptyList(),
    ): ArtifactSpec = ArtifactSpec(
        id = ArtifactId(projectId = "test-project", targetName = "hello", variant = "default"),
        expectedPath = expectedPath,
        kind = kind,
        sources = sources,
        reconfigureSources = reconfigureSources,
    )
}
