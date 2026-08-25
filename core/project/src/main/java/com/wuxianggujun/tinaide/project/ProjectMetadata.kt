package com.wuxianggujun.tinaide.project

import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber

object ProjectMetadataStore {
    private const val TAG = "ProjectMetadataStore"
    private const val PROJECT_METADATA_SCHEMA_CURRENT = 5
    private const val MAX_METADATA_BYTES = 256 * 1024
    private const val MAX_DISPLAY_NAME_CHARS = 256
    private const val MAX_VERSION_CHARS = 64
    private const val MAX_ENUM_VALUE_CHARS = 64
    private const val MAX_PATH_ENTRIES = 256
    private const val MAX_PATH_ENTRY_CHARS = 4_096
    private const val MAX_BUILD_FLAGS_CHARS = 16 * 1024
    private const val MAX_TARGET_NAME_CHARS = 256

    private val json = JsonSerializer.pretty

    private const val META_DIR_NAME = ".tinaide"
    private const val META_FILE_NAME = "project.json"

    /** 当前 IDE 版本，由 Application 初始化时设置 */
    var currentIdeVersion: String = "unknown"

    fun getMetaFile(projectRoot: File): File = File(File(projectRoot, META_DIR_NAME), META_FILE_NAME)

    /**
     * 读取项目元数据，如果需要会自动补全缺失字段
     */
    fun read(projectRoot: File): ProjectMetadata? {
        val file = getMetaFile(projectRoot)
        if (!file.exists()) return null

        return runCatching {
            val jsonContent = readMetadataText(file)
            val decoded = json.decodeFromString<ProjectMetadata>(jsonContent)
            val normalized = normalizeMetadata(
                migrateGraphicalRuntimeMetadata(projectRoot, decoded)
                    .copy(schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT),
                displayNameFallback = projectRoot.name,
            )
            if (normalized != decoded) {
                Timber.tag(TAG).i("Normalized project metadata")
                check(write(projectRoot, normalized)) { "Failed to persist normalized project metadata" }
            }
            normalized
        }.onFailure { error ->
            Timber.tag(TAG).e("Failed to read project metadata: error=%s", error.javaClass.simpleName)
        }.getOrNull()
    }

    /**
     * 确保项目有元数据，如果没有则创建，如果缺少字段则补全
     */
    fun ensure(
        projectRoot: File,
        displayNameFallback: String = projectRoot.name,
        buildSystem: ProjectBuildSystem? = null,
        cppStandard: CppStandard? = null,
        primaryLanguage: ProjectLanguage? = null,
        apkExportType: ProjectApkExportType? = null,
        sdlVersion: ProjectSdlVersion? = null,
        nativeApiLevel: Int? = null,
        defaultRunTargetName: String? = null,
        defaultSdlTargetName: String? = null
    ): ProjectMetadata {
        val normalizedNativeApiLevel = normalizeNativeApiLevel(nativeApiLevel)
        val normalizedDefaultRunTargetName = normalizeTargetName(defaultRunTargetName)
        val normalizedDefaultSdlTargetName = normalizeTargetName(defaultSdlTargetName)
        read(projectRoot)?.let { existing ->
            var needsUpdate = false
            var updated = existing

            if (existing.createdByIdeVersion == null) {
                updated = updated.copy(createdByIdeVersion = currentIdeVersion)
                needsUpdate = true
                Timber.tag(TAG).i("Added createdByIdeVersion to project metadata")
            }

            if (existing.lastOpenedIdeVersion != currentIdeVersion) {
                updated = updated.copy(
                    lastOpenedIdeVersion = currentIdeVersion,
                    lastOpenedAt = System.currentTimeMillis()
                )
                needsUpdate = true
            }

            if (normalizedNativeApiLevel != null && existing.nativeApiLevel != normalizedNativeApiLevel) {
                updated = updated.copy(nativeApiLevel = normalizedNativeApiLevel)
                needsUpdate = true
            }

            if (apkExportType != null && existing.apkExportType != apkExportType) {
                updated = updated.copy(apkExportType = apkExportType)
                needsUpdate = true
            }

            if (sdlVersion != null && existing.sdlVersion != sdlVersion) {
                updated = updated.copy(sdlVersion = sdlVersion)
                needsUpdate = true
            }

            if (
                normalizedDefaultRunTargetName != null &&
                existing.defaultRunTargetName != normalizedDefaultRunTargetName
            ) {
                updated = updated.copy(defaultRunTargetName = normalizedDefaultRunTargetName)
                needsUpdate = true
            }

            if (
                normalizedDefaultSdlTargetName != null &&
                existing.defaultSdlTargetName != normalizedDefaultSdlTargetName
            ) {
                updated = updated.copy(defaultSdlTargetName = normalizedDefaultSdlTargetName)
                needsUpdate = true
            }

            if (needsUpdate) {
                check(write(projectRoot, updated)) { "Failed to persist updated project metadata" }
            }
            return updated
        }

        val meta = ProjectMetadata(
            schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT,
            id = ProjectIdentity.create(),
            displayName = displayNameFallback,
            createdAt = System.currentTimeMillis(),
            createdByIdeVersion = currentIdeVersion,
            buildSystem = buildSystem,
            cppStandard = cppStandard?.name,
            primaryLanguage = primaryLanguage?.name,
            apkExportType = apkExportType,
            sdlVersion = sdlVersion,
            lastOpenedIdeVersion = currentIdeVersion,
            lastOpenedAt = System.currentTimeMillis(),
            nativeApiLevel = normalizedNativeApiLevel,
            defaultRunTargetName = normalizedDefaultRunTargetName,
            defaultSdlTargetName = normalizedDefaultSdlTargetName
        )
        check(write(projectRoot, meta)) { "Failed to create project metadata" }
        return meta
    }

