package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File
import java.util.Properties

/**
 * Shared verification logic for `tina-toolchain` assets declared under
 * `app/src/<flavor>/assets/tina-toolchain/current.properties`.
 *
 * The logic is extracted from the legacy inline task previously defined in
 * `app/build.gradle.kts` so that it can be reused from a convention plugin
 * without coupling build script logic to Android DSL internals.
 */
internal object TinaToolchainAssetsVerification {

    /**
     * ABI flavor names that Tina supports end-to-end today. Kept in the
     * convention plugin so that tools / plugins depending on the same
     * matrix can share a single source of truth.
     */
    val supportedDevAbis: Set<String> = setOf("arm64", "x86_64")

    /**
     * Resolve the configured dev ABI from Gradle properties. Defaults to
     * `arm64` to match the historic behaviour of `app/build.gradle.kts`.
     */
    fun resolveLocalDevAbi(project: Project): String {
        val raw = project.providers.gradleProperty("tina.devAbi").orNull?.trim().orEmpty()
        val abi = if (raw.isBlank()) "arm64" else raw
        require(abi in supportedDevAbis) {
            "Unsupported -Ptina.devAbi=$abi. Expected one of $supportedDevAbis."
        }
        return abi
    }

    /**
     * Resolve whether the current invocation needs to cover all ABI
     * flavors. An explicit `-Ptina.allAbi=true/false` wins first so CI
     * release matrix jobs can intentionally build one ABI per runner.
     * Without an explicit value, CI and `AllAbi` tasks still enable all
     * supported ABI flavors.
     */
    fun resolveBuildAllAbiRequested(project: Project): Boolean {
        project.providers.gradleProperty("tina.allAbi").orNull?.let {
            return project.resolveBooleanGradleProperty("tina.allAbi", default = false)
        }
        if (System.getenv("CI")?.equals("true", ignoreCase = true) == true) return true
        return project.gradle.startParameter.taskNames.any { it.contains("AllAbi", ignoreCase = true) }
    }

    /**
     * Perform the actual verification. Throws a [GradleException] with a
     * descriptive message when the declared assets are missing or the
     * spec file is malformed.
     */
    fun verify(project: Project, logger: Logger, buildAllAbiRequested: Boolean, localDevAbi: String) {
        val expectedFlavorSpecs = (if (buildAllAbiRequested) supportedDevAbis else setOf(localDevAbi))
            .sorted()
            .associateWith { flavor ->
                project.file("src/$flavor/assets/tina-toolchain/current.properties")
            }

        val missingFlavorSpecs = expectedFlavorSpecs.filterValues { !it.isFile }.toSortedMap()
        if (missingFlavorSpecs.isNotEmpty()) {
            val details = missingFlavorSpecs.entries.joinToString("\n") { (flavor, file) ->
                " - $flavor: ${file.relativeTo(project.projectDir).invariantSeparatorsPath}"
            }
            throw GradleException(
                "Missing tina-toolchain spec for required ABI flavor(s):\n$details\n" +
                    "Fix: generate/sync toolchain assets for the missing ABI before building.",
            )
        }

        val srcRoot = project.file("src")
        val specFiles = if (srcRoot.isDirectory) {
            srcRoot.walkTopDown()
                .filter {
                    it.isFile &&
                        it.name == "current.properties" &&
                        it.parentFile?.name == "tina-toolchain" &&
                        it.parentFile?.parentFile?.name == "assets"
                }
                .sortedBy { it.invariantSeparatorsPath }
                .toList()
        } else {
            emptyList()
        }

        if (specFiles.isEmpty()) {
            logger.lifecycle(
                "No tina-toolchain spec files found under app/src/*/assets/tina-toolchain; skipping.",
            )
            return
        }

        for (specFile in specFiles) {
            verifySingleSpec(project, logger, specFile)
        }
    }

