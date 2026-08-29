package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun <T> withLinuxDistroMutationLock(
    targetRootfsDir: File,
    block: suspend () -> T,
): T = LinuxDistroMutationLocks.forTarget(targetRootfsDir).withLock { block() }

private object LinuxDistroMutationLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forTarget(targetRootfsDir: File): Mutex {
        val key = targetRootfsDir.toPath().toAbsolutePath().normalize().toString()
        return locks.computeIfAbsent(key) { Mutex() }
    }
}

class LinuxDistroInstaller(
    private val catalog: LinuxDistroCatalog,
    private val downloader: LinuxDistroDownloader = OkHttpLinuxDistroDownloader(),
    private val checksumVerifier: LinuxDistroChecksumVerifier = MessageDigestLinuxDistroChecksumVerifier(),
    private val archiveExtractor: LinuxDistroArchiveExtractor = TarLinuxDistroArchiveExtractor(),
    private val rootfsConfigurator: LinuxDistroRootfsConfigurator = BasicLinuxDistroRootfsConfigurator(),
    private val metadataStore: LinuxDistroInstallMetadataStore = JsonLinuxDistroInstallMetadataStore(),
    private val rootfsProbe: LinuxDistroRootfsProbe = BasicLinuxDistroRootfsProbe,
    private val clock: () -> Long = System::currentTimeMillis,
    private val downloadCandidateOrder: (canonicalUrl: String, mirrorUrls: List<String>) -> List<String> =
        { canonicalUrl, mirrorUrls -> listOf(canonicalUrl) + mirrorUrls },
) {

    suspend fun install(
        request: LinuxDistroInstallRequest,
        progress: (LinuxDistroInstallProgress) -> Unit = {},
    ): LinuxDistroInstallResult = withLinuxDistroMutationLock(request.layout.rootfsDir(request.distroId)) {
        installUnderMutationLock(request, progress)
    }

    internal suspend fun installUnderMutationLock(
        request: LinuxDistroInstallRequest,
        progress: (LinuxDistroInstallProgress) -> Unit,
    ): LinuxDistroInstallResult {
        val coroutineContext = currentCoroutineContext()
        request.layout.ensureDirectories()
        progress(request.progress(LinuxDistroInstallPhase.PREPARING, 0.01f))

        val resolved = catalog.resolveArtifact(
            distroId = request.distroId,
            releaseId = request.releaseId,
            architecture = request.architecture,
        ) ?: error(
            "No rootfs artifact for distro=${request.distroId}, release=${request.releaseId ?: "default"}, " +
                "arch=${request.architecture}",
        )
        progress(request.progress(LinuxDistroInstallPhase.RESOLVING_ARTIFACT, 0.05f, resolved))

        val targetRootfsDir = request.layout.rootfsDir(resolved.distro.id)
        recoverInterruptedReplacement(targetRootfsDir)
        cleanupInterruptedStagingDirectories(request.layout, resolved.distro.id)
        if (targetRootfsDir.isDirectory && !request.reinstall) {
            val existingInstallation = metadataStore.read(targetRootfsDir)
            if (existingInstallation != null &&
                existingInstallation.matchesResolvedArtifact(resolved, targetRootfsDir) &&
                rootfsProbe.hasBootShell(targetRootfsDir)
            ) {
                metadataStore.write(targetRootfsDir, existingInstallation)
                progress(request.progress(LinuxDistroInstallPhase.COMPLETED, 1f, resolved))
                return LinuxDistroInstallResult(
                    resolved = resolved,
                    rootfsDir = targetRootfsDir,
                    archiveFile = request.layout.archiveFile(resolved),
                    installation = existingInstallation,
                    installed = false,
                )
            }
        }

        val archiveFile = request.layout.archiveFile(resolved)
        ensureArchive(resolved, archiveFile, request, progress)
        coroutineContext.ensureActive()
        progress(request.progress(LinuxDistroInstallPhase.VERIFYING, 0.45f, resolved))
        try {
            checksumVerifier.requireValid(archiveFile, resolved.artifact.checksum)
        } catch (throwable: Throwable) {
            archiveFile.delete()
            throw throwable
        }

        val stagingRootfsDir = request.layout.newStagingRootfsDir(resolved.distro.id)
        check(!stagingRootfsDir.exists() || stagingRootfsDir.deleteRecursively()) {
            "Failed to clean linux distro staging directory: ${stagingRootfsDir.absolutePath}"
        }
        try {
            progress(request.progress(LinuxDistroInstallPhase.EXTRACTING, 0.50f, resolved))
            archiveExtractor.extract(
                archiveFile = archiveFile,
                targetDir = stagingRootfsDir,
                format = resolved.artifact.format,
                ensureActive = { coroutineContext.ensureActive() },
            ) { extractProgress ->
                progress(
                    request.progress(
                        LinuxDistroInstallPhase.EXTRACTING,
                        0.50f + extractProgress.coerceIn(0f, 1f) * 0.35f,
                        resolved,
                    ),
                )
            }

            coroutineContext.ensureActive()
            progress(request.progress(LinuxDistroInstallPhase.CONFIGURING, 0.88f, resolved))
            rootfsConfigurator.configure(stagingRootfsDir, request.rootfsConfig)

            coroutineContext.ensureActive()
            val installedAt = clock()
            val installation = createInstallation(
                resolved = resolved,
                rootfsDir = targetRootfsDir,
                archiveFile = archiveFile,
                installedAtEpochMillis = installedAt,
            )
            metadataStore.write(stagingRootfsDir, installation)

            progress(request.progress(LinuxDistroInstallPhase.REGISTERING, 0.95f, resolved))
            replaceRootfsDirectory(stagingRootfsDir, targetRootfsDir)

            progress(request.progress(LinuxDistroInstallPhase.COMPLETED, 1f, resolved))
            return LinuxDistroInstallResult(
                resolved = resolved,
                rootfsDir = targetRootfsDir,
                archiveFile = archiveFile,
                installation = installation,
                installed = true,
            )
        } catch (throwable: Throwable) {
            stagingRootfsDir.deleteRecursively()
            throw throwable
        }
    }

    private fun replaceRootfsDirectory(
        stagingRootfsDir: File,
        targetRootfsDir: File,
    ) {
        targetRootfsDir.parentFile?.mkdirs()
        val backupRootfsDir = targetRootfsDir.takeIf { it.exists() }?.let { newReplacementBackupDir(it) }

        try {
            if (backupRootfsDir != null) {
                check(targetRootfsDir.renameTo(backupRootfsDir)) {
                    "Failed to backup rootfs from ${targetRootfsDir.absolutePath} to ${backupRootfsDir.absolutePath}"
                }
            }

            check(stagingRootfsDir.renameTo(targetRootfsDir)) {
                "Failed to move rootfs from ${stagingRootfsDir.absolutePath} to ${targetRootfsDir.absolutePath}"
            }

            backupRootfsDir?.deleteRecursively()
        } catch (throwable: Throwable) {
            if (backupRootfsDir != null && backupRootfsDir.exists()) {
                val restoreFailure = runCatching {
                    if (targetRootfsDir.exists()) {
                        targetRootfsDir.deleteRecursively()
                    }
                    check(backupRootfsDir.renameTo(targetRootfsDir)) {
                        "Failed to restore rootfs backup from ${backupRootfsDir.absolutePath} to ${targetRootfsDir.absolutePath}"
                    }
                }.exceptionOrNull()
                restoreFailure?.let(throwable::addSuppressed)
            }
            throw throwable
        }
    }

    private fun recoverInterruptedReplacement(targetRootfsDir: File) {
        val parentDir = targetRootfsDir.parentFile ?: return
        val targetPath = targetRootfsDir.toPath()
        check(!Files.isSymbolicLink(targetPath)) { "Linux distro rootfs target must not be a symbolic link" }

        val backupPrefix = ".${targetRootfsDir.name}.replace-backup-"
        val backups = parentDir.listFiles()
            .orEmpty()
            .filter { candidate ->
                candidate.name.startsWith(backupPrefix) &&
                    Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        if (backups.isEmpty()) return

        if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            val restore = backups.first()
            check(restore.renameTo(targetRootfsDir)) {
                "Failed to restore interrupted linux distro replacement"
            }
        }
        backups.filter(File::exists).forEach { staleBackup ->
            check(staleBackup.deleteRecursively()) {
                "Failed to clean interrupted linux distro backup"
            }
        }
    }

    private fun cleanupInterruptedStagingDirectories(
        layout: LinuxDistroInstallLayout,
        distroId: String,
    ) {
        val prefix = "$distroId-"
        layout.stagingDir.listFiles()
            .orEmpty()
            .filter { candidate ->
                candidate.name.startsWith(prefix) &&
                    candidate.name.removePrefix(prefix).isUuid() &&
                    Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            .forEach { staleStaging ->
                check(staleStaging.deleteRecursively()) {
                    "Failed to clean interrupted linux distro staging directory"
                }
            }
    }

    private fun newReplacementBackupDir(targetRootfsDir: File): File {
        val parentDir = targetRootfsDir.parentFile ?: error("Rootfs has no parent: ${targetRootfsDir.absolutePath}")
        val timestamp = clock()
        for (attempt in 0 until 100) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = File(parentDir, ".${targetRootfsDir.name}.replace-backup-$timestamp$suffix")
            if (!candidate.exists()) return candidate
        }
        error("Failed to allocate rootfs backup path for ${targetRootfsDir.absolutePath}")
    }

    private fun String.isUuid(): Boolean = runCatching { java.util.UUID.fromString(this) }.isSuccess

    private suspend fun ensureArchive(
        resolved: ResolvedDistroArtifact,
        archiveFile: File,
        request: LinuxDistroInstallRequest,
        progress: (LinuxDistroInstallProgress) -> Unit,
    ) {
        if (archiveFile.isFile) {
            val existing = checksumVerifier.verify(archiveFile, resolved.artifact.checksum)
            if (existing.isValid) return
            archiveFile.delete()
        }

        progress(request.progress(LinuxDistroInstallPhase.DOWNLOADING, 0.10f, resolved))

        val candidates = downloadCandidates(resolved.artifact.url)
        var lastError: Throwable? = null
        for (url in candidates) {
            // 切换镜像前清掉上一候选的残留，避免续传到不同源的半包文件。
            archiveFile.delete()
            try {
                downloader.download(
                    request = DistroDownloadRequest(
                        url = url,
                        targetFile = archiveFile,
                        resume = true,
                        expectedSizeBytes = resolved.artifact.sizeBytes,
                    ),
                ) { downloadProgress ->
                    val fraction = downloadProgress.fraction ?: 0f
                    progress(request.progress(LinuxDistroInstallPhase.DOWNLOADING, 0.10f + fraction * 0.30f, resolved))
                }
                return
            } catch (cancellation: CancellationException) {
                // 用户取消：不再尝试其它镜像，直接向上抛。
                throw cancellation
            } catch (throwable: Throwable) {
                archiveFile.delete()
                lastError = throwable
            }
        }
        throw lastError ?: IllegalStateException("No valid linux distro download candidates")
    }

    /** 规范地址优先，随后按清单镜像规则派生候选（去重）。 */
    private fun downloadCandidates(url: String): List<String> {
        val mirrors = catalog.mirrorRules().mapNotNull { rule -> rule.deriveOrNull(url) }
        return downloadCandidateOrder(url, mirrors).distinct()
    }

    private fun createInstallation(
        resolved: ResolvedDistroArtifact,
        rootfsDir: File,
        archiveFile: File,
        installedAtEpochMillis: Long,
    ): InstalledLinuxDistro = InstalledLinuxDistro(
        distroId = resolved.distro.id,
        releaseId = resolved.release.id,
        architecture = resolved.artifact.architecture,
        displayName = resolved.distro.displayName,
        packageManager = resolved.distro.packageManager,
        rootfsPath = rootfsDir.absolutePath,
        archivePath = archiveFile.absolutePath,
        checksum = resolved.artifact.checksum,
        installedAtEpochMillis = installedAtEpochMillis,
        updatedAtEpochMillis = clock().coerceAtLeast(installedAtEpochMillis),
    )

    private fun LinuxDistroInstallRequest.progress(
        phase: LinuxDistroInstallPhase,
        fraction: Float,
        resolved: ResolvedDistroArtifact? = null,
    ): LinuxDistroInstallProgress = LinuxDistroInstallProgress(
        phase = phase,
        fraction = fraction.coerceIn(0f, 1f),
        distroId = resolved?.distro?.id ?: distroId,
        releaseId = resolved?.release?.id ?: releaseId,
        architecture = resolved?.artifact?.architecture ?: architecture,
    )

    private fun InstalledLinuxDistro.matchesResolvedArtifact(
        resolved: ResolvedDistroArtifact,
        rootfsDir: File,
    ): Boolean = distroId == resolved.distro.id &&
        releaseId == resolved.release.id &&
        architecture == resolved.artifact.architecture &&
        checksum == resolved.artifact.checksum &&
        rootfsPath == rootfsDir.absolutePath
}

data class LinuxDistroInstallRequest(
    val distroId: String,
    val architecture: DistroArchitecture,
    val layout: LinuxDistroInstallLayout,
    val releaseId: String? = null,
    val reinstall: Boolean = false,
    val rootfsConfig: LinuxDistroRootfsConfig = LinuxDistroRootfsConfig(),
)

data class LinuxDistroInstallResult(
    val resolved: ResolvedDistroArtifact,
    val rootfsDir: File,
    val archiveFile: File,
    val installation: InstalledLinuxDistro,
    val installed: Boolean,
)

data class LinuxDistroInstallProgress(
    val phase: LinuxDistroInstallPhase,
    val fraction: Float,
    val distroId: String,
    val releaseId: String?,
    val architecture: DistroArchitecture,
)

enum class LinuxDistroInstallPhase {
    PREPARING,
    RESOLVING_ARTIFACT,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    CONFIGURING,
    REGISTERING,
    COMPLETED,
}
