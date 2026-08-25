package com.wuxianggujun.tinaide.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionBudget
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import com.wuxianggujun.tinaide.core.common.io.ArchivePathSafety
import com.wuxianggujun.tinaide.core.common.io.ZipArchiveValidator
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.project.ProjectIdentity
import com.wuxianggujun.tinaide.project.ProjectMetadata
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object ProjectImporter {

    private const val TAG = "ProjectImporter"
    private const val MEBIBYTE = 1024L * 1024L
    private const val COPY_BUFFER_SIZE = 32 * 1024
    private const val MAX_DIRECTORY_DEPTH = 64
    private val archiveLimits = ArchiveExtractionLimits(
        maxArchiveBytes = 256L * MEBIBYTE,
        maxExpandedBytes = 1024L * MEBIBYTE,
        maxEntryBytes = 256L * MEBIBYTE,
        maxEntryCount = 50_000,
        maxCompressionRatio = 500L,
        maxPathDepth = MAX_DIRECTORY_DEPTH,
    )

    private val tarSuffixes = listOf(
        ".tar.gz",
        ".tgz",
        ".tar.xz",
        ".txz",
        ".tar.zst",
        ".tar"
    )

    suspend fun importDirectory(
        context: Context,
        uri: Uri,
        projectsRoot: File,
        projectLocationManager: ProjectLocationManager,
        storageManager: StorageManager
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistablePermission(
                context = context,
                uri = uri,
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val projectDir = resolveDirectoryFromTreeUri(context, uri)
                ?.takeIf { it.exists() && it.isDirectory }
                ?: throw IllegalArgumentException(Strings.error_import_directory_not_supported.strOr(context))

            if (!storageManager.canAccessProjectDir(projectDir)) {
                throw IllegalArgumentException(Strings.permission_storage_settings.strOr(context))
            }

            if ((!projectsRoot.exists() && !projectsRoot.mkdirs()) || !projectsRoot.isDirectory) {
                throw IllegalStateException(Strings.error_create_project_dir.strOr(context))
            }

            val targetName = ProjectImportNamePolicy.projectName(projectDir.name)
            val targetDir = File(projectsRoot, targetName)
            val sourcePath = runCatching { projectDir.canonicalPath }.getOrElse { projectDir.absolutePath }
            val targetPath = runCatching { targetDir.canonicalPath }.getOrElse { targetDir.absolutePath }
            val projectsRootPath = projectsRoot.canonicalPath

            if (sourcePath == targetPath) {
                projectLocationManager.registerProjectAndAwait(projectDir)
                return@runCatching projectDir
            }
            if (projectsRootPath == sourcePath ||
                projectsRootPath.startsWith(sourcePath.trimEnd(File.separatorChar) + File.separator)
            ) {
                throw IllegalArgumentException(Strings.error_project_import_recursive_source.strOr(context))
            }

            if (targetDir.exists()) {
                throw IllegalArgumentException(Strings.error_project_name_exists.strOr(context))
            }

            val stagingDir = File(projectsRoot, ".import-${UUID.randomUUID()}")
            val copiedProjectDir = File(stagingDir, targetName)
            if (!stagingDir.mkdirs()) {
                throw IllegalStateException(Strings.toast_import_failed.strOr(context))
            }

            var publishedProjectDir: File? = null
            var importedMetadata: ProjectMetadata? = null
            try {
                copyDirectoryWithBudget(context, projectDir, copiedProjectDir)

                if (!copiedProjectDir.renameTo(targetDir)) {
                    throw IllegalStateException(Strings.toast_import_failed.strOr(context))
                }
                publishedProjectDir = targetDir
                importedMetadata = ensureUniqueImportedProjectIdentity(
                    projectDir = targetDir,
                    projectsRoot = projectsRoot,
                    projectLocationManager = projectLocationManager,
                )

                projectLocationManager.registerProjectAndAwait(targetDir)
                targetDir
            } catch (error: Throwable) {
                publishedProjectDir?.let { published ->
                    importedMetadata?.id?.let { projectId ->
                        unregisterImportedProjectMapping(
                            projectLocationManager = projectLocationManager,
                            projectId = projectId,
                            projectDir = published,
                            error = error,
                        )
                    }
                    if (published.exists() && !published.deleteRecursively()) {
                        error.addSuppressed(IllegalStateException("Failed to roll back imported project"))
                    }
                }
                throw error
            } finally {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }
        }
    }

    private fun copyDirectoryWithBudget(context: Context, sourceRoot: File, targetRoot: File) {
        if (Files.isSymbolicLink(sourceRoot.toPath())) {
            throw IllegalArgumentException(Strings.error_project_import_symlink.strOr(context))
        }
        val canonicalSourceRoot = sourceRoot.canonicalFile
        val canonicalTargetRoot = targetRoot.canonicalFile
        if (!targetRoot.mkdirs()) {
            throw IllegalStateException(Strings.toast_import_failed.strOr(context))
        }

        var entryCount = 0
        var copiedBytes = 0L
        val pendingDirectories = ArrayDeque<DirectoryCopyEntry>()
        pendingDirectories.add(DirectoryCopyEntry(canonicalSourceRoot, canonicalTargetRoot, depth = 0))

        while (pendingDirectories.isNotEmpty()) {
            val current = pendingDirectories.removeLast()
            val children = current.source.listFiles()
                ?: throw IllegalStateException(Strings.toast_import_failed.strOr(context))
            for (child in children) {
                entryCount++
                if (entryCount > archiveLimits.maxEntryCount) {
                    throw IllegalArgumentException(Strings.error_project_import_too_large.strOr(context))
                }
                if (Files.isSymbolicLink(child.toPath())) {
                    throw IllegalArgumentException(Strings.error_project_import_symlink.strOr(context))
                }

                val canonicalChild = child.canonicalFile
                requireContainedPath(context, canonicalSourceRoot, canonicalChild)
                val destination = File(current.target, child.name).canonicalFile
                requireContainedPath(context, canonicalTargetRoot, destination)

                when {
                    canonicalChild.isDirectory -> {
                        val childDepth = current.depth + 1
                        if (childDepth > MAX_DIRECTORY_DEPTH) {
                            throw IllegalArgumentException(Strings.error_project_import_too_large.strOr(context))
                        }
                        if (!destination.mkdir()) {
                            throw IllegalStateException(Strings.toast_import_failed.strOr(context))
                        }
                        pendingDirectories.add(DirectoryCopyEntry(canonicalChild, destination, childDepth))
                    }
                    canonicalChild.isFile -> {
                        val declaredSize = canonicalChild.length()
                        if (declaredSize > archiveLimits.maxEntryBytes) {
                            throw IllegalArgumentException(Strings.error_project_import_too_large.strOr(context))
                        }
                        canonicalChild.inputStream().use { input ->
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(COPY_BUFFER_SIZE)
                                var fileBytes = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    fileBytes += read.toLong()
                                    copiedBytes += read.toLong()
                                    if (fileBytes > archiveLimits.maxEntryBytes ||
                                        copiedBytes > archiveLimits.maxExpandedBytes
                                    ) {
                                        throw IllegalArgumentException(
                                            Strings.error_project_import_too_large.strOr(context)
                                        )
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    else -> throw IllegalArgumentException(Strings.error_project_import_symlink.strOr(context))
                }
            }
        }
    }

    private fun requireContainedPath(context: Context, root: File, candidate: File) {
        val rootPath = root.path.trimEnd(File.separatorChar)
        require(candidate.path == rootPath || candidate.path.startsWith(rootPath + File.separator)) {
            Strings.error_project_import_symlink.strOr(context)
        }
    }

    suspend fun importArchive(
        context: Context,
        uri: Uri,
        projectsRoot: File,
        projectLocationManager: ProjectLocationManager
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistablePermission(
                context = context,
                uri = uri,
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            if ((!projectsRoot.exists() && !projectsRoot.mkdirs()) || !projectsRoot.isDirectory) {
                throw IllegalStateException(Strings.error_create_project_dir.strOr(context))
            }

            val displayName = queryDisplayName(context, uri)
                ?.takeIf { it.isNotBlank() }
                ?: "imported_project.zip"
            val tempArchive = copyArchiveToCache(context, uri, displayName)
            val stagingDir = File(projectsRoot, ".import-${UUID.randomUUID()}")
            var importedProjectDir: File? = null
            var importedMetadata: ProjectMetadata? = null

            if (!stagingDir.mkdirs()) {
                tempArchive.delete()
                throw IllegalStateException(Strings.toast_import_failed.strOr(context))
            }

            try {
                extractArchive(context, tempArchive, stagingDir, displayName)
                importedProjectDir = finalizeImportedProject(context, stagingDir, projectsRoot, displayName)
                importedMetadata = ensureUniqueImportedProjectIdentity(
                    projectDir = importedProjectDir,
                    projectsRoot = projectsRoot,
                    projectLocationManager = projectLocationManager,
                )
                projectLocationManager.registerProjectAndAwait(importedProjectDir)
                importedProjectDir
            } catch (error: Throwable) {
                importedProjectDir?.let { projectDir ->
                    importedMetadata?.id?.let { projectId ->
                        unregisterImportedProjectMapping(
                            projectLocationManager = projectLocationManager,
                            projectId = projectId,
                            projectDir = projectDir,
                            error = error,
                        )
                    }
                }
                importedProjectDir?.let { projectDir ->
                    if (projectDir.exists() && !projectDir.deleteRecursively()) {
                        error.addSuppressed(IllegalStateException("Failed to roll back imported project"))
                    }
                }
                throw error
            } finally {
                runCatching { tempArchive.delete() }
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }
        }
    }

    private fun copyArchiveToCache(context: Context, uri: Uri, displayName: String): File {
        val cacheDir = File(context.cacheDir, "project-imports").apply { mkdirs() }
        val tempArchive = File(
            cacheDir,
            "project-import-${UUID.randomUUID()}-${ProjectImportNamePolicy.cacheFileName(displayName)}"
        )

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempArchive.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var copiedBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copiedBytes += read.toLong()
                        if (copiedBytes > archiveLimits.maxArchiveBytes) {
                            throw IllegalArgumentException(Strings.error_project_import_too_large.strOr(context))
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalArgumentException(Strings.error_invalid_project_archive.strOr(context))
        } catch (error: Throwable) {
            runCatching { tempArchive.delete() }.onFailure(error::addSuppressed)
            throw error
        }

        return tempArchive
    }

    private fun extractArchive(
        context: Context,
        archiveFile: File,
        targetDir: File,
        displayName: String
    ) {
        when (detectArchiveKind(archiveFile, displayName)) {
            ArchiveKind.ZIP -> extractZip(context, archiveFile, targetDir)
            ArchiveKind.TAR -> TarExtractor.extract(
                archiveFile,
                targetDir,
                symlinkPolicy = TarExtractor.SymlinkPolicy.REJECT_LINKS,
                limits = archiveLimits,
            )
            null -> throw IllegalArgumentException(Strings.error_invalid_project_archive.strOr(context))
        }
    }

    private fun finalizeImportedProject(
        context: Context,
        stagingDir: File,
        projectsRoot: File,
        displayName: String
    ): File {
        val topLevelEntries = stagingDir.listFiles()
            ?.filterNot(::shouldIgnoreTopLevelEntry)
            .orEmpty()

        if (topLevelEntries.isEmpty()) {
            throw IllegalArgumentException(Strings.error_project_archive_empty.strOr(context))
        }

        val sourceDir = topLevelEntries.singleOrNull()
            ?.takeIf { it.isDirectory }
            ?: stagingDir
        val projectName = ProjectImportNamePolicy.projectName(
            if (sourceDir == stagingDir) stripArchiveSuffix(displayName) else sourceDir.name
        )
        val targetDir = File(projectsRoot, projectName)

        if (targetDir.exists()) {
            throw IllegalArgumentException(Strings.error_project_name_exists.strOr(context))
        }

        if (!sourceDir.renameTo(targetDir)) {
            throw IllegalStateException(Strings.toast_import_failed.strOr(context))
        }

        if (sourceDir != stagingDir && stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }

        return targetDir
    }

    private fun extractZip(context: Context, archiveFile: File, targetDir: File) {
        ZipArchiveValidator.validate(archiveFile, archiveLimits)
        val budget = ArchiveExtractionBudget(archiveLimits, archiveFile.length())
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val safeName = ArchivePathSafety.sanitizeRelativePath(entry.name)
                if (safeName.isBlank()) throw IllegalArgumentException("Invalid archive path: ${entry.name}")
                budget.beginEntry(safeName, entry.size)

                val entryFile = ArchivePathSafety.resolveEntryFile(targetDir, safeName, "project archive entry")
                if (entry.isDirectory) {
                    ensureDirectory(context, entryFile)
                } else {
                    entryFile.parentFile?.let { ensureDirectory(context, it) }
                    zip.getInputStream(entry).use { input ->
                        entryFile.outputStream().use { output ->
                            budget.copyEntry(input, output, safeName)
                        }
                    }
                }
            }
        }
    }

    private fun ensureDirectory(context: Context, directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IllegalStateException(Strings.toast_import_failed.strOr(context))
        }
    }

    private fun ensureUniqueImportedProjectIdentity(
        projectDir: File,
        projectsRoot: File,
        projectLocationManager: ProjectLocationManager,
    ): ProjectMetadata {
        val metadata = ProjectMetadataStore.ensure(projectDir, displayNameFallback = projectDir.name)
        val projectPath = projectDir.canonicalOrAbsolutePath()
        val registeredConflict = projectLocationManager.getProjectLocation(metadata.id)
            ?.takeIf { it.sourceRootPath != projectPath }
            ?.let { File(it.sourceRootPath).isDirectory }
            ?: false
        val managedConflict = projectsRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.canonicalOrAbsolutePath() != projectPath }
            .mapNotNull(ProjectMetadataStore::read)
            .any { it.id == metadata.id }
        if (!registeredConflict && !managedConflict) return metadata

        val reidentified = metadata.copy(id = ProjectIdentity.create())
        check(ProjectMetadataStore.write(projectDir, reidentified)) {
            "Failed to assign a unique imported project identity"
        }
        return reidentified
    }

    private suspend fun unregisterImportedProjectMapping(
        projectLocationManager: ProjectLocationManager,
        projectId: String,
        projectDir: File,
        error: Throwable,
    ) {
        val expectedPath = projectDir.canonicalOrAbsolutePath()
        val mappedToImportedProject = projectLocationManager.getProjectLocation(projectId)
            ?.sourceRootPath == expectedPath
        if (!mappedToImportedProject) return

        runCatching {
            projectLocationManager.unregisterProjectAndAwait(projectId, deleteWorkspace = false)
        }.onFailure(error::addSuppressed)
    }

    private fun detectArchiveKind(archiveFile: File, displayName: String): ArchiveKind? {
        val lowerName = displayName.lowercase()
        return when {
            lowerName.endsWith(".zip") -> ArchiveKind.ZIP
            tarSuffixes.any { lowerName.endsWith(it) } -> ArchiveKind.TAR
            isZipFile(archiveFile) -> ArchiveKind.ZIP
            else -> null
        }
    }

    private fun isZipFile(file: File): Boolean {
        val header = ByteArray(4)
        val bytesRead = file.inputStream().use { input ->
            input.read(header)
        }
        return bytesRead >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
    }

    private fun shouldIgnoreTopLevelEntry(file: File): Boolean = file.name == "__MACOSX" || file.name == ".DS_Store"

    private fun stripArchiveSuffix(fileName: String): String {
        val matchedSuffix = tarSuffixes
            .plus(".zip")
            .firstOrNull { fileName.endsWith(it, ignoreCase = true) }

        return when {
            matchedSuffix != null -> fileName.dropLast(matchedSuffix.length)
            else -> fileName.substringBeforeLast('.', fileName)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex < 0) return@use null
                    cursor.getString(columnIndex)
                }
        }.getOrNull()
    }

    private fun resolveDirectoryFromTreeUri(context: Context, uri: Uri): File? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .onFailure { error ->
                Timber.tag(TAG).w(
                    "Failed to resolve tree document id: provider=%s, error=%s",
                    uri.authority.orEmpty().ifBlank { "<unknown>" },
                    error::class.java.simpleName,
                )
            }
            .getOrNull()
            ?: return null

        val resolved = resolveDocumentIdToFile(context, documentId)
        Timber.tag(TAG).d(
            "Tree document resolution completed: provider=%s, resolved=%s",
            uri.authority.orEmpty().ifBlank { "<unknown>" },
            resolved != null,
        )
        return resolved
    }

    private fun resolveDocumentIdToFile(context: Context, documentId: String): File? {
        if (documentId.startsWith("raw:")) {
            return File(documentId.removePrefix("raw:"))
        }

        val parts = documentId.split(':', limit = 2)
        val volumeId = parts.firstOrNull().orEmpty()
        val relativePath = parts.getOrElse(1) { "" }
        val root = when (volumeId.lowercase()) {
            "primary" -> Environment.getExternalStorageDirectory()
            "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> resolveSecondaryStorageRoot(context, volumeId)
        } ?: return null

        return if (relativePath.isBlank()) {
            root
        } else {
            File(root, relativePath)
        }
    }

    private fun resolveSecondaryStorageRoot(context: Context, volumeId: String): File? = context.getExternalFilesDirs(null)
        .asSequence()
        .filterNotNull()
        .mapNotNull(::deriveStorageRoot)
        .firstOrNull { root ->
            root.name.equals(volumeId, ignoreCase = true) ||
                root.absolutePath.contains("/$volumeId/")
        }

    private fun deriveStorageRoot(appSpecificDir: File): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val absolutePath = appSpecificDir.absolutePath
        val index = absolutePath.indexOf(marker)
        if (index <= 0) return null
        return File(absolutePath.substring(0, index))
    }

    private fun takePersistablePermission(context: Context, uri: Uri, flags: Int) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { error ->
            Timber.tag(TAG).d(
                "Persistable permission not granted: provider=%s, error=%s",
                uri.authority.orEmpty().ifBlank { "<unknown>" },
                error::class.java.simpleName,
            )
        }
    }

    private enum class ArchiveKind {
        ZIP,
        TAR
    }

    private data class DirectoryCopyEntry(
        val source: File,
        val target: File,
        val depth: Int,
    )

    private fun File.canonicalOrAbsolutePath(): String = runCatching { canonicalPath }.getOrElse { absolutePath }
}
