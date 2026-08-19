package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionBudget
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateMetadata
import com.wuxianggujun.tinaide.project.ProjectTemplateArchivePolicy
import com.wuxianggujun.tinaide.project.ProjectTemplateMetadataReader
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class UserProjectTemplateItem(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val metadata: ProjectTemplateMetadata? = null,
)

internal data class UserProjectTemplateMetadataUpdate(
    val name: String? = null,
    val description: String? = null,
    val author: String? = null,
    val buildSystem: ProjectBuildSystem? = null,
    val primaryLanguage: ProjectLanguage? = null,
    val isNdkTemplate: Boolean = false,
    val variables: Map<String, String> = emptyMap(),
)

internal enum class UserProjectTemplateFailure {
    NOT_ZIP,
    INVALID_NAME,
    CANNOT_READ,
    INVALID_ZIP,
    IMPORT_FAILED,
    RENAME_FAILED,
    EXPORT_FAILED,
    DELETE_FAILED,
    METADATA_UPDATE_FAILED,
    UNSAFE_PATH,
}

internal enum class UserProjectTemplateVariableInputError {
    MISSING_SEPARATOR,
    INVALID_NAME,
    EMPTY_VALUE,
}

internal class UserProjectTemplateException(
    val failure: UserProjectTemplateFailure,
    cause: Throwable? = null,
) : Exception(cause?.message, cause)

internal object UserProjectTemplateManager {
    private val invalidFileNameChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
    private val whitespace = Regex("\\s+")
    private val variableName = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val metadataJson = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    fun listTemplates(templatesDir: File): List<UserProjectTemplateItem> = templatesDir
        .takeIf { it.isDirectory }
        ?.listFiles { file -> file.isFile && isZipFileName(file.name) }
        ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        ?.map(::toItem)
        .orEmpty()

