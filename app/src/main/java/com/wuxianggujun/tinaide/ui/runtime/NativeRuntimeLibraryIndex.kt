package com.wuxianggujun.tinaide.ui.runtime

import java.io.File

/** Builds a deterministic file-name index shared by graphical runtime resolvers. */
internal fun buildNativeRuntimeLibraryIndex(runtimeDirs: List<File>): Map<String, File> {
    val index = linkedMapOf<String, File>()
    runtimeDirs.forEach { dir ->
        dir.listFiles { file -> file.isFile && file.name.contains(".so") }
            ?.sortedBy(File::getName)
            ?.forEach { library ->
                putNativeRuntimeCandidate(index, library.name, library)
            }
    }
    return index
}

internal fun canonicalSharedLibraryName(name: String): String? {
    val markerIndex = name.indexOf(".so")
    if (markerIndex < 0) return null
    return name.substring(0, markerIndex + 3)
}

internal fun resolveNativeRuntimeLibrary(
    runtimeIndex: Map<String, File>,
    libraryName: String,
): File? = runtimeIndex[libraryName]
    // A versioned DT_NEEDED entry may use an unversioned package file, but it must never
    // resolve to a different numbered ABI such as libfoo.so.2 for libfoo.so.9.
    ?: canonicalSharedLibraryName(libraryName)
        ?.takeUnless { canonicalName -> canonicalName == libraryName }
        ?.let(runtimeIndex::get)

private fun putNativeRuntimeCandidate(
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
