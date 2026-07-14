package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InstalledLinuxDistro(
    val distroId: String,
    val releaseId: String? = null,
    val architecture: DistroArchitecture? = null,
    val displayName: String,
    val packageManager: DistroPackageManager,
    val rootfsPath: String,
    val archivePath: String? = null,
    val checksum: DistroChecksum? = null,
    val installedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = installedAtEpochMillis,
    val source: String = SOURCE_SELF_HOSTED,
) {
    init {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        require(releaseId == null || releaseId.isSafeId()) { "Unsafe release id: $releaseId" }
        require(displayName.isNotBlank()) { "Installed distro display name must not be blank." }
        require(rootfsPath.isNotBlank()) { "Rootfs path must not be blank." }
        require(installedAtEpochMillis > 0L) { "Install timestamp must be positive." }
        require(updatedAtEpochMillis >= installedAtEpochMillis) {
            "Update timestamp must not be earlier than install timestamp."
        }
    }

    val profileId: String get() = LinuxDistroIds.profileIdFor(distroId)

    companion object {
        const val SOURCE_SELF_HOSTED = "tinaide-linux-distro"
    }
}

object LinuxDistroIds {
    const val PROFILE_PREFIX = "linux-distro:"

    fun profileIdFor(distroId: String): String {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        return "$PROFILE_PREFIX$distroId"
    }
}

@Serializable
data class LinuxDistroRegistrySnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val installations: List<InstalledLinuxDistro> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported linux distro registry schema: $schemaVersion"
        }
        require(installations.map { it.distroId }.distinct().size == installations.size) {
            "Installed distro ids must be unique."
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

interface LinuxDistroInstallationRegistry {
    fun list(): List<InstalledLinuxDistro>
    fun find(distroId: String): InstalledLinuxDistro?
    fun upsert(installation: InstalledLinuxDistro): InstalledLinuxDistro
    fun remove(distroId: String): Boolean
    fun replaceAll(installations: List<InstalledLinuxDistro>)
}

class FileLinuxDistroInstallationRegistry(
    private val registryFile: File,
) : LinuxDistroInstallationRegistry {
    private val registryLock = lockFor(registryFile)

    override fun list(): List<InstalledLinuxDistro> = synchronized(registryLock) {
        readSnapshot().installations.sortedBy { installation -> installation.displayName.lowercase() }
    }

    override fun find(distroId: String): InstalledLinuxDistro? = synchronized(registryLock) {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        readSnapshot().installations.firstOrNull { installation -> installation.distroId == distroId }
    }

    override fun upsert(installation: InstalledLinuxDistro): InstalledLinuxDistro = synchronized(registryLock) {
        val snapshot = readSnapshot()
        val merged = snapshot.installations
            .filterNot { current -> current.distroId == installation.distroId } + installation
        writeSnapshot(snapshot.copy(installations = merged.sortedBy { it.displayName.lowercase() }))
        installation
    }

    override fun remove(distroId: String): Boolean = synchronized(registryLock) {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        val snapshot = readSnapshot()
        val next = snapshot.installations.filterNot { installation -> installation.distroId == distroId }
        if (next.size == snapshot.installations.size) return@synchronized false
        writeSnapshot(snapshot.copy(installations = next))
        true
    }

    override fun replaceAll(installations: List<InstalledLinuxDistro>) {
        synchronized(registryLock) {
            writeSnapshot(LinuxDistroRegistrySnapshot(installations = installations.sortedBy { it.displayName.lowercase() }))
        }
    }

    private fun readSnapshot(): LinuxDistroRegistrySnapshot {
        if (!registryFile.isFile) return LinuxDistroRegistrySnapshot()
        return json.decodeFromString(
            LinuxDistroRegistrySnapshot.serializer(),
            registryFile.readText(Charsets.UTF_8),
        )
    }

    private fun writeSnapshot(snapshot: LinuxDistroRegistrySnapshot) {
        val parentDirectory = registryFile.parentFile ?: File(".")
        parentDirectory.mkdirs()
        val tempFile = Files.createTempFile(
            parentDirectory.toPath(),
            "${registryFile.name}.",
            ".tmp",
        ).toFile()
        try {
            tempFile.writeText(json.encodeToString(LinuxDistroRegistrySnapshot.serializer(), snapshot), Charsets.UTF_8)
            try {
                Files.move(
                    tempFile.toPath(),
                    registryFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), registryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile.toPath())
        }
    }

    private companion object {
        val registryLocks = ConcurrentHashMap<String, Any>()

        fun lockFor(file: File): Any {
            val normalizedPath = file.toPath().toAbsolutePath().normalize().toString()
            return registryLocks.computeIfAbsent(normalizedPath) { Any() }
        }

        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

interface LinuxDistroInstallMetadataStore {
    fun read(rootfsDir: File): InstalledLinuxDistro?
    fun write(rootfsDir: File, installation: InstalledLinuxDistro)
}

class JsonLinuxDistroInstallMetadataStore(
    private val relativePath: String = DEFAULT_RELATIVE_PATH,
) : LinuxDistroInstallMetadataStore {
    override fun read(rootfsDir: File): InstalledLinuxDistro? {
        val metadataFile = File(rootfsDir, relativePath)
        if (!metadataFile.isFile) return null
        return json.decodeFromString(InstalledLinuxDistro.serializer(), metadataFile.readText(Charsets.UTF_8))
    }

    override fun write(rootfsDir: File, installation: InstalledLinuxDistro) {
        val metadataFile = File(rootfsDir, relativePath)
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText(json.encodeToString(InstalledLinuxDistro.serializer(), installation), Charsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_RELATIVE_PATH = ".tinaide/linux-distro.json"
        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

interface LinuxDistroRootfsProbe {
    fun hasBootShell(rootfsDir: File): Boolean
}

object BasicLinuxDistroRootfsProbe : LinuxDistroRootfsProbe {
    override fun hasBootShell(rootfsDir: File): Boolean = rootfsDir.isDirectory && SHELL_CANDIDATES.any { relativePath -> File(rootfsDir, relativePath).isFile }

    private val SHELL_CANDIDATES = listOf(
        "bin/sh",
        "usr/bin/sh",
        "bin/bash",
        "usr/bin/bash",
    )
}
