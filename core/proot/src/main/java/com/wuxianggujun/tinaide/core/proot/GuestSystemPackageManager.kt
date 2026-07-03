package com.wuxianggujun.tinaide.core.proot

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult

internal data class GuestPackageCommandGroup(
    val id: String,
    val commands: List<String>,
    val packageCandidates: List<String>,
    val required: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Package command group id must not be blank" }
        require(commands.isNotEmpty()) { "Package command group must define commands: $id" }
        require(packageCandidates.isNotEmpty()) { "Package command group must define package candidates: $id" }
    }
}

internal data class GuestPackageManagerSpec(
    val packageManager: RootfsPackageManager,
    val updateCommand: List<String>,
    val installCommand: (packages: List<String>, force: Boolean) -> List<String>,
    val removeCommand: (packages: List<String>) -> List<String>,
    val installedVersionsCommand: (packages: List<String>) -> List<String>,
    val packageManagerCommands: List<String>,
    val versionCommand: List<String> = packageManagerCommands.firstOrNull()?.let { command ->
        listOf(command, "--version")
    }.orEmpty(),
    val environment: Map<String, String> = emptyMap(),
    val bootstrapCommandGroups: List<GuestPackageCommandGroup> = emptyList(),
)

internal object GuestPackageManagerSpecs {
    fun resolve(packageManager: RootfsPackageManager): GuestPackageManagerSpec? = specs[packageManager]

    fun require(packageManager: RootfsPackageManager): GuestPackageManagerSpec = resolve(packageManager) ?: error("Unsupported package manager: $packageManager")

    private val aptEnvironment = mapOf("DEBIAN_FRONTEND" to "noninteractive")

    private fun defaultBootstrapGroups(
        xzPackage: String,
        caCertificatesPackage: String = "ca-certificates",
    ): List<GuestPackageCommandGroup> = listOf(
        GuestPackageCommandGroup(
            id = "bash",
            commands = listOf("bash"),
            packageCandidates = listOf("bash"),
        ),
        GuestPackageCommandGroup(
            id = "curl",
            commands = listOf("curl"),
            packageCandidates = listOf("curl"),
        ),
        GuestPackageCommandGroup(
            id = "tar",
            commands = listOf("tar"),
            packageCandidates = listOf("tar"),
        ),
        GuestPackageCommandGroup(
            id = "xz",
            commands = listOf("xz"),
            packageCandidates = listOf(xzPackage),
        ),
        GuestPackageCommandGroup(
            id = "file",
            commands = listOf("file"),
            packageCandidates = listOf("file"),
        ),
        GuestPackageCommandGroup(
            id = "ca-certificates",
            commands = listOf("update-ca-certificates"),
            packageCandidates = listOf(caCertificatesPackage),
        ),
        GuestPackageCommandGroup(
            id = "proot",
            commands = listOf("proot"),
            packageCandidates = listOf("proot"),
            required = false,
        ),
    )

    private val specs = mapOf(
        RootfsPackageManager.APK to GuestPackageManagerSpec(
            packageManager = RootfsPackageManager.APK,
            updateCommand = listOf("/sbin/apk", "update"),
            installCommand = { packages, force ->
                if (force) listOf("/sbin/apk", "fix") + packages else listOf("/sbin/apk", "add", "--no-cache") + packages
            },
            removeCommand = { packages -> listOf("/sbin/apk", "del") + packages },
            installedVersionsCommand = { packages -> listOf("/sbin/apk", "info", "-v") + packages },
            packageManagerCommands = listOf("/sbin/apk"),
            bootstrapCommandGroups = defaultBootstrapGroups(xzPackage = "xz"),
        ),
        RootfsPackageManager.APT to GuestPackageManagerSpec(
            packageManager = RootfsPackageManager.APT,
            updateCommand = listOf("apt-get", "update"),
            installCommand = { packages, force ->
                if (force) listOf("apt-get", "install", "--reinstall", "-y") + packages else listOf("apt-get", "install", "-y") + packages
            },
            removeCommand = { packages -> listOf("apt-get", "remove", "-y") + packages },
            installedVersionsCommand = { packages -> listOf("dpkg-query", "-W", "-f=\${Package}\t\${Version}\n") + packages },
            packageManagerCommands = listOf("apt-get", "apt-cache"),
            environment = aptEnvironment,
            bootstrapCommandGroups = defaultBootstrapGroups(xzPackage = "xz-utils"),
        ),
        RootfsPackageManager.PACMAN to GuestPackageManagerSpec(
            packageManager = RootfsPackageManager.PACMAN,
            updateCommand = listOf("pacman", "-Sy", "--noconfirm"),
            installCommand = { packages, force ->
                if (force) listOf("pacman", "-S", "--noconfirm") + packages else listOf("pacman", "-S", "--noconfirm", "--needed") + packages
            },
            removeCommand = { packages -> listOf("pacman", "-Rns", "--noconfirm") + packages },
            installedVersionsCommand = { packages -> listOf("pacman", "-Q") + packages },
            packageManagerCommands = listOf("pacman"),
        ),
        RootfsPackageManager.DNF to GuestPackageManagerSpec(
            packageManager = RootfsPackageManager.DNF,
            updateCommand = listOf("dnf", "makecache"),
            installCommand = { packages, force ->
                if (force) listOf("dnf", "reinstall", "-y") + packages else listOf("dnf", "install", "-y") + packages
            },
            removeCommand = { packages -> listOf("dnf", "remove", "-y") + packages },
            installedVersionsCommand = { packages -> listOf("rpm", "-q", "--queryformat", "%{NAME}\t%{VERSION}-%{RELEASE}\n") + packages },
            packageManagerCommands = listOf("dnf", "rpm"),
        ),
    )
}
object GuestSystemPackageManager {

