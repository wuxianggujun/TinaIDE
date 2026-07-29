package com.wuxianggujun.tinaide.project

import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.util.UUID
import kotlinx.serialization.decodeFromString
import timber.log.Timber

object ProjectMetadataStore {
    private const val TAG = "ProjectMetadataStore"
    private const val PROJECT_METADATA_SCHEMA_CURRENT = 4

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
            val jsonContent = file.readText()
            val decoded = json.decodeFromString<ProjectMetadata>(jsonContent)
            if (decoded.id.isBlank()) {
                Timber.tag(TAG).w("Invalid metadata: id is blank for project ${projectRoot.name}")
                null
            } else {
                val normalized = normalizeMetadata(
                    migrateMissingSdlVersion(projectRoot, decoded)
                        .copy(schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT)
                )
                if (normalized != decoded) {
                    Timber.tag(TAG).i("Normalized project metadata for ${projectRoot.name}")
                    write(projectRoot, normalized)
                }
                normalized
            }
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to read metadata for project ${projectRoot.name}")
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
                Timber.tag(TAG).i("Added createdByIdeVersion for project ${projectRoot.name}")
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
                write(projectRoot, updated)
            }
            return updated
        }

        val meta = ProjectMetadata(
            schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT,
            id = UUID.randomUUID().toString(),
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
        write(projectRoot, meta)
        return meta
    }

    fun write(projectRoot: File, metadata: ProjectMetadata): Boolean {
        val metadataToPersist = normalizeMetadata(
            metadata.copy(schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT)
        )
        return runCatching {
            val dir = File(projectRoot, META_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, META_FILE_NAME)
            JsonSerializer.encodePrettyToFile(file, metadataToPersist)
            true
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to write metadata for project ${projectRoot.name}")
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
        val normalizedCMakeArgs = normalizePathEntries(cmakeArgs)

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

    private fun normalizeMetadata(metadata: ProjectMetadata): ProjectMetadata {
        val normalizedSdlVersion = metadata.getSdlVersionOrNull()
        val normalizedApkExportType = metadata.apkExportType.takeUnless {
            normalizedSdlVersion == ProjectSdlVersion.SDL2 &&
                (it == ProjectApkExportType.SDL3 || it == ProjectApkExportType.NATIVE_ACTIVITY)
        }
        return metadata.copy(
            schemaVersion = PROJECT_METADATA_SCHEMA_CURRENT,
            cppStandard = metadata.normalizedCppStandardValue(),
            nativeApiLevel = normalizeNativeApiLevel(metadata.nativeApiLevel),
            nativeIncludeDirs = normalizePathEntries(metadata.nativeIncludeDirs),
            nativeLibraryDirs = normalizePathEntries(metadata.nativeLibraryDirs),
            nativeRuntimeDirs = normalizePathEntries(metadata.nativeRuntimeDirs),
            nativeCFlags = normalizeFlagValue(metadata.nativeCFlags),
            nativeCppFlags = normalizeFlagValue(metadata.nativeCppFlags),
            nativeLdFlags = normalizeFlagValue(metadata.nativeLdFlags),
            nativeLdLibs = normalizeFlagValue(metadata.nativeLdLibs),
            nativeCMakeArgs = normalizePathEntries(metadata.nativeCMakeArgs),
            defaultRunTargetName = normalizeTargetName(metadata.defaultRunTargetName),
            defaultSdlTargetName = normalizeTargetName(metadata.defaultSdlTargetName),
            apkExportType = normalizedApkExportType,
            sdlVersion = normalizedSdlVersion,
        )
    }

    private fun migrateMissingSdlVersion(
        projectRoot: File,
        metadata: ProjectMetadata,
    ): ProjectMetadata {
        if (metadata.sdlVersion != null || metadata.apkExportType != ProjectApkExportType.SDL3) {
            return metadata
        }

        // Older detection treated the SDL2/SDL3 shared SDLActivity class as SDL3.
        // Re-scan version-specific build/source markers before preserving the legacy fallback.
        return when (ProjectApkExportSupportResolver.detectSdlVersion(projectRoot)) {
            ProjectSdlVersion.SDL2 -> metadata.copy(
                apkExportType = null,
                sdlVersion = ProjectSdlVersion.SDL2,
            )
            ProjectSdlVersion.SDL3,
            null,
            -> metadata.copy(sdlVersion = ProjectSdlVersion.SDL3)
        }
    }

    private fun normalizeNativeApiLevel(nativeApiLevel: Int?): Int? = nativeApiLevel?.takeIf { it in 21..35 }

    private fun normalizePathEntries(paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        return paths.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun normalizeFlagValue(value: String): String {
        if (value.isBlank()) return ""
        return value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    private fun normalizeTargetName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
