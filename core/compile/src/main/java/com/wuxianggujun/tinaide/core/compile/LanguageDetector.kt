package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 项目主要语言检测器
 *
 * 通过扫描项目目录中的源文件扩展名，统计各语言的文件数量，
 * 选择占比最多的语言作为项目主要语言。
 *
 * 优先从项目元数据读取，只有在元数据中没有指定时才进行文件检测。
 */
object LanguageDetector {

    private const val TAG = "LanguageDetector"

    /**
     * 扩展名 → 语言映射表
     */
    private val EXTENSION_TO_LANGUAGE: Map<String, ProjectLanguage> = buildMap {
        // C
        CxxFileSupport.cSourceExtensions.forEach { put(it, ProjectLanguage.C) }
        // C++
        CxxFileSupport.cxxSourceExtensions.forEach { put(it, ProjectLanguage.CPP) }
        // 头文件归为 C++（因为 C 头文件和 C++ 头文件共享扩展名，默认归 CPP）
        CxxFileSupport.headerExtensions.forEach { put(it, ProjectLanguage.CPP) }
        // Java
        put("java", ProjectLanguage.JAVA)
        // Kotlin
        put("kt", ProjectLanguage.KOTLIN)
        put("kts", ProjectLanguage.KOTLIN)
        // Python
        put("py", ProjectLanguage.PYTHON)
        put("pyw", ProjectLanguage.PYTHON)
        put("pyi", ProjectLanguage.PYTHON)
        // Rust
        put("rs", ProjectLanguage.RUST)
        // Go
        put("go", ProjectLanguage.GO)
        // JavaScript
        put("js", ProjectLanguage.JAVASCRIPT)
        put("jsx", ProjectLanguage.JAVASCRIPT)
        put("mjs", ProjectLanguage.JAVASCRIPT)
        put("cjs", ProjectLanguage.JAVASCRIPT)
        // TypeScript
        put("ts", ProjectLanguage.TYPESCRIPT)
        put("tsx", ProjectLanguage.TYPESCRIPT)
        put("mts", ProjectLanguage.TYPESCRIPT)
        put("cts", ProjectLanguage.TYPESCRIPT)
        // Shell
        put("sh", ProjectLanguage.SHELL)
        put("bash", ProjectLanguage.SHELL)
        put("zsh", ProjectLanguage.SHELL)
    }

    /** 参与检测的所有源文件扩展名 */
    private val DETECTABLE_EXTENSIONS: Set<String> = EXTENSION_TO_LANGUAGE.keys

    /**
     * 检测项目的主要编程语言
     *
     * 检测顺序：
     * 1. 从项目元数据读取
     * 2. 如果元数据中没有指定，则通过文件扫描检测
     * 3. 如果检测成功，将结果保存到元数据中
     */
    suspend fun detect(projectRoot: File): ProjectLanguage = withContext(Dispatchers.IO) {
        detectOnIo(projectRoot)
    }

    private suspend fun detectOnIo(projectRoot: File): ProjectLanguage {
        Timber.tag(TAG).d("Detecting primary language: ${projectRoot.absolutePath}")

        if (!projectRoot.exists() || !projectRoot.isDirectory || !projectRoot.canRead()) {
            return ProjectLanguage.UNKNOWN
        }

        // 1. 优先从元数据读取
        val metadata = ProjectMetadataStore.read(projectRoot)
        val stored = metadata?.getPrimaryLanguage()
        if (stored != null && stored != ProjectLanguage.UNKNOWN) {
            Timber.tag(TAG).d("Language from metadata: $stored")
            return stored
        }

        // 2. 通过文件扫描检测
        val detected = detectByFiles(projectRoot)
        Timber.tag(TAG).d("Detected language: $detected")

        // 3. 保存到元数据
        if (detected != ProjectLanguage.UNKNOWN) {
            if (ProjectMetadataStore.updatePrimaryLanguage(projectRoot, detected)) {
                Timber.tag(TAG).d("Saved detected language to metadata: $detected")
            }
        }

        return detected
    }

    /**
     * 通过扫描文件检测主要语言
     *
     * 只扫描顶层目录和第一层子目录（src/ 等常见结构），
     * 避免递归过深影响性能。
     */
    private fun detectByFiles(projectRoot: File): ProjectLanguage {
        return try {
            val counts = mutableMapOf<ProjectLanguage, Int>()

            // 扫描顶层文件
            countLanguageFiles(projectRoot, counts)

            // 扫描第一层子目录（如 src/、lib/、app/ 等）
            projectRoot.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
                countLanguageFiles(dir, counts)
                // 再深入一层（如 src/main/）
                dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { subDir ->
                    countLanguageFiles(subDir, counts)
                }
            }

            if (counts.isEmpty()) {
                return ProjectLanguage.UNKNOWN
            }

            // C 和 C++ 合并统计（如果同时存在 .c 和 .cpp 文件，按 C++ 计算）
            val cCount = counts.getOrDefault(ProjectLanguage.C, 0)
            val cppCount = counts.getOrDefault(ProjectLanguage.CPP, 0)
            if (cCount > 0 || cppCount > 0) {
                counts.remove(ProjectLanguage.C)
                counts[ProjectLanguage.CPP] = cCount + cppCount
            }

            // 返回文件数最多的语言
            counts.maxByOrNull { it.value }?.key ?: ProjectLanguage.UNKNOWN
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception while detecting language")
            ProjectLanguage.UNKNOWN
        }
    }

    /**
     * 统计目录中各语言的源文件数量（仅当前目录，不递归）
     */
    private fun countLanguageFiles(dir: File, counts: MutableMap<ProjectLanguage, Int>) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val ext = file.extension.lowercase()
                EXTENSION_TO_LANGUAGE[ext]?.let { lang ->
                    counts[lang] = (counts[lang] ?: 0) + 1
                }
            }
        }
    }
}
