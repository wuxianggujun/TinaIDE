package com.wuxianggujun.tinaide.core.compile.cmake

/**
 * CMake 依赖注入策略。
 *
 * 已安装包提供的 prefix/include/lib 路径仅用于帮助 `find_package()`、`find_library()`
 * 和 pkg-config 发现依赖，不能直接把扫描到的 `-L/-l` 注入到
 * `CMAKE_<LANG>_STANDARD_LIBRARIES`。
 *
 * 否则用户即使没有在 `CMakeLists.txt` 中声明依赖，也会被强制链接到所有已安装共享库，
 * 最终让可执行文件产生意外的 DT_NEEDED（例如 `libSDL3.so`）。
 */
internal object CMakeLinkPolicy {
    private const val ANDROID_LOG_LIBRARY = "-llog"
    private const val ANDROID_SHARED_LIBRARY_ORIGIN_RUNPATH = "-Wl,-rpath,\$ORIGIN"

    /**
     * 仅传播项目显式配置的链接库参数。
     */
    fun resolveStandardLibraries(projectLdLibs: String): String {
        return normalizeLibraries(projectLdLibs.lineSequence())
    }

    fun resolveAndroidStandardLibraries(projectLdLibs: String): String {
        return normalizeLibraries(sequenceOf(ANDROID_LOG_LIBRARY) + projectLdLibs.lineSequence())
    }

    /**
     * Shared-library targets are staged with their dependencies before NativeActivity/SDL launch.
     * An origin-relative RUNPATH lets Android's linker resolve that private, co-located dependency set.
     */
    fun resolveAndroidSharedLinkerFlags(linkerFlags: String): String {
        val normalizedFlags = normalizeLibraries(linkerFlags.lineSequence())
        return if (containsFlag(normalizedFlags, ANDROID_SHARED_LIBRARY_ORIGIN_RUNPATH)) {
            normalizedFlags
        } else {
            normalizeLibraries(sequenceOf(normalizedFlags, ANDROID_SHARED_LIBRARY_ORIGIN_RUNPATH))
        }
    }

    private fun containsFlag(flags: String, expected: String): Boolean = flags
        .splitToSequence(Regex("""\s+"""))
        .any { flag -> flag == expected }

    private fun normalizeLibraries(libraries: Sequence<String>): String {
        return libraries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .trim()
    }
}
