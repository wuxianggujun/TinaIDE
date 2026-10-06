package com.wuxianggujun.tinaide.ui.apk

import android.content.Context
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 为终端模板导出解析可执行产物及其运行库。
 *
 * 终端模板会把可执行文件放到 assets，再把依赖 .so 注入 lib/<abi>/，
 * 运行时通过 LD_LIBRARY_PATH 指向应用 nativeLibraryDir。
 */
object TerminalApkExportResolver {
    private const val TAG = "TerminalApkExport"

    private val excludedArtifactNames = setOf("Makefile", "makefile", "GNUmakefile", ".gitignore")
    private val excludedArtifactExtensions = setOf(
        "c", "cc", "cpp", "cxx",
        "h", "hh", "hpp", "hxx",
        "s", "asm",
        "o", "obj", "a", "so",
        "d", "mk", "cmake", "ninja",
        "txt", "md", "json", "xml", "gradle", "kts", "properties"
    )

    data class Resolution(
        val executableFile: File?,
        val packagedLibraries: List<File>,
        val missingLibraries: List<String>
    )

    internal data class BuildArtifacts(
        val executableFiles: List<File>,
        val libraries: List<File>
    )

    suspend fun resolve(
        context: Context,
        projectRoot: File?,
        buildDir: File?
    ): Resolution = withContext(Dispatchers.IO) {
        resolveOnIo(context, projectRoot, buildDir)
    }

    private suspend fun resolveOnIo(
        context: Context,
        projectRoot: File?,
        buildDir: File?,
    ): Resolution {
        val buildArtifacts = scanBuildArtifacts(buildDir)
        val executableFile = buildArtifacts.executableFiles.firstOrNull()
            ?: return Resolution(
                executableFile = null,
                packagedLibraries = emptyList(),
                missingLibraries = emptyList()
            )

        val appContext = context.applicationContext
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectRoot)
        val runtimeCandidates = linkedSetOf<File>().apply {
            addAll(buildArtifacts.libraries)
            addAll(ApkExportRuntimeLibrariesResolver.scanRuntimeLibraries(packagePaths.runtimeLibDirs))
            addAll(ApkExportRuntimeLibrariesResolver.collectSysrootRuntimeLibraries(appContext))
        }
        val dependencyResolution = resolveRuntimeDependencies(
            executableFile = executableFile,
            runtimeCandidates = runtimeCandidates.toList()
        )
        val packagedLibraries = dependencyResolution.libraries

        Timber.tag(TAG).i(
            "Resolved terminal APK payload: executable=%s packaged=%d missing=%d",
            executableFile.absolutePath,
            packagedLibraries.size,
            dependencyResolution.missingLibraries.size
        )
        if (dependencyResolution.missingLibraries.isNotEmpty()) {
            Timber.tag(TAG).w(
                "Missing terminal APK libraries: %s",
                dependencyResolution.missingLibraries.joinToString(", ")
            )
        }

        return Resolution(
            executableFile = executableFile,
            packagedLibraries = packagedLibraries,
            missingLibraries = dependencyResolution.missingLibraries
        )
    }

    internal fun resolveRuntimeDependencies(
        executableFile: File,
        runtimeCandidates: List<File>,
        dependencyReader: (File) -> Set<String> = { ApkExportRuntimeLibrariesResolver.extractNeededLibraryNames(it) }
    ): ApkExportRuntimeLibrariesResolver.DependencyResolution {
        val runtimeIndex = ApkExportRuntimeLibrariesResolver.buildRuntimeLibraryIndex(runtimeCandidates)
        return ApkExportRuntimeLibrariesResolver.resolveDependencyClosure(
            rootLibraries = listOf(executableFile),
            runtimeIndex = runtimeIndex,
            dependencyReader = dependencyReader
        )
    }

    internal fun scanBuildArtifacts(buildDir: File?): BuildArtifacts {
        if (buildDir == null || !buildDir.isDirectory) {
            return BuildArtifacts(
                executableFiles = emptyList(),
                libraries = emptyList()
            )
        }

        val files = buildDir.walkTopDown()
            .filter(File::isFile)
            .map(ApkExportRuntimeLibrariesResolver::canonicalOrAbsolute)
            .distinctBy { it.absolutePath }
            .toList()

        val executableFiles = files.asSequence()
            .filter(::isRunnableArtifact)
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
            .toList()
        val libraries = files.asSequence()
            .filter { it.extension.equals("so", ignoreCase = true) }
            .sortedBy { it.absolutePath }
            .toList()

        return BuildArtifacts(
            executableFiles = executableFiles,
            libraries = libraries
        )
    }

    internal fun isRunnableArtifact(file: File): Boolean {
        if (!file.isFile || !file.exists()) return false
        if (file.name in excludedArtifactNames || file.name.startsWith(".")) return false
        if (file.extension.lowercase() in excludedArtifactExtensions) return false
        return file.canExecute() || hasElfMagic(file)
    }

    private fun hasElfMagic(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4) {
                false
            } else {
                header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }
    }.getOrDefault(false)
}
