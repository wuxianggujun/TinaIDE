package com.wuxianggujun.tinaide.ui.sdl

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.ui.runtime.AndroidSystemLibraries
import com.wuxianggujun.tinaide.ui.runtime.NativeLibraryDependencyHints
import com.wuxianggujun.tinaide.ui.runtime.NativeLibraryDependencyReader
import java.io.File
import java.io.IOException
import timber.log.Timber

/**
 * SDL 运行时解析器：
 * - 从用户编译产物 .so 中尽力识别是否依赖 SDL2/SDL3。
 * - 无法从动态依赖识别版本时，允许静态链接 SDL 的主库继续尝试启动。
 * - 解析主库依赖并尽可能预加载可从已安装包目录定位到的动态库。
 */
object SdlRuntimeResolver {
    private const val TAG = "SdlRuntimeResolver"
    private const val INSTALL_DIR_NAME = "installed-packages"

    private val sdl2NamePattern = Regex("""^libSDL2\.so(?:\..+)?$""")
    private val sdl3NamePattern = Regex("""^libSDL3\.so(?:\..+)?$""")

    // OS 提供的 NDK 系统库统一走 AndroidSystemLibraries.ndkProvided，
    // 额外加上 libc++_shared.so：RUN 时由 sysroot 经 LD_LIBRARY_PATH 注入，无需预加载。
    private val systemLibraryNames = AndroidSystemLibraries.ndkProvided + "libc++_shared.so"

    data class SdlRuntimeSpec(
        val requiredSdlMajor: Int,
        val sdlLibraryPath: String,
        val preloadLibraryPaths: List<String>,
        val sdlPackageId: String? = null,
        val sdlPackageVersion: String? = null,
    )

    sealed class ResolveResult {
        data class Sdl(val spec: SdlRuntimeSpec) : ResolveResult()
        data object NonSdl : ResolveResult()
        data class Error(val message: String) : ResolveResult()
    }

    private data class ManagedAndroidPackage(
        val packageId: String,
        val version: String,
        val installedAt: Long,
        val runtimeLibDirs: List<File>,
    )

    private data class SdlLibrarySelection(
        val libraryFile: File,
        val runtimeLibDirs: List<File>,
        val packageId: String? = null,
        val packageVersion: String? = null,
    )

    fun resolve(
        context: Context,
        mainLibraryPath: String,
        extraRuntimeLibDirs: List<File> = emptyList(),
        allowUndetectedSdl: Boolean = false,
    ): ResolveResult {
        if (mainLibraryPath.isBlank()) {
            return ResolveResult.Error(Strings.sdl_runtime_error_main_library_missing.strOr(context))
        }

        val mainLibrary = File(mainLibraryPath)

        val projectRoot = resolveProjectRoot(mainLibrary.parentFile)
        val packagePaths = InstalledPackagePathResolver.resolve(
            context = context.applicationContext,
            projectRoot = projectRoot
        )
        val configuredRuntimeDirs = normalizeRuntimeDirs(
            extraRuntimeLibDirs + packagePaths.runtimeLibDirs
        )

        val neededLibraries = try {
            extractNeededLibraryNames(mainLibrary)
        } catch (error: IOException) {
            Timber.tag(TAG).e(error, "Failed to scan library: %s", mainLibrary.absolutePath)
            return ResolveResult.Error(
                Strings.sdl_runtime_error_scan_failed.strOr(
                    context,
                    mainLibrary.absolutePath,
                    error.message ?: "I/O error"
                )
            )
        }

        val requiresSdl2 = neededLibraries.any { sdl2NamePattern.matches(it) }
        val requiresSdl3 = neededLibraries.any { sdl3NamePattern.matches(it) }
        if (requiresSdl2 && requiresSdl3) {
            return ResolveResult.Error(Strings.sdl_runtime_error_conflicting_versions.strOr(context))
        }

        val detectedSdlMajor = when {
            requiresSdl3 -> 3
            requiresSdl2 -> 2
            else -> if (allowUndetectedSdl) null else return ResolveResult.NonSdl
        }

        val managedPackages = resolveManagedAndroidPackages(context.applicationContext)
        val configuredExistingRuntimeDirs = configuredRuntimeDirs.filter { it.isDirectory }
        val selectedRuntime = selectSdlRuntime(
            managedPackages = managedPackages,
            runtimeDirs = configuredExistingRuntimeDirs,
            detectedSdlMajor = detectedSdlMajor,
        ) ?: return ResolveResult.Error(
            if (detectedSdlMajor != null) {
                buildMissingSdlRuntimeMessage(
                    context = context,
                    requiredSdlMajor = detectedSdlMajor,
                    managedPackages = managedPackages
                )
            } else {
                Strings.sdl_runtime_error_no_available_runtime.strOr(context)
            }
        )
        val requiredSdlMajor = selectedRuntime.first
        val selectedSdlLibrary = selectedRuntime.second

        val runtimeDirs = linkedSetOf<File>().apply {
            mainLibrary.parentFile?.takeIf { it.isDirectory }?.let(::add)
            addAll(selectedSdlLibrary.runtimeLibDirs)
            selectedSdlLibrary.libraryFile.parentFile?.let { add(it) }
            addAll(configuredExistingRuntimeDirs)
        }.toList()

        val runtimeIndex = buildRuntimeLibraryIndex(runtimeDirs)
        val preloadLibraries = resolvePreloadLibraries(
            runtimeIndex = runtimeIndex,
            neededLibraries = neededLibraries,
            mainLibrary = mainLibrary,
            sdlLibrary = selectedSdlLibrary.libraryFile
        )

        val packageTag = if (selectedSdlLibrary.packageId.isNullOrBlank()) {
            "external-runtime-scan"
        } else {
            "${selectedSdlLibrary.packageId}@${selectedSdlLibrary.packageVersion.orEmpty()}"
        }
        Timber.tag(TAG).i(
            "Detected SDL%d runtime: main=%s, sdl=%s, package=%s, preload=%d",
            requiredSdlMajor,
            mainLibrary.name,
            selectedSdlLibrary.libraryFile.name,
            packageTag,
            preloadLibraries.size
        )
        return ResolveResult.Sdl(
            SdlRuntimeSpec(
                requiredSdlMajor = requiredSdlMajor,
                sdlLibraryPath = selectedSdlLibrary.libraryFile.absolutePath,
                preloadLibraryPaths = preloadLibraries,
                sdlPackageId = selectedSdlLibrary.packageId,
                sdlPackageVersion = selectedSdlLibrary.packageVersion
            )
        )
    }

