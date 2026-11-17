package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import java.nio.file.Files
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
 * 子目录。先在唯一暂存目录合并，再复用包安装事务发布；取消或复制失败不会修改已发布的库。
 */
object VcpkgSharedLibrary {
    private const val STAGING_PREFIX = ".shared-staging-"
    private const val BACKUP_PREFIX = ".shared-backup-"

    /** 导出使用预编译库的 triplet 命名，与项目工作区的 tina triplet 区分开。 */
    fun triplet(androidAbi: String): String =
        VcpkgProjectSettings(VcpkgProjectSettings.SHARED).targetTriplet(androidAbi)

    fun root(context: Context): File = ProjectPaths.getSharedVcpkgInstalledDir(context)

    /** 项目是否已经指向设备级共享库目录。 */
    fun usesSharedLibrary(context: Context, settings: VcpkgProjectSettings): Boolean {
        return settings.mode == VcpkgProjectSettings.SHARED
    }

    /**
     * 把项目已安装的 triplet 树导出到共享目录，返回共享目录根路径。
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
            VcpkgManagedPaths.installed(context, project, settings),
            settings.targetTriplet(androidAbi),
        )
        val files = if (source.isDirectory) filesWithinTree(source) else emptyList()
        // An empty tree means vcpkg never produced a library for this ABI; exporting nothing would leave
        // the project pointing at a shared directory that cannot satisfy the triplet check.
        if (files.isEmpty()) throw VcpkgException(Strings.vcpkg_shared_source_missing)
        val operation = currentCoroutineContext()
        val shared = root(context)
        Files.createDirectories(shared.toPath())
        PackageInstallCoordinator.withExclusiveAccess(shared) {
            val operationId = PackageInstallCoordinator.createOperationId()
            val triplet = triplet(androidAbi)
            val target = File(shared, triplet)
            val staging = PackageInstallCoordinator.resolveTransactionDirectory(shared, STAGING_PREFIX, triplet, operationId)
            Files.createDirectories(staging.toPath())
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
                val retainedBackup = PackageInstallCoordinator.publishStagedDirectory(
                    shared, staging, target, BACKUP_PREFIX, triplet, operationId,
                )
                if (retainedBackup != null) {
                    Timber.tag("VcpkgSharedLibrary").w("Could not remove shared library backup: %s", retainedBackup.name)
                }
            } finally {
                if (staging.exists() && !staging.deleteRecursively()) {
                    Timber.tag("VcpkgSharedLibrary").w("Could not remove shared library staging: %s", staging.name)
                }
            }
        }
        onProgress(Strings.vcpkg_shared_export_done.strOr(context))
        shared
    }

    private fun filesWithinTree(root: File): List<File> {
        val boundary = root.canonicalFile.toPath()
        return root.walkTopDown().filter(File::isFile).onEach { file ->
            if (!file.canonicalFile.toPath().startsWith(boundary)) throw VcpkgException(Strings.vcpkg_invalid_path)
        }.toList()
    }

    private fun copyToStaging(file: File, source: File, staging: File) {
        val destination = File(staging, file.relativeTo(source).path)
        Files.createDirectories(destination.parentFile!!.toPath())
        file.copyTo(destination, overwrite = true)
    }
}
