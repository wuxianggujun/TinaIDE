package com.wuxianggujun.tinaide.project

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import timber.log.Timber

object ProjectApkExportSupportResolver {

    private const val TAG = "ProjectApkExportSupport"

    internal data class Detection(
        val apkExportType: ProjectApkExportType?,
        val sdlVersion: ProjectSdlVersion?,
        val nativeActivityRuntime: Boolean,
    )

    private const val MAX_SCANNED_TEXT_FILES = 160
    /** 自动能力探测不得把日志/转储等大文本整份读入内存。 */
    private const val MAX_CANDIDATE_TEXT_BYTES = 1L * 1024L * 1024L
    /** 即使候选文件很多，单次探测也只允许读取有限总量。 */
    private const val MAX_TOTAL_CANDIDATE_TEXT_BYTES = 8L * 1024L * 1024L
    private const val MAX_SCANNED_PROJECT_ENTRIES = 100_000
    private const val MAX_SCANNED_ARTIFACT_ENTRIES = 100_000
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
        "cmake", "mk",
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
        val text: String,
        val byteSize: Long,
    )

    fun resolve(projectRoot: File, buildDir: File? = null): ProjectApkExportType? {
        val metadata = ProjectMetadataStore.read(projectRoot)
        return metadata?.apkExportType ?: detect(projectRoot, buildDir)
    }

    fun ensureDetected(projectRoot: File, buildDir: File? = null): ProjectApkExportType? {
        val metadata = ProjectMetadataStore.read(projectRoot)
        val knownSdlVersion = metadata?.getSdlVersionOrNull()
        if (
            metadata?.apkExportType != null &&
            metadata.sdlVersion != null &&
            metadata.nativeActivityRuntime != null
        ) {
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
        // 运行能力一律以本次探测为准，不沿用旧值：否则「先建普通项目、后加 raylib」会永久卡在 TERMINAL。
        // 仅两种情况覆盖探测：SDL 项目互斥置 false；导出类型已确认为 NativeActivity 则置 true。
        val resolvedNativeActivityRuntime = when {
            resolvedSdlVersion != null -> false
            resolvedApkExportType == ProjectApkExportType.NATIVE_ACTIVITY -> true
            else -> detected.nativeActivityRuntime
        }
        if (
            metadata.apkExportType != resolvedApkExportType ||
            metadata.sdlVersion != resolvedSdlVersion ||
            metadata.nativeActivityRuntime != resolvedNativeActivityRuntime
        ) {
            ProjectMetadataStore.write(
                projectRoot,
                metadata.copy(
                    apkExportType = resolvedApkExportType,
                    sdlVersion = resolvedSdlVersion,
                    nativeActivityRuntime = resolvedNativeActivityRuntime,
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
        val textMatches = readCandidateTexts(projectRoot)

        val hasLibMainMarker = containsAnyMarker(textMatches, libmainMarkers) || hasCompiledLibMain(projectRoot, buildDir)
        val hasSdl2Marker = containsAnyPattern(textMatches, sdl2MarkerPatterns)
        val hasSdl3Marker = containsAnyPattern(textMatches, sdl3MarkerPatterns)
        val hasRaylibMarker = containsAnyPattern(textMatches, raylibMarkerPatterns)
        val sdlVersion = when {
            hasSdl2Marker == hasSdl3Marker -> null
            hasSdl2Marker -> ProjectSdlVersion.SDL2
            else -> ProjectSdlVersion.SDL3
        }

        // 运行能力与库名无关：宿主按绝对路径 dlopen，目标叫什么都能跑。
        // APK 导出能力才依赖 libmain.so（导出模板把 android.app.lib_name 写死为 main）。
        val hasNativeActivityRuntime = (
            hasRaylibMarker || containsAnyMarker(textMatches, nativeActivityMarkers)
            ) && !hasSdl2Marker && !hasSdl3Marker

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
            nativeActivityRuntime = hasNativeActivityRuntime,
        )
    }

    private fun collectCandidateFiles(projectRoot: File): List<File> {
        if (!projectRoot.isDirectory) return emptyList()

        return projectRoot.walkTopDown()
            .onEnter { dir -> dir == projectRoot || dir.name !in excludedDirNames }
            .take(MAX_SCANNED_PROJECT_ENTRIES)
            .filter { file ->
                file.isFile &&
                    file.length() <= MAX_CANDIDATE_TEXT_BYTES &&
                    (
                        file.name in candidateFileNames ||
                            file.extension.lowercase() in candidateExtensions
                        )
            }
            .take(MAX_SCANNED_TEXT_FILES)
            .toList()
    }

    private fun readCandidateTexts(projectRoot: File): List<CandidateText> {
        var remainingBytes = MAX_TOTAL_CANDIDATE_TEXT_BYTES
        val candidates = ArrayList<CandidateText>()
        for (file in collectCandidateFiles(projectRoot)) {
            if (remainingBytes <= 0L) break
            val fileSize = runCatching { file.length() }.getOrDefault(Long.MAX_VALUE)
            if (fileSize > remainingBytes) continue
            val candidate = readTextSafely(
                file = file,
                maxBytes = minOf(MAX_CANDIDATE_TEXT_BYTES, remainingBytes),
            ) ?: continue
            candidates.add(candidate)
            remainingBytes -= candidate.byteSize
        }
        return candidates
    }

    private fun readTextSafely(file: File, maxBytes: Long = MAX_CANDIDATE_TEXT_BYTES): CandidateText? {
        if (file.length() > maxBytes) return null
        return try {
            val bytes = file.inputStream().use { input ->
                ByteArrayOutputStream(minOf(file.length(), maxBytes).toInt()).use candidate@{ output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        // MT 等外部程序仍在写入时，文件可能在 length() 检查后继续增长。
                        if (total > maxBytes) return@candidate null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } ?: return null
            CandidateText(
                file = file,
                text = bytes.toString(Charsets.UTF_8),
                byteSize = bytes.size.toLong(),
            )
        } catch (error: IOException) {
            Timber.tag(TAG).d(error, "Skipping unreadable candidate file: %s", file.absolutePath)
            null
        } catch (error: SecurityException) {
            Timber.tag(TAG).d(error, "Skipping inaccessible candidate file: %s", file.absolutePath)
            null
        }
    }

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
                    .take(MAX_SCANNED_ARTIFACT_ENTRIES)
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
                    .take(MAX_SCANNED_ARTIFACT_ENTRIES)
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
