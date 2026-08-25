package com.wuxianggujun.tinaide.project

import java.io.File

object ProjectApkExportSupportResolver {

    internal data class Detection(
        val apkExportType: ProjectApkExportType?,
        val sdlVersion: ProjectSdlVersion?,
    )

    private const val MAX_SCANNED_TEXT_FILES = 160
    private val terminalSourceExtensions = setOf("c", "cc", "cpp", "cxx")
    private val terminalMainEntryRegex = Regex("""(?m)^\s*(?:int|auto|void)\s+main\s*\(""")
    private val excludedDirNames = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".tinaide",
        ".vscode",
        "build",
        "out",
        "cmake-build-debug",
        "cmake-build-release"
    )
    private val candidateFileNames = setOf(
        "CMakeLists.txt",
        "Makefile",
        "makefile",
        "GNUmakefile",
        "Android.mk",
        "Application.mk",
        "AndroidManifest.xml",
        "build.gradle",
        "build.gradle.kts"
    )
    private val candidateExtensions = setOf(
        "c", "cc", "cpp", "cxx",
        "h", "hh", "hpp", "hxx",
        "cmake", "mk", "txt",
    )
    private val sdl2MarkerPatterns = sdlMarkerPatterns(major = 2)
    private val sdl3MarkerPatterns = sdlMarkerPatterns(major = 3) + listOf(
        Regex("""\bSDL_MAIN_USE_CALLBACKS\b"""),
        Regex("""\bSDL_App(?:Init|Iterate|Event|Quit)\b"""),
    )
    private val raylibMarkerPatterns = listOf(
        Regex("""(?i)\bfind_package\s*\(\s*raylib\b"""),
        Regex("""(?i)#\s*include\s*[<\"]raylib\.h[>\"]"""),
        Regex("""(?i)(?:^|\s)-lraylib(?:\s|$)""", RegexOption.MULTILINE),
        Regex("""(?i)\blibraylib\.so(?:\.[0-9A-Za-z_.+-]+)?\b"""),
        Regex("""(?is)\btarget_link_libraries\s*\([^)]*\braylib(?:::\w+)?\b"""),
    )
    private val nativeActivityMarkers = listOf(
        "android.app.NativeActivity",
        "android.app.lib_name",
        "ANativeActivity_onCreate",
        "android_main(",
        "android_native_app_glue",
        "#include <android/native_activity.h>",
        "#include <android_native_app_glue.h>",
        "#include <android/native_app_glue/android_native_app_glue.h>"
    )
    private val libmainMarkers = listOf(
        "add_library(main SHARED",
        "OUTPUT_NAME \"main\"",
        "OUTPUT_NAME main",
        "libmain.so",
        "LOCAL_MODULE := main",
        "LOCAL_MODULE:=main"
    )
    private val terminalExcludedArtifactNames = setOf("Makefile", "makefile", "GNUmakefile", ".gitignore")
    private val terminalExcludedArtifactExtensions = setOf(
        "c", "cc", "cpp", "cxx",
        "h", "hh", "hpp", "hxx",
        "s", "asm",
        "o", "obj", "a", "so",
        "d", "mk", "cmake", "ninja",
        "txt", "md", "json", "xml", "gradle", "kts", "properties"
    )

    private data class CandidateText(
        val file: File,
        val text: String
    )

    fun resolve(projectRoot: File, buildDir: File? = null): ProjectApkExportType? {
        val metadata = ProjectMetadataStore.read(projectRoot)
        return metadata?.apkExportType ?: detect(projectRoot, buildDir)
    }

    fun ensureDetected(projectRoot: File, buildDir: File? = null): ProjectApkExportType? {
        val metadata = ProjectMetadataStore.read(projectRoot)
        val knownSdlVersion = metadata?.getSdlVersionOrNull()
        if (metadata?.apkExportType != null && metadata.sdlVersion != null) {
            return metadata.apkExportType
        }

        val detected = detectSupport(projectRoot, buildDir)
        if (metadata == null) return detected.apkExportType

        val resolvedSdlVersion = knownSdlVersion ?: detected.sdlVersion
        val compatibleDetectedApkExportType = detected.apkExportType.takeUnless {
            resolvedSdlVersion == ProjectSdlVersion.SDL2 &&
                (it == ProjectApkExportType.SDL3 || it == ProjectApkExportType.NATIVE_ACTIVITY)
        }
        val resolvedApkExportType = metadata.apkExportType ?: compatibleDetectedApkExportType
        if (
            metadata.apkExportType != resolvedApkExportType ||
            metadata.sdlVersion != resolvedSdlVersion
        ) {
            ProjectMetadataStore.write(
                projectRoot,
                metadata.copy(
                    apkExportType = resolvedApkExportType,
                    sdlVersion = resolvedSdlVersion,
                )
            )
        }
        return resolvedApkExportType
    }

    internal fun detect(projectRoot: File, buildDir: File? = null): ProjectApkExportType? =
        detectSupport(projectRoot, buildDir).apkExportType

    internal fun detectSdlVersion(projectRoot: File, buildDir: File? = null): ProjectSdlVersion? =
        detectSupport(projectRoot, buildDir).sdlVersion

    internal fun detectSupport(projectRoot: File, buildDir: File? = null): Detection {
        val textMatches = collectCandidateFiles(projectRoot)
            .mapNotNull(::readTextSafely)

        val hasLibMainMarker = containsAnyMarker(textMatches, libmainMarkers) || hasCompiledLibMain(projectRoot, buildDir)
        val hasSdl2Marker = containsAnyPattern(textMatches, sdl2MarkerPatterns)
        val hasSdl3Marker = containsAnyPattern(textMatches, sdl3MarkerPatterns)
        val hasRaylibMarker = containsAnyPattern(textMatches, raylibMarkerPatterns)
        val sdlVersion = when {
            hasSdl2Marker == hasSdl3Marker -> null
            hasSdl2Marker -> ProjectSdlVersion.SDL2
            else -> ProjectSdlVersion.SDL3
        }

        val apkExportType = if (hasLibMainMarker && sdlVersion == ProjectSdlVersion.SDL3) {
            ProjectApkExportType.SDL3
        } else if (
            hasLibMainMarker &&
            !hasSdl2Marker &&
            !hasSdl3Marker &&
            (hasRaylibMarker || containsAnyMarker(textMatches, nativeActivityMarkers))
        ) {
            ProjectApkExportType.NATIVE_ACTIVITY
        } else if (!hasLibMainMarker &&
            (
                hasTerminalMainEntry(textMatches) ||
                    hasCompiledTerminalExecutable(projectRoot, buildDir)
                )
        ) {
            ProjectApkExportType.TERMINAL
        } else {
            null
        }
        return Detection(
            apkExportType = apkExportType,
            sdlVersion = sdlVersion,
        )
    }

    private fun collectCandidateFiles(projectRoot: File): List<File> {
        if (!projectRoot.isDirectory) return emptyList()

        return projectRoot.walkTopDown()
            .onEnter { dir -> dir == projectRoot || dir.name !in excludedDirNames }
            .filter { file ->
                file.isFile &&
                    (
                        file.name in candidateFileNames ||
                            file.extension.lowercase() in candidateExtensions
                        )
            }
            .take(MAX_SCANNED_TEXT_FILES)
            .toList()
    }

    private fun readTextSafely(file: File): CandidateText? = runCatching {
        CandidateText(
            file = file,
            text = file.readText(Charsets.UTF_8)
        )
    }.getOrNull()

    private fun containsAnyMarker(textMatches: List<CandidateText>, markers: List<String>): Boolean = textMatches.any { candidate -> markers.any(candidate.text::contains) }

    private fun containsAnyPattern(
        textMatches: List<CandidateText>,
        patterns: List<Regex>,
    ): Boolean = textMatches.any { candidate -> patterns.any { it.containsMatchIn(candidate.text) } }

    private fun sdlMarkerPatterns(major: Int): List<Regex> = listOf(
        Regex("""(?i)\bfind_package\s*\(\s*SDL$major\b"""),
        Regex("""(?i)\bSDL$major::SDL$major[A-Za-z0-9_-]*\b"""),
        Regex("""(?i)#\s*include\s*[<"]SDL$major/"""),
        Regex("""(?im)(?:^|\s)-lSDL$major(?:\s|$)"""),
        Regex("""(?i)\blibSDL$major(?:-[0-9][0-9.]*)?\.so(?:\.[0-9A-Za-z_.+-]+)?\b"""),
        Regex("""(?i)\b(?:pkg_check_modules|pkg_search_module)\s*\([^)]*\bSDL$major\b"""),
        Regex("""(?i)\bSDL$major-config\b"""),
        Regex("""(?i)\bpkg-config\b[^\r\n]*\bSDL$major\b"""),
        Regex("""(?is)\btarget_link_libraries\s*\([^)]*\bSDL$major\b"""),
        Regex("""(?i)\bSDL${major}_(?:LIBRARIES|LIBRARY|INCLUDE_DIRS?|DIR)\b"""),
        Regex(
            """(?im)\b(?:LOCAL_SHARED_LIBRARIES|LOCAL_STATIC_LIBRARIES)\s*[:+?]?=[^\r\n]*\bSDL$major\b"""
        ),
    )

    private fun hasTerminalMainEntry(textMatches: List<CandidateText>): Boolean = textMatches.any { candidate ->
        candidate.file.extension.lowercase() in terminalSourceExtensions &&
            terminalMainEntryRegex.containsMatchIn(candidate.text)
    }

    private fun hasCompiledLibMain(projectRoot: File, buildDir: File?): Boolean {
        val candidates = buildList {
            buildDir?.let { add(it) }
            add(File(projectRoot, "build"))
        }.distinctBy { it.absolutePath }

        return candidates.any { candidate ->
            candidate.isDirectory &&
                candidate.walkTopDown()
                    .onEnter { dir -> dir == candidate || dir.name !in excludedDirNames }
                    .any { file -> file.isFile && file.name == "libmain.so" }
        }
    }

    private fun hasCompiledTerminalExecutable(projectRoot: File, buildDir: File?): Boolean {
        val candidates = buildList {
            buildDir?.let { add(it) }
            add(File(projectRoot, "build"))
        }.distinctBy { it.absolutePath }

        return candidates.any { candidate ->
            candidate.isDirectory &&
                candidate.walkTopDown()
                    .onEnter { dir -> dir == candidate || dir.name !in excludedDirNames }
                    .any(::isRunnableTerminalArtifact)
        }
    }

    private fun isRunnableTerminalArtifact(file: File): Boolean {
        if (!file.isFile || !file.exists()) return false
        if (file.name in terminalExcludedArtifactNames || file.name.startsWith(".")) return false
        if (file.extension.lowercase() in terminalExcludedArtifactExtensions) return false
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
