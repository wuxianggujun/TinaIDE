package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import timber.log.Timber

/**
 * Android Sysroot / NDK runtime manager.
 *
 * Built-in sysroots are declared by `assets/android-sysroot/profiles.json` and installed
 * into `files/android-sysroots/<profileId>/`. If the active/default profile is missing,
 * callers can reinstall it from the bundled profile asset.
 */
class AndroidSysrootManager private constructor(
    private val context: Context,
    private val builtinManifestProvider: () -> BuiltinSysrootProfileManifest?
) {

    constructor(context: Context) : this(
        context = context,
        builtinManifestProvider = { readBuiltinManifestFromAssets(context.applicationContext) }
    )

    internal constructor(
        context: Context,
        builtinManifest: BuiltinSysrootProfileManifest
    ) : this(
        context = context,
        builtinManifestProvider = { builtinManifest }
    )

    companion object {
        private const val TAG = "SysrootManager"

        private const val SYSROOT_ASSET_MANIFEST = "android-sysroot/profiles.json"

        private const val SYSROOT_DIR_NAME = "android-sysroot"
        private const val CUSTOM_PROFILE_PREFIX = "custom"
        private const val API_LEVEL_HEADER_PATH = "usr/include/android/api-level.h"
        private const val CXX_SHARED_LIBRARY_NAME = "libc++_shared.so"
        private const val CXX_STATIC_LIBRARY_NAME = "libc++_static.a"
        private const val CXX_ABI_LIBRARY_NAME = "libc++abi.a"
        private const val CXX_API_LINKER_SCRIPT_NAME = "libc++.a"

        private fun readBuiltinManifestFromAssets(context: Context): BuiltinSysrootProfileManifest? = try {
            context.assets.open(SYSROOT_ASSET_MANIFEST).use { input ->
                JsonSerializer.pretty.decodeFromString<BuiltinSysrootProfileManifest>(
                    input.bufferedReader(Charsets.UTF_8).readText()
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Builtin sysroot manifest not found or unreadable")
            null
        }

        enum class Arch(val triple: String) {
            ARM64("aarch64-linux-android"),
            X86_64("x86_64-linux-android");

            companion object {
                fun current(): Arch {
                    val abi = android.os.Build.SUPPORTED_ABIS[0]
                    return when {
                        abi.startsWith("arm64") || abi.startsWith("aarch64") -> ARM64
                        abi.startsWith("x86_64") -> X86_64
                        else -> ARM64
                    }
                }
            }
        }
    }

    private val configManager = SysrootProfileConfigManager(context.applicationContext)

    fun getConfigManager(): SysrootProfileConfigManager = configManager

    fun listProfiles(arch: Arch = Arch.current()): List<SysrootProfileInfo> {
        val configured = configManager.listProfiles(arch)
            .filter { isCurrentConfiguredProfile(it, arch) }
        val bundled = listBuiltinProfileInfos(arch)
        return (configured + bundled)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<SysrootProfileInfo> { it.id == activeProfileId(arch) }
                    .thenByDescending { it.type == SysrootProfileType.BUILTIN }
                    .thenByDescending { it.installedAt }
            )
    }

    fun getActiveProfile(arch: Arch = Arch.current()): SysrootProfileInfo? =
        validConfiguredActiveProfile(arch)
            ?: installedDefaultBuiltinProfile(arch)

    fun resolveProfileId(profileId: String?, arch: Arch = Arch.current()): String? {
        val explicitProfileId = profileId?.trim()?.takeIf { it.isNotBlank() }
        if (explicitProfileId != null) {
            val explicitProfile = findConfiguredProfile(explicitProfileId, arch)
                ?: findBuiltinAssetProfile(explicitProfileId, arch)?.toProfileInfo()
            if (explicitProfile != null) {
                return explicitProfile.id
            }
        }

        return activeProfileId(arch)
            ?: defaultBuiltinAssetProfile(arch)?.id
    }

    fun activateProfile(profileId: String, arch: Arch = Arch.current()): Result<Unit> {
        val id = profileId.trim()
        if (id.isBlank()) {
            return Result.failure(IllegalArgumentException("Sysroot profile id is blank"))
        }
        val profile = configManager.listProfiles(arch)
            .filter { isCurrentConfiguredProfile(it, arch) }
            .firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Sysroot profile not found: $id"))
        if (!isValidSysrootRoot(configManager.getProfileDir(profile), arch)) {
            return Result.failure(IllegalStateException("Sysroot profile is not installed: $id"))
        }
        return configManager.switchProfile(id, arch)
    }

    suspend fun activateOrInstallProfile(
        profileId: String,
        arch: Arch = Arch.current(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val id = profileId.trim()
        if (id.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Sysroot profile id is blank"))
        }

        val builtin = findBuiltinAssetProfile(id, arch)
        if (builtin != null && !isValidSysrootRoot(configManager.getProfileDir(id), arch)) {
            return@withContext installBuiltinProfile(builtin, arch, onProgress).map { Unit }
        }
        if (builtin != null) {
            val installedInfo = builtin.toProfileInfo(
                installedAt = configManager.getProfileDir(id).lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            configManager.registerOrReplaceProfile(installedInfo).getOrThrow()
        }

        activateProfile(id, arch)
    }

    suspend fun ensureProfileInstalled(
        profileId: String,
        arch: Arch = Arch.current(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val id = profileId.trim()
        if (id.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Sysroot profile id is blank"))
        }

        val builtin = findBuiltinAssetProfile(id, arch)
        if (builtin != null) {
            val profileDir = configManager.getProfileDir(id)
            if (!isValidSysrootRoot(profileDir, arch)) {
                return@withContext installBuiltinProfile(
                    assetProfile = builtin,
                    arch = arch,
                    onProgress = onProgress,
                    activate = false
                ).map { Unit }
            }

            val installedInfo = builtin.toProfileInfo(
                installedAt = profileDir.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            configManager.registerOrReplaceProfile(installedInfo).getOrThrow()
            return@withContext Result.success(Unit)
        }

        val configured = findConfiguredProfile(id, arch)
            ?: return@withContext Result.failure(IllegalArgumentException("Sysroot profile not found: $id"))
        if (!isValidSysrootRoot(configManager.getProfileDir(configured), arch)) {
            return@withContext Result.failure(IllegalStateException("Sysroot profile is not installed: $id"))
        }
        Result.success(Unit)
    }

    fun isInstalled(arch: Arch = Arch.current(), profileId: String? = null): Boolean =
        isValidSysrootRoot(getSysrootDir(arch, profileId), arch)

    fun findMissingStaticCppRuntimeFile(
        apiLevel: Int,
        arch: Arch = Arch.current(),
        profileId: String? = null,
    ): File? {
        val root = getSysrootDir(arch, profileId)
        val includeDir = File(root, "usr/include")
        val apiLevelHeader = File(root, API_LEVEL_HEADER_PATH)
        val libDir = File(root, "usr/lib/${arch.triple}")
        val apiLibDir = File(libDir, apiLevel.toString())
        val cxxSharedLibrary = File(libDir, CXX_SHARED_LIBRARY_NAME)
        if (!root.isDirectory ||
            !includeDir.isDirectory ||
            !apiLevelHeader.isFile ||
            !libDir.isDirectory ||
            !apiLibDir.isDirectory ||
            !cxxSharedLibrary.isFile
        ) {
            return null
        }
        return requiredStaticCppRuntimeFiles(libDir, listOf(apiLevel)).firstOrNull { !it.isFile }
    }

    suspend fun importFromFile(
        archiveFile: File,
        arch: Arch = Arch.current(),
        profileName: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!archiveFile.isFile) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        AppStrings.get(
                            Strings.sysroot_import_archive_not_found,
                            archiveFile.absolutePath
                        )
                    )
                )
            }

            Timber.tag(TAG).i("Importing sysroot from file: %s", archiveFile.absolutePath)

            val tempDir = File(context.cacheDir, "sysroot-import-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                onProgress?.invoke(0.1f)

                TarExtractor.extract(archiveFile, tempDir) { progress ->
                    onProgress?.invoke(0.1f + progress * 0.7f)
                }

                onProgress?.invoke(0.8f)

                val extractedRoot = findExtractedSysrootRoot(tempDir, arch)
                    ?: return@withContext Result.failure(
                        IllegalStateException(
                            AppStrings.get(
                                Strings.sysroot_import_invalid_missing_paths,
                                "usr/include",
                                "usr/lib/${arch.triple}"
                            )
                        )
                    )

                val profileId = uniqueCustomProfileId(
                    preferredName = profileName ?: archiveFile.nameWithoutExtension,
                    arch = arch
                )
                val targetDir = configManager.getProfileDir(profileId)
                targetDir.parentFile?.mkdirs()
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }

                if (!extractedRoot.renameTo(targetDir)) {
                    extractedRoot.copyRecursively(targetDir, overwrite = true)
                    extractedRoot.deleteRecursively()
                }

                val info = buildProfileInfo(
                    id = profileId,
                    name = profileName?.trim()?.takeIf { it.isNotBlank() }
                        ?: archiveFile.nameWithoutExtension.ifBlank { profileId },
                    type = SysrootProfileType.CUSTOM,
                    path = "android-sysroots/$profileId",
                    arch = arch,
                    root = targetDir
                )

                configManager.registerOrReplaceProfile(info).getOrThrow()
                configManager.switchProfile(profileId, arch).getOrThrow()

                onProgress?.invoke(1.0f)
                Timber.tag(TAG).i("Sysroot imported successfully: id=%s path=%s", profileId, targetDir.absolutePath)
                Result.success(profileId)
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Sysroot import failed")
            Result.failure(e)
        }
    }

    suspend fun install(
        arch: Arch = Arch.current(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> {
        val builtin = defaultBuiltinAssetProfile(arch)
            ?: return Result.failure(IllegalStateException("No builtin sysroot profile for arch: ${arch.name}"))
        return installBuiltinProfile(builtin, arch, onProgress).map { Unit }
    }

    private suspend fun installBuiltinProfile(
        assetProfile: BuiltinSysrootProfileAsset,
        arch: Arch,
        onProgress: ((Float) -> Unit)? = null,
        activate: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).i("Starting sysroot installation: arch=%s profile=%s", arch, assetProfile.id)

            val assetExists = try {
                context.assets.open(assetProfile.assetPath).use { true }
            } catch (e: Exception) {
                false
            }

            if (!assetExists) {
                return@withContext Result.failure(
                    IllegalStateException(
                        AppStrings.get(
                            Strings.sysroot_asset_not_found,
                            assetProfile.assetPath
                        )
                    )
                )
            }

            val tempDir = File(context.cacheDir, "sysroot-install-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                onProgress?.invoke(0.1f)
                val tarFile = File(tempDir, "sysroot.tar.xz")
                context.assets.open(assetProfile.assetPath).use { input ->
                    FileOutputStream(tarFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.tag(TAG).i("Copied sysroot asset: %d bytes", tarFile.length())
                validateAssetHashIfPresent(assetProfile, tarFile).getOrThrow()

                onProgress?.invoke(0.3f)
                TarExtractor.extract(tarFile, tempDir) { progress ->
                    onProgress?.invoke(0.3f + progress * 0.6f)
                }

                onProgress?.invoke(0.9f)
                val extractedRoot = findExtractedSysrootRoot(tempDir, arch)
                    ?: return@withContext Result.failure(
                        IllegalStateException(
                            AppStrings.get(
                                Strings.sysroot_install_extracted_dir_missing,
                                File(tempDir, SYSROOT_DIR_NAME).absolutePath
                            )
                        )
                    )

                val profileId = assetProfile.id
                val targetDir = configManager.getProfileDir(profileId)
                targetDir.parentFile?.mkdirs()
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }

                if (!extractedRoot.renameTo(targetDir)) {
                    extractedRoot.copyRecursively(targetDir, overwrite = true)
                    extractedRoot.deleteRecursively()
                }

                val info = buildProfileInfo(
                    id = profileId,
                    name = assetProfile.name.ifBlank { assetProfile.id },
                    type = SysrootProfileType.BUILTIN,
                    path = "android-sysroots/$profileId",
                    arch = arch,
                    root = targetDir,
                    assetProfile = assetProfile
                )
                configManager.registerOrReplaceProfile(info).getOrThrow()
                if (activate) {
                    configManager.switchProfile(profileId, arch).getOrThrow()
                }

                onProgress?.invoke(1.0f)
                Timber.tag(TAG).i("Sysroot installation complete: id=%s path=%s", profileId, targetDir.absolutePath)
                Result.success(profileId)
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Sysroot installation failed")
            Result.failure(e)
        }
    }

    fun getSysrootDir(arch: Arch = Arch.current(), profileId: String? = null): File {
        val explicitProfileId = profileId?.trim()?.takeIf { it.isNotBlank() }
        if (explicitProfileId != null) {
            val resolvedProfileId = resolveProfileId(explicitProfileId, arch)
            return if (resolvedProfileId != null) {
                configManager.getProfileDir(resolvedProfileId)
            } else {
                configManager.getProfileDir(explicitProfileId)
            }
        }

        return resolveProfileId(profileId = null, arch = arch)
            ?.let(configManager::getProfileDir)
            ?: File(context.filesDir, "android-sysroots/${arch.name.lowercase(Locale.US)}")
    }

    fun getSysrootPath(arch: Arch = Arch.current(), profileId: String? = null): String? {
        val dir = getSysrootDir(arch, profileId)
        return if (dir.exists()) dir.absolutePath else null
    }

    fun getLibPath(apiLevel: Int, arch: Arch = Arch.current(), profileId: String? = null): String? {
        val sysroot = getSysrootDir(arch, profileId)
        val libDir = File(sysroot, "usr/lib/${arch.triple}/$apiLevel")
        return if (libDir.exists()) libDir.absolutePath else null
    }

    fun getIncludePath(arch: Arch = Arch.current(), profileId: String? = null): String? {
        val sysroot = getSysrootDir(arch, profileId)
        val includeDir = File(sysroot, "usr/include")
        return if (includeDir.exists()) includeDir.absolutePath else null
    }

    fun getCompilerFlags(
        apiLevel: Int,
        arch: Arch = Arch.current(),
        isCpp: Boolean = false,
        profileId: String? = null
    ): List<String> {
        val sysroot = getSysrootPath(arch, profileId) ?: return emptyList()
        val libPath = getLibPath(apiLevel, arch, profileId) ?: return emptyList()

        return buildList {
            add("--target=${arch.triple}$apiLevel")
            add("--sysroot=$sysroot")
            if (isCpp) {
                add("-isystem")
                add("$sysroot/usr/include/c++/v1")
            }
            add("-I$sysroot/usr/include")
            add("-I$sysroot/usr/include/${arch.triple}")
            add("-L$libPath")
        }
    }

    fun getVersion(arch: Arch = Arch.current()): SysrootVersion? =
        readSysrootVersion(getSysrootDir(arch))

    fun uninstall(arch: Arch = Arch.current()): Boolean {
        val dir = getSysrootDir(arch)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
    }

    private fun activeProfileId(arch: Arch): String? =
        configManager.getActiveProfile(arch)
            ?.takeIf { isCurrentConfiguredProfile(it, arch) }
            ?.takeIf {
                it.type == SysrootProfileType.BUILTIN ||
                    isValidSysrootRoot(configManager.getProfileDir(it), arch)
            }
            ?.id
            ?: installedDefaultBuiltinProfile(arch)?.id

    private fun validConfiguredActiveProfile(arch: Arch): SysrootProfileInfo? =
        configManager.getActiveProfile(arch)
            ?.takeIf { isCurrentConfiguredProfile(it, arch) }
            ?.takeIf { isValidSysrootRoot(configManager.getProfileDir(it), arch) }

    private fun isCurrentConfiguredProfile(profile: SysrootProfileInfo, arch: Arch): Boolean =
        when (profile.type) {
            SysrootProfileType.BUILTIN -> listBuiltinAssetProfiles(arch).any { it.id == profile.id }
            SysrootProfileType.CUSTOM -> true
            SysrootProfileType.LEGACY -> false
        }

    private fun listBuiltinProfileInfos(arch: Arch): List<SysrootProfileInfo> =
        listBuiltinAssetProfiles(arch).map { assetProfile ->
            val profileDir = configManager.getProfileDir(assetProfile.id)
            val installedAt = if (isValidSysrootRoot(profileDir, arch)) {
                profileDir.lastModified().takeIf { it > 0L } ?: 0L
            } else {
                0L
            }
            assetProfile.toProfileInfo(installedAt = installedAt)
        }

    private fun listBuiltinAssetProfiles(arch: Arch): List<BuiltinSysrootProfileAsset> {
        val manifest = readBuiltinManifest()
        return manifest?.profiles.orEmpty()
            .filter { it.supports(arch) && it.id.isNotBlank() && it.assetPath.isNotBlank() }
    }

    private fun defaultBuiltinAssetProfile(arch: Arch): BuiltinSysrootProfileAsset? {
        val profiles = listBuiltinAssetProfiles(arch)
        if (profiles.isEmpty()) return null
        val manifestDefaultId = readBuiltinManifest()?.defaultProfileId?.trim().orEmpty()
        return profiles.firstOrNull { manifestDefaultId.isNotBlank() && it.id == manifestDefaultId }
            ?: profiles.firstOrNull { it.isDefault }
            ?: profiles.first()
    }

    private fun installedDefaultBuiltinProfile(arch: Arch): SysrootProfileInfo? {
        val assetProfile = defaultBuiltinAssetProfile(arch) ?: return null
        val profileDir = configManager.getProfileDir(assetProfile.id)
        if (!isValidSysrootRoot(profileDir, arch)) return null
        return assetProfile.toProfileInfo(
            installedAt = profileDir.lastModified().takeIf { it > 0L } ?: 0L
        )
    }

    private fun findConfiguredProfile(id: String, arch: Arch): SysrootProfileInfo? =
        configManager.listProfiles(arch)
            .filter { isCurrentConfiguredProfile(it, arch) }
            .firstOrNull { it.id == id }

    private fun findBuiltinAssetProfile(id: String, arch: Arch): BuiltinSysrootProfileAsset? =
        listBuiltinAssetProfiles(arch).firstOrNull { it.id == id }

    private fun readBuiltinManifest(): BuiltinSysrootProfileManifest? = builtinManifestProvider()

    private fun validateAssetHashIfPresent(
        assetProfile: BuiltinSysrootProfileAsset,
        file: File
    ): Result<Unit> {
        return try {
            val expected = assetProfile.sha256?.trim()?.lowercase(Locale.US).orEmpty()
            if (expected.isBlank()) {
                return Result.success(Unit)
            }
            val actual = sha256(file)
            if (actual != expected) {
                Result.failure(IllegalStateException("Sysroot asset sha256 mismatch: ${assetProfile.assetPath}"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uniqueCustomProfileId(preferredName: String, arch: Arch): String {
        val base = sanitizeProfileId(
            "$CUSTOM_PROFILE_PREFIX-${preferredName.ifBlank { "sysroot" }}-${arch.name}-${System.currentTimeMillis()}"
        )
        return base.ifBlank { "$CUSTOM_PROFILE_PREFIX-${System.currentTimeMillis()}" }
    }

    private fun sanitizeProfileId(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9.-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .lowercase(Locale.US)

    private fun buildProfileInfo(
        id: String,
        name: String,
        type: SysrootProfileType,
        path: String,
        arch: Arch,
        root: File,
        assetProfile: BuiltinSysrootProfileAsset? = null
    ): SysrootProfileInfo {
        val version = readSysrootVersion(root)
        val apiLevels = version?.apiLevels?.takeIf { it.isNotEmpty() }
            ?: assetProfile?.apiLevels?.takeIf { it.isNotEmpty() }
            ?: discoverApiLevels(root, arch)
        return SysrootProfileInfo(
            id = id,
            name = name,
            arch = arch.name,
            type = type,
            path = path,
            installedAt = System.currentTimeMillis(),
            ndkVersion = version?.ndkVersion ?: assetProfile?.ndkVersion,
            ndkLlvmVersion = version?.ndkLlvmVersion ?: assetProfile?.ndkLlvmVersion,
            apiLevels = apiLevels,
            toolchainTriple = version?.toolchainTriple ?: assetProfile?.toolchainTriple,
            createdAt = version?.createdAt ?: assetProfile?.createdAt
        )
    }

    private fun findExtractedSysrootRoot(tempDir: File, arch: Arch): File? {
        val candidates = buildList {
            add(File(tempDir, SYSROOT_DIR_NAME))
            add(tempDir)
            tempDir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .forEach { child ->
                    add(child)
                    add(File(child, SYSROOT_DIR_NAME))
                }
        }
        return candidates.firstOrNull { isValidSysrootRoot(it, arch) }
    }

    private fun isValidSysrootRoot(root: File, arch: Arch): Boolean {
        if (!root.isDirectory) return false
        val includeDir = File(root, "usr/include")
        val apiLevelHeader = File(root, API_LEVEL_HEADER_PATH)
        val libDir = File(root, "usr/lib/${arch.triple}")
        val cxxSharedLibrary = File(libDir, CXX_SHARED_LIBRARY_NAME)
        val apiLevels = discoverApiLevels(root, arch)
        return includeDir.isDirectory &&
            apiLevelHeader.isFile &&
            libDir.isDirectory &&
            cxxSharedLibrary.isFile &&
            apiLevels.isNotEmpty() &&
            requiredStaticCppRuntimeFiles(libDir, apiLevels).all { it.isFile }
    }

    private fun requiredStaticCppRuntimeFiles(libDir: File, apiLevels: List<Int>): List<File> = buildList {
        add(File(libDir, CXX_STATIC_LIBRARY_NAME))
        add(File(libDir, CXX_ABI_LIBRARY_NAME))
        apiLevels.distinct().forEach { apiLevel ->
            add(File(File(libDir, apiLevel.toString()), CXX_API_LINKER_SCRIPT_NAME))
        }
    }

    private fun discoverApiLevels(root: File, arch: Arch): List<Int> {
        val libDir = File(root, "usr/lib/${arch.triple}")
        return libDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { it.name.toIntOrNull() }
            .sorted()
            .toList()
    }

    private fun readSysrootVersion(sysrootDir: File): SysrootVersion? {
        val versionFile = File(sysrootDir, ".version")
        if (!versionFile.isFile) return null

        return try {
            val props = Properties()
            FileInputStream(versionFile).use { props.load(it) }

            SysrootVersion(
                arch = props.getProperty("ARCH"),
                abi = props.getProperty("ABI"),
                apiLevels = props.getProperty("API_LEVELS")
                    ?.split(" ")
                    ?.mapNotNull { it.toIntOrNull() }
                    ?: emptyList(),
                toolchainTriple = props.getProperty("TOOLCHAIN_TRIPLE"),
                createdAt = props.getProperty("CREATED_AT"),
                ndkVersion = props.getProperty("NDK_VERSION"),
                ndkLlvmVersion = props.getProperty("NDK_LLVM_VERSION")
                    ?: props.getProperty("LLVM_VERSION")
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read sysroot version info")
            null
        }
    }

    data class SysrootVersion(
        val arch: String?,
        val abi: String?,
        val apiLevels: List<Int>,
        val toolchainTriple: String?,
        val createdAt: String?,
        val ndkVersion: String? = null,
        val ndkLlvmVersion: String? = null
    )
}
