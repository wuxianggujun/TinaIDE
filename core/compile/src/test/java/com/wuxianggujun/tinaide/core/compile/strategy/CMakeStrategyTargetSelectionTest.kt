package com.wuxianggujun.tinaide.core.compile.strategy

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CMakeStrategyTargetSelectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `terminal default prefers non auxiliary executable`() = runTest {
        val projectRoot = createProject(
            """
            cmake_minimum_required(VERSION 3.16)
            project(target_selection LANGUAGES CXX)
            add_executable(app src/main.cpp)
            add_executable(app_test test/main.cpp)
            add_library(app_core SHARED src/core.cpp)
            """.trimIndent()
        )
        val context = mockk<Context>(relaxed = true)
        val spec = CMakeStrategy(context).describeOutput(newContext(projectRoot, BuildOptions()))

        assertThat(spec?.id?.targetName).isEqualTo("app")
        assertThat(spec?.kind).isEqualTo(ArtifactKind.EXECUTABLE)
        verify(exactly = 0) { context.applicationInfo }
        verify(exactly = 0) { context.filesDir }
    }

    @Test
    fun `terminal default can still use only executable test target`() = runTest {
        val projectRoot = createProject(
            """
            cmake_minimum_required(VERSION 3.16)
            project(target_selection LANGUAGES CXX)
            add_library(app SHARED src/core.cpp)
            add_executable(app_test test/main.cpp)
            target_link_libraries(app_test PRIVATE app)
            """.trimIndent()
        )
        val spec = strategy().describeOutput(newContext(projectRoot, BuildOptions()))

        assertThat(spec?.id?.targetName).isEqualTo("app_test")
        assertThat(spec?.kind).isEqualTo(ArtifactKind.EXECUTABLE)
    }

    @Test
    fun `sdl run default selects shared library`() = runTest {
        val projectRoot = createProject(
            """
            cmake_minimum_required(VERSION 3.16)
            project(target_selection LANGUAGES CXX)
            add_library(app SHARED src/core.cpp)
            add_executable(app_test test/main.cpp)
            """.trimIndent()
        )
        val spec = strategy().describeOutput(
            newContext(
                projectRoot = projectRoot,
                options = BuildOptions(preferSharedLibraryForRun = true),
            )
        )

        assertThat(spec?.id?.targetName).isEqualTo("app")
        assertThat(spec?.kind).isEqualTo(ArtifactKind.SHARED_LIBRARY)
    }

    @Test
    fun `fingerprint change cleans before cmake build`() {
        assertThat(CMakeStrategy.shouldCleanBeforeBuild("fingerprint changed: trackedInputsHash"))
            .isTrue()
    }

    @Test
    fun `empty build reason does not clean before cmake build`() {
        assertThat(CMakeStrategy.shouldCleanBeforeBuild(null))
            .isFalse()
        assertThat(CMakeStrategy.shouldCleanBeforeBuild(""))
            .isFalse()
    }

    @Test
    fun `reconfigure fingerprint change forces cmake configure`() {
        assertThat(CMakeStrategy.shouldForceReconfigureForReason("fingerprint changed: reconfigureInputsHash"))
            .isTrue()
        assertThat(CMakeStrategy.shouldForceReconfigureForReason("fingerprint changed: trackedInputsHash"))
            .isFalse()
    }

    private fun strategy(): CMakeStrategy = CMakeStrategy(context = mockk<Context>(relaxed = true))

    private fun newContext(projectRoot: File, options: BuildOptions): BuildContext = BuildContext(
        appContext = mockk(relaxed = true),
        projectRoot = projectRoot,
        buildDir = tempFolder.newFolder("build-${projectRoot.name}"),
        buildSystem = BuildSystem.CMAKE,
        options = options,
        projectId = projectRoot.name,
        target = null,
    )

    private fun createProject(cmake: String): File {
        val projectRoot = tempFolder.newFolder("project-${System.nanoTime()}")
        File(projectRoot, "src").mkdirs()
        File(projectRoot, "test").mkdirs()
        File(projectRoot, "src/main.cpp").writeText("int main() { return 0; }\n")
        File(projectRoot, "src/core.cpp").writeText("int core() { return 1; }\n")
        File(projectRoot, "test/main.cpp").writeText("int main() { return 0; }\n")
        File(projectRoot, "CMakeLists.txt").writeText(cmake)
        return projectRoot
    }
}