    private fun requiredSdlSoname(requiredSdlMajor: Int): String = if (requiredSdlMajor == 3) "libSDL3.so" else "libSDL2.so"

    private fun selectSdlRuntime(
        managedPackages: List<ManagedAndroidPackage>,
        runtimeDirs: List<File>,
        detectedSdlMajor: Int?,
    ): Pair<Int, SdlLibrarySelection>? {
        val candidateMajors = detectedSdlMajor?.let(::listOf) ?: listOf(3, 2)
        candidateMajors.forEach { major ->
            val selection = selectSdlLibraryFromManagedPackages(managedPackages, major)
                ?: selectSdlLibraryFromRuntimeDirs(runtimeDirs, major)
            if (selection != null) return major to selection
        }
        return null
    }

    private fun selectSdlLibraryFromRuntimeDirs(
        runtimeDirs: List<File>,
        requiredSdlMajor: Int,
    ): SdlLibrarySelection? {
        val requiredName = requiredSdlSoname(requiredSdlMajor)
        val matcher = if (requiredSdlMajor == 3) sdl3NamePattern else sdl2NamePattern

        runtimeDirs.forEach { dir ->
            val candidates = dir.listFiles { file ->
                file.isFile && matcher.matches(file.name)
            }?.sortedWith(
                compareBy<File>(
                    { it.name != requiredName },
                    { it.name.length },
                    { it.name }
                )
            ) ?: emptyList()

            val hit = candidates.firstOrNull()
            if (hit != null) {
                return SdlLibrarySelection(
                    libraryFile = hit,
                    runtimeLibDirs = listOf(dir),
                )
            }
        }
        return null
    }