    suspend fun updateIndex(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        timeoutMs: Long,
    ): LinuxExecutionResult {
        val spec = GuestPackageManagerSpecs.resolve(packageManager) ?: return unsupportedResult(packageManager)
        return linuxEnvironment.execute(
            command = spec.updateCommand,
            workDir = "/",
            env = spec.environment,
            timeout = timeoutMs,
        )
    }

    suspend fun installPackages(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        packages: List<String>,
        timeoutMs: Long,
        force: Boolean = false,
    ): LinuxExecutionResult {
        require(packages.isNotEmpty()) { "packages must not be empty" }
        val spec = GuestPackageManagerSpecs.resolve(packageManager) ?: return unsupportedResult(packageManager)
        return linuxEnvironment.execute(
            command = spec.installCommand(packages, force),
            workDir = "/",
            env = spec.environment,
            timeout = timeoutMs,
        )
    }

    suspend fun removePackages(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        packages: List<String>,
        timeoutMs: Long,
    ): LinuxExecutionResult {
        require(packages.isNotEmpty()) { "packages must not be empty" }
        val spec = GuestPackageManagerSpecs.resolve(packageManager) ?: return unsupportedResult(packageManager)
        return linuxEnvironment.execute(
            command = spec.removeCommand(packages),
            workDir = "/",
            env = spec.environment,
            timeout = timeoutMs,
        )
    }

    suspend fun queryInstalledVersions(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        packages: List<String>,
        timeoutMs: Long = 20_000L,
    ): Map<String, String?> {
        val requested = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requested.isEmpty()) return emptyMap()

        val spec = GuestPackageManagerSpecs.resolve(packageManager)
            ?: return requested.associateWith { null }
        val result = linuxEnvironment.execute(
            command = spec.installedVersionsCommand(requested),
            workDir = "/",
            timeout = timeoutMs,
            env = spec.environment,
        )

