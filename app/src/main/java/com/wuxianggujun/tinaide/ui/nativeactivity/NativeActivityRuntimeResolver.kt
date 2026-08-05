package com.wuxianggujun.tinaide.ui.nativeactivity

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.ui.runtime.AndroidSystemLibraries
import com.wuxianggujun.tinaide.ui.runtime.NativeLibraryDependencyReader
import com.wuxianggujun.tinaide.ui.runtime.buildNativeRuntimeLibraryIndex
import com.wuxianggujun.tinaide.ui.runtime.canonicalSharedLibraryName
import com.wuxianggujun.tinaide.ui.runtime.resolveNativeRuntimeLibrary
import java.io.File
import timber.log.Timber

/** Resolves the complete non-system dependency set for a NativeActivity shared library. */
object NativeActivityRuntimeResolver {
    private const val TAG = "NativeActivityResolver"

    data class RuntimeSpec(
        val mainLibrary: File,
        /** Dependency-first load order. */
        val dependencyLibraries: List<File>,
    )

    sealed class ResolveResult {
        data class Success(val spec: RuntimeSpec) : ResolveResult()
        data class Error(val message: String) : ResolveResult()
    }

    internal data class DependencyResolution(
        val dependencyLibraries: List<File>,
        val missingLibraries: List<String>,
        val sdlLibraries: List<String>,
        val scanFailure: Throwable?,
    )

    fun resolve(
        context: Context,
        mainLibraryPath: String,
        extraRuntimeLibDirs: List<File> = emptyList(),
    ): ResolveResult {
        val mainLibrary = File(mainLibraryPath)
        if (mainLibraryPath.isBlank() || !mainLibrary.isFile) {
            return ResolveResult.Error(
                Strings.native_activity_runtime_main_library_missing.strOr(context)
            )
        }

        val projectRoot = resolveProjectRoot(mainLibrary.parentFile)
        val packagePaths = InstalledPackagePathResolver.resolve(
            context = context.applicationContext,
            projectRoot = projectRoot,
        )
        val runtimeDirs = buildList {
            mainLibrary.parentFile?.let(::add)
            addAll(extraRuntimeLibDirs)
            addAll(packagePaths.runtimeLibDirs)
        }.asSequence()
            .map(::canonicalOrAbsolute)
            .filter(File::isDirectory)
            .distinctBy(File::getAbsolutePath)
            .toList()
        val runtimeIndex = buildNativeRuntimeLibraryIndex(runtimeDirs)
        val dependencyResolution = resolveDependencyClosure(
            mainLibrary = mainLibrary,
            runtimeIndex = runtimeIndex,
        )
        dependencyResolution.scanFailure?.let { error ->
            return ResolveResult.Error(
                Strings.native_activity_runtime_dependency_scan_failed.strOr(
                    context,
                    error.message ?: error.javaClass.simpleName,
                )
            )
        }
        if (dependencyResolution.sdlLibraries.isNotEmpty()) {
            return ResolveResult.Error(
                Strings.native_activity_runtime_sdl_conflict.strOr(context)
            )
        }
        if (dependencyResolution.missingLibraries.isNotEmpty()) {
            return ResolveResult.Error(
                Strings.native_activity_runtime_missing_dependencies.strOr(
                    context,
                    dependencyResolution.missingLibraries.joinToString(", "),
                )
            )
        }

        Timber.tag(TAG).i(
            "Resolved NativeActivity runtime: main=%s dependencies=%d",
            mainLibrary.name,
            dependencyResolution.dependencyLibraries.size,
        )
        return ResolveResult.Success(
            RuntimeSpec(
                mainLibrary = canonicalOrAbsolute(mainLibrary),
                dependencyLibraries = dependencyResolution.dependencyLibraries,
            )
        )
    }

    internal fun resolveDependencyClosure(
        mainLibrary: File,
        runtimeIndex: Map<String, File>,
        dependencyReader: (File) -> Set<String> = NativeLibraryDependencyReader::readNeededLibraryNames,
    ): DependencyResolution {
        val canonicalMain = canonicalOrAbsolute(mainLibrary)
        val dependencyOrder = mutableListOf<File>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val missingLibraries = linkedSetOf<String>()
        val sdlLibraries = linkedSetOf<String>()
        var scanFailure: Throwable? = null

        fun visit(library: File) {
            val canonicalLibrary = canonicalOrAbsolute(library)
            val path = canonicalLibrary.absolutePath
            if (path in visited || scanFailure != null || !visiting.add(path)) return

            val neededLibraries = runCatching { dependencyReader(canonicalLibrary) }
                .onFailure { error ->
                    scanFailure = error
                    Timber.tag(TAG).w(error, "Failed to inspect %s", canonicalLibrary.absolutePath)
                }
                .getOrDefault(emptySet())

            neededLibraries.sorted().forEach { needed ->
                val canonicalName = canonicalSharedLibraryName(needed)
                if (isSdlLibrary(needed) || (canonicalName != null && isSdlLibrary(canonicalName))) {
                    sdlLibraries += canonicalName ?: needed
                    return@forEach
                }
                if (
                    needed in AndroidSystemLibraries.ndkProvided ||
                    (canonicalName != null && canonicalName in AndroidSystemLibraries.ndkProvided)
                ) {
                    return@forEach
                }

                val dependency = resolveNativeRuntimeLibrary(runtimeIndex, needed)
                if (dependency == null) {
                    missingLibraries += needed
                    return@forEach
                }
                visit(dependency)
                val canonicalDependency = canonicalOrAbsolute(dependency)
                if (canonicalDependency.absolutePath != canonicalMain.absolutePath) {
                    dependencyOrder += canonicalDependency
                }
            }

            visiting.remove(path)
            visited += path
        }

        visit(canonicalMain)
        return DependencyResolution(
            dependencyLibraries = dependencyOrder.distinctBy(File::getAbsolutePath),
            missingLibraries = missingLibraries.sorted(),
            sdlLibraries = sdlLibraries.sorted(),
            scanFailure = scanFailure,
        )
    }

    private fun isSdlLibrary(name: String): Boolean =
        Regex("""^libSDL[23](?:[_-].*)?\.so$""").matches(name)

    private fun resolveProjectRoot(startDir: File?): File? {
        var current = startDir
        while (current != null) {
            if (File(File(current, ".tinaide"), "project.json").isFile) return current
            current = current.parentFile
        }
        return null
    }

    private fun canonicalOrAbsolute(file: File): File =
        runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
}
