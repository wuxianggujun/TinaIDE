package com.wuxianggujun.tinaide.core.packages

import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object PackageInstallCoordinator {
    const val BUNDLED_STAGING_PREFIX = ".bundled-staging-"
    const val BUNDLED_BACKUP_PREFIX = ".bundled-backup-"
    const val DOWNLOAD_STAGING_PREFIX = ".download-staging-"
    const val DOWNLOAD_BACKUP_PREFIX = ".download-backup-"

    private const val INSTALL_LOCK_FILE_NAME = ".package-install.lock"
    private const val UUID_TEXT_LENGTH = 36
    private val mutex = Mutex()

    suspend fun <T> withExclusiveAccess(installDir: File, action: () -> T): T = mutex.withLock {
        val lockFile = File(installDir, INSTALL_LOCK_FILE_NAME)
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val fileLock = channel.lock()
            try {
                action()
            } finally {
                fileLock.release()
            }
        }
    }

    fun createOperationId(): String = UUID.randomUUID().toString()

    fun resolveTransactionDirectory(
        installDir: File,
        prefix: String,
        packageId: String,
        operationId: String,
    ): File {
        RegistryPackageId.requireValid(packageId)
        require(UUID.fromString(operationId).toString() == operationId) { "Invalid package install operation id" }
        return File(installDir, "$prefix$packageId-$operationId")
    }

    fun packageIdFromTransactionDirectory(directory: File, prefix: String): String? {
        val suffix = directory.name.removePrefix(prefix)
        if (suffix == directory.name || suffix.length <= UUID_TEXT_LENGTH + 1) return null
        val separatorIndex = suffix.length - UUID_TEXT_LENGTH - 1
        if (suffix[separatorIndex] != '-') return null
        val packageId = suffix.substring(0, separatorIndex)
        val operationId = suffix.substring(separatorIndex + 1)
        return packageId.takeIf(RegistryPackageId::isValid)
            ?.takeIf { runCatching { UUID.fromString(operationId) }.isSuccess }
    }

    fun publishStagedDirectory(
        installDir: File,
        stagingDir: File,
        targetDir: File,
        backupPrefix: String,
        packageId: String,
        operationId: String,
    ): File? {
        val backupDir = resolveTransactionDirectory(
            installDir = installDir,
            prefix = backupPrefix,
            packageId = packageId,
            operationId = operationId,
        )
        var oldTargetMoved = false
        try {
            if (targetDir.exists()) {
                check(targetDir.renameTo(backupDir)) { "Failed to move existing package to backup" }
                oldTargetMoved = true
            }
            check(stagingDir.renameTo(targetDir)) { "Failed to publish package" }
            return backupDir.takeIf { oldTargetMoved && !it.deleteRecursively() }
        } catch (error: Throwable) {
            if (oldTargetMoved && !targetDir.exists()) {
                runCatching {
                    check(backupDir.renameTo(targetDir)) { "Failed to restore package backup" }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }
}
