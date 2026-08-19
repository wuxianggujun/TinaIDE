package com.wuxianggujun.tinaide.core.common.io

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/**
 * 通用 Tar 解压工具
 *
 * 支持格式：
 * - .tar（纯 tar）
 * - .tar.gz / .tgz（gzip 压缩）
 * - .tar.xz / .txz（xz 压缩）
 * - .tar.zst（zstd 压缩）
 *
 * 特性：
 * - 自动检测压缩格式（magic bytes）
 * - 支持符号链接和硬链接
 * - 路径安全检查（防止 zip slip 攻击）
 * - 保留 Unix 文件权限
 */
object TarExtractor {

    private const val BUFFER_SIZE = 8192

    // Magic bytes
    private const val GZIP_MAGIC_1 = 0x1F
    private const val GZIP_MAGIC_2 = 0x8B
    private const val XZ_MAGIC_1 = 0xFD
    private const val XZ_MAGIC_2 = 0x37 // '7'
    private const val XZ_MAGIC_3 = 0x7A // 'z'
    private const val XZ_MAGIC_4 = 0x58 // 'X'
    private const val XZ_MAGIC_5 = 0x5A // 'Z'
    private const val XZ_MAGIC_6 = 0x00
    private const val ZSTD_MAGIC_1 = 0x28
    private const val ZSTD_MAGIC_2 = 0xB5
    private const val ZSTD_MAGIC_3 = 0x2F
    private const val ZSTD_MAGIC_4 = 0xFD

    /**
     * 压缩格式
     */
    enum class CompressionType {
        NONE, // 纯 tar
        GZIP, // tar.gz
        XZ, // tar.xz
        ZSTD // tar.zst
    }

    enum class SymlinkPolicy {
        RESTRICT_TO_TARGET_DIR,
        PRESERVE_ARCHIVE_TARGETS
    }

