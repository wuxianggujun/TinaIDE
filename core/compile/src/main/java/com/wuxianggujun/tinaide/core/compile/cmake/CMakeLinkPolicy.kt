package com.wuxianggujun.tinaide.core.compile.cmake

import com.wuxianggujun.tinaide.core.compile.AndroidCppRuntimeLinkage

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

    // Ninja/Make 的链接命令最终经 /bin/sh -c 执行，未转义的 $ORIGIN 会被 shell 展开成空串；
    // clang 的 -Wl 逗号拆分会丢弃空段，裸 -rpath 进而吞掉后续参数（如 -soname），
    // 让 libmain.so 变成链接器输入文件（ld.lld: cannot open libmain.so）。
    // 因此注入形态必须带反斜杠，shell 还原后链接器才能收到字面量 $ORIGIN。
    private const val ANDROID_SHARED_LIBRARY_ORIGIN_RUNPATH_UNESCAPED = "-Wl,-rpath,\$ORIGIN"
    private const val ANDROID_SHARED_LIBRARY_ORIGIN_RUNPATH = "-Wl,-rpath,\\\$ORIGIN"

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
     * Native command-line executables must carry their own libc++ implementation.
     * Otherwise another app can load an older `libc++_shared.so` after the binary is copied.
     */
    fun resolveAndroidExecutableLinkerFlags(linkerFlags: String): String {
        return AndroidCppRuntimeLinkage.appendToLinkerFlags(
            linkerFlags = normalizeLibraries(linkerFlags.lineSequence()),
            isCpp = true,
            outputIsSharedLibrary = false,
        )
    }

    /**
     * Shared-library targets are staged with their dependencies before NativeActivity/SDL launch.
     * An origin-relative RUNPATH lets Android's linker resolve that private, co-located dependency set.
     */
    fun resolveAndroidSharedLinkerFlags(linkerFlags: String): String {
        val normalizedFlags = normalizeLibraries(linkerFlags.lineSequence())
            .splitToSequence(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .map { flag ->
                // 兜底修正历史配置里未转义的 $ORIGIN，避免其继续破坏共享库链接。
                if (flag == ANDROID_SHARED_LIBRARY_ORIGIN_RUNPATH_UNESCAPED) {
                    ANDROID_SHARED_LIBRARY_ORIGIN_RUNPATH
                } else {
                    flag
                }
            }
            .joinToString(" ")
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
