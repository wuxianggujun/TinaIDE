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
    val signingConfig: ApkSigningConfig = ApkSigningConfig.Debug
)
