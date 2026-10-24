package com.wuxianggujun.tinaide.ui.sdl

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.elf.ElfDynamicSymbolReader
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File

/**
 * SDL 图形宿主用 `dlsym("SDL_main")` 拉起用户主库，因此产物必须导出该符号。
 *
 * 常见踩坑：SDL3 的 `<SDL3/SDL.h>` **不会**自动包含 `SDL_main.h`（SDL2 的会），
 * 于是只写了 `#include <SDL3/SDL.h>` 的项目能编译、能链接成 `.so`，却没有 `SDL_main`。
 * 这种产物启动时 SDL 线程立即结束、Activity 直接 finish，表现为“屏幕一闪就没了”。
 */
internal object SdlEntryPointResolver {
    private const val SDL_MAIN_SYMBOL = "SDL_main"
    private const val PLAIN_MAIN_SYMBOL = "main"

    /** 产物入口符号信息；无法读取动态符号表时为 null，调用方不应据此拦截启动。 */
    data class EntryPointInfo(
        val hasSdlMain: Boolean,
        val hasPlainMain: Boolean,
    )

    fun resolve(
        mainLibrary: File,
        symbolReader: (File) -> Set<String>? = ElfDynamicSymbolReader::readDefinedSymbols,
    ): EntryPointInfo? {
        val symbols = runCatching { symbolReader(mainLibrary) }.getOrNull() ?: return null
        return EntryPointInfo(
            hasSdlMain = SDL_MAIN_SYMBOL in symbols,
            hasPlainMain = PLAIN_MAIN_SYMBOL in symbols,
        )
    }

    /**
     * 把“产物缺 SDL_main”翻译成告诉用户怎么改的提示。
     *
     * 不做自动兜底（不帮用户注入入口），直接把原因说出来：
     * 符号表不可读（[info] == null）或入口存在时返回 null，放行启动。
     */
    fun entryPointErrorMessage(context: Context, info: EntryPointInfo?, sdlMajor: Int): String? {
        if (info == null || info.hasSdlMain) return null
        return when (sdlMajor) {
            3 -> Strings.sdl_runtime_error_missing_entry_sdl3.strOr(context)
            2 -> Strings.sdl_runtime_error_missing_entry_sdl2.strOr(context)
            else -> if (info.hasPlainMain) {
                Strings.sdl_runtime_error_missing_entry_generic.strOr(context)
            } else {
                Strings.sdl_runtime_error_no_entry_at_all.strOr(context)
            }
        }
    }
}