    fun write(projectRoot: File, metadata: ProjectMetadata): Boolean {
        val metadataToPersist = normalizeMetadata(
            metadata = metadata.copy(schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT),
            displayNameFallback = projectRoot.name,
        )
        return runCatching {
            val dir = File(projectRoot, META_DIR_NAME)
            check((dir.exists() || dir.mkdirs()) && dir.isDirectory) {
                "Failed to create project metadata directory"
            }
            val file = File(dir, META_FILE_NAME)
            writeMetadataAtomically(file, metadataToPersist)
            true
        }.onFailure { error ->
            Timber.tag(TAG).e("Failed to write project metadata: error=%s", error.javaClass.simpleName)
        }.getOrElse { false }
    }

    fun updateBuildSystem(projectRoot: File, buildSystem: ProjectBuildSystem): Boolean {
        val existing = read(projectRoot) ?: return false
        val updated = existing.copy(buildSystem = buildSystem)
        return write(projectRoot, updated)
    }

    fun updateCppStandard(projectRoot: File, cppStandard: CppStandard): Boolean {
        val existing = read(projectRoot) ?: return false
        val updated = existing.copy(cppStandard = cppStandard.name)
        return write(projectRoot, updated)
    }

    fun updatePrimaryLanguage(projectRoot: File, language: ProjectLanguage): Boolean {
        val existing = read(projectRoot) ?: return false
        val updated = existing.copy(primaryLanguage = language.name)
        return write(projectRoot, updated)
    }

    fun updateApkExportType(projectRoot: File, apkExportType: ProjectApkExportType?): Boolean {
        val existing = read(projectRoot) ?: return false
        if (existing.apkExportType == apkExportType) return true
        return write(projectRoot, existing.copy(apkExportType = apkExportType))
    }

    fun updateSdlVersion(projectRoot: File, sdlVersion: ProjectSdlVersion?): Boolean {
        val existing = read(projectRoot) ?: return false
        if (existing.sdlVersion == sdlVersion) return true
        return write(projectRoot, existing.copy(sdlVersion = sdlVersion))
    }

    fun updateLastOpened(projectRoot: File): Boolean {
        val existing = read(projectRoot) ?: return false
        val updated = existing.copy(
            lastOpenedIdeVersion = currentIdeVersion,
            lastOpenedAt = System.currentTimeMillis()
        )
        return write(projectRoot, updated)
    }

    fun updateNativeApiLevel(projectRoot: File, nativeApiLevel: Int?): Boolean {
        val existing = read(projectRoot) ?: return false
        val normalized = normalizeNativeApiLevel(nativeApiLevel)
        if (existing.nativeApiLevel == normalized) return true
        return write(projectRoot, existing.copy(nativeApiLevel = normalized))
    }

