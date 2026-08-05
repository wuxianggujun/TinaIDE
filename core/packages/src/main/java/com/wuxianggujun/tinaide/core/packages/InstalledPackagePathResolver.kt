package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import timber.log.Timber

/**
 * 扫描已安装的 Download 类型包，收集 include / lib 路径。
 *
 * 约定：包解压后若包含 `include/` 子目录，则视为可提供头文件；
 * 若包含 `lib/` 子目录，则视为可提供库文件。
 */
object InstalledPackagePathResolver {

    private const val TAG = "PkgPathResolver"
    private const val INSTALL_DIR_NAME = "installed-packages"
    private val SHARED_LIBRARY_PATTERN = Regex("""^lib(.+?)\.so(?:\..+)?$""")
    private val STATIC_LIBRARY_PATTERN = Regex("""^lib(.+?)\.a$""")
    private val EXCLUDED_LIBRARY_SUFFIXES = listOf("_test", "-test", "_tests", "-tests")

    private enum class LibraryKind {
        SHARED,
        STATIC
    }

    private data class LibraryCandidate(
        val name: String,
        val kind: LibraryKind
    )

    data class PackagePaths(
        /** 所有已安装包中存在的 include 目录 */
        val includeDirs: List<File>,
        /** 所有已安装包中存在的 lib 目录 */
        val libDirs: List<File>,
        /** 包根目录列表（供 CMAKE_PREFIX_PATH 使用） */
        val prefixDirs: List<File>,
        /** pkg-config 路径列表（供 PKG_CONFIG_PATH 使用） */
        val pkgConfigDirs: List<File>,
        /** 自动发现的可链接库名（不含 lib 前缀和扩展名） */
        val linkLibraries: List<String>,
        /** 运行时动态库目录（供 LD_LIBRARY_PATH 使用） */
        val runtimeLibDirs: List<File>
    ) {
        val isEmpty: Boolean
            get() = includeDirs.isEmpty() &&
                libDirs.isEmpty() &&
                prefixDirs.isEmpty() &&
                pkgConfigDirs.isEmpty() &&
                linkLibraries.isEmpty() &&
                runtimeLibDirs.isEmpty()
    }

    private data class ProjectDependencyDirs(
        val includeDirs: List<File>,
        val libDirs: List<File>,
        val runtimeDirs: List<File>
    )

    /**
     * 扫描 installed-packages 目录，返回所有有效的 include/lib 路径。
     */
    fun resolve(context: Context, projectRoot: File? = null): PackagePaths {
        val deviceAbi = PackageAbiCompatibility.currentAppAbi(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            supportedAbis = Build.SUPPORTED_ABIS,
        )

        val installDir = File(context.filesDir, INSTALL_DIR_NAME)
        val packageDirs = if (installDir.isDirectory) {
            resolveManagedPackageDirs(context, installDir)
        } else {
            emptyList()
        }

        val includeDirs = linkedSetOf<File>()
        val libDirs = linkedSetOf<File>()
        val prefixDirs = linkedSetOf<File>()
        val pkgConfigDirs = linkedSetOf<File>()
        val runtimeLibDirs = linkedSetOf<File>()
        val librariesByName = linkedMapOf<String, LibraryKind>()

        for (pkgDir in packageDirs) {
            val includeDir = File(pkgDir, "include")
            val libRootDir = File(pkgDir, "lib")
            val abiLibDir = File(libRootDir, deviceAbi)
            val effectiveLibDirs = buildList {
                if (abiLibDir.isDirectory) {
                    add(abiLibDir)
                }
                if (libRootDir.isDirectory) {
                    add(libRootDir)
                }
            }

            if (includeDir.isDirectory || effectiveLibDirs.isNotEmpty()) {
                prefixDirs += pkgDir
            }
            if (includeDir.isDirectory) {
                includeDirs += includeDir
            }

            effectiveLibDirs.forEach { dir ->
                libDirs += dir
                collectLibraryCandidates(
                    dir = dir,
                    librariesByName = librariesByName,
                    runtimeLibDirs = runtimeLibDirs
                )
            }

            val packagePkgConfigDir = File(pkgDir, "pkgconfig")
            if (packagePkgConfigDir.isDirectory) {
                pkgConfigDirs += packagePkgConfigDir
            }
            val rootPkgConfigDir = File(libRootDir, "pkgconfig")
            if (rootPkgConfigDir.isDirectory) {
                pkgConfigDirs += rootPkgConfigDir
            }
            val abiPkgConfigDir = File(abiLibDir, "pkgconfig")
            if (abiPkgConfigDir.isDirectory) {
                pkgConfigDirs += abiPkgConfigDir
            }
        }

        val projectDependencyDirs = resolveProjectDependencyDirs(projectRoot)
        for (includeDir in projectDependencyDirs.includeDirs) {
            includeDirs += includeDir
            inferPrefixDirFromIncludeDir(includeDir)?.let { prefixDirs += it }
        }
        for (libDir in projectDependencyDirs.libDirs) {
            libDirs += libDir
            inferPrefixDirFromLibDir(libDir)?.let { prefixDirs += it }
            if (libDir.isDirectory) {
                collectLibraryCandidates(
                    dir = libDir,
                    librariesByName = librariesByName,
                    runtimeLibDirs = runtimeLibDirs
                )
                val libPkgConfigDir = File(libDir, "pkgconfig")
                if (libPkgConfigDir.isDirectory) {
                    pkgConfigDirs += libPkgConfigDir
                }
            }
        }
        runtimeLibDirs += projectDependencyDirs.runtimeDirs

        val linkLibraries = librariesByName.keys.toList()

        Timber.tag(TAG).d(
            "Resolved include=%d lib=%d prefix=%d pkgconfig=%d linkLibs=%d runtimeLib=%d from %d packages + project(include=%d, lib=%d, runtime=%d) (ABI: %s)",
            includeDirs.size,
            libDirs.size,
            prefixDirs.size,
            pkgConfigDirs.size,
            linkLibraries.size,
            runtimeLibDirs.size,
            packageDirs.size,
            projectDependencyDirs.includeDirs.size,
            projectDependencyDirs.libDirs.size,
            projectDependencyDirs.runtimeDirs.size,
            deviceAbi
        )
        return PackagePaths(
            includeDirs = includeDirs.toList(),
            libDirs = libDirs.toList(),
            prefixDirs = prefixDirs.toList(),
            pkgConfigDirs = pkgConfigDirs.toList(),
            linkLibraries = linkLibraries,
            runtimeLibDirs = runtimeLibDirs.toList()
        )
    }