        val versions = mutableMapOf<String, String?>()
        result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                when (packageManager) {
                    RootfsPackageManager.APK -> parseApkLine(line, requested, versions)
                    RootfsPackageManager.APT,
                    RootfsPackageManager.PACMAN,
                    RootfsPackageManager.DNF -> parseTabOrSpaceSeparatedLine(line, versions)
                    RootfsPackageManager.UNKNOWN -> Unit
                }
            }

        requested.forEach { pkg -> versions.putIfAbsent(pkg, null) }
        return versions
    }

    suspend fun areCommandGroupsAvailable(
        linuxEnvironment: LinuxEnvironment,
        commandGroups: List<List<String>>,
        timeoutMs: Long = 10_000L,
    ): Boolean = findMissingCommandGroups(
        linuxEnvironment = linuxEnvironment,
        commandGroups = commandGroups,
        timeoutMs = timeoutMs,
    ).isEmpty()

    suspend fun findMissingCommandGroups(
        linuxEnvironment: LinuxEnvironment,
        commandGroups: List<List<String>>,
        timeoutMs: Long = 10_000L,
    ): List<List<String>> {
        val normalizedGroups = commandGroups
            .map { group -> group.map(String::trim).filter(String::isNotEmpty).distinct() }
            .filter(List<String>::isNotEmpty)

        if (normalizedGroups.isEmpty()) return emptyList()

        val missingGroups = mutableListOf<List<String>>()
        for (group in normalizedGroups) {
            if (!isCommandGroupAvailable(linuxEnvironment, group, timeoutMs)) {
                missingGroups += group
            }
        }
        return missingGroups
    }

    fun isPackageManagerSupported(packageManager: RootfsPackageManager): Boolean = GuestPackageManagerSpecs.resolve(packageManager) != null

    internal fun bootstrapCommandGroups(packageManager: RootfsPackageManager): List<GuestPackageCommandGroup> = GuestPackageManagerSpecs.resolve(packageManager)?.bootstrapCommandGroups.orEmpty()

    internal suspend fun findMissingPackageManagerCommands(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        timeoutMs: Long = 10_000L,
    ): List<String> {
        val spec = GuestPackageManagerSpecs.resolve(packageManager) ?: return emptyList()
        return spec.packageManagerCommands
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filter { command ->
                !isCommandAvailable(
                    linuxEnvironment = linuxEnvironment,
                    command = command,
                    timeoutMs = timeoutMs,
                )
            }
    }

    internal suspend fun probePackageManagerVersion(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        timeoutMs: Long = 10_000L,
    ): LinuxExecutionResult {
        val spec = GuestPackageManagerSpecs.resolve(packageManager) ?: return unsupportedResult(packageManager)
        return linuxEnvironment.execute(
            command = spec.versionCommand,
            workDir = "/",
            env = spec.environment,
            timeout = timeoutMs,
        )
    }

    internal suspend fun isCommandGroupAvailable(
        linuxEnvironment: LinuxEnvironment,
        commands: List<String>,
        timeoutMs: Long = 10_000L,
    ): Boolean {
        val normalized = commands.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return false
        return normalized.any { command ->
            isCommandAvailable(
                linuxEnvironment = linuxEnvironment,
                command = command,
                timeoutMs = timeoutMs,
            )
        }
    }

    internal suspend fun packageExists(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        packageName: String,
        timeoutMs: Long = 30_000L,
    ): Boolean {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return false
        val spec = GuestPackageManagerSpecs.resolve(packageManager) ?: return false
        val result = when (packageManager) {
            RootfsPackageManager.APK -> linuxEnvironment.execute(
                command = listOf("/sbin/apk", "search", "-x", normalized),
                workDir = "/",
                timeout = timeoutMs,
            )
            RootfsPackageManager.APT -> linuxEnvironment.execute(
                command = listOf("/bin/sh", "-lc", "apt-cache show ${shellEscape(normalized)} >/dev/null 2>&1"),
                workDir = "/",
                env = spec.environment,
                timeout = timeoutMs,
            )
            RootfsPackageManager.PACMAN -> linuxEnvironment.execute(
                command = if (normalized == "base-devel") {
                    listOf("pacman", "-Sg", normalized)
                } else {
                    listOf("pacman", "-Si", normalized)
                },
                workDir = "/",
                timeout = timeoutMs,
            )
            RootfsPackageManager.DNF -> linuxEnvironment.execute(
                command = listOf("dnf", "info", normalized),
                workDir = "/",
                timeout = timeoutMs,
            )
            RootfsPackageManager.UNKNOWN -> return false
        }

        return when (packageManager) {
            RootfsPackageManager.APK ->
                result.isSuccess &&
                    result.stdout.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .any { line -> line.isApkExactSearchMatch(normalized) }
            else -> result.exitCode == 0
        }
    }
    private fun parseApkLine(
        line: String,
        requested: List<String>,
        versions: MutableMap<String, String?>,
    ) {
        for (pkg in requested) {
            if (line.startsWith("$pkg-")) {
                versions[pkg] = line.removePrefix("$pkg-")
                return
            }
        }
    }

    private fun String.isApkExactSearchMatch(packageName: String): Boolean {
        val token = substringBefore(" - ").substringBefore('\t').substringBefore(' ')
        return token == packageName || token.startsWith("$packageName-")
    }

    private fun parseTabOrSpaceSeparatedLine(
        line: String,
        versions: MutableMap<String, String?>,
    ) {
        val parts = line.split('\t', ' ', limit = 2).filter { it.isNotBlank() }
        if (parts.size >= 2) {
            versions[parts[0]] = parts[1].trim()
        }
    }

    private fun unsupportedResult(packageManager: RootfsPackageManager): LinuxExecutionResult = LinuxExecutionResult(
        exitCode = -1,
        stdout = "",
        stderr = "Unsupported package manager: $packageManager",
        durationMs = 0L,
        timedOut = false,
    )

    private suspend fun isCommandAvailable(
        linuxEnvironment: LinuxEnvironment,
        command: String,
        timeoutMs: Long,
    ): Boolean {
        val probeCommand = buildCommandAvailabilityProbe(command) ?: return false

        return linuxEnvironment.execute(
            command = probeCommand,
            workDir = "/",
            timeout = timeoutMs,
        ).exitCode == 0
    }

    private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