    /**
     * 从文件解压 tar 归档
     *
     * @param archiveFile tar/tar.gz/tar.xz 文件
     * @param targetDir 解压目标目录
     * @param estimatedTotalEntries 预估条目数（用于进度计算）
     * @param limits 可选归档展开限制
     * @param progress 进度回调 (0.0 - 1.0)
     */
    fun extract(
        archiveFile: File,
        targetDir: File,
        symlinkPolicy: SymlinkPolicy = SymlinkPolicy.RESTRICT_TO_TARGET_DIR,
        estimatedTotalEntries: Int = 2500,
        ensureActive: () -> Unit = {},
        limits: ArchiveExtractionLimits? = null,
        progress: (Float) -> Unit = {},
    ) {
        if (limits != null && archiveFile.length() > limits.maxArchiveBytes) {
            throw ArchiveLimitException("Archive is larger than the allowed size")
        }
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                extract(bis, targetDir, symlinkPolicy, estimatedTotalEntries, ensureActive, limits, progress)
            }
        }
    }

    /**
     * 从输入流解压 tar 归档（自动检测压缩格式）
     *
     * @param bufferedInput 带缓冲的输入流（必须支持 mark/reset）
     * @param targetDir 解压目标目录
     * @param estimatedTotalEntries 预估条目数（用于进度计算）
     * @param progress 进度回调 (0.0 - 1.0)
     */
    fun extract(
        bufferedInput: BufferedInputStream,
        targetDir: File,
        symlinkPolicy: SymlinkPolicy = SymlinkPolicy.RESTRICT_TO_TARGET_DIR,
        estimatedTotalEntries: Int = 2500,
        ensureActive: () -> Unit = {},
        limits: ArchiveExtractionLimits? = null,
        progress: (Float) -> Unit = {},
    ) {
        ensureActive()
        val compressionType = detectCompressionType(bufferedInput)
        val tarInput = wrapWithDecompressor(bufferedInput, compressionType)
        extractTarStream(tarInput, targetDir, symlinkPolicy, estimatedTotalEntries, ensureActive, limits, progress)
    }

    /**
     * 从输入流解压 tar 归档（指定压缩格式）
     */
    fun extract(
        input: InputStream,
        targetDir: File,
        compressionType: CompressionType,
        symlinkPolicy: SymlinkPolicy = SymlinkPolicy.RESTRICT_TO_TARGET_DIR,
        estimatedTotalEntries: Int = 2500,
        ensureActive: () -> Unit = {},
        limits: ArchiveExtractionLimits? = null,
        progress: (Float) -> Unit = {},
    ) {
        ensureActive()
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER_SIZE)
        val tarInput = wrapWithDecompressor(buffered, compressionType)
        extractTarStream(tarInput, targetDir, symlinkPolicy, estimatedTotalEntries, ensureActive, limits, progress)
    }

    /**
     * 检测压缩格式
     */
    private fun detectCompressionType(bufferedInput: BufferedInputStream): CompressionType {
        bufferedInput.mark(6)
        val header = ByteArray(6)
        val bytesRead = bufferedInput.read(header)
        bufferedInput.reset()

        if (bytesRead < 2) return CompressionType.NONE

        // 检查 gzip magic: 1F 8B
        if (header[0].toInt() and 0xFF == GZIP_MAGIC_1 &&
            header[1].toInt() and 0xFF == GZIP_MAGIC_2
        ) {
            return CompressionType.GZIP
        }

        // 检查 zstd magic: 28 B5 2F FD
        if (bytesRead >= 4 &&
            header[0].toInt() and 0xFF == ZSTD_MAGIC_1 &&
            header[1].toInt() and 0xFF == ZSTD_MAGIC_2 &&
            header[2].toInt() and 0xFF == ZSTD_MAGIC_3 &&
            header[3].toInt() and 0xFF == ZSTD_MAGIC_4
        ) {
            return CompressionType.ZSTD
        }

        // 检查 xz magic: FD 37 7A 58 5A 00
        if (bytesRead >= 6 &&
            header[0].toInt() and 0xFF == XZ_MAGIC_1 &&
            header[1].toInt() and 0xFF == XZ_MAGIC_2 &&
            header[2].toInt() and 0xFF == XZ_MAGIC_3 &&
            header[3].toInt() and 0xFF == XZ_MAGIC_4 &&
            header[4].toInt() and 0xFF == XZ_MAGIC_5 &&
            header[5].toInt() and 0xFF == XZ_MAGIC_6
        ) {
            return CompressionType.XZ
        }

        return CompressionType.NONE
    }

    /**
     * 根据压缩类型包装解压流
     */
    private fun wrapWithDecompressor(
        input: BufferedInputStream,
        compressionType: CompressionType
    ): TarArchiveInputStream = when (compressionType) {
        CompressionType.GZIP -> TarArchiveInputStream(GzipCompressorInputStream(input))
        CompressionType.XZ -> TarArchiveInputStream(XZCompressorInputStream(input))
        CompressionType.ZSTD -> TarArchiveInputStream(ZstdCompressorInputStream(input))
        CompressionType.NONE -> TarArchiveInputStream(input)
    }

    /**
     * 解压 tar 流的核心逻辑
     */
    private fun extractTarStream(
        tarInput: TarArchiveInputStream,
        targetDir: File,
        symlinkPolicy: SymlinkPolicy,
        estimatedTotalEntries: Int,
        ensureActive: () -> Unit,
        limits: ArchiveExtractionLimits?,
        progress: (Float) -> Unit,
    ) {
        var entryCount = 0
        val budget = limits?.let(::ArchiveExtractionBudget)

        tarInput.use { tarStream ->
            ensureActive()
            var entry = tarStream.nextEntry
            val pendingHardLinks = mutableListOf<PendingHardLink>()
            while (entry != null) {
                ensureActive()
                entryCount++
                budget?.beginEntry(entry.name, entry.size)
                val outputFile = ArchivePathSafety.resolveEntryFile(targetDir, entry.name, "tar entry")

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                    applyUnixModeIfPresent(outputFile, entry.mode)
                } else if (entry.isSymbolicLink) {
                    outputFile.parentFile?.mkdirs()
                    val linkTarget = when (symlinkPolicy) {
                        SymlinkPolicy.RESTRICT_TO_TARGET_DIR ->
                            ArchivePathSafety.requireSymlinkTargetInsideTargetDir(
                                targetDir = targetDir,
                                linkFile = outputFile,
                                linkTarget = entry.linkName,
                                source = "tar symlink target"
                            )
                        SymlinkPolicy.PRESERVE_ARCHIVE_TARGETS -> entry.linkName
                    }
                    recreateSymlink(outputFile, linkTarget)
                } else if (entry.isLink) {
                    pendingHardLinks.add(
                        PendingHardLink(
                            linkPath = outputFile,
                            targetPathInTar = ArchivePathSafety.sanitizeRelativePath(
                                entry.linkName,
                                "tar hardlink target"
                            ),
                            mode = entry.mode
                        )
                    )
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        if (budget != null) {
                            budget.copyEntry(tarStream, output, entry.name, ensureActive)
                        } else {
                            val buffer = ByteArray(BUFFER_SIZE)
                            var len: Int
                            while (tarStream.read(buffer).also { len = it } != -1) {
                                ensureActive()
                                output.write(buffer, 0, len)
                            }
                        }
                    }
                    applyUnixModeIfPresent(outputFile, entry.mode)
                }

                if (entryCount % 50 == 0) {
                    progress((entryCount.toFloat() / estimatedTotalEntries).coerceAtMost(0.99f))
                }

                entry = tarStream.nextEntry
            }

            pendingHardLinks.forEach { link ->
                ensureActive()
                val targetFile = ArchivePathSafety.resolveEntryFile(
                    targetDir,
                    link.targetPathInTar,
                    "tar hardlink target"
                )
                if (!targetFile.exists() || targetFile.isDirectory) {
                    throw IllegalStateException("Hardlink target missing: ${link.targetPathInTar}")
                }

                link.linkPath.parentFile?.mkdirs()
                if (link.linkPath.exists()) {
                    link.linkPath.delete()
                }

                try {
                    Os.link(targetFile.absolutePath, link.linkPath.absolutePath)
                } catch (_: Throwable) {
                    FileInputStream(targetFile).use { input ->
                        FileOutputStream(link.linkPath).use { output ->
                            if (budget != null) {
                                budget.copyEntry(input, output, link.targetPathInTar, ensureActive)
                            } else {
                                input.copyTo(output, BUFFER_SIZE)
                            }
                        }
                    }
                }

                applyUnixModeIfPresent(link.linkPath, link.mode)
            }
        }

        progress(1f)
    }

    private data class PendingHardLink(
        val linkPath: File,
        val targetPathInTar: String,
        val mode: Int
    )

    private fun recreateSymlink(linkFile: File, linkTarget: String) {
        if (linkFile.exists()) {
            linkFile.delete()
        }
        try {
            Os.symlink(linkTarget, linkFile.absolutePath)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to create symlink: ${linkFile.path} -> $linkTarget (${t.message})", t)
        }
    }

    private fun applyUnixModeIfPresent(file: File, mode: Int) {
        val perms = mode and 0x1FF
        if (perms and 0b001_001_001 != 0) file.setExecutable(true, false)
        if (perms and 0b100_100_100 != 0) file.setReadable(true, false)
        if (perms and 0b010_010_010 != 0) file.setWritable(true, false)
    }
}
