package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageInstallCoordinatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun publishStagedDirectory_shouldReplaceTargetAndRemoveBackup() {
        val installDir = tempFolder.newFolder("installed")
        val targetDir = installDir.resolve("sdl2").withPayload("old")
        val operationId = "00000000-0000-0000-0000-000000000001"
        val stagingDir = PackageInstallCoordinator.resolveTransactionDirectory(
            installDir = installDir,
            prefix = PackageInstallCoordinator.DOWNLOAD_STAGING_PREFIX,
            packageId = "sdl2",
            operationId = operationId,
        ).withPayload("new")

        val staleBackup = PackageInstallCoordinator.publishStagedDirectory(
            installDir = installDir,
            stagingDir = stagingDir,
            targetDir = targetDir,
            backupPrefix = PackageInstallCoordinator.DOWNLOAD_BACKUP_PREFIX,
            packageId = "sdl2",
            operationId = operationId,
        )

        assertThat(staleBackup).isNull()
        assertThat(targetDir.resolve(PAYLOAD_FILE_NAME).readText()).isEqualTo("new")
        assertThat(stagingDir.exists()).isFalse()
        assertThat(installDir.listFiles().orEmpty().map(File::getName))
            .containsExactly("sdl2")
    }

    @Test
    fun publishStagedDirectory_shouldRestoreTargetWhenPublishFails() {
        val installDir = tempFolder.newFolder("rollback")
        val targetDir = installDir.resolve("sdl3").withPayload("old")
        val operationId = "00000000-0000-0000-0000-000000000002"
        val missingStagingDir = PackageInstallCoordinator.resolveTransactionDirectory(
            installDir = installDir,
            prefix = PackageInstallCoordinator.DOWNLOAD_STAGING_PREFIX,
            packageId = "sdl3",
            operationId = operationId,
        )

        assertThrows(IllegalStateException::class.java) {
            PackageInstallCoordinator.publishStagedDirectory(
                installDir = installDir,
                stagingDir = missingStagingDir,
                targetDir = targetDir,
                backupPrefix = PackageInstallCoordinator.DOWNLOAD_BACKUP_PREFIX,
                packageId = "sdl3",
                operationId = operationId,
            )
        }

        assertThat(targetDir.resolve(PAYLOAD_FILE_NAME).readText()).isEqualTo("old")
        assertThat(installDir.listFiles().orEmpty().map(File::getName))
            .containsExactly("sdl3")
    }

    private fun File.withPayload(content: String): File = apply {
        check(mkdirs())
        resolve(PAYLOAD_FILE_NAME).writeText(content)
    }

    private companion object {
        const val PAYLOAD_FILE_NAME = "payload.txt"
    }
}
