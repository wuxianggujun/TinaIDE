package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 设备级共享依赖库目录。
 *
 * 项目按清单把依赖装进私有工作区后，可把对应的 triplet 目录导出到这里，其他项目再以
 * [VcpkgProjectSettings.SHARED] 模式直接链接，无需重复编译同一批库。目录按 ABI 分 triplet
 * 子目录。库与安装索引写入同一代目录，原子切换指针后读者才能看见新版本。
 */
object VcpkgSharedLibrary {
    private const val LEGACY_GENERATION = "legacy"
    private const val LEGACY_STAGING_PREFIX = ".shared-staging-"
    private const val LEGACY_BACKUP_PREFIX = ".shared-backup-"

    private fun pointer(shared: File, triplet: String) = File(shared, ".current-$triplet")
    private fun generations(shared: File, triplet: String) = File(shared, ".versions/$triplet")

    /** 导出使用预编译库的 triplet 命名，与项目工作区的 tina triplet 区分开。 */
    fun triplet(androidAbi: String): String =
        VcpkgProjectSettings(VcpkgProjectSettings.SHARED).targetTriplet(androidAbi)

    fun root(context: Context): File = ProjectPaths.getSharedVcpkgInstalledDir(context)

    suspend fun publishedRoot(context: Context, androidAbi: String): File = withContext(Dispatchers.IO) {
        val shared = root(context)
        val triplet = triplet(androidAbi)
        if (shared.isDirectory && !pointer(shared, triplet).exists() && !File(shared, triplet).exists()) {
            PackageInstallCoordinator.withExclusiveAccess(shared) { recoverLegacyBackup(shared, triplet) }
        }
        VcpkgBuildUse.sharedRoot(pointer(shared, triplet)) { readPublished(shared, triplet) }
    }

    private fun readPublished(shared: File, triplet: String): File {
        val reference = pointer(shared, triplet)
        if (Files.isSymbolicLink(reference.toPath())) throw VcpkgException(Strings.vcpkg_invalid_path)
        if (!reference.exists()) return shared // Installations exported by 0.19.5.
        try {
            val id = PackageMetadataFiles.decodeUtf8(PackageMetadataFiles.readBytes(reference, 36))
            if (!isGenerationId(id)) throw VcpkgException(Strings.vcpkg_invalid_path)
            val versions = generations(shared, triplet)
            val generation = File(versions, id)
            if (listOf(versions.parentFile!!, versions, generation, File(generation, triplet)).any {
                    Files.isSymbolicLink(it.toPath())
                } || !File(generation, triplet).isDirectory) {
                throw VcpkgException(Strings.vcpkg_invalid_path)
            }
            return generation
        } catch (error: Exception) {
            throw VcpkgException(Strings.vcpkg_invalid_path, error)
        }
    }

