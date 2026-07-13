package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNames
import timber.log.Timber

@Serializable
enum class SysrootProfileType {
    BUILTIN,
    CUSTOM,
    LEGACY
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SysrootProfileInfo(
    val id: String,
    val name: String,
    val arch: String,
    val type: SysrootProfileType,
    val path: String,
    val installedAt: Long,
    val ndkVersion: String? = null,
    @JsonNames("llvmVersion")
    val ndkLlvmVersion: String? = null,
    val apiLevels: List<Int> = emptyList(),
    val toolchainTriple: String? = null,
    val createdAt: String? = null
) {
    fun supports(arch: AndroidSysrootManager.Companion.Arch): Boolean =
        this.arch.equals(arch.name, ignoreCase = true) ||
            this.arch.equals(arch.triple, ignoreCase = true)
}

@Serializable
data class InstalledSysrootProfileConfig(
    val activeProfiles: Map<String, String> = emptyMap(),
    val profiles: List<SysrootProfileInfo> = emptyList()
)

@Serializable
data class BuiltinSysrootProfileManifest(
    val schemaVersion: Int = 1,
    val defaultProfileId: String? = null,
    val profiles: List<BuiltinSysrootProfileAsset> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BuiltinSysrootProfileAsset(
    val id: String,
    val name: String,
    val arch: String,
    val assetPath: String,
    val sha256: String? = null,
    val ndkVersion: String? = null,
    @JsonNames("llvmVersion")
    val ndkLlvmVersion: String? = null,
    val apiLevels: List<Int> = emptyList(),
    val toolchainTriple: String? = null,
    val createdAt: String? = null,
    @SerialName("default")
    val isDefault: Boolean = false
) {
    fun supports(arch: AndroidSysrootManager.Companion.Arch): Boolean =
        this.arch.equals(arch.name, ignoreCase = true) ||
            this.arch.equals(arch.triple, ignoreCase = true)

    fun toProfileInfo(installedAt: Long = 0L): SysrootProfileInfo = SysrootProfileInfo(
        id = id,
        name = name,
        arch = arch,
        type = SysrootProfileType.BUILTIN,
        path = "android-sysroots/$id",
        installedAt = installedAt,
        ndkVersion = ndkVersion,
        ndkLlvmVersion = ndkLlvmVersion,
        apiLevels = apiLevels,
        toolchainTriple = toolchainTriple,
        createdAt = createdAt
    )
}

class SysrootProfileConfigManager(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        private const val TAG = "SysrootProfileConfig"
        private const val CONFIG_FILE = "sysroot-profile-config.json"
        private const val SYSROOT_PROFILES_DIR = "android-sysroots"
    }

    private val json = JsonSerializer.pretty

    private val configFile: File
        get() = File(context.filesDir, CONFIG_FILE)

    private val profilesDir: File
        get() = File(context.filesDir, SYSROOT_PROFILES_DIR)

    fun readConfig(): InstalledSysrootProfileConfig = try {
        if (configFile.isFile) {
            json.decodeFromString<InstalledSysrootProfileConfig>(
                configFile.readText(Charsets.UTF_8)
            )
        } else {
            InstalledSysrootProfileConfig()
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to read sysroot profile config")
        InstalledSysrootProfileConfig()
    }

    fun saveConfig(config: InstalledSysrootProfileConfig) {
        try {
            configFile.writeText(json.encodeToString(config), Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save sysroot profile config")
        }
    }

    fun getProfileDir(id: String): File {
        profilesDir.mkdirs()
        return File(profilesDir, id)
    }

    fun getProfileDir(profile: SysrootProfileInfo): File = File(context.filesDir, profile.path)

    fun getActiveProfile(arch: AndroidSysrootManager.Companion.Arch): SysrootProfileInfo? {
        val config = readConfig()
        val activeId = config.activeProfiles[arch.name] ?: return null
        return config.profiles.firstOrNull { it.id == activeId && it.supports(arch) }
    }

    fun listProfiles(arch: AndroidSysrootManager.Companion.Arch): List<SysrootProfileInfo> =
        readConfig().profiles
            .filter { it.supports(arch) }
            .sortedWith(
                compareByDescending<SysrootProfileInfo> { it.type == SysrootProfileType.BUILTIN }
                    .thenByDescending { it.installedAt }
            )

    fun registerOrReplaceProfile(info: SysrootProfileInfo): Result<Unit> = try {
        require(info.id.isNotBlank()) { "Sysroot profile id is blank" }
        val config = readConfig()
        val profiles = config.profiles.filterNot { it.id == info.id } + info
        saveConfig(config.copy(profiles = profiles))
        Timber.tag(TAG).i("Registered sysroot profile: %s", info.id)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to register sysroot profile")
        Result.failure(e)
    }

    fun switchProfile(id: String, arch: AndroidSysrootManager.Companion.Arch): Result<Unit> {
        return try {
            val config = readConfig()
            val profile = config.profiles.firstOrNull { it.id == id && it.supports(arch) }
                ?: return Result.failure(IllegalArgumentException("Sysroot profile not found: $id"))
            val profileDir = getProfileDir(profile)
            if (!profileDir.isDirectory) {
                return Result.failure(IllegalStateException("Sysroot profile directory not found: ${profileDir.absolutePath}"))
            }
            saveConfig(
                config.copy(
                    activeProfiles = config.activeProfiles + (arch.name to id)
                )
            )
            Timber.tag(TAG).i("Switched sysroot profile: arch=%s id=%s", arch.name, id)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to switch sysroot profile")
            Result.failure(e)
        }
    }

    suspend fun removeProfile(id: String, deleteFiles: Boolean = true): Result<Unit> =
        withContext(ioDispatcher) {
            removeProfileOnIo(id, deleteFiles)
        }

    private fun removeProfileOnIo(id: String, deleteFiles: Boolean): Result<Unit> {
        return try {
            val config = readConfig()
            val profile = config.profiles.firstOrNull { it.id == id }
                ?: return Result.failure(IllegalArgumentException("Sysroot profile not found: $id"))
            if (config.activeProfiles.containsValue(id)) {
                return Result.failure(IllegalStateException("Cannot remove active sysroot profile"))
            }
            if (deleteFiles) {
                val profileDir = getProfileDir(profile)
                if (profileDir.exists() && !profileDir.deleteRecursively()) {
                    throw IOException("Failed to delete sysroot profile directory: ${profileDir.absolutePath}")
                }
            }
            saveConfig(config.copy(profiles = config.profiles.filterNot { it.id == id }))
            Timber.tag(TAG).i("Removed sysroot profile: %s", id)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to remove sysroot profile")
            Result.failure(e)
        }
    }
}
