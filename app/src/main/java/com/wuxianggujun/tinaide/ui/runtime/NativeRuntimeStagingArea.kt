package com.wuxianggujun.tinaide.ui.runtime

import java.io.File
import java.io.IOException

/** A clean, collision-checked directory used to stage one native runtime session. */
internal class NativeRuntimeStagingArea private constructor(
    val directory: File,
) {
    private val sourceByName = linkedMapOf<String, File>()

    fun register(source: File): File {
        val canonicalSource = canonicalRuntimeFile(source)
        val previous = sourceByName[canonicalSource.name]
        if (previous != null && canonicalRuntimeFile(previous) != canonicalSource) {
            throw IOException(
                "Conflicting runtime libraries share filename ${canonicalSource.name}: " +
                    "${previous.absolutePath} and ${canonicalSource.absolutePath}"
            )
        }
        sourceByName[canonicalSource.name] = canonicalSource
        return canonicalSource
    }

    fun copy(source: File): File {
        val canonicalSource = register(source)
        return canonicalSource.copyTo(File(directory, canonicalSource.name), overwrite = true)
    }

    companion object {
        fun prepare(
            stageRootDir: File,
            identityFile: File,
            runtimeName: String,
        ): NativeRuntimeStagingArea {
            if (!stageRootDir.isDirectory && !stageRootDir.mkdirs()) {
                throw IOException("Cannot create $runtimeName staging root: ${stageRootDir.absolutePath}")
            }

            val identity = canonicalRuntimeFile(identityFile)
            val stageKey = identity.absolutePath.hashCode().toUInt().toString(16)
            val stageDir = File(stageRootDir, "${identity.nameWithoutExtension}.$stageKey")
            if (stageDir.exists() && !stageDir.deleteRecursively()) {
                throw IOException("Cannot clean $runtimeName staging directory: ${stageDir.absolutePath}")
            }
            if (!stageDir.mkdirs() && !stageDir.isDirectory) {
                throw IOException("Cannot create $runtimeName staging directory: ${stageDir.absolutePath}")
            }
            return NativeRuntimeStagingArea(stageDir)
        }
    }
}

internal fun canonicalRuntimeFile(file: File): File =
    runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
