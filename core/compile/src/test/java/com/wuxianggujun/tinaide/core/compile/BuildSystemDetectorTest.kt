package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadata
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.runBlocking
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BuildSystemDetector 单元测试
 *
 * 使用临时目录模拟不同项目结构，验证构建系统检测逻辑。
 * Mock 掉 ProjectMetadataStore 以隔离文件检测逻辑。
 */
class BuildSystemDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        mockkObject(ProjectMetadataStore)

        // 默认：元数据中没有构建系统信息
        coEvery { ProjectMetadataStore.read(any()) } returns null
        coEvery { ProjectMetadataStore.updateBuildSystem(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== 目录不存在/不可读 ====================

    @Test
    fun `returns UNKNOWN for non-existent directory`() {
        val nonExistent = tempFolder.root.resolve("does-not-exist")
        assertThat(detect(nonExistent)).isEqualTo(BuildSystem.UNKNOWN)
    }

    @Test
    fun `returns UNKNOWN when path is a file not directory`() {
        val file = tempFolder.newFile("not-a-dir.txt")
        assertThat(detect(file)).isEqualTo(BuildSystem.UNKNOWN)
    }

    // ==================== 元数据优先 ====================

    @Test
    fun `returns CMAKE from metadata when metadata specifies CMAKE`() {
        val projectRoot = tempFolder.newFolder("cmake-project")
        val metadata = createMetadata(ProjectBuildSystem.CMAKE)
        coEvery { ProjectMetadataStore.read(projectRoot) } returns metadata

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.CMAKE)
    }

    @Test
    fun `returns MAKE from metadata when metadata specifies MAKE`() {
        val projectRoot = tempFolder.newFolder("make-project")
        val metadata = createMetadata(ProjectBuildSystem.MAKE)
        coEvery { ProjectMetadataStore.read(projectRoot) } returns metadata

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.MAKE)
    }

    @Test
    fun `returns SINGLE_FILE from metadata when metadata specifies SINGLE_FILE`() {
        val projectRoot = tempFolder.newFolder("single-project")
        val metadata = createMetadata(ProjectBuildSystem.SINGLE_FILE)
        coEvery { ProjectMetadataStore.read(projectRoot) } returns metadata

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.SINGLE_FILE)
    }

    @Test
    fun `returns PLUGIN from metadata when metadata specifies PLUGIN`() {
        val projectRoot = tempFolder.newFolder("plugin-project")
        val metadata = createMetadata(ProjectBuildSystem.PLUGIN)
        coEvery { ProjectMetadataStore.read(projectRoot) } returns metadata

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.PLUGIN)
    }

    @Test
    fun `falls through to file detection when metadata has UNKNOWN build system`() {
        val projectRoot = tempFolder.newFolder("unknown-meta")
        val metadata = createMetadata(ProjectBuildSystem.UNKNOWN)
        coEvery { ProjectMetadataStore.read(projectRoot) } returns metadata

        // 空目录 → UNKNOWN
        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.UNKNOWN)
    }

    // ==================== 文件检测：CMake ====================

    @Test
    fun `detects CMAKE when CMakeLists txt exists`() {
        val projectRoot = tempFolder.newFolder("cmake")
        projectRoot.resolve("CMakeLists.txt").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.CMAKE)
    }

    @Test
    fun `CMake takes priority over Makefile`() {
        val projectRoot = tempFolder.newFolder("cmake-and-make")
        projectRoot.resolve("CMakeLists.txt").createNewFile()
        projectRoot.resolve("Makefile").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.CMAKE)
    }

    @Test
    fun `detects PLUGIN when TinaIDE plugin manifest exists`() {
        val projectRoot = tempFolder.newFolder("plugin-manifest")
        projectRoot.resolve("manifest.json").writeText(
            """
            {
              "id": "demo.plugin",
              "name": "Demo Plugin",
              "version": "1.0.0",
              "type": "config",
              "contributions": {}
            }
            """.trimIndent()
        )

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.PLUGIN)
    }

    @Test
    fun `plugin manifest updates stale SINGLE_FILE metadata`() {
        val projectRoot = tempFolder.newFolder("plugin-stale-metadata")
        val metadata = createMetadata(ProjectBuildSystem.SINGLE_FILE)
        coEvery { ProjectMetadataStore.read(projectRoot) } returns metadata
        coEvery {
            ProjectMetadataStore.updateBuildSystem(projectRoot, ProjectBuildSystem.PLUGIN)
        } returns true
        projectRoot.resolve("manifest.json").writeText(
            """
            {
              "id": "demo.plugin",
              "name": "Demo Plugin",
              "version": "1.0.0",
              "type": "script",
              "main": "main.lua"
            }
            """.trimIndent()
        )

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.PLUGIN)
    }

    // ==================== 文件检测：Make ====================

    @Test
    fun `detects MAKE when Makefile exists`() {
        val projectRoot = tempFolder.newFolder("make")
        projectRoot.resolve("Makefile").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.MAKE)
    }

    @Test
    fun `detects MAKE when lowercase makefile exists`() {
        val projectRoot = tempFolder.newFolder("make-lower")
        projectRoot.resolve("makefile").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.MAKE)
    }

    @Test
    fun `detects MAKE when GNUmakefile exists`() {
        val projectRoot = tempFolder.newFolder("gnu-make")
        projectRoot.resolve("GNUmakefile").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.MAKE)
    }

    // ==================== 文件检测：单文件 ====================

    @Test
    fun `detects SINGLE_FILE when c source file exists`() {
        val projectRoot = tempFolder.newFolder("single-c")
        projectRoot.resolve("main.c").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.SINGLE_FILE)
    }

    @Test
    fun `detects SINGLE_FILE when cpp source file exists`() {
        val projectRoot = tempFolder.newFolder("single-cpp")
        projectRoot.resolve("main.cpp").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.SINGLE_FILE)
    }

    @Test
    fun `detects SINGLE_FILE when cc source file exists`() {
        val projectRoot = tempFolder.newFolder("single-cc")
        projectRoot.resolve("hello.cc").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.SINGLE_FILE)
    }

    // ==================== 空目录 ====================

    @Test
    fun `returns UNKNOWN for empty directory`() {
        val projectRoot = tempFolder.newFolder("empty")
        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.UNKNOWN)
    }

    @Test
    fun `returns UNKNOWN when only non-source files exist`() {
        val projectRoot = tempFolder.newFolder("no-source")
        projectRoot.resolve("README.md").createNewFile()
        projectRoot.resolve("data.txt").createNewFile()

        assertThat(detect(projectRoot)).isEqualTo(BuildSystem.UNKNOWN)
    }

    // ==================== findMainSourceFile ====================

    @Test
    fun `findMainSourceFile prefers main named file`() {
        val projectRoot = tempFolder.newFolder("main-pref")
        projectRoot.resolve("utils.c").createNewFile()
        projectRoot.resolve("main.c").createNewFile()

        val mainFile = BuildSystemDetector.findMainSourceFile(projectRoot)
        assertThat(mainFile).isNotNull()
        assertThat(mainFile!!.name).isEqualTo("main.c")
    }

    @Test
    fun `findMainSourceFile returns first file when no main exists`() {
        val projectRoot = tempFolder.newFolder("no-main")
        projectRoot.resolve("hello.cpp").createNewFile()

        val mainFile = BuildSystemDetector.findMainSourceFile(projectRoot)
        assertThat(mainFile).isNotNull()
        assertThat(mainFile!!.name).isEqualTo("hello.cpp")
    }

    @Test
    fun `findMainSourceFile returns null for empty directory`() {
        val projectRoot = tempFolder.newFolder("empty-main")
        assertThat(BuildSystemDetector.findMainSourceFile(projectRoot)).isNull()
    }

    // ==================== findAllSourceFiles ====================

    @Test
    fun `findAllSourceFiles returns all source files`() {
        val projectRoot = tempFolder.newFolder("multi")
        projectRoot.resolve("main.c").createNewFile()
        projectRoot.resolve("utils.cpp").createNewFile()
        projectRoot.resolve("README.md").createNewFile()

        val sources = BuildSystemDetector.findAllSourceFiles(projectRoot)
        assertThat(sources.map { it.name }).containsExactly("main.c", "utils.cpp")
    }

    // ==================== 辅助方法 ====================

    private fun createMetadata(buildSystem: ProjectBuildSystem): ProjectMetadata = ProjectMetadata(
        id = "test-id",
        displayName = "Test Project",
        createdAt = System.currentTimeMillis(),
        buildSystem = buildSystem
    )

    private fun detect(projectRoot: java.io.File): BuildSystem = runBlocking {
        BuildSystemDetector.detect(projectRoot)
    }
}
