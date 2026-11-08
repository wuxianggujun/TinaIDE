package com.wuxianggujun.tinaide.core.compile.artifact

import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticsLog
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import java.io.File

/**
 * 收集参与增量判定的输入文件。
 *
 * 这里不区分“源码”还是“构建脚本”：
 * 只要文件变化会影响最终产物，就应该进入 tracked inputs。
 */
internal object TrackedInputCollector {

    private val EXCLUDED_DIR_NAMES = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".cxx",
        "build",
        "out",
        "vcpkg_installed",
        ".vcpkg",
    )

    private val CMAKE_SOURCE_EXTENSIONS: Set<String> =
        CxxFileSupport.clangdTranslationUnitExtensions + CxxFileSupport.headerExtensions

    fun collectSingleFileInputs(projectRoot: File, source: File): List<File> {
        val projectHeaders = walkProjectFiles(projectRoot) { file ->
            file.extension.lowercase() in CxxFileSupport.headerExtensions
        }
        return mergeUniqueFiles(listOf(source) + projectHeaders)
    }

    fun collectMakeInputs(projectRoot: File, sourceExtensions: Set<String>): List<File> {
        val makefiles = listOf("Makefile", "makefile", "GNUmakefile")
            .map { File(projectRoot, it) }
        val sourceFiles = walkProjectFiles(projectRoot) { file ->
            file.extension.lowercase() in sourceExtensions
        }
        return mergeUniqueFiles(makefiles + sourceFiles)
    }

    fun collectCMakeCompileInputs(
        projectRoot: File,
        buildDir: File,
        targetNames: Set<String>,
        targetSources: List<File>,
    ): List<File> {
        val compileCommandsInputs = CompileCommandsInputCollector.collect(projectRoot, buildDir, targetNames)
        return if (compileCommandsInputs != null) {
            val merged = mergeUniqueFiles(
                targetSources +
                    compileCommandsInputs.databaseFile +
                    compileCommandsInputs.sources
            )
            BuildDiagnosticsLog.i {
                "tracked inputs cmake: using compile_commands database=${compileCommandsInputs.databaseFile.absolutePath} " +
                    "targetSources=${targetSources.size} compileCommandSources=${compileCommandsInputs.sources.size} " +
                    "merged=${merged.size}"
            }
            merged
        } else {
            val projectSources = walkProjectFiles(projectRoot) { file ->
                file.extension.lowercase() in CMAKE_SOURCE_EXTENSIONS
            }
            val merged = mergeUniqueFiles(targetSources + projectSources)
            BuildDiagnosticsLog.i {
                "tracked inputs cmake: compile_commands unavailable, fallback project scan " +
                    "targetSources=${targetSources.size} projectSources=${projectSources.size} merged=${merged.size}"
            }
            merged
        }
    }

    fun collectCMakeReconfigureInputs(projectRoot: File): List<File> {
        val projectSources = walkProjectFiles(projectRoot) { file ->
            file.extension.lowercase() in CMAKE_SOURCE_EXTENSIONS
        }
        val cmakeScripts = walkProjectFiles(projectRoot) { file ->
            file.name == "CMakeLists.txt" || file.extension.lowercase() == "cmake"
        }
        val vcpkgManifests = listOf("vcpkg.json", "vcpkg-configuration.json")
            .map { File(projectRoot, it) }.filter { it.isFile }
        return mergeUniqueFiles(projectSources + cmakeScripts + vcpkgManifests)
    }

    fun collectCMakeInputs(
        projectRoot: File,
        buildDir: File,
        targetNames: Set<String>,
        targetSources: List<File>,
    ): List<File> = mergeUniqueFiles(
        collectCMakeCompileInputs(projectRoot, buildDir, targetNames, targetSources) +
            collectCMakeReconfigureInputs(projectRoot)
    )

    private fun walkProjectFiles(
        projectRoot: File,
        predicate: (File) -> Boolean,
    ): List<File> = projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || dir.name !in EXCLUDED_DIR_NAMES
        }
        .filter { it.isFile && predicate(it) }
        .toList()

    private fun mergeUniqueFiles(files: List<File>): List<File> {
        val unique = linkedMapOf<String, File>()
        files.asSequence()
            .filter { it.isFile }
            .map { it.absoluteFile }
            .sortedBy { normalizePath(it) }
            .forEach { file ->
                unique.putIfAbsent(normalizePath(file), file)
            }
        return unique.values.toList()
    }

    private fun normalizePath(file: File): String = file.absolutePath.replace(File.separatorChar, '/')
}
