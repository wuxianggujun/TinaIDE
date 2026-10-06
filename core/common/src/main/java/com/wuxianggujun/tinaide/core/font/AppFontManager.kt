package com.wuxianggujun.tinaide.core.font

import android.content.Context
import android.graphics.Typeface
import com.wuxianggujun.tinaide.core.editorapi.EditorFontSize
import java.io.File
import timber.log.Timber

/**
 * 应用字体管理器
 *
 * 管理应用使用的等宽字体，供终端和代码编辑器使用。
 * 内置字体：JetBrains Mono Nerd Font（包含 Powerline 图标支持）
 */
object AppFontManager {

    private const val TAG = "AppFontManager"

    /** 内置字体文件路径 */
    private const val BUILTIN_FONT_PATH = "fonts/JetBrainsMonoNerdFont-Regular.ttf"

    /** 内置字体显示名称 */
    const val BUILTIN_FONT_NAME = "JetBrains Mono Nerd Font"

    // ========== 统一的字体大小常量 ==========

    /** 最小字体大小（sp） */
    const val MIN_FONT_SIZE = EditorFontSize.MIN_SP

    /** 最大字体大小（sp） */
    const val MAX_FONT_SIZE = EditorFontSize.MAX_SP

    /** 终端最大字体大小（sp）- 终端通常不需要太大的字体 */
    const val TERMINAL_MAX_FONT_SIZE = 32f

    /** 编辑器默认字体大小（sp） */
    const val DEFAULT_EDITOR_FONT_SIZE = EditorFontSize.DEFAULT_SP

    /** 终端默认字体大小（sp） */
    const val DEFAULT_TERMINAL_FONT_SIZE = 13f

    // 缓存已加载的字体
    private var cachedTypeface: Typeface? = null

    // 缓存自定义字体
    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontPath: String? = null

    /**
     * 获取应用使用的等宽字体
     *
     * @param context Android Context
     * @return 等宽字体 Typeface，如果内置字体加载失败则返回系统等宽字体
     */
    fun getMonospaceTypeface(context: Context): Typeface {
        cachedTypeface?.let { return it }

        val typeface = try {
            Typeface.createFromAsset(context.assets, BUILTIN_FONT_PATH).also {
                Timber.tag(TAG).i("Successfully loaded built-in font: $BUILTIN_FONT_NAME")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load built-in font from assets, falling back to system monospace")
            Typeface.MONOSPACE
        }

        cachedTypeface = typeface
        return typeface
    }

    /**
     * 从文件路径加载自定义字体
     *
     * @param fontPath 字体文件路径
     * @return 字体 Typeface，如果加载失败则返回 null
     */
    fun loadCustomFont(fontPath: String): Typeface? {
        // 检查缓存
        if (fontPath == cachedCustomFontPath && cachedCustomTypeface != null) {
            return cachedCustomTypeface
        }

        return try {
            val file = File(fontPath)
            if (!file.exists()) {
                Timber.tag(TAG).w("Custom font file not found: $fontPath")
                return null
            }

            val typeface = Typeface.createFromFile(file)
            // 缓存成功加载的字体
            cachedCustomFontPath = fontPath
            cachedCustomTypeface = typeface
            Timber.tag(TAG).i("Successfully loaded custom font: ${file.name}")
            typeface
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load custom font: $fontPath")
            null
        }
    }

    /**
     * 验证字体文件是否有效
     *
     * @param fontPath 字体文件路径
     * @return true 如果字体文件有效，false 否则
     */
    fun isValidFontFile(fontPath: String): Boolean {
        return try {
            val file = File(fontPath)
            if (!file.exists()) {
                Timber.tag(TAG).w("Font file does not exist: $fontPath")
                return false
            }

            // 检查文件扩展名
            val ext = file.extension.lowercase()
            if (ext !in listOf("ttf", "otf", "ttc")) {
                Timber.tag(TAG).w("Invalid font file extension: $ext")
                return false
            }

            // 尝试加载字体以验证其有效性
            Typeface.createFromFile(file)
            Timber.tag(TAG).i("Font file validation successful: ${file.name}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Font file validation failed: $fontPath")
            false
        }
    }

    /**
     * 清除字体缓存
     */
    fun clearCache() {
        cachedTypeface = null
        cachedCustomTypeface = null
        cachedCustomFontPath = null
        Timber.tag(TAG).d("Font cache cleared")
    }

    /**
     * 检查内置字体是否可用
     */
    fun hasBuiltInFont(context: Context): Boolean = try {
        context.assets.open(BUILTIN_FONT_PATH).use { true }
    } catch (e: Exception) {
        false
    }

    /**
     * 获取当前字体名称
     */
    fun getCurrentFontName(context: Context): String = if (hasBuiltInFont(context)) {
        BUILTIN_FONT_NAME
    } else {
        "System Monospace"
    }

    /**
     * 约束字体大小到有效范围
     *
     * @param size 原始字体大小
     * @param maxSize 最大字体大小（默认使用 MAX_FONT_SIZE）
     * @return 约束后的字体大小
     */
    fun clampFontSize(size: Float, maxSize: Float = MAX_FONT_SIZE): Float = size.coerceIn(MIN_FONT_SIZE, maxSize)
}
