package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

data class TinaAppVersionInfo(
    val versionCode: Int,
    val versionName: String,
)

class TinaAppVersioningExtension internal constructor(
    val versionPropsFile: File,
    val currentVersion: TinaAppVersionInfo,
    /**
     * 本次构建的唯一标识（`<gitShortSha>-<buildTimestamp>`）。
     *
     * 同一次 Gradle 运行内取值固定，因此 BuildConfig 与 mapping 归档目录拿到的是同一个值，
     * 崩溃墓碑里的 "App version" 可以反查到唯一匹配的 mapping。
     */
    val buildId: String,
) {
    val versionCode: Int
        get() = currentVersion.versionCode

    val versionName: String
        get() = currentVersion.versionName
}

/**
 * 生成 `<gitShortSha>-<buildTimestamp>` 形式的构建标识。
 *
 * git 不可用时退化为 `nogit-<timestamp>`，仍保证不同构建互不混淆。
 * git 目录可能在工作树之外（submodule / shallow clone），失败一律走兜底。
 */
internal fun resolveBuildId(rootDir: File): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    val shortSha = runCatching {
        val process = ProcessBuilder("git", "-C", rootDir.absolutePath, "rev-parse", "--short=10", "HEAD")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.inputStream.bufferedReader().readText().trim().also {
            process.waitFor()
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() && it.matches(Regex("[0-9a-fA-F]{4,40}")) }
    return "${shortSha ?: "nogit"}-$timestamp"
}

internal fun ensureVersionProps(file: File) {
    if (!file.exists()) {
        file.writeText(
            """
            versionCode=1
            versionName=1.0.0
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }
}

internal fun readAppVersionInfo(file: File): TinaAppVersionInfo {
    ensureVersionProps(file)

    val props = Properties().apply {
        file.inputStream().use(::load)
    }
    val versionInfo = TinaAppVersionInfo(
        versionCode = props.getProperty("versionCode")?.toIntOrNull() ?: 1,
        versionName = props.getProperty("versionName") ?: "1.0.0",
    )
    validateAppVersionInfo(versionInfo, file)
    return versionInfo
}

/**
 * 项目历史版本码规则：minor * 100 + patch + 1。
 * 例如 0.15.6 对应 1507，保持与既有安装升级链路兼容。
 */
internal fun expectedVersionCodeFor(versionName: String): Int? {
    val numericPart = versionName.substringBefore("-")
    val numbers = numericPart.split(".")
        .filter { it.isNotBlank() }
        .map { it.toIntOrNull() ?: return null }
    val minor = numbers.getOrElse(1) { 0 }
    val patch = numbers.getOrElse(2) { 0 }
    if (minor < 0 || patch < 0) return null
    return minor * 100 + patch + 1
}

private fun validateAppVersionInfo(versionInfo: TinaAppVersionInfo, file: File) {
    val expectedVersionCode = expectedVersionCodeFor(versionInfo.versionName) ?: return
    if (versionInfo.versionCode == expectedVersionCode) return

    throw GradleException(
        "version.properties mismatch: versionName=${versionInfo.versionName} " +
            "expects versionCode=$expectedVersionCode, " +
            "but found versionCode=${versionInfo.versionCode} in ${file.path}",
    )
}

internal fun bumpPatchVersionString(version: String): String {
    val (numericPart, suffix) = version.split("-", limit = 2).let { parts ->
        parts[0] to parts.getOrNull(1)?.let { "-$it" }.orEmpty()
    }
    val numbers = numericPart.split(".")
        .filter { it.isNotBlank() }
        .map { it.toIntOrNull() ?: 0 }
        .toMutableList()
    if (numbers.isEmpty()) {
        numbers += 0
    }
    while (numbers.size < 3) {
        numbers += 0
    }

    var major = numbers.getOrElse(0) { 0 }
    var minor = numbers.getOrElse(1) { 0 }
    var patch = numbers.getOrElse(2) { 0 }

    patch += 1

    while (patch >= 100) {
        patch -= 100
        minor += 1
    }
    while (minor >= 100) {
        minor -= 100
        major += 1
    }

    val newNumbers = mutableListOf(major, minor, patch)
    if (numbers.size > 3) {
        newNumbers += numbers.subList(3, numbers.size)
    }
    return newNumbers.joinToString(".") + suffix
}

internal fun autoIncrementAppVersion(file: File, logger: Logger): TinaAppVersionInfo {
    val currentVersion = readAppVersionInfo(file)
    val nextVersionName = bumpPatchVersionString(currentVersion.versionName)
    val nextVersion = TinaAppVersionInfo(
        versionCode = expectedVersionCodeFor(nextVersionName) ?: (currentVersion.versionCode + 1),
        versionName = nextVersionName,
    )

    Properties().apply {
        setProperty("versionCode", nextVersion.versionCode.toString())
        setProperty("versionName", nextVersion.versionName)
        file.outputStream().use { store(it, "Auto-managed app version") }
    }

    logger.lifecycle(
        "Auto-incremented app version to ${nextVersion.versionName} (${nextVersion.versionCode})",
    )
    return nextVersion
}

internal fun isReleaseVersionBumpTask(taskName: String): Boolean =
    listOf("assemble", "bundle", "install").any { keyword ->
        taskName.contains(keyword, ignoreCase = true)
    } && taskName.contains("release", ignoreCase = true)