    private fun verifySingleSpec(project: Project, logger: Logger, specFile: File) {
        val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
        val relParts = relSpec.split('/')
        val flavorName = relParts.getOrNull(1) ?: "unknown"
        val assetsDir = specFile.parentFile
        val legacyArchiveDir = assetsDir.resolve("archive")
        val legacyArchiveFile = if (legacyArchiveDir.isDirectory) {
            legacyArchiveDir.walkTopDown().firstOrNull { it.isFile }
        } else {
            null
        }
        if (legacyArchiveFile != null) {
            val relLegacyDir = legacyArchiveDir.relativeTo(project.projectDir).invariantSeparatorsPath
            val relLegacyFile = legacyArchiveFile.relativeTo(project.projectDir).invariantSeparatorsPath
            throw GradleException(
                "Found legacy toolchain archive under assets: $relLegacyDir\n" +
                    "Sample file: $relLegacyFile\n" +
                    "Move archived versions out of app/src to avoid packaging into APK " +
                    "(recommended: app/.local/toolchain-archive/$flavorName).",
            )
        }

        val props = Properties()
        specFile.inputStream().use { props.load(it) }

        val version = readTrimmed(props, "version")
        val arch = readTrimmed(props, "arch")
        val full = readTrimmed(props, "full")
        val base = readTrimmed(props, "base")
        val tools = readTrimmed(props, "tools")
        val sha = readTrimmed(props, "sha256")

        if (version == null || arch == null || (full == null && base == null)) {
            throw GradleException(
                "Invalid tina-toolchain spec (missing version/arch and one of full/base): $relSpec",
            )
        }

        val shaFile = sha?.let { assetsDir.resolve(it) }
        val shaEntries = shaFile?.let { parseSha256File(it) }
        if (sha != null && shaEntries != null && shaEntries.isEmpty()) {
            throw GradleException("sha256 file is empty/unreadable: $relSpec (sha256=$sha)")
        }

        val mainArchive = full ?: base!!
        val mainKey = if (full != null) "full" else "base"

        // 主包（full/base）必填；tools/sha256 可选。
        requireAssetFile(project, specFile, assetsDir, flavorName, mainKey, mainArchive, shaEntries)
        if (full == null) {
            tools?.let {
                requireAssetFile(project, specFile, assetsDir, flavorName, "tools", it, shaEntries)
            }
        } else if (tools != null) {
            logger.warn(
                "tina-toolchain spec has both full and tools; tools will be ignored at runtime (spec=$relSpec)",
            )
        }
        sha?.let {
            requireAssetFile(project, specFile, assetsDir, flavorName, "sha256", it, null)
        }

        // Basic sanity check to reduce accidental version mismatches.
        if (!mainArchive.contains("v$version")) {
            logger.warn(
                "tina-toolchain spec version mismatch? version=$version but archive=$mainArchive (spec=$relSpec)",
            )
        }
    }

    private fun requireAssetFile(
        project: Project,
        specFile: File,
        assetsDir: File,
        flavorName: String,
        key: String,
        fileName: String,
        shaEntries: Set<String>?,
    ) {
        val f = assetsDir.resolve(fileName)
        if (!f.isFile) {
            val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
            val relExpected = f.relativeTo(project.projectDir).invariantSeparatorsPath
            val hint = "Fix: sync assets from build/tina-toolchain/release into $assetsDir " +
                "(try: pwsh -NoProfile -ExecutionPolicy Bypass -File tools/sync-tina-toolchain-assets.ps1 -Abi $flavorName -Clean)"
            throw GradleException(
                "Missing tina-toolchain asset for '$key' in $relSpec: expected $relExpected\n$hint",
            )
        }
        if (!fileName.endsWith(".tar.xz") && (key == "full" || key == "base" || key == "tools")) {
            val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
            throw GradleException(
                "Unsupported tina-toolchain archive extension in $relSpec ($key=$fileName). " +
                    "Only .tar.xz is supported at runtime.",
            )
        }
        if (shaEntries != null && shaEntries.isNotEmpty() && !shaEntries.contains(fileName)) {
            val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
            throw GradleException(
                "sha256 file does not contain an entry for $fileName (spec: $relSpec). " +
                    "Re-generate/check the sha256 asset.",
            )
        }
    }

    private fun readTrimmed(props: Properties, key: String): String? {
        return props.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseSha256File(shaFile: File): Set<String> {
        if (!shaFile.isFile) return emptySet()
        return shaFile.readLines(Charsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }
            .toSet()
    }
}
