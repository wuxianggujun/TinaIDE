package com.wuxianggujun.tinaide.core.apkbuilder

import java.io.File

data class ApkBuildConfig(
    val soFiles: List<File>,
    val targetAbis: List<String>,
    val executableFile: File? = null,
    val packageName: String,
    val appName: String,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val requestedPermissions: List<String> = emptyList(),
    val templateType: ApkTemplateType,
    val templateFile: File? = null,
    val sdlLibraryPath: File? = null,
    val preloadLibraries: List<File> = emptyList(),
    val iconFile: File? = null,
    val orientation: String = "unspecified",
    /**
     * 需要打进 APK `assets/` 的项目资源目录（递归）。
     *
     * 约定来源是项目根下的 `assets/` 目录；目录内容会映射为 `assets/<相对路径>`。
     * 详见 [ProjectAssets]。
     */
    val assetDirectories: List<File> = emptyList(),
    /**
     * 是否额外把资源镜像到 `assets/assets/<相对路径>`（注意 Kotlin 块注释可嵌套，
     * 这里不能写通配符形式）。
     *
     * 打开后 `SDL_IOFromFile("font.ttf")` 与 `SDL_IOFromFile("assets/font.ttf")`
     * 两种写法在导出 APK 里都能命中，代价是 APK 内资源体积翻倍。
     */
    val mirrorProjectAssetsUnderAssetsNamespace: Boolean = true,
    val signingConfig: ApkSigningConfig = ApkSigningConfig.Debug
)
