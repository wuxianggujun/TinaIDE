package com.wuxianggujun.tinaide.ui.sdl

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * SDL 资源相对路径重定向的 JNI 桥。
 *
 * 只在图形运行进程（:sdl2 / :sdl）里、加载完 libSDL*.so 之后、SDL_main 之前调用
 * [install]，安装进程内的 fopen/fopen64 GOT hook（native 侧 sdl_asset_redirect）。
 *
 * 背景见 native `sdl_asset_redirect.cpp`：SDL_RWFromFile 把相对资源解析成
 * `getFilesDir()/<file>`，既不在项目目录、也不落 cwd；hook 据此把 filesDir 下不存在、
 * 但项目根下存在的只读资源重定向回项目根。
 */
object SdlAssetRedirect {
    private const val TAG = "SdlAssetRedirect"
    private const val LIBRARY_NAME = "tina_sdl_asset_redirect"

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var installed = false

    /**
     * 安装资源重定向 hook。
     *
     * @param context 运行宿主 Activity（用其 getFilesDir 规范路径作为 SDL 内部存储前缀）。
     * @param projectRoot 项目根绝对路径（来自 [com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase.RUNTIME_ASSET_ROOT_ENV]）。
     * @return true 表示 hook 已安装（或此前已安装）；false 表示跳过或失败，SDL 仍走原生 filesDir/APK asset 逻辑。
     */
    fun install(context: Context, projectRoot: String): Boolean {
        val normalizedRoot = projectRoot.trim()
        if (normalizedRoot.isEmpty()) {
            Timber.tag(TAG).d("skip install: empty project root")
            return false
        }
        if (installed) return true
        if (!ensureLibraryLoaded()) return false

        // SDL 用 getFilesDir().getCanonicalPath() 作内部存储前缀，这里必须同样取
        // canonicalPath，否则 /data/user/0/<pkg> 与 /data/data/<pkg> 前缀无法匹配。
        val filesDir = runCatching { context.filesDir.canonicalPath }
            .getOrElse { context.filesDir.absolutePath }

        val projectRootPath = runCatching { File(normalizedRoot).canonicalPath }
            .getOrElse { normalizedRoot }

        return runCatching { nativeInstall(filesDir, projectRootPath) }
            .onFailure { Timber.tag(TAG).w(it, "nativeInstall threw") }
            .getOrDefault(false)
            .also { result ->
                installed = result
                Timber.tag(TAG).i(
                    "install result=%b filesDir=%s projectRoot=%s",
                    result,
                    filesDir,
                    projectRootPath,
                )
            }
    }

    private fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
        return runCatching { System.loadLibrary(LIBRARY_NAME) }
            .onFailure { Timber.tag(TAG).w(it, "failed to load %s", LIBRARY_NAME) }
            .isSuccess
            .also { libraryLoaded = it }
    }

    private external fun nativeInstall(filesDir: String, projectRoot: String): Boolean
}