    private fun resolveProjectDependencyDirs(projectRoot: File?): ProjectDependencyDirs {
        if (projectRoot == null || !projectRoot.isDirectory) {
            return ProjectDependencyDirs(
                includeDirs = emptyList(),
                libDirs = emptyList(),
                runtimeDirs = emptyList()
            )
        }

        val metadata = runCatching { ProjectMetadataStore.read(projectRoot) }
            .onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to read project metadata: %s", projectRoot.absolutePath)
            }
            .getOrNull()
            ?: return ProjectDependencyDirs(
                includeDirs = emptyList(),
                libDirs = emptyList(),
                runtimeDirs = emptyList()
            )

        val includeDirs = resolveConfiguredDirs(projectRoot, metadata.normalizedNativeIncludeDirs())
        val libDirs = resolveConfiguredDirs(projectRoot, metadata.normalizedNativeLibraryDirs())
        val runtimeDirs = resolveConfiguredDirs(projectRoot, metadata.normalizedNativeRuntimeDirs())
        return ProjectDependencyDirs(
            includeDirs = includeDirs,
            libDirs = libDirs,
            runtimeDirs = runtimeDirs
        )
    }

    private fun resolveConfiguredDirs(projectRoot: File, configuredPaths: List<String>): List<File> {
        if (configuredPaths.isEmpty()) return emptyList()
        return configuredPaths.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { path ->
                val candidate = File(path).takeIf { it.isAbsolute } ?: File(projectRoot, path)
                canonicalOrAbsolute(candidate)
            }
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun canonicalOrAbsolute(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

    private fun inferPrefixDirFromIncludeDir(includeDir: File): File? = if (includeDir.name.equals("include", ignoreCase = true)) {
        includeDir.parentFile ?: includeDir
    } else {
        includeDir
    }

    private fun inferPrefixDirFromLibDir(libDir: File): File? {
        if (libDir.name.equals("lib", ignoreCase = true)) {
            return libDir.parentFile ?: libDir
        }
        val parent = libDir.parentFile
        return if (parent?.name?.equals("lib", ignoreCase = true) == true) {
            parent.parentFile ?: parent
        } else {
            libDir
        }
    }

    private fun resolveManagedPackageDirs(context: Context, installDir: File): List<File> {
        val allPackageDirs = installDir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        if (allPackageDirs.isEmpty()) return emptyList()

        val installedAndroidPackageIds = runCatching {
            LocalInstallStateStore(context.applicationContext)
                .getAllInstalledPackages()
                .asSequence()
                .filter { it.platform == Platform.ANDROID }
                .map { it.packageId.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed to read install state; ignoring unmanaged package directories")
            return emptyList()
        }

        if (installedAndroidPackageIds.isEmpty()) return emptyList()

        val selected = allPackageDirs.filter { dir -> dir.name in installedAndroidPackageIds }
            .sortedBy { it.name.lowercase() }
        if (selected.isEmpty()) {
            Timber.tag(TAG).w(
                "Install state has %d Android packages, but no directories found under %s",
                installedAndroidPackageIds.size,
                installDir.absolutePath
            )
            return emptyList()
        }
        return selected
    }

    private fun collectLibraryCandidates(
        dir: File,
        librariesByName: MutableMap<String, LibraryKind>,
        runtimeLibDirs: MutableSet<File>
    ) {
        val files = dir.listFiles { file -> file.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: return

        var hasSharedLibrary = false
        for (file in files) {
            val candidate = parseLibraryCandidate(file.name) ?: continue
            if (candidate.kind == LibraryKind.SHARED) {
                hasSharedLibrary = true
            }

            val existingKind = librariesByName[candidate.name]
            if (existingKind == null || (existingKind == LibraryKind.STATIC && candidate.kind == LibraryKind.SHARED)) {
                librariesByName[candidate.name] = candidate.kind
            }
        }

        if (hasSharedLibrary) {
            runtimeLibDirs += dir
        }
    }

    private fun parseLibraryCandidate(fileName: String): LibraryCandidate? {
        val sharedName = SHARED_LIBRARY_PATTERN.matchEntire(fileName)?.groupValues?.getOrNull(1)
        if (!sharedName.isNullOrBlank() && !shouldExcludeLibrary(sharedName)) {
            return LibraryCandidate(name = sharedName, kind = LibraryKind.SHARED)
        }

        val staticName = STATIC_LIBRARY_PATTERN.matchEntire(fileName)?.groupValues?.getOrNull(1)
        if (!staticName.isNullOrBlank() && !shouldExcludeLibrary(staticName)) {
            return LibraryCandidate(name = staticName, kind = LibraryKind.STATIC)
        }

        return null
    }

    private fun shouldExcludeLibrary(name: String): Boolean {
        val normalized = name.lowercase()
        return EXCLUDED_LIBRARY_SUFFIXES.any { normalized.endsWith(it) }
    }
}
