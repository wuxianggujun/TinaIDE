package com.wuxianggujun.tinaide.project

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** target_link_libraries 允许跨行，但不能无限回溯到候选文件末尾。 */
    private const val MAX_TARGET_LINK_COMMAND_CHARS = 8_192
    private const val MAX_DETECTION_CACHE_ENTRIES = 64
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
        targetLinkLibrariesPattern("raylib(?:::\\w+)?"),
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

    private data class DetectionCacheKey(
        val projectRootPath: String,
        val buildDirPath: String?,
    )

    private data class FileStamp(
        val path: String,
        val exists: Boolean,
        val length: Long,
        val lastModified: Long,
    )

    private data class DetectionFingerprint(
        val projectRoot: FileStamp,
        val candidateFiles: List<FileStamp>,
        val candidateParentDirectories: List<FileStamp>,
        val artifactRoots: List<FileStamp>,
    )

    private data class DetectionCacheEntry(
        val fingerprint: DetectionFingerprint,
        val detection: Detection,
    )

    /**
     * 只缓存输入文件指纹对应的探测结果。指纹变化会自动失效，显式重新检测还可以调用
     * [invalidate]，不会把项目能力永久缓存成旧值。
     */
    private val detectionCache = LinkedHashMap<DetectionCacheKey, DetectionCacheEntry>(
        MAX_DETECTION_CACHE_ENTRIES,
        0.75f,
        true,
    )
    private val detectionCacheLock = Any()

    suspend fun resolve(projectRoot: File, buildDir: File? = null): ProjectApkExportType? = withContext(Dispatchers.IO) {
        val metadata = ProjectMetadataStore.read(projectRoot)
        metadata?.apkExportType ?: detectSupportCached(projectRoot, buildDir).apkExportType
    }

    suspend fun ensureDetected(projectRoot: File, buildDir: File? = null): ProjectApkExportType? = withContext(Dispatchers.IO) {
        val metadata = ProjectMetadataStore.read(projectRoot)
        val knownSdlVersion = metadata?.getSdlVersionOrNull()
        if (
            metadata?.apkExportType != null &&
            metadata.sdlVersion != null &&
            metadata.nativeActivityRuntime != null
        ) {
            return@withContext metadata.apkExportType
        }

        val detected = detectSupportCached(projectRoot, buildDir)
        if (metadata == null) return@withContext detected.apkExportType

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
        resolvedApkExportType
    }

    suspend fun detect(projectRoot: File, buildDir: File? = null): ProjectApkExportType? = withContext(Dispatchers.IO) {
        detectSupportCached(projectRoot, buildDir).apkExportType
    }

    suspend fun detectSdlVersion(projectRoot: File, buildDir: File? = null): ProjectSdlVersion? = withContext(Dispatchers.IO) {
        detectSupportCached(projectRoot, buildDir).sdlVersion
    }

    /** 清除指定项目的探测缓存，供用户显式重新检测或项目结构发生已知变化时调用。 */
    fun invalidate(projectRoot: File) {
        val projectRootPath = cachePath(projectRoot)
        synchronized(detectionCacheLock) {
            val iterator = detectionCache.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().projectRootPath == projectRootPath) {
                    iterator.remove()
                }
            }
        }
    }

    internal fun detectSupport(projectRoot: File, buildDir: File? = null): Detection {
        return detectSupport(
            projectRoot = projectRoot,
            buildDir = buildDir,
            candidateFiles = collectCandidateFiles(projectRoot),
        )
    }

    private fun detectSupport(
        projectRoot: File,
        buildDir: File?,
        candidateFiles: List<File>,
    ): Detection {
        val textMatches = readCandidateTexts(candidateFiles)

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

    private fun detectSupportCached(projectRoot: File, buildDir: File?): Detection {
        val key = DetectionCacheKey(
            projectRootPath = cachePath(projectRoot),
            buildDirPath = buildDir?.let(::cachePath),
        )

        // Avoid walking the complete project tree on every recomposition. The common path only
        // stats the files that participated in the previous detection and the relevant roots.
        // A changed stamp falls through to a full candidate scan; explicit invalidate() remains
        // available for callers that know a project changed outside these file-system stamps.
        synchronized(detectionCacheLock) {
            detectionCache[key]?.takeIf { isCacheFresh(it, projectRoot, buildDir) }?.let { cached ->
                return cached.detection
            }
        }

        val candidateFiles = collectCandidateFiles(projectRoot)
        val fingerprint = buildFingerprint(projectRoot, buildDir, candidateFiles)

        val detected = detectSupport(
            projectRoot = projectRoot,
            buildDir = buildDir,
            candidateFiles = candidateFiles,
        )
        synchronized(detectionCacheLock) {
            // Another coroutine may have completed the same scan while this one was reading
            // files. Reuse an equivalent result instead of replacing it with a duplicate entry.
            detectionCache[key]?.takeIf { it.fingerprint == fingerprint }?.let { cached ->
                return cached.detection
            }
            detectionCache[key] = DetectionCacheEntry(fingerprint, detected)
            while (detectionCache.size > MAX_DETECTION_CACHE_ENTRIES) {
                detectionCache.entries.iterator().apply {
                    if (hasNext()) {
                        next()
                        remove()
                    }
                }
            }
        }
        return detected
    }

    private fun buildFingerprint(
        projectRoot: File,
        buildDir: File?,
        candidateFiles: List<File>,
    ): DetectionFingerprint {
        val candidateParentDirectories = candidateFiles
            .mapNotNull(File::getParentFile)
            .distinctBy(::cachePath)
            .map(::fileStamp)
        return DetectionFingerprint(
            projectRoot = fileStamp(projectRoot),
            candidateFiles = candidateFiles.map(::fileStamp),
            candidateParentDirectories = candidateParentDirectories,
            artifactRoots = artifactRoots(projectRoot, buildDir).map(::fileStamp),
        )
    }

    private fun isCacheFresh(
        entry: DetectionCacheEntry,
        projectRoot: File,
        buildDir: File?,
    ): Boolean {
        val fingerprint = entry.fingerprint
        if (fingerprint.projectRoot != fileStamp(projectRoot)) return false
        if (fingerprint.artifactRoots != artifactRoots(projectRoot, buildDir).map(::fileStamp)) {
            return false
        }
        if (fingerprint.candidateParentDirectories.any { stamp ->
                fileStamp(File(stamp.path)) != stamp
            }) {
            return false
        }
        return fingerprint.candidateFiles.all { stamp -> fileStamp(File(stamp.path)) == stamp }
    }

    private fun readCandidateTexts(candidateFiles: List<File>): List<CandidateText> {
        var remainingBytes = MAX_TOTAL_CANDIDATE_TEXT_BYTES
        val candidates = ArrayList<CandidateText>()
        for (file in candidateFiles) {
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

    private fun artifactRoots(projectRoot: File, buildDir: File?): List<File> = buildList {
        buildDir?.let(::add)
        add(File(projectRoot, "build"))
    }.distinctBy { it.absolutePath }

    private fun cachePath(file: File): String = runCatching { file.canonicalPath }
        .getOrElse { file.absolutePath }

    private fun fileStamp(file: File): FileStamp = FileStamp(
        path = cachePath(file),
        exists = file.exists(),
        length = file.length(),
        lastModified = file.lastModified(),
    )

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
        Regex("""(?i)\b(?:pkg_check_modules|pkg_search_module)\s*\([^)]{0,$MAX_TARGET_LINK_COMMAND_CHARS}\bSDL$major\b"""),
        Regex("""(?i)\bSDL$major-config\b"""),
        Regex("""(?i)\bpkg-config\b[^\r\n]*\bSDL$major\b"""),
        targetLinkLibrariesPattern("SDL$major"),
        Regex("""(?i)\bSDL${major}_(?:LIBRARIES|LIBRARY|INCLUDE_DIRS?|DIR)\b"""),
        Regex(
            """(?im)\b(?:LOCAL_SHARED_LIBRARIES|LOCAL_STATIC_LIBRARIES)\s*[:+?]?=[^\r\n]*\bSDL$major\b"""
        ),
    )

    private fun targetLinkLibrariesPattern(marker: String): Regex = Regex(
        """(?i)\btarget_link_libraries\s*\([^)]{0,$MAX_TARGET_LINK_COMMAND_CHARS}\b$marker\b"""
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
