package com.wuxianggujun.tinaide.core.help

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 帮助文档数据类
 *
 * @param id 文档唯一标识
 * @param title 文档标题
 * @param category 文档分类
 * @param keywords 搜索关键词列表
 * @param fileName 文档文件名（相对于 assetDirectory）
 * @param summary 文档摘要（可选）
 * @param order 排序权重（数字越小越靠前）
 */
data class HelpDocument(
    val id: String,
    val title: String,
    val category: HelpCategory,
    val keywords: List<String> = emptyList(),
    val fileName: String,
    val summary: String = "",
    val order: Int = 0,
    /** 普通参考文档与实操课程正文分目录，避免教程章节混入帮助资源目录。 */
    val assetDirectory: String = "help",
)

/**
 * 帮助文档分类
 */
enum class HelpCategory(
    @param:StringRes @get:StringRes val displayNameRes: Int,
    @param:DrawableRes @get:DrawableRes val iconRes: Int
) {
    GETTING_STARTED(Strings.help_cat_getting_started, Drawables.ic_help_getting_started),
    EDITOR(Strings.help_cat_editor, Drawables.ic_help_editor),
    COMPILER(Strings.help_cat_compiler, Drawables.ic_help_compiler),
    LSP(Strings.help_cat_lsp, Drawables.ic_help_lsp),
    TERMINAL(Strings.help_cat_terminal, Drawables.ic_help_terminal),
    GIT(Strings.help_cat_git, Drawables.ic_help_git),
    DEBUG(Strings.help_cat_debug, Drawables.ic_help_debug),
    ADVANCED(Strings.help_cat_advanced, Drawables.ic_help_advanced),
    FAQ(Strings.help_cat_faq, Drawables.ic_help_faq)
}

/**
 * 搜索结果
 */
data class HelpSearchResult(
    val document: HelpDocument,
    val matchedContent: String = "",
    val relevanceScore: Float = 0f
)