    private fun isGenerationId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    /** A killed old two-rename export may have moved the only usable ABI tree into its backup. */
    private fun recoverLegacyBackup(shared: File, triplet: String) {
        if (pointer(shared, triplet).exists() || File(shared, triplet).exists()) return
        val backup = legacyTransactions(shared, triplet, LEGACY_BACKUP_PREFIX)
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name }) ?: return
        Files.move(backup.toPath(), File(shared, triplet).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun legacyTransactions(shared: File, triplet: String, prefix: String): List<File> =
        shared.listFiles().orEmpty().filter {
            it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                PackageInstallCoordinator.packageIdFromTransactionDirectory(it, prefix) == triplet
        }

    /** Cleanup must not turn an already committed publication into a reported export failure. */
    private fun collectObsoleteGenerations(shared: File, triplet: String) {
        try {
            pruneGenerations(shared, triplet)
        } catch (error: Exception) {
            Timber.tag("VcpkgSharedLibrary").w(error, "Could not collect obsolete shared generations")
        }
    }

    private fun pruneGenerations(shared: File, triplet: String) {
        val versions = generations(shared, triplet)
        val current = readPublished(shared, triplet)
        val previous = File(current, ".previous").takeIf(File::isFile)?.let {
            PackageMetadataFiles.decodeUtf8(PackageMetadataFiles.readBytes(it, 36))
        }
        check(previous == null || previous == LEGACY_GENERATION || isGenerationId(previous))
        val obsolete = versions.listFiles().orEmpty().filter {
            it != current && it.name != previous && (isGenerationId(it.name) || it.name.startsWith(".retired-"))
        }
        val legacy = File(shared, triplet).takeIf { previous != LEGACY_GENERATION && it.isDirectory }
        val transactions = legacyTransactions(shared, triplet, LEGACY_STAGING_PREFIX) +
            legacyTransactions(shared, triplet, LEGACY_BACKUP_PREFIX) +
            shared.listFiles().orEmpty().filter { it.name.startsWith(".retired-") }
        VcpkgBuildUse.retireWhenIdle(versions, obsolete + listOfNotNull(legacy) + transactions) {
            PackageInstallCoordinator.withExclusiveAccess(shared) { collectObsoleteGenerations(shared, triplet) }
        }
    }

    /** 项目是否已经指向设备级共享库目录。 */
    fun usesSharedLibrary(context: Context, settings: VcpkgProjectSettings): Boolean {
        return settings.mode == VcpkgProjectSettings.SHARED
    }

    /**
     * 把项目已安装的 triplet 树导出到共享目录，返回这一代不可变 installed 根路径。
     *
     * @throws VcpkgException 项目还没有装出对应 ABI 的依赖时抛出，交由上层提示用户先安装。
     */
    suspend fun export(
        context: Context,
        project: File,
        settings: VcpkgProjectSettings,
        androidAbi: String,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val source = File(
            VcpkgManagedPaths.installed(context, project, settings, androidAbi),
            settings.targetTriplet(androidAbi),
        )
        val files = if (source.isDirectory) filesWithinTree(source) else emptyList()
        // An empty tree means vcpkg never produced a library for this ABI; exporting nothing would leave
        // the project pointing at a shared directory that cannot satisfy the triplet check.
        if (files.isEmpty()) throw VcpkgException(Strings.vcpkg_shared_source_missing)
        val operation = currentCoroutineContext()
        val shared = root(context)
        Files.createDirectories(shared.toPath())
        val published = PackageInstallCoordinator.withExclusiveAccess(shared) {
            val operationId = PackageInstallCoordinator.createOperationId()
            val triplet = triplet(androidAbi)
            recoverLegacyBackup(shared, triplet)
            val previous = readPublished(shared, triplet)
            val target = File(previous, triplet)
            val versions = generations(shared, triplet)
            val next = File(versions, operationId)
            val staging = File(next, triplet)
            Files.createDirectories(staging.toPath())
            var committed = false
            try {
                // Preserve libraries from other projects while replacing only files from this export.
                if (target.isDirectory) {
                    filesWithinTree(target).forEach { file ->
                        operation.ensureActive()
                        copyToStaging(file, target, staging)
                    }
                }
                var lastPercentage = -1
                files.forEachIndexed { index, file ->
                    operation.ensureActive()
                    copyToStaging(file, source, staging)
                    val percentage = (index + 1) * 100 / files.size
                    if (percentage != lastPercentage) {
                        lastPercentage = percentage
                        onProgress(Strings.vcpkg_shared_exporting.strOr(context, percentage))
                    }
                }
                operation.ensureActive()
                val oldIndex = VcpkgInstalledStatus.read(File(previous, "vcpkg/status"), triplet, operation::ensureActive)
                val exportedIndex = VcpkgInstalledStatus.read(File(source.parentFile, "vcpkg/status"),
                    settings.targetTriplet(androidAbi), operation::ensureActive)
                VcpkgInstalledStatus.write(File(next, "vcpkg/status"), triplet, oldIndex + exportedIndex, operation::ensureActive)
                val previousId = if (previous == shared) LEGACY_GENERATION else previous.name
                File(next, ".previous").writeText(previousId, Charsets.UTF_8)
                val reference = File(next, ".publish").apply { writeText(operationId, Charsets.UTF_8) }
                operation.ensureActive()
                Files.move(reference.toPath(), pointer(shared, triplet).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                committed = true
                collectObsoleteGenerations(shared, triplet)
                next
            } finally {
                if (!committed && !next.deleteRecursively()) {
                    Timber.tag("VcpkgSharedLibrary").w("Could not remove unpublished generation")
                }
            }
        }
        onProgress(Strings.vcpkg_shared_export_done.strOr(context))
        published
    }

    private fun filesWithinTree(root: File): List<File> {
        val boundary = root.canonicalFile.toPath()
        return root.walkTopDown().onEnter {
            if (Files.isSymbolicLink(it.toPath())) throw VcpkgException(Strings.vcpkg_invalid_path)
            true
        }.onFail { _, error -> throw error }.filter(File::isFile).onEach { file ->
            if (Files.isSymbolicLink(file.toPath()) || !file.canonicalFile.toPath().startsWith(boundary)) {
                throw VcpkgException(Strings.vcpkg_invalid_path)
            }
        }.toList()
    }

    private fun copyToStaging(file: File, source: File, staging: File) {
        val destination = File(staging, file.relativeTo(source).path)
        Files.createDirectories(destination.parentFile!!.toPath())
        file.copyTo(destination, overwrite = true)
    }
}