    fun updateNativeDependencyPaths(
        projectRoot: File,
        includeDirs: List<String>,
        libraryDirs: List<String>,
        runtimeDirs: List<String>
    ): Boolean {
        val existing = read(projectRoot) ?: ensure(projectRoot)
        val normalizedIncludeDirs = normalizePathEntries(includeDirs)
        val normalizedLibraryDirs = normalizePathEntries(libraryDirs)
        val normalizedRuntimeDirs = normalizePathEntries(runtimeDirs)

        val unchanged = existing.normalizedNativeIncludeDirs() == normalizedIncludeDirs &&
            existing.normalizedNativeLibraryDirs() == normalizedLibraryDirs &&
            existing.normalizedNativeRuntimeDirs() == normalizedRuntimeDirs
        if (unchanged) return true

        return write(
            projectRoot,
            existing.copy(
                nativeIncludeDirs = normalizedIncludeDirs,
                nativeLibraryDirs = normalizedLibraryDirs,
                nativeRuntimeDirs = normalizedRuntimeDirs
            )
        )
    }

    fun updateNativeBuildFlags(
        projectRoot: File,
        cFlags: String,
        cppFlags: String,
        ldFlags: String,
        ldLibs: String,
        cmakeArgs: List<String>
    ): Boolean {
        val existing = read(projectRoot) ?: ensure(projectRoot)
        val normalizedCFlags = normalizeFlagValue(cFlags)
        val normalizedCppFlags = normalizeFlagValue(cppFlags)
        val normalizedLdFlags = normalizeFlagValue(ldFlags)
        val normalizedLdLibs = normalizeFlagValue(ldLibs)
        val normalizedCMakeArgs = ProjectCMakeArgumentPolicy.sanitize(cmakeArgs)

        val unchanged = existing.normalizedNativeCFlags() == normalizedCFlags &&
            existing.normalizedNativeCppFlags() == normalizedCppFlags &&
            existing.normalizedNativeLdFlags() == normalizedLdFlags &&
            existing.normalizedNativeLdLibs() == normalizedLdLibs &&
            existing.normalizedNativeCMakeArgs() == normalizedCMakeArgs
        if (unchanged) return true

        return write(
            projectRoot,
            existing.copy(
                nativeCFlags = normalizedCFlags,
                nativeCppFlags = normalizedCppFlags,
                nativeLdFlags = normalizedLdFlags,
                nativeLdLibs = normalizedLdLibs,
                nativeCMakeArgs = normalizedCMakeArgs
            )
        )
    }

    private fun normalizeMetadata(
        metadata: ProjectMetadata,
        displayNameFallback: String,
    ): ProjectMetadata {
        val normalizedId = metadata.id.takeIf(ProjectIdentity::isValid) ?: ProjectIdentity.create()
        val normalizedSdlVersion = metadata.getSdlVersionOrNull()
        val normalizedApkExportType = metadata.apkExportType.takeUnless {
            normalizedSdlVersion == ProjectSdlVersion.SDL2 &&
                (it == ProjectApkExportType.SDL3 || it == ProjectApkExportType.NATIVE_ACTIVITY)
        }
        return metadata.copy(
            schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT,
            id = normalizedId,
            displayName = normalizeDisplayName(metadata.displayName, displayNameFallback, normalizedId),
            createdByIdeVersion = normalizeOptionalValue(metadata.createdByIdeVersion, MAX_VERSION_CHARS),
            cppStandard = normalizeOptionalValue(metadata.normalizedCppStandardValue(), MAX_ENUM_VALUE_CHARS),
            primaryLanguage = normalizeOptionalValue(metadata.primaryLanguage, MAX_ENUM_VALUE_CHARS),
            lastOpenedIdeVersion = normalizeOptionalValue(metadata.lastOpenedIdeVersion, MAX_VERSION_CHARS),
            nativeApiLevel = normalizeNativeApiLevel(metadata.nativeApiLevel),
            nativeIncludeDirs = normalizePathEntries(metadata.nativeIncludeDirs),
            nativeLibraryDirs = normalizePathEntries(metadata.nativeLibraryDirs),
            nativeRuntimeDirs = normalizePathEntries(metadata.nativeRuntimeDirs),
            nativeCFlags = normalizeFlagValue(metadata.nativeCFlags),
            nativeCppFlags = normalizeFlagValue(metadata.nativeCppFlags),
            nativeLdFlags = normalizeFlagValue(metadata.nativeLdFlags),
            nativeLdLibs = normalizeFlagValue(metadata.nativeLdLibs),
            nativeCMakeArgs = metadata.normalizedNativeCMakeArgs(),
            defaultRunTargetName = normalizeTargetName(metadata.defaultRunTargetName),
            defaultSdlTargetName = normalizeTargetName(metadata.defaultSdlTargetName),
            apkExportType = normalizedApkExportType,
            sdlVersion = normalizedSdlVersion,
        )
    }

