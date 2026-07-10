package com.wuxianggujun.tinaide.ui.runtime

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import java.io.File

object NativeLibraryDependencyHints {
    private const val INSTALL_DIR_NAME = "installed-packages"

    private val libraryPackageHints = mapOf(
        "libSDL2.so" to "sdl2",
        "libSDL3.so" to "sdl3",
        "libSDL3_image.so" to "sdl3-image",
        "libSDL3_mixer.so" to "sdl3-mixer",
        "libSDL3_net.so" to "sdl3-net",
        "libSDL3_ttf.so" to "sdl3-ttf",
        "libBox2D.so" to "box2d",
        "libbox2d.so" to "box2d",
        "libminiaudio.so" to "miniaudio",
        "libraylib.so" to "raylib"
    )

    fun inferPackageIds(
        libraryNames: List<String>,
        availablePackages: List<GUIPackage> = emptyList(),
        installedLibraryPackageIndex: Map<String, String> = emptyMap()
    ): List<String> {
        val packageIndex = installedLibraryPackageIndex.asSequence()
            .map { (libraryName, packageId) -> normalizeLibraryName(libraryName) to packageId.trim() }
            .filter { (_, packageId) -> packageId.isNotBlank() }
            .toMap()

        return normalizeLibraryNames(libraryNames).asSequence()
            .flatMap { libraryName ->
                sequence {
                    packageIndex[libraryName]?.let { yield(it) }
                    inferFromAvailablePackages(libraryName, availablePackages).forEach { yield(it) }
                    libraryPackageHints[libraryName]?.let { yield(it) }
                }
            }
            .distinct()
            .sorted()
            .toList()
    }

    internal fun shouldPreferInstalledPackageCandidate(
        libraryName: String,
        current: File,
        candidate: File,
    ): Boolean {
        val preferredPackageId = libraryPackageHints[normalizeLibraryName(libraryName)] ?: return false
        val currentPackageId = installedPackageId(current) ?: return false
        val candidatePackageId = installedPackageId(candidate) ?: return false
        return !currentPackageId.equals(preferredPackageId, ignoreCase = true) &&
            candidatePackageId.equals(preferredPackageId, ignoreCase = true)
    }

