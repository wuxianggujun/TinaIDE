package com.wuxianggujun.tinaide.ui.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import timber.log.Timber

/**
 * Stages SDL application libraries into app-private storage before launch.
 */
object SdlRuntimeLibraryStager {
    private const val TAG = "SdlRuntimeLibraryStager"

    data class StagedRuntime(
        val sdlLibraryPath: String,
        val mainLibraryPath: String,
        val preSdlLibraryPaths: List<String>,
        val preloadLibraryPaths: List<String>
    )

    sealed class StageResult {
        data class Success(val runtime: StagedRuntime) : StageResult()
        data class Error(val message: String, val throwable: Throwable? = null) : StageResult()
    }

    fun stage(
        context: Context,
        sdlLibraryPath: String,
        mainLibraryPath: String,
        preSdlLibraryPaths: List<String> = emptyList(),
        preloadLibraryPaths: List<String> = emptyList()
    ): StageResult {
        val privatePathPrefixes = buildList {
            context.applicationInfo.dataDir
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            context.applicationInfo.nativeLibraryDir
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }.distinct()

        return stage(
            sdlLibrary = File(sdlLibraryPath),
            mainLibrary = File(mainLibraryPath),
            preSdlLibraryPaths = preSdlLibraryPaths,
            preloadLibraryPaths = preloadLibraryPaths,
            stageRootDir = File(context.filesDir, "run-bin/sdl"),
            privatePathPrefixes = privatePathPrefixes
        )
    }

    @Synchronized
    internal fun stage(
        sdlLibrary: File,
        mainLibrary: File,
        preSdlLibraryPaths: List<String>,
        preloadLibraryPaths: List<String>,
        stageRootDir: File,
        privatePathPrefixes: List<String>
    ): StageResult {
        return runCatching {
            if (!stageRootDir.isDirectory && !stageRootDir.mkdirs()) {
                throw IOException("Cannot create SDL staging root: ${stageRootDir.absolutePath}")
            }
            val stageKey = mainLibrary.absolutePath.hashCode().toUInt().toString(16)
            // stageDir 按项目固定（hashCode），不清空会残留上一次的脏副本。
            // 例如曾被误判为依赖而复制进来的 libmediandk.so 等 OS 系统库副本，
            // 即使代码已不再 stage 它们，旧副本仍会在受限 linker 命名空间里被 dlopen 而崩溃。
            // 因此每次 stage 前先清空该项目目录，保证只保留本次真正需要的库。
            val stageDir = File(stageRootDir, "${mainLibrary.nameWithoutExtension}.$stageKey")
            if (stageDir.exists() && !stageDir.deleteRecursively()) {
                throw IOException("Cannot clean stale SDL staging directory: ${stageDir.absolutePath}")
            }
            if (!stageDir.mkdirs() && !stageDir.isDirectory) {
                throw IOException("Cannot create SDL staging directory: ${stageDir.absolutePath}")
            }

            val stagedSources = mutableMapOf<String, File>()
            fun registerSource(source: File) {
                val canonicalSource = canonicalOrAbsolute(source)
                val previousSource = stagedSources[source.name]
                if (previousSource != null && canonicalOrAbsolute(previousSource) != canonicalSource) {
                    throw IOException(
                        "Conflicting SDL runtime libraries share filename ${source.name}: " +
                            "${previousSource.absolutePath} and ${source.absolutePath}"
                    )
                }
                stagedSources[source.name] = source
            }

            fun stagePublicFile(source: File): File {
                registerSource(source)
                return stageFile(source, stageDir)
            }

            fun resolveLibrary(source: File): File {
                registerSource(source)
                return if (isPrivateRuntimePath(source, privatePathPrefixes)) {
                    source
                } else {
                    stageFile(source, stageDir)
                }
            }

            val stagedMain = stagePublicFile(mainLibrary)
            val stagedSdl = resolveLibrary(sdlLibrary)
            val stagedPreSdl = linkedSetOf<String>()
            val stagedPreloads = linkedSetOf<String>()

            fun stageLibraries(paths: List<String>, destination: MutableSet<String>) {
                paths.map(::File).forEach { preload ->
                    val resolved = resolveLibrary(preload)
                    if (resolved.absolutePath != stagedMain.absolutePath) {
                        destination += resolved.absolutePath
                    }
                }
            }
            stageLibraries(preSdlLibraryPaths, stagedPreSdl)
            stageLibraries(preloadLibraryPaths, stagedPreloads)
            stagedPreloads.removeAll(stagedPreSdl)
            stagedPreloads.remove(stagedSdl.absolutePath)
            stagedPreSdl.remove(stagedSdl.absolutePath)

            Timber.tag(TAG).i(
                "Staged SDL runtime: main=%s -> %s, sdl=%s -> %s, preSdl=%d, preload=%d",
                mainLibrary.absolutePath,
                stagedMain.absolutePath,
                sdlLibrary.absolutePath,
                stagedSdl.absolutePath,
                stagedPreSdl.size,
                stagedPreloads.size
            )

            StageResult.Success(
                StagedRuntime(
                    sdlLibraryPath = stagedSdl.absolutePath,
                    mainLibraryPath = stagedMain.absolutePath,
                    preSdlLibraryPaths = stagedPreSdl.toList(),
                    preloadLibraryPaths = stagedPreloads.toList()
                )
            )
        }.getOrElse { throwable ->
            Timber.tag(TAG).e(throwable, "Failed to stage SDL runtime: %s", mainLibrary.absolutePath)
            StageResult.Error(
                message = throwable.message ?: throwable.javaClass.simpleName,
                throwable = throwable
            )
        }
    }

    private fun stageFile(source: File, stageDir: File): File {
        val target = File(stageDir, source.name)
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun isPrivateRuntimePath(file: File, privatePathPrefixes: List<String>): Boolean {
        val absolutePath = canonicalOrAbsolute(file).path
        return privatePathPrefixes.any { prefix ->
            val prefixPath = canonicalOrAbsolute(File(prefix)).path.trimEnd(File.separatorChar)
            absolutePath == prefixPath || absolutePath.startsWith(prefixPath + File.separator)
        }
    }

    private fun canonicalOrAbsolute(file: File): File =
        runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
}