    private fun migrateGraphicalRuntimeMetadata(
        projectRoot: File,
        metadata: ProjectMetadata,
    ): ProjectMetadata {
        if (
            metadata.schemaVersion >= PROJECT_METADATA_SCHEMA_CURRENT ||
            metadata.apkExportType != ProjectApkExportType.SDL3
        ) {
            return metadata
        }

        // Older versions could persist raylib or SDL2 projects as SDL3. Re-scan once while
        // crossing the schema boundary, including metadata that already contains sdlVersion=SDL3.
        val detected = ProjectApkExportSupportResolver.detectSupport(projectRoot)
        return when {
            detected.sdlVersion == ProjectSdlVersion.SDL2 -> metadata.copy(
                apkExportType = null,
                sdlVersion = ProjectSdlVersion.SDL2,
            )
            detected.apkExportType == ProjectApkExportType.NATIVE_ACTIVITY &&
                detected.sdlVersion == null -> metadata.copy(
                apkExportType = ProjectApkExportType.NATIVE_ACTIVITY,
                sdlVersion = null,
            )
            metadata.sdlVersion == null -> metadata.copy(sdlVersion = ProjectSdlVersion.SDL3)
            else -> metadata
        }
    }

    private fun normalizeNativeApiLevel(nativeApiLevel: Int?): Int? = nativeApiLevel?.takeIf { it in 21..35 }

    private fun normalizePathEntries(paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        return paths.asSequence()
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    it.length <= MAX_PATH_ENTRY_CHARS &&
                    it.none(Char::isISOControl)
            }
            .distinct()
            .take(MAX_PATH_ENTRIES)
            .toList()
    }

    private fun normalizeFlagValue(value: String): String {
        if (value.isBlank()) return ""
        val normalized = value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return normalized.takeIf {
            it.length <= MAX_BUILD_FLAGS_CHARS && it.none(Char::isISOControl)
        }.orEmpty()
    }

    private fun normalizeTargetName(value: String?): String? = value
        ?.trim()
        ?.takeIf {
            it.isNotBlank() &&
                it.length <= MAX_TARGET_NAME_CHARS &&
                it.none(Char::isISOControl)
        }

    private fun normalizeDisplayName(value: String, fallback: String, projectId: String): String {
        val normalizedValue = normalizeSingleLineValue(value, MAX_DISPLAY_NAME_CHARS)
        if (normalizedValue.isNotEmpty()) return normalizedValue
        return normalizeSingleLineValue(fallback, MAX_DISPLAY_NAME_CHARS).ifEmpty { projectId }
    }

    private fun normalizeOptionalValue(value: String?, maxChars: Int): String? = value
        ?.let { normalizeSingleLineValue(it, maxChars) }
        ?.takeIf(String::isNotEmpty)

    private fun normalizeSingleLineValue(value: String, maxChars: Int): String = value
        .asSequence()
        .map { char -> if (char.isISOControl()) ' ' else char }
        .joinToString(separator = "")
        .trim()
        .replace(Regex("\\s+"), " ")
        .takeWithoutSplittingSurrogatePair(maxChars)
        .trim()

    private fun String.takeWithoutSplittingSurrogatePair(maxChars: Int): String {
        if (length <= maxChars) return this
        val endIndex = if (this[maxChars - 1].isHighSurrogate() && this[maxChars].isLowSurrogate()) {
            maxChars - 1
        } else {
            maxChars
        }
        return substring(0, endIndex)
    }

    private fun readMetadataText(file: File): String {
        require(file.length() <= MAX_METADATA_BYTES) { "Project metadata exceeds the size limit" }
        val bytes = file.inputStream().use { input ->
            ByteArrayOutputStream(minOf(file.length(), MAX_METADATA_BYTES.toLong()).toInt()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_METADATA_BYTES) { "Project metadata exceeds the size limit" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun writeMetadataAtomically(file: File, metadata: ProjectMetadata) {
        val encoded = json.encodeToString(metadata).toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_METADATA_BYTES) { "Project metadata exceeds the size limit" }

        val tempFile = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            tempFile.outputStream().use { output ->
                output.write(encoded)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.delete()
        }
    }
}
