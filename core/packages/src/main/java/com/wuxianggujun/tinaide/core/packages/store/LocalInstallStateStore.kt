package com.wuxianggujun.tinaide.core.packages.store

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import timber.log.Timber

class LocalInstallStateStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = JsonSerializer.default

    companion object {
        // Multiple screens and the bundled installer use separate store instances.
        private val stateLock = Any()
        private const val PREFS_NAME = "package_install_state"
        private const val KEY_INSTALLED_PACKAGES = "installed_packages"
        private const val KEY_UPDATE_AVAILABLE = "update_available"
    }

    @Serializable
    private data class StoredInstallInfo(
        val packageId: String,
        val packageName: String,
        val platform: String,
        val version: String,
        val installedAt: Long,
        val installType: String,
        val size: Long? = null,
        val isBundled: Boolean = false
    )

    @Serializable
    private data class StoredUpdateInfo(
        val packageId: String,
        val platform: String,
        val currentVersion: String,
        val newVersion: String
    )

    fun getInstallState(packageId: String): PackageInstallState = synchronized(stateLock) {
        val installedPackages = getStoredPackages()
        val updateInfos = getStoredUpdates()

        val linuxInfo = installedPackages.find { it.packageId == packageId && it.platform == "linux" }
        val androidInfo = installedPackages.find { it.packageId == packageId && it.platform == "android" }
        val linuxUpdate = linuxInfo?.let { installed -> updateInfos.find {
            it.packageId == packageId && it.platform == "linux" && it.currentVersion == installed.version
        } }
        val androidUpdate = androidInfo?.let { installed -> updateInfos.find {
            it.packageId == packageId && it.platform == "android" && it.currentVersion == installed.version
        } }

        PackageInstallState(
            linux = when {
                linuxUpdate != null -> PlatformInstallState.UpdateAvailable(linuxUpdate.currentVersion, linuxUpdate.newVersion)
                linuxInfo != null -> PlatformInstallState.Installed(linuxInfo.version, linuxInfo.installedAt)
                else -> PlatformInstallState.NotInstalled
            },
            android = when {
                androidUpdate != null -> PlatformInstallState.UpdateAvailable(androidUpdate.currentVersion, androidUpdate.newVersion)
                androidInfo != null -> PlatformInstallState.Installed(androidInfo.version, androidInfo.installedAt)
                else -> PlatformInstallState.NotInstalled
            }
        )
    }

    fun setInstalled(
        packageId: String,
        platform: Platform,
        version: String,
        packageName: String = packageId,
        installType: InstallType = InstallType.APT,
        size: Long? = null,
        isBundled: Boolean = false
    ) = synchronized(stateLock) {
        val installedPackages = getStoredPackages(strict = true).toMutableList()

        installedPackages.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }

        installedPackages.add(
            StoredInstallInfo(
                packageId = packageId,
                packageName = packageName,
                platform = platform.name.lowercase(),
                version = version,
                installedAt = System.currentTimeMillis(),
                installType = installType.name.lowercase(),
                size = size,
                isBundled = isBundled
            )
        )

        saveState(installedPackages, getStoredUpdates(strict = true).filterNot {
            it.packageId == packageId && it.platform == platform.name.lowercase()
        })
    }

    fun setUninstalled(packageId: String, platform: Platform) = synchronized(stateLock) {
        val installedPackages = getStoredPackages(strict = true).toMutableList()
        installedPackages.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }
        saveState(installedPackages, getStoredUpdates(strict = true).filterNot {
            it.packageId == packageId && it.platform == platform.name.lowercase()
        })
    }

    fun getAllInstalledPackages(): List<InstalledPackageInfo> = synchronized(stateLock) { getStoredPackages().map { stored ->
        InstalledPackageInfo(
            packageId = stored.packageId,
            packageName = stored.packageName,
            platform = Platform.valueOf(stored.platform.uppercase()),
            version = stored.version,
            installedAt = stored.installedAt,
            installType = InstallType.valueOf(stored.installType.uppercase()),
            size = stored.size
        )
    } }

    fun isInstalled(packageId: String, platform: Platform): Boolean = synchronized(stateLock) { getStoredPackages().any {
        it.packageId == packageId && it.platform == platform.name.lowercase()
    } }

    fun getInstalledVersion(packageId: String, platform: Platform): String? = synchronized(stateLock) { getStoredPackages().find {
        it.packageId == packageId && it.platform == platform.name.lowercase()
    }?.version }

    fun isBundledPackage(packageId: String): Boolean = synchronized(stateLock) { getStoredPackages().any {
        it.packageId == packageId && it.isBundled
    } }

    fun setUpdateAvailable(
        packageId: String,
        platform: Platform,
        currentVersion: String,
        newVersion: String
    ) = synchronized(stateLock) {
        // A late update response must not resurrect an uninstalled or replaced package.
        if (getInstalledVersion(packageId, platform) != currentVersion) return@synchronized
        val updates = getStoredUpdates(strict = true).toMutableList()
        updates.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }
        updates.add(
            StoredUpdateInfo(
                packageId = packageId,
                platform = platform.name.lowercase(),
                currentVersion = currentVersion,
                newVersion = newVersion
            )
        )
        saveStoredUpdates(updates)
    }

    fun clearUpdateAvailable(packageId: String, platform: Platform) = synchronized(stateLock) {
        val updates = getStoredUpdates(strict = true).toMutableList()
        updates.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }
        saveStoredUpdates(updates)
    }

    fun clear() = synchronized(stateLock) {
        prefs.edit().clear().commitState()
    }

    private fun getStoredPackages(strict: Boolean = false): List<StoredInstallInfo> {
        val jsonString = prefs.getString(KEY_INSTALLED_PACKAGES, null) ?: return emptyList()
        return try {
            val records = json.decodeFromString<List<StoredInstallInfo>>(jsonString)
            // Writers retain unfamiliar records so updating one known package
            // never discards data created by another version. Readers expose
            // only records that all state/list APIs can interpret consistently.
            if (strict) records else records.filter { record ->
                RegistryPackageId.isValid(record.packageId) &&
                    record.version.isNotBlank() &&
                    Platform.entries.any { it.name.lowercase() == record.platform } &&
                    InstallType.entries.any { it.name.lowercase() == record.installType }
            }.also { readable ->
                if (readable.size != records.size) {
                    Timber.tag("LocalInstallStateStore").w(
                        "Ignoring %d unsupported installed package record(s)", records.size - readable.size,
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag("LocalInstallStateStore").e(e, "Cannot decode installed package records")
            if (strict) throw IllegalStateException("Cannot decode installed package records", e)
            emptyList()
        }
    }

    private fun saveState(packages: List<StoredInstallInfo>, updates: List<StoredUpdateInfo>) {
        // Installation completion can be followed immediately by process death.
        // apply() only changes the in-process cache; callers already mutate on IO.
        prefs.edit()
            .putString(KEY_INSTALLED_PACKAGES, json.encodeToString(packages))
            .putString(KEY_UPDATE_AVAILABLE, json.encodeToString(updates))
            .commitState()
    }

    private fun getStoredUpdates(strict: Boolean = false): List<StoredUpdateInfo> {
        val jsonString = prefs.getString(KEY_UPDATE_AVAILABLE, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredUpdateInfo>>(jsonString)
        } catch (e: Exception) {
            Timber.tag("LocalInstallStateStore").e(e, "Cannot decode package update records")
            if (strict) throw IllegalStateException("Cannot decode package update records", e)
            emptyList()
        }
    }

    private fun saveStoredUpdates(updates: List<StoredUpdateInfo>) {
        val jsonString = json.encodeToString(updates)
        prefs.edit().putString(KEY_UPDATE_AVAILABLE, jsonString).commitState()
    }

    private fun SharedPreferences.Editor.commitState() {
        if (!commit()) throw IOException("Could not persist package installation state")
    }
}
