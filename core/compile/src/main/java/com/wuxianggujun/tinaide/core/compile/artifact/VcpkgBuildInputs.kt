package com.wuxianggujun.tinaide.core.compile.artifact

import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Tracks installed inputs, never vcpkg downloads/buildtrees or every available port. */
internal object VcpkgBuildInputs {
    fun collect(project: File, arguments: List<String>, androidAbi: String, debug: Boolean): List<File> {
        val config = VcpkgProjectSettings.from(arguments)
        if (!config.enabled || config.root.isBlank()) return emptyList()
        fun resolve(path: String) = File(path).let { if (it.isAbsolute) it else File(project, path) }.canonicalFile
        val installed = when {
            config.installedDir.isNotBlank() -> resolve(config.installedDir)
            config.mode == VcpkgProjectSettings.MANIFEST -> File(project, "vcpkg_installed")
            else -> File(resolve(config.root), "installed")
        }
        val prefix = File(installed, config.targetTriplet(androidAbi))
        val directories = listOf("include", "share", "lib", "bin") + if (debug) listOf("debug/lib", "debug/bin") else emptyList()
        return buildList {
            File(installed, "vcpkg/status").takeIf { it.isFile }?.let(::add)
            directories.forEach { name ->
                val directory = File(prefix, name)
                if (!directory.isDirectory) return@forEach
                directory.walkTopDown()
                    .onEnter { it == directory || !Files.isSymbolicLink(it.toPath()) }
                    .onFail { file, error -> throw IOException("Cannot track vcpkg dependency inputs: ${file.name}", error) }
                    .filter { it.isFile }
                    .forEach { add(it) }
            }
        }.distinctBy { it.absolutePath }
    }
}
