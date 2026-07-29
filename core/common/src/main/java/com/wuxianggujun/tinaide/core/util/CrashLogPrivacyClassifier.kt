package com.wuxianggujun.tinaide.core.util

/**
 * 用户运行容器与崩溃日志隐私分类器。
 *
 * 规则集中在这里，避免 SDL / 普通 Native / NDK 的判断散落在上传、导出和进程初始化逻辑中。
 */
object CrashLogPrivacyClassifier {
    private val userRuntimeProcessSuffixes = setOf(":gui", ":sdl", ":sdl2")

    fun isHostAppProcess(packageName: String, processName: String): Boolean = packageName.isNotBlank() && processName == packageName

    fun isUserRuntimeProcess(packageName: String, processName: String): Boolean {
        if (packageName.isBlank() || processName.isBlank()) return false
        return userRuntimeProcessSuffixes.any { suffix -> processName == packageName + suffix }
    }

    fun shouldUploadCrashForProcess(packageName: String, processName: String): Boolean = isHostAppProcess(packageName, processName)

    fun isUserRuntimeCrash(packageName: String, crashText: String): Boolean {
        if (packageName.isBlank() || crashText.isBlank()) return false
        return userRuntimeProcessSuffixes.any { suffix ->
            crashText.contains(">>> $packageName$suffix <<<")
        } ||
            containsRunBinPath(packageName, crashText)
    }

    fun containsRunBinPath(packageName: String, text: String): Boolean {
        if (text.isBlank()) return false
        return buildList {
            add("/files/run-bin/")
            if (packageName.isNotBlank()) {
                add("/data/data/$packageName/files/run-bin/")
                add("/data/user/0/$packageName/files/run-bin/")
            }
        }.any { marker -> text.contains(marker) }
    }
}
