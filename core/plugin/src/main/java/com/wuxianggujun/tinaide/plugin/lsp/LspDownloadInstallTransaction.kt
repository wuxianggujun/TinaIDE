package com.wuxianggujun.tinaide.plugin.lsp

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

internal class LspDownloadInstallTransaction(
    private val targetDir: File,
    private val toolchainId: String,
    private val operationId: String,
    private val unmanagedTargetMessage: String,
) {
    private val parentDir = requireNotNull(targetDir.parentFile) { "Toolchain target must have a parent directory" }
    private val stagingDir = File(parentDir, ".${targetDir.name}.$operationId.staging")
    private val backupDir = File(parentDir, ".${targetDir.name}.$operationId.backup")
    private val transactionLock = transactionLocks.computeIfAbsent(targetDir.canonicalPath) { Mutex() }
    private var published = false
    private var hadTarget = false
    private var lockHeld = false

    suspend fun createStagingDirectory(): File {
        check(!lockHeld) { "Toolchain transaction lock is already held" }
        transactionLock.lock()
        lockHeld = true
        return try {
            ensureParentDirectory()
            recoverInterruptedTransaction()
            requireManagedOrEmptyTarget()
            check(!pathExists(stagingDir) && !pathExists(backupDir)) { "Toolchain transaction path already exists" }
            check(stagingDir.mkdir()) { "Failed to create toolchain staging directory" }
            writeOwnerMarker(stagingDir)
            stagingDir
        } catch (error: Throwable) {
            releaseLock()
            throw error
        }
    }

    fun publish() {
        check(pathExists(stagingDir)) { "Toolchain staging directory is missing" }
        requireManagedOrEmptyTarget()
        writeOwnerMarker(stagingDir)
        writePendingMarker(stagingDir)

        hadTarget = pathExists(targetDir)
        if (hadTarget) {
            moveDirectory(targetDir, backupDir)
        }
        try {
            moveDirectory(stagingDir, targetDir)
            published = true
        } catch (error: Exception) {
            if (hadTarget && pathExists(backupDir) && !pathExists(targetDir)) {
                runCatching { moveDirectory(backupDir, targetDir) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    fun commit(): Boolean {
        check(published) { "Toolchain transaction has not been published" }
        check(readPendingMarker(targetDir) == PendingInstall(toolchainId, operationId)) {
            "Toolchain transaction marker is missing or invalid"
        }
        check(Files.deleteIfExists(File(targetDir, PENDING_MARKER_NAME).toPath())) {
            "Failed to commit toolchain transaction marker"
        }
        val cleaned = !pathExists(backupDir) || runCatching { deleteTreeNoFollow(backupDir) }.isSuccess
        published = false
        hadTarget = false
        releaseLock()
        return cleaned
    }

    fun rollback() {
        try {
            if (!published) return
            if (pathExists(targetDir)) {
                deleteTreeNoFollow(targetDir)
            }
            if (hadTarget) {
                check(pathExists(backupDir)) { "Toolchain backup is missing during rollback" }
                moveDirectory(backupDir, targetDir)
            }
            published = false
            hadTarget = false
        } finally {
            releaseLock()
        }
    }

    fun cleanup() {
        try {
            if (pathExists(stagingDir)) {
                deleteTreeNoFollow(stagingDir)
            }
        } finally {
            releaseLock()
        }
    }

    private fun recoverInterruptedTransaction() {
        val staleStaging = ownedTransactionDirectories(".staging")
            .sortedByDescending(File::lastModified)
        val staleBackups = ownedTransactionDirectories(".backup")
            .sortedByDescending(File::lastModified)
        val stagedPending = staleStaging.asSequence()
            .mapNotNull(::readPendingMarker)
            .firstOrNull { pending -> pending.toolchainId == toolchainId }
        staleStaging.forEach(::deleteTreeNoFollow)

        val targetExists = pathExists(targetDir)
        val targetEntries = if (targetExists && targetDir.isDirectory) targetDir.listFiles() else null
        val targetOwner = if (targetExists) readOwnerMarker(targetDir) else null
        val targetIsProtected = targetExists && (
            Files.isSymbolicLink(targetDir.toPath()) ||
                !targetDir.isDirectory ||
                targetEntries == null ||
                targetEntries.isNotEmpty() && targetOwner != toolchainId
            )
        if (targetIsProtected) return

        val pending = if (targetExists) readPendingMarker(targetDir) else null
        require(pending == null || pending.toolchainId == toolchainId) { unmanagedTargetMessage }
        val restoreBackup = when {
            pending != null -> {
                deleteTreeNoFollow(targetDir)
                staleBackups.forOperation(pending.operationId)
            }
            !targetExists -> staleBackups.forOperation(stagedPending?.operationId)
                ?: staleBackups.firstOrNull()
            targetEntries?.isEmpty() == true && staleBackups.isNotEmpty() -> {
                deleteTreeNoFollow(targetDir)
                staleBackups.forOperation(stagedPending?.operationId)
                    ?: staleBackups.first()
            }
            else -> null
        }
        restoreBackup?.let { backup -> moveDirectory(backup, targetDir) }
        staleBackups.filterNot { it == restoreBackup }.forEach(::deleteTreeNoFollow)
    }

    private fun List<File>.forOperation(operationId: String?): File? {
        if (operationId.isNullOrBlank()) return null
        val expectedName = ".${targetDir.name}.$operationId.backup"
        return firstOrNull { backup -> backup.name == expectedName }
    }

    private fun ownedTransactionDirectories(suffix: String): List<File> {
        val prefix = ".${targetDir.name}."
        return parentDir.listFiles()
            .orEmpty()
            .filter { entry ->
                entry.name.startsWith(prefix) &&
                    entry.name.endsWith(suffix) &&
                    !Files.isSymbolicLink(entry.toPath()) &&
                    entry.isDirectory &&
                    readOwnerMarker(entry) == toolchainId
            }
    }

    private fun ensureParentDirectory() {
        if ((!parentDir.exists() && !parentDir.mkdirs()) || !parentDir.isDirectory) {
            error("Failed to create toolchain target parent directory")
        }
    }

    private fun requireManagedOrEmptyTarget() {
        if (!pathExists(targetDir)) return
        require(!Files.isSymbolicLink(targetDir.toPath()) && targetDir.isDirectory) {
            unmanagedTargetMessage
        }
        val entries = targetDir.listFiles() ?: throw IOException("Failed to inspect toolchain target directory")
        if (entries.isEmpty()) return

        require(readOwnerMarker(targetDir) == toolchainId) { unmanagedTargetMessage }
    }

    private fun writeOwnerMarker(directory: File) {
        val marker = File(directory, OWNER_MARKER_NAME)
        Files.deleteIfExists(marker.toPath())
        marker.writeText(toolchainId, StandardCharsets.UTF_8)
    }

    private fun readOwnerMarker(directory: File): String? = readSmallRegularFile(
        File(directory, OWNER_MARKER_NAME),
        MAX_OWNER_MARKER_BYTES,
    )?.trim()

    private fun writePendingMarker(directory: File) {
        File(directory, PENDING_MARKER_NAME).writeText(
            "$toolchainId\n$operationId",
            StandardCharsets.UTF_8,
        )
    }

    private fun readPendingMarker(directory: File): PendingInstall? {
        val lines = readSmallRegularFile(File(directory, PENDING_MARKER_NAME), MAX_PENDING_MARKER_BYTES)
            ?.lineSequence()
            ?.toList()
            ?: return null
        if (lines.size != 2 || lines.any(String::isBlank)) return null
        return PendingInstall(lines[0], lines[1])
    }

    private fun readSmallRegularFile(file: File, maxBytes: Long): String? {
        val path = file.toPath()
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) || file.length() > maxBytes) {
            return null
        }
        return file.readText(StandardCharsets.UTF_8)
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (atomicError: IOException) {
            try {
                Files.move(source.toPath(), target.toPath())
            } catch (fallbackError: IOException) {
                fallbackError.addSuppressed(atomicError)
                throw fallbackError
            }
        }
    }

    private fun deleteTreeNoFollow(directory: File) {
        Files.walkFileTree(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun pathExists(file: File): Boolean = Files.exists(
        file.toPath(),
        java.nio.file.LinkOption.NOFOLLOW_LINKS,
    )

    private fun releaseLock() {
        if (!lockHeld) return
        lockHeld = false
        transactionLock.unlock()
    }

    private data class PendingInstall(
        val toolchainId: String,
        val operationId: String,
    )

    companion object {
        internal const val OWNER_MARKER_NAME = ".tinaide-lsp-toolchain"
        internal const val PENDING_MARKER_NAME = ".tinaide-lsp-install-pending"
        private const val MAX_OWNER_MARKER_BYTES = 256L
        private const val MAX_PENDING_MARKER_BYTES = 512L
        private val transactionLocks = ConcurrentHashMap<String, Mutex>()
    }
}