    suspend fun importTemplateFromUri(
        context: Context,
        uri: Uri,
    ): Result<UserProjectTemplateItem> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceName = queryDisplayName(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
            val input = context.contentResolver.openInputStream(uri)
                ?: throw UserProjectTemplateException(UserProjectTemplateFailure.CANNOT_READ)
            input.use {
                importTemplate(
                    templatesDir = ProjectPaths.getUserProjectTemplatesRoot(context),
                    sourceName = sourceName,
                    input = it,
                )
            }
        }
    }

    fun importTemplate(
        templatesDir: File,
        sourceName: String?,
        input: InputStream,
    ): UserProjectTemplateItem {
        val safeName = sanitizeTemplateFileName(sourceName)
        if (!isZipFileName(sourceName) || !isZipFileName(safeName)) {
            throw UserProjectTemplateException(UserProjectTemplateFailure.NOT_ZIP)
        }

        val root = ProjectPaths.ensureDir(templatesDir).canonicalFile
        val target = resolveUniqueTarget(root, safeName)
        val tempFile = File(root, ".${target.name}.${System.nanoTime()}.tmp")

        return try {
            tempFile.outputStream().use { output -> copyArchiveWithLimit(input, output) }
            runCatching { ProjectTemplateArchivePolicy.validate(tempFile) }
                .onFailure { throw UserProjectTemplateException(UserProjectTemplateFailure.INVALID_ZIP, it) }

            if (!tempFile.renameTo(target)) {
                throw UserProjectTemplateException(UserProjectTemplateFailure.IMPORT_FAILED)
            }
            toItem(target)
        } catch (error: UserProjectTemplateException) {
            tempFile.delete()
            throw error
        } catch (error: Throwable) {
            tempFile.delete()
            throw UserProjectTemplateException(UserProjectTemplateFailure.IMPORT_FAILED, error)
        }
    }

    fun renameTemplate(
        templatesDir: File,
        currentName: String,
        desiredName: String,
    ): UserProjectTemplateItem {
        if (desiredName.isBlank()) {
            throw UserProjectTemplateException(UserProjectTemplateFailure.INVALID_NAME)
        }

        return try {
            val root = ProjectPaths.ensureDir(templatesDir).canonicalFile
            val source = resolveExistingTemplate(root, currentName)
            val safeName = sanitizeTemplateFileName(desiredName)
            if (!isZipFileName(safeName)) {
                throw UserProjectTemplateException(UserProjectTemplateFailure.INVALID_NAME)
            }

            val target = File(root, safeName).canonicalFile
            if (!target.isInside(root) || target == root || target.isDirectory) {
                throw UserProjectTemplateException(UserProjectTemplateFailure.UNSAFE_PATH)
            }
            if (target == source) {
                return toItem(source)
            }
            if (target.exists() || !source.renameTo(target)) {
                throw UserProjectTemplateException(UserProjectTemplateFailure.RENAME_FAILED)
            }
            toItem(target)
        } catch (error: UserProjectTemplateException) {
            throw error
        } catch (error: Throwable) {
            throw UserProjectTemplateException(UserProjectTemplateFailure.RENAME_FAILED, error)
        }
    }

    fun exportTemplate(
        templatesDir: File,
        templateName: String,
        output: OutputStream,
    ): Boolean = try {
        val root = ProjectPaths.ensureDir(templatesDir).canonicalFile
        val source = resolveExistingTemplate(root, templateName)
        source.inputStream().use { input ->
            input.copyTo(output)
        }
        true
    } catch (error: UserProjectTemplateException) {
        throw error
    } catch (error: Throwable) {
        throw UserProjectTemplateException(UserProjectTemplateFailure.EXPORT_FAILED, error)
    }

    fun deleteTemplate(templatesDir: File, templateName: String): Boolean = try {
        val root = ProjectPaths.ensureDir(templatesDir).canonicalFile
        val target = resolveExistingTemplate(root, templateName)
        if (!target.delete()) {
            throw UserProjectTemplateException(UserProjectTemplateFailure.DELETE_FAILED)
        }
        true
    } catch (error: UserProjectTemplateException) {
        throw error
    } catch (error: Throwable) {
        throw UserProjectTemplateException(UserProjectTemplateFailure.DELETE_FAILED, error)
    }

    fun updateTemplateMetadata(
        templatesDir: File,
        templateName: String,
        metadata: UserProjectTemplateMetadataUpdate,
    ): UserProjectTemplateItem = try {
        val root = ProjectPaths.ensureDir(templatesDir).canonicalFile
        val source = resolveExistingTemplate(root, templateName)
        val tempFile = File.createTempFile(".${source.nameWithoutExtension}-metadata-", ".zip", root)
        try {
            rewriteTemplateMetadataZip(
                source = source,
                target = tempFile,
                metadata = metadata,
            )
            replaceTemplateFile(
                source = source,
                replacement = tempFile,
            )
            toItem(source)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    } catch (error: UserProjectTemplateException) {
        throw error
    } catch (error: Throwable) {
        throw UserProjectTemplateException(UserProjectTemplateFailure.METADATA_UPDATE_FAILED, error)
    }

    fun buildTemplateMetadataPreview(metadata: UserProjectTemplateMetadataUpdate): String = metadata.toMetadataBytes()
        ?.toString(Charsets.UTF_8)
        ?.trimEnd()
        ?: "{}"

    fun formatVariableDefaults(variables: Map<String, String>): String = variables.normalizedVariables()
        .entries
        .joinToString(separator = "\n") { (key, value) -> "$key=$value" }

    fun validateVariableDefaultsInput(input: String): UserProjectTemplateVariableInputError? {
        input.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                if (!line.contains('=')) {
                    return UserProjectTemplateVariableInputError.MISSING_SEPARATOR
                }
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                if (!variableName.matches(key)) {
                    return UserProjectTemplateVariableInputError.INVALID_NAME
                }
                if (value.isBlank()) {
                    return UserProjectTemplateVariableInputError.EMPTY_VALUE
                }
            }
        return null
    }

    fun parseVariableDefaults(input: String): Map<String, String> = input.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains('=') }
        .map { line ->
            line.substringBefore('=').trim() to line.substringAfter('=').trim()
        }
        .filter { (key, value) -> variableName.matches(key) && value.isNotBlank() }
        .toMap()

    fun sanitizeTemplateFileName(sourceName: String?): String {
        val leafName = sourceName
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.trim()
            .orEmpty()

        val baseName = leafName
            .removeZipExtension()
            .replace(invalidFileNameChars, "-")
            .replace(whitespace, " ")
            .trim(' ', '.', '-', '_')
            .ifBlank { "template" }

        return "$baseName.zip"
    }

    fun resolveUniqueTarget(templatesDir: File, sourceName: String?): File {
        val safeName = sanitizeTemplateFileName(sourceName)
        val baseName = safeName.removeZipExtension()
        var candidate = File(templatesDir, safeName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(templatesDir, "$baseName-$index.zip")
            index++
        }
        return candidate
    }

    fun isZipFileName(fileName: String?): Boolean = fileName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.equals("zip", ignoreCase = true) == true

    fun formatTemplateSize(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun toItem(file: File): UserProjectTemplateItem = UserProjectTemplateItem(
        file = file,
        name = file.name,
        sizeBytes = file.length(),
        lastModifiedMillis = file.lastModified(),
        metadata = ProjectTemplateMetadataReader.readFromZip(file),
    )

    private fun rewriteTemplateMetadataZip(
        source: File,
        target: File,
        metadata: UserProjectTemplateMetadataUpdate,
    ) {
        ProjectTemplateArchivePolicy.validate(source)
        val metadataBytes = metadata.toMetadataBytes()
        val copiedEntryNames = linkedSetOf<String>()
        val budget = ArchiveExtractionBudget(ProjectTemplateArchivePolicy.limits)

        ZipInputStream(source.inputStream().buffered()).use { input ->
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    budget.beginEntry(entryName, entry.size)
                    if (!ProjectTemplateMetadataReader.isMetadataEntry(entryName) &&
                        copiedEntryNames.add(entryName)
                    ) {
                        val nextEntry = ZipEntry(entryName)
                        if (entry.time >= 0L) {
                            nextEntry.time = entry.time
                        }
                        output.putNextEntry(nextEntry)
                        if (!entry.isDirectory) {
                            budget.copyEntry(input, output, entryName)
                        }
                        output.closeEntry()
                    } else if (!entry.isDirectory) {
                        budget.copyEntry(input, DISCARDING_OUTPUT, entryName)
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }

                if (metadataBytes != null) {
                    output.putNextEntry(ZipEntry(ProjectTemplateMetadataReader.METADATA_FILE_NAME))
                    output.write(metadataBytes)
                    output.closeEntry()
                }
            }
        }

        runCatching { ProjectTemplateArchivePolicy.validate(target) }
            .onFailure { throw UserProjectTemplateException(UserProjectTemplateFailure.METADATA_UPDATE_FAILED, it) }
    }

    private fun copyArchiveWithLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count.toLong()
            if (copied > ProjectTemplateArchivePolicy.limits.maxArchiveBytes) {
                throw UserProjectTemplateException(UserProjectTemplateFailure.INVALID_ZIP)
            }
            output.write(buffer, 0, count)
        }
    }

    private fun replaceTemplateFile(source: File, replacement: File) {
        val backup = File(
            source.parentFile,
            ".${source.name}.${System.nanoTime()}.metadata.bak"
        )
        if (!source.renameTo(backup)) {
            throw UserProjectTemplateException(UserProjectTemplateFailure.METADATA_UPDATE_FAILED)
        }

        try {
            if (!replacement.renameTo(source)) {
                throw UserProjectTemplateException(UserProjectTemplateFailure.METADATA_UPDATE_FAILED)
            }
            backup.delete()
        } catch (error: Throwable) {
            if (source.exists()) {
                runCatching {
                    check(source.delete()) { "Failed to remove incomplete template replacement" }
                }.onFailure(error::addSuppressed)
            }
            if (!source.exists()) {
                runCatching {
                    check(backup.renameTo(source)) { "Failed to restore template metadata backup" }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun UserProjectTemplateMetadataUpdate.toMetadataBytes(): ByteArray? {
        val content = toJsonObject()
        if (content.isEmpty()) return null
        val jsonText = metadataJson.encodeToString(JsonObject.serializer(), content)
        return (jsonText + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun UserProjectTemplateMetadataUpdate.toJsonObject(): JsonObject = buildJsonObject {
        name.trimToNull()?.let { put("name", it) }
        description.trimToNull()?.let { put("description", it) }
        author.trimToNull()?.let { put("author", it) }
        buildSystem?.takeUnless { it == ProjectBuildSystem.UNKNOWN }?.let {
            put("buildSystem", it.toMetadataValue())
        }
        primaryLanguage?.takeUnless { it == ProjectLanguage.UNKNOWN }?.let {
            put("primaryLanguage", it.name)
        }
        if (isNdkTemplate) {
            put("ndkTemplate", true)
        }
        val normalizedVariables = variables.normalizedVariables()
        if (normalizedVariables.isNotEmpty()) {
            put(
                "variables",
                buildJsonObject {
                    normalizedVariables.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            )
        }
    }

    private fun ProjectBuildSystem.toMetadataValue(): String = when (this) {
        ProjectBuildSystem.SINGLE_FILE -> "single_file"
        ProjectBuildSystem.CMAKE -> "cmake"
        ProjectBuildSystem.MAKE -> "make"
        ProjectBuildSystem.PLUGIN -> "plugin"
        ProjectBuildSystem.UNKNOWN -> "unknown"
    }

    private fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun Map<String, String>.normalizedVariables(): Map<String, String> = entries
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim().takeIf { it.isNotBlank() }
            val normalizedValue = value.trim().takeIf { it.isNotBlank() }
            if (normalizedKey != null && normalizedValue != null) {
                normalizedKey to normalizedValue
            } else {
                null
            }
        }
        .toMap()

    private fun resolveExistingTemplate(root: File, templateName: String): File {
        if (!isZipFileName(templateName)) {
            throw UserProjectTemplateException(UserProjectTemplateFailure.UNSAFE_PATH)
        }
        val target = File(root, templateName).canonicalFile
        if (!target.isInside(root) || !target.isFile || !isZipFileName(target.name)) {
            throw UserProjectTemplateException(UserProjectTemplateFailure.UNSAFE_PATH)
        }
        return target
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
        ?.takeUnless { it.isBlank() }

    private fun String.removeZipExtension(): String = if (endsWith(".zip", ignoreCase = true)) {
        dropLast(4)
    } else {
        this
    }

    private fun File.isInside(root: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar)
        val targetPath = path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    private val DISCARDING_OUTPUT = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }
}
