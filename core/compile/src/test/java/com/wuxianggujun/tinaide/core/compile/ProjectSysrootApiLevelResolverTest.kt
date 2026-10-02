package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectMetadata
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import org.junit.Rule
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectSysrootApiLevelResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `run config value takes precedence over metadata`() = runTest {
        val projectRoot = tempFolder.newFolder("run-config-priority")
        writeMetadata(projectRoot, nativeApiLevel = 33)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = 29)

        assertThat(resolution.apiLevel).isEqualTo(29)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.RUN_CONFIG)
        assertThat(resolution.invalidRunConfigApiLevel).isNull()
    }

    @Test
    fun `run config accepts future api levels for imported newer ndk profiles`() = runTest {
        val projectRoot = tempFolder.newFolder("future-api-run-config")
        writeMetadata(projectRoot, nativeApiLevel = 33)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = 36)

        assertThat(resolution.apiLevel).isEqualTo(36)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.RUN_CONFIG)
        assertThat(resolution.invalidRunConfigApiLevel).isNull()
    }

    @Test
    fun `metadata nativeApiLevel is used when run config is empty`() = runTest {
        val projectRoot = tempFolder.newFolder("metadata-native")
        writeMetadata(projectRoot, nativeApiLevel = 31)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = null)

        assertThat(resolution.apiLevel).isEqualTo(31)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.METADATA)
        assertThat(resolution.invalidRunConfigApiLevel).isNull()
    }

    @Test
    fun `invalid run config falls back to metadata`() = runTest {
        val projectRoot = tempFolder.newFolder("invalid-run-config")
        writeMetadata(projectRoot, nativeApiLevel = 34)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = 100)

        assertThat(resolution.apiLevel).isEqualTo(34)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.METADATA)
        assertThat(resolution.invalidRunConfigApiLevel).isEqualTo(100)
    }

    @Test
    fun `defaults to API 28 when no valid config exists`() = runTest {
        val projectRoot = tempFolder.newFolder("default-fallback")
        writeMetadata(projectRoot, nativeApiLevel = 100)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = 20)

        assertThat(resolution.apiLevel).isEqualTo(MakeCommandOverrides.DEFAULT_SYSROOT_API_LEVEL)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.DEFAULT)
        assertThat(resolution.invalidRunConfigApiLevel).isEqualTo(20)
    }

    private suspend fun writeMetadata(
        projectRoot: File,
        nativeApiLevel: Int?
    ) {
        val metadata = ProjectMetadata(
            id = "test-${projectRoot.name}",
            displayName = projectRoot.name,
            createdAt = System.currentTimeMillis(),
            nativeApiLevel = nativeApiLevel
        )
        check(ProjectMetadataStore.write(projectRoot, metadata)) {
            "Failed to write metadata for ${projectRoot.absolutePath}"
        }
    }
}
