package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import java.io.File

/** Resource roots are project-owned; custom paths must not escape through ../ or symlinks. */
fun resolveRuntimeAssetDirectory(
    projectRoot: File,
    configuredDirectory: String,
    buildContext: BuildVariables.BuildContext,
): File {
    val root = projectRoot.canonicalFile
    val configured = configuredDirectory.trim()
    if (configured.isEmpty()) return File(root, "assets").takeIf { it.isDirectory }?.canonicalFile
        ?.takeIf { it.toPath().startsWith(root.toPath()) } ?: root
    val expanded = BuildVariables.expand(configured, buildContext)
    val path = File(expanded).let { if (it.isAbsolute) it else File(root, expanded) }.canonicalFile
    require(path.toPath().startsWith(root.toPath()) && path.isDirectory) {
        Strings.run_config_assets_invalid.str()
    }
    return path
}

/** Export only the chosen resource directory; never implicitly package source/build/secrets. */
fun resolveExportAssetDirectories(
    projectRoot: File,
    configuredDirectory: String,
    buildContext: BuildVariables.BuildContext,
): List<File> {
    val directory = resolveRuntimeAssetDirectory(projectRoot, configuredDirectory, buildContext)
    if (configuredDirectory.isBlank() && directory == projectRoot.canonicalFile) return emptyList()
    return listOf(directory)
}
