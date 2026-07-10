package com.wuxianggujun.tinaide.ui.runtime

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * Stages SDL application libraries into app-private storage before launch.
 */
object SdlRuntimeLibraryStager {
    private const val TAG = "SdlRuntimeLibraryStager"

    data class StagedRuntime(
        val mainLibraryPath: String,
        val preloadLibraryPaths: List<String>
    )

    sealed class StageResult {
        data class Success(val runtime: StagedRuntime) : StageResult()
        data class Error(val message: String, val throwable: Throwable? = null) : StageResult()
    }

    fun stage(
        context: Context,
        mainLibraryPath: String,
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
            mainLibrary = File(mainLibraryPath),
            preloadLibraryPaths = preloadLibraryPaths,
            stageRootDir = File(context.filesDir, "run-bin/sdl"),
            privatePathPrefixes = privatePathPrefixes
        )
    }

    internal fun stage(
        mainLibrary: File,
        preloadLibraryPaths: List<String>,
        stageRootDir: File,
        privatePathPrefixes: List<String>
    ): StageResult {
        return runCatching {
            stageRootDir.mkdirs()
            val stageKey = mainLibrary.absolutePath.hashCode().toUInt().toString(16)
            // stageDir 按项目固定（hashCode），不清空会残留上一次的脏副本。
            // 例如曾被误判为依赖而复制进来的 libmediandk.so 等 OS 系统库副本，
            // 即使代码已不再 stage 它们，旧副本仍会在受限 linker 命名空间里被 dlopen 而崩溃。
            // 因此每次 stage 前先清空该项目目录，保证只保留本次真正需要的库。
            val stageDir = File(stageRootDir, "${mainLibrary.nameWithoutExtension}.$stageKey").apply {
                deleteRecursively()
                mkdirs()
            }

            val stagedMain = stageFile(mainLibrary, stageDir)
            val stagedPreloads = linkedSetOf<String>()

            preloadLibraryPaths
                .map(::File)
                .forEach { preload ->
                    val resolved = if (isPrivateRuntimePath(preload, privatePathPrefixes)) {
                        preload
                    } else {
                        stageFile(preload, stageDir)
                    }
                    if (resolved.absolutePath != stagedMain.absolutePath) {
                        stagedPreloads += resolved.absolutePath
                    }
                }

            Timber.tag(TAG).i(
                "Staged SDL runtime: main=%s -> %s, preloadCount=%d",
                mainLibrary.absolutePath,
                stagedMain.absolutePath,
                stagedPreloads.size
            )

            StageResult.Success(
                StagedRuntime(
                    mainLibraryPath = stagedMain.absolutePath,
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
