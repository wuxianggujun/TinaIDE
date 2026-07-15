package com.wuxianggujun.tinaide.core.help

import android.content.Context
import androidx.annotation.ArrayRes
import com.wuxianggujun.tinaide.core.i18n.Arrays
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 帮助文档仓库
 * 负责加载、缓存和搜索帮助文档
 */
class HelpRepository(private val context: Context) {

    // 文档内容缓存
    private val contentCache = mutableMapOf<String, String>()

    private fun keywords(@ArrayRes resId: Int): List<String> = context.resources.getStringArray(resId).toList()

    /**
     * 清理帮助文档中偶发的“`n+”伪换行标记，避免 Markdown 渲染异常。
     *
     * 这些标记通常来自生成/拷贝过程中的转义问题，优先在渲染/搜索前做一次兜底修复。
     */
    private fun sanitizeHelpMarkdown(raw: String): String = raw
        .replace("`n+- ", "`\n- ")
        .replace("`n+-", "`\n- ")
        .replace("`n+", "`\n")

    // 预定义的帮助文档列表
    private val documents: List<HelpDocument> = listOf(
        // 快速开始
        HelpDocument(
            id = "getting-started",
            title = Strings.help_doc_getting_started_title.strOr(context),
            category = HelpCategory.GETTING_STARTED,
            keywords = keywords(Arrays.help_keywords_getting_started),
            fileName = "getting-started.md",
            summary = Strings.help_doc_getting_started_summary.strOr(context),
            order = 0
        ),
        HelpDocument(
            id = "create-project",
            title = Strings.help_doc_create_project_title.strOr(context),
            category = HelpCategory.GETTING_STARTED,
            keywords = keywords(Arrays.help_keywords_create_project),
            fileName = "create-project.md",
            summary = Strings.help_doc_create_project_summary.strOr(context),
            order = 1
        ),
        HelpDocument(
            id = "plugin-quick-start",
            title = Strings.help_doc_plugin_quick_start_title.strOr(context),
            category = HelpCategory.GETTING_STARTED,
            keywords = keywords(Arrays.help_keywords_plugin_quick_start),
            fileName = "plugin-quick-start.md",
            summary = Strings.help_doc_plugin_quick_start_summary.strOr(context),
            order = 2
        ),
        HelpDocument(
            id = "settings-overview",
            title = Strings.help_doc_settings_overview_title.strOr(context),
            category = HelpCategory.GETTING_STARTED,
            keywords = keywords(Arrays.help_keywords_settings_overview),
            fileName = "settings-overview.md",
            summary = Strings.help_doc_settings_overview_summary.strOr(context),
            order = 3
        ),
        HelpDocument(
            id = "linux-storage",
            title = Strings.help_doc_linux_storage_title.strOr(context),
            category = HelpCategory.GETTING_STARTED,
            keywords = keywords(Arrays.help_keywords_linux_storage),
            fileName = "linux-storage.md",
            summary = Strings.help_doc_linux_storage_summary.strOr(context),
            order = 4
        ),
        HelpDocument(
            id = "project-settings",
            title = Strings.help_doc_project_settings_title.strOr(context),
            category = HelpCategory.GETTING_STARTED,
            keywords = keywords(Arrays.help_keywords_project_settings),
            fileName = "project-settings.md",
            summary = Strings.help_doc_project_settings_summary.strOr(context),
            order = 5
        ),

        // 编辑器
        HelpDocument(
            id = "editor-basics",
            title = Strings.help_doc_editor_basics_title.strOr(context),
            category = HelpCategory.EDITOR,
            keywords = keywords(Arrays.help_keywords_editor_basics),
            fileName = "editor-basics.md",
            summary = Strings.help_doc_editor_basics_summary.strOr(context),
            order = 0
        ),
        HelpDocument(
            id = "code-completion",
            title = Strings.help_doc_code_completion_title.strOr(context),
            category = HelpCategory.EDITOR,
            keywords = keywords(Arrays.help_keywords_code_completion),
            fileName = "code-completion.md",
            summary = Strings.help_doc_code_completion_summary.strOr(context),
            order = 1
        ),
        HelpDocument(
            id = "editor-settings",
            title = Strings.help_doc_editor_settings_title.strOr(context),
            category = HelpCategory.EDITOR,
            keywords = keywords(Arrays.help_keywords_editor_settings),
            fileName = "editor-settings.md",
            summary = Strings.help_doc_editor_settings_summary.strOr(context),
            order = 2
        ),

        // 编译与构建
        HelpDocument(
            id = "build-project",
            title = Strings.help_doc_build_project_title.strOr(context),
            category = HelpCategory.COMPILER,
            keywords = keywords(Arrays.help_keywords_build_project),
            fileName = "build-project.md",
            summary = Strings.help_doc_build_project_summary.strOr(context),
            order = 0
        ),
        HelpDocument(
            id = "cmake-guide",
            title = Strings.help_doc_cmake_guide_title.strOr(context),
            category = HelpCategory.COMPILER,
            keywords = keywords(Arrays.help_keywords_cmake_guide),
            fileName = "cmake-guide.md",
            summary = Strings.help_doc_cmake_guide_summary.strOr(context),
            order = 1
        ),
        HelpDocument(
            id = "compiler-settings",
            title = Strings.help_doc_compiler_settings_title.strOr(context),
            category = HelpCategory.COMPILER,
            keywords = keywords(Arrays.help_keywords_compiler_settings),
            fileName = "compiler-settings.md",
            summary = Strings.help_doc_compiler_settings_summary.strOr(context),
            order = 2
        ),
        HelpDocument(
            id = "package-manager",
            title = Strings.help_doc_package_manager_title.strOr(context),
            category = HelpCategory.COMPILER,
            keywords = keywords(Arrays.help_keywords_package_manager),
            fileName = "package-manager.md",
            summary = Strings.help_doc_package_manager_summary.strOr(context),
            order = 3
        ),

        // 语言服务器
        HelpDocument(
            id = "lsp-overview",
            title = Strings.help_doc_lsp_overview_title.strOr(context),
            category = HelpCategory.LSP,
            keywords = keywords(Arrays.help_keywords_lsp_overview),
            fileName = "lsp-overview.md",
            summary = Strings.help_doc_lsp_overview_summary.strOr(context),
            order = 0
        ),
        HelpDocument(
            id = "lsp-settings",
            title = Strings.help_doc_lsp_settings_title.strOr(context),
            category = HelpCategory.LSP,
            keywords = keywords(Arrays.help_keywords_lsp_settings),
            fileName = "lsp-settings.md",
            summary = Strings.help_doc_lsp_settings_summary.strOr(context),
            order = 1
        ),
        HelpDocument(
            id = "remote-lsp-guide",
            title = Strings.help_doc_remote_lsp_guide_title.strOr(context),
            category = HelpCategory.LSP,
            keywords = keywords(Arrays.help_keywords_remote_lsp_guide),
            fileName = "remote-lsp-guide.md",
            summary = Strings.help_doc_remote_lsp_guide_summary.strOr(context),
            order = 2
        ),
        HelpDocument(
            id = "rsync-setup",
            title = Strings.help_doc_rsync_setup_title.strOr(context),
            category = HelpCategory.LSP,
            keywords = keywords(Arrays.help_keywords_rsync_setup),
            fileName = "rsync-setup.md",
            summary = Strings.help_doc_rsync_setup_summary.strOr(context),
            order = 3
        ),

        // 终端
        HelpDocument(
            id = "terminal-usage",
            title = Strings.help_doc_terminal_usage_title.strOr(context),
            category = HelpCategory.TERMINAL,
            keywords = keywords(Arrays.help_keywords_terminal_usage),
            fileName = "terminal-usage.md",
            summary = Strings.help_doc_terminal_usage_summary.strOr(context),
            order = 0
        ),
        HelpDocument(
            id = "terminal-settings",
            title = Strings.help_doc_terminal_settings_title.strOr(context),
            category = HelpCategory.TERMINAL,
            keywords = keywords(Arrays.help_keywords_terminal_settings),
            fileName = "terminal-settings.md",
            summary = Strings.help_doc_terminal_settings_summary.strOr(context),
            order = 1
        ),
        HelpDocument(
            id = "terminal-troubleshooting",
            title = Strings.help_doc_terminal_troubleshooting_title.strOr(context),
            category = HelpCategory.TERMINAL,
            keywords = keywords(Arrays.help_keywords_terminal_troubleshooting),
            fileName = "terminal-troubleshooting.md",
            summary = Strings.help_doc_terminal_troubleshooting_summary.strOr(context),
            order = 2
        ),

        // Git
        HelpDocument(
            id = "git-basics",
            title = Strings.help_doc_git_basics_title.strOr(context),
            category = HelpCategory.GIT,
            keywords = keywords(Arrays.help_keywords_git_basics),
            fileName = "git-basics.md",
            summary = Strings.help_doc_git_basics_summary.strOr(context),
            order = 0
        ),
        HelpDocument(
            id = "git-settings",
            title = Strings.help_doc_git_settings_title.strOr(context),
            category = HelpCategory.GIT,
            keywords = keywords(Arrays.help_keywords_git_settings),
            fileName = "git-settings.md",
            summary = Strings.help_doc_git_settings_summary.strOr(context),
            order = 1
        ),

        // 调试
        HelpDocument(
            id = "debug-guide",
            title = Strings.help_doc_debug_guide_title.strOr(context),
            category = HelpCategory.DEBUG,
            keywords = keywords(Arrays.help_keywords_debug_guide),
            fileName = "debug-guide.md",
            summary = Strings.help_doc_debug_guide_summary.strOr(context),
            order = 0
        ),

        // 高级功能
        HelpDocument(
            id = "keyboard-shortcuts",
            title = Strings.help_doc_keyboard_shortcuts_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_keyboard_shortcuts),
            fileName = "keyboard-shortcuts.md",
            summary = Strings.help_doc_keyboard_shortcuts_summary.strOr(context),
            order = 0
        ),
        HelpDocument(
            id = "appearance-settings",
            title = Strings.help_doc_appearance_settings_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_appearance_settings),
            fileName = "appearance-settings.md",
            summary = Strings.help_doc_appearance_settings_summary.strOr(context),
            order = 1
        ),
        HelpDocument(
            id = "keyboard-settings",
            title = Strings.help_doc_keyboard_settings_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_keyboard_settings),
            fileName = "keyboard-settings.md",
            summary = Strings.help_doc_keyboard_settings_summary.strOr(context),
            order = 2
        ),
        HelpDocument(
            id = "plugin-manifest-compatibility",
            title = Strings.help_doc_plugin_manifest_compatibility_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_plugin_manifest_compatibility),
            fileName = "plugin-manifest-compatibility.md",
            summary = Strings.help_doc_plugin_manifest_compatibility_summary.strOr(context),
            order = 3
        ),
        HelpDocument(
            id = "plugin-script-api",
            title = Strings.help_doc_plugin_script_api_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_plugin_script_api),
            fileName = "plugin-script-api.md",
            summary = Strings.help_doc_plugin_script_api_summary.strOr(context),
            order = 4
        ),
        HelpDocument(
            id = "plugin-panels-events",
            title = Strings.help_doc_plugin_panels_events_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_plugin_panels_events),
            fileName = "plugin-panels-events.md",
            summary = Strings.help_doc_plugin_panels_events_summary.strOr(context),
            order = 5
        ),
        HelpDocument(
            id = "plugin-lsp-troubleshooting",
            title = Strings.help_doc_plugin_lsp_troubleshooting_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_plugin_lsp_troubleshooting),
            fileName = "plugin-lsp-troubleshooting.md",
            summary = Strings.help_doc_plugin_lsp_troubleshooting_summary.strOr(context),
            order = 6
        ),
        HelpDocument(
            id = "plugin-testing-recovery",
            title = Strings.help_doc_plugin_testing_recovery_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_plugin_testing_recovery),
            fileName = "plugin-testing-recovery.md",
            summary = Strings.help_doc_plugin_testing_recovery_summary.strOr(context),
            order = 7
        ),
        HelpDocument(
            id = "plugins-settings",
            title = Strings.help_doc_plugin_settings_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_plugin_settings),
            fileName = "plugins-settings.md",
            summary = Strings.help_doc_plugin_settings_summary.strOr(context),
            order = 8
        ),
        HelpDocument(
            id = "about-and-logs",
            title = Strings.help_doc_about_and_logs_title.strOr(context),
            category = HelpCategory.ADVANCED,
            keywords = keywords(Arrays.help_keywords_about_and_logs),
            fileName = "about-and-logs.md",
            summary = Strings.help_doc_about_and_logs_summary.strOr(context),
            order = 9
        ),

        // 常见问题
        HelpDocument(
            id = "known-issues",
            title = Strings.help_doc_known_issues_title.strOr(context),
            category = HelpCategory.FAQ,
            keywords = keywords(Arrays.help_keywords_known_issues),
            fileName = "known-issues.md",
            summary = Strings.help_doc_known_issues_summary.strOr(context),
            order = 0
        )
    )

    /**
     * 获取所有文档
     */
    fun getAllDocuments(): List<HelpDocument> = documents.sortedWith(
        compareBy({ it.category.ordinal }, { it.order })
    )

    /**
     * 按分类获取文档
     */
    fun getDocumentsByCategory(category: HelpCategory): List<HelpDocument> = documents.filter { it.category == category }.sortedBy { it.order }

    /**
     * 获取所有分类
     */
    fun getAllCategories(): List<HelpCategory> = HelpCategory.entries

    /**
     * 根据 ID 获取文档
     */
    fun getDocumentById(id: String): HelpDocument? = documents.find { it.id == id }

    /**
     * 根据 Markdown 链接目标解析帮助文档。
     *
     * 支持：
     * - `plugin-quick-start.md`
     * - `./plugin-quick-start.md`
     * - `help/plugin-quick-start.md`
     * - `plugin-quick-start.md#section`
     */
    fun resolveDocumentByLinkTarget(linkTarget: String): HelpDocument? {
        val normalizedFileName = normalizeHelpLinkTarget(linkTarget) ?: return null
        return documents.find { document ->
            document.fileName.equals(normalizedFileName, ignoreCase = true)
        }
    }

    /**
     * 按站内 Markdown 链接加载文档。
     *
     * 教程页和帮助页共享同一套本地化、UTF-8、缓存与缺失资源回退规则，避免教程页
     * 直接读取固定 asset 后在英文环境或资源变更时显示空白。
     */
    suspend fun loadDocumentContentByLinkTarget(linkTarget: String): Result<String> {
        val document = resolveDocumentByLinkTarget(linkTarget)
            ?: return Result.failure(IOException("Unknown help document: $linkTarget"))
        return loadDocumentContent(document)
    }

    /**
     * 加载文档内容
     */
    suspend fun loadDocumentContent(document: HelpDocument): Result<String> = withContext(Dispatchers.IO) {
        val cacheKey = contentCacheKey(document)
        // 检查缓存
        contentCache[cacheKey]?.let {
            return@withContext Result.success(it)
        }

        try {
            val sanitized = readLocalizedMarkdown(document)
            contentCache[cacheKey] = sanitized
            Result.success(sanitized)
        } catch (e: IOException) {
            // 如果 assets 中没有，尝试返回占位内容
            val placeholder = sanitizeHelpMarkdown(generatePlaceholderContent(document))
            Result.success(placeholder)
        }
    }

    /**
     * 搜索文档
     *
     * @param query 搜索关键词
     * @return 匹配的文档列表，按相关性排序
     */
    suspend fun search(query: String): List<HelpSearchResult> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()

        val queryLower = query.lowercase().trim()
        val queryWords = queryLower.split(Regex("\\s+"))

        documents.mapNotNull { doc ->
            var score = 0f

            // 标题匹配（权重最高）
            if (doc.title.lowercase().contains(queryLower)) {
                score += 10f
            }
            queryWords.forEach { word ->
                if (doc.title.lowercase().contains(word)) {
                    score += 3f
                }
            }

            // 关键词匹配
            doc.keywords.forEach { keyword ->
                if (keyword.lowercase().contains(queryLower)) {
                    score += 5f
                }
                queryWords.forEach { word ->
                    if (keyword.lowercase().contains(word)) {
                        score += 2f
                    }
                }
            }

            // 摘要匹配
            if (doc.summary.lowercase().contains(queryLower)) {
                score += 3f
            }
            queryWords.forEach { word ->
                if (doc.summary.lowercase().contains(word)) {
                    score += 1f
                }
            }

            // 内容匹配（如果已缓存）
            contentCache[contentCacheKey(doc)]?.let { content ->
                val contentLower = content.lowercase()
                if (contentLower.contains(queryLower)) {
                    score += 2f
                    // 提取匹配的上下文
                    val matchIndex = contentLower.indexOf(queryLower)
                    if (matchIndex >= 0) {
                        val start = maxOf(0, matchIndex - 50)
                        val end = minOf(content.length, matchIndex + queryLower.length + 50)
                        val matchedContent = "..." + content.substring(start, end).trim() + "..."
                        if (score > 0) {
                            return@mapNotNull HelpSearchResult(doc, matchedContent, score)
                        }
                    }
                }
            }

            if (score > 0) {
                HelpSearchResult(doc, "", score)
            } else {
                null
            }
        }.sortedByDescending { it.relevanceScore }
    }

    /**
     * 预加载所有文档内容（用于搜索优化）
     */
    suspend fun preloadAllContent() = withContext(Dispatchers.IO) {
        documents.forEach { doc ->
            val cacheKey = contentCacheKey(doc)
            if (!contentCache.containsKey(cacheKey)) {
                try {
                    contentCache[cacheKey] = readLocalizedMarkdown(doc)
                } catch (_: IOException) {
                    // 忽略加载失败的文档
                }
            }
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        contentCache.clear()
    }

    private fun contentCacheKey(document: HelpDocument): String =
        "${currentLanguageCode()}:${document.id}"

    private fun currentLanguageCode(): String {
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault().language else locales[0].language
    }

    @Throws(IOException::class)
    private fun readLocalizedMarkdown(document: HelpDocument): String {
        var lastError: IOException? = null
        HelpAssetPathResolver.candidatePaths(document.fileName, currentLanguageCode()).forEach { assetPath ->
            try {
                val content = context.assets.open(assetPath)
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader -> reader.readText() }
                return sanitizeHelpMarkdown(content)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("No help asset candidate for ${document.fileName}")
    }

    private fun normalizeHelpLinkTarget(linkTarget: String): String? {
        val trimmedTarget = linkTarget.trim()
        if (trimmedTarget.isBlank() || trimmedTarget.startsWith("#")) {
            return null
        }

        // 带 scheme 的链接视为外链，例如 https://、mailto:。
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmedTarget)) {
            return null
        }

        val path = trimmedTarget
            .substringBefore('#')
            .substringBefore('?')
            .removePrefix("./")
            .removePrefix("/")
            .removePrefix("help/")
            .substringAfterLast('/')

        return path.takeIf { it.endsWith(".md", ignoreCase = true) }
    }

    /**
     * 生成占位内容（当文档文件不存在时）
     */
    private fun generatePlaceholderContent(document: HelpDocument): String {
        val tipLabel = Strings.help_placeholder_tip_label.strOr(context)
        val tipBody = Strings.help_placeholder_tip_body.strOr(context)
        val tipContact = Strings.help_placeholder_tip_contact.strOr(context)
        val relatedTitle = Strings.help_placeholder_related_topics_title.strOr(context)

        return """
# ${document.title}

${document.summary}

---

> **$tipLabel**：$tipBody
>
> $tipContact

## $relatedTitle

${documents.filter { it.category == document.category && it.id != document.id }
            .take(3)
            .joinToString("\n") { "- ${it.title}" }}
        """.trimIndent()
    }
}
