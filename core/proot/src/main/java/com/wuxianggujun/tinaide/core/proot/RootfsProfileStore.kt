package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RootfsProfileStore(
    context: Context,
    private val configManager: IConfigManager,
) {

    companion object {
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    private val stateFile: File
        get() = File(ProjectPaths.getPRootRoot(appContext), "rootfs_profiles.json")

    fun listProfiles(): List<RootfsProfile> = getState().profiles.sortedWith(
        compareByDescending<RootfsProfile> { it.updatedAt }
            .thenBy { it.displayName.lowercase() }
    )

    fun listProfilesForDistro(distroId: String): List<RootfsProfile> {
        require(distroId.isNotBlank()) { "Distro id must not be blank" }
        return listProfiles().filter { profile -> profile.distroId == distroId }
    }

    fun getState(): RootfsProfilesState = synchronized(lock) { ensureStateLocked() }

    fun getProfile(profileId: String): RootfsProfile? = getState().profiles.firstOrNull { it.id == profileId }

    fun getActiveProfileOrNull(): RootfsProfile? {
        val state = getState()
        return state.profiles.firstOrNull { it.id == state.activeProfileId }
            ?: state.profiles.firstOrNull()
    }

    /**
     * Returns the active profile for the requested distribution.
     *
     * The active profile id is global, so an older or unrelated profile can remain
     * selected while a new distribution is being installed. Runtime callers must
     * resolve the profile they actually support instead of accidentally launching
     * another distribution.
     */
    fun getActiveProfileForDistro(distroId: String): RootfsProfile? {
        require(distroId.isNotBlank()) { "Distro id must not be blank" }
        val state = getState()
        return state.profiles.firstOrNull {
            it.id == state.activeProfileId && it.distroId == distroId
        } ?: state.profiles.firstOrNull { it.distroId == distroId }
    }

    fun getActiveRootfsPathForDistro(distroId: String): String =
        getActiveProfileForDistro(distroId)?.rootfsPath.orEmpty()

    fun getActiveProfile(): RootfsProfile = getActiveProfileOrNull()
        ?: throw IllegalStateException("No Linux rootfs profile is installed")

    fun setActiveProfile(profileId: String): RootfsProfile = synchronized(lock) {
        val currentState = ensureStateLocked()
        val target = currentState.profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("Unknown rootfs profile: $profileId")
        val nextState = currentState.copy(activeProfileId = target.id)
        persistStateLocked(nextState)
        target
    }

    fun setActiveProfileForDistro(profileId: String, distroId: String): RootfsProfile = synchronized(lock) {
        require(distroId.isNotBlank()) { "Distro id must not be blank" }
        val currentState = ensureStateLocked()
        val target = currentState.profiles.firstOrNull {
            it.id == profileId && it.distroId == distroId
        } ?: throw IllegalArgumentException("Unknown $distroId rootfs profile: $profileId")
        val nextState = currentState.copy(activeProfileId = target.id)
        persistStateLocked(nextState)
        target
    }

    fun renameProfile(profileId: String, displayName: String): RootfsProfile {
        val normalizedName = displayName.trim()
        require(normalizedName.isNotEmpty()) { "Profile display name must not be empty" }

        return synchronized(lock) {
            val currentState = ensureStateLocked()
            val target = currentState.profiles.firstOrNull { it.id == profileId }
                ?: throw IllegalArgumentException("Unknown rootfs profile: $profileId")

            val updated = target.copy(
                displayName = normalizedName,
                updatedAt = System.currentTimeMillis(),
            )
            val nextState = currentState.copy(
                profiles = currentState.profiles.map { profile ->
                    if (profile.id == profileId) updated else profile
                }
            )
            persistStateLocked(nextState)
            updated
        }
    }

    fun deleteProfile(profileId: String): RootfsProfile = synchronized(lock) {
        val currentState = ensureStateLocked()
        val target = currentState.profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("Unknown rootfs profile: $profileId")

        deleteProfileRootfsDirIfManaged(target)

        val remainingProfiles = currentState.profiles.filterNot { it.id == profileId }
        val nextActiveId = when {
            currentState.activeProfileId != profileId -> currentState.activeProfileId
            else -> remainingProfiles.firstOrNull()?.id.orEmpty()
        }
        val nextState = currentState.copy(
            activeProfileId = nextActiveId,
            profiles = remainingProfiles,
        )
        persistStateLocked(nextState)
        target
    }

    fun upsertProfile(profile: RootfsProfile, makeActive: Boolean = false): RootfsProfile = synchronized(lock) {
        val currentState = ensureStateLocked()
        val now = System.currentTimeMillis()
        val existing = currentState.profiles.firstOrNull { it.id == profile.id }
        val merged = profile.copy(
            createdAt = existing?.createdAt ?: profile.createdAt,
            updatedAt = now,
        )
        val nextProfiles = currentState.profiles
            .filterNot { it.id == merged.id } + merged
        val nextState = currentState.copy(
            activeProfileId = when {
                makeActive -> merged.id
                currentState.activeProfileId.isBlank() -> merged.id
                else -> currentState.activeProfileId
            },
            profiles = nextProfiles,
        )
        persistStateLocked(nextState)
        merged
    }

    fun isInstalled(profile: RootfsProfile): Boolean {
        val rootfsDir = File(profile.rootfsPath)
        val shellPath = profile.shellPath.ifBlank { RootfsProfile.DEFAULT_SHELL_PATH }
        return rootfsDir.isDirectory && RootfsFileChecks.exists(rootfsDir, shellPath)
    }

    fun getActiveRootfsPath(): String = getActiveProfile().rootfsPath

    private fun ensureStateLocked(): RootfsProfilesState {
        val existing = readStateFromDiskLocked()
        val normalized = normalizeState(existing)
        if (existing != normalized) {
            persistStateLocked(normalized)
        } else {
            syncActiveRootfsPathLocked(normalized)
        }
        return normalized
    }

    private fun readStateFromDiskLocked(): RootfsProfilesState? {
        if (!stateFile.isFile) return null
        return runCatching {
            json.decodeFromString<RootfsProfilesState>(stateFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun normalizeState(state: RootfsProfilesState?): RootfsProfilesState {
        val profiles = state?.profiles
            .orEmpty()
            .distinctBy { it.id }
        val activeId = state?.activeProfileId
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id.orEmpty()

        return RootfsProfilesState(
            activeProfileId = activeId,
            profiles = profiles,
        )
    }

    private fun persistStateLocked(state: RootfsProfilesState) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.encodeToString(state), Charsets.UTF_8)
        syncActiveRootfsPathLocked(state)
    }

    private fun deleteProfileRootfsDirIfManaged(profile: RootfsProfile) {
        val installedRootfsRoot = File(
            ProjectPaths.getPRootRoot(appContext),
            "linux-distro/installed-rootfs",
        )
        val profileRoot = File(profile.rootfsPath)
        val managedDir = runCatching { profileRoot.canonicalFile }.getOrElse { profileRoot.absoluteFile }
        val managedRoot = runCatching { installedRootfsRoot.canonicalFile }
            .getOrElse { installedRootfsRoot.absoluteFile }
        val isManagedChild = managedDir.path.startsWith(managedRoot.path + File.separator)
        if (isManagedChild && managedDir.exists()) {
            managedDir.deleteRecursively()
        }
    }

    private fun syncActiveRootfsPathLocked(state: RootfsProfilesState) {
        val activePath = state.profiles.firstOrNull { it.id == state.activeProfileId }?.rootfsPath.orEmpty()
        configManager.set(ConfigKeys.RootfsPath, activePath)
    }
}
