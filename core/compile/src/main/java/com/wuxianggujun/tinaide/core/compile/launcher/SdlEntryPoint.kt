package com.wuxianggujun.tinaide.core.compile.launcher

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.elf.ElfDynamicSymbolReader
import com.wuxianggujun.tinaide.core.compile.elf.ElfNeededLibraryReader
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File

/**
 * SDL 图形宿主用 `dlsym("SDL_main")` 拉起用户主库，因此产物必须导出该符号。
 *
 * 常见踩坑：SDL3 的 `<SDL3/SDL.h>` 不会自动包含 `SDL_main.h`（SDL2 的会），
 * 于是只写了 `#include <SDL3/SDL.h>` 的项目能编译、能链接成 `.so`，却没有 `SDL_main`。
 * 这种产物启动时 SDL 线程立即结束、Activity 直接 finish，表现为“屏幕一闪就没了”。
 */
internal object SdlEntryPoint {
    private const val SDL_MAIN_SYMBOL = "SDL_main"
    private const val PLAIN_MAIN_SYMBOL = "main"

    /**
     * @return null 表示入口存在或符号表不可读（不做拦截）；非 null 为给用户的修复提示。
     */
    fun entryPointErrorMessage(
        context: Context,
        mainLibrary: File,
    ): String? {
        val symbols = runCatching { ElfDynamicSymbolReader.readDefinedSymbols(mainLibrary) }
            .getOrNull() ?: return null
        if (SDL_MAIN_SYMBOL in symbols) return null

        return when (detectSdlMajor(mainLibrary)) {
            3 -> Strings.sdl_runtime_error_missing_entry_sdl3.strOr(context)
            2 -> Strings.sdl_runtime_error_missing_entry_sdl2.strOr(context)
            else -> if (PLAIN_MAIN_SYMBOL in symbols) {
                Strings.sdl_runtime_error_missing_entry_generic.strOr(context)
            } else {
                Strings.sdl_runtime_error_no_entry_at_all.strOr(context)
            }
        }
    }

    /** 依产物 DT_NEEDED 区分 SDL2/SDL3，给出对应的头文件指引；检测不出返回 null。 */
    private fun detectSdlMajor(mainLibrary: File): Int? {
        val needed = runCatching { ElfNeededLibraryReader.read(mainLibrary) }.getOrNull()
            ?: return null
        return when {
            needed.any { it.startsWith("libSDL3") } -> 3
            needed.any { it.startsWith("libSDL2") } -> 2
            else -> null
        }
    }
}
