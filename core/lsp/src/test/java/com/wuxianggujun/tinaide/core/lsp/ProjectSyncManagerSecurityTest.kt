package com.wuxianggujun.tinaide.core.lsp

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ProjectSyncManagerSecurityTest {
    @Test
    fun validateSyncFiles_shouldUseUtf8BytesAndRejectDuplicateOrUnsafePaths() {
        val valid = ProjectFileInfo("src/main.cpp", "int main() {}", 0)
        assertThat(ProjectSyncManager.validateSyncFiles(listOf(valid))).isEqualTo(13L)

        assertThat(
            runCatching { ProjectSyncManager.validateSyncFiles(listOf(valid, valid)) }.isFailure
        ).isTrue()
        assertThat(ProjectSyncManager.isSafeRelativeSyncPath("../secret.txt")).isFalse()
        assertThat(ProjectSyncManager.isSafeRelativeSyncPath(".env")).isFalse()
        assertThat(ProjectSyncManager.isSafeRelativeSyncPath("config/client_secret.json")).isFalse()
        assertThat(ProjectSyncManager.isSafeRelativeSyncPath("src/CredentialStore.kt")).isTrue()
        assertThat(ProjectSyncManager.isSafeRelativeSyncPath("src/SecretManager.kt")).isTrue()
        assertThat(ProjectSyncManager.isSafeRelativeSyncPath("a".repeat(1025))).isFalse()
        assertThat(ProjectSyncManager.isSafeRelativeSyncPath("src/line\nbreak.cpp")).isFalse()
    }

    @Test
    fun generateFileChangedMessage_shouldRequireTypeSpecificFields() {
        assertThat(
            runCatching {
                ProjectSyncManager.generateFileChangedMessage("created", "src/main.cpp")
            }.isFailure
        ).isTrue()
        assertThat(
            runCatching {
                ProjectSyncManager.generateFileChangedMessage("renamed", "src/new.cpp")
            }.isFailure
        ).isTrue()
        val message = ProjectSyncManager.generateFileChangedMessage(
            type = "renamed",
            path = "src/new.cpp",
            oldPath = "src/old.cpp",
        )
        // org.json 序列化时会把 '/' 转义成 '\/'，两种形式 JSON 语义等价，做结构化断言。
        val parsed = org.json.JSONObject(message)
        assertThat(parsed.getString("method")).isEqualTo("tina/fileChanged")
        assertThat(parsed.getJSONObject("params").getString("type")).isEqualTo("renamed")
        assertThat(parsed.getJSONObject("params").getString("path")).isEqualTo("src/new.cpp")
        assertThat(parsed.getJSONObject("params").getString("oldPath")).isEqualTo("src/old.cpp")
    }

    @Test
    fun splitIntoChunks_shouldRejectInvalidConfigurationAndRespectFileLimits() {
        assertThat(runCatching { ChunkConfig(maxChunkSize = 0) }.isFailure).isTrue()
        val files = listOf(
            ProjectFileInfo("a.cpp", "1234", 4),
            ProjectFileInfo("b.cpp", "5678", 4),
        )

        val chunks = ProjectSyncManager.splitIntoChunks(
            files,
            ChunkConfig(maxChunkSize = 4, maxFilesPerChunk = 1),
        )

        assertThat(chunks).hasSize(2)
        assertThat(chunks.last().isLast).isTrue()
    }

    @Test
    fun scanProject_shouldIgnoreNoisyDirectoryPrefixes() {
        runBlocking {
            val projectRoot = Files.createTempDirectory("tina-lsp-sync").toFile()
            try {
                File(projectRoot, "src").mkdirs()
                File(projectRoot, "src/main.cpp").writeText("int main() {}", Charsets.UTF_8)
                File(projectRoot, "cmake-build-debug").mkdirs()
                File(projectRoot, "cmake-build-debug/generated.cpp").writeText("generated", Charsets.UTF_8)

                val files = ProjectSyncManager.scanProject(projectRoot)

                assertThat(files.map(ProjectFileInfo::relativePath)).containsExactly("src/main.cpp")
            } finally {
                projectRoot.deleteRecursively()
            }
        }
    }
}
