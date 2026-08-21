package com.wuxianggujun.tinaide.core.linuxdistro

import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import java.io.File

interface LinuxDistroArchiveExtractor {
    fun extract(
        archiveFile: File,
        targetDir: File,
        format: DistroArchiveFormat,
        ensureActive: () -> Unit = {},
        progress: (Float) -> Unit = {},
    )
}

class TarLinuxDistroArchiveExtractor : LinuxDistroArchiveExtractor {
    override fun extract(
        archiveFile: File,
        targetDir: File,
        format: DistroArchiveFormat,
        ensureActive: () -> Unit,
        progress: (Float) -> Unit,
    ) {
        require(archiveFile.isFile) { "Rootfs archive does not exist: ${archiveFile.absolutePath}" }
        require(archiveFile.length() <= DISTRO_ARCHIVE_LIMITS.maxArchiveBytes) {
            "Rootfs archive is larger than the allowed size"
        }
        targetDir.mkdirs()
        TarExtractor.extract(
            input = archiveFile.inputStream(),
            targetDir = targetDir,
            compressionType = format.compressionType(),
            symlinkPolicy = TarExtractor.SymlinkPolicy.PRESERVE_ARCHIVE_TARGETS,
            limits = DISTRO_ARCHIVE_LIMITS,
            archiveSizeBytes = archiveFile.length(),
            ensureActive = ensureActive,
            progress = progress,
        )
    }

    private companion object {
        val DISTRO_ARCHIVE_LIMITS = ArchiveExtractionLimits(
            maxArchiveBytes = DEFAULT_MAX_DISTRO_ARCHIVE_BYTES,
            maxExpandedBytes = 8L * 1024L * 1024L * 1024L,
            maxEntryBytes = 2L * 1024L * 1024L * 1024L,
            maxEntryCount = 500_000,
            maxCompressionRatio = 1_000,
        )
    }
}