    private fun selectSdlLibraryFromManagedPackages(
        managedPackages: List<ManagedAndroidPackage>,
        requiredSdlMajor: Int
    ): SdlLibrarySelection? {
        val requiredName = requiredSdlSoname(requiredSdlMajor)
        val matcher = if (requiredSdlMajor == 3) sdl3NamePattern else sdl2NamePattern
        val candidates = buildList {
            managedPackages.forEach { pkg ->
                pkg.runtimeLibDirs.forEach { dir ->
                    val files = dir.listFiles { file -> file.isFile && matcher.matches(file.name) }
                        ?.toList()
                        .orEmpty()
                    files.forEach { file ->
                        add(
                            SdlLibrarySelection(
                                libraryFile = file,
                                runtimeLibDirs = pkg.runtimeLibDirs,
                                packageId = pkg.packageId,
                                packageVersion = pkg.version
                            )
                        )
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null

        return candidates.sortedWith { left, right ->
            val leftExact = left.libraryFile.name == requiredName
            val rightExact = right.libraryFile.name == requiredName
            if (leftExact != rightExact) {
                return@sortedWith if (leftExact) -1 else 1
            }

            val preferredPackageId = "sdl$requiredSdlMajor"
            val leftPreferredPackage = left.packageId?.equals(preferredPackageId, ignoreCase = true) == true
            val rightPreferredPackage = right.packageId?.equals(preferredPackageId, ignoreCase = true) == true
            if (leftPreferredPackage != rightPreferredPackage) {
                return@sortedWith if (leftPreferredPackage) -1 else 1
            }

            val versionOrder = compareVersionLike(
                right.packageVersion.orEmpty(),
                left.packageVersion.orEmpty()
            )
            if (versionOrder != 0) return@sortedWith versionOrder

            val installOrder = compareInstallTimestamp(
                packageIdLeft = left.packageId,
                packageIdRight = right.packageId,
                managedPackages = managedPackages
            )
            if (installOrder != 0) return@sortedWith installOrder

            val nameOrder = left.libraryFile.name.length.compareTo(right.libraryFile.name.length)
            if (nameOrder != 0) return@sortedWith nameOrder

            left.libraryFile.name.compareTo(right.libraryFile.name, ignoreCase = true)
        }.firstOrNull()
    }

    private fun compareInstallTimestamp(
        packageIdLeft: String?,
        packageIdRight: String?,
        managedPackages: List<ManagedAndroidPackage>
    ): Int {
        val leftTs = managedPackages.firstOrNull { it.packageId == packageIdLeft }?.installedAt ?: 0L
        val rightTs = managedPackages.firstOrNull { it.packageId == packageIdRight }?.installedAt ?: 0L
        // 新安装优先
        return rightTs.compareTo(leftTs)
    }

    private fun compareVersionLike(left: String, right: String): Int {
        val tokenPattern = Regex("""\d+|[A-Za-z]+""")
        val leftTokens = tokenPattern.findAll(left).map { it.value }.toList()
        val rightTokens = tokenPattern.findAll(right).map { it.value }.toList()
        val maxSize = maxOf(leftTokens.size, rightTokens.size)

        for (i in 0 until maxSize) {
            val leftToken = leftTokens.getOrNull(i)
            val rightToken = rightTokens.getOrNull(i)
            if (leftToken == null && rightToken == null) break
            if (leftToken == null) return -1
            if (rightToken == null) return 1

            val leftNumber = leftToken.toLongOrNull()
            val rightNumber = rightToken.toLongOrNull()
            val order = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> 1
                rightNumber != null -> -1
                else -> leftToken.compareTo(rightToken, ignoreCase = true)
            }
            if (order != 0) return order
        }
        return left.compareTo(right, ignoreCase = true)
    }

    private fun resolveManagedAndroidPackages(context: Context): List<ManagedAndroidPackage> {
        val installRootDir = File(context.filesDir, INSTALL_DIR_NAME)
        if (!installRootDir.isDirectory) return emptyList()

        val installedPackages = runCatching {
            LocalInstallStateStore(context)
                .getAllInstalledPackages()
                .filter { it.platform == Platform.ANDROID }
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed to read installed package state")
            return emptyList()
        }

        if (installedPackages.isEmpty()) return emptyList()

        return installedPackages.mapNotNull { installed ->
            val packageRootDir = File(installRootDir, installed.packageId)
            if (!packageRootDir.isDirectory) return@mapNotNull null
            val runtimeLibDirs = collectRuntimeLibraryDirs(packageRootDir)
            if (runtimeLibDirs.isEmpty()) return@mapNotNull null

            ManagedAndroidPackage(
                packageId = installed.packageId,
                version = installed.version,
                installedAt = installed.installedAt,
                runtimeLibDirs = runtimeLibDirs
            )
        }
    }

    private fun collectRuntimeLibraryDirs(packageRootDir: File): List<File> {
        val dirs = linkedSetOf<File>()
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libRoot = File(packageRootDir, "lib")
        val libsRoot = File(packageRootDir, "libs")

        dirs += File(libRoot, deviceAbi)
        dirs += File(libsRoot, deviceAbi)
        dirs += libRoot
        dirs += libsRoot
        dirs += packageRootDir

        return dirs.filter { dir ->
            dir.isDirectory && hasAnySharedLibrary(dir)
        }
    }

    private fun normalizeRuntimeDirs(dirs: List<File>): List<File> = dirs.asSequence()
        .map { dir -> runCatching { dir.canonicalFile }.getOrDefault(dir.absoluteFile) }
        .distinctBy { it.absolutePath }
        .toList()

    private fun hasAnySharedLibrary(dir: File): Boolean = dir.listFiles { file ->
        file.isFile && file.name.contains(".so")
    }?.isNotEmpty() == true

    private fun buildMissingSdlRuntimeMessage(
        context: Context,
        requiredSdlMajor: Int,
        managedPackages: List<ManagedAndroidPackage>,
    ): String {
        val requiredSoname = requiredSdlSoname(requiredSdlMajor)
        val hintedPackages = managedPackages.filter { pkg ->
            inferSdlMajorFromPackage(pkg.packageId, pkg.version) == requiredSdlMajor
        }

        return if (hintedPackages.isNotEmpty()) {
            val packageText = hintedPackages
                .sortedWith(compareBy<ManagedAndroidPackage>({ it.packageId }, { it.version }))
                .joinToString(separator = ", ") { "${it.packageId}@${it.version}" }
            Strings.sdl_runtime_error_sdl_package_broken.strOr(
                context,
                requiredSdlMajor,
                packageText,
                requiredSoname
            )
        } else {
            Strings.sdl_runtime_error_sdl_not_found.strOr(
                context,
                requiredSdlMajor,
                requiredSoname
            )
        }
    }

    private fun inferSdlMajorFromPackage(packageId: String, version: String): Int? {
        val normalizedId = packageId.lowercase()
        return when {
            "sdl3" in normalizedId -> 3
            "sdl2" in normalizedId -> 2
            "sdl" in normalizedId -> version.substringBefore('.').toIntOrNull()
            else -> null
        }
    }

    private fun buildRuntimeLibraryIndex(runtimeDirs: List<File>): Map<String, File> {
        val index = linkedMapOf<String, File>()
        runtimeDirs.forEach { dir ->
            val files = dir.listFiles { file ->
                file.isFile && file.name.contains(".so")
            }?.sortedBy { it.name } ?: return@forEach

            files.forEach { file ->
                putRuntimeLibrary(index, file.name, file)
                canonicalSoName(file.name)?.let { canonical ->
                    putRuntimeLibrary(index, canonical, file)
                }
            }
        }
        return index
    }

    private fun putRuntimeLibrary(
        index: MutableMap<String, File>,
        libraryName: String,
        candidate: File,
    ) {
        val current = index[libraryName]
        if (
            current == null ||
            NativeLibraryDependencyHints.shouldPreferInstalledPackageCandidate(
                libraryName = libraryName,
                current = current,
                candidate = candidate,
            )
        ) {
            index[libraryName] = candidate
        }
    }

    internal fun resolvePreloadLibraries(
        runtimeIndex: Map<String, File>,
        neededLibraries: Set<String>,
        mainLibrary: File,
        sdlLibrary: File,
        dependencyReader: (File) -> Set<String> = NativeLibraryDependencyReader::readNeededLibraryNames,
    ): List<String> {
        val resolved = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val selectedSdlCanonicalName = canonicalSoName(sdlLibrary.name)

        fun visit(needed: String) {
            val canonical = canonicalSoName(needed)
            if (
                needed == mainLibrary.name ||
                needed == sdlLibrary.name ||
                (canonical != null && canonical == selectedSdlCanonicalName)
            ) {
                return
            }
            if (needed in systemLibraryNames || (canonical != null && canonical in systemLibraryNames)) {
                return
            }

            val resolvedFile = runtimeIndex[needed]
                ?: canonical?.let { runtimeIndex[it] }
                ?: return

            val absolutePath = resolvedFile.absolutePath
            if (absolutePath == mainLibrary.absolutePath || absolutePath == sdlLibrary.absolutePath) {
                return
            }
            if (absolutePath in visited || !visiting.add(absolutePath)) return

            runCatching { dependencyReader(resolvedFile) }
                .onFailure { error ->
                    Timber.tag(TAG).w(
                        error,
                        "Failed to inspect preload dependencies: %s",
                        resolvedFile.absolutePath,
                    )
                }
                .getOrDefault(emptySet())
                .sorted()
                .forEach(::visit)

            visiting.remove(absolutePath)
            visited += absolutePath
            resolved += absolutePath
        }

        neededLibraries.sorted().forEach(::visit)
        return resolved.toList()
    }

    @Throws(IOException::class)
    private fun extractNeededLibraryNames(library: File): Set<String> =
        NativeLibraryDependencyReader.readNeededLibraryNames(library)

    private fun canonicalSoName(name: String): String? {
        val markerIndex = name.indexOf(".so")
        if (markerIndex < 0) return null
        return name.substring(0, markerIndex + 3)
    }

    private fun resolveProjectRoot(startDir: File?): File? {
        var current = startDir
        while (current != null) {
            val metadataFile = File(File(current, ".tinaide"), "project.json")
            if (metadataFile.isFile) {
                return current
            }
            current = current.parentFile
        }
        return null
    }
}