    fun buildInstalledLibraryPackageIndex(context: Context): Map<String, String> {
        val installRoot = File(context.filesDir, INSTALL_DIR_NAME)
        if (!installRoot.isDirectory) return emptyMap()

        val installedPackageIds = runCatching {
            LocalInstallStateStore(context.applicationContext)
                .getAllInstalledPackages()
                .asSequence()
                .filter { it.platform == Platform.ANDROID }
                .map { it.packageId.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
        if (installedPackageIds.isEmpty()) return emptyMap()

        val index = linkedMapOf<String, String>()
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        installedPackageIds.sorted().forEach { packageId ->
            val packageDir = File(installRoot, packageId)
            if (!packageDir.isDirectory) return@forEach
            val runtimeDirs = listOf(
                File(packageDir, "lib/$deviceAbi"),
                File(packageDir, "libs/$deviceAbi"),
                File(packageDir, "lib"),
                File(packageDir, "libs"),
                packageDir,
            )
            runtimeDirs.filter(File::isDirectory).forEach { runtimeDir ->
                runtimeDir.listFiles { library ->
                    library.isFile && library.name.contains(".so")
                }?.forEach { library ->
                        val normalizedName = normalizeLibraryName(library.name)
                        if (normalizedName.isNotBlank()) {
                            val currentPackageId = index[normalizedName]
                            val preferredPackageId = libraryPackageHints[normalizedName]
                            if (
                                currentPackageId == null ||
                                packageId.equals(preferredPackageId, ignoreCase = true)
                            ) {
                                index[normalizedName] = packageId
                            }
                        }
                    }
            }
        }
        return index
    }

    fun filterUnresolvedLibraries(
        missingLibraries: List<String>,
        providedLibraries: List<File>
    ): List<String> {
        if (missingLibraries.isEmpty() || providedLibraries.isEmpty()) {
            return normalizeLibraryNames(missingLibraries)
        }

        val providedNames = providedLibraries.asSequence()
            .flatMap { library ->
                sequenceOf(library.name, normalizeLibraryName(library.name))
            }
            .toSet()

        return normalizeLibraryNames(missingLibraries)
            .filterNot { missing -> missing in providedNames }
    }

    fun buildMissingLibrariesMessage(
        context: Context,
        missingLibraries: List<String>,
        includeApkImportHint: Boolean = false,
        suggestedPackageIds: List<String>? = null
    ): String {
        val normalizedLibraries = normalizeLibraryNames(missingLibraries)
        if (normalizedLibraries.isEmpty()) return ""

        val missingText = Strings.native_library_missing_libraries_message.strOr(
            context,
            normalizedLibraries.joinToString(", ")
        )
        val packageIds = suggestedPackageIds ?: inferPackageIds(
            libraryNames = normalizedLibraries,
            installedLibraryPackageIndex = buildInstalledLibraryPackageIndex(context)
        )
        val hint = when {
            packageIds.isNotEmpty() && includeApkImportHint ->
                Strings.native_library_missing_libraries_package_or_import_hint.strOr(
                    context,
                    packageIds.joinToString(", ")
                )
            packageIds.isNotEmpty() ->
                Strings.native_library_missing_libraries_package_hint.strOr(
                    context,
                    packageIds.joinToString(", ")
                )
            includeApkImportHint ->
                Strings.native_library_missing_libraries_generic_or_import_hint.strOr(context)
            else ->
                Strings.native_library_missing_libraries_generic_hint.strOr(context)
        }
        return listOf(missingText, hint).joinToString("\n")
    }

    private fun inferFromAvailablePackages(
        libraryName: String,
        availablePackages: List<GUIPackage>
    ): List<String> {
        if (availablePackages.isEmpty()) return emptyList()

        val libraryKeys = libraryPackageKeys(libraryName)
        if (libraryKeys.isEmpty()) return emptyList()

        val scored = availablePackages.mapNotNull { pkg ->
            val score = packageMatchScore(pkg, libraryKeys)
            if (score > 0) pkg.id to score else null
        }
        val maxScore = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored
            .filter { it.second == maxScore }
            .map { it.first }
            .distinct()
    }

    private fun packageMatchScore(pkg: GUIPackage, libraryKeys: Set<String>): Int {
        val compactId = compactKey(pkg.id)
        val compactName = compactKey(pkg.name)
        val compactCategory = compactKey(pkg.category.orEmpty())
        val compactDescription = compactKey(pkg.description.orEmpty())

        return libraryKeys.maxOf { key ->
            when {
                compactId == key -> 100
                compactName == key -> 95
                compactId.startsWith(key) -> 70
                compactName.startsWith(key) -> 65
                key.length >= 4 && compactId.contains(key) -> 55
                key.length >= 4 && compactName.contains(key) -> 50
                key.length >= 4 && compactCategory.contains(key) -> 30
                key.length >= 4 && compactDescription.contains(key) -> 20
                else -> 0
            }
        }
    }

    private fun libraryPackageKeys(libraryName: String): Set<String> {
        val normalized = normalizeLibraryName(libraryName)
        val withoutPrefix = normalized
            .removePrefix("lib")
            .removeSuffix(".so")
        return setOf(
            compactKey(withoutPrefix),
            compactKey(withoutPrefix.replace('_', '-')),
            compactKey(withoutPrefix.replace('_', ' '))
        ).filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun normalizeLibraryNames(libraryNames: List<String>): List<String> = libraryNames.asSequence()
        .map(::normalizeLibraryName)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .toList()

    internal fun normalizeLibraryName(name: String): String {
        val trimmed = name.trim()
        val markerIndex = trimmed.indexOf(".so")
        if (markerIndex < 0) return trimmed
        return trimmed.substring(0, markerIndex + 3)
    }

    private fun installedPackageId(library: File): String? {
        var current = library.parentFile
        while (current != null) {
            if (current.parentFile?.name == INSTALL_DIR_NAME) {
                return current.name
            }
            current = current.parentFile
        }
        return null
    }

    private fun compactKey(value: String): String = value.lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' }
}
