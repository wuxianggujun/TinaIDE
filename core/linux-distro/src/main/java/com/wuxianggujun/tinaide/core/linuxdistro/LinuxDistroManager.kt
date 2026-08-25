package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class LinuxDistroManager(
    private val catalog: LinuxDistroCatalog,
    private val layout: LinuxDistroInstallLayout,
    private val installer: LinuxDistroInstaller = LinuxDistroInstaller(catalog),
    private val registry: LinuxDistroInstallationRegistry = FileLinuxDistroInstallationRegistry(
        File(layout.runtimeDir, "linux-distro-registry.json"),
    ),
    private val metadataStore: LinuxDistroInstallMetadataStore = JsonLinuxDistroInstallMetadataStore(),
    private val rootfsProbe: LinuxDistroRootfsProbe = BasicLinuxDistroRootfsProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun listAvailable(): List<DistroDefinition> = catalog.listDistros()

    fun resolveDistro(distroId: String): DistroDefinition? = catalog.resolveDistro(distroId)

    fun listInstalled(syncFromDisk: Boolean = true): List<InstalledLinuxDistro> = if (syncFromDisk) refreshInstalledFromDisk() else registry.list()

    fun isInstalled(distroId: String): Boolean {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        val rootfsDir = layout.rootfsDir(distroId)
        return rootfsProbe.hasBootShell(rootfsDir)
    }

    suspend fun install(
        distroId: String,
        architecture: DistroArchitecture,
        releaseId: String? = null,
        reinstall: Boolean = false,
        rootfsConfig: LinuxDistroRootfsConfig = LinuxDistroRootfsConfig(),
        progress: (LinuxDistroInstallProgress) -> Unit = {},
    ): LinuxDistroInstallResult {
        val request = LinuxDistroInstallRequest(
            distroId = distroId,
            architecture = architecture,
            layout = layout,
            releaseId = releaseId,
            reinstall = reinstall,
            rootfsConfig = rootfsConfig,
        )
        return withLinuxDistroMutationLock(layout.rootfsDir(distroId)) {
            installer.installUnderMutationLock(request, progress).also { result ->
                registry.upsert(result.installation)
            }
        }
    }

    suspend fun uninstall(distroId: String): Boolean {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        val rootfsDir = layout.rootfsDir(distroId)
        return withLinuxDistroMutationLock(rootfsDir) {
            val rootfsPath = rootfsDir.toPath()
            val rootfsExisted = Files.exists(rootfsPath, LinkOption.NOFOLLOW_LINKS)
            check(!Files.isSymbolicLink(rootfsPath)) {
                "Linux distro rootfs target must not be a symbolic link"
            }
            check(!rootfsExisted || rootfsDir.deleteRecursively()) {
                "Failed to delete linux distro rootfs"
            }
            val removedRegistry = registry.remove(distroId)
            rootfsExisted || removedRegistry
        }
    }

    fun refreshInstalledFromDisk(): List<InstalledLinuxDistro> {
        val installedRoot = layout.installedRootfsDir
        val installations = installedRoot.listFiles()
            .orEmpty()
            .filter { rootfsDir -> rootfsProbe.hasBootShell(rootfsDir) }
            .mapNotNull { rootfsDir -> metadataStore.read(rootfsDir) ?: inferInstallation(rootfsDir) }
            .sortedBy { installation -> installation.displayName.lowercase() }
        registry.replaceAll(installations)
        return installations
    }

    private fun inferInstallation(rootfsDir: File): InstalledLinuxDistro? {
        val distroId = rootfsDir.name.takeIf { it.isSafeId() } ?: return null
        val distro = catalog.resolveDistro(distroId)
        val timestamp = rootfsDir.lastModified().takeIf { it > 0L } ?: clock()
        return InstalledLinuxDistro(
            distroId = distroId,
            releaseId = distro?.defaultReleaseId,
            architecture = null,
            displayName = distro?.displayName ?: distroId,
            packageManager = distro?.packageManager ?: DistroPackageManager.UNKNOWN,
            rootfsPath = rootfsDir.absolutePath,
            archivePath = null,
            checksum = null,
            installedAtEpochMillis = timestamp,
            updatedAtEpochMillis = timestamp,
        )
    }
}
