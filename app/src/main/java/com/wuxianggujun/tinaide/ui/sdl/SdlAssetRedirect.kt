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
    /**
     * logcat 标签，同时也是 [com.wuxianggujun.tinaide.ui.compose.components.GraphicalRuntimeOverlay]
     * 悬浮日志面板捕获的标签之一。改名必须两处同步。
     */
    const val LOG_TAG = "SdlAssetRedirect"

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
    fun install(context: Context, projectRoot: String, assetDirectory: String = ""): Boolean {
        val normalizedRoot = projectRoot.trim()
        if (normalizedRoot.isEmpty()) {
            Timber.tag(LOG_TAG).d("skip install: empty project root")
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

        val root = File(projectRootPath)
        val assets = if (assetDirectory.isBlank()) File(root, "assets").takeIf { it.isDirectory } ?: root
            else File(assetDirectory)
        val assetsPath = runCatching { assets.canonicalFile }.getOrElse {
            Timber.tag(LOG_TAG).w(it, "Invalid asset directory")
            return false
        }
        if (!root.isDirectory || !assetsPath.isDirectory || !assetsPath.toPath().startsWith(root.toPath())) {
            Timber.tag(LOG_TAG).w("Asset directory is missing or outside the project")
            return false
        }

        return runCatching { nativeInstall(filesDir.toByteArray(Charsets.UTF_8),
            projectRootPath.toByteArray(Charsets.UTF_8), assetsPath.path.toByteArray(Charsets.UTF_8)) }
            .onFailure { Timber.tag(LOG_TAG).w(it, "nativeInstall threw") }
            .getOrDefault(false)
            .also { result ->
                installed = result
                Timber.tag(LOG_TAG).i(
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
            .onFailure { Timber.tag(LOG_TAG).w(it, "failed to load %s", LIBRARY_NAME) }
            .isSuccess
            .also { libraryLoaded = it }
    }

    // JNI's GetStringUTFChars uses modified UTF-8 and corrupts supplementary characters
    // in filesystem paths. Pass ordinary UTF-8 bytes, including emoji directory names.
    private external fun nativeInstall(filesDir: ByteArray, projectRoot: ByteArray, assetDirectory: ByteArray): Boolean
}
